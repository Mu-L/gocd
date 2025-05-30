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

import com.thoughtworks.go.config.*;
import com.thoughtworks.go.config.materials.MaterialConfigs;
import com.thoughtworks.go.domain.*;
import com.thoughtworks.go.domain.buildcause.BuildCause;
import com.thoughtworks.go.domain.materials.TestingMaterial;
import com.thoughtworks.go.domain.materials.svn.Subversion;
import com.thoughtworks.go.domain.materials.svn.SvnCommand;
import com.thoughtworks.go.fixture.PipelineWithTwoStages;
import com.thoughtworks.go.helper.PipelineMother;
import com.thoughtworks.go.helper.StageConfigMother;
import com.thoughtworks.go.helper.SvnTestRepo;
import com.thoughtworks.go.helper.TestRepo;
import com.thoughtworks.go.server.cache.GoCache;
import com.thoughtworks.go.server.dao.DatabaseAccessHelper;
import com.thoughtworks.go.server.dao.PipelineDao;
import com.thoughtworks.go.server.dao.StageSqlMapDao;
import com.thoughtworks.go.server.domain.Username;
import com.thoughtworks.go.server.persistence.MaterialRepository;
import com.thoughtworks.go.server.scheduling.ScheduleHelper;
import com.thoughtworks.go.server.transaction.TransactionTemplate;
import com.thoughtworks.go.util.GoConfigFileHelper;
import com.thoughtworks.go.util.TimeProvider;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static com.thoughtworks.go.helper.MaterialConfigsMother.svnMaterialConfig;
import static com.thoughtworks.go.helper.ModificationsMother.modifyOneFile;
import static com.thoughtworks.go.util.GoConfigFileHelper.env;
import static com.thoughtworks.go.util.GoConstants.DEFAULT_APPROVED_BY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.fail;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = {
        "classpath:/applicationContext-global.xml",
        "classpath:/applicationContext-dataLocalAccess.xml",
        "classpath:/testPropertyConfigurer.xml",
        "classpath:/spring-all-servlet.xml",
})
public class PipelineScheduleServiceTest {
    private static final String STAGE_NAME = "dev";

    @Autowired private ScheduleService scheduleService;
    @Autowired private GoConfigDao goConfigDao;
    @Autowired private GoConfigService goConfigService;
    @Autowired private PipelineDao pipelineDao;
    @Autowired private StageSqlMapDao stageDao;
    @Autowired PipelineScheduleQueue pipelineScheduleQueue;
    @Autowired PipelineService pipelineService;
    @Autowired private ScheduleHelper scheduleHelper;
    @Autowired private DatabaseAccessHelper dbHelper;
    @Autowired private PipelineLockService pipelineLockService;
    @Autowired private GoCache goCache;
    @Autowired private EnvironmentConfigService environmentConfigService;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private InstanceFactory instanceFactory;
    @Autowired private AgentService agentService;

    private PipelineWithTwoStages pipelineWithTwoStages;
    private PipelineConfig mingleConfig;
    private PipelineConfig evolveConfig;
    private final String md5 = "md5-test";

    private GoConfigFileHelper configHelper;
    private Subversion repository;
    private TestRepo testRepo;
    private PipelineConfig goConfig;

    @BeforeEach
    public void setup(@TempDir Path tempDir) throws Exception {
        configHelper = new GoConfigFileHelper();
        configHelper.usingCruiseConfigDao(goConfigDao);
        configHelper.onSetUp();
        testRepo = new SvnTestRepo(tempDir);
        dbHelper.onSetUp();
        repository = new SvnCommand(null, testRepo.projectRepositoryUrl());
        mingleConfig = configHelper.addPipeline("mingle", STAGE_NAME, repository, "unit", "functional");


        goConfig = configHelper.addPipeline("go", STAGE_NAME, repository, "unit");
        StageConfig ftStageConfig = StageConfigMother.custom("ft", "twist");
        ftStageConfig.jobConfigByConfigName(new CaseInsensitiveString("twist")).addVariable("JOB_LVL", "job value");
        ftStageConfig.setVariables(env("STAGE_LVL", "stage value"));
        configHelper.addStageToPipeline("go", ftStageConfig);
        configHelper.addEnvironmentVariableToPipeline("go", env("PIPELINE_LVL", "pipeline value"));
        configHelper.addEnvironments("uat");
        EnvironmentConfig uatEnv = configHelper.currentConfig().getEnvironments().named(new CaseInsensitiveString("uat"));
        uatEnv.addPipeline(new CaseInsensitiveString("go"));
        uatEnv.addEnvironmentVariable("ENV_LVL", "env value");

        evolveConfig = configHelper.addPipeline("evolve", STAGE_NAME, repository, "unit");
        goCache.clear();
    }

    @AfterEach
    public void teardown() throws Exception {
        if (pipelineWithTwoStages != null) {
            pipelineWithTwoStages.onTearDown();
        }
        dbHelper.onTearDown();
        pipelineScheduleQueue.clear();
        testRepo.tearDown();
        FileUtils.deleteQuietly(new File("pipelines"));
        configHelper.onTearDown();
    }

    @Test
    public void shouldScheduleStageAfterModifications() throws Exception {
        scheduleAndCompleteInitialPipelines();
        TestingMaterial stubMaterial = new TestingMaterial();

        mingleConfig.setMaterialConfigs(new MaterialConfigs(stubMaterial.config()));

        MaterialRevisions revisions = new MaterialRevisions();
        revisions.addRevision(stubMaterial, stubMaterial.modificationsSince());
        BuildCause buildCause = BuildCause.createWithModifications(revisions, "");
        dbHelper.saveMaterials(buildCause.getMaterialRevisions());
        Pipeline pipeline = instanceFactory.createPipelineInstance(mingleConfig, buildCause, new DefaultSchedulingContext(DEFAULT_APPROVED_BY), md5, new TimeProvider());
        pipelineService.save(pipeline);

        verifyMingleScheduledWithModifications();
    }

    @Test
    public void shouldLockPipelineWhenSchedulingIt() throws Exception {
        scheduleAndCompleteInitialPipelines();

        configHelper.lockPipeline("mingle");

        TestingMaterial stubMaterial = new TestingMaterial();
        mingleConfig.setMaterialConfigs(new MaterialConfigs(stubMaterial.config()));

        assertThat(pipelineLockService.isLocked("mingle")).isEqualTo(false);

        MaterialRevisions revisions = new MaterialRevisions();
        revisions.addRevision(stubMaterial, stubMaterial.modificationsSince());
        BuildCause buildCause = BuildCause.createWithModifications(revisions, "");
        dbHelper.saveMaterials(buildCause.getMaterialRevisions());
        Pipeline pipeline = instanceFactory.createPipelineInstance(mingleConfig, buildCause, new DefaultSchedulingContext(DEFAULT_APPROVED_BY), md5, new TimeProvider());
        pipelineService.save(pipeline);

        assertThat(pipelineLockService.isLocked("mingle")).isEqualTo(true);
    }

    @Test
    public void shouldScheduleJobForAllAgentsWhenToBeRunOnAllAgents() {
        configHelper.addEnvironments("dev");
        Agent agentConfigWithUuid1 = new Agent("uuid1", "localhost", "127.0.0.1", "cookie1");
        agentConfigWithUuid1.setEnvironments("dev");
        agentService.clearAll();
        agentService.saveOrUpdate(agentConfigWithUuid1);
        agentService.saveOrUpdate(new Agent("uuid2", "localhost", "127.0.0.1", "cookie2"));
        agentService.saveOrUpdate(new Agent("uuid3", "localhost", "127.0.0.1", "cookie3"));
        configHelper.setRunOnAllAgents(CaseInsensitiveString.str(evolveConfig.name()), STAGE_NAME, "unit", true);

        TestingMaterial stubMaterial = new TestingMaterial();
        evolveConfig.setMaterialConfigs(new MaterialConfigs(stubMaterial.config()));
        MaterialRevisions revisions = new MaterialRevisions();
        revisions.addRevision(stubMaterial, stubMaterial.modificationsSince());
        BuildCause buildCause = BuildCause.createWithModifications(revisions, "");
        dbHelper.saveMaterials(buildCause.getMaterialRevisions());

        Pipeline pipeline = instanceFactory.createPipelineInstance(evolveConfig, buildCause, new DefaultSchedulingContext(DEFAULT_APPROVED_BY, environmentConfigService.agentsForPipeline(evolveConfig.name())), md5,
                new TimeProvider());
        pipelineService.save(pipeline);

        Stage instance = scheduleService.scheduleStage(pipeline, STAGE_NAME, "anyone", new ScheduleService.NewStageInstanceCreator(goConfigService),
                new ScheduleService.ExceptioningErrorHandler());
        JobInstances scheduledJobs = instance.getJobInstances();
        assertThat(scheduledJobs).satisfiesExactlyInAnyOrder(
            job -> {
                assertThat(job.getAgentUuid()).isEqualTo("uuid2");
                assertThat(job.getName()).isEqualTo(RunOnAllAgents.CounterBasedJobNameGenerator.appendMarker("unit", 1));
            },
            job -> {
                assertThat(job.getAgentUuid()).isEqualTo("uuid3");
                assertThat(job.getName()).isEqualTo(RunOnAllAgents.CounterBasedJobNameGenerator.appendMarker("unit", 2));
            }
        );
    }

	@Test
	public void shouldScheduleMultipleJobsWhenToBeRunMultipleInstance() {
		configHelper.setRunMultipleInstance(CaseInsensitiveString.str(evolveConfig.name()), STAGE_NAME, "unit", 2);

		TestingMaterial stubMaterial = new TestingMaterial();
		evolveConfig.setMaterialConfigs(new MaterialConfigs(stubMaterial.config()));
		MaterialRevisions revisions = new MaterialRevisions();
		revisions.addRevision(stubMaterial, stubMaterial.modificationsSince());
		BuildCause buildCause = BuildCause.createWithModifications(revisions, "");
		dbHelper.saveMaterials(buildCause.getMaterialRevisions());

		Pipeline pipeline = instanceFactory.createPipelineInstance(evolveConfig, buildCause, new DefaultSchedulingContext(DEFAULT_APPROVED_BY, environmentConfigService.agentsForPipeline(evolveConfig.name())), md5, new TimeProvider());
		pipelineService.save(pipeline);

		Stage instance = scheduleService.scheduleStage(pipeline, STAGE_NAME, "anyone", new ScheduleService.NewStageInstanceCreator(goConfigService), new ScheduleService.ExceptioningErrorHandler());

		JobInstances scheduledJobs = instance.getJobInstances();

        assertThat(scheduledJobs).satisfiesExactlyInAnyOrder(
            job -> assertThat(job.getName()).isEqualTo(RunMultipleInstance.CounterBasedJobNameGenerator.appendMarker("unit", 1)),
            job -> assertThat(job.getName()).isEqualTo(RunMultipleInstance.CounterBasedJobNameGenerator.appendMarker("unit", 2))
        );
	}

    @Test
    public void shouldPassEnvironmentLevelEnvironmentVariablesToJobsForNewlyScheduledStage() throws Exception {
        scheduleAndCompleteInitialPipelines();
        Pipeline pipeline = pipelineDao.mostRecentPipeline("go");

        Stage stage = scheduleService.scheduleStage(pipeline, "ft", "anonymous", new ScheduleService.NewStageInstanceCreator(goConfigService), new ScheduleService.ExceptioningErrorHandler());
        EnvironmentVariables jobVariables = stage.getJobInstances().first().getPlan().getVariables();
        assertThat(jobVariables).containsExactlyInAnyOrder(
            new EnvironmentVariable("PIPELINE_LVL", "pipeline value"),
            new EnvironmentVariable("STAGE_LVL", "stage value"),
            new EnvironmentVariable("JOB_LVL", "job value")
        );
    }


    @Test
    public void shouldLockPipelineWhenSchedulingStage() throws Exception {
        scheduleAndCompleteInitialPipelines();
        Pipeline pipeline = pipelineDao.mostRecentPipeline("mingle");

        configHelper.lockPipeline("mingle");

        assertThat(pipelineLockService.isLocked("mingle")).isEqualTo(false);

        scheduleService.scheduleStage(pipeline, STAGE_NAME, "anonymous", new ScheduleService.NewStageInstanceCreator(goConfigService), new ScheduleService.ExceptioningErrorHandler());

        assertThat(pipelineLockService.isLocked("mingle")).isEqualTo(true);
    }

    @Test
    public void shouldForceFirstStagePlan(@TempDir Path tempDir) throws Exception {
        pipelineWithTwoStages = new PipelineWithTwoStages(materialRepository, transactionTemplate, tempDir);
        pipelineWithTwoStages.usingDbHelper(dbHelper).usingConfigHelper(configHelper).onSetUp();
        pipelineWithTwoStages.createPipelineWithFirstStagePassedAndSecondStageRunning();
        Pipeline pipeline = manualSchedule(pipelineWithTwoStages.pipelineName);
        assertThat(pipeline.getFirstStage().stageState()).isEqualTo(StageState.Building);
    }

    @Test
    public void shouldForceFirstStagePlanWhenOtherStageIsRunning(@TempDir Path tempDir) throws Exception {
        pipelineWithTwoStages = new PipelineWithTwoStages(materialRepository, transactionTemplate, tempDir);
        pipelineWithTwoStages.usingDbHelper(dbHelper).usingConfigHelper(configHelper).onSetUp();
        pipelineWithTwoStages.createPipelineWithFirstStagePassedAndSecondStageRunning();
        Pipeline pipeline = manualSchedule(pipelineWithTwoStages.pipelineName);
        assertThat(pipeline.getFirstStage().isActive()).isEqualTo(true);
    }

    @Test
    public void shouldForceStagePlanWithModificationsSinceLast() throws Exception {
        Pipeline completedMingle = scheduleAndCompleteInitialPipelines();
        pipelineDao.loadPipeline(completedMingle.getId());
        TestingMaterial testingMaterial = new TestingMaterial();
        mingleConfig.setMaterialConfigs(new MaterialConfigs(testingMaterial.config()));

        MaterialRevisions revisions = new MaterialRevisions();
        revisions.addRevision(testingMaterial, testingMaterial.modificationsSince());
        BuildCause buildCause = BuildCause.createManualForced(revisions, Username.ANONYMOUS);
        dbHelper.saveMaterials(buildCause.getMaterialRevisions());
        Pipeline forcedPipeline = instanceFactory.createPipelineInstance(mingleConfig, buildCause, new DefaultSchedulingContext(
                DEFAULT_APPROVED_BY), md5, new TimeProvider());

        pipelineService.save(forcedPipeline);

        verifyMingleScheduledWithModifications();
    }

    @Test
    public void shouldNotScheduleAnyNewPipelineWhenErrorHappens() throws Exception {
        String stageName = "invalidStageName";
        PipelineConfig invalidPipeline = configHelper.addPipelineWithInvalidMaterial("invalidPipeline", stageName);
        int beforeScheduling = pipelineDao.count(CaseInsensitiveString.str(invalidPipeline.name()));

        autoSchedulePipelines();

        int afterScheduling = pipelineDao.count(CaseInsensitiveString.str(invalidPipeline.name()));
        assertThat(beforeScheduling).isEqualTo(afterScheduling);
    }

    @Test
    public void shouldNotScheduleActivePipeline() throws Exception {
        Pipeline pipeline = PipelineMother.building(mingleConfig);
        pipeline = dbHelper.savePipelineWithStagesAndMaterials(pipeline);
        Pipeline newPipeline = manualSchedule(CaseInsensitiveString.str(mingleConfig.name()));
        assertThat(newPipeline.getId()).isEqualTo(pipeline.getId());
    }

    @Test
    public void shouldNotScheduleBuildIfNoModification() throws Exception {
        autoSchedulePipelines("mingle", "evolve");
        // Get the scheduled evolve stage and complete it.
        Stage evolveInstance = stageDao.mostRecentWithBuilds(CaseInsensitiveString.str(evolveConfig.name()), evolveConfig.findBy(new CaseInsensitiveString("dev")));
        dbHelper.passStage(evolveInstance);
        stageDao.stageStatusChanged(evolveInstance);

        autoSchedulePipelines();
        Stage mostRecent = stageDao.mostRecentWithBuilds(CaseInsensitiveString.str(evolveConfig.name()), evolveConfig.findBy(new CaseInsensitiveString("dev")));

        assertThat(mostRecent.getId()).isEqualTo(evolveInstance.getId());
        assertThat(mostRecent.getJobInstances().first().getState()).isEqualTo(JobState.Completed);
    }


    @Test
    public void shouldSaveBuildStateCorrectly() throws Exception {
        PipelineConfig cruisePlan = configHelper.addPipeline("cruise", "dev", repository);
        goConfigService.forceNotifyListeners();

        autoSchedulePipelines("mingle", "evolve", "cruise");

        Stage cruise = stageDao.mostRecentWithBuilds(CaseInsensitiveString.str(cruisePlan.name()), cruisePlan.findBy(new CaseInsensitiveString("dev")));
        JobInstance instance = cruise.getJobInstances().first();
        assertThat(instance.getState()).isEqualTo(JobState.Scheduled);
    }

    @Test
    public void shouldRemoveBuildCauseIfPipelineNotExist() throws Exception {
        configHelper.addPipeline("cruise", "dev", repository);
        goConfigService.forceNotifyListeners();
        scheduleHelper.autoSchedulePipelinesWithRealMaterials("mingle", "evolve", "cruise");

        await()
            .atMost(5, TimeUnit.SECONDS)
            .untilAsserted(
                () -> assertThat(pipelineScheduleQueue.toBeScheduled()).hasSizeGreaterThanOrEqualTo(2)
            );

        configHelper.initializeConfigFile();
        goConfigService.forceNotifyListeners();

        scheduleService.autoSchedulePipelinesFromRequestBuffer();
        assertThat(pipelineScheduleQueue.toBeScheduled().size()).isEqualTo(0);
    }

    @Test
    public void shouldRemoveBuildCauseIfAnyExceptionIsThrown() throws Exception {
        configHelper.addPipeline("cruise", "dev", repository);
        goConfigService.forceNotifyListeners();
        goConfigService.getCurrentConfig().pipelineConfigByName(new CaseInsensitiveString("cruise")).get(0).jobConfigByConfigName(new CaseInsensitiveString("unit")).setRunOnAllAgents(true);
        scheduleHelper.autoSchedulePipelinesWithRealMaterials("cruise");

        goConfigService.forceNotifyListeners();

        scheduleService.autoSchedulePipelinesFromRequestBuffer();
        assertThat(pipelineScheduleQueue.toBeScheduled().size()).isEqualTo(0);
    }

    @Test
    public void shouldNotThrowErrorWhenMaterialsChange() throws Exception {
        configHelper.addPipeline("cruise", "dev", repository);
        goConfigService.forceNotifyListeners();
        scheduleHelper.autoSchedulePipelinesWithRealMaterials("mingle", "evolve", "cruise");

        configHelper.replaceMaterialForPipeline("cruise", svnMaterialConfig("http://new-material", null));
        goConfigService.forceNotifyListeners();
        try {
            scheduleService.autoSchedulePipelinesFromRequestBuffer();
        } catch (Exception e) {
            fail("#2520 - should not cause an error if materials have changed");
        }
    }

    @Test
    public void shouldConsumeAllBuildCausesInServerHealth() {
        pipelineScheduleQueue.schedule(new CaseInsensitiveString("mingle"), BuildCause.createManualForced(modifyOneFile(mingleConfig), Username.ANONYMOUS));
        pipelineScheduleQueue.schedule(new CaseInsensitiveString("evolve"), BuildCause.createManualForced(modifyOneFile(evolveConfig), Username.ANONYMOUS));

        scheduleService.autoSchedulePipelinesFromRequestBuffer();
        assertThat(pipelineScheduleQueue.toBeScheduled().size()).isEqualTo(0);
    }

    private void autoSchedulePipelines(String... pipelineNames) throws Exception {
        scheduleHelper.autoSchedulePipelinesWithRealMaterials(pipelineNames);
        scheduleService.autoSchedulePipelinesFromRequestBuffer();
    }

    private Pipeline manualSchedule(String pipelineName) throws Exception {
        scheduleHelper.manuallySchedulePipelineWithRealMaterials(pipelineName, new Username(new CaseInsensitiveString("some user name")));
        scheduleService.autoSchedulePipelinesFromRequestBuffer();
        return pipelineService.mostRecentFullPipelineByName(pipelineName);
    }

    private void assertPipelinesScheduled() {
        Pipeline minglePipeline = pipelineDao.mostRecentPipeline(CaseInsensitiveString.str(mingleConfig.name()));
        Stage mingleStage = minglePipeline.getFirstStage();
        assertThat(mingleStage.getName()).isEqualTo(STAGE_NAME);
        assertThat(mingleStage.getJobInstances().size()).isEqualTo(2);
        JobInstance mingleJob = mingleStage.getJobInstances().first();
        assertThat(mingleJob.getState()).isEqualTo(JobState.Scheduled);

        assertPipelineScheduled(evolveConfig);
        assertPipelineScheduled(goConfig);
    }

    private void assertPipelineScheduled(PipelineConfig config) {
        Stage evolveStage = stageDao.mostRecentWithBuilds(CaseInsensitiveString.str(config.name()), config.findBy(new CaseInsensitiveString("dev")));
        assertThat(evolveStage.getName()).isEqualTo("dev");
        assertThat(evolveStage.getJobInstances().size()).isEqualTo(1);
        assertThat(evolveStage.getJobInstances().first().getState()).isEqualTo(JobState.Scheduled);
    }

    private void verifyMingleScheduledWithModifications() {
        Pipeline scheduledPipeline = pipelineDao.mostRecentPipeline(CaseInsensitiveString.str(mingleConfig.name()));
        BuildCause buildCause = scheduledPipeline.getBuildCause();
        assertThat(buildCause.getMaterialRevisions().totalNumberOfModifications()).isEqualTo(3);
        JobInstance instance = scheduledPipeline.getFirstStage().getJobInstances().first();
        assertThat(instance.getState()).isEqualTo(JobState.Scheduled);
    }

    private Pipeline scheduleAndCompleteInitialPipelines() throws Exception {
        autoSchedulePipelines("mingle", "evolve", "go");
        assertPipelinesScheduled();
        passFirstStage(goConfig);
        return passFirstStage(mingleConfig);
    }

    private Pipeline passFirstStage(PipelineConfig pipelineConfig) {
        Stage completedMingleStage = stageDao.mostRecentWithBuilds(CaseInsensitiveString.str(pipelineConfig.name()), pipelineConfig.findBy(new CaseInsensitiveString("dev")));
        dbHelper.passStage(completedMingleStage);
        dbHelper.passStage(completedMingleStage);
        assertThat(completedMingleStage.getJobInstances().first().getState()).isEqualTo(JobState.Completed);
        Pipeline pipeline = pipelineDao.mostRecentPipeline(CaseInsensitiveString.str(pipelineConfig.name()));
        return dbHelper.passPipelineFirstStageOnly(pipeline);
    }
}
