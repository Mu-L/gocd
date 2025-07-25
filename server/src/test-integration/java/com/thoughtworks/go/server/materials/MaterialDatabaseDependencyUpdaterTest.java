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
package com.thoughtworks.go.server.materials;

import com.thoughtworks.go.config.CaseInsensitiveString;
import com.thoughtworks.go.config.materials.SubprocessExecutionContext;
import com.thoughtworks.go.config.materials.dependency.DependencyMaterial;
import com.thoughtworks.go.domain.*;
import com.thoughtworks.go.domain.materials.Modification;
import com.thoughtworks.go.server.cache.GoCache;
import com.thoughtworks.go.server.dao.DatabaseAccessHelper;
import com.thoughtworks.go.server.dao.DependencyMaterialSourceDao;
import com.thoughtworks.go.server.persistence.MaterialRepository;
import com.thoughtworks.go.server.service.GoConfigService;
import com.thoughtworks.go.server.service.MaterialExpansionService;
import com.thoughtworks.go.server.service.MaterialService;
import com.thoughtworks.go.server.transaction.TransactionTemplate;
import com.thoughtworks.go.server.util.Pagination;
import com.thoughtworks.go.serverhealth.HealthStateScope;
import com.thoughtworks.go.serverhealth.HealthStateType;
import com.thoughtworks.go.serverhealth.ServerHealthService;
import com.thoughtworks.go.serverhealth.ServerHealthState;
import com.thoughtworks.go.util.ReflectionUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = {
    "classpath:/applicationContext-global.xml",
    "classpath:/applicationContext-dataLocalAccess.xml",
    "classpath:/testPropertyConfigurer.xml",
    "classpath:/spring-all-servlet.xml",
})
public class MaterialDatabaseDependencyUpdaterTest {
    @Autowired
    private DatabaseAccessHelper dbHelper;
    @Autowired
    protected MaterialRepository materialRepository;
    @Autowired
    private GoCache goCache;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private MaterialService materialService;
    @Autowired
    private LegacyMaterialChecker legacyMaterialChecker;
    @Autowired
    private SubprocessExecutionContext subprocessExecutionContext;
    @Autowired
    private MaterialExpansionService materialExpansionService;
    @Autowired
    private GoConfigService goConfigService;

    protected MaterialDatabaseUpdater updater;
    private DependencyMaterialSourceDao dependencyMaterialSourceDao;
    private ServerHealthService healthService;
    private DependencyMaterialUpdater dependencyMaterialUpdater;
    private ScmMaterialUpdater scmMaterialUpdater;

    @BeforeEach
    public void setUp() throws Exception {
        dbHelper.onSetUp();
        goCache.clear();
        dependencyMaterialSourceDao = mock(DependencyMaterialSourceDao.class);
        healthService = mock(ServerHealthService.class);
        dependencyMaterialUpdater = new DependencyMaterialUpdater(dependencyMaterialSourceDao, materialRepository);
        scmMaterialUpdater = new ScmMaterialUpdater(materialRepository, legacyMaterialChecker, subprocessExecutionContext, materialService);
        updater = new MaterialDatabaseUpdater(materialRepository, healthService, transactionTemplate, dependencyMaterialUpdater, scmMaterialUpdater, null, null, materialExpansionService, goConfigService);
    }

    @AfterEach
    public void tearDown() throws Exception {
        dbHelper.onTearDown();
    }

    @Test
    public void shouldCreateEntriesForCompletedPipelines() throws Exception {
        DependencyMaterial dependencyMaterial = new DependencyMaterial(new CaseInsensitiveString("pipeline-name"), new CaseInsensitiveString("stage-name"));

        stubStageServiceGetHistory(stages(9));

        updater.updateMaterial(dependencyMaterial);

        List<Modification> modification = materialRepository.findLatestModification(dependencyMaterial).getMaterialRevision(0).getModifications();

        assertThat(modification.size()).isEqualTo(1);
        assertThat(modification.get(0).getRevision()).isEqualTo("pipeline-name/9/stage-name/0");
        assertThat(modification.get(0).getPipelineLabel()).isEqualTo("LABEL-9");
    }

    @Test
    public void shouldUpdateServerHealthIfCheckFails() {
        DependencyMaterial dependencyMaterial = new DependencyMaterial(new CaseInsensitiveString("pipeline-name"), new CaseInsensitiveString("stage-name"));

        RuntimeException runtimeException = new RuntimeException("Description of error");
        when(dependencyMaterialSourceDao.getPassedStagesByName(new DependencyMaterial(new CaseInsensitiveString("pipeline-name"), new CaseInsensitiveString("stage-name")),
            Pagination.pageStartingAt(0, null, MaterialDatabaseUpdater.STAGES_PER_PAGE)))
            .thenThrow(runtimeException);

        try {
            updater.updateMaterial(dependencyMaterial);
            fail("should have thrown exception " + runtimeException.getMessage());
        } catch (Exception e) {
            assertSame(e, runtimeException);
        }

        HealthStateType scope = HealthStateType.general(HealthStateScope.forMaterial(dependencyMaterial));
        ServerHealthState state = ServerHealthState.errorWithHtml("Modification check failed for material: pipeline-name [ stage-name ]\nNo pipelines affected, may only affect configuration repositories.", "Description of error", scope);
        verify(healthService).update(state);
    }

    @Test
    public void shouldClearServerHealthIfCheckSucceeds() throws Exception {
        DependencyMaterial dependencyMaterial = new DependencyMaterial(new CaseInsensitiveString("pipeline-name"), new CaseInsensitiveString("stage-name"));

        when(dependencyMaterialSourceDao.getPassedStagesByName(new DependencyMaterial(new CaseInsensitiveString("pipeline-name"), new CaseInsensitiveString("stage-name")),
            Pagination.pageStartingAt(0, null, MaterialDatabaseUpdater.STAGES_PER_PAGE)))
            .thenReturn(new ArrayList<>());

        updater.updateMaterial(dependencyMaterial);

        verify(healthService).removeByScope(HealthStateScope.forMaterial(dependencyMaterial));
    }

    @Test
    public void shouldReturnNoNewModificationsIfNoNewPipelineHasBennCompleted() throws Exception {
        DependencyMaterial dependencyMaterial = new DependencyMaterial(new CaseInsensitiveString("pipeline-name"), new CaseInsensitiveString("stage-name"));

        stubStageServiceGetHistory(stages(9));
        updater.updateMaterial(dependencyMaterial);

        List<Modification> modification = materialRepository.findLatestModification(dependencyMaterial).getMaterialRevision(0).getModifications();

        stubStageServiceGetHistoryAfter(dependencyMaterial, 9, stages());

        updater.updateMaterial(dependencyMaterial);

        List<Modification> newModifications = materialRepository.findModificationsSince(dependencyMaterial, new MaterialRevision(dependencyMaterial, modification));

        assertThat(newModifications.size()).isEqualTo(0);
    }

    private void stubStageServiceGetHistoryAfter(DependencyMaterial material, int pipelineCounter, Stages... stageses) {
        if (material == null) {
            material = new DependencyMaterial(new CaseInsensitiveString("pipeline-name"), new CaseInsensitiveString("stage-name"));
        }
        StageIdentifier identifier = new StageIdentifier(String.format("%s/%s/%s/0", material.getPipelineName().toString(), pipelineCounter, material.getStageName().toString()));
        for (int i = 0; i < stageses.length; i++) {
            Stages stages = stageses[i];
            List<Modification> mods = new ArrayList<>();
            for (Stage stage : stages) {
                StageIdentifier id = stage.getIdentifier();
                mods.add(new Modification(stage.completedDate(), id.stageLocator(), id.getPipelineLabel(), stage.getPipelineId()));
            }
            when(dependencyMaterialSourceDao.getPassedStagesAfter(identifier.stageLocator(),
                material,
                Pagination.pageStartingAt(i * MaterialDatabaseUpdater.STAGES_PER_PAGE, null, MaterialDatabaseUpdater.STAGES_PER_PAGE)
            )).thenReturn(mods);
        }
        when(dependencyMaterialSourceDao.getPassedStagesAfter(identifier.stageLocator(),
            material,
            Pagination.pageStartingAt(MaterialDatabaseUpdater.STAGES_PER_PAGE * stageses.length, null, MaterialDatabaseUpdater.STAGES_PER_PAGE)
        )).thenReturn(new ArrayList<>());
    }

    @Test
    public void shouldReturnNoNewModificationsIfPipelineHasNeverBeenScheduled() throws Exception {
        DependencyMaterial dependencyMaterial = new DependencyMaterial(new CaseInsensitiveString("pipeline-name"), new CaseInsensitiveString("stage-name"));

        stubStageServiceGetHistory();
        updater.updateMaterial(dependencyMaterial);

        MaterialRevisions materialRevisions = materialRepository.findLatestModification(dependencyMaterial);

        assertThat(materialRevisions.isEmpty()).isTrue();
    }

    @Test
    public void shouldReturnLatestPipelineIfThereHasBeenANewOne() throws Exception {
        DependencyMaterial dependencyMaterial = new DependencyMaterial(new CaseInsensitiveString("pipeline-name"), new CaseInsensitiveString("stage-name"));

        stubStageServiceGetHistory(stages(9));
        updater.updateMaterial(dependencyMaterial);

        List<Modification> modification = materialRepository.findLatestModification(dependencyMaterial).getMaterialRevision(0).getModifications();

        stubStageServiceGetHistoryAfter(null, 9, stages(10));
        updater.updateMaterial(dependencyMaterial);

        List<Modification> newModifications = materialRepository.findModificationsSince(dependencyMaterial, new MaterialRevision(dependencyMaterial, modification));

        assertThat(newModifications.size()).isEqualTo(1);
        assertThat(newModifications.get(0).getRevision()).isEqualTo("pipeline-name/10/stage-name/0");
        assertThat(newModifications.get(0).getPipelineLabel()).isEqualTo("LABEL-10");
    }

    @Test
    public void shouldInsertAllHistoricRunsOfUpstreamStageTheFirstTime() throws Exception {
        DependencyMaterial dependencyMaterial = new DependencyMaterial(new CaseInsensitiveString("pipeline-name"), new CaseInsensitiveString("stage-name"));

        stubStageServiceGetHistory(stages(9, 10, 11), stages(12, 13));

        updater.updateMaterial(dependencyMaterial);

        for (Integer revision : new int[]{9, 10, 11, 12, 13}) {
            String stageLocator = String.format("pipeline-name/%s/stage-name/0", revision);
            Modification modification = materialRepository.findModificationWithRevision(dependencyMaterial, stageLocator);
            assertThat(modification.getRevision()).isEqualTo(stageLocator);
            assertThat(modification.getPipelineLabel()).isEqualTo(String.format("LABEL-%s", revision));
        }
    }

    @Test
    public void shouldUpdateMaterialCorrectlyIfCaseOfPipelineNameIsDifferentInConfigurationOfDependencyMaterial() throws Exception {

        DependencyMaterial dependencyMaterial = new DependencyMaterial(new CaseInsensitiveString("PIPEline-name"), new CaseInsensitiveString("STAge-name"));
        stubStageServiceGetHistory(stages(1));

        // create the material instance
        updater.updateMaterial(dependencyMaterial);

        stubStageServiceGetHistoryAfter(dependencyMaterial, 1, stages(2));

        // update first time & should mark cache as updated
        updater.updateMaterial(dependencyMaterial);

        Stage stage = stage(3);
        ReflectionUtil.setField(stage, "result", StageResult.Passed);

        // update subsequently should hit database
        updater.updateMaterial(dependencyMaterial);

        verify(dependencyMaterialSourceDao, times(2)).getPassedStagesAfter(any(String.class), any(DependencyMaterial.class), any(Pagination.class));
        verify(dependencyMaterialSourceDao, times(2)).getPassedStagesByName(any(DependencyMaterial.class), any(Pagination.class));
    }

    private Stages stages(int... pipelineCounters) {
        Stages stages = new Stages();
        for (int counter : pipelineCounters) {
            stages.add(stage(counter));
        }
        return stages;
    }

    private Stage stage(int pipelineCounter) {
        Stage stage = new Stage();
        stage.setIdentifier(new StageIdentifier("pipeline-name", pipelineCounter, "LABEL-" + pipelineCounter, "stage-name", "0"));
        return stage;
    }

    private void stubStageServiceGetHistory(Stages... stageses) {
        DependencyMaterial dependencyMaterial = new DependencyMaterial(new CaseInsensitiveString("pipeline-name"), new CaseInsensitiveString("stage-name"));
        for (int i = 0; i < stageses.length; i++) {
            List<Modification> mods = new ArrayList<>();
            for (Stage stage : stageses[i]) {
                StageIdentifier id = stage.getIdentifier();
                mods.add(new Modification(stage.completedDate(), id.stageLocator(), id.getPipelineLabel(), stage.getPipelineId()));
            }
            when(dependencyMaterialSourceDao.getPassedStagesByName(dependencyMaterial,
                Pagination.pageStartingAt(i * MaterialDatabaseUpdater.STAGES_PER_PAGE, null, MaterialDatabaseUpdater.STAGES_PER_PAGE)))
                .thenReturn(mods);
        }
        when(dependencyMaterialSourceDao.getPassedStagesByName(dependencyMaterial,
            Pagination.pageStartingAt(MaterialDatabaseUpdater.STAGES_PER_PAGE * stageses.length, null, MaterialDatabaseUpdater.STAGES_PER_PAGE)
        )).thenReturn(new ArrayList<>());
    }
}
