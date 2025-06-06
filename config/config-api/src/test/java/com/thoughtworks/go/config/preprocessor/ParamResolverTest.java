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
package com.thoughtworks.go.config.preprocessor;

import com.thoughtworks.go.config.*;
import com.thoughtworks.go.config.materials.AbstractMaterialConfig;
import com.thoughtworks.go.config.materials.ScmMaterialConfig;
import com.thoughtworks.go.config.materials.mercurial.HgMaterialConfig;
import com.thoughtworks.go.config.materials.perforce.P4MaterialConfig;
import com.thoughtworks.go.config.materials.perforce.P4MaterialViewConfig;
import com.thoughtworks.go.config.materials.svn.SvnMaterialConfig;
import com.thoughtworks.go.config.merge.MergePipelineConfigs;
import com.thoughtworks.go.helper.MaterialConfigsMother;
import com.thoughtworks.go.helper.PipelineConfigMother;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.thoughtworks.go.helper.MaterialConfigsMother.p4;
import static com.thoughtworks.go.util.ReflectionUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;

public class ParamResolverTest {

    private ClassAttributeCache.FieldCache fieldCache;

    @BeforeEach
    public void setUp() {
        fieldCache = new ClassAttributeCache.FieldCache();
    }

    @Test
    public void shouldResolve_ConfigValue_MappedAsObject() {
        SecurityConfig securityConfig = new SecurityConfig();
        securityConfig.adminsConfig().add(new AdminUser(new CaseInsensitiveString("lo#{foo}")));
        securityConfig.addRole(new RoleConfig(new CaseInsensitiveString("boo#{bar}"), new RoleUser(new CaseInsensitiveString("choo#{foo}"))));
        new ParamResolver(new ParamSubstitutionHandlerFactory(params(param("foo", "ser"), param("bar", "zer"))), fieldCache).resolve(securityConfig);
        assertThat(CaseInsensitiveString.str(securityConfig.adminsConfig().get(0).getName())).isEqualTo("loser");
        assertThat(CaseInsensitiveString.str(securityConfig.getRoles().get(0).getName())).isEqualTo("boozer");
        assertThat(CaseInsensitiveString.str(securityConfig.getRoles().get(0).getUsers().get(0).getName())).isEqualTo("chooser");
    }

    @Test
    public void shouldResolveTopLevelAttribute() {
        PipelineConfig pipelineConfig = PipelineConfigMother.createPipelineConfig("cruise", "dev", "ant");
        pipelineConfig.setLabelTemplate("2.1-${COUNT}");
        setField(pipelineConfig, PipelineConfig.LOCK_BEHAVIOR, "#{partial}Finished");
        new ParamResolver(new ParamSubstitutionHandlerFactory(params(param("foo", "pavan"), param("partial", "unlockWhen"), param("COUNT", "quux"))), fieldCache).resolve(pipelineConfig);
        assertThat(pipelineConfig.getLabelTemplate()).isEqualTo("2.1-${COUNT}");
        assertThat(pipelineConfig.explicitLock()).isTrue();
    }

    @Test
    public void shouldNotTryToResolveNonStringAttributes() {//this tests replacement doesn't fail when non-string config-attributes are present, and non opt-out annotated
        MailHost mailHost = new MailHost("host", 25, "loser", "passwd", true, false, "boozer@loser.com", "root@loser.com");
        new ParamResolver(new ParamSubstitutionHandlerFactory(params(param("foo", "pavan"), param("bool", "tr"))), fieldCache).resolve(mailHost);
    }

    @Test
    public void shouldNotResolveOptedOutConfigAttributes() throws NoSuchFieldException {
        PipelineConfig pipelineConfig = PipelineConfigMother.createPipelineConfig("cruise-#{foo}-#{bar}", "dev", "ant");
        SvnMaterialConfig svn = (SvnMaterialConfig) pipelineConfig.materialConfigs().get(0);
        svn.setPassword("#quux-#{foo}-#{bar}");
        pipelineConfig.setLabelTemplate("2.1-${COUNT}-#{foo}-bar-#{bar}");
        new ParamResolver(new ParamSubstitutionHandlerFactory(params(param("foo", "pavan"), param("bar", "jj"))), fieldCache).resolve(pipelineConfig);
        assertThat(pipelineConfig.getLabelTemplate()).isEqualTo("2.1-${COUNT}-pavan-bar-jj");
        assertThat(pipelineConfig.name()).isEqualTo(new CaseInsensitiveString("cruise-#{foo}-#{bar}"));
        assertThat(((SvnMaterialConfig) pipelineConfig.materialConfigs().get(0)).getPassword()).isEqualTo("#quux-#{foo}-#{bar}");
        assertThat(pipelineConfig.getClass().getDeclaredField("name").getAnnotation(SkipParameterResolution.class)).isInstanceOf(SkipParameterResolution.class);
    }

    @Test
    public void shouldNotResolveOptedOutConfigSubtags() throws NoSuchFieldException {
        PipelineConfig pipelineConfig = PipelineConfigMother.createPipelineConfig("cruise", "dev", "ant");
        pipelineConfig.setLabelTemplate("2.1-${COUNT}-#{foo}-bar-#{bar}");
        pipelineConfig.addParam(param("#{foo}-name", "#{foo}-#{bar}-baz"));
        new ParamResolver(new ParamSubstitutionHandlerFactory(params(param("foo", "pavan"), param("bar", "jj"))), fieldCache).resolve(pipelineConfig);
        assertThat(pipelineConfig.getLabelTemplate()).isEqualTo("2.1-${COUNT}-pavan-bar-jj");
        assertThat(pipelineConfig.getParams().get(0)).isEqualTo(param("#{foo}-name", "#{foo}-#{bar}-baz"));
        assertThat(pipelineConfig.getClass().getDeclaredField("params").getAnnotation(SkipParameterResolution.class)).isInstanceOf(SkipParameterResolution.class);
    }

    @Test
    public void shouldNotInterpolateEscapedSequences() {
        PipelineConfig pipelineConfig = PipelineConfigMother.createPipelineConfig("cruise", "dev", "ant");
        pipelineConfig.setLabelTemplate("2.1-${COUNT}-##{foo}-bar-#{bar}");
        new ParamResolver(new ParamSubstitutionHandlerFactory(params(param("foo", "pavan"), param("bar", "jj"))), fieldCache).resolve(pipelineConfig);
        assertThat(pipelineConfig.getLabelTemplate()).isEqualTo("2.1-${COUNT}-#{foo}-bar-jj");
    }

    @Test
    public void shouldInterpolateLiteralEscapedSequences() {
        PipelineConfig pipelineConfig = PipelineConfigMother.createPipelineConfig("cruise", "dev", "ant");
        pipelineConfig.setLabelTemplate("2.1-${COUNT}-###{foo}-bar-#{bar}");
        new ParamResolver(new ParamSubstitutionHandlerFactory(params(param("foo", "pavan"), param("bar", "jj"))), fieldCache).resolve(pipelineConfig);
        assertThat(pipelineConfig.getLabelTemplate()).isEqualTo("2.1-${COUNT}-#pavan-bar-jj");
    }

    @Test
    public void shouldEscapeEscapedPatternStartSequences() {
        PipelineConfig pipelineConfig = PipelineConfigMother.createPipelineConfig("cruise", "dev", "ant");
        pipelineConfig.setLabelTemplate("2.1-${COUNT}-#######{foo}-bar-####{bar}");
        new ParamResolver(new ParamSubstitutionHandlerFactory(params(param("foo", "pavan"), param("bar", "jj"))), fieldCache).resolve(pipelineConfig);
        assertThat(pipelineConfig.getLabelTemplate()).isEqualTo("2.1-${COUNT}-###pavan-bar-##{bar}");
    }

    @Test
    public void shouldNotRecursivelySubstituteParams() {
        PipelineConfig pipelineConfig = PipelineConfigMother.createPipelineConfig("cruise", "dev", "ant");

        pipelineConfig.setLabelTemplate("#{foo}");

        new ParamResolver(new ParamSubstitutionHandlerFactory(params(param("foo", "#{bar}"), param("bar", "baz"))), fieldCache).resolve(pipelineConfig);
        assertThat(pipelineConfig.getLabelTemplate()).isEqualTo("#{bar}");

        pipelineConfig.setLabelTemplate("#{foo}");

        new ParamResolver(new ParamSubstitutionHandlerFactory(params(param("foo", "###"))), fieldCache).resolve(pipelineConfig);
        assertThat(pipelineConfig.getLabelTemplate()).isEqualTo("###");
    }

    @Test
    public void shouldResolveConfigValue() {
        PipelineConfig pipelineConfig = PipelineConfigMother.createPipelineConfig("cruise", "dev", "ant");
        pipelineConfig.setLabelTemplate("2.1-${COUNT}-#{foo}-bar-#{bar}");
        StageConfig stageConfig = pipelineConfig.get(0);
        stageConfig.updateApproval(new Approval(new AuthConfig(new AdminUser(new CaseInsensitiveString("#{foo}")), new AdminUser(new CaseInsensitiveString("#{bar}")))));

        new ParamResolver(new ParamSubstitutionHandlerFactory(params(param("foo", "pavan"), param("bar", "jj"))), fieldCache).resolve(pipelineConfig);

        assertThat(pipelineConfig.getLabelTemplate()).isEqualTo("2.1-${COUNT}-pavan-bar-jj");
        assertThat(stageConfig.getApproval().getAuthConfig()).isEqualTo(new AuthConfig(new AdminUser(new CaseInsensitiveString("pavan")), new AdminUser(new CaseInsensitiveString("jj"))));
    }

    @Test
    public void shouldResolveSubTags() {
        PipelineConfig pipelineConfig = PipelineConfigMother.createPipelineConfig("cruise", "dev", "ant");
        pipelineConfig.setLabelTemplate("2.1-${COUNT}-#{foo}-bar-#{bar}");
        TrackingTool trackingTool = new TrackingTool("http://#{foo}.com/#{bar}", "\\w+#{bar}");
        pipelineConfig.setTrackingTool(trackingTool);

        new ParamResolver(new ParamSubstitutionHandlerFactory(params(param("foo", "pavan"), param("bar", "jj"))), fieldCache).resolve(pipelineConfig);

        assertThat(pipelineConfig.getLabelTemplate()).isEqualTo("2.1-${COUNT}-pavan-bar-jj");
        assertThat(trackingTool.getLink()).isEqualTo("http://pavan.com/jj");
        assertThat(trackingTool.getRegex()).isEqualTo("\\w+jj");
    }

    @Test
    public void shouldResolveCollections() {
        PipelineConfig pipelineConfig = PipelineConfigMother.createPipelineConfig("cruise", "dev", "ant");
        pipelineConfig.setLabelTemplate("2.1-${COUNT}-#{foo}-bar-#{bar}");
        HgMaterialConfig materialConfig = MaterialConfigsMother.hgMaterialConfig("http://#{foo}.com/#{bar}");
        pipelineConfig.addMaterialConfig(materialConfig);

        new ParamResolver(new ParamSubstitutionHandlerFactory(params(param("foo", "pavan"), param("bar", "jj"))), fieldCache).resolve(pipelineConfig);

        assertThat(pipelineConfig.getLabelTemplate()).isEqualTo("2.1-${COUNT}-pavan-bar-jj");
        assertThat(pipelineConfig.materialConfigs().get(1).getUriForDisplay()).isEqualTo("http://pavan.com/jj");
    }

    @Test
    public void shouldResolveInBasicPipelineConfigs() {
        PipelineConfig pipelineConfig = PipelineConfigMother.createPipelineConfig("cruise", "dev", "ant");
        pipelineConfig.setLabelTemplate("2.1-${COUNT}-#{foo}-bar-#{bar}");
        HgMaterialConfig materialConfig = MaterialConfigsMother.hgMaterialConfig("http://#{foo}.com/#{bar}");
        pipelineConfig.addMaterialConfig(materialConfig);
        BasicPipelineConfigs pipelines = new BasicPipelineConfigs(pipelineConfig);

        new ParamResolver(new ParamSubstitutionHandlerFactory(params(param("foo", "pavan"), param("bar", "jj"))), fieldCache).resolve(pipelines);

        assertThat(pipelineConfig.getLabelTemplate()).isEqualTo("2.1-${COUNT}-pavan-bar-jj");
        assertThat(pipelineConfig.materialConfigs().get(1).getUriForDisplay()).isEqualTo("http://pavan.com/jj");
    }

    @Test
    public void shouldResolveInMergePipelineConfigs() {
        PipelineConfig pipelineConfig = PipelineConfigMother.createPipelineConfig("cruise", "dev", "ant");
        pipelineConfig.setLabelTemplate("2.1-${COUNT}-#{foo}-bar-#{bar}");
        HgMaterialConfig materialConfig = MaterialConfigsMother.hgMaterialConfig("http://#{foo}.com/#{bar}");
        pipelineConfig.addMaterialConfig(materialConfig);
        MergePipelineConfigs merge = new MergePipelineConfigs(new BasicPipelineConfigs(), new BasicPipelineConfigs(pipelineConfig));

        new ParamResolver(new ParamSubstitutionHandlerFactory(params(param("foo", "pavan"), param("bar", "jj"))), fieldCache).resolve(merge);

        assertThat(pipelineConfig.getLabelTemplate()).isEqualTo("2.1-${COUNT}-pavan-bar-jj");
        assertThat(pipelineConfig.materialConfigs().get(1).getUriForDisplay()).isEqualTo("http://pavan.com/jj");
    }

    @Test
    public void shouldProvideContextWhenAnExceptionOccurs() {
        PipelineConfig pipelineConfig = PipelineConfigMother.createPipelineConfig("cruise", "dev", "ant");
        pipelineConfig.setLabelTemplate("#a");
        new ParamResolver(new ParamSubstitutionHandlerFactory(params(param("foo", "pavan"), param("bar", "jj"))), fieldCache).resolve(pipelineConfig);
        assertThat(pipelineConfig.errors().on("labelTemplate")).isEqualTo("Error when processing params for '#a' used in field 'labelTemplate', # must be followed by a parameter pattern or escaped by another #");
    }

    @Test
    public void shouldUseValidationErrorKeyAnnotationForFieldNameInCaseOfException() {
        PipelineConfig pipelineConfig = PipelineConfigMother.createPipelineConfig("cruise", "dev", "ant", "nant");
        FetchTask task = new FetchTask(new CaseInsensitiveString("cruise"), new CaseInsensitiveString("dev"), new CaseInsensitiveString("ant"), "#a", "dest");
        pipelineConfig.get(0).getJobs().getJob(new CaseInsensitiveString("nant")).addTask(task);
        new ParamResolver(new ParamSubstitutionHandlerFactory(params(param("foo", "pavan"), param("bar", "jj"))), fieldCache).resolve(pipelineConfig);
        assertThat(task.errors().isEmpty()).isFalse();
        assertThat(task.errors().on(FetchTask.SRC)).isEqualTo("Error when processing params for '#a' used in field 'src', # must be followed by a parameter pattern or escaped by another #");
    }


    @Test
    public void shouldAddErrorTheMessageOnTheRightFieldOfTheRightElement() {
        ResourceConfig resourceConfig = new ResourceConfig();
        resourceConfig.setName("#{not-found}");

        PipelineConfig pipelineConfig = PipelineConfigMother.createPipelineConfig("cruise", "dev", "ant");
        pipelineConfig.setLabelTemplate("#a");
        pipelineConfig.get(0).getJobs().addJobWithoutValidityAssertion(new JobConfig(new CaseInsensitiveString("another"), new ResourceConfigs(resourceConfig), new ArtifactTypeConfigs()));

        new ParamResolver(new ParamSubstitutionHandlerFactory(params(param("foo", "pavan"), param("bar", "jj"))), fieldCache).resolve(pipelineConfig);

        assertThat(pipelineConfig.errors().on("labelTemplate")).isEqualTo("Error when processing params for '#a' used in field 'labelTemplate', # must be followed by a parameter pattern or escaped by another #");
        assertThat(resourceConfig.errors().on(JobConfig.RESOURCES)).isEqualTo("Parameter 'not-found' is not defined. All pipelines using this parameter directly or via a template must define it.");
    }

    @Test
    public void shouldProvideContextWhenAnExceptionOccursBecauseOfHashAtEnd() {
        PipelineConfig pipelineConfig = PipelineConfigMother.createPipelineConfig("cruise", "dev", "ant");
        pipelineConfig.setLabelTemplate("abc#");
        new ParamResolver(new ParamSubstitutionHandlerFactory(params(param("foo", "pavan"), param("bar", "jj"))), fieldCache).resolve(pipelineConfig);
        assertThat(pipelineConfig.errors().on("labelTemplate")).isEqualTo("Error when processing params for 'abc#' used in field 'labelTemplate', # must be followed by a parameter pattern or escaped by another #");
    }

    @Test
    public void shouldProvideContextWhenAnExceptionOccursBecauseOfIncompleteParamAtEnd() {
        PipelineConfig pipelineConfig = PipelineConfigMother.createPipelineConfig("cruise", "dev", "ant");
        pipelineConfig.setLabelTemplate("abc#{");
        new ParamResolver(new ParamSubstitutionHandlerFactory(params(param("foo", "pavan"), param("bar", "jj"))), fieldCache).resolve(pipelineConfig);
        assertThat(pipelineConfig.errors().on("labelTemplate")).isEqualTo("Incomplete param usage in 'abc#{'");
    }

    @Test
    public void shouldResolveInheritedAttributes() {
        PipelineConfig pipelineConfig = PipelineConfigMother.createPipelineConfig("cruise", "dev", "ant");
        HgMaterialConfig materialConfig = MaterialConfigsMother.hgMaterialConfig();
        materialConfig.setConfigAttributes(Map.of(ScmMaterialConfig.FOLDER, "work/#{foo}/#{bar}/baz"));
        pipelineConfig.addMaterialConfig(materialConfig);

        new ParamResolver(new ParamSubstitutionHandlerFactory(params(param("foo", "pavan"), param("bar", "jj"))), fieldCache).resolve(pipelineConfig);

        assertThat(pipelineConfig.materialConfigs().get(1).getFolder()).isEqualTo("work/pavan/jj/baz");
    }

    @Test
    public void shouldAddResolutionErrorOnViewIfP4MaterialViewHasAnError() {
        P4MaterialViewConfig p4MaterialViewConfig = new P4MaterialViewConfig("#");

        new ParamResolver(new ParamSubstitutionHandlerFactory(params(param("foo", "pavan"), param("bar", "jj"))), fieldCache).resolve(p4MaterialViewConfig);

        assertThat(p4MaterialViewConfig.errors().on(P4MaterialConfig.VIEW)).isEqualTo("Error when processing params for '#' used in field 'view', # must be followed by a parameter pattern or escaped by another #");
    }

    @Test
    public void shouldErrorOutIfCannotResolveParamForP4View() {
        P4MaterialConfig p4MaterialConfig = p4("server:port", "#");
        new ParamResolver(new ParamSubstitutionHandlerFactory(params(param("foo", "pavan"), param("bar", "jj"))), fieldCache).resolve(p4MaterialConfig);
        assertThat(p4MaterialConfig.getP4MaterialView().errors().on(P4MaterialConfig.VIEW)).isEqualTo("Error when processing params for '#' used in field 'view', # must be followed by a parameter pattern or escaped by another #");
    }

    @Test
    public void shouldLexicallyScopeTheParameters() {
        PipelineConfig withParams = PipelineConfigMother.createPipelineConfig("cruise", "dev", "ant");
        withParams.addParam(param("foo", "pipeline"));

        PipelineConfig withoutParams = PipelineConfigMother.createPipelineConfig("mingle", "dev", "ant");

        CruiseConfig cruiseConfig = new BasicCruiseConfig();
        cruiseConfig.addPipeline("group", withParams);
        cruiseConfig.addPipeline("group", withoutParams);
        cruiseConfig.server().setArtifactsDir("/#{foo}/#{bar}");

        HgMaterialConfig materialConfig = MaterialConfigsMother.hgMaterialConfig();
        materialConfig.setConfigAttributes(Map.of(ScmMaterialConfig.FOLDER, "work/#{foo}/#{bar}/baz"));
        withParams.addMaterialConfig(materialConfig);

        withParams.setLabelTemplate("2.0.#{foo}-#{bar}");
        withoutParams.setLabelTemplate("2.0.#{foo}-#{bar}");

        new ParamResolver(new ParamSubstitutionHandlerFactory(params(param("foo", "global"), param("bar", "global-only"))), fieldCache).resolve(cruiseConfig);

        assertThat(withParams.materialConfigs().get(1).getFolder()).isEqualTo("work/pipeline/global-only/baz");
        assertThat(withParams.getLabelTemplate()).isEqualTo("2.0.pipeline-global-only");
        assertThat(withoutParams.getLabelTemplate()).isEqualTo("2.0.global-global-only");
    }

    @Test
    public void shouldSkipResolution() throws NoSuchFieldException {
        Object[] specs = new Object[]{
                BasicCruiseConfig.class, "serverConfig",
                BasicCruiseConfig.class, "templatesConfig",
                BasicCruiseConfig.class, "environments",
                BasicPipelineConfigs.class, "authorization",
                PipelineConfig.class, "name",
                PipelineConfig.class, "params",
                PipelineConfig.class, "templateName",
                StageConfig.class, "name",
                AbstractMaterialConfig.class, "name",
                Approval.class, "type",
                JobConfig.class, "jobName",
                RunIfConfig.class, "status",
        };
        for (int i = 0; i < specs.length; i += 2) {
            Class<?> clz = (Class<?>) specs[i];
            String field = (String) specs[i + 1];
            assertSkipsResolution(clz, field);
        }
    }

    private void assertSkipsResolution(Class<?> clz, String fieldName) throws NoSuchFieldException {
        assertThat(clz.getDeclaredField(fieldName).getAnnotation(SkipParameterResolution.class))
            .describedAs(String.format("Field %s on class %s does not skip param resolution", clz.getName(), fieldName))
            .isNotNull();
    }

    private ParamConfig param(String name, String value) {
        return new ParamConfig(name, value);
    }

    private ParamsConfig params(ParamConfig... configs) {
        ParamsConfig paramsConfig = new ParamsConfig();
        paramsConfig.addAll(List.of(configs));
        return paramsConfig;
    }

}
