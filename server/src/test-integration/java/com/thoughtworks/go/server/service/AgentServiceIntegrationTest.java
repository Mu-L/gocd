/*
 * Copyright Thoughtworks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.thoughtworks.go.server.service;

import com.thoughtworks.go.config.*;
import com.thoughtworks.go.config.exceptions.BadRequestException;
import com.thoughtworks.go.config.exceptions.EntityType;
import com.thoughtworks.go.config.exceptions.RecordNotFoundException;
import com.thoughtworks.go.config.exceptions.UnprocessableEntityException;
import com.thoughtworks.go.config.remote.ConfigRepoConfig;
import com.thoughtworks.go.config.remote.PartialConfig;
import com.thoughtworks.go.config.remote.RepoConfigOrigin;
import com.thoughtworks.go.domain.AgentInstance;
import com.thoughtworks.go.domain.AgentRuntimeStatus;
import com.thoughtworks.go.domain.AgentStatus;
import com.thoughtworks.go.domain.ConfigErrors;
import com.thoughtworks.go.domain.exception.UnregisteredAgentException;
import com.thoughtworks.go.helper.AgentMother;
import com.thoughtworks.go.helper.PartialConfigMother;
import com.thoughtworks.go.listener.AgentStatusChangeListener;
import com.thoughtworks.go.remote.AgentIdentifier;
import com.thoughtworks.go.server.dao.DatabaseAccessHelper;
import com.thoughtworks.go.server.domain.AgentInstances;
import com.thoughtworks.go.server.messaging.SendEmailMessage;
import com.thoughtworks.go.server.messaging.notifications.AgentStatusChangeNotifier;
import com.thoughtworks.go.server.persistence.AgentDao;
import com.thoughtworks.go.server.util.UuidGenerator;
import com.thoughtworks.go.serverhealth.ServerHealthService;
import com.thoughtworks.go.util.GoConfigFileHelper;
import com.thoughtworks.go.util.ReflectionUtil;
import com.thoughtworks.go.util.SystemEnvironment;
import com.thoughtworks.go.util.SystemUtil;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static com.thoughtworks.go.helper.AgentInstanceMother.*;
import static com.thoughtworks.go.server.service.AgentRuntimeInfo.fromServer;
import static com.thoughtworks.go.util.SystemUtil.currentWorkingDirectory;
import static com.thoughtworks.go.util.TriState.*;
import static java.lang.String.format;
import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;
import static java.util.stream.StreamSupport.stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = {
        "classpath:/applicationContext-global.xml",
        "classpath:/applicationContext-dataLocalAccess.xml",
        "classpath:/testPropertyConfigurer.xml",
        "classpath:/spring-all-servlet.xml",
})
public class AgentServiceIntegrationTest {
    @Autowired
    private GoConfigDao goConfigDao;
    @Autowired
    private EnvironmentConfigService environmentConfigService;
    @Autowired
    private AgentService agentService;
    @Autowired
    private GoConfigService goConfigService;
    @Autowired
    private CachedGoConfig cachedGoConfig;
    @Autowired
    private ServerHealthService serverHealthService;
    @Autowired
    private AgentDao agentDao;
    @Autowired
    private DatabaseAccessHelper dbHelper;

    private static final GoConfigFileHelper CONFIG_HELPER = new GoConfigFileHelper();
    private static final String UUID = "uuid";
    private static final String UUID2 = "uuid2";
    private static final String UUID3 = "uuid3";

    private static final List<String> emptyStrList = emptyList();

    @BeforeEach
    public void setUp() throws Exception {
        CONFIG_HELPER.usingCruiseConfigDao(goConfigDao);
        CONFIG_HELPER.onSetUp();
        dbHelper.onSetUp();
        cachedGoConfig.clearListeners();
        agentDao.clearListeners();
        agentService.clearAll();
        agentService.initialize();
        environmentConfigService.initialize();
    }

    @AfterEach
    public void tearDown() throws Exception {
        new SystemEnvironment().setProperty("agent.connection.timeout", "300");
        dbHelper.onTearDown();
        cachedGoConfig.clearListeners();
        agentService.clearAll();
        CONFIG_HELPER.onTearDown();
    }

    private AgentService newAgentService(AgentInstances agentInstances) {
        return new AgentService(new SystemEnvironment(), agentInstances, agentDao,
                new UuidGenerator(), serverHealthService, agentStatusChangeNotifier());
    }

    @Nested
    @ContextConfiguration(locations = {"classpath:/applicationContext-global.xml", "classpath:/applicationContext-dataLocalAccess.xml",
            "classpath:/testPropertyConfigurer.xml", "classpath:/spring-all-servlet.xml"})
    class DeleteAgents {
        @Test
        void onlyDisabledAgentsShouldBeAllowedToBeDeleted() {
            createAnIdleAgentAndDisableIt(UUID);
            createEnabledAgent(UUID2);

            assertThat(agentService.getAgentInstances().size()).isEqualTo(2);
            assertDoesNotThrow(() -> agentService.deleteAgents(List.of(UUID)));

            UnprocessableEntityException e = assertThrows(UnprocessableEntityException.class, () -> agentService.deleteAgents(List.of(UUID2)));
            assertThat(e.getMessage()).isEqualTo("Failed to delete an agent, as it is not in a disabled state or is still building.");

            assertThat(agentService.getAgentInstances().size()).isEqualTo(1);
            assertTrue(agentService.getAgentInstances().hasAgent(UUID2));
        }

        @Test
        void shouldDeleteAgentsOnlyWhenAllRequestedAgentsAreDisabled() {
            createAnIdleAgentAndDisableIt(UUID);
            createAnIdleAgentAndDisableIt(UUID2);
            createEnabledAgent(UUID3);

            assertThat(agentService.getAgentInstances().size()).isEqualTo(3);

            UnprocessableEntityException e = assertThrows(UnprocessableEntityException.class, () -> agentService.deleteAgents(List.of(UUID, UUID2, UUID3)));
            assertThat(e.getMessage()).isEqualTo("Could not delete any agents, as one or more agents might not be disabled or are still building.");

            assertThat(agentService.getAgentInstances().size()).isEqualTo(3);

            agentService.deleteAgents(List.of(UUID, UUID2));

            assertThat(agentService.getAgentInstances().size()).isEqualTo(1);
        }

        @Test
        void shouldNotBeAbleToDeleteDisabledAgentWhoseRuntimeStatusIsBuilding() {
            createDisabledAgentWithBuildingRuntimeStatus(UUID);

            assertThat(agentService.getAgentInstances().size()).isEqualTo(1);

            UnprocessableEntityException e = assertThrows(UnprocessableEntityException.class, () -> agentService.deleteAgents(List.of(UUID)));
            assertThat(e.getMessage()).isEqualTo("Failed to delete an agent, as it is not in a disabled state or is still building.");

            assertThat(agentService.getAgentInstances().size()).isEqualTo(1);
            assertTrue(agentService.getAgentInstances().hasAgent(UUID));
        }

        @Test
        void shouldBeAbleToDeleteDisabledAgentInIdleState() {
            createAnIdleAgentAndDisableIt(UUID);
            createEnabledAgent(UUID2);

            assertThat(agentService.getAgentInstances().size()).isEqualTo(2);

            assertDoesNotThrow(() -> agentService.deleteAgents(List.of(UUID)));

            assertThat(agentService.getAgentInstances().size()).isEqualTo(1);
        }

        @Test
        void shouldReturn404WhenDeleteAgentsIsCalledWithUnknownAgentUUID() {
            String unknownUUID = "unknown-agent-id";
            RecordNotFoundException e = assertThrows(RecordNotFoundException.class, () -> agentService.deleteAgents(List.of(unknownUUID)));
            assertThat(e.getMessage()).isEqualTo("Agent with uuid 'unknown-agent-id' was not found!");
        }
    }

    @Test
    void shouldNotBeAbleToAssignCookieToAnUnregisteredAgent() {
        Agent pendingAgent = pending().getAgent();
        AgentIdentifier pendingAgentIdentifier = new AgentIdentifier(pendingAgent.getHostname(), pendingAgent.getIpaddress(), pendingAgent.getUuid());
        UnregisteredAgentException exception = assertThrows(UnregisteredAgentException.class, () -> agentService.assignCookie(pendingAgentIdentifier));
        assertEquals(format("Agent [%s] is not registered.", pendingAgent.getUuid()), exception.getMessage());
    }

    @Nested
    @ContextConfiguration(locations = {
            "classpath:/applicationContext-global.xml",
            "classpath:/applicationContext-dataLocalAccess.xml",
            "classpath:/testPropertyConfigurer.xml",
            "classpath:/spring-all-servlet.xml"})
    class PendingAgents {
        @Test
        void shouldBeAbleToDisableAPendingAgent() {
            String uuid = DatabaseAccessHelper.AGENT_UUID;
            Agent agent = new Agent(uuid, "agentName", "50.40.30.9");
            AgentRuntimeInfo agentRuntime = new AgentRuntimeInfo(agent.getAgentIdentifier(), AgentRuntimeStatus.Idle,
                    currentWorkingDirectory(), "cookie");
            agentRuntime.busy(new AgentBuildingInfo("path", "buildLocator"));
            agentService.requestRegistration(agentRuntime);

            assertDoesNotThrow(() -> agentService.bulkUpdateAgentAttributes(List.of(uuid), emptyStrList, emptyStrList, emptyStrList, emptyStrList, FALSE, environmentConfigService));

            AgentInstances agents = agentService.getAgentInstances();

            assertThat(agents.size()).isEqualTo(1);
            assertThat(agents.findAgent(uuid).isDisabled()).isTrue();
            assertThat(agentService.findAgentAndRefreshStatus(uuid).isDisabled()).isTrue();
            assertThat(agentService.findAgentAndRefreshStatus(uuid).getStatus()).isEqualTo(AgentStatus.Disabled);
        }

        @Test
        void shouldBeAbleToEnableAPendingAgent() {
            String uuid = DatabaseAccessHelper.AGENT_UUID;
            Agent agent = new Agent(uuid, "agentName", "50.40.30.9");
            AgentRuntimeInfo agentRuntime = new AgentRuntimeInfo(agent.getAgentIdentifier(), AgentRuntimeStatus.Idle, currentWorkingDirectory(), "cookie");
            agentRuntime.busy(new AgentBuildingInfo("path", "buildLocator"));
            agentService.requestRegistration(agentRuntime);

            assertDoesNotThrow(() -> agentService.bulkUpdateAgentAttributes(List.of(uuid), emptyStrList, emptyStrList, emptyStrList, emptyStrList, TRUE, environmentConfigService));
        }

        @Test
        void shouldNotAllowAnyUpdateOperationOnPendingAgent() {
            AgentRuntimeInfo pendingAgent = fromServer(new Agent(UUID, "CCeDev03", "10.18.5.3", List.of("db", "web")),
                    false, "/var/lib", 0L, "linux");
            agentService.requestRegistration(pendingAgent);
            assertThat(agentService.findAgent(UUID).isRegistered()).isFalse();

            List<String> uuids = new ArrayList<>(List.of(pendingAgent.getUUId()));
            List<String> resourcesToAdd = new ArrayList<>(List.of("Linux"));
            List<String> resourcesToRemove = new ArrayList<>(List.of("Gauge"));

            BadRequestException e = assertThrows(BadRequestException.class, () -> agentService.bulkUpdateAgentAttributes(uuids, resourcesToAdd, resourcesToRemove, emptyStrList, emptyStrList, UNSET, environmentConfigService));

            assertThat(e.getMessage()).isEqualTo("Pending agents [" + pendingAgent.getUUId() + "] must be explicitly enabled or disabled when performing any operations on them.");
        }

        @Test
        void shouldBeAbleToUpdatePendingAgentProvidedTheStateIsSet() {
            String uuid = DatabaseAccessHelper.AGENT_UUID;
            Agent agent = new Agent(uuid, "agentName", "50.40.30.9");
            AgentRuntimeInfo agentRuntime = new AgentRuntimeInfo(agent.getAgentIdentifier(), AgentRuntimeStatus.Idle, currentWorkingDirectory(), "cookie");
            agentRuntime.busy(new AgentBuildingInfo("path", "buildLocator"));
            agentService.requestRegistration(agentRuntime);

            AgentInstance agentInstance = agentService.updateAgentAttributes(uuid, "new-hostname", "resources", "env1", TRUE);

            AgentInstances agents = agentService.getAgentInstances();

            assertThat(agents.size()).isEqualTo(1);
            assertThat(agentInstance.isDisabled()).isFalse();
            assertThat(agentInstance.getStatus()).isEqualTo(AgentStatus.Missing);
        }
    }

    @Nested
    @ContextConfiguration(locations = {"classpath:/applicationContext-global.xml", "classpath:/applicationContext-dataLocalAccess.xml",
            "classpath:/testPropertyConfigurer.xml", "classpath:/spring-all-servlet.xml"})
    class EnableDisableAgents {
        @Test
        void shouldBeAbleToDisableAgentUsingUpdateAgentAttributesCall() {
            createEnabledAgent(UUID2);

            assertThat(agentService.getAgentInstances().size()).isEqualTo(1);
            assertThat(getFirstAgent().isDisabled()).isFalse();

            agentService.updateAgentAttributes(UUID2, null, null, null, FALSE);

            assertThat(agentService.getAgentInstances().size()).isEqualTo(1);
            assertThat(getFirstAgent().isDisabled()).isTrue();
        }

        @Test
        void shouldBeAbleToDisableBuildingAgent() {
            String uuid = DatabaseAccessHelper.AGENT_UUID;
            Agent agent = new Agent(uuid, "agentName", "127.0.0.9");
            requestRegistrationAndApproveAgent(agent);

            AgentIdentifier agentIdentifier = agent.getAgentIdentifier();
            String cookie = agentService.assignCookie(agentIdentifier);
            AgentRuntimeInfo agentRuntimeInfo = new AgentRuntimeInfo(agentIdentifier, AgentRuntimeStatus.Idle, currentWorkingDirectory(), cookie);
            agentRuntimeInfo.busy(new AgentBuildingInfo("path", "buildLocator"));

            agentService.updateRuntimeInfo(agentRuntimeInfo);

            List<String> emptyList = emptyStrList;
            agentService.bulkUpdateAgentAttributes(List.of(uuid), emptyList, emptyList, emptyStrList, emptyList, FALSE, environmentConfigService);

            assertThat(agentService.agents().getAgentByUUID(uuid).isDisabled()).isTrue();
        }

        @Test
        void shouldBeAbleToEnableADisabledAgent() {
            String agentId = DatabaseAccessHelper.AGENT_UUID;
            Agent agent = new Agent(agentId, "agentName", "127.0.0.9");
            requestRegistrationAndApproveAgent(agent);
            disableAgent(agent);

            assertDoesNotThrow(() -> agentService.bulkUpdateAgentAttributes(List.of(agentId), emptyStrList, emptyStrList, emptyStrList, emptyStrList, TRUE, environmentConfigService));

            assertThat(isDisabled(agent)).isFalse();
        }

        @Test
        void shouldBeAbleToEnabledMultipleAgentsUsingBulkUpdateCall() {
            Agent agent1 = createDisabledAgentWithBuildingRuntimeStatus(UUID);
            Agent agent2 = createDisabledAgentWithBuildingRuntimeStatus(UUID2);

            assertDoesNotThrow(() -> agentService.bulkUpdateAgentAttributes(List.of(UUID, UUID2), emptyStrList, emptyStrList, emptyStrList, emptyStrList, TRUE, environmentConfigService));

            assertThat(isDisabled(agent1)).isFalse();
            assertThat(isDisabled(agent2)).isFalse();
        }

        @Test
        void shouldBeAbleToDisableMultipleAgentsUsingBulkUpdateCall() {
            Agent agent1 = createEnabledAgent(UUID);
            Agent agent2 = createEnabledAgent(UUID2);

            assertDoesNotThrow(() -> agentService.bulkUpdateAgentAttributes(List.of(UUID, UUID2), emptyStrList, emptyStrList, emptyStrList, emptyStrList, FALSE, environmentConfigService));

            assertThat(isDisabled(agent1)).isTrue();
            assertThat(isDisabled(agent2)).isTrue();
        }

        @Test
        void shouldReturn200WhenAnAlreadyEnableAgentIsEnabled() {
            String uuid = DatabaseAccessHelper.AGENT_UUID;
            Agent agent = new Agent(uuid, "agentName", "127.0.0.9");
            requestRegistrationAndApproveAgent(agent);

            assertDoesNotThrow(() -> agentService.bulkUpdateAgentAttributes(List.of(uuid), emptyStrList, emptyStrList, emptyStrList, emptyStrList, TRUE, environmentConfigService));
        }

        @Test
        void shouldReturn200WhenAnAlreadyDisabledAgentIsDisabled() {
            String uuid = DatabaseAccessHelper.AGENT_UUID;
            Agent agent = new Agent(uuid, "agentName", "127.0.0.9");
            requestRegistrationAndApproveAgent(agent);
            disableAgent(agent);

            assertDoesNotThrow(() -> agentService.bulkUpdateAgentAttributes(List.of(uuid), emptyStrList, emptyStrList, emptyStrList, emptyStrList, FALSE, environmentConfigService));
        }

        @Test
        void shouldNotBeAbleToEnableDisableWhenTriStateIsUnset() {
            createEnabledAgent("enabled");
            createDisabledAgentWithBuildingRuntimeStatus("disabled");

            assertThat(agentService.getAgentInstances().size()).isEqualTo(2);
            assertThat(agentService.findAgentAndRefreshStatus("enabled").getAgent().isDisabled()).isFalse();
            assertThat(agentService.findAgentAndRefreshStatus("disabled").getAgent().isDisabled()).isTrue();

            agentService.updateAgentAttributes("enabled", "new.enabled.hostname", "linux,java", null, UNSET);

            agentService.updateAgentAttributes("disabled", "new.disabled.hostname", "linux,java", null, UNSET);

            assertThat(agentService.getAgentInstances().size()).isEqualTo(2);
            assertThat(agentService.findAgentAndRefreshStatus("enabled").getAgent().isDisabled()).isFalse();
            assertThat(agentService.findAgentAndRefreshStatus("disabled").getAgent().isDisabled()).isTrue();
        }

        @Test
        void shouldAllowEnablingThePendingAndDisabledAgentsTogether() {
            AgentRuntimeInfo pendingAgent = fromServer(new Agent(UUID, "CCeDev03", "10.18.5.3", List.of("db", "web")), false, "/var/lib", 0L, "linux");
            Agent agent = new Agent(UUID2, "remote-host1", "50.40.30.21");

            agentService.requestRegistration(pendingAgent);
            agentService.register(agent);
            agentService.disableAgents(agent.getUuid());

            assertThat(agentService.findAgent(UUID).isRegistered()).isFalse();
            assertThat(agentService.findAgent(agent.getUuid()).isDisabled()).isTrue();

            List<String> uuids = List.of(UUID, agent.getUuid());

            assertDoesNotThrow(() -> agentService.bulkUpdateAgentAttributes(uuids, emptyStrList, emptyStrList, emptyStrList, emptyStrList, TRUE, environmentConfigService));

            assertThat(agentService.agents().getAgentByUUID(UUID).isEnabled()).isTrue();
            assertThat(agentService.agents().getAgentByUUID(agent.getUuid()).isEnabled()).isTrue();
        }

        @Test
        void shouldNotDisableAnyAgentsAndShouldThrow400WhenEvenOneOfTheUUIDIsInvalid() {
            Agent agent = new Agent(UUID, "remote-host1", "50.40.30.21");
            Agent agent1 = new Agent(UUID2, "remote-host2", "50.40.30.22");

            agentService.register(agent);
            agentService.register(agent1);

            assertFalse(agentService.findAgent(agent.getUuid()).isDisabled());
            assertFalse(agentService.findAgent(agent1.getUuid()).isDisabled());

            List<String> uuids = List.of(agent.getUuid(), agent1.getUuid(), "invalid-uuid");

            RecordNotFoundException e = assertThrows(RecordNotFoundException.class, () -> agentService.bulkUpdateAgentAttributes(uuids, emptyStrList, emptyStrList, emptyStrList, emptyStrList, FALSE, environmentConfigService));

            assertFalse(agentService.findAgent(agent.getUuid()).isDisabled());
            assertFalse(agentService.findAgent(agent1.getUuid()).isDisabled());

            assertThat(e.getMessage()).isEqualTo(EntityType.Agent.notFoundMessage(List.of("invalid-uuid")));
        }

        @Test
        void shouldNotEnableAnyAgentsAndShouldThrow400WhenEvenOneOfTheUUIDIsInvalid() {
            Agent agent = new Agent(UUID, "remote-host1", "50.40.30.21");
            Agent agent1 = new Agent(UUID2, "remote-host2", "50.40.30.22");

            agentService.register(agent);
            agentService.register(agent1);

            assertFalse(agentService.findAgent(agent.getUuid()).isDisabled());
            assertFalse(agentService.findAgent(agent1.getUuid()).isDisabled());

            List<String> uuids = List.of(agent.getUuid(), agent1.getUuid(), "invalid-uuid");

            RecordNotFoundException e = assertThrows(RecordNotFoundException.class, () -> agentService.bulkUpdateAgentAttributes(uuids, emptyStrList, emptyStrList, emptyStrList, emptyStrList, TRUE, environmentConfigService));

            assertFalse(agentService.findAgent(agent.getUuid()).isDisabled());
            assertFalse(agentService.findAgent(agent1.getUuid()).isDisabled());

            assertThat(e.getMessage()).isEqualTo(EntityType.Agent.notFoundMessage(List.of("invalid-uuid")));
        }
    }

    @Nested
    @ContextConfiguration(locations = {"classpath:/applicationContext-global.xml", "classpath:/applicationContext-dataLocalAccess.xml",
            "classpath:/testPropertyConfigurer.xml", "classpath:/spring-all-servlet.xml"})
    class ErrorConditions {
        @Test
        void shouldReturn400WhenUpdatingAnUnknownAgent() {
            RecordNotFoundException e = assertThrows(RecordNotFoundException.class, () -> agentService.bulkUpdateAgentAttributes(List.of("unknown-agent-id"), emptyStrList, emptyStrList, emptyStrList, emptyStrList, TRUE, environmentConfigService));
            assertThat(e.getMessage()).isEqualTo("Agents with uuids [unknown-agent-id] were not found!");
        }

        @Test
        void shouldReturn404WhenAgentToBeDeletedDoesNotExist() {
            String unknownUUID = "unknown-agent-id";
            RecordNotFoundException e = assertThrows(RecordNotFoundException.class, () -> agentService.deleteAgents(List.of(unknownUUID)));
            assertThat(e.getMessage()).isEqualTo("Agent with uuid 'unknown-agent-id' was not found!");
        }

        @Test
        void shouldThrow422WhenUpdatingAgentWithInvalidInputs() {
            Agent agent = createAnIdleAgentAndDisableIt(UUID);
            String originalHostname = agent.getHostname();
            List<String> originalResourceNames = agent.getResourcesAsList();

            assertThat(agentService.getAgentInstances().size()).isEqualTo(1);
            assertThat(getFirstAgent().getHostname()).isNotEqualTo("some-hostname");

            String invalidResourceName = "lin!ux";
            AgentInstance agentInstance = agentService.updateAgentAttributes(UUID, "some-hostname",
                    invalidResourceName, null, UNSET);

            assertThat(agentInstance.getAgent().errors().on(JobConfig.RESOURCES)).isEqualTo("Resource name 'lin!ux' is not valid. Valid names much match '^[-\\w\\s|.]*$'");

            assertThat(agentService.getAgentInstances().size()).isEqualTo(1);
            assertThat(getFirstAgent().getHostname()).isEqualTo(originalHostname);
            assertThat(getFirstAgent().getResourceConfigs().resourceNames()).isEqualTo(originalResourceNames);
        }

        @Test
        void shouldThrowExceptionWhenADuplicateAgentTriesToUpdateStatus() {
            AgentInstance buildingAgentInstance = building();
            AgentService agentService = newAgentService(new AgentInstances(new SystemEnvironment(), agentStatusChangeListener(), buildingAgentInstance));
            AgentInstances agentInstances = agentService.findRegisteredAgents();

            String uuid = buildingAgentInstance.getAgent().getUuid();
            assertThat(agentInstances.findAgentAndRefreshStatus(uuid).getStatus()).isEqualTo(AgentStatus.Building);

            AgentIdentifier identifier = buildingAgentInstance.getAgent().getAgentIdentifier();
            // register the agent - cookie can be associated only if the agent is a registered one
            agentService.register(new Agent(identifier.getUuid(), identifier.getHostName(), identifier.getIpAddress()));
            agentDao.associateCookie(identifier, "new_cookie");

            AgentRuntimeInfo runtimeInfo = new AgentRuntimeInfo(identifier, AgentRuntimeStatus.Idle, currentWorkingDirectory(), "old_cookie");
            AgentWithDuplicateUUIDException e = assertThrows(AgentWithDuplicateUUIDException.class, () -> agentService.updateRuntimeInfo(runtimeInfo));
            assertEquals(e.getMessage(), format("Agent [%s] has invalid cookie", runtimeInfo.agentInfoDebugString()));

            agentInstances = agentService.findRegisteredAgents();
            assertThat(agentInstances.findAgentAndRefreshStatus(uuid).getStatus()).isEqualTo(AgentStatus.Building);
            AgentIdentifier agentIdentifier = buildingAgentInstance.getAgent().getAgentIdentifier();
            String cookie = agentService.assignCookie(agentIdentifier);
            agentService.updateRuntimeInfo(new AgentRuntimeInfo(agentIdentifier, AgentRuntimeStatus.Idle, currentWorkingDirectory(), cookie));
        }
    }

    @Nested
    @ContextConfiguration(locations = {"classpath:/applicationContext-global.xml", "classpath:/applicationContext-dataLocalAccess.xml",
            "classpath:/testPropertyConfigurer.xml", "classpath:/spring-all-servlet.xml"})
    class Update {
        @Test
        void shouldBeAbleToUpdateAgentsHostName() {
            createAnIdleAgentAndDisableIt(UUID);

            assertThat(agentService.getAgentInstances().size()).isEqualTo(1);
            String someHostName = "some-hostname";
            assertThat(getFirstAgent().getHostname()).isNotEqualTo(someHostName);

            agentService.updateAgentAttributes(UUID, someHostName, null, null, UNSET);

            assertThat(agentService.getAgentInstances().size()).isEqualTo(1);
            assertThat(getFirstAgent().getHostname()).isEqualTo(someHostName);
        }

        @Test
        void shouldUpdateAgentAttributesForValidInputs() {
            createAnIdleAgentAndDisableIt(UUID);
            createEnvironment("a", "b");

            assertThat(agentService.getAgentInstances().size()).isEqualTo(1);
            assertThat(getFirstAgent().getHostname()).isNotEqualTo("some-hostname");
            assertThat(getFirstAgent().isDisabled()).isTrue();

            agentService.updateAgentAttributes(UUID, "some-hostname", "linux,java", "a,b", TRUE);

            AgentInstance firstAgent = getFirstAgent();
            List<String> resourceNames = firstAgent.getResourceConfigs().resourceNames();

            assertThat(agentService.getAgentInstances().size()).isEqualTo(1);
            assertThat(firstAgent.getHostname()).isEqualTo("some-hostname");
            assertThat(resourceNames).isEqualTo(List.of("java", "linux"));
            assertThat(firstAgent.isDisabled()).isFalse();
            assertEquals(getEnvironments(getFirstAgent().getUuid()), Set.of("a", "b"));
        }

        @Test
        void shouldUpdateAgentStatus() {
            AgentInstance buildingAgentInstance = building();
            AgentService agentService = newAgentService(new AgentInstances(new SystemEnvironment(), agentStatusChangeListener(), buildingAgentInstance));
            AgentInstances registeredAgentInstances = agentService.findRegisteredAgents();

            String uuid = buildingAgentInstance.getAgent().getUuid();
            assertThat(registeredAgentInstances.findAgentAndRefreshStatus(uuid).getStatus()).isEqualTo(AgentStatus.Building);

            AgentIdentifier agentIdentifier = buildingAgentInstance.getAgent().getAgentIdentifier();
            // register the agent - cookie can be associated only if the agent is a registered one
            agentService.register(new Agent(agentIdentifier.getUuid(), agentIdentifier.getHostName(), agentIdentifier.getIpAddress()));
            String cookie = agentService.assignCookie(agentIdentifier);
            agentService.updateRuntimeInfo(new AgentRuntimeInfo(agentIdentifier, AgentRuntimeStatus.Idle, currentWorkingDirectory(), cookie));

            registeredAgentInstances = agentService.findRegisteredAgents();
            assertThat(registeredAgentInstances.findAgentAndRefreshStatus(uuid).getStatus()).isEqualTo(AgentStatus.Idle);
        }

        @Test
        void shouldUpdateOnlyThoseAgentsAttributeThatAreSpecified() {
            createEnvironment("a", "b");
            Agent agent = createAnIdleAgentAndDisableIt(UUID);
            String originalHostname = agent.getHostname();

            assertDoesNotThrow(() -> agentService.bulkUpdateAgentAttributes(List.of(UUID), emptyStrList, emptyStrList, List.of("a", "b"), emptyStrList, TRUE, environmentConfigService));

            assertThat(agentService.getAgentInstances().size()).isEqualTo(1);

            String notSpecifying = null;
            BadRequestException e = assertThrows(BadRequestException.class, () -> agentService.updateAgentAttributes(UUID, notSpecifying, notSpecifying, null, UNSET));
            assertThat(e.getMessage()).isEqualTo("Bad Request. No operation is specified in the request to be performed on agent.");

            assertThat(agentService.getAgentInstances().size()).isEqualTo(1);
            assertThat(getFirstAgent().getHostname()).isEqualTo(originalHostname);
            assertEquals(getEnvironments(getFirstAgent().getUuid()), Set.of("a", "b"));
        }

        @Test
        void shouldThrowBadRequestIfNoOperationToPerformOnBulkUpdatingAgents() {
            AgentInstance pendingAgent = pending();
            AgentInstance registeredAgent = disabled();

            AgentInstances instances = new AgentInstances(new SystemEnvironment(), agentStatusChangeListener(), pendingAgent);
            AgentService agentService = newAgentService(instances);

            List<String> uuids = new ArrayList<>();
            uuids.add(pendingAgent.getUuid());
            uuids.add(registeredAgent.getUuid());

            BadRequestException e = assertThrows(BadRequestException.class, () -> agentService.bulkUpdateAgentAttributes(uuids, emptyStrList, emptyStrList, emptyStrList, emptyStrList, UNSET, environmentConfigService));
            assertThat(e.getMessage()).isEqualTo("Bad Request. No operation is specified in the request to be performed on agents.");
        }

        @Test
        void shouldUpdateResourcesEnvironmentsAndAgentStateOfAllTheProvidedAgentsAllTogether() {
            createEnabledAgent(UUID);
            createEnabledAgent(UUID2);

            createEnvironment("dev");

            EnvironmentConfig dev = environmentConfigService.getEnvironmentConfig("dev");

            assertThat(dev.getAgents().getUuids()).doesNotContain(UUID, UUID2);
            assertFalse(agentService.findAgent(UUID).isDisabled());
            assertFalse(agentService.findAgent(UUID2).isDisabled());
            assertThat(agentService.findAgent(UUID).getResourceConfigs().size()).isEqualTo(0);
            assertThat(agentService.findAgent(UUID2).getResourceConfigs().size()).isEqualTo(0);

            List<String> uuids = List.of(UUID, UUID2);
            List<String> resources = List.of("resource1");
            List<String> environmentsToAdd = List.of("dev");

            assertDoesNotThrow(() -> agentService.bulkUpdateAgentAttributes(uuids, resources, emptyStrList, environmentsToAdd, emptyStrList, FALSE, environmentConfigService));

            assertTrue(agentService.findAgent(UUID).isDisabled());
            assertTrue(agentService.findAgent(UUID2).isDisabled());
            assertThat((List<ResourceConfig>) agentService.findAgent(UUID).getResourceConfigs()).contains(new ResourceConfig("resource1"));
            assertThat((List<ResourceConfig>) agentService.findAgent(UUID2).getResourceConfigs()).contains(new ResourceConfig("resource1"));

            assertThat(environmentConfigService.getEnvironmentConfig("dev").getAgents().getUuids()).contains(UUID, UUID2);
        }
    }

    @Nested
    @ContextConfiguration(locations = {"classpath:/applicationContext-global.xml", "classpath:/applicationContext-dataLocalAccess.xml",
            "classpath:/testPropertyConfigurer.xml", "classpath:/spring-all-servlet.xml"})
    class LostContact {
        @Test
        void shouldMarkAgentAsLostContactWhenAgentDoesNotPingWithinTimeoutPeriod() {
            new SystemEnvironment().setProperty("agent.connection.timeout", "-1");
            Date date = Date.from(LocalDateTime.of(1970, 1, 1, 1, 1, 1).toInstant(ZoneOffset.UTC));
            AgentInstance instance = idle(date, "CCeDev01");
            ReflectionUtil.<AgentRuntimeInfo>getField(instance, "agentRuntimeInfo").setOperatingSystem("Minix");

            AgentService agentService = new AgentService(new SystemEnvironment(), agentDao, new UuidGenerator(),
                    serverHealthService, agentStatusChangeNotifier());
            AgentInstances agentInstances = ReflectionUtil.getField(agentService, "agentInstances");
            agentInstances.add(instance);

            AgentInstances agents = agentService.findRegisteredAgents();
            assertThat(agents.size()).isEqualTo(1);
            AgentInstance agentInstance = agents.findAgentAndRefreshStatus(instance.getAgent().getUuid());
            assertThat(agentInstance.getStatus()).isEqualTo(AgentStatus.LostContact);
        }

        @Test
        void shouldNotSendLostContactEmailWhenAgentStateIsLostContact() {
            new SystemEnvironment().setProperty("agent.connection.timeout", "-1");
            CONFIG_HELPER.addMailHost(new MailHost("ghost.name", 25, "loser", "boozer", true, false, "go@foo.mail.com", "admin@foo.mail.com"));

            Date date = Date.from(LocalDateTime.of(1970, 1, 1, 1, 1, 1).toInstant(ZoneOffset.UTC));
            AgentInstance idleAgentInstance = idle(date, "CCeDev01");
            ReflectionUtil.<AgentRuntimeInfo>getField(idleAgentInstance, "agentRuntimeInfo").setOperatingSystem("Minix");

            EmailSender mailSender = mock(EmailSender.class);
            AgentService agentService = new AgentService(new SystemEnvironment(), agentDao, new UuidGenerator(), serverHealthService, agentStatusChangeNotifier());
            AgentInstances agentInstances = ReflectionUtil.getField(agentService, "agentInstances");
            agentInstances.add(idleAgentInstance);

            AgentInstances agents = agentService.findRegisteredAgents();
            assertThat(agents.size()).isEqualTo(1);

            AgentInstance agentInstance = agents.findAgentAndRefreshStatus(idleAgentInstance.getAgent().getUuid());
            assertThat(agentInstance.getStatus()).isEqualTo(AgentStatus.LostContact);
            String body = format("""
                    The email has been sent out automatically by the Go server at (%s) to Go administrators.

                    The Go server has lost contact with agent:

                    Agent name: CCeDev01
                    Free Space: 10.0 KB
                    Sandbox: /var/lib/foo
                    IP Address: 10.18.5.1
                    OS: Minix
                    Resources:\s
                    Environments:\s

                    Lost contact at: %s""", SystemUtil.getFirstLocalNonLoopbackIpAddress(), date);
            verify(mailSender, never()).sendEmail(new SendEmailMessage("[Lost Contact] Go agent host: " + idleAgentInstance.getHostname(), body, "admin@foo.mail.com"));
        }
    }

    @Nested
    @ContextConfiguration(locations = {"classpath:/applicationContext-global.xml", "classpath:/applicationContext-dataLocalAccess.xml",
            "classpath:/testPropertyConfigurer.xml", "classpath:/spring-all-servlet.xml"})
    class Resources {
        @Test
        void shouldAddResourcesToDisabledAgent() {
            createAnIdleAgentAndDisableIt(UUID);

            assertThat(agentService.getAgentInstances().size()).isEqualTo(1);
            assertThat((List<ResourceConfig>) getFirstAgent().getResourceConfigs()).isEmpty();
            assertThat(agentService.findAgent(UUID).getStatus()).isEqualTo(AgentStatus.Disabled);

            agentService.updateAgentAttributes(UUID, null, "linux,java", null, UNSET);

            assertThat(agentService.getAgentInstances().size()).isEqualTo(1);
            assertThat(getFirstAgent().getResourceConfigs().resourceNames()).isEqualTo(List.of("java", "linux"));
        }

        @Test
        void shouldAddResourcesWhileBulkUpdatingAgents() {
            createEnabledAgent(UUID);
            createEnabledAgent(UUID2);

            List<String> uuids = List.of(UUID, UUID2);
            List<String> resourcesToAdd = List.of("resource1", "resource2");

            assertDoesNotThrow(() -> agentService.bulkUpdateAgentAttributes(uuids, resourcesToAdd, emptyStrList,
                    emptyStrList, emptyStrList, TRUE, environmentConfigService));

            List<String> uuidResources = agentService.findAgentAndRefreshStatus(UUID).getAgent().getResourcesAsList();
            assertThat(uuidResources).contains("resource1");
            assertThat(uuidResources).contains("resource2");

            List<String> uuid2Resources = agentService.findAgentAndRefreshStatus(UUID2).getAgent().getResourcesAsList();
            assertThat(uuid2Resources).contains("resource1");
            assertThat(uuid2Resources).contains("resource2");
        }

        @Test
        void shouldNotUpdateResourcesOnElasticAgents() {
            Agent elasticAgent = AgentMother.elasticAgent();

            agentService.register(elasticAgent);

            List<String> uuids = List.of(elasticAgent.getUuid());
            List<String> resourcesToAdd = List.of("resource");

            assertTrue(agentService.findAgent(elasticAgent.getUuid()).getResourceConfigs().isEmpty());

            BadRequestException e = assertThrows(BadRequestException.class, () -> agentService.bulkUpdateAgentAttributes(uuids, resourcesToAdd, emptyStrList, emptyStrList, emptyStrList, UNSET, environmentConfigService));

            assertThat(e.getMessage()).isEqualTo("Resources on elastic agents with uuids [" + StringUtils.join(uuids, ", ") + "] can not be updated.");
            assertTrue(agentService.findAgent(elasticAgent.getUuid()).getResourceConfigs().isEmpty());
        }

        @Test
        void shouldRemoveResourcesFromTheSpecifiedAgents() {
            Agent agent = new Agent(UUID, "remote-host1", "50.40.30.21");
            Agent agent1 = new Agent(UUID2, "remote-host1", "50.40.30.22");
            agent.setResources("resource1,resource2");
            agent1.setResources("resource2");

            agentService.register(agent);
            agentService.register(agent1);

            List<String> uuids = List.of(UUID, UUID2);
            List<String> resourcesToRemove = List.of("resource2");

            assertThat(agentService.findAgent(UUID).getResourceConfigs().size()).isEqualTo(2);
            assertThat(agentService.findAgent(UUID2).getResourceConfigs().size()).isEqualTo(1);

            assertDoesNotThrow(() -> agentService.bulkUpdateAgentAttributes(uuids, emptyStrList, resourcesToRemove, emptyStrList, emptyStrList, UNSET, environmentConfigService));

            assertThat(agentService.findAgent(UUID).getResourceConfigs().size()).isEqualTo(1);
            assertThat((List<ResourceConfig>) agentService.findAgent(UUID).getResourceConfigs()).contains(new ResourceConfig("resource1"));
            assertThat(agentService.findAgent(UUID2).getResourceConfigs().size()).isEqualTo(0);
        }

        @Test
        void shouldThrowValidationErrorOnResourceNameForUpdateAgent() {
            createAnIdleAgentAndDisableIt(UUID);

            assertThat(agentService.getAgentInstances().size()).isEqualTo(1);
            assertThat((List<ResourceConfig>) getFirstAgent().getResourceConfigs()).isEmpty();
            assertThat(agentService.findAgent(UUID).getStatus()).isEqualTo(AgentStatus.Disabled);

            AgentInstance agentInstance = agentService.updateAgentAttributes(UUID, null, "foo%", null, UNSET);

            assertThat(agentInstance.getAgent().hasErrors()).isTrue();

            ConfigErrors configErrors = agentInstance.getAgent().errors();
            assertFalse(configErrors.isEmpty());
            assertEquals("Resource name 'foo%' is not valid. Valid names much match '^[-\\w\\s|.]*$'", configErrors.on("resources"));

            assertThat(agentService.getAgentInstances().size()).isEqualTo(1);
            assertThat(getFirstAgent().getResourceConfigs().resourceNames()).isEqualTo(emptyStrList);
        }

        @Test
        void shouldThrowValidationErrorOnResourceNameForUpdateBulkAgent() {
            createAnIdleAgentAndDisableIt(UUID);

            assertThat(agentService.getAgentInstances().size()).isEqualTo(1);
            assertThat((List<ResourceConfig>) getFirstAgent().getResourceConfigs()).isEmpty();
            assertThat(agentService.findAgent(UUID).getStatus()).isEqualTo(AgentStatus.Disabled);

            UnprocessableEntityException e = assertThrows(UnprocessableEntityException.class, () -> agentService.bulkUpdateAgentAttributes(List.of(UUID), List.of("foo%"), emptyStrList, emptyStrList, emptyStrList, UNSET, environmentConfigService));

            assertThat(e.getMessage()).isEqualTo("Validations failed for bulk update of agents. Error(s): {resources=[Resource name 'foo%' is not valid. Valid names much match '^[-\\w\\s|.]*$']}");

            assertThat(agentService.getAgentInstances().size()).isEqualTo(1);
            assertThat((List<ResourceConfig>) getFirstAgent().getResourceConfigs()).isEmpty();
        }
    }

    @Nested
    @ContextConfiguration(locations = {"classpath:/applicationContext-global.xml", "classpath:/applicationContext-dataLocalAccess.xml",
            "classpath:/testPropertyConfigurer.xml", "classpath:/spring-all-servlet.xml"})
    class RegistrationAndApproval {
        @Test
        void shouldRegisterLocalAgentWithNonLoopbackIpAddress() throws Exception {
            String nonLoopbackIp = SystemUtil.getFirstLocalNonLoopbackIpAddress();
            InetAddress nonLoopbackIpAddress = InetAddress.getByName(nonLoopbackIp);
            assertThat(SystemUtil.isLocalIpAddress(nonLoopbackIp)).isTrue();

            Agent agent = new Agent("uuid", nonLoopbackIpAddress.getHostName(), nonLoopbackIp);
            AgentRuntimeInfo agentRuntimeInfo = fromServer(agent, false, "/var/lib", 0L, "linux");
            agentService.requestRegistration(agentRuntimeInfo);

            AgentInstance agentInstance = agentService.findRegisteredAgents().findAgentAndRefreshStatus("uuid");

            assertThat(agentInstance.getAgent().getIpaddress()).isEqualTo(nonLoopbackIp);
            assertThat(agentInstance.getStatus()).isEqualTo(AgentStatus.Idle);
        }

        @Test
        void shouldBeAbleToRegisterPendingAgent() {
            AgentInstance pendingAgentInstance = pending();
            Agent pendingAgent = pendingAgentInstance.getAgent();

            agentService.requestRegistration(fromServer(pendingAgent, false, "var/lib",
                    0L, "linux"));
            String uuid = pendingAgentInstance.getUuid();
            agentService.approve(uuid);

            assertThat(agentService.findRegisteredAgents().size()).isEqualTo(1);
            assertThat(agentService.findAgentAndRefreshStatus(uuid).getAgent().isDisabled()).isFalse();
            assertThat(agentDao.getAgentByUUIDFromCacheOrDB(uuid).isDisabled()).isFalse();
        }

        @Test
        void shouldRegisterAgentOnlyOnce() {
            Agent pendingAgent = pending().getAgent();
            AgentRuntimeInfo agentRuntimeInfo1 = fromServer(pendingAgent, false, "var/lib", 0L,
                    "linux");
            agentService.requestRegistration(agentRuntimeInfo1);

            agentService.approve(pendingAgent.getUuid());

            AgentRuntimeInfo agentRuntimeInfo2 = fromServer(pendingAgent, true, "var/lib", 0L,
                    "linux");
            agentService.requestRegistration(agentRuntimeInfo2);
            agentService.requestRegistration(agentRuntimeInfo2);

            assertThat(agentService.findRegisteredAgents().size()).isEqualTo(1);
        }
    }

    @Nested
    @ContextConfiguration(locations = {"classpath:/applicationContext-global.xml", "classpath:/applicationContext-dataLocalAccess.xml",
            "classpath:/testPropertyConfigurer.xml", "classpath:/spring-all-servlet.xml"})
    class LoadingAgents {
        @Test
        void shouldLoadAllAgents() {
            AgentInstance idleAgentInstance = idle(new Date(), "CCeDev01");
            AgentInstance pendingAgentInstance = pending();
            AgentInstance buildingAgentInstance = building();
            AgentInstance deniedAgentInstance = disabled();

            AgentInstances agentInstances = new AgentInstances(new SystemEnvironment(), agentStatusChangeListener(),
                    idleAgentInstance, pendingAgentInstance, buildingAgentInstance, deniedAgentInstance);
            AgentService agentService = newAgentService(agentInstances);

            assertThat(agentService.getAgentInstances().size()).isEqualTo(4);

            assertThat(agentService.findAgentAndRefreshStatus(idleAgentInstance.getAgent().getUuid())).isEqualTo(idleAgentInstance);
            assertThat(agentService.findAgentAndRefreshStatus(pendingAgentInstance.getAgent().getUuid())).isEqualTo(pendingAgentInstance);
            assertThat(agentService.findAgentAndRefreshStatus(buildingAgentInstance.getAgent().getUuid())).isEqualTo(buildingAgentInstance);
            assertThat(agentService.findAgentAndRefreshStatus(deniedAgentInstance.getAgent().getUuid())).isEqualTo(deniedAgentInstance);
        }
    }

    @Nested
    @ContextConfiguration(locations = {"classpath:/applicationContext-global.xml", "classpath:/applicationContext-dataLocalAccess.xml",
            "classpath:/testPropertyConfigurer.xml", "classpath:/spring-all-servlet.xml"})
    class Environments {
        @Test
        void shouldDoNothingWhenEnvironmentsToAddOrRemoveIsNullOrEmpty() {
            String prodEnv = "prod";

            createEnvironment(prodEnv);

            createEnabledAgent(UUID);

            List<String> emptyList = emptyStrList;

            agentService.bulkUpdateAgentAttributes(List.of(UUID), List.of("R1"),
                    emptyList, List.of(prodEnv), emptyList, UNSET, environmentConfigService);

            Agent agent = agentService.findAgent(UUID).getAgent();
            assertEquals(1, agent.getEnvironmentsAsList().size());
            assertTrue(agent.getEnvironmentsAsList().contains(prodEnv));

            agentService.bulkUpdateAgentAttributes(List.of(UUID), List.of("R2"),
                    List.of("R1"), null, emptyList, UNSET, environmentConfigService);

            agent = agentService.findAgent(UUID).getAgent();
            assertEquals(1, agent.getEnvironmentsAsList().size());
            assertTrue(agent.getEnvironmentsAsList().contains(prodEnv));

            assertTrue(agent.getResourcesAsList().contains("R2"));
            assertFalse(agent.getResourcesAsList().contains("R1"));

            agentService.bulkUpdateAgentAttributes(List.of(UUID), List.of("R3", "R4"),
                    List.of("R2"), emptyStrList, null, UNSET, environmentConfigService);


            agent = agentService.findAgent(UUID).getAgent();
            assertEquals(1, agent.getEnvironmentsAsList().size());
            assertTrue(agent.getEnvironmentsAsList().contains(prodEnv));

            assertTrue(agent.getResourcesAsList().contains("R3"));
            assertTrue(agent.getResourcesAsList().contains("R4"));
            assertFalse(agent.getResourcesAsList().contains("R2"));
        }

        @Test
        void shouldDoNothingWhenResourcesToAddOrRemoveIsNULLOrEmpty() {
            createEnabledAgent(UUID);

            List<String> emptyList = emptyStrList;
            agentService.bulkUpdateAgentAttributes(List.of(UUID), List.of("r1", "r2"),
                    null, emptyStrList, emptyList, TRUE, environmentConfigService);

            AgentInstance agentInstance = agentService.findAgent(UUID);
            Agent agent = agentInstance.getAgent();
            assertTrue(agent.getResourcesAsList().contains("r1"));
            assertTrue(agent.getResourcesAsList().contains("r2"));

            agentService.bulkUpdateAgentAttributes(List.of(UUID), null,
                    emptyList, null, null, TRUE, environmentConfigService);

            assertTrue(agent.getResourcesAsList().contains("r1"));
            assertTrue(agent.getResourcesAsList().contains("r2"));
        }

        @Test
        void shouldNotAddEnvsWhichAreAssociatedWithTheAgentFromConfigRepoOnBulkAgentUpdate() {
            String prodEnv = "prod";
            createEnabledAgent(UUID);

            createMergeEnvironment(prodEnv, UUID);

            agentService.bulkUpdateAgentAttributes(List.of(UUID), emptyStrList, emptyStrList,
                    List.of(prodEnv), emptyStrList, UNSET, environmentConfigService);

            Agent agent = agentService.findAgent(UUID).getAgent();
            assertTrue(agent.getEnvironmentsAsList().isEmpty());
        }

        @Test
        void shouldBeAbleToUpdateEnvironmentsUsingUpdateAgentAttrsCall() {
            createEnvironment("a", "b", "c", "d", "e");
            createEnabledAgent(UUID);

            agentService.bulkUpdateAgentAttributes(List.of(UUID), emptyStrList, emptyStrList,
                    List.of("a", "b", "c"), emptyStrList, UNSET, environmentConfigService);

            assertThat(agentService.getAgentInstances().size()).isEqualTo(1);
            assertThat((List<ResourceConfig>) getFirstAgent().getResourceConfigs()).isEmpty();
            assertThat(getFirstAgent().getAgent().getEnvironmentsAsList()).isEqualTo(List.of("a", "b", "c"));

            agentService.updateAgentAttributes(UUID, null, null, "c,d,e", UNSET);

            assertThat(agentService.getAgentInstances().size()).isEqualTo(1);
            assertThat(getFirstAgent().getAgent().getEnvironmentsAsList()).isEqualTo(List.of("c", "d", "e"));
        }

        @Test
        void shouldAddEnvironmentsWhileBulkUpdatingAgents() {
            createEnvironment("uat", "prod");

            createEnabledAgent(UUID);
            createEnabledAgent(UUID2);

            assertDoesNotThrow(() -> agentService.bulkUpdateAgentAttributes(List.of(UUID, UUID2), emptyStrList, emptyStrList, List.of("uat", "prod"), emptyStrList, TRUE, environmentConfigService));

            assertThat(environmentConfigService.getAgentEnvironmentNames(UUID)).contains("uat", "prod");
            assertThat(environmentConfigService.getAgentEnvironmentNames(UUID2)).contains("uat", "prod");
        }

        @Test
        void shouldAddEnvToSpecifiedAgents() {
            createEnvironment("uat");

            createEnabledAgent(UUID);
            createEnabledAgent(UUID2);

            BasicEnvironmentConfig uat = new BasicEnvironmentConfig(new CaseInsensitiveString("uat"));
            assertDoesNotThrow(() -> agentService.updateAgentsAssociationOfEnvironment(uat, List.of(UUID, UUID2)));

            assertThat(environmentConfigService.getAgentEnvironmentNames(UUID)).contains("uat");
            assertThat(environmentConfigService.getAgentEnvironmentNames(UUID2)).contains("uat");
        }

        @Test
        void shouldRemoveEnvFromSpecifiedAgents() {
            createEnvironment("uat");

            createEnabledAgent(UUID);
            createEnabledAgent(UUID2);

            assertDoesNotThrow(() -> agentService.bulkUpdateAgentAttributes(List.of(UUID, UUID2), emptyStrList, emptyStrList, List.of("uat"), emptyStrList, TRUE, environmentConfigService));

            BasicEnvironmentConfig uat = new BasicEnvironmentConfig(new CaseInsensitiveString("uat"));
            uat.addAgent(UUID);
            uat.addAgent(UUID2);
            List<String> noAgents = emptyList();
            agentService.updateAgentsAssociationOfEnvironment(uat, noAgents);

            assertThat(environmentConfigService.getAgentEnvironmentNames(UUID).size()).isEqualTo(0);
            assertThat(environmentConfigService.getAgentEnvironmentNames(UUID2).size()).isEqualTo(0);
        }

        @Test
        void shouldAddRemoveEnvFromSpecifiedAgents() {
            createEnvironment("uat");

            createEnabledAgent(UUID);
            createEnabledAgent(UUID2);
            String UUID3 = "uuid3";
            createEnabledAgent(UUID3);

            assertDoesNotThrow(() -> agentService.bulkUpdateAgentAttributes(List.of(UUID, UUID2), emptyStrList, emptyStrList, List.of("uat"), emptyStrList, TRUE, environmentConfigService));

            BasicEnvironmentConfig uat = new BasicEnvironmentConfig(new CaseInsensitiveString("uat"));
            uat.addAgent(UUID);
            uat.addAgent(UUID2);
            agentService.updateAgentsAssociationOfEnvironment(uat, List.of(UUID, UUID3));

            assertThat(environmentConfigService.getAgentEnvironmentNames(UUID)).contains("uat");
            assertThat(environmentConfigService.getAgentEnvironmentNames(UUID2).size()).isEqualTo(0);
            assertThat(environmentConfigService.getAgentEnvironmentNames(UUID3)).contains("uat");
        }

        @Test
        void shouldAddEnvToSpecifiedAgentsForPatchRequest() {
            createEnvironment("uat");

            createEnabledAgent(UUID);
            createEnabledAgent(UUID2);

            BasicEnvironmentConfig uat = new BasicEnvironmentConfig(new CaseInsensitiveString("uat"));
            assertDoesNotThrow(() -> agentService.updateAgentsAssociationOfEnvironment(uat, List.of(UUID, UUID2), Collections.emptyList()));

            assertThat(environmentConfigService.getAgentEnvironmentNames(UUID)).contains("uat");
            assertThat(environmentConfigService.getAgentEnvironmentNames(UUID2)).contains("uat");
        }

        @Test
        void shouldRemoveEnvFromSpecifiedAgentsForPatchRequest() {
            createEnvironment("uat");

            createEnabledAgent(UUID);
            createEnabledAgent(UUID2);

            assertDoesNotThrow(() -> agentService.bulkUpdateAgentAttributes(List.of(UUID, UUID2), emptyStrList, emptyStrList, List.of("uat"), emptyStrList, TRUE, environmentConfigService));

            BasicEnvironmentConfig uat = new BasicEnvironmentConfig(new CaseInsensitiveString("uat"));
            uat.addAgent(UUID);
            uat.addAgent(UUID2);
            List<String> noAgents = emptyList();
            agentService.updateAgentsAssociationOfEnvironment(uat, noAgents, List.of(UUID, UUID2));

            assertThat(environmentConfigService.getAgentEnvironmentNames(UUID).size()).isEqualTo(0);
            assertThat(environmentConfigService.getAgentEnvironmentNames(UUID2).size()).isEqualTo(0);
        }

        @Test
        void shouldAddRemoveEnvFromSpecifiedAgentsForPatchRequest() {
            createEnvironment("uat");

            createEnabledAgent(UUID);
            createEnabledAgent(UUID2);
            String UUID3 = "uuid3";
            createEnabledAgent(UUID3);

            assertDoesNotThrow(() -> agentService.bulkUpdateAgentAttributes(List.of(UUID, UUID2), emptyStrList, emptyStrList, List.of("uat"), emptyStrList, TRUE, environmentConfigService));

            BasicEnvironmentConfig uat = new BasicEnvironmentConfig(new CaseInsensitiveString("uat"));
            uat.addAgent(UUID);
            uat.addAgent(UUID2);
            agentService.updateAgentsAssociationOfEnvironment(uat, List.of(UUID, UUID3), List.of(UUID2));

            assertThat(environmentConfigService.getAgentEnvironmentNames(UUID)).contains("uat");
            assertThat(environmentConfigService.getAgentEnvironmentNames(UUID2).size()).isEqualTo(0);
            assertThat(environmentConfigService.getAgentEnvironmentNames(UUID3)).contains("uat");
        }

        @Test
        void shouldNotFailWhenAddingAgentsEnvironmentThatAlreadyExist() {
            createEnvironment("uat");

            createEnabledAgent(UUID);
            createEnabledAgent(UUID2);

            agentService.bulkUpdateAgentAttributes(List.of(UUID, UUID2), emptyStrList, emptyStrList,
                    List.of("uat"), emptyStrList, TRUE, environmentConfigService);

            agentService.bulkUpdateAgentAttributes(List.of(UUID, UUID2), emptyStrList, emptyStrList,
                    List.of("uat"), emptyStrList, TRUE, environmentConfigService);

            assertThat(environmentConfigService.getAgentEnvironmentNames(UUID)).contains("uat");
            assertThat(environmentConfigService.getAgentEnvironmentNames(UUID2)).contains("uat");
        }

        @Test
        void shouldBeAbleToRemoveAgentsEnvironments() {
            createEnvironment("uat", "prod");

            Agent enabledAgent1 = createEnabledAgent(UUID);
            enabledAgent1.setEnvironments("uat");
            agentDao.saveOrUpdate(enabledAgent1);

            Agent enabledAgent2 = createEnabledAgent(UUID2);
            enabledAgent2.setEnvironments("uat,prod");
            agentDao.saveOrUpdate(enabledAgent2);

            assertDoesNotThrow(() -> agentService.bulkUpdateAgentAttributes(List.of(UUID, UUID2), emptyStrList, emptyStrList, emptyStrList, List.of("uat"), TRUE, environmentConfigService));

            assertTrue(environmentConfigService.getAgentEnvironmentNames(UUID).isEmpty());

            assertEquals(1, environmentConfigService.getAgentEnvironmentNames(UUID2).size());
            assertThat(environmentConfigService.getAgentEnvironmentNames(UUID2)).contains("prod");
        }

        @Test
        void shouldOnlyAddRemoveAgentsEnvironmentThatAreRequested() {
            createEnvironment("uat", "prod", "perf", "test", "dev");

            Agent agent1 = createEnabledAgent(UUID);
            agent1.setEnvironments("uat,prod");
            agentDao.saveOrUpdate(agent1);

            Agent agent2 = createEnabledAgent(UUID2);
            agent2.setEnvironments("prod,uat,perf");
            agentDao.saveOrUpdate(agent2);

            assertDoesNotThrow(() -> agentService.bulkUpdateAgentAttributes(List.of(UUID, UUID2), emptyStrList, emptyStrList, List.of("dev", "test", "perf"), List.of("uat", "prod"), TRUE, environmentConfigService));

            String[] expectedEnvs = new String[]{"perf", "dev", "test"};
            assertThat(environmentConfigService.getAgentEnvironmentNames(UUID).size()).isEqualTo(3);
            assertThat(environmentConfigService.getAgentEnvironmentNames(UUID)).contains(expectedEnvs);

            assertThat(environmentConfigService.getAgentEnvironmentNames(UUID2).size()).isEqualTo(3);
            assertThat(environmentConfigService.getAgentEnvironmentNames(UUID2)).contains(expectedEnvs);
        }

        @Test
        void shouldNotFailWhenAgentIsAssociatedWithNonExistingEnvironment() {
            createEnabledAgent(UUID);
            createEnabledAgent(UUID2);

            assertDoesNotThrow(() -> agentService.bulkUpdateAgentAttributes(List.of(UUID, UUID2), emptyStrList, emptyStrList, List.of("non-existent-env"), emptyStrList, TRUE, environmentConfigService));
        }

        @Test
        void shouldNotFailWhenAgentAssociatedIsAskedToBeRemovedFromNonExistingEnvironment() {
            createEnabledAgent(UUID);
            createEnabledAgent(UUID2);

            assertDoesNotThrow(() -> agentService.bulkUpdateAgentAttributes(List.of(UUID, UUID2), emptyStrList, emptyStrList, emptyStrList, List.of("non-existent-env"), TRUE, environmentConfigService));
        }
    }

    private void bulkUpdateEnvironments(Agent... agents) {
        List<String> uuids = Stream.of(agents).map(Agent::getUuid).collect(toList());
        List<String> envsConfig = List.of(randomName("env"), randomName("env"), randomName("env"));
        agentService.bulkUpdateAgentAttributes(uuids, null, null, envsConfig, null, TRUE, environmentConfigService);
    }

    private void bulkUpdateResources(Agent... agents) {
        List<String> uuids = Stream.of(agents).map(Agent::getUuid).collect(toList());
        String res1 = randomName("res");
        String res2 = randomName("res");
        agentService.bulkUpdateAgentAttributes(uuids, List.of(res1, res2), null, emptyStrList, null, TRUE, environmentConfigService);
    }

    private void updateAgentHostnames(Agent... agents) {
        List<String> uuids = Stream.of(agents).map(Agent::getUuid).toList();
        uuids.forEach(uuid -> agentService.updateAgentAttributes(uuid, randomName("hostname"), null, null, TRUE));
    }

    private String randomName(String namePrefix) {
        return namePrefix + new Random().nextLong();
    }

    @Test
    void whenMultipleThreadsUpdateAgentDetailsInDBTheAgentsCacheShouldAlwaysBeInSyncWithAgentsInDB() {
        final int numOfThreads = 30;

        @SuppressWarnings("resource")
        ExecutorService execService = Executors.newFixedThreadPool(numOfThreads);
        try {
            Collection<Future<?>> futures = new ArrayList<>(numOfThreads);

            final Agent agent1 = AgentMother.localAgent();
            final Agent agent2 = AgentMother.localAgent();
            final Agent agent3 = AgentMother.localAgent();

            agentDao.saveOrUpdate(agent1);
            agentDao.saveOrUpdate(agent2);
            agentDao.saveOrUpdate(agent3);

            for (int i = 0; i < (numOfThreads / 2); i++) {
                futures.add(execService.submit(() -> bulkUpdateEnvironments(agent1)));
                futures.add(execService.submit(() -> bulkUpdateResources(agent1, agent2, agent3)));
                futures.add(execService.submit(() -> updateAgentHostnames(agent1)));
                futures.add(execService.submit(() -> agentService.getAgentByUUID(agent1.getUuid())));
                futures.add(execService.submit(() -> agentService.register(AgentMother.localAgent())));
            }

            joinFutures(futures, numOfThreads);

            assertThat(agentDao.fetchAgentFromDBByUUID(agent1.getUuid())).isEqualTo(agentService.findAgent(agent1.getUuid()).getAgent());
            assertThat(agentDao.fetchAgentFromDBByUUID(agent2.getUuid())).isEqualTo(agentService.findAgent(agent2.getUuid()).getAgent());
            assertThat(agentDao.fetchAgentFromDBByUUID(agent3.getUuid())).isEqualTo(agentService.findAgent(agent3.getUuid()).getAgent());
        } finally {
            execService.shutdownNow();
        }
    }

    private void joinFutures(Collection<Future<?>> futures, int numOfThreads) {
        int count = 0;
        for (Future<?> f : futures) {
            try {
                f.get();
                count++;
            } catch (InterruptedException | ExecutionException e) {
                System.out.println(e.getMessage());
                e.printStackTrace();
            }
        }
        assertThat(count).isEqualTo(numOfThreads / 2 * 5);
    }

    private AgentStatusChangeListener agentStatusChangeListener() {
        return agentInstance -> {};
    }

    private void createEnvironment(String... environmentNames) {
        CONFIG_HELPER.addEnvironments(environmentNames);
        goConfigService.forceNotifyListeners();
    }

    private void createMergeEnvironment(String envName, String agentUuid) {
        RepoConfigOrigin repoConfigOrigin = PartialConfigMother.createRepoOrigin();
        ConfigRepoConfig configRepo = repoConfigOrigin.getConfigRepo();
        PartialConfig partialConfig = new PartialConfig();
        BasicEnvironmentConfig envConfig = new BasicEnvironmentConfig(new CaseInsensitiveString(envName));
        envConfig.addAgent(agentUuid);
        partialConfig.getEnvironments().add(envConfig);
        partialConfig.setOrigins(repoConfigOrigin);
        goConfigService.updateConfig(cruiseConfig -> {
            cruiseConfig.getConfigRepos().add(configRepo);
            cruiseConfig.getPartials().add(partialConfig);
            return cruiseConfig;
        });
        goConfigService.forceNotifyListeners();
    }

    private Agent createEnabledAgent(String uuid) {
        Agent agent = new Agent(uuid, "agentName", "127.0.0.9", uuid);
        requestRegistrationAndApproveAgent(agent);
        return agent;
    }

    private void disableAgent(Agent agent) {
        AgentIdentifier agentIdentifier = agent.getAgentIdentifier();
        String cookie = agentService.assignCookie(agentIdentifier);
        AgentRuntimeInfo agentRuntimeInfo = new AgentRuntimeInfo(agentIdentifier, AgentRuntimeStatus.Idle, currentWorkingDirectory(), cookie);
        agentRuntimeInfo.busy(new AgentBuildingInfo("path", "buildLocator"));
        agentService.updateRuntimeInfo(agentRuntimeInfo);

        agentService.bulkUpdateAgentAttributes(List.of(agent.getUuid()), emptyStrList, emptyStrList, emptyStrList, emptyStrList, FALSE, environmentConfigService);

        assertThat(isDisabled(agent)).isTrue();
    }

    private boolean isDisabled(Agent agent) {
        return agentService.findAgentAndRefreshStatus(agent.getUuid()).isDisabled();
    }

    public void requestRegistrationAndApproveAgent(Agent agent) {
        agentService.requestRegistration(fromServer(agent, false, "/var/lib", 0L, "linux"));
        agentService.approve(agent.getUuid());
    }

    private Agent createDisabledAgentWithBuildingRuntimeStatus(String uuid) {
        Agent agent = new Agent(uuid, "agentName", "127.0.0.9");
        requestRegistrationAndApproveAgent(agent);
        disableAgent(agent);
        return agent;
    }

    private Agent createAnIdleAgentAndDisableIt(String uuid) {
        Agent agent = new Agent(uuid, "agentName", "127.0.0.9");
        requestRegistrationAndApproveAgent(agent);

        // Make an agent idle
        AgentIdentifier agentIdentifier = agent.getAgentIdentifier();
        String cookie = agentService.assignCookie(agentIdentifier);
        AgentRuntimeInfo agentRuntimeInfo = new AgentRuntimeInfo(agentIdentifier, AgentRuntimeStatus.Idle, currentWorkingDirectory(), cookie);
        agentRuntimeInfo.idle();
        agentService.updateRuntimeInfo(agentRuntimeInfo);
        assertTrue(agentService.findAgentAndRefreshStatus(uuid).isIdle());

        // Disable the agent
        agentService.bulkUpdateAgentAttributes(List.of(agent.getUuid()), emptyStrList,
                emptyStrList, emptyStrList, emptyStrList, FALSE, environmentConfigService);
        Agent updatedAgent = agentService.getAgentByUUID(agent.getUuid());
        assertThat(isDisabled(updatedAgent)).isTrue();

        return updatedAgent;
    }

    private AgentInstance getFirstAgent() {
        return stream(agentService.getAgentInstances().spliterator(), false).findFirst().orElseThrow();
    }

    private Set<String> getEnvironments(String uuid) {
        return environmentConfigService.getAgentEnvironmentNames(uuid);
    }

    private AgentStatusChangeNotifier agentStatusChangeNotifier() {
        return new MockAgentStatusChangeNotifier();
    }

    private static class MockAgentStatusChangeNotifier extends AgentStatusChangeNotifier {
        public MockAgentStatusChangeNotifier() {
            super(null, null);
        }

        @Override
        public void onAgentStatusChange(AgentInstance agentInstance) {
        }
    }
}
