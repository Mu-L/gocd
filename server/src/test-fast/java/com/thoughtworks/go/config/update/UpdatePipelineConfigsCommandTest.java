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
package com.thoughtworks.go.config.update;

import com.thoughtworks.go.config.*;
import com.thoughtworks.go.config.exceptions.RecordNotFoundException;
import com.thoughtworks.go.config.remote.RepoConfigOrigin;
import com.thoughtworks.go.domain.PipelineGroups;
import com.thoughtworks.go.helper.GoConfigMother;
import com.thoughtworks.go.helper.PipelineConfigMother;
import com.thoughtworks.go.server.domain.Username;
import com.thoughtworks.go.server.service.EntityHashingService;
import com.thoughtworks.go.server.service.SecurityService;
import com.thoughtworks.go.server.service.result.HttpLocalizedOperationResult;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class UpdatePipelineConfigsCommandTest {
    @Mock
    private EntityHashingService entityHashingService;

    @Mock
    private SecurityService securityService;

    private PipelineConfigs pipelineConfigs;
    private Authorization authorization;
    private Username user;
    private BasicCruiseConfig cruiseConfig;

    @BeforeEach
    public void setup() {
        authorization = new Authorization(new AdminsConfig(new AdminUser(new CaseInsensitiveString("user"))));
        pipelineConfigs = new BasicPipelineConfigs("group", authorization);
        cruiseConfig = GoConfigMother.defaultCruiseConfig();
        cruiseConfig.setGroup(new PipelineGroups(pipelineConfigs));
        cruiseConfig.server().security().addRole(new RoleConfig("validRole"));
        user = new Username(new CaseInsensitiveString("user"));
    }

    @Test
    public void shouldReplaceOnlyPipelineConfigsAuthorizationWhileUpdatingTheTemplate() {
        Authorization newAuthorization = new Authorization(new AdminsConfig(new AdminRole(new CaseInsensitiveString("foo"))));
        PipelineConfigs newPipelineConfig = new BasicPipelineConfigs("group", newAuthorization);

        UpdatePipelineConfigsCommand command = new UpdatePipelineConfigsCommand(this.pipelineConfigs, newPipelineConfig, new HttpLocalizedOperationResult(), user, "digest", entityHashingService, securityService);
        command.update(cruiseConfig);

        assertThat(cruiseConfig.hasPipelineGroup(this.pipelineConfigs.getGroup())).isTrue();
        Authorization expectedTemplateAuthorization = cruiseConfig.findGroup(this.pipelineConfigs.getGroup()).getAuthorization();
        assertNotEquals(expectedTemplateAuthorization, authorization);
        assertThat(expectedTemplateAuthorization).isEqualTo(newAuthorization);
    }

    @Test
    public void commandShouldBeValid_whenRoleIsValid() {
        Authorization newAuthorization = new Authorization(new AdminsConfig(new AdminRole(new CaseInsensitiveString("validRole"))));
        PipelineConfigs newPipelineConfig = new BasicPipelineConfigs("group", newAuthorization);

        UpdatePipelineConfigsCommand command = new UpdatePipelineConfigsCommand(this.pipelineConfigs, newPipelineConfig, new HttpLocalizedOperationResult(), user, "digest", entityHashingService, securityService);
        command.isValid(cruiseConfig);
        assertThat(authorization.getAllErrors()).isEqualTo(Collections.emptyList());
    }

    @Test
    public void commandShouldCopyOverErrors_whenRoleIsInvalid() {
        Authorization newAuthorization = new Authorization(new AdminsConfig(new AdminRole(new CaseInsensitiveString("invalidRole"))));
        PipelineConfigs newPipelineConfig = new BasicPipelineConfigs("group", newAuthorization);

        UpdatePipelineConfigsCommand command = new UpdatePipelineConfigsCommand(this.pipelineConfigs, newPipelineConfig, new HttpLocalizedOperationResult(), user, "digest", entityHashingService, securityService);
        command.update(cruiseConfig);
        assertFalse(command.isValid(cruiseConfig));

        assertThat(newAuthorization.getAllErrors().get(0).getAllOn("roles")).isEqualTo(List.of("Role \"invalidRole\" does not exist."));
    }

    @Test
    public void commandShouldThrowExceptionAndReturnUnprocessableEntityResult_whenValidatingWithNullGroupName() {
        Authorization newAuthorization = new Authorization(new AdminsConfig(new AdminRole(new CaseInsensitiveString("validRole"))));
        PipelineConfigs newPipelineConfig = new BasicPipelineConfigs(null, newAuthorization);

        HttpLocalizedOperationResult result = new HttpLocalizedOperationResult();
        UpdatePipelineConfigsCommand command = new UpdatePipelineConfigsCommand(this.pipelineConfigs, newPipelineConfig, result, user, "digest", entityHashingService, securityService);

        assertThatThrownBy(() -> command.isValid(cruiseConfig))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Group name cannot be null.");

        assertThat(result.httpCode()).isEqualTo(HttpStatus.SC_UNPROCESSABLE_ENTITY);
        assertThat(result.message()).isEqualTo("The group is invalid. Attribute 'name' cannot be null.");
    }

    @Test
    public void commandShouldThrowIllegalArgumentException_whenValidatingWithBlankGroupName() {
        Authorization newAuthorization = new Authorization(new AdminsConfig(new AdminRole(new CaseInsensitiveString("validRole"))));
        PipelineConfigs newPipelineConfig = new BasicPipelineConfigs("  ", newAuthorization);

        HttpLocalizedOperationResult result = new HttpLocalizedOperationResult();
        UpdatePipelineConfigsCommand command = new UpdatePipelineConfigsCommand(this.pipelineConfigs, newPipelineConfig, result, user, "digest", entityHashingService, securityService);

        assertThatThrownBy(() -> command.isValid(cruiseConfig))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Group name cannot be null.");
    }

    @Test
    public void commandShouldThrowRecordNotFoundException_whenValidatingWithNonExistentGroup() {
        Authorization newAuthorization = new Authorization(new AdminsConfig(new AdminRole(new CaseInsensitiveString("validRole"))));
        PipelineConfigs newPipelineConfig = new BasicPipelineConfigs("group", newAuthorization);
        this.pipelineConfigs.setGroup(null);

        HttpLocalizedOperationResult result = new HttpLocalizedOperationResult();
        UpdatePipelineConfigsCommand command = new UpdatePipelineConfigsCommand(this.pipelineConfigs, newPipelineConfig, result, user, "digest", entityHashingService, securityService);

        assertThatThrownBy(() -> command.isValid(cruiseConfig))
                .isInstanceOf(RecordNotFoundException.class);
    }

    @Test
    public void commandShouldContinue_whenRequestIsFreshAndUserIsAuthorized() {
        when(securityService.isUserAdminOfGroup(user, "group")).thenReturn(true);
        when(entityHashingService.hashForEntity(pipelineConfigs)).thenReturn("digest");

        Authorization newAuthorization = new Authorization(new AdminsConfig(new AdminRole(new CaseInsensitiveString("validRole"))));
        PipelineConfigs newPipelineConfig = new BasicPipelineConfigs("group", newAuthorization);

        HttpLocalizedOperationResult result = new HttpLocalizedOperationResult();
        UpdatePipelineConfigsCommand command = new UpdatePipelineConfigsCommand(this.pipelineConfigs, newPipelineConfig, result, user, "digest", entityHashingService, securityService);

        assertTrue(command.canContinue(cruiseConfig));
    }

    @Test
    public void commandShouldNotContinue_whenRequestIsNotFresh() {
        when(entityHashingService.hashForEntity(pipelineConfigs)).thenReturn("digest-old");

        Authorization newAuthorization = new Authorization(new AdminsConfig(new AdminRole(new CaseInsensitiveString("validRole"))));
        PipelineConfigs newPipelineConfig = new BasicPipelineConfigs("group", newAuthorization);
        HttpLocalizedOperationResult result = new HttpLocalizedOperationResult();
        UpdatePipelineConfigsCommand command = new UpdatePipelineConfigsCommand(this.pipelineConfigs, newPipelineConfig, result, user, "digest", entityHashingService, securityService);

        assertFalse(command.canContinue(cruiseConfig));
        assertThat(result.httpCode()).isEqualTo(HttpStatus.SC_PRECONDITION_FAILED);
    }

    @Test
    public void commandShouldNotContinue_whenUserUnauthorized() {
        when(securityService.isUserAdminOfGroup(user, "group")).thenReturn(false);
        when(entityHashingService.hashForEntity(pipelineConfigs)).thenReturn("digest");

        Authorization newAuthorization = new Authorization(new AdminsConfig(new AdminRole(new CaseInsensitiveString("validRole"))));
        PipelineConfigs newPipelineConfig = new BasicPipelineConfigs("group", newAuthorization);
        HttpLocalizedOperationResult result = new HttpLocalizedOperationResult();
        UpdatePipelineConfigsCommand command = new UpdatePipelineConfigsCommand(this.pipelineConfigs, newPipelineConfig, result, user, "digest", entityHashingService, securityService);

        assertFalse(command.canContinue(cruiseConfig));
        assertThat(result.httpCode()).isEqualTo(HttpStatus.SC_FORBIDDEN);
    }

    @Test
    public void commandShouldContinue_whenPipelineGroupNameIsModified() {
        when(securityService.isUserAdminOfGroup(user, "group")).thenReturn(true);
        when(entityHashingService.hashForEntity(pipelineConfigs)).thenReturn("digest");

        Authorization newAuthorization = new Authorization(new AdminsConfig(new AdminRole(new CaseInsensitiveString("validRole"))));
        PipelineConfigs newPipelineConfig = new BasicPipelineConfigs("group-2", newAuthorization);

        HttpLocalizedOperationResult result = new HttpLocalizedOperationResult();
        UpdatePipelineConfigsCommand command = new UpdatePipelineConfigsCommand(this.pipelineConfigs, newPipelineConfig, result, user, "digest", entityHashingService, securityService);

        assertTrue(command.canContinue(cruiseConfig));
    }

    @Test
    public void commandShouldNotContinue_whenPipelineGroupNameIsModifiedWhileItContainsRemotePipelines() {
        when(securityService.isUserAdminOfGroup(user, "group")).thenReturn(true);
        when(entityHashingService.hashForEntity(pipelineConfigs)).thenReturn("digest");
        PipelineConfig remotePipeline = PipelineConfigMother.pipelineConfig("remote-pipeline");
        remotePipeline.setOrigin(new RepoConfigOrigin());
        this.pipelineConfigs.add(remotePipeline);

        Authorization newAuthorization = new Authorization(new AdminsConfig(new AdminRole(new CaseInsensitiveString("validRole"))));
        PipelineConfigs newPipelineConfig = new BasicPipelineConfigs("group-2", newAuthorization);

        HttpLocalizedOperationResult result = new HttpLocalizedOperationResult();
        UpdatePipelineConfigsCommand command = new UpdatePipelineConfigsCommand(this.pipelineConfigs, newPipelineConfig, result, user, "digest", entityHashingService, securityService);

        assertFalse(command.canContinue(cruiseConfig));
        assertFalse(result.isSuccessful());
        assertThat(result.message()).isEqualTo("Can not rename pipeline group 'group' as it contains remote pipelines.");
    }
}
