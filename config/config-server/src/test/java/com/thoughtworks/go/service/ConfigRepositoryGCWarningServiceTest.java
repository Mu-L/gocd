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
package com.thoughtworks.go.service;

import com.thoughtworks.go.serverhealth.*;
import com.thoughtworks.go.util.SystemEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.thoughtworks.go.CurrentGoCDVersion.docsUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ConfigRepositoryGCWarningServiceTest {

    private ConfigRepository configRepository;
    private ServerHealthService serverHealthService;
    private SystemEnvironment systemEnvironment;
    private ConfigRepositoryGCWarningService service;

    @BeforeEach
    public void setUp() {
        configRepository = mock(ConfigRepository.class);
        serverHealthService = new ServerHealthService();
        systemEnvironment = mock(SystemEnvironment.class);
        service = new ConfigRepositoryGCWarningService(configRepository, serverHealthService, systemEnvironment);
    }

    @Test
    public void shouldAddWarningWhenConfigRepoLooseObjectCountGoesBeyondTheConfiguredThreshold() throws Exception {
        when(systemEnvironment.get(SystemEnvironment.GO_CONFIG_REPO_GC_LOOSE_OBJECT_WARNING_THRESHOLD)).thenReturn(10L);
        when(configRepository.getLooseObjectCount()).thenReturn(20L);

        service.checkRepoAndAddWarningIfRequired();
        List<ServerHealthState> healthStates = serverHealthService.logsSortedForScope(HealthStateScope.forConfigRepo("GC"));
        String message = "Action required: Run 'git gc' on config.git repo";
        String description = "Number of loose objects in your Configuration repository(config.git) has grown beyond " +
                "the configured threshold. As the size of config repo increases, the config save operations tend to slow down " +
                "drastically. It is recommended that you run 'git gc' from " +
                "'&lt;go server installation directory&gt;/db/config.git/' to address this problem. Go can do this " +
                "automatically on a periodic basis if you enable automatic GC. <a target='_blank' href='" + docsUrl("/advanced_usage/config_repo.html") + "'>read more...</a>";

        assertThat(healthStates.get(0).getDescription()).isEqualTo(description);
        assertThat(healthStates.get(0).getLogLevel()).isEqualTo(HealthStateLevel.WARNING);
        assertThat(healthStates.get(0).getMessage()).isEqualTo(message);
    }

    @Test
    public void shouldNotAddWarningWhenConfigRepoLooseObjectCountIsBelowTheConfiguredThreshold() throws Exception {
        when(systemEnvironment.get(SystemEnvironment.GO_CONFIG_REPO_GC_LOOSE_OBJECT_WARNING_THRESHOLD)).thenReturn(10L);
        when(configRepository.getLooseObjectCount()).thenReturn(1L);

        service.checkRepoAndAddWarningIfRequired();
        List<ServerHealthState> healthStates = serverHealthService.logsSortedForScope(HealthStateScope.forConfigRepo("GC"));
        assertThat(healthStates.isEmpty()).isTrue();
    }

    @Test
    public void shouldRemoteExistingWarningAboutGCIfLooseObjectCountGoesBelowTheSetThreshold() throws Exception {
        serverHealthService.update(ServerHealthState.warning("message", "description", HealthStateType.general(HealthStateScope.forConfigRepo("GC"))));
        assertThat(serverHealthService.logsSortedForScope(HealthStateScope.forConfigRepo("GC")).isEmpty()).isFalse();

        when(systemEnvironment.get(SystemEnvironment.GO_CONFIG_REPO_GC_LOOSE_OBJECT_WARNING_THRESHOLD)).thenReturn(10L);
        when(configRepository.getLooseObjectCount()).thenReturn(1L);

        service.checkRepoAndAddWarningIfRequired();
        assertThat(serverHealthService.logsSortedForScope(HealthStateScope.forConfigRepo("GC")).isEmpty()).isTrue();
    }

}
