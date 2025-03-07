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
package com.thoughtworks.go.plugin.access.artifact;

import com.thoughtworks.go.plugin.domain.artifact.ArtifactPluginInfo;
import com.thoughtworks.go.plugin.domain.artifact.Capabilities;
import com.thoughtworks.go.plugin.domain.common.*;
import com.thoughtworks.go.plugin.infra.plugininfo.GoPluginDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

public class ArtifactPluginInfoBuilderTest {

    private ArtifactExtension extension;

    @BeforeEach
    public void setUp() {
        extension = mock(ArtifactExtension.class);
    }

    @Test
    public void shouldBuildPluginInfoWithCapabilities() {
        GoPluginDescriptor descriptor = GoPluginDescriptor.builder().id("plugin1").build();

        when(extension.getCapabilities(descriptor.id())).thenReturn(new Capabilities());

        ArtifactPluginInfo pluginInfo = new ArtifactPluginInfoBuilder(extension).pluginInfoFor(descriptor);

        assertNotNull(pluginInfo.getCapabilities());
    }

    @Test
    public void shouldBuildPluginInfoWithStoreSettings() {
        GoPluginDescriptor descriptor = GoPluginDescriptor.builder().id("plugin1").build();
        List<PluginConfiguration> pluginConfigurations = List.of(
                new PluginConfiguration("S3_BUCKET", new Metadata(true, false)),
                new PluginConfiguration("AWS_ACCESS_KEY_ID", new Metadata(true, true))
        );

        when(extension.getArtifactStoreMetadata(descriptor.id())).thenReturn(pluginConfigurations);
        when(extension.getArtifactStoreView(descriptor.id())).thenReturn("store_config");

        ArtifactPluginInfo pluginInfo = new ArtifactPluginInfoBuilder(extension).pluginInfoFor(descriptor);

        assertThat(pluginInfo.getStoreConfigSettings()).isEqualTo(new PluggableInstanceSettings(pluginConfigurations, new PluginView("store_config")));
    }

    @Test
    public void shouldBuildPluginInfoWithPublishArtifactConfigSettings() {
        GoPluginDescriptor descriptor = GoPluginDescriptor.builder().id("plugin1").build();
        List<PluginConfiguration> pluginConfigurations = List.of(
                new PluginConfiguration("FILENAME", new Metadata(true, false))
        );

        when(extension.getPublishArtifactMetadata(descriptor.id())).thenReturn(pluginConfigurations);
        when(extension.getPublishArtifactView(descriptor.id())).thenReturn("artifact_config");

        ArtifactPluginInfo pluginInfo = new ArtifactPluginInfoBuilder(extension).pluginInfoFor(descriptor);

        assertThat(pluginInfo.getArtifactConfigSettings()).isEqualTo(new PluggableInstanceSettings(pluginConfigurations, new PluginView("artifact_config")));
    }

    @Test
    public void shouldBuildPluginInfoWithFetchArtifactConfigSettings() {
        GoPluginDescriptor descriptor = GoPluginDescriptor.builder().id("plugin1").build();
        List<PluginConfiguration> pluginConfigurations = List.of(
                new PluginConfiguration("FILENAME", new Metadata(true, false)),
                new PluginConfiguration("SECURE", new Metadata(true, true))
        );

        when(extension.getFetchArtifactMetadata(descriptor.id())).thenReturn(pluginConfigurations);
        when(extension.getFetchArtifactView(descriptor.id())).thenReturn("fetch_artifact_view");

        ArtifactPluginInfo pluginInfo = new ArtifactPluginInfoBuilder(extension).pluginInfoFor(descriptor);

        assertThat(pluginInfo.getFetchArtifactSettings()).isEqualTo(new PluggableInstanceSettings(pluginConfigurations, new PluginView("fetch_artifact_view")));
    }

    @Test
    public void shouldContinueWithBuildingPluginInfoIfPluginSettingsIsNotProvidedByPlugin() {
        GoPluginDescriptor descriptor = GoPluginDescriptor.builder().id("plugin1").build();
        doThrow(new RuntimeException("foo")).when(extension).getPluginSettingsConfiguration("plugin1");

        ArtifactPluginInfo artifactPluginInfo = new ArtifactPluginInfoBuilder(extension).pluginInfoFor(descriptor);

        assertThat(artifactPluginInfo.getDescriptor()).isEqualTo(descriptor);
        assertThat(artifactPluginInfo.getExtensionName()).isEqualTo(PluginConstants.ARTIFACT_EXTENSION);
    }
}
