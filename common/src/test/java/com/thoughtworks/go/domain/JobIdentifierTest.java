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
package com.thoughtworks.go.domain;

import com.thoughtworks.go.util.command.EnvironmentVariableContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class JobIdentifierTest {
    @Test
    public void shouldReturnBuildLocator() {
        JobIdentifier id = new JobIdentifier("cruise", 1, "label-", "dev", "1", "linux-firefox-1", 100L);
        assertThat(id.buildLocator()).isEqualTo("cruise/1/dev/1/linux-firefox-1");
    }

    @Test
    public void shouldReturnPropertyLocatorUsingLabel() {
        JobIdentifier id = new JobIdentifier("cruise", -1, "1.0.1234", "dev", "1", "linux-firefox-1", 100L);
        assertThat(id.propertyLocator("test-duration")).isEqualTo("cruise/1.0.1234/dev/1/linux-firefox-1/test-duration");
    }

    @Test
    public void shouldReturnPropertyLocatorUsingPipelineCounter() {
        JobIdentifier id = new JobIdentifier("cruise", 1234, "1.0.1234", "dev", "1", "linux-firefox-1", 100L);
        assertThat(id.propertyLocator("test-duration")).isEqualTo("cruise/1234/dev/1/linux-firefox-1/test-duration");
    }

    @Test
    public void shouldReturnArtifactLocatorUsingLabel() {
        JobIdentifier id = new JobIdentifier("cruise", -2, "1.0.1234", "dev", "1", "linux-firefox-1", 100L);
        assertThat(id.artifactLocator("consoleoutput/log.xml")).isEqualTo("cruise/1.0.1234/dev/1/linux-firefox-1/consoleoutput/log.xml");
    }

    @Test
    public void shouldReturnArtifactLocatorUsingPipelineCounter() {
        JobIdentifier id = new JobIdentifier("cruise", 1234, "1.0.1234", "dev", "1", "linux-firefox-1", 100L);
        assertThat(id.artifactLocator("consoleoutput/log.xml")).isEqualTo("cruise/1234/dev/1/linux-firefox-1/consoleoutput/log.xml");
    }

    @Test
    public void shouldFixFilePathWithPrecedingSlash() {
        JobIdentifier id = new JobIdentifier("cruise", 1234, "1.0.1234", "dev", "1", "linux-firefox-1", 100L);
        assertThat(id.artifactLocator("/consoleoutput/log.xml")).isEqualTo("cruise/1234/dev/1/linux-firefox-1/consoleoutput/log.xml");
    }

    @Test
    public void shouldReturnURN() {
        JobIdentifier id = new JobIdentifier("cruise", 1, "label-", "dev", "1", "linux-firefox-1", 100L);
        assertThat(id.asURN()).isEqualTo("urn:x-go.studios.thoughtworks.com:job-id:cruise:1:dev:1:linux-firefox-1");
    }

    @Test
    public void shouldPopulateGoJobInstanceRelatedEnvironmentVariables() {
        JobIdentifier id = new JobIdentifier("pipeline-name", 10, "label-10", "stage-name", "2", "build-name");
        id.setRerunOfCounter(1);

        EnvironmentVariableContext context = new EnvironmentVariableContext();
        id.populateEnvironmentVariables(context);

        EnvironmentVariableContext expected = new EnvironmentVariableContext();

        expected.setProperty("GO_PIPELINE_NAME", "pipeline-name", false);
        expected.setProperty("GO_PIPELINE_COUNTER", "10", false);
        expected.setProperty("GO_PIPELINE_LABEL", "label-10", false);
        expected.setProperty("GO_STAGE_NAME", "stage-name", false);
        expected.setProperty("GO_STAGE_COUNTER", "2", false);
        expected.setProperty("GO_RERUN_OF_STAGE_COUNTER", "1", false);
        expected.setProperty("GO_JOB_NAME", "build-name", false);

        assertThat(context).isEqualTo(expected);
    }

    @Test
    public void shouldNotPopulate_RerunOfStage_whenNotAvailable() {
        JobIdentifier id = new JobIdentifier("pipeline-name", 10, "label-10", "stage-name", "2", "build-name");
        EnvironmentVariableContext context = new EnvironmentVariableContext();
        id.populateEnvironmentVariables(context);

        assertThat(context.hasProperty("GO_RERUN_OF_STAGE_COUNTER")).isFalse();

        id.setRerunOfCounter(1);
        context = new EnvironmentVariableContext();
        id.populateEnvironmentVariables(context);

        assertThat(context.hasProperty("GO_RERUN_OF_STAGE_COUNTER")).isTrue();
    }
}
