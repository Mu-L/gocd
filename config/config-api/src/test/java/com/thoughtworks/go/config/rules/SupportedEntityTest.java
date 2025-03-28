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
package com.thoughtworks.go.config.rules;

import com.thoughtworks.go.config.BasicEnvironmentConfig;
import com.thoughtworks.go.config.BasicPipelineConfigs;
import com.thoughtworks.go.config.EnvironmentConfig;
import com.thoughtworks.go.config.PipelineConfigs;
import com.thoughtworks.go.config.elastic.ClusterProfile;
import com.thoughtworks.go.config.merge.MergeEnvironmentConfig;
import com.thoughtworks.go.config.merge.MergePipelineConfigs;
import com.thoughtworks.go.domain.packagerepository.PackageRepository;
import com.thoughtworks.go.domain.scm.SCM;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.thoughtworks.go.config.rules.SupportedEntity.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SupportedEntityTest {
    @Test
    void shouldSupportPipelineGroup() {
        assertThat(PIPELINE_GROUP.getType()).isEqualTo("pipeline_group");

        assertThat(PIPELINE_GROUP.getEntityType()).isEqualTo(PipelineConfigs.class);

        assertThat(PIPELINE_GROUP.getEntityType().isAssignableFrom(BasicPipelineConfigs.class)).isTrue();
        assertThat(PIPELINE_GROUP.getEntityType().isAssignableFrom(MergePipelineConfigs.class)).isTrue();
    }

    @Test
    void shouldSupportEnvironment() {
        assertThat(ENVIRONMENT.getType()).isEqualTo("environment");
        assertThat(ENVIRONMENT.getEntityType()).isEqualTo(EnvironmentConfig.class);

        assertThat(ENVIRONMENT.getEntityType().isAssignableFrom(BasicEnvironmentConfig.class)).isTrue();
        assertThat(ENVIRONMENT.getEntityType().isAssignableFrom(MergeEnvironmentConfig.class)).isTrue();
    }

    @Test
    void shouldSupportPluggableSCM() {
        assertThat(PLUGGABLE_SCM.getType()).isEqualTo("pluggable_scm");
        assertThat(PLUGGABLE_SCM.getEntityType()).isEqualTo(SCM.class);
    }

    @Test
    void shouldSupportPackageRepository() {
        assertThat(PACKAGE_REPOSITORY.getType()).isEqualTo("package_repository");
        assertThat(PACKAGE_REPOSITORY.getEntityType()).isEqualTo(PackageRepository.class);
    }

    @Test
    void shouldSupportClusterProfile() {
        assertThat(CLUSTER_PROFILE.getType()).isEqualTo("cluster_profile");
        assertThat(CLUSTER_PROFILE.getEntityType()).isEqualTo(ClusterProfile.class);
    }

    @Test
    void shouldReturnUnmodifiableListOfTheSupportedEntities() {
        List<String> entities = unmodifiableListOf(PIPELINE_GROUP, ENVIRONMENT);

        assertThat(entities).hasSize(2).contains(PIPELINE_GROUP.getType(), ENVIRONMENT.getType());
        //noinspection DataFlowIssue
        assertThatCode(() -> entities.add("foo"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
