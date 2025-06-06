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
package com.thoughtworks.go.server.presentation.models;

import com.thoughtworks.go.config.JobConfigs;
import com.thoughtworks.go.config.StageConfig;
import com.thoughtworks.go.helper.StageConfigMother;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class StageInfoAdapterTest {

    @Test
    public void shouldReturnCorrectAutoApprovedStatus() {
        StageConfig stage = StageConfigMother.custom("dev", new JobConfigs());
        assertThat(stage.requiresApproval()).isFalse();
        assertThat(new StageInfoAdapter(stage).isAutoApproved()).isTrue();
    }
}
