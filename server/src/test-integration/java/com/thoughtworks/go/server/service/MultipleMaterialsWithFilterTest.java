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

import com.thoughtworks.go.config.CaseInsensitiveString;
import com.thoughtworks.go.config.GoConfigDao;
import com.thoughtworks.go.config.materials.Filter;
import com.thoughtworks.go.config.materials.IgnoredFiles;
import com.thoughtworks.go.config.materials.MaterialConfigs;
import com.thoughtworks.go.config.materials.SubprocessExecutionContext;
import com.thoughtworks.go.config.materials.svn.SvnMaterial;
import com.thoughtworks.go.domain.MaterialRevision;
import com.thoughtworks.go.domain.MaterialRevisions;
import com.thoughtworks.go.domain.Pipeline;
import com.thoughtworks.go.domain.buildcause.BuildCause;
import com.thoughtworks.go.domain.materials.svn.SvnCommand;
import com.thoughtworks.go.fixture.PipelineWithTwoStages;
import com.thoughtworks.go.helper.SvnTestRepo;
import com.thoughtworks.go.server.dao.DatabaseAccessHelper;
import com.thoughtworks.go.server.materials.MaterialDatabaseUpdater;
import com.thoughtworks.go.server.persistence.MaterialRepository;
import com.thoughtworks.go.server.scheduling.BuildCauseProducerService;
import com.thoughtworks.go.server.service.result.ServerHealthStateOperationResult;
import com.thoughtworks.go.server.transaction.TransactionTemplate;
import com.thoughtworks.go.util.GoConfigFileHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = {
        "classpath:/applicationContext-global.xml",
        "classpath:/applicationContext-dataLocalAccess.xml",
        "classpath:/testPropertyConfigurer.xml",
        "classpath:/spring-all-servlet.xml",
})
public class MultipleMaterialsWithFilterTest {
    @Autowired private GoConfigDao goConfigDao;
    @Autowired private DatabaseAccessHelper dbHelper;
    @Autowired private PipelineScheduleQueue pipelineScheduleQueue;
    @Autowired private BuildCauseProducerService buildCauseProducerService;
    @Autowired private MaterialDatabaseUpdater materialDatabaseUpdater;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private SubprocessExecutionContext subprocessExecutionContext;

    private PipelineWithMultipleMaterials fixture;
    private static GoConfigFileHelper configHelper;

    @BeforeAll
    public static void fixtureSetUp() {
        configHelper = new GoConfigFileHelper();
    }

    @BeforeEach
    public void setUp(@TempDir Path tempDir) throws Exception {
        configHelper.onSetUp();
        configHelper.usingCruiseConfigDao(goConfigDao);
        fixture = new PipelineWithMultipleMaterials(materialRepository, transactionTemplate, tempDir);
        fixture.usingFilterForFirstMaterial("**/*.doc").usingConfigHelper(configHelper).usingDbHelper(
                dbHelper).onSetUp();
        pipelineScheduleQueue.clear();
    }

    @AfterEach
    public void teardown() throws Exception {
        configHelper.onTearDown();
        fixture.onTearDown();
        dbHelper.onTearDown();
        pipelineScheduleQueue.clear();
    }

    @Test
    public void shouldUseLatestRevisionWhenAutoTriggered() throws Exception {
        fixture.createPipelineHistory();
        fixture.checkInToFirstMaterial("a.doc");
        fixture.checkInToSecondMaterial("b.java");
        buildCauseProducerService.autoSchedulePipeline(fixture.pipelineName, new ServerHealthStateOperationResult(), 12345);
        BuildCause buildCause = pipelineScheduleQueue.toBeScheduled().get(new CaseInsensitiveString(fixture.pipelineName));
        assertThat(buildCause).isInstanceOf(BuildCause.class);

        MaterialRevisions actual = buildCause.getMaterialRevisions();
        assertThat(actual.getMaterialRevision(fixture.getSecondMaterialFolder()).getRevision()).isEqualTo(fixture.latestRevisionOfSecondMaterial().getRevision());
        assertThat(actual.getMaterialRevision(fixture.getFirstMaterialFolder()).getRevision()).isEqualTo(fixture.latestRevisionOfFirstMaterial().getRevision());
    }

    @Test
    public void shouldNotTriggerPipelineWhenCheckinsAreIgnored() throws Exception {
        fixture.createPipelineHistory();
        fixture.checkInToFirstMaterial("a.doc");

        int size = pipelineScheduleQueue.toBeScheduled().size();

        buildCauseProducerService.autoSchedulePipeline(fixture.pipelineName, new ServerHealthStateOperationResult(), 12345);

        assertThat(pipelineScheduleQueue.toBeScheduled().size()).isEqualTo(size);
        assertThat(pipelineScheduleQueue.toBeScheduled().get(new CaseInsensitiveString(fixture.pipelineName))).isNull();
    }

    public class PipelineWithMultipleMaterials extends PipelineWithTwoStages {
        private String filterForFirstMaterial;
        private SvnTestRepo svnTestRepo1;
        private SvnTestRepo svnTestRepo2;
        private SvnMaterial svnMaterial1;
        private SvnMaterial svnMaterial2;
        private String firstMaterialFolder = "svn1";
        private String secondMaterialFolder = "svn2";

        public PipelineWithMultipleMaterials(MaterialRepository materialRepository, final TransactionTemplate transactionTemplate, Path tempDir) {
            super(materialRepository, transactionTemplate, tempDir);
        }

        @Override
        public void onSetUp() throws Exception {
            configHelper.initializeConfigFile();

            svnTestRepo1 = new SvnTestRepo(tempDir());
            svnMaterial1 = new SvnMaterial(new SvnCommand(null, svnTestRepo1.projectRepositoryUrl()));
            svnMaterial1.setFolder(firstMaterialFolder);
            if (filterForFirstMaterial != null) {
                svnMaterial1.setFilter(new Filter(new IgnoredFiles(filterForFirstMaterial)));
            }

            svnTestRepo2 = new SvnTestRepo(tempDir());
            svnMaterial2 = new SvnMaterial(new SvnCommand(null, svnTestRepo2.projectRepositoryUrl()));
            svnMaterial2.setFolder(secondMaterialFolder);

            configHelper.addPipeline(pipelineName, devStage, new MaterialConfigs(svnMaterial1.config(), svnMaterial2.config()), jobsOfDevStage);
            configHelper.addStageToPipeline(pipelineName, ftStage, JOB_FOR_FT_STAGE);
        }

        public PipelineWithMultipleMaterials usingFilterForFirstMaterial(String pattern) {
            filterForFirstMaterial = pattern;
            return this;
        }

        public void checkInToFirstMaterial(String path) throws Exception {
            svnTestRepo1.checkInOneFile(path);
            materialDatabaseUpdater.updateMaterial(svnMaterial1);
        }

        public void checkInToSecondMaterial(String path) throws Exception {
            svnTestRepo2.checkInOneFile(path);
            materialDatabaseUpdater.updateMaterial(svnMaterial2);
        }

        @Override
        public Pipeline schedulePipeline() {
            MaterialRevisions materialRevisions = new MaterialRevisions();
            materialRevisions.addRevision(latestRevisionOfFirstMaterial());
            materialRevisions.addRevision(latestRevisionOfSecondMaterial());
            return schedulePipeline(BuildCause.createWithModifications(materialRevisions, ""));
        }

        public MaterialRevision latestRevisionOfFirstMaterial() {
            return new MaterialRevision(svnMaterial1, svnMaterial1.latestModification(null, subprocessExecutionContext));
        }

        public MaterialRevision latestRevisionOfSecondMaterial() {
            return new MaterialRevision(svnMaterial2, svnMaterial2.latestModification(null, subprocessExecutionContext));
        }

        public String getFirstMaterialFolder() {
            return firstMaterialFolder;
        }

        public String getSecondMaterialFolder() {
            return secondMaterialFolder;
        }
   }
}
