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

import com.thoughtworks.go.config.ArtifactStore;
import com.thoughtworks.go.domain.ArtifactPlan;
import com.thoughtworks.go.plugin.access.ExtensionsRegistry;
import com.thoughtworks.go.plugin.access.artifact.model.PublishArtifactResponse;
import com.thoughtworks.go.plugin.api.request.GoPluginApiRequest;
import com.thoughtworks.go.plugin.api.response.DefaultGoPluginApiResponse;
import com.thoughtworks.go.plugin.api.response.validation.ValidationError;
import com.thoughtworks.go.plugin.api.response.validation.ValidationResult;
import com.thoughtworks.go.plugin.domain.common.Metadata;
import com.thoughtworks.go.plugin.domain.common.PluginConfiguration;
import com.thoughtworks.go.plugin.infra.PluginManager;
import com.thoughtworks.go.util.command.EnvironmentVariableContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static com.thoughtworks.go.plugin.access.artifact.ArtifactExtensionConstants.*;
import static com.thoughtworks.go.plugin.api.response.DefaultGoPluginApiResponse.SUCCESS_RESPONSE_CODE;
import static com.thoughtworks.go.plugin.domain.common.PluginConstants.ARTIFACT_EXTENSION;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

public abstract class ArtifactExtensionTestBase {
    static final String PLUGIN_ID = "foo.plugin";
    PluginManager pluginManager;
    ArtifactExtension artifactExtension;
    ArgumentCaptor<GoPluginApiRequest> requestArgumentCaptor;
    ExtensionsRegistry extensionsRegistry;

    abstract String versionToTestAgainst();

    @BeforeEach
    public void setUp() {
        pluginManager = mock(PluginManager.class);
        extensionsRegistry = mock(ExtensionsRegistry.class);
        artifactExtension = new ArtifactExtension(pluginManager, extensionsRegistry);
        requestArgumentCaptor = ArgumentCaptor.forClass(GoPluginApiRequest.class);

        when(pluginManager.isPluginOfType(ARTIFACT_EXTENSION, PLUGIN_ID)).thenReturn(true);
        when(pluginManager.resolveExtensionVersion(PLUGIN_ID, ARTIFACT_EXTENSION, artifactExtension.goSupportedVersions())).thenReturn(versionToTestAgainst());
    }

    @Test
    public void shouldGetSupportedVersions() {
        assertThat(artifactExtension.goSupportedVersions()).containsExactlyInAnyOrder("1.0", "2.0");
    }

    @Test
    public void shouldRegisterMessageHandler() {
        assertTrue(artifactExtension.getMessageHandler(ArtifactMessageConverterV2.VERSION) instanceof ArtifactMessageConverterV2);
        assertThat(artifactExtension.getMessageHandler("3.0")).isNull();
    }

    @Test
    public void shouldGetArtifactStoreMetadataFromPlugin() {
        String responseBody = "[{\"key\":\"BUCKET_NAME\",\"metadata\":{\"required\":true,\"secure\":false}},{\"key\":\"AWS_ACCESS_KEY\",\"metadata\":{\"required\":true,\"secure\":true}}]";


        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(ARTIFACT_EXTENSION), requestArgumentCaptor.capture())).thenReturn(DefaultGoPluginApiResponse.success(responseBody));

        final List<PluginConfiguration> response = artifactExtension.getArtifactStoreMetadata(PLUGIN_ID);

        final GoPluginApiRequest request = requestArgumentCaptor.getValue();

        assertThat(request.extension()).isEqualTo(ARTIFACT_EXTENSION);
        assertThat(request.requestName()).isEqualTo(REQUEST_STORE_CONFIG_METADATA);
        assertNull(request.requestBody());

        assertThat(response).containsExactlyInAnyOrder(
                new PluginConfiguration("BUCKET_NAME", new Metadata(true, false)),
                new PluginConfiguration("AWS_ACCESS_KEY", new Metadata(true, true))
        );
    }

    @Test
    public void shouldGetArtifactStoreViewFromPlugin() {
        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(ARTIFACT_EXTENSION), requestArgumentCaptor.capture())).thenReturn(new DefaultGoPluginApiResponse(SUCCESS_RESPONSE_CODE, "{ \"template\": \"artifact-store-view\"}"));

        String view = artifactExtension.getArtifactStoreView(PLUGIN_ID);

        final GoPluginApiRequest request = requestArgumentCaptor.getValue();

        assertThat(request.extension()).isEqualTo(ARTIFACT_EXTENSION);
        assertThat(request.requestName()).isEqualTo(REQUEST_STORE_CONFIG_VIEW);
        assertNull(request.requestBody());

        assertThat(view).isEqualTo("artifact-store-view");
    }

    @Test
    public void shouldValidateArtifactStoreConfig() {
        String responseBody = "[{\"message\":\"ACCESS_KEY must not be blank.\",\"key\":\"ACCESS_KEY\"}]";

        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(ARTIFACT_EXTENSION), requestArgumentCaptor.capture())).thenReturn(new DefaultGoPluginApiResponse(SUCCESS_RESPONSE_CODE, responseBody));

        ValidationResult validationResult = artifactExtension.validateArtifactStoreConfig(PLUGIN_ID, Map.of("ACCESS_KEY", ""));

        final GoPluginApiRequest request = requestArgumentCaptor.getValue();

        assertThat(request.extension()).isEqualTo(ARTIFACT_EXTENSION);
        assertThat(request.requestName()).isEqualTo(REQUEST_STORE_CONFIG_VALIDATE);
        assertThat(request.requestBody()).isEqualTo("{\"ACCESS_KEY\":\"\"}");

        assertThat(validationResult.isSuccessful()).isEqualTo(false);
        assertThat(validationResult.getErrors()).containsExactlyInAnyOrder(
                new ValidationError("ACCESS_KEY", "ACCESS_KEY must not be blank.")
        );
    }

    @Test
    public void shouldGetPluggableArtifactMetadataFromPlugin() {
        String responseBody = "[{\"key\":\"FILENAME\",\"metadata\":{\"required\":true,\"secure\":false}},{\"key\":\"VERSION\",\"metadata\":{\"required\":true,\"secure\":true}}]";

        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(ARTIFACT_EXTENSION), requestArgumentCaptor.capture())).thenReturn(DefaultGoPluginApiResponse.success(responseBody));

        final List<PluginConfiguration> response = artifactExtension.getPublishArtifactMetadata(PLUGIN_ID);

        final GoPluginApiRequest request = requestArgumentCaptor.getValue();

        assertThat(request.extension()).isEqualTo(ARTIFACT_EXTENSION);
        assertThat(request.requestName()).isEqualTo(REQUEST_PUBLISH_ARTIFACT_METADATA);
        assertNull(request.requestBody());

        assertThat(response).containsExactlyInAnyOrder(
                new PluginConfiguration("FILENAME", new Metadata(true, false)),
                new PluginConfiguration("VERSION", new Metadata(true, true))
        );
    }

    @Test
    public void shouldGetPluggableArtifactViewFromPlugin() {
        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(ARTIFACT_EXTENSION), requestArgumentCaptor.capture())).thenReturn(new DefaultGoPluginApiResponse(SUCCESS_RESPONSE_CODE, "{ \"template\": \"pluggable-artifact-view\"}"));

        String view = artifactExtension.getPublishArtifactView(PLUGIN_ID);

        final GoPluginApiRequest request = requestArgumentCaptor.getValue();

        assertThat(request.extension()).isEqualTo(ARTIFACT_EXTENSION);
        assertThat(request.requestName()).isEqualTo(REQUEST_PUBLISH_ARTIFACT_VIEW);
        assertNull(request.requestBody());

        assertThat(view).isEqualTo("pluggable-artifact-view");
    }

    @Test
    public void shouldValidatePluggableArtifactConfig() {
        String responseBody = "[{\"message\":\"Filename must not be blank.\",\"key\":\"Filename\"}]";

        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(ARTIFACT_EXTENSION), requestArgumentCaptor.capture())).thenReturn(new DefaultGoPluginApiResponse(SUCCESS_RESPONSE_CODE, responseBody));

        ValidationResult validationResult = artifactExtension.validatePluggableArtifactConfig(PLUGIN_ID, Map.of("Filename", ""));

        final GoPluginApiRequest request = requestArgumentCaptor.getValue();

        assertThat(request.extension()).isEqualTo(ARTIFACT_EXTENSION);
        assertThat(request.requestName()).isEqualTo(REQUEST_PUBLISH_ARTIFACT_VALIDATE);
        assertThat(request.requestBody()).isEqualTo("{\"Filename\":\"\"}");

        assertThat(validationResult.isSuccessful()).isEqualTo(false);
        assertThat(validationResult.getErrors()).containsExactlyInAnyOrder(
                new ValidationError("Filename", "Filename must not be blank.")
        );
    }

    @Test
    public void shouldSubmitPublishArtifactRequest() {
        final String responseBody = """
                {
                  "metadata": {
                    "artifact-version": "10.12.0"
                  }
                }""";

        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(ARTIFACT_EXTENSION), requestArgumentCaptor.capture())).thenReturn(DefaultGoPluginApiResponse.success(responseBody));

        ArtifactPlan artifactPlan = new ArtifactPlan("{\"id\":\"installer\",\"storeId\":\"docker\"}");
        ArtifactStore artifactStore = new ArtifactStore("docker", "cd.go.docker");
        EnvironmentVariableContext env = new EnvironmentVariableContext("foo", "bar");
        final PublishArtifactResponse response = artifactExtension.publishArtifact(PLUGIN_ID, artifactPlan, artifactStore, "/temp", env);

        final GoPluginApiRequest request = requestArgumentCaptor.getValue();

        assertThat(request.extension()).isEqualTo(ARTIFACT_EXTENSION);
        assertThat(request.requestName()).isEqualTo(REQUEST_PUBLISH_ARTIFACT);

        final String expectedJSON = "{" +
                "  \"artifact_plan\": {" +
                "    \"id\": \"installer\"," +
                "    \"storeId\": \"docker\"" +
                "  }," +
                "  \"artifact_store\": {" +
                "    \"configuration\": {}," +
                "    \"id\": \"docker\"" +
                "  }," +
                "  \"environment_variables\": {" +
                "    \"foo\": \"bar\"" +
                "  }," +
                "  \"agent_working_directory\": \"/temp\"" +
                "}";

        assertThatJson(expectedJSON).isEqualTo(request.requestBody());

        assertThat(response.getMetadata()).hasSize(1).containsEntry("artifact-version", "10.12.0");
    }

    @Test
    public void shouldGetFetchArtifactMetadataFromPlugin() {
        String responseBody = "[{\"key\":\"FILENAME\",\"metadata\":{\"required\":true,\"secure\":false}},{\"key\":\"VERSION\",\"metadata\":{\"required\":true,\"secure\":true}}]";

        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(ARTIFACT_EXTENSION), requestArgumentCaptor.capture())).thenReturn(DefaultGoPluginApiResponse.success(responseBody));

        final List<PluginConfiguration> response = artifactExtension.getFetchArtifactMetadata(PLUGIN_ID);

        final GoPluginApiRequest request = requestArgumentCaptor.getValue();

        assertThat(request.extension()).isEqualTo(ARTIFACT_EXTENSION);
        assertThat(request.requestName()).isEqualTo(REQUEST_FETCH_ARTIFACT_METADATA);
        assertNull(request.requestBody());

        assertThat(response).containsExactlyInAnyOrder(
                new PluginConfiguration("FILENAME", new Metadata(true, false)),
                new PluginConfiguration("VERSION", new Metadata(true, true))
        );
    }

    @Test
    public void shouldGetFetchArtifactViewFromPlugin() {
        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(ARTIFACT_EXTENSION), requestArgumentCaptor.capture())).thenReturn(new DefaultGoPluginApiResponse(SUCCESS_RESPONSE_CODE, "{ \"template\": \"fetch-artifact-view\"}"));

        String view = artifactExtension.getFetchArtifactView(PLUGIN_ID);

        final GoPluginApiRequest request = requestArgumentCaptor.getValue();

        assertThat(request.extension()).isEqualTo(ARTIFACT_EXTENSION);
        assertThat(request.requestName()).isEqualTo(REQUEST_FETCH_ARTIFACT_VIEW);
        assertNull(request.requestBody());

        assertThat(view).isEqualTo("fetch-artifact-view");
    }

    @Test
    public void shouldValidateFetchArtifactConfig() {
        String responseBody = "[{\"message\":\"Filename must not be blank.\",\"key\":\"Filename\"}]";

        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(ARTIFACT_EXTENSION), requestArgumentCaptor.capture())).thenReturn(new DefaultGoPluginApiResponse(SUCCESS_RESPONSE_CODE, responseBody));

        ValidationResult validationResult = artifactExtension.validateFetchArtifactConfig(PLUGIN_ID, Map.of("Filename", ""));

        final GoPluginApiRequest request = requestArgumentCaptor.getValue();

        assertThat(request.extension()).isEqualTo(ARTIFACT_EXTENSION);
        assertThat(request.requestName()).isEqualTo(REQUEST_FETCH_ARTIFACT_VALIDATE);
        assertThat(request.requestBody()).isEqualTo("{\"Filename\":\"\"}");

        assertThat(validationResult.isSuccessful()).isEqualTo(false);
        assertThat(validationResult.getErrors()).containsExactlyInAnyOrder(
                new ValidationError("Filename", "Filename must not be blank.")
        );
    }

    @Test
    public void shouldGetCapabilities() {
        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(ARTIFACT_EXTENSION), requestArgumentCaptor.capture())).thenReturn(DefaultGoPluginApiResponse.success("{}"));

        artifactExtension.getCapabilities(PLUGIN_ID);

        final GoPluginApiRequest request = requestArgumentCaptor.getValue();

        assertThat(request.extension()).isEqualTo(ARTIFACT_EXTENSION);
        assertThat(request.requestName()).isEqualTo(REQUEST_GET_CAPABILITIES);
        assertNull(request.requestBody());
    }

    @Test
    public void allRequestMustHaveRequestPrefix() {
        assertThat(REQUEST_PREFIX).isEqualTo("cd.go.artifact");

        assertThat(REQUEST_STORE_CONFIG_METADATA).startsWith(REQUEST_PREFIX);
        assertThat(REQUEST_STORE_CONFIG_VIEW).startsWith(REQUEST_PREFIX);
        assertThat(REQUEST_STORE_CONFIG_VALIDATE).startsWith(REQUEST_PREFIX);

        assertThat(REQUEST_PUBLISH_ARTIFACT_METADATA).startsWith(REQUEST_PREFIX);
        assertThat(REQUEST_PUBLISH_ARTIFACT_VIEW).startsWith(REQUEST_PREFIX);
        assertThat(REQUEST_PUBLISH_ARTIFACT_VALIDATE).startsWith(REQUEST_PREFIX);

        assertThat(REQUEST_FETCH_ARTIFACT_METADATA).startsWith(REQUEST_PREFIX);
        assertThat(REQUEST_FETCH_ARTIFACT_VIEW).startsWith(REQUEST_PREFIX);
        assertThat(REQUEST_FETCH_ARTIFACT_VALIDATE).startsWith(REQUEST_PREFIX);

        assertThat(REQUEST_PUBLISH_ARTIFACT).startsWith(REQUEST_PREFIX);
        assertThat(REQUEST_FETCH_ARTIFACT).startsWith(REQUEST_PREFIX);
        assertThat(REQUEST_GET_PLUGIN_ICON).startsWith(REQUEST_PREFIX);
    }
}
