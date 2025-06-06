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

import ch.qos.logback.classic.Level;
import com.thoughtworks.go.config.Agents;
import com.thoughtworks.go.config.CaseInsensitiveString;
import com.thoughtworks.go.config.GoConfigDao;
import com.thoughtworks.go.config.PipelineConfig;
import com.thoughtworks.go.config.elastic.ClusterProfile;
import com.thoughtworks.go.config.elastic.ElasticProfile;
import com.thoughtworks.go.domain.*;
import com.thoughtworks.go.domain.config.ConfigurationKey;
import com.thoughtworks.go.domain.config.ConfigurationProperty;
import com.thoughtworks.go.domain.config.ConfigurationValue;
import com.thoughtworks.go.helper.BuildPlanMother;
import com.thoughtworks.go.helper.JobInstanceMother;
import com.thoughtworks.go.helper.PipelineMother;
import com.thoughtworks.go.server.cache.GoCache;
import com.thoughtworks.go.server.service.InstanceFactory;
import com.thoughtworks.go.server.service.JobInstanceService;
import com.thoughtworks.go.server.service.ScheduleService;
import com.thoughtworks.go.server.transaction.SqlMapClientTemplate;
import com.thoughtworks.go.server.transaction.TransactionTemplate;
import com.thoughtworks.go.server.ui.SortOrder;
import com.thoughtworks.go.util.GoConfigFileHelper;
import com.thoughtworks.go.util.LogFixture;
import com.thoughtworks.go.util.TimeProvider;
import com.thoughtworks.go.util.command.EnvironmentVariableContext;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.time.Instant;
import java.util.*;

import static com.thoughtworks.go.helper.JobInstanceMother.*;
import static com.thoughtworks.go.helper.ModificationsMother.modifySomeFiles;
import static com.thoughtworks.go.util.GoConstants.DEFAULT_APPROVED_BY;
import static com.thoughtworks.go.util.LogFixture.logFixtureFor;
import static java.time.temporal.ChronoUnit.MINUTES;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = {
        "classpath:/applicationContext-global.xml",
        "classpath:/applicationContext-dataLocalAccess.xml",
        "classpath:/testPropertyConfigurer.xml",
        "classpath:/spring-all-servlet.xml",
})
public class JobInstanceSqlMapDaoIntegrationTest {
    @Autowired
    private JobInstanceSqlMapDao jobInstanceDao;
    @Autowired
    private JobInstanceService jobInstanceService;
    @Autowired
    private EnvironmentVariableDao environmentVariableDao;
    @Autowired
    private PipelineDao pipelineDao;
    @Autowired
    private DatabaseAccessHelper dbHelper;
    @Autowired
    private StageDao stageDao;
    @Autowired
    private GoCache goCache;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private ScheduleService scheduleService;
    @Autowired
    private GoConfigDao goConfigDao;
    @Autowired
    private InstanceFactory instanceFactory;

    private GoConfigFileHelper configHelper = new GoConfigFileHelper();

    private long stageId;
    private static final String JOB_NAME = "functional";
    private static final String JOB_NAME_IN_DIFFERENT_CASE = "FUnctiONAl";
    private final String projectOne = "project1";
    private static final String STAGE_NAME = "mingle";
    private static final String PIPELINE_NAME = "pipeline";
    private PipelineConfig pipelineConfig;
    private Pipeline savedPipeline;
    private Stage savedStage;
    private static final Date MOST_RECENT_DATE = Date.from(Instant.now().plus(20, MINUTES));
    private int counter;
    private static final String OTHER_JOB_NAME = "unit";
    private DefaultSchedulingContext schedulingContext;
    private SqlMapClientTemplate actualSqlClientTemplate;

    @BeforeEach
    public void setUp() throws Exception {
        dbHelper.onSetUp();
        goCache.clear();
        pipelineConfig = PipelineMother.withSingleStageWithMaterials(PIPELINE_NAME, STAGE_NAME, BuildPlanMother.withBuildPlans(JOB_NAME, OTHER_JOB_NAME));
        schedulingContext = new DefaultSchedulingContext(DEFAULT_APPROVED_BY);
        savedPipeline = instanceFactory.createPipelineInstance(pipelineConfig, modifySomeFiles(pipelineConfig), schedulingContext, "md5-test", new TimeProvider());

        dbHelper.savePipelineWithStagesAndMaterials(savedPipeline);

        actualSqlClientTemplate = jobInstanceDao.getSqlMapClientTemplate();

        savedStage = savedPipeline.getFirstStage();
        stageId = savedStage.getId();
        counter = savedPipeline.getFirstStage().getCounter();
        JobInstance job = savedPipeline.getStages().first().getJobInstances().first();
        job.setIgnored(true);
        goCache.clear();
        configHelper.usingCruiseConfigDao(goConfigDao);
        configHelper.onSetUp();
    }

    @AfterEach
    public void teardown() throws Exception {
        jobInstanceDao.setSqlMapClientTemplate(actualSqlClientTemplate);
        dbHelper.onTearDown();
        configHelper.onTearDown();
    }

    @Test
    public void shouldSaveAndRetrieveIncompleteBuild() {
        JobInstance expected = scheduled(JOB_NAME, new Date());
        expected = jobInstanceDao.save(stageId, expected);
        assertThat(expected.getId()).isNotEqualTo(0L);

        JobInstance actual = jobInstanceDao.buildByIdWithTransitions(expected.getId());
        assertThat(actual.getId()).isEqualTo(expected.getId());
    }

    @Test
    public void shouldGetBuildIngBuildAsMostRecentBuildByPipelineLabelAndStageCounter() {
        JobInstance expected = JobInstanceMother.building(JOB_NAME);
        expected.setScheduledDate(MOST_RECENT_DATE);
        expected = jobInstanceDao.save(stageId, expected);

        JobInstance actual = jobInstanceDao.mostRecentJobWithTransitions(
                new JobIdentifier(PIPELINE_NAME, savedPipeline.getCounter(), savedPipeline.getLabel(), STAGE_NAME,
                        String.valueOf(counter), JOB_NAME));

        assertThat(actual.getId()).isEqualTo(expected.getId());
        assertThat(actual.getId()).isEqualTo(expected.getId());
        assertThat(actual.getTransitions()).isEqualTo(expected.getTransitions());
    }

    @Test
    public void shouldGetCompletedBuildAsMostRecentBuildByPipelineLabelAndStageCounter() {
        JobInstance expected = JobInstanceMother.completed(JOB_NAME, JobResult.Unknown);
        expected.setScheduledDate(MOST_RECENT_DATE);
        expected = jobInstanceDao.save(stageId, expected);

        JobInstance actual = jobInstanceDao.mostRecentJobWithTransitions(
                new JobIdentifier(PIPELINE_NAME, savedPipeline.getCounter(), savedPipeline.getLabel(), STAGE_NAME,
                        String.valueOf(counter), JOB_NAME));
        assertThat(actual.getId()).isEqualTo(expected.getId());
    }

    @Test
    public void shouldGetAllRunningJobs() {
        //setup schedules 2 jobs, keeping it as is.
        List<JobInstance> runningJobs = jobInstanceDao.getRunningJobs();

        assertThat(runningJobs.size()).isEqualTo(2);
        List<String> jobNames = runningJobs.stream().map(JobInstance::getName).collect(toList());
        assertThat(jobNames).contains(JOB_NAME, OTHER_JOB_NAME);
    }

    @Test
    public void shouldNotIncludeCompletedJobsAsPartOfRunningJobs() {
        JobInstance completed = JobInstanceMother.completed("Completed_Job", JobResult.Unknown);
        completed.setScheduledDate(MOST_RECENT_DATE);
        completed = jobInstanceDao.save(stageId, completed);

        List<JobInstance> runningJobs = jobInstanceDao.getRunningJobs();

        List<String> jobNames = runningJobs.stream().map(JobInstance::getName).collect(toList());
        assertThat(runningJobs.size()).isEqualTo(2);
        assertThat(jobNames).contains(JOB_NAME, OTHER_JOB_NAME);
        assertThat(runningJobs.contains(completed)).isFalse();
    }

    @Test
    public void shouldFindJobByPipelineCounterWhenTwoPipelinesWithSameLabel() {
        pipelineConfig.setLabelTemplate("fixed-label");
        Pipeline oldPipeline = createNewPipeline(pipelineConfig);
        Pipeline newPipeline = createNewPipeline(pipelineConfig);

        JobInstance expected = oldPipeline.getFirstStage().getJobInstances().first();
        JobInstance actual = jobInstanceDao.mostRecentJobWithTransitions(
                new JobIdentifier(oldPipeline, oldPipeline.getFirstStage(), expected));
        assertThat(actual.getId()).isEqualTo(expected.getId());
    }

    private Pipeline createNewPipeline(PipelineConfig pipelineConfig) {
        Pipeline pipeline = instanceFactory.createPipelineInstance(pipelineConfig, modifySomeFiles(pipelineConfig), new DefaultSchedulingContext(
                DEFAULT_APPROVED_BY), "md5-test", new TimeProvider());
        dbHelper.savePipelineWithStagesAndMaterials(pipeline);
        return pipeline;
    }

    @Test
    public void shouldFindJobIdByPipelineCounter() {
        long actual = jobInstanceDao.findOriginalJobIdentifier(new StageIdentifier(savedPipeline, savedStage), JOB_NAME).getBuildId();

        assertThat(actual).isEqualTo(savedStage.getJobInstances().getByName(JOB_NAME).getId());
    }

    @Test
    public void shouldInvalidateSessionAndFetchJobIdentifier_WhenNewJobIsInserted() {
        configHelper.addPipeline(pipelineConfig);
        configHelper.turnOffSecurity();

        StageIdentifier stageIdentifier = new StageIdentifier(savedPipeline.getName(), savedPipeline.getCounter(), savedPipeline.getLabel(), savedStage.getName(), Integer.toString(savedStage.getCounter() + 1));
        assertThat(jobInstanceDao.findOriginalJobIdentifier(stageIdentifier, JOB_NAME)).isNull();
        dbHelper.passStage(savedStage);
        Stage stage = scheduleService.rerunStage(savedPipeline.getName(), savedPipeline.getCounter(), savedStage.getName());

        JobIdentifier actual = jobInstanceDao.findOriginalJobIdentifier(stageIdentifier, JOB_NAME);

        assertThat(actual).isNotNull();
        assertThat(actual).isEqualTo(new JobIdentifier(stageIdentifier, JOB_NAME, stage.getFirstJob().getId()));
    }

    @Test
    public void shouldFindJobIdByPipelineLabel() {
        long actual = jobInstanceDao.findOriginalJobIdentifier(new StageIdentifier(PIPELINE_NAME, savedPipeline.getCounter(), null, STAGE_NAME, String.valueOf(counter)), JOB_NAME).getBuildId();

        assertThat(actual).isEqualTo(savedStage.getJobInstances().getByName(JOB_NAME).getId());
    }

    @Test
    public void findByJobIdShouldBeJobNameCaseAgnostic() {
        long actual = jobInstanceDao.findOriginalJobIdentifier(new StageIdentifier(PIPELINE_NAME, savedPipeline.getCounter(),
                null, STAGE_NAME, String.valueOf(counter)), JOB_NAME_IN_DIFFERENT_CASE).getBuildId();

        assertThat(actual).isEqualTo(savedStage.getJobInstances().getByName(JOB_NAME).getId());
    }

    @Test
    public void findByJobIdShouldLoadOriginalJobWhenCopiedForJobRerun() {
        Stage firstOldStage = savedPipeline.getStages().get(0);
        Stage newStage = instanceFactory.createStageForRerunOfJobs(firstOldStage, List.of(JOB_NAME), new DefaultSchedulingContext("loser", new Agents()), pipelineConfig.get(0), new TimeProvider(), "md5");

        stageDao.saveWithJobs(savedPipeline, newStage);
        dbHelper.passStage(newStage);

        JobIdentifier oldJobIdentifierThroughOldJob = jobInstanceDao.findOriginalJobIdentifier(new StageIdentifier(PIPELINE_NAME, savedPipeline.getCounter(), null, STAGE_NAME, String.valueOf(counter)), OTHER_JOB_NAME);

        JobIdentifier oldJobIdentifierThroughCopiedNewJob = jobInstanceDao.findOriginalJobIdentifier(new StageIdentifier(PIPELINE_NAME, savedPipeline.getCounter(), null, STAGE_NAME, String.valueOf(newStage.getCounter())), OTHER_JOB_NAME);

        JobIdentifier newJobIdentifierThroughRerunJob = jobInstanceDao.findOriginalJobIdentifier(new StageIdentifier(PIPELINE_NAME, savedPipeline.getCounter(), null, STAGE_NAME, String.valueOf(newStage.getCounter())), JOB_NAME);

        assertThat(oldJobIdentifierThroughOldJob).isEqualTo(firstOldStage.getJobInstances().getByName(OTHER_JOB_NAME).getIdentifier());
        assertThat(oldJobIdentifierThroughCopiedNewJob).isEqualTo(firstOldStage.getJobInstances().getByName(OTHER_JOB_NAME).getIdentifier());
        assertThat(newJobIdentifierThroughRerunJob).isEqualTo(newStage.getJobInstances().getByName(JOB_NAME).getIdentifier());
    }

    @Test
    public void findJobIdShouldExcludeIgnoredJob() {
        JobInstance oldJob = savedStage.getJobInstances().getByName(JOB_NAME);
        jobInstanceDao.ignore(oldJob);

        JobInstance expected = JobInstanceMother.scheduled(JOB_NAME);
        expected = jobInstanceDao.save(stageId, expected);

        long actual = jobInstanceDao.findOriginalJobIdentifier(new StageIdentifier(savedPipeline, savedStage), JOB_NAME).getBuildId();

        assertThat(actual).isEqualTo(expected.getId());
    }

    @Test
    public void shouldFindAllInstancesOfJobsThatAreRunOnAllAgents() {
        List<JobInstance> before = stageDao.mostRecentJobsForStage(PIPELINE_NAME, STAGE_NAME);

        String uuid1 = UUID.randomUUID().toString();
        String uuid2 = UUID.randomUUID().toString();

        JobInstance instance1 = savedJobForAgent(JOB_NAME + "-" + uuid1, uuid1, true, false);
        JobInstance instance2 = savedJobForAgent(JOB_NAME + "-" + uuid2, uuid2, true, false);

        List<JobInstance> after = stageDao.mostRecentJobsForStage(PIPELINE_NAME, STAGE_NAME);
        after.removeAll(before);
        assertThat(after).satisfiesExactlyInAnyOrder(
                j -> assertThat(j.getName()).isEqualTo(instance1.getName()),
                j -> assertThat(j.getName()).isEqualTo(instance2.getName())
        );
    }

    @Test
    public void shouldFindAllInstancesOfJobsThatAreRunMultipleInstance() {
        List<JobInstance> before = stageDao.mostRecentJobsForStage(PIPELINE_NAME, STAGE_NAME);

        JobInstance instance1 = savedJobForAgent(RunMultipleInstance.CounterBasedJobNameGenerator.appendMarker("job", 1), null, false, true);
        JobInstance instance2 = savedJobForAgent(RunMultipleInstance.CounterBasedJobNameGenerator.appendMarker("job", 2), null, false, true);

        List<JobInstance> after = stageDao.mostRecentJobsForStage(PIPELINE_NAME, STAGE_NAME);
        after.removeAll(before);
        assertThat(after).satisfiesExactlyInAnyOrder(
            j -> assertThat(j.getName()).isEqualTo(instance1.getName()),
            j -> assertThat(j.getName()).isEqualTo(instance2.getName())
        );
    }

    @Test
    public void shouldLoadOldestBuild() {
        JobStateTransition jobStateTransition = jobInstanceDao.oldestBuild();
        assertThat(jobStateTransition.getId()).isEqualTo(stageDao.stageById(stageId).getJobInstances().first().getTransitions().first().getId());
    }

    private JobInstance savedJobForAgent(final String jobName, final String uuid, final boolean runOnAllAgents, final boolean runMultipleInstance) {
        return transactionTemplate.execute(status -> {
            JobInstance jobInstance = scheduled(jobName, Date.from(Instant.now().plus(1, MINUTES)));
            jobInstance.setRunOnAllAgents(runOnAllAgents);
            jobInstance.setRunMultipleInstance(runMultipleInstance);
            jobInstanceService.save(savedStage.getIdentifier(), stageId, jobInstance);
            jobInstance.changeState(JobState.Building);
            jobInstance.setAgentUuid(uuid);
            jobInstanceDao.updateStateAndResult(jobInstance);
            return jobInstance;
        });
    }

    @Test
    public void shouldThrowWhenNoBuildInstanceFound() {
        assertThatThrownBy(() -> jobInstanceDao.buildByIdWithTransitions(999))
                .isInstanceOf(DataRetrievalFailureException.class);
    }

    @Test
    public void shouldReturnBuildInstanceIfItExists() {
        JobInstance jobInstance = JobInstanceMother.completed("Baboon", JobResult.Passed);
        JobInstance instance = jobInstanceDao.save(stageId, jobInstance);
        JobInstance actual = jobInstanceDao.buildByIdWithTransitions(instance.getId());
        assertThat(actual.isNull()).isFalse();
    }

    @Test
    public void shouldLogStatusUpdatesOfCompletedJobs() {
        try (LogFixture logFixture = logFixtureFor(JobInstanceSqlMapDao.class, Level.DEBUG)) {
            JobInstance instance = runningJob("1");
            completeJobs(instance);
            instance.schedule();
            jobInstanceDao.updateStateAndResult(instance);
            assertThat(logFixture.contains(Level.WARN, "State change for a completed Job is not allowed.")).isTrue();
        }
    }

    private JobInstance[] completeJobs(JobInstance... instances) {
        for (JobInstance instance : instances) {
            complete(instance);
        }
        return instances;
    }

    private JobInstance runningJob(final String name) {
        JobInstance jobInstance = JobInstanceMother.buildingInstance("pipeline", "stage", name, "1");
        jobInstanceDao.save(stageId, jobInstance);
        return jobInstance;
    }

    private void complete(JobInstance jobInstance) {
        jobInstance.completing(JobResult.Passed);
        jobInstance.completed(new Date());
        jobInstanceDao.updateStateAndResult(jobInstance);
    }

    @Test
    public void shouldUpdateBuildResult() {
        JobInstance jobInstance = JobInstanceMother.scheduled("Baboon");
        jobInstanceDao.save(stageId, jobInstance);
        jobInstance.cancel();
        jobInstanceDao.updateStateAndResult(jobInstance);
        JobInstance instance = jobInstanceDao.buildByIdWithTransitions(jobInstance.getId());
        assertThat(instance.getResult()).isEqualTo(JobResult.Cancelled);

        jobInstance.fail();
        jobInstanceDao.updateStateAndResult(jobInstance);
        instance = jobInstanceDao.buildByIdWithTransitions(jobInstance.getId());
        assertThat(instance.getResult()).isEqualTo(JobResult.Failed);

    }

    @Test
    public void shouldDeleteJobPlanAssociatedEntities() {
        JobInstance jobInstance = JobInstanceMother.building("Baboon");

        JobPlan jobPlan = JobInstanceMother.jobPlanWithAssociatedEntities(jobInstance.getName(), jobInstance.getId(), artifactPlans());
        jobInstance.setPlan(jobPlan);

        jobInstanceDao.save(stageId, jobInstance);
        JobPlan jobPlanFromDb = jobInstanceDao.loadPlan(jobInstance.getId());

        assertThat(jobPlanFromDb.getArtifactPlans()).isEqualTo(jobPlan.getArtifactPlans());
        assertThat(jobPlanFromDb.getResources()).isEqualTo(jobPlan.getResources());
        assertThat(jobPlanFromDb.getVariables()).isEqualTo(jobPlan.getVariables());
        assertThat(jobPlanFromDb.getElasticProfile()).isEqualTo(jobPlan.getElasticProfile());

        jobInstanceDao.deleteJobPlanAssociatedEntities(jobInstance);
        jobPlanFromDb = jobInstanceDao.loadPlan(jobInstance.getId());

        assertThat(jobPlanFromDb.getArtifactPlans()).containsExactlyInAnyOrder(
                new ArtifactPlan(ArtifactPlanType.unit, "unit", "unit"),
                new ArtifactPlan(ArtifactPlanType.unit, "integration", "integration")
        );
        assertThat(jobPlanFromDb.getResources().size()).isEqualTo(0);
        assertThat(jobPlanFromDb.getVariables().size()).isEqualTo(0);
        assertThat(jobPlanFromDb.getElasticProfile()).isNull();
    }

    @Test
    public void shouldDeleteVariablesAttachedToJobAfterTheJobReschedules() {
        JobInstance jobInstance = JobInstanceMother.building("Baboon");

        JobPlan jobPlan = JobInstanceMother.jobPlanWithAssociatedEntities(jobInstance.getName(), jobInstance.getId(), artifactPlans());
        jobInstance.setPlan(jobPlan);

        jobInstanceDao.save(stageId, jobInstance);
        JobPlan jobPlanFromDb = jobInstanceDao.loadPlan(jobInstance.getId());

        assertThat(jobPlanFromDb.getArtifactPlans().size()).isEqualTo(4);
        assertThat(jobPlanFromDb.getResources()).isEqualTo(jobPlan.getResources());
        assertThat(jobPlanFromDb.getVariables()).isEqualTo(jobPlan.getVariables());
        assertThat(jobPlanFromDb.getElasticProfile()).isEqualTo(jobPlan.getElasticProfile());

        jobInstance.setState(JobState.Rescheduled);
        jobInstanceDao.ignore(jobInstance);
        jobPlanFromDb = jobInstanceDao.loadPlan(jobInstance.getId());

        assertThat(jobPlanFromDb.getArtifactPlans()).containsExactlyInAnyOrder(
                new ArtifactPlan(ArtifactPlanType.unit, "unit", "unit"),
                new ArtifactPlan(ArtifactPlanType.unit, "integration", "integration")
        );
        assertThat(jobPlanFromDb.getResources().size()).isEqualTo(0);
        assertThat(jobPlanFromDb.getVariables().size()).isEqualTo(0);
        assertThat(jobPlanFromDb.getElasticProfile()).isNull();

    }

    @Test
    public void shouldThrowIfItNotExists() {
        assertThatThrownBy(() -> jobInstanceDao.buildByIdWithTransitions(1))
                .isInstanceOf(DataRetrievalFailureException.class);
    }

    @Test
    public void shouldNotGetJobsFromBeforeTheJobNameIsChanged() {
        String oldName = "oldName";
        createSomeJobs(oldName, 15);

        String newName = "newName";
        createSomeJobs(newName, 10);

        JobInstances myinstances = jobInstanceDao.latestCompletedJobs(PIPELINE_NAME, STAGE_NAME, newName, 25);
        assertThat(myinstances.size()).isEqualTo(10);
        assertThat(myinstances.get(0).getName()).isNotEqualTo(oldName);
        assertThat(myinstances.get(0).getName()).isEqualTo(newName);
    }

    private long createSomeJobs(String jobName, int count) {
        long stageId = 0;
        for (int i = 0; i < count; i++) {
            Pipeline newPipeline = createNewPipeline(pipelineConfig);
            stageId = newPipeline.getFirstStage().getId();
            JobInstance job = JobInstanceMother.completed(jobName, JobResult.Passed);
            jobInstanceDao.save(stageId, job);
        }
        return stageId;
    }

    private void createCopiedJobs(long stageId, String jobName, int count) {
        for (int i = 0; i < count; i++) {
            JobInstance job = JobInstanceMother.completed(jobName, JobResult.Failed);
            job.setOriginalJobId(1L);
            jobInstanceDao.save(stageId, job);
        }
    }

    @Test
    public void shouldGetMostRecentCompletedBuildsWhenTotalBuildsIsLessThan25() {
        JobInstance jobInstance = JobInstanceMother.completed("shouldnotload", JobResult.Passed);
        jobInstanceDao.save(stageId, jobInstance);

        createSomeJobs(JOB_NAME, 3);

        JobInstances instances = jobInstanceDao.latestCompletedJobs(PIPELINE_NAME, STAGE_NAME, JOB_NAME, 25);
        assertThat(instances.size()).isEqualTo(3);
    }

    @Test
    public void shouldLoadStageCounter() {
        JobInstance jobInstance = JobInstanceMother.completed("shouldnotload", JobResult.Passed);
        jobInstanceDao.save(stageId, jobInstance);
        createSomeJobs(JOB_NAME, 3);

        JobInstances instances = jobInstanceDao.latestCompletedJobs(PIPELINE_NAME, STAGE_NAME, JOB_NAME, 25);

        for (JobInstance instance : instances) {
            Pipeline pipeline = pipelineDao.pipelineWithMaterialsAndModsByBuildId(instance.getId());
            String locator = pipeline.getName() +
                    "/" + pipeline.getLabel() + "/" + savedStage.getName() + "/1/" + JOB_NAME;
            assertThat(instance.getIdentifier().buildLocator()).isEqualTo(locator);
        }
    }

    @Test
    public void shouldGet25Builds() {
        createSomeJobs(JOB_NAME, 30);

        JobInstances instances = jobInstanceDao.latestCompletedJobs(PIPELINE_NAME, STAGE_NAME, JOB_NAME, 25);
        assertThat(instances.size()).isEqualTo(25);

        for (JobInstance instance : instances) {
            assertThat(instance.getIdentifier().getBuildName()).isEqualTo(JOB_NAME);
        }
    }

    @Test
    public void shouldGet25Builds_AlthoughFirst5AreCopied() {
        long stageId = createSomeJobs(JOB_NAME, 30);
        createCopiedJobs(stageId, JOB_NAME, 5);

        JobInstances instances = jobInstanceDao.latestCompletedJobs(PIPELINE_NAME, STAGE_NAME, JOB_NAME, 25);
        assertThat(instances.size()).isEqualTo(25);

        for (JobInstance instance : instances) {
            assertThat(instance.getIdentifier().getBuildName()).isEqualTo(JOB_NAME);
            assertThat(instance.isCopy()).isFalse();
        }
    }

    @Test
    public void shouldGetMostRecentCompletedBuildsWhenTwoStagesWithIdenticalStageNamesAndBuildPlanNames() {

        Pipeline otherPipeline = PipelineMother.passedPipelineInstance(PIPELINE_NAME + "2", STAGE_NAME, JOB_NAME);
        dbHelper.savePipelineWithStagesAndMaterials(otherPipeline);

        for (int i = 0; i < 2; i++) {
            Pipeline completedPipeline = PipelineMother.passedPipelineInstance(PIPELINE_NAME, STAGE_NAME, JOB_NAME);
            dbHelper.savePipelineWithStagesAndMaterials(completedPipeline);
        }

        assertThat(jobInstanceDao.latestCompletedJobs(PIPELINE_NAME, STAGE_NAME, JOB_NAME, 25).size()).isEqualTo(2);

        assertThat(jobInstanceDao.latestCompletedJobs(otherPipeline.getName(), STAGE_NAME, JOB_NAME, 25).size()).isEqualTo(1);
    }

    @Test
    public void shouldIgnoreBuildingBuilds() {
        JobInstance instance = JobInstanceMother.completed("shouldnotload", JobResult.Passed);
        jobInstanceDao.save(stageId, instance);
        JobInstance building = JobInstanceMother.building(JOB_NAME);
        JobInstance saved = jobInstanceDao.save(stageId, building);

        JobInstances instances =
                jobInstanceDao.latestCompletedJobs(PIPELINE_NAME, STAGE_NAME, JOB_NAME, 25);
        assertThat(instances.size()).isEqualTo(0);
    }

    @Test
    public void shouldCorrectly_getJobHistoryCount_findJobHistoryPage() {
        // has a scheduled job
        long stageId = createSomeJobs(JOB_NAME, 2); // create 4 instances completed, scheduled, completed, scheduled
        createCopiedJobs(stageId, JOB_NAME, 2);

        JobInstance shouldNotLoadInstance = JobInstanceMother.completed("shouldnotload", JobResult.Passed); // create job with a different name
        jobInstanceDao.save(stageId, shouldNotLoadInstance);

        JobInstance building = JobInstanceMother.building(JOB_NAME); // create a building job
        JobInstance saved = jobInstanceDao.save(stageId, building);

        int jobHistoryCount = jobInstanceDao.getJobHistoryCount(PIPELINE_NAME, STAGE_NAME, JOB_NAME);
        assertThat(jobHistoryCount).isEqualTo(6);

        JobInstances instances = jobInstanceDao.findJobHistoryPage(PIPELINE_NAME, STAGE_NAME, JOB_NAME, 4, 0);
        assertThat(instances.size()).isEqualTo(4);

        assertThat(instances.get(0).getState()).isEqualTo(JobState.Building);
        assertThat(instances.get(1).getState()).isEqualTo(JobState.Completed);
        assertThat(instances.get(2).getState()).isEqualTo(JobState.Scheduled);
        assertThat(instances.get(3).getState()).isEqualTo(JobState.Completed);
        assertJobHistoryCorrectness(instances, JOB_NAME);

        instances = jobInstanceDao.findJobHistoryPage(PIPELINE_NAME, STAGE_NAME, JOB_NAME, 4, 4);
        assertThat(instances.size()).isEqualTo(2);

        assertThat(instances.get(0).getState()).isEqualTo(JobState.Scheduled);
        assertThat(instances.get(1).getState()).isEqualTo(JobState.Scheduled);
        assertJobHistoryCorrectness(instances, JOB_NAME);
    }

    private void assertJobHistoryCorrectness(JobInstances instances, String jobName) {
        for (JobInstance instance : instances) {
            assertThat(instance.getIdentifier().getBuildName()).isEqualTo(jobName);
            assertThat(instance.isCopy()).isFalse();
        }
    }

    @Test
    public void shouldLoadRerunOfCounterValueForScheduledBuilds() {
        List<JobPlan> jobPlans = jobInstanceDao.orderedScheduledBuilds();
        assertThat(jobPlans.size()).isEqualTo(2);
        assertThat(jobPlans.get(0).getIdentifier().getRerunOfCounter()).isNull();
        assertThat(jobPlans.get(1).getIdentifier().getRerunOfCounter()).isNull();

        dbHelper.passStage(savedStage);
        Stage stage = instanceFactory.createStageForRerunOfJobs(savedStage, List.of(JOB_NAME), schedulingContext, pipelineConfig.getStage(new CaseInsensitiveString(STAGE_NAME)), new TimeProvider(), "md5");
        dbHelper.saveStage(savedPipeline, stage, stage.getOrderId() + 1);

        jobPlans = jobInstanceDao.orderedScheduledBuilds();
        assertThat(jobPlans.size()).isEqualTo(1);
        assertThat(jobPlans.get(0).getIdentifier().getRerunOfCounter()).isEqualTo(savedStage.getCounter());
    }

    @Test
    public void shouldGetAllScheduledBuildsInOrder() {
        // in setup, we created 2 scheduled builds
        assertThat(jobInstanceDao.orderedScheduledBuilds().size()).isEqualTo(2);

        JobIdentifier jobIdentifier = new JobIdentifier(PIPELINE_NAME, 1, "LABEL-1", STAGE_NAME, "1", JOB_NAME);

        long newestId = schedule(JOB_NAME, stageId, new Date(10001), jobIdentifier);
        long olderId = schedule(JOB_NAME, stageId, new Date(10000), jobIdentifier);
        long oldestId = schedule(JOB_NAME, stageId, new Date(999), jobIdentifier);


        List<JobPlan> jobPlans = jobInstanceDao.orderedScheduledBuilds();
        assertThat(jobPlans.size()).isEqualTo(5);
        assertJobInstance(jobPlans.get(0), oldestId, PIPELINE_NAME, STAGE_NAME);
        assertJobInstance(jobPlans.get(1), olderId, PIPELINE_NAME, STAGE_NAME);
        assertJobInstance(jobPlans.get(2), newestId, PIPELINE_NAME, STAGE_NAME);
    }

    private long schedule(String jobName, long stageId, Date date, JobIdentifier jobIdentifier) {
        JobInstance newest = new JobInstance(jobName);
        newest.setScheduledDate(date);
        jobInstanceDao.save(stageId, newest);

        jobInstanceDao.save(newest.getId(), new DefaultJobPlan(new Resources(), new ArrayList<>(), -1, jobIdentifier, null, new EnvironmentVariables(), new EnvironmentVariables(), null, null));

        return newest.getId();
    }

    private void assertJobInstance(JobPlan actual, long expect, String pipelineName, String stageName) {
        assertThat(actual.getPipelineName()).isEqualTo(pipelineName);
        assertThat(actual.getStageName()).isEqualTo(stageName);
        assertThat(actual.getJobId()).isEqualTo(expect);
    }

    @Test
    public void shouldUpdateStateTransitions() {
        JobInstance expected = scheduled(JOB_NAME, new Date(1000));
        jobInstanceDao.save(stageId, expected);
        JobInstance actual = jobInstanceDao.buildByIdWithTransitions(expected.getId());
        assertThat(actual.getTransitions()).hasSize(1);
        expected.changeState(JobState.Assigned);
        jobInstanceDao.updateStateAndResult(expected);
        actual = jobInstanceDao.buildByIdWithTransitions(expected.getId());
        assertThat(actual.getTransitions()).hasSize(2);
        for (JobStateTransition transition : actual.getTransitions()) {
            assertThat(transition.getStageId()).isEqualTo(stageId);
        }
    }

    @Test
    public void shouldUpdateBuildStatus() {
        JobInstance expected = scheduled(JOB_NAME);
        jobInstanceDao.save(stageId, expected);
        expected.changeState(JobState.Building);
        jobInstanceDao.updateStateAndResult(expected);
        JobInstance actual = jobInstanceDao.buildByIdWithTransitions(expected.getId());
        assertThat(actual.getState()).isEqualTo(JobState.Building);
        assertThat(actual.getTransitions().size()).isEqualTo(2);
    }

    @Test
    public void shouldUpdateAssignedInfo() {
        JobInstance expected = scheduled(JOB_NAME);
        jobInstanceDao.save(stageId, expected);
        expected.changeState(JobState.Building);
        expected.setAgentUuid("uuid");
        jobInstanceDao.updateAssignedInfo(expected);
        JobInstance actual = jobInstanceDao.buildByIdWithTransitions(expected.getId());
        assertThat(actual.getState()).isEqualTo(JobState.Building);
        assertThat(actual.getTransitions().size()).isEqualTo(2);
        assertThat(actual.getAgentUuid()).isEqualTo("uuid");
    }

    @Test
    public void shouldUpdateCompletingInfo() {
        JobInstance expected = scheduled(JOB_NAME);
        jobInstanceDao.save(stageId, expected);
        expected.changeState(JobState.Completing);
        expected.setResult(JobResult.Failed);
        jobInstanceDao.updateStateAndResult(expected);
        JobInstance actual = jobInstanceDao.buildByIdWithTransitions(expected.getId());
        assertThat(actual.getState()).isEqualTo(JobState.Completing);
        assertThat(actual.getTransitions().size()).isEqualTo(2);
        assertThat(actual.getResult()).isEqualTo(JobResult.Failed);
    }

    @Test
    public void shouldSaveTransitionsCorrectly() {
        JobInstance jobInstance = scheduled(projectOne, new Date(1));
        jobInstance.completing(JobResult.Failed, new Date(3));

        jobInstanceDao.save(stageId, jobInstance);

        JobInstance loaded = jobInstanceDao.buildByIdWithTransitions(jobInstance.getId());

        JobStateTransitions actualTransitions = loaded.getTransitions();
        assertThat(actualTransitions).hasSize(2);
        assertThat(actualTransitions.first().getCurrentState()).isEqualTo(JobState.Scheduled);
    }

    @Test
    public void shouldSaveResources() {
        JobInstance instance = jobInstanceDao.save(stageId, new JobInstance(JOB_NAME));
        instance.setIdentifier(new JobIdentifier(savedPipeline, savedStage, instance));
        JobPlan plan = new DefaultJobPlan(new Resources("something"), new ArrayList<>(),
                instance.getId(), instance.getIdentifier(), null, new EnvironmentVariables(), new EnvironmentVariables(), null, null);
        jobInstanceDao.save(instance.getId(), plan);
        JobPlan retrieved = jobInstanceDao.loadPlan(plan.getJobId());
        assertThat(retrieved).isEqualTo(plan);
    }

    @Test
    public void shouldSaveJobAgentMetadata_WhenClusterProfileIsAssociatedWithElasticAgentProfile() {
        JobInstance instance = jobInstanceDao.save(stageId, new JobInstance(JOB_NAME));
        instance.setIdentifier(new JobIdentifier(savedPipeline, savedStage, instance));

        ElasticProfile elasticProfile = new ElasticProfile("foo", "clusterId", List.of(new ConfigurationProperty(new ConfigurationKey("key"), new ConfigurationValue("value"))));
        ClusterProfile clusterProfile = new ClusterProfile("clusterId", "cd.go.elastic-agent:docker", List.of(new ConfigurationProperty(new ConfigurationKey("key"), new ConfigurationValue("value"))));

        JobPlan plan = new DefaultJobPlan(new Resources("something"), new ArrayList<>(),
                instance.getId(), instance.getIdentifier(), null, new EnvironmentVariables(), new EnvironmentVariables(), elasticProfile, clusterProfile);
        jobInstanceDao.save(instance.getId(), plan);

        JobPlan retrieved = jobInstanceDao.loadPlan(plan.getJobId());
        assertThat(retrieved.getElasticProfile()).isEqualTo(elasticProfile);
        assertThat(retrieved.getClusterProfile()).isEqualTo(clusterProfile);
    }

    @Test
    public void shouldNotThrowUpWhenJobAgentMetadataIsNull() {
        JobInstance instance = jobInstanceDao.save(stageId, new JobInstance(JOB_NAME));
        instance.setIdentifier(new JobIdentifier(savedPipeline, savedStage, instance));
        ElasticProfile elasticProfile = null;
        JobPlan plan = new DefaultJobPlan(new Resources("something"), new ArrayList<>(),
                instance.getId(), instance.getIdentifier(), null, new EnvironmentVariables(), new EnvironmentVariables(), elasticProfile, null);
        jobInstanceDao.save(instance.getId(), plan);

        JobPlan retrieved = jobInstanceDao.loadPlan(plan.getJobId());
        assertThat(retrieved.getElasticProfile()).isEqualTo(elasticProfile);
    }

    @Test
    public void shouldSaveEnvironmentVariables() {
        JobInstance instance = jobInstanceDao.save(stageId, new JobInstance(JOB_NAME));
        instance.setIdentifier(new JobIdentifier(savedPipeline, savedStage, instance));

        EnvironmentVariables variables = new EnvironmentVariables();
        variables.add("VARIABLE_NAME", "variable value");
        variables.add("TRIGGER_VAR", "junk val");
        JobPlan plan = new DefaultJobPlan(new Resources(), new ArrayList<>(),
                instance.getId(),
                instance.getIdentifier(), null, variables, new EnvironmentVariables(), null, null);
        jobInstanceDao.save(instance.getId(), plan);
        environmentVariableDao.save(savedPipeline.getId(), EnvironmentVariableType.Trigger, environmentVariables("TRIGGER_VAR", "trigger val"));
        JobPlan retrieved = jobInstanceDao.loadPlan(plan.getJobId());
        assertThat(retrieved.getVariables()).isEqualTo(plan.getVariables());
        EnvironmentVariableContext context = new EnvironmentVariableContext();
        retrieved.applyTo(context);
        assertThat(context.getProperty("VARIABLE_NAME")).isEqualTo("variable value");
        assertThat(context.getProperty("TRIGGER_VAR")).isEqualTo("trigger val");
    }

    private EnvironmentVariables environmentVariables(String name, String value) {
        return new EnvironmentVariables(List.of(new EnvironmentVariable(name, value, false)));
    }

    @Test
    public void shouldLoadEnvironmentVariablesForScheduledJobs() {
        JobInstance newInstance = new JobInstance(JOB_NAME);
        newInstance.schedule();
        JobInstance instance = jobInstanceDao.save(stageId, newInstance);
        instance.setIdentifier(new JobIdentifier(savedPipeline, savedStage, instance));

        EnvironmentVariables variables = new EnvironmentVariables();
        variables.add("VARIABLE_NAME", "variable value");
        variables.add("TRIGGER_VAR", "junk val");
        JobPlan plan = new DefaultJobPlan(new Resources(), new ArrayList<>(),
                instance.getId(),
                instance.getIdentifier(), null, variables, new EnvironmentVariables(), null, null);
        jobInstanceDao.save(instance.getId(), plan);

        environmentVariableDao.save(savedPipeline.getId(), EnvironmentVariableType.Trigger, environmentVariables("TRIGGER_VAR", "trigger val"));

        List<JobPlan> retrieved = jobInstanceDao.orderedScheduledBuilds();

        JobPlan reloadedPlan = planForJob(retrieved, plan.getJobId());
        EnvironmentVariableContext context = new EnvironmentVariableContext();
        reloadedPlan.applyTo(context);
        assertThat(reloadedPlan.getVariables()).isEqualTo(plan.getVariables());
        assertThat(context.getProperty("VARIABLE_NAME")).isEqualTo("variable value");
        assertThat(context.getProperty("TRIGGER_VAR")).isEqualTo("trigger val");
    }

    private JobPlan planForJob(List<JobPlan> retrieved, long expectedJobId) {
        for (JobPlan loadedJobPlan : retrieved) {
            if (loadedJobPlan.getJobId() == expectedJobId) {
                return loadedJobPlan;
            }
        }
        return null;
    }

    @Test
    public void shouldLoadArtifactsAndResourcesForAssignment() {
        JobInstance instance = jobInstanceDao.save(stageId, new JobInstance(projectOne));
        instance.setIdentifier(new JobIdentifier(savedPipeline, savedStage, instance));
        Resources resources = new Resources("one, two, three");
        JobPlan savedPlan = new DefaultJobPlan(resources, artifactPlans(), instance.getId(), instance.getIdentifier(), null, new EnvironmentVariables(), new EnvironmentVariables(), null, null);

        jobInstanceDao.save(instance.getId(), savedPlan);

        final List<JobPlan> planList = jobInstanceDao.orderedScheduledBuilds();

        final List<JobPlan> plans = findPlans(planList, projectOne);

        assertThat(plans.size()).isEqualTo(1);
        assertThat(plans.get(0).getResources()).isEqualTo(resources);
    }

    @Test
    public void shouldLoadJobIdentifierForAssignment() {
        JobInstance jobInstance = scheduled(projectOne);
        jobInstanceDao.save(stageId, jobInstance);

        JobPlan job = findPlan(jobInstanceDao.orderedScheduledBuilds(), projectOne);
        assertThat(job.getIdentifier()).isEqualTo(jobIdentifier(jobInstance));
    }

    @Test
    public void shouldLoadAgentUuidForAssignment() {
        JobInstance jobInstance = scheduled(projectOne);
        jobInstance.setAgentUuid("uuid1");
        jobInstanceDao.save(stageId, jobInstance);

        JobPlan job = findPlan(jobInstanceDao.orderedScheduledBuilds(), projectOne);
        assertThat(job.getAgentUuid()).isEqualTo("uuid1");
    }

    @Test
    public void shouldLoadRunOnAllAgentsForAssignment() {
        JobInstance jobInstance = scheduled(projectOne);
        jobInstance.setRunOnAllAgents(true);
        jobInstanceDao.save(stageId, jobInstance);

        JobInstance reloaded = jobInstanceDao.buildByIdWithTransitions(jobInstance.getId());
        assertThat(reloaded.isRunOnAllAgents()).isTrue();
    }

    @Test
    public void shouldLoadRunMultipleInstanceForAssignment() {
        JobInstance jobInstance = scheduled(projectOne);
        jobInstance.setRunMultipleInstance(true);
        jobInstanceDao.save(stageId, jobInstance);

        JobInstance reloaded = jobInstanceDao.buildByIdWithTransitions(jobInstance.getId());
        assertThat(reloaded.isRunMultipleInstance()).isTrue();
    }

    private JobIdentifier jobIdentifier(JobInstance jobInstance) {
        return new JobIdentifier(savedPipeline, savedStage, jobInstance);
    }

    private JobPlan findPlan(List<JobPlan> list, String jobName) {
        final List<JobPlan> planList = findPlans(list, jobName);

        if (planList.size() > 0) {
            return planList.get(0);
        }

        return null;
    }

    private List<JobPlan> findPlans(List<JobPlan> list, String jobName) {
        List<JobPlan> result = new ArrayList<>();
        for (JobPlan buildNameBean : list) {
            if (jobName.equals(buildNameBean.getName())) {
                result.add(buildNameBean);
            }
        }

        return result;
    }

    @Test
    public void shouldGetLatestInProgressBuildByAgentUuid() {
        JobInstance buildingJob = building(projectOne, new Date(1));
        final String uuid = "uuid";
        buildingJob.setAgentUuid(uuid);
        jobInstanceDao.save(stageId, buildingJob);

        JobInstance completedJob = JobInstanceMother.completed("anotherBuild", JobResult.Passed);
        completedJob.setAgentUuid(uuid);
        jobInstanceDao.save(stageId, completedJob);

        JobInstance jobInstance = jobInstanceDao.getLatestInProgressBuildByAgentUuid(uuid);
        assertThat(jobInstance.getId()).isEqualTo(buildingJob.getId());
        assertThat(jobInstance.getIdentifier()).isEqualTo(jobIdentifier(jobInstance));
    }

    @Test
    public void shouldGetInProgressJobs() {
        JobInstance buildingJob1 = building(projectOne, new Date(1));
        buildingJob1.setAgentUuid("uuid1");
        jobInstanceDao.save(stageId, buildingJob1);

        JobInstance buildingJob2 = building("project2", new Date(2));
        buildingJob2.setAgentUuid("uuid2");
        jobInstanceDao.save(stageId, buildingJob2);

        JobInstance buildingJob3 = building("project3", new Date(3));
        buildingJob3.setAgentUuid("uuid3");
        jobInstanceDao.save(stageId, buildingJob3);

        JobInstances list = jobInstanceDao.findHungJobs(List.of("uuid1", "uuid2"));
        assertThat(list.size()).isEqualTo(1);
        JobInstance reloaded = list.get(0);
        assertThat(reloaded.getId()).isEqualTo(buildingJob3.getId());
        assertThat(reloaded.getIdentifier()).isEqualTo(jobIdentifier(buildingJob3));
    }


    @Test
    public void shouldIgnore() {
        JobInstance instance = scheduled(projectOne);
        jobInstanceDao.save(stageId, instance);
        jobInstanceDao.ignore(instance);

        JobInstance reloaded = jobInstanceDao.buildByIdWithTransitions(instance.getId());
        assertThat(reloaded.isIgnored()).isTrue();
    }

    @Test
    public void shouldGetCompletedJobsOnAgentForARange() {
        String agentUuid = "special_uuid";
        JobInstance buildingJob = building("job1", new Date(1));
        buildingJob.setAgentUuid(agentUuid);
        jobInstanceDao.save(stageId, buildingJob);

        JobInstance completedJob = completed("job2", JobResult.Passed, new Date(1));
        completedJob.setAgentUuid(agentUuid);
        jobInstanceDao.save(stageId, completedJob);

        JobInstance cancelledJob = cancelled("job3");
        cancelledJob.setAgentUuid("something_different");//Different UUID. Should not be considered
        jobInstanceDao.save(stageId, cancelledJob);

        JobInstance rescheduledJob = rescheduled("rescheduled", agentUuid);
        jobInstanceDao.save(stageId, rescheduledJob);
        jobInstanceDao.ignore(rescheduledJob);

        List<JobInstance> jobInstances = jobInstanceDao.completedJobsOnAgent(agentUuid, JobInstanceService.JobHistoryColumns.stage, SortOrder.ASC, 0, 10);
        assertThat(jobInstances.size()).isEqualTo(2);
        JobInstance actual = jobInstances.get(0);
        assertThat(actual.getName()).isEqualTo(completedJob.getName());
        completedJob.setIdentifier(actual.getIdentifier());
        assertThat(actual).isEqualTo(completedJob);

        actual = jobInstances.get(1);
        assertThat(actual.getName()).isEqualTo(rescheduledJob.getName());
        rescheduledJob.setIdentifier(actual.getIdentifier());
        assertThat(actual).isEqualTo(rescheduledJob);
    }

    @Test
    public void shouldGetTotalNumberOfCompletedJobsForAnAgent() {
        String agentUuid = "special_uuid";
        JobInstance buildingJob = building("job1", new Date(1));
        buildingJob.setAgentUuid(agentUuid);
        jobInstanceDao.save(stageId, buildingJob);

        JobInstance completedJob = completed("job2", JobResult.Passed, new Date(1));
        completedJob.setAgentUuid(agentUuid);
        jobInstanceDao.save(stageId, completedJob);

        JobInstance rescheduledJob = rescheduled("rescheduled", agentUuid);
        jobInstanceDao.save(stageId, rescheduledJob);
        jobInstanceDao.ignore(rescheduledJob);

        JobInstance cancelledJob = cancelled("job3");
        cancelledJob.setAgentUuid("something_different");//Different UUID. Should not be counted
        jobInstanceDao.save(stageId, cancelledJob);

        JobInstance simpleJob = failed("simpleJob");
        simpleJob.setAgentUuid(agentUuid);
        jobInstanceDao.save(stageId, simpleJob);

        assertThat(jobInstanceDao.totalCompletedJobsOnAgent(agentUuid)).isEqualTo(3);
    }

    @Test
    public void shouldGetJobInstanceBasedOnParametersProvided() {
        long stageId = createSomeJobs(JOB_NAME, 1); // create 2 instances completed, scheduled
        JobInstance jobInstance = jobInstanceDao.findJobInstance(PIPELINE_NAME, STAGE_NAME, JOB_NAME, 1, 1);

        assertThat(jobInstance.isNull()).isFalse();
    }

    @Test
    public void shouldReturnNullJobInstanceWhenTheSaidCountersAreNotYetRun() {
        long stageId = createSomeJobs(JOB_NAME, 1); // create 2 instances completed, scheduled
        JobInstance jobInstance = jobInstanceDao.findJobInstance(PIPELINE_NAME, STAGE_NAME, JOB_NAME, 10, 10);

        assertThat(jobInstance.isNull()).isTrue();
    }

    private List<ArtifactPlan> artifactPlans() {
        List<ArtifactPlan> artifactPlans = new ArrayList<>();
        artifactPlans.add(new ArtifactPlan(ArtifactPlanType.file, "src", "dest"));
        artifactPlans.add(new ArtifactPlan(ArtifactPlanType.file, "src1", "dest2"));
        artifactPlans.add(new ArtifactPlan(ArtifactPlanType.unit, "unit", "unit"));
        artifactPlans.add(new ArtifactPlan(ArtifactPlanType.unit, "integration", "integration"));
        return artifactPlans;
    }

    @Test
    public void shouldReturnTheLatestAndOldestRunForGivenIdentifier() {
        JobInstances jobInstances = new JobInstances();
        String pipelineName = PIPELINE_NAME + "-" + UUID.randomUUID();
        pipelineConfig = PipelineMother.withSingleStageWithMaterials(pipelineName, STAGE_NAME, BuildPlanMother.withBuildPlans(JOB_NAME));
        Pipeline newPipeline = createNewPipeline(pipelineConfig);
        jobInstances.add(newPipeline.getFirstStage().getFirstJob());
        for (int i = 0; i < 2; i++) {
            stageId = newPipeline.getFirstStage().getId();
            JobInstance scheduled = JobInstanceMother.completed(JOB_NAME);
            jobInstanceDao.save(stageId, scheduled);
            jobInstances.add(scheduled);
        }

        PipelineRunIdInfo runIdInfo = jobInstanceDao.getOldestAndLatestJobInstanceId(pipelineName, STAGE_NAME, JOB_NAME);

        assertThat(runIdInfo.getLatestRunId()).isEqualTo(jobInstances.last().getId());
        assertThat(runIdInfo.getOldestRunId()).isEqualTo(jobInstances.first().getId());
    }

    @Test
    public void findDetailedJobHistoryViaCursor_getLatestRecords() {
        JobInstances jobInstances = new JobInstances();
        String pipelineName = PIPELINE_NAME + "-" + UUID.randomUUID();
        pipelineConfig = PipelineMother.withSingleStageWithMaterials(pipelineName, STAGE_NAME, BuildPlanMother.withBuildPlans(JOB_NAME));
        Pipeline newPipeline = createNewPipeline(pipelineConfig);
        jobInstances.add(newPipeline.getFirstStage().getFirstJob());
        for (int i = 0; i < 4; i++) {
            stageId = newPipeline.getFirstStage().getId();
            JobInstance scheduled = JobInstanceMother.completed(JOB_NAME);
            jobInstanceDao.save(stageId, scheduled);
            jobInstances.add(scheduled);
        }

        JobInstances history = jobInstanceDao.findDetailedJobHistoryViaCursor(pipelineName, STAGE_NAME, JOB_NAME, FeedModifier.Latest, 0, 3);

        Collections.reverse(jobInstances);
        assertThat(history.size()).isEqualTo(3);
        assertThat(history.get(0).getId()).isEqualTo(jobInstances.get(0).getId());
        assertThat(history.get(1).getId()).isEqualTo(jobInstances.get(1).getId());
        assertThat(history.get(2).getId()).isEqualTo(jobInstances.get(2).getId());
    }

    @Test
    public void findDetailedJobHistoryViaCursor_getRecordsAfterTheSpecifiedCursor() {  //older records
        JobInstances jobInstances = new JobInstances();
        String pipelineName = PIPELINE_NAME + "-" + UUID.randomUUID();
        pipelineConfig = PipelineMother.withSingleStageWithMaterials(pipelineName, STAGE_NAME, BuildPlanMother.withBuildPlans(JOB_NAME));
        Pipeline newPipeline = createNewPipeline(pipelineConfig);
        jobInstances.add(newPipeline.getFirstStage().getFirstJob());
        for (int i = 0; i < 4; i++) {
            stageId = newPipeline.getFirstStage().getId();
            JobInstance scheduled = JobInstanceMother.completed(JOB_NAME);
            jobInstanceDao.save(stageId, scheduled);
            jobInstances.add(scheduled);
        }

        JobInstances history = jobInstanceDao.findDetailedJobHistoryViaCursor(pipelineName, STAGE_NAME, JOB_NAME, FeedModifier.After, jobInstances.get(2).getId(), 3);

        Assertions.assertThat(history).hasSize(2);
        Assertions.assertThat(history.stream().map(JobInstance::getId).collect(toList()))
                .containsExactly(jobInstances.get(1).getId(), jobInstances.get(0).getId());
    }

    @Test
    public void findDetailedJobHistoryViaCursor_getRecordsBeforeTheSpecifiedCursor() {  //newer records
        JobInstances jobInstances = new JobInstances();
        String pipelineName = PIPELINE_NAME + "-" + UUID.randomUUID();
        pipelineConfig = PipelineMother.withSingleStageWithMaterials(pipelineName, STAGE_NAME, BuildPlanMother.withBuildPlans(JOB_NAME));
        Pipeline newPipeline = createNewPipeline(pipelineConfig);
        jobInstances.add(newPipeline.getFirstStage().getFirstJob());
        for (int i = 0; i < 4; i++) {
            stageId = newPipeline.getFirstStage().getId();
            JobInstance scheduled = JobInstanceMother.completed(JOB_NAME);
            jobInstanceDao.save(stageId, scheduled);
            jobInstances.add(scheduled);
        }
        Collections.reverse(jobInstances);

        JobInstances history = jobInstanceDao.findDetailedJobHistoryViaCursor(pipelineName, STAGE_NAME, JOB_NAME, FeedModifier.Before, jobInstances.get(2).getId(), 3);

        Assertions.assertThat(history).hasSize(2);
        Assertions.assertThat(history.stream().map(JobInstance::getId).collect(toList()))
                .containsExactly(jobInstances.get(0).getId(), jobInstances.get(1).getId());
    }
}
