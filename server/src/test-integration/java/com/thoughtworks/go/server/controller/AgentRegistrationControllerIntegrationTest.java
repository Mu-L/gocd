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
package com.thoughtworks.go.server.controller;

import com.rits.cloning.Cloner;
import com.thoughtworks.go.config.Agent;
import com.thoughtworks.go.domain.AgentConfigStatus;
import com.thoughtworks.go.domain.AgentInstance;
import com.thoughtworks.go.server.service.AgentRuntimeInfo;
import com.thoughtworks.go.server.service.AgentService;
import com.thoughtworks.go.server.service.EphemeralAutoRegisterKeyService;
import com.thoughtworks.go.server.service.GoConfigService;
import com.thoughtworks.go.server.util.UuidGenerator;
import com.thoughtworks.go.util.ClonerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.Properties;
import java.util.UUID;

import static com.thoughtworks.go.util.SystemEnvironment.AUTO_REGISTER_LOCAL_AGENT_ENABLED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.HttpStatus.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = {
        "classpath:/applicationContext-global.xml",
        "classpath:/applicationContext-dataLocalAccess.xml",
        "classpath:/testPropertyConfigurer.xml",
        "classpath:/spring-all-servlet.xml",
})

public class AgentRegistrationControllerIntegrationTest {
    @Autowired
    private AgentRegistrationController controller;
    @Autowired
    private GoConfigService goConfigService;
    @Autowired
    private AgentService agentService;
    @Autowired
    private UuidGenerator uuidGenerator;
    @Autowired
    EphemeralAutoRegisterKeyService ephemeralAutoRegisterKeyService;

    static final Cloner CLONER = ClonerFactory.instance();
    private Properties original;

    @BeforeEach
    public void before() {
        original = CLONER.deepClone(System.getProperties());
    }

    @AfterEach
    public void after() {
        System.setProperties(original);
    }

    @Test
    public void shouldRegisterLocalAgent() {
        System.setProperty(AUTO_REGISTER_LOCAL_AGENT_ENABLED.propertyName(), "true");
        String uuid = UUID.randomUUID().toString();
        MockHttpServletRequest request = new MockHttpServletRequest();
        final ResponseEntity<String> responseEntity = controller.agentRequest("hostname", uuid, "sandbox", "100", null, null, null, null, null, null, null, controller.hmacOf(uuid), request);
        Agent agent = agentService.getAgentByUUID(uuid);

        assertThat(agent.getHostname()).isEqualTo("hostname");
        assertThat(responseEntity.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(responseEntity.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(responseEntity.getBody()).isEqualTo("");
    }

    @Test
    public void shouldRegisterElasticAgent() {
        String autoRegisterKey = ephemeralAutoRegisterKeyService.autoRegisterKey();
        String uuid = UUID.randomUUID().toString();
        String elasticAgentId = UUID.randomUUID().toString();
        MockHttpServletRequest request = new MockHttpServletRequest();
        final ResponseEntity<String> responseEntity = controller.agentRequest("elastic-agent-hostname",
                uuid,
                "sandbox",
                "100",
                "Alpine Linux v3.5",
                autoRegisterKey,
                "",
                "",
                "hostname",
                elasticAgentId,
                "elastic-plugin-id",
                controller.hmacOf(uuid),
                request);
        Agent agent = agentService.getAgentByUUID(uuid);

        assertTrue(agent.isElastic());
        assertThat(responseEntity.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(responseEntity.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(responseEntity.getBody()).isEqualTo("");
    }

    @Test
    public void shouldNotRegisterElasticAgentWithDuplicateElasticAgentID() {
        String uuid = UUID.randomUUID().toString();
        String elasticAgentId = UUID.randomUUID().toString();
        MockHttpServletRequest request = new MockHttpServletRequest();

        controller.agentRequest("elastic-agent-hostname",
                uuid,
                "sandbox",
                "100",
                "Alpine Linux v3.5",
                ephemeralAutoRegisterKeyService.autoRegisterKey(),
                "",
                "",
                "hostname",
                elasticAgentId,
                "elastic-plugin-id",
            controller.hmacOf(uuid),
                request);
        Agent agent = agentService.getAgentByUUID(uuid);
        assertTrue(agent.isElastic());

        final ResponseEntity<String> responseEntity = controller.agentRequest("elastic-agent-hostname",
                uuid,
                "sandbox",
                "100",
                "Alpine Linux v3.5",
                ephemeralAutoRegisterKeyService.autoRegisterKey(),
                "",
                "",
                "hostname",
                elasticAgentId,
                "elastic-plugin-id",
                controller.hmacOf(uuid),
                request);

        assertThat(responseEntity.getStatusCode()).isEqualTo(UNPROCESSABLE_ENTITY);
        assertThat(responseEntity.getBody()).isEqualTo("Duplicate Elastic agent Id used to register elastic agent.");
    }

    @Test
    public void shouldAddAgentInPendingState() {
        System.setProperty(AUTO_REGISTER_LOCAL_AGENT_ENABLED.propertyName(), "false");
        String uuid = UUID.randomUUID().toString();
        MockHttpServletRequest request = new MockHttpServletRequest();
        final ResponseEntity<String> responseEntity = controller.agentRequest("hostname", uuid, "sandbox", "100", null, null, null, null, null, null, null, controller.hmacOf(uuid), request);
        AgentInstance agentInstance = agentService.findAgent(uuid);

        assertTrue(agentInstance.isPending());
        assertThat(responseEntity.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(responseEntity.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(responseEntity.getBody()).isEqualTo("");
    }

    @Test
    public void shouldAutoRegisterRemoteAgent() {
        System.setProperty(AUTO_REGISTER_LOCAL_AGENT_ENABLED.propertyName(), "false");
        String uuid = UUID.randomUUID().toString();
        MockHttpServletRequest request = new MockHttpServletRequest();
        final ResponseEntity<String> responseEntity = controller.agentRequest("hostname", uuid, "sandbox", "100", null, goConfigService.serverConfig().getAgentAutoRegisterKey(), "", "", null, null, null, controller.hmacOf(uuid), request);
        AgentInstance agentInstance = agentService.findAgent(uuid);

        assertTrue(agentInstance.isIdle());
        assertThat(responseEntity.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(responseEntity.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(responseEntity.getBody()).isEqualTo("");
    }

    @Test
    public void shouldNotRegisterAgentWhenValidationFails() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        int totalAgentsBeforeRegistrationRequest = agentService.findRegisteredAgents().size();
        final ResponseEntity<String> responseEntity = controller.agentRequest("hostname", "", "sandbox", "100", null, null, null, null, null, null, null, controller.hmacOf(""), request);
        int totalAgentsAfterRegistrationRequest = agentService.findRegisteredAgents().size();
        assertThat(totalAgentsBeforeRegistrationRequest).isEqualTo(totalAgentsAfterRegistrationRequest);

        assertThat(responseEntity.getStatusCode()).isEqualTo(UNPROCESSABLE_ENTITY);
        assertThat(responseEntity.getBody()).isEqualTo("Error occurred during agent registration process: UUID cannot be empty");
    }

    @Test
    public void shouldGenerateToken() {
        final String token = controller.hmacOf("uuid-from-agent");

        final ResponseEntity<String> responseEntity = controller.getToken("uuid-from-agent");

        assertThat(responseEntity.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(responseEntity.getBody()).isEqualTo(token);
    }

    @Test
    public void shouldRejectGenerateTokenRequestIfAgentIsInPendingState() {
        System.setProperty(AUTO_REGISTER_LOCAL_AGENT_ENABLED.propertyName(), "false");
        final String uuid = UUID.randomUUID().toString();
        final AgentRuntimeInfo agentRuntimeInfo = AgentRuntimeInfo.fromServer(new Agent(uuid, "hostname", "127.0.01"), false, "sandbox", 0L, "linux");
        agentService.requestRegistration(agentRuntimeInfo);
        final AgentInstance agentInstance = agentService.findAgent(uuid);
        assertTrue(agentInstance.isPending());

        final ResponseEntity<String> responseEntity = controller.getToken(uuid);

        assertThat(responseEntity.getStatusCode()).isEqualTo(CONFLICT);
        assertThat(responseEntity.getBody()).isEqualTo("A token has already been issued for this agent.");
    }

    @Test
    public void shouldRejectGenerateTokenRequestIfAgentIsInConfig() {
        System.setProperty(AUTO_REGISTER_LOCAL_AGENT_ENABLED.propertyName(), "false");
        final String uuid = UUID.randomUUID().toString();
        agentService.saveOrUpdate(new Agent(uuid, "hostname", "127.0.01", uuidGenerator.randomUuid()));
        assertThat(agentService.findAgent(uuid).getAgentConfigStatus()).isEqualTo(AgentConfigStatus.Enabled);

        final ResponseEntity<String> responseEntity = controller.getToken(uuid);

        assertThat(responseEntity.getStatusCode()).isEqualTo(CONFLICT);
        assertThat(responseEntity.getBody()).isEqualTo("A token has already been issued for this agent.");
    }

    @Test
    public void shouldRejectGenerateTokenRequestIfUUIDIsEmpty() {
        final ResponseEntity<String> responseEntity = controller.getToken("               ");

        assertThat(responseEntity.getStatusCode()).isEqualTo(CONFLICT);
        assertThat(responseEntity.getBody()).isEqualTo("UUID cannot be blank.");
    }

    @Test
    public void shouldRejectAgentRegistrationRequestWhenTokenIsInvalid() {
        String uuid = UUID.randomUUID().toString();
        MockHttpServletRequest request = new MockHttpServletRequest();
        final ResponseEntity<String> responseEntity = controller.agentRequest("hostname", uuid, "sandbox", "100", null, null, null, null, null, null, null, "invalid-token", request);

        AgentInstance agentInstance = agentService.findAgent(uuid);

        assertTrue(agentInstance.isNullAgent());
        assertThat(responseEntity.getStatusCode()).isEqualTo(FORBIDDEN);
        assertThat(responseEntity.getBody()).isEqualTo("Not a valid token.");
    }

    @Test
    public void shouldReIssueCertificateIfRegisteredAgentAsksForRegistrationWithoutAutoRegisterKeys() {
        String uuid = UUID.randomUUID().toString();
        agentService.saveOrUpdate(new Agent(uuid, "hostname", "127.0.01", uuidGenerator.randomUuid()));
        assertThat(agentService.findAgent(uuid).getAgentConfigStatus()).isEqualTo(AgentConfigStatus.Enabled);

        MockHttpServletRequest request = new MockHttpServletRequest();
        final ResponseEntity<String> responseEntity = controller.agentRequest("hostname", uuid, "sandbox", "100", null, null, null, null, null, null, null, controller.hmacOf(uuid), request);

        AgentInstance agentInstance = agentService.findAgent(uuid);

        assertTrue(agentInstance.isIdle());
        assertThat(responseEntity.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(responseEntity.getBody()).isEqualTo("");
    }

    @Test
    public void shouldReIssueCertificateIfRegisteredAgentAsksForRegistrationWithAutoRegisterKeys() {
        String uuid = UUID.randomUUID().toString();
        agentService.saveOrUpdate(new Agent(uuid, "hostname", "127.0.01", uuidGenerator.randomUuid()));
        assertThat(agentService.findAgent(uuid).getAgentConfigStatus()).isEqualTo(AgentConfigStatus.Enabled);

        MockHttpServletRequest request = new MockHttpServletRequest();
        final ResponseEntity<String> responseEntity = controller.agentRequest("hostname", uuid, "sandbox", "100", null, goConfigService.serverConfig().getAgentAutoRegisterKey(), "", "", null, null, null, controller.hmacOf(uuid), request);

        AgentInstance agentInstance = agentService.findAgent(uuid);

        assertTrue(agentInstance.isIdle());
        assertThat(responseEntity.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(responseEntity.getBody()).isEqualTo("");
    }
}
