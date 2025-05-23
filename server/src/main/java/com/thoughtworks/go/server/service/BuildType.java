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
package com.thoughtworks.go.server.service;

import com.thoughtworks.go.config.PipelineConfig;
import com.thoughtworks.go.domain.MaterialRevisions;
import com.thoughtworks.go.domain.buildcause.BuildCause;
import com.thoughtworks.go.server.service.result.OperationResult;
import com.thoughtworks.go.serverhealth.ServerHealthService;

/**
 * Understands builds that are triggered in different ways
 */
public interface BuildType {
    BuildCause onModifications(MaterialRevisions materialRevisions, boolean materialConfigurationChanged, MaterialRevisions previousMaterialRevisions);

    BuildCause onEmptyModifications(PipelineConfig pipelineConfig, MaterialRevisions materialRevisions);

    void canProduce(PipelineConfig pipelineConfig, SchedulingCheckerService schedulingChecker,
                                 ServerHealthService serverHealthService,
                                 OperationResult operationResult);

    boolean isValidBuildCause(PipelineConfig pipelineConfig, BuildCause buildCause);

    boolean shouldCheckWhetherOlderRunsHaveRunWithLatestMaterials();

    void notifyPipelineNotScheduled(PipelineConfig pipelineConfig);
}
