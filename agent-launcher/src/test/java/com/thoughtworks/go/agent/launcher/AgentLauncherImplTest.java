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
package com.thoughtworks.go.agent.launcher;

import com.thoughtworks.cruise.agent.common.launcher.AgentLaunchDescriptor;
import com.thoughtworks.cruise.agent.common.launcher.AgentLauncher;
import com.thoughtworks.go.CurrentGoCDVersion;
import com.thoughtworks.go.agent.common.AgentBootstrapperArgs;
import com.thoughtworks.go.agent.testhelper.FakeGoServer;
import com.thoughtworks.go.agent.testhelper.FakeGoServerExtension;
import com.thoughtworks.go.agent.testhelper.GoTestResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.thoughtworks.go.agent.common.util.Downloader.*;
import static com.thoughtworks.go.agent.testhelper.FakeGoServer.TestResource.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(FakeGoServerExtension.class)
public class AgentLauncherImplTest {

    @GoTestResource
    public FakeGoServer server;

    @BeforeEach
    public void setUp() throws IOException {
        cleanup();
    }

    @AfterEach
    public void tearDown() throws IOException {
        cleanup();
    }

    private void cleanup() throws IOException {
        Files.deleteIfExists(AGENT_PLUGINS_ZIP.toPath());
        Files.deleteIfExists(AGENT_BINARY_JAR.toPath());
        Files.deleteIfExists(AGENT_LAUNCHER_JAR.toPath());
        Files.deleteIfExists(TFS_IMPL_JAR.toPath());
        new Lockfile(new File(AgentLauncherImpl.AGENT_BOOTSTRAPPER_LOCK_FILE)).delete();
    }

    @Test
    public void shouldPassLauncherVersionToAgent() throws IOException {
        final List<String> actualVersion = new ArrayList<>();
        final AgentLauncher launcher = new AgentLauncherImpl((launcherVersion, launcherMd5, urlConstructor, environmentVariables, context) -> {
            actualVersion.add(launcherVersion);
            return 0;
        });
        TEST_AGENT_LAUNCHER.copyTo(AGENT_LAUNCHER_JAR);
        launcher.launch(launchDescriptor());

        assertThat(actualVersion.size()).isEqualTo(1);
        assertThat(actualVersion.get(0)).isEqualTo(CurrentGoCDVersion.getInstance().fullVersion());
    }

    @Test
    public void shouldNotThrowException_insteadReturnAppropriateErrorCode_whenSomethingGoesWrongInLaunch() {
        AgentLaunchDescriptor launchDesc = mock(AgentLaunchDescriptor.class);
        when(launchDesc.context().get(AgentBootstrapperArgs.SERVER_URL)).thenThrow(new RuntimeException("Ouch!"));
        assertThat(new AgentLauncherImpl().launch(launchDesc))
            .describedAs("should not have blown up, because it directly interfaces with bootstrapper")
            .isEqualTo(-273);
    }

    private AgentLaunchDescriptor launchDescriptor() {
        AgentLaunchDescriptor launchDescriptor = mock(AgentLaunchDescriptor.class);
        Map<String, String> contextMap = new ConcurrentHashMap<>();
        contextMap.put(AgentBootstrapperArgs.SERVER_URL, "http://localhost:" + server.getPort() + "/go");
        contextMap.put(AgentBootstrapperArgs.SSL_VERIFICATION_MODE, "NONE");
        when(launchDescriptor.context()).thenReturn(contextMap);
        return launchDescriptor;
    }

    @Test
    public void shouldDownloadLauncherJarIfLocalCopyIsStale() throws IOException {
        //because new invocation will take care of pulling latest agent down, and will then operate on it with the latest launcher -jj
        File staleJar = randomFile(AGENT_LAUNCHER_JAR);
        long original = staleJar.length();
        new AgentLauncherImpl().launch(launchDescriptor());
        assertThat(staleJar.length()).isNotEqualTo(original);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void shouldDownload_AgentJar_IfTheCurrentJarIsStale() throws Exception {
        TEST_AGENT_LAUNCHER.copyTo(AGENT_LAUNCHER_JAR);
        File staleJar = randomFile(AGENT_BINARY_JAR);
        long original = staleJar.length();
        new AgentLauncherImpl().launch(launchDescriptor());
        assertThat(staleJar.length()).isNotEqualTo(original);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void should_NOT_Download_AgentJar_IfTheCurrentJarIsUpToDate() throws Exception {
        TEST_AGENT_LAUNCHER.copyTo(AGENT_LAUNCHER_JAR);
        TEST_AGENT.copyTo(AGENT_BINARY_JAR);

        assertTrue(AGENT_BINARY_JAR.setLastModified(0));
        new AgentLauncherImpl().launch(launchDescriptor());
        assertThat(AGENT_BINARY_JAR.lastModified()).isEqualTo(0L);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void should_NOT_Download_TfsImplJar_IfTheCurrentJarIsUpToDate() throws Exception {
        TEST_AGENT_LAUNCHER.copyTo(AGENT_LAUNCHER_JAR);
        TEST_AGENT.copyTo(AGENT_BINARY_JAR);
        TEST_TFS_IMPL.copyTo(TFS_IMPL_JAR);

        assertTrue(TFS_IMPL_JAR.setLastModified(0));
        new AgentLauncherImpl().launch(launchDescriptor());
        assertThat(TFS_IMPL_JAR.lastModified()).isEqualTo(0L);
    }

    @Test
    public void shouldDownloadLauncherJarIfLocalCopyIsStale_butShouldReturnWithoutDownloadingOrLaunchingAgent() throws Exception {
        File launcher = randomFile(AGENT_LAUNCHER_JAR);
        long original = launcher.length();
        File agentFile = randomFile(AGENT_BINARY_JAR);
        long originalAgentLength = agentFile.length();
        new AgentLauncherImpl().launch(launchDescriptor());

        assertThat(launcher.length()).isNotEqualTo(original);
        assertThat(agentFile.length()).isEqualTo(originalAgentLength);
    }

    private File randomFile(final File pathname) throws IOException {
        Files.writeString(pathname.toPath(), "some rubbish", StandardCharsets.UTF_8);
        return pathname;
    }
}
