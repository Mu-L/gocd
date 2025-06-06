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
package com.thoughtworks.go.remote.work;

import com.thoughtworks.go.remote.work.artifact.PluggableArtifactMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PluggableArtifactMetadataTest {
    @Test
    public void shouldAddMetadataWhenMetadataAbsentForPlugin() {
        final PluggableArtifactMetadata pluggableArtifactMetadata = new PluggableArtifactMetadata();

        assertTrue(pluggableArtifactMetadata.getMetadataPerPlugin().isEmpty());

        pluggableArtifactMetadata.addMetadata("docker", "installer", Map.of("image", "alpine"));

        assertThat(pluggableArtifactMetadata.getMetadataPerPlugin()).containsEntry("docker", Map.of("installer", Map.of("image", "alpine")));
    }

    @Test
    public void shouldAddMetadataWhenMetadataOfOtherArtifactIsAlreadyPresetForAPlugin() {
        final PluggableArtifactMetadata pluggableArtifactMetadata = new PluggableArtifactMetadata();

        assertTrue(pluggableArtifactMetadata.getMetadataPerPlugin().isEmpty());

        pluggableArtifactMetadata.addMetadata("docker", "centos", Map.of("image", "centos"));
        pluggableArtifactMetadata.addMetadata("docker", "alpine", Map.of("image", "alpine"));

        final Map<String, Map<String, Object>> docker = pluggableArtifactMetadata.getMetadataPerPlugin().get("docker");
        assertNotNull(docker);
        assertThat(docker).containsEntry("centos", Map.of("image", "centos"));
        assertThat(docker).containsEntry("alpine", Map.of("image", "alpine"));
    }

    @Test
    public void shouldWriteMetadataFile(@TempDir File workingDirectory) throws IOException {
        final PluggableArtifactMetadata pluggableArtifactMetadata = new PluggableArtifactMetadata();

        pluggableArtifactMetadata.addMetadata("cd.go.docker-registry", "centos", Map.of("image", "centos"));
        pluggableArtifactMetadata.addMetadata("cd.go.docker-registry", "alpine", Map.of("image", "alpine"));

        pluggableArtifactMetadata.write(workingDirectory);

        final File jsonFile = new File(workingDirectory.listFiles()[0], "cd.go.docker-registry.json");
        final String fileContent = Files.readString(jsonFile.toPath(), StandardCharsets.UTF_8);

        assertThat(fileContent).isEqualTo("{\"alpine\":{\"image\":\"alpine\"},\"centos\":{\"image\":\"centos\"}}");
    }
}