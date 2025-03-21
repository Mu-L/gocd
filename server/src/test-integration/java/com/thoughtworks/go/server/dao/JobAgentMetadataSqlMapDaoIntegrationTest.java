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
package com.thoughtworks.go.server.dao;

import com.thoughtworks.go.config.PipelineConfig;
import com.thoughtworks.go.config.elastic.ClusterProfile;
import com.thoughtworks.go.config.elastic.ElasticProfile;
import com.thoughtworks.go.domain.DefaultSchedulingContext;
import com.thoughtworks.go.domain.JobAgentMetadata;
import com.thoughtworks.go.domain.Pipeline;
import com.thoughtworks.go.domain.Stage;
import com.thoughtworks.go.domain.config.ConfigurationProperty;
import com.thoughtworks.go.helper.BuildPlanMother;
import com.thoughtworks.go.helper.PipelineMother;
import com.thoughtworks.go.server.service.InstanceFactory;
import com.thoughtworks.go.util.TimeProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static com.thoughtworks.go.helper.ModificationsMother.modifySomeFiles;
import static com.thoughtworks.go.util.GoConstants.DEFAULT_APPROVED_BY;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = {
        "classpath:/applicationContext-global.xml",
        "classpath:/applicationContext-dataLocalAccess.xml",
        "classpath:/testPropertyConfigurer.xml",
        "classpath:/spring-all-servlet.xml",
})
public class JobAgentMetadataSqlMapDaoIntegrationTest {
    private static final String JOB_NAME = "functional";
    private static final String OTHER_JOB_NAME = "unit";
    private static final String STAGE_NAME = "mingle";
    private static final String PIPELINE_NAME = "pipeline";
    private long jobId;

    @Autowired
    private InstanceFactory instanceFactory;
    @Autowired
    private JobAgentMetadataSqlMapDao dao;
    @Autowired
    private DatabaseAccessHelper dbHelper;

    @BeforeEach
    public void setup() throws Exception {
        dbHelper.onSetUp();

        PipelineConfig pipelineConfig = PipelineMother.withSingleStageWithMaterials(PIPELINE_NAME, STAGE_NAME, BuildPlanMother.withBuildPlans(JOB_NAME, OTHER_JOB_NAME));
        DefaultSchedulingContext schedulingContext = new DefaultSchedulingContext(DEFAULT_APPROVED_BY);
        Pipeline savedPipeline = instanceFactory.createPipelineInstance(pipelineConfig, modifySomeFiles(pipelineConfig), schedulingContext, "md5-test", new TimeProvider());
        dbHelper.savePipelineWithStagesAndMaterials(savedPipeline);
        Stage savedStage = savedPipeline.getFirstStage();
        jobId = savedStage.getJobInstances().getByName(JOB_NAME).getId();
    }

    @AfterEach
    public void tearDown() throws Exception {
        dbHelper.onTearDown();
    }

    @Test
    public void shouldSaveElasticAgentPropertyOnJob() {
        ElasticProfile profile = new ElasticProfile("elastic", "clusterProfileId");
        ClusterProfile clusterProfile = new ClusterProfile("clusterProfileId", "plugin");
        JobAgentMetadata jobAgentMetadata = new JobAgentMetadata(jobId, profile, clusterProfile);
        dao.save(jobAgentMetadata);

        JobAgentMetadata metadataFromDb = dao.load(jobId);
        assertThat(metadataFromDb.elasticProfile()).isEqualTo(profile);
        assertThat(metadataFromDb.clusterProfile()).isEqualTo(clusterProfile);
        assertThat(metadataFromDb).isEqualTo(jobAgentMetadata);
    }

    @Test
    public void shouldDeleteElasticAgentPropertySetToJob() {
        ElasticProfile profile = new ElasticProfile("elastic", "clusterProfileId", new ConfigurationProperty());
        ClusterProfile clusterProfile = new ClusterProfile("clusterProfileId", "plugin", new ConfigurationProperty());
        JobAgentMetadata jobAgentMetadata = new JobAgentMetadata(jobId, profile, clusterProfile);
        dao.save(jobAgentMetadata);

        JobAgentMetadata metadataFromDb = dao.load(jobId);
        assertThat(metadataFromDb).isEqualTo(jobAgentMetadata);

        dao.delete(metadataFromDb);
        assertThat(dao.load(jobId)).isNull();
    }
}
