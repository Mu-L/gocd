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
package com.thoughtworks.go.config;

import com.thoughtworks.go.config.elastic.ClusterProfile;
import com.thoughtworks.go.config.elastic.ClusterProfiles;
import com.thoughtworks.go.config.elastic.ElasticProfile;
import com.thoughtworks.go.config.elastic.ElasticProfiles;
import com.thoughtworks.go.config.materials.dependency.DependencyMaterialConfig;
import com.thoughtworks.go.config.materials.mercurial.HgMaterialConfig;
import com.thoughtworks.go.config.pluggabletask.PluggableTask;
import com.thoughtworks.go.config.registry.ConfigElementImplementationRegistry;
import com.thoughtworks.go.config.validation.GoConfigValidity;
import com.thoughtworks.go.domain.GoConfigRevision;
import com.thoughtworks.go.domain.Task;
import com.thoughtworks.go.domain.config.*;
import com.thoughtworks.go.domain.materials.MaterialConfig;
import com.thoughtworks.go.domain.packagerepository.ConfigurationPropertyMother;
import com.thoughtworks.go.domain.packagerepository.PackageRepositories;
import com.thoughtworks.go.helper.ConfigFileFixture;
import com.thoughtworks.go.security.ResetCipher;
import com.thoughtworks.go.server.dao.DatabaseAccessHelper;
import com.thoughtworks.go.server.persistence.AgentDao;
import com.thoughtworks.go.server.service.GoConfigService;
import com.thoughtworks.go.serverhealth.*;
import com.thoughtworks.go.service.ConfigRepository;
import com.thoughtworks.go.util.ConfigElementImplementationRegistryMother;
import com.thoughtworks.go.util.GoConfigFileHelper;
import com.thoughtworks.go.util.SystemEnvironment;
import com.thoughtworks.go.util.TimeProvider;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.assertj.core.api.Assertions;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.hibernate.Cache;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.xmlunit.assertj.XmlAssert;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.thoughtworks.go.domain.config.CaseInsensitiveStringMother.str;
import static com.thoughtworks.go.domain.packagerepository.ConfigurationPropertyMother.create;
import static com.thoughtworks.go.util.GoConstants.CONFIG_SCHEMA_VERSION;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@ExtendWith(ResetCipher.class)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = {
        "classpath:/applicationContext-global.xml",
        "classpath:/applicationContext-dataLocalAccess.xml",
        "classpath:/testPropertyConfigurer.xml"
})
public class GoConfigMigratorIntegrationTest {
    private Path configFile;
    ConfigRepository configRepository;
    @Autowired
    private AgentDao agentDao;
    @Autowired
    private SystemEnvironment systemEnvironment;
    @Autowired
    private GoConfigService goConfigService;
    @Autowired
    private ServerHealthService serverHealthService;
    private GoConfigMigrator goConfigMigrator;
    @Autowired
    private GoConfigMigration goConfigMigration;
    @Autowired
    private FullConfigSaveNormalFlow fullConfigSaveNormalFlow;
    @Autowired
    private ConfigCache configCache;
    @Autowired
    private ConfigElementImplementationRegistry registry;
    @Autowired
    private GoFileConfigDataSource goFileConfigDataSource;
    @Autowired
    private SessionFactory sessionFactory;
    @Autowired
    private DatabaseAccessHelper dbHelper;
    private List<Exception> exceptions;

    @BeforeEach
    public void setUp(@TempDir File temporaryFolder, ResetCipher resetCipher) throws Exception {
        dbHelper.onSetUp();
        configFile = new File(temporaryFolder, "cruise-config.xml").toPath();
        new SystemEnvironment().setProperty(SystemEnvironment.CONFIG_FILE_PROPERTY, configFile.toAbsolutePath().toString());
        GoConfigFileHelper.clearConfigVersions();
        configRepository = new ConfigRepository(systemEnvironment);
        configRepository.initialize();
        serverHealthService.removeAllLogs();
        resetCipher.setupDESCipherFile();
        resetCipher.setupAESCipherFile();
        exceptions = new ArrayList<>();
        MagicalGoConfigXmlLoader xmlLoader = new MagicalGoConfigXmlLoader(configCache, registry);
        goConfigMigrator = new GoConfigMigrator(goConfigMigration, systemEnvironment, fullConfigSaveNormalFlow, xmlLoader, new GoConfigFileReader(systemEnvironment), configRepository, serverHealthService, e -> exceptions.add(e));
    }

    @AfterEach
    public void tearDown() throws Exception {
        dbHelper.onTearDown();
        GoConfigFileHelper.clearConfigVersions();
        Files.deleteIfExists(configFile);
        serverHealthService.removeAllLogs();
    }

    @Test
    public void shouldNotUpgradeCruiseConfigFileUponServerStartupIfSchemaVersionMatches() throws Exception {

        String config = ConfigFileFixture.SERVER_WITH_ARTIFACTS_DIR;
        Files.writeString(configFile, config, UTF_8);
        // To create a version of this config in config.git since there wouldn't be any commit
        // in config.git at this point
        goFileConfigDataSource.forceLoad(configFile);

        CruiseConfig cruiseConfig = loadConfigFileWithContent(config);
        assertThat(cruiseConfig.schemaVersion()).isEqualTo(CONFIG_SCHEMA_VERSION);
        assertThat(configRepository.getRevision(ConfigRepository.CURRENT).getUsername()).isNotEqualTo("Upgrade");
    }

    @Test
    public void shouldValidateCruiseConfigFileIrrespectiveOfUpgrade() {
        String configString = ConfigFileFixture.configWithEnvironments("""
                <environments>
                  <environment name='foo'>
                <pipelines>
                 <pipeline name='does_not_exist'/>
                </pipelines>
                </environment>
                </environments>""", CONFIG_SCHEMA_VERSION);
        try {
            loadConfigFileWithContent(configString);
            fail("Should not upgrade invalid config file");
        } catch (Exception e) {
            assertThat(e.getMessage()).contains("Environment 'foo' refers to an unknown pipeline 'does_not_exist'");
        }
    }

    @Test
    public void shouldUpgradeCruiseConfigFileIfVersionDoesNotMatch() throws Exception {
        CruiseConfig cruiseConfig = loadConfigFileWithContent(ConfigFileFixture.OLD);
        assertThat(cruiseConfig.schemaVersion()).isEqualTo(CONFIG_SCHEMA_VERSION);
    }

    @Test
    public void shouldNotUpgradeInvalidConfigFileWhenThereIsNoValidConfigVersioned() throws GitAPIException, IOException {
        Assertions.assertThat(configRepository.getRevision(ConfigRepository.CURRENT)).isNull();
        Files.writeString(configFile, "<cruise></cruise>", UTF_8);
        goConfigMigrator.migrate();
        assertThat(exceptions.size()).isEqualTo(1);
        assertThat(exceptions.get(0).getMessage()).contains("Cruise config file with version 0 is invalid. Unable to upgrade.");
    }

    @Test
    public void shouldRevertConfigToTheLatestValidConfigVersionFromGitIfCurrentConfigIsInvalid() {
        try {
            loadConfigFileWithContent(ConfigFileFixture.MINIMAL);
            loadConfigFileWithContent("<cruise></cruise>");
            ServerHealthStates states = serverHealthService.logsSorted();
            assertThat(states.size()).isEqualTo(1);
            assertThat(states.get(0).getDescription()).contains("Go encountered an invalid configuration file while starting up. The invalid configuration file has been renamed to &lsquo;");
            assertThat(states.get(0).getDescription()).contains("&rsquo; and a new configuration file has been automatically created using the last good configuration.");
            assertThat(states.get(0).getMessage()).contains("Invalid Configuration");
            assertThat(states.get(0).getType()).isEqualTo(HealthStateType.general(HealthStateScope.forInvalidConfig()));
            assertThat(states.get(0).getLogLevel()).isEqualTo(HealthStateLevel.WARNING);
        } catch (Exception e) {
            fail("Should not Throw an exception, should revert to the last valid file versioned in config.git");
        }
    }

    @Test
    public void shouldTryToRevertConfigToTheLatestValidConfigVersionOnlyOnce() throws Exception {
        configRepository.checkin(new GoConfigRevision("<cruise></cruise>", "md5", "ps", "123", new TimeProvider()));
        Files.writeString(configFile, "<cruise></cruise>", UTF_8);
        goConfigMigrator.migrate();
        assertThat(exceptions.get(0).getMessage()).contains("Cruise config file with version 0 is invalid. Unable to upgrade.");
    }

    @Test
    public void shouldMoveApprovalFromAPreviousStageToTheBeginningOfASecondStage() throws Exception {
        CruiseConfig cruiseConfig = loadConfigFileWithContent(ConfigFileFixture.VERSION_0);

        PipelineConfig pipelineConfig = cruiseConfig.pipelineConfigByName(new CaseInsensitiveString("pipeline"));
        StageConfig firstStage = pipelineConfig.get(0);
        StageConfig secondStage = pipelineConfig.get(1);
        assertThat(firstStage.requiresApproval()).isEqualTo(Boolean.FALSE);
        assertThat(secondStage.requiresApproval()).isEqualTo(Boolean.TRUE);
    }

    @Test
    public void shouldMigrateApprovalsCorrectlyBug2112() throws Exception {
        Path bjcruise = Path.of("../common/src/test/resources/data/bjcruise-cruise-config-1.0.xml");
        assertThat(bjcruise).exists();
        String xml = Files.readString(bjcruise, StandardCharsets.UTF_8);

        CruiseConfig cruiseConfig = loadConfigFileWithContent(xml);

        PipelineConfig pipeline = cruiseConfig.pipelineConfigByName(new CaseInsensitiveString("evolve"));

        StageConfig dbStage = pipeline.findBy(new CaseInsensitiveString("db"));
        assertThat(dbStage.requiresApproval()).isFalse();

        StageConfig installStage = pipeline.findBy(new CaseInsensitiveString("install"));
        assertThat(installStage.requiresApproval()).isTrue();
    }

    @Test
    public void shouldMigrateMaterialFolderAttributeToDest() throws Exception {
        CruiseConfig cruiseConfig = loadConfigFileWithContent(ConfigFileFixture.VERSION_2);
        MaterialConfig actual = cruiseConfig.pipelineConfigByName(new CaseInsensitiveString("multiple")).materialConfigs().first();
        assertThat(actual.getFolder()).isEqualTo("part1");
    }

    @Test
    public void shouldMigrateRevision5ToTheLatest() throws Exception {
        CruiseConfig cruiseConfig = loadConfigFileWithContent(ConfigFileFixture.VERSION_5);
        assertThat(cruiseConfig.schemaVersion()).isEqualTo(CONFIG_SCHEMA_VERSION);
    }

    @Test
    public void shouldMigrateRevision7To8() throws Exception {
        CruiseConfig cruiseConfig = loadConfigFileWithContent(ConfigFileFixture.VERSION_7);
        HgMaterialConfig hgConfig = (HgMaterialConfig) cruiseConfig.pipelineConfigByName(new CaseInsensitiveString("framework")).materialConfigs().first();
        assertThat(hgConfig.getFolder()).isNull();
        assertThat(hgConfig.filter()).isNotNull();
    }

    @Test
    public void shouldMigrateDependsOnTagToBeADependencyMaterial() throws Exception {
        String content = Files.readString(
                Path.of("../common/src/test/resources/data/config/version4/cruise-config-dependency-migration.xml"), UTF_8);
        CruiseConfig cruiseConfig = loadConfigFileWithContent(content);
        MaterialConfig actual = cruiseConfig.pipelineConfigByName(new CaseInsensitiveString("depends")).materialConfigs().first();
        Assertions.assertThat(actual).isInstanceOf(DependencyMaterialConfig.class);
        DependencyMaterialConfig depends = (DependencyMaterialConfig) actual;
        assertThat(depends.getPipelineName()).isEqualTo(new CaseInsensitiveString("multiple"));
        assertThat(depends.getStageName()).isEqualTo(new CaseInsensitiveString("helloworld-part2"));
    }

    @Test
    public void shouldFailIfJobsWithSameNameButDifferentCasesExistInConfig() throws Exception {
        Files.writeString(configFile, ConfigFileFixture.JOBS_WITH_DIFFERENT_CASE, UTF_8);
        GoConfigHolder configHolder = goConfigMigrator.migrate();
        Assertions.assertThat(configHolder).isNull();
        PipelineConfig frameworkPipeline = goConfigService.getCurrentConfig().getPipelineConfigByName(new CaseInsensitiveString("framework"));
        assertThat(frameworkPipeline).isNull();

        assertThat(exceptions.size()).isEqualTo(1);
        assertThat(exceptions.get(0).getMessage()).contains("You have defined multiple Jobs called 'Test'");
    }

    @Test
    public void shouldVersionControlAnUpgradedConfigIfItIsValid() throws Exception {
        Files.writeString(configFile, ConfigFileFixture.DEFAULT_XML_WITH_2_AGENTS, UTF_8);
        configRepository.checkin(new GoConfigRevision("dummy-content", "some-md5", "loser", "100.3.1", new TimeProvider()));

        GoConfigHolder goConfigHolder = goConfigMigrator.migrate();
        Assertions.assertThat(goConfigHolder.config).isNotNull();
        Assertions.assertThat(goConfigHolder.configForEdit).isNotNull();

        GoConfigRevision latest = configRepository.getRevision(ConfigRepository.CURRENT);

        assertThat(latest.getUsername()).isEqualTo("Upgrade");

        String contents = Files.readString(configFile, UTF_8);
        assertThat(latest.getContent()).isEqualTo(contents);
        assertThat(latest.getMd5()).isEqualTo(DigestUtils.md5Hex(contents));
    }

    @Test
    public void shouldEncryptPasswordsOnMigration() throws Exception {
        String configContent = ConfigFileFixture.configWithPipeline(String.format(
                """
                        <pipeline name='pipeline1'>
                            <materials>
                              <svn url='svnurl' username='admin' password='%s'/>
                            </materials>
                          <stage name='mingle'>
                            <jobs>
                              <job name='do-something'><tasks><exec command='echo'><runif status='passed' /></exec></tasks>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline>""", "hello"), 32);
        Files.writeString(configFile, configContent, UTF_8);

        goConfigMigrator.migrate();

        assertThat(Files.readString(configFile, UTF_8)).contains("encryptedPassword=");
        assertThat(Files.readString(configFile, UTF_8)).doesNotContain("password=");
    }

    @Test
    public void shouldMergeRolesWithMatchingCaseInsensitiveNames() throws Exception {
        final String configContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <cruise schemaVersion="39">
                    <server artifactsdir="artifacts">
                        <security>
                             <roles>
                                 <role name="bAr">
                                     <user>quux</user>
                                     <user>bang</user>
                                     <user>LoSeR</user>
                                 </role>
                                 <role name="Foo">
                                     <user>foo</user>
                                     <user>LoSeR</user>
                                     <user>bar</user>
                                     <user>LOsER</user>
                                 </role>
                                 <role name="BaR">
                                     <user>baz</user>
                                     <user>bang</user>
                                     <user>lOsEr</user>
                                 </role>
                             </roles>
                        </security>
                    </server>
                 </cruise>""";

        Path configFile = Path.of(systemEnvironment.getCruiseConfigFile());
        Files.writeString(configFile, configContent, UTF_8);
        CruiseConfig cruiseConfig = goConfigMigrator.migrate().config;

        RolesConfig roles = cruiseConfig.server().security().getRoles();
        assertThat(roles.size()).isEqualTo(2);
        Assertions.assertThat(roles.get(0)).isEqualTo(new RoleConfig(new CaseInsensitiveString("bAr"),
                new RoleUser(new CaseInsensitiveString("quux")),
                new RoleUser(new CaseInsensitiveString("bang")),
                new RoleUser(new CaseInsensitiveString("LoSeR")),
                new RoleUser(new CaseInsensitiveString("baz"))));

        Assertions.assertThat(roles.get(1)).isEqualTo(new RoleConfig(new CaseInsensitiveString("Foo"),
                new RoleUser(new CaseInsensitiveString("foo")),
                new RoleUser(new CaseInsensitiveString("LoSeR")),
                new RoleUser(new CaseInsensitiveString("bar"))));
    }

    @Test
    public void shouldAllowParamsInP4ServerAndPortField() throws Exception {
        String configContent = ConfigFileFixture.configWithPipeline(
                """
                        <pipeline name='pipeline1'>
                        <params>
                                <param name='param_foo'>a:3</param>
                              </params>
                            <materials>
                        <p4 port='#{param_foo}' username='' dest='blah' materialName='boo'>
                        <view><![CDATA[blah]]></view>
                        <filter>
                        <ignore pattern='' />
                        </filter>
                        </p4>
                            </materials>
                          <stage name='mingle'>
                            <jobs>
                              <job name='do-something'>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline>""", 34);
        Files.writeString(configFile, configContent, UTF_8);

        goConfigMigrator.migrate();

        assertThat(Files.readString(configFile, UTF_8)).contains("port=\"#{param_foo}\"");
    }

    @Test
    public void shouldIntroduceAWrapperTagForUsersOfRole() throws Exception {
        String content = """
                <cruise schemaVersion='47'>
                <server artifactsdir="logs" siteUrl="http://go-server-site-url:8153" secureSiteUrl="https://go-server-site-url" jobTimeout="60">
                    <security>
                      <roles>
                        <role name="admins">
                            <user>admin_one</user>
                            <user>admin_two</user>
                        </role>
                        <role name="devs">
                            <user>dev_one</user>
                            <user>dev_two</user>
                            <user>dev_three</user>
                        </role>
                      </roles>
                      <admins>
                        <role>admins</role>
                      </admins>
                    </security>
                  </server>
                </cruise>""";

        Files.writeString(configFile, content, UTF_8);

        goConfigMigrator.migrate();

        String configXml = Files.readString(configFile, UTF_8);

        MagicalGoConfigXmlLoader loader = new MagicalGoConfigXmlLoader(new ConfigCache(), ConfigElementImplementationRegistryMother.withNoPlugins());
        GoConfigHolder configHolder = loader.loadConfigHolder(configXml);

        CruiseConfig config = configHolder.config;

        ServerConfig server = config.server();
        RolesConfig roles = server.security().getRoles();
        assertThat(roles).contains(new RoleConfig(new CaseInsensitiveString("admins"), new RoleUser(new CaseInsensitiveString("admin_one")), new RoleUser(new CaseInsensitiveString("admin_two"))));
        assertThat(roles).contains(new RoleConfig(new CaseInsensitiveString("devs"), new RoleUser(new CaseInsensitiveString("dev_one")), new RoleUser(new CaseInsensitiveString("dev_two")),
                new RoleUser(new CaseInsensitiveString("dev_three"))));
    }

    @Test
    public void shouldSetServerId_toARandomUUID_ifServerTagDoesntExist() {
        GoConfigService.XmlPartialSaver<CruiseConfig> fileSaver = goConfigService.fileSaver(true);
        GoConfigValidity configValidity = fileSaver.saveXml("<cruise schemaVersion='" + 53 + "'>\n"
                + "</cruise>", goConfigService.configFileMd5());
        assertThat(configValidity.isValid()).as("Has no error").isTrue();

        CruiseConfig config = goConfigService.getCurrentConfig();
        ServerConfig server = config.server();

        assertThat(server.getServerId().matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")).isTrue();
    }

    @Test
    public void shouldSetServerId_toARandomUUID_ifOneDoesntExist() {
        GoConfigService.XmlPartialSaver<CruiseConfig> fileSaver = goConfigService.fileSaver(true);
        GoConfigValidity configValidity = fileSaver.saveXml("""
                <cruise schemaVersion='55'>
                <server artifactsdir="logs" siteUrl="http://go-server-site-url:8153" secureSiteUrl="https://go-server-site-url" jobTimeout="60">
                  </server>
                </cruise>""", goConfigService.configFileMd5());
        assertThat(configValidity.isValid()).as("Has no error").isTrue();

        CruiseConfig config = goConfigService.getCurrentConfig();
        ServerConfig server = config.server();

        assertThat(server.getServerId().matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")).isTrue();
    }

    @Test
    public void shouldMigrateFrom62_ToAddOnChangesAttributeToTimerWithDefaultValueOff() throws Exception {
        final String oldContent = ConfigFileFixture.configWithPipeline("""
                <pipeline name='old-timer'>
                  <timer>0 0 1 * * ?</timer>
                  <materials>
                    <git url='/tmp/git' />
                  </materials>
                  <stage name='dist'>
                    <jobs>
                      <job name='test'><tasks><exec command='echo'><runif status='passed' /></exec></tasks></job>
                    </jobs>
                  </stage>
                </pipeline>""", 62);
        CruiseConfig configAfterMigration = migrateConfigAndLoadTheNewConfig(oldContent);
        String currentContent = Files.readString(Path.of(goConfigService.fileLocation()), UTF_8);

        PipelineConfig pipelineConfig = configAfterMigration.pipelineConfigByName(new CaseInsensitiveString("old-timer"));
        TimerConfig timer = pipelineConfig.getTimer();

        assertThat(configAfterMigration.schemaVersion()).isGreaterThan(62);
        assertThat(timer.shouldTriggerOnlyOnChanges()).isFalse();
        assertThat(currentContent).as("Should not have added onChanges since its default value is false.").doesNotContain("onChanges");
    }

    @Test
    public void forVersion63_shouldUseOnChangesWhileCreatingTimerConfigWhenItIsTrue() throws Exception {
        TimerConfig timer = createTimerConfigWithAttribute("onlyOnChanges='true'");

        assertThat(timer.shouldTriggerOnlyOnChanges()).isTrue();
    }

    @Test
    public void forVersion63_shouldUseOnChangesWhileCreatingTimerConfigWhenItIsFalse() throws Exception {
        TimerConfig timer = createTimerConfigWithAttribute("onlyOnChanges='false'");

        assertThat(timer.shouldTriggerOnlyOnChanges()).isFalse();
    }

    @Test
    public void forVersion63_shouldSetOnChangesToFalseWhileCreatingTimerConfigWhenTheWholeAttributeIsNotPresent() throws Exception {
        TimerConfig timer = createTimerConfigWithAttribute("");

        assertThat(timer.shouldTriggerOnlyOnChanges()).isFalse();
    }

    @Test
    public void forVersion63_shouldFailWhenOnChangesValueIsEmpty() throws IOException {
        String config = configWithTimerBasedPipeline("onlyOnChanges=''");
        Files.writeString(configFile, config, UTF_8);
        goConfigMigrator.migrate();
        assertThat(exceptions.size()).isEqualTo(1);
        assertThat(exceptions.get(0).getCause().getMessage()).contains("'' is not a valid value for 'boolean'");
    }

    @Test
    public void forVersion63_shouldFailWhenOnChangesValueIsNotAValidBooleanValue() throws IOException {
        String config = configWithTimerBasedPipeline("onlyOnChanges='junk-non-boolean'");
        Files.writeString(configFile, config, UTF_8);
        goConfigMigrator.migrate();
        assertThat(exceptions.size()).isEqualTo(1);
        assertThat(exceptions.get(0).getCause().getMessage()).contains("'junk-non-boolean' is not a valid value for 'boolean'");
    }

    @Test
    public void shouldValidatePackageRepositoriesConfiguration() throws Exception {
        String configString =
                """
                        <cruise schemaVersion='66'>
                        <repositories>
                        <repository id='go-repo' name='go-repo'>
                             <pluginConfiguration id='plugin-id' version='1.0'/>
                             <configuration>
                                 <property><key>url</key><value>http://fake-yum-repo</value></property>
                                 <property><key>username</key><value>godev</value></property>
                                 <property><key>password</key><value>password</value></property>
                             </configuration>
                             <packages>
                                 <package id='go-server' name='go-server'>
                                     <configuration>
                                         <property><key>name</key><value>go-server-13.2.0-1-i386</value></property>
                                     </configuration>
                                 </package>
                             </packages>
                        </repository>
                        </repositories>
                        </cruise>""";

        CruiseConfig cruiseConfig = migrateConfigAndLoadTheNewConfig(configString);
        PackageRepositories packageRepositories = cruiseConfig.getPackageRepositories();
        assertThat(packageRepositories.size()).isEqualTo(1);

        assertThat(packageRepositories.get(0).getId()).isEqualTo("go-repo");
        assertThat(packageRepositories.get(0).getName()).isEqualTo("go-repo");
        assertThat(packageRepositories.get(0).getPluginConfiguration().getId()).isEqualTo("plugin-id");
        assertThat(packageRepositories.get(0).getPluginConfiguration().getVersion()).isEqualTo("1.0");
        assertThat(packageRepositories.get(0).getConfiguration()).isNotNull();
        assertThat(packageRepositories.get(0).getPackages().size()).isEqualTo(1);

        assertConfiguration(packageRepositories.get(0).getConfiguration(),
                List.of(
                    List.of("url", Boolean.FALSE, "http://fake-yum-repo"),
                    List.of("username", Boolean.FALSE, "godev"),
                    List.of("password", Boolean.FALSE, "password")
                )
        );

        assertThat(packageRepositories.get(0).getPackages().get(0).getId()).isEqualTo("go-server");
        assertThat(packageRepositories.get(0).getPackages().get(0).getName()).isEqualTo("go-server");
        assertConfiguration(packageRepositories.get(0).getPackages().get(0).getConfiguration(),
                List.of(List.of("name", Boolean.FALSE, "go-server-13.2.0-1-i386")));

    }

    @Test
    public void shouldAllowOnlyRepositoryConfiguration() throws Exception {
        String configString =
                """
                        <cruise schemaVersion='66'>
                        <repositories>
                        <repository id='go-repo' name='go-repo'>
                             <pluginConfiguration id='plugin-id' version='1.0'/>
                             <configuration>
                                 <property><key>url</key><value>http://fake-yum-repo</value></property>
                                 <property><key>username</key><value>godev</value></property>
                                 <property><key>password</key><value>password</value></property>
                             </configuration>
                        </repository>
                        </repositories>
                        </cruise>""";
        CruiseConfig cruiseConfig = loadConfigFileWithContent(configString);
        PackageRepositories packageRepositories = cruiseConfig.getPackageRepositories();
        assertThat(packageRepositories.size()).isEqualTo(1);

        assertThat(packageRepositories.get(0).getId()).isEqualTo("go-repo");
        assertThat(packageRepositories.get(0).getName()).isEqualTo("go-repo");
        assertThat(packageRepositories.get(0).getPluginConfiguration().getId()).isEqualTo("plugin-id");
        assertThat(packageRepositories.get(0).getPluginConfiguration().getVersion()).isEqualTo("1.0");
        assertThat(packageRepositories.get(0).getConfiguration()).isNotNull();
        assertThat(packageRepositories.get(0).getPackages().size()).isEqualTo(0);

        assertConfiguration(packageRepositories.get(0).getConfiguration(),
                List.of(
                    List.of("url", Boolean.FALSE, "http://fake-yum-repo"),
                    List.of("username", Boolean.FALSE, "godev"),
                    List.of("password", Boolean.FALSE, "password")
                )
        );
    }

    @Test
    public void shouldAllowPluggableTaskConfiguration_asPartOfMigration70() throws Exception {
        String configString =
                """
                        <cruise schemaVersion='70'> <pipelines>
                        <pipeline name='pipeline1'>
                            <materials>
                              <svn url='svnurl' username='admin' password='%s'/>
                            </materials>
                          <stage name='mingle'>
                            <jobs>
                              <job name='do-something'><tasks>
                                <task name='run-curl'>
                                  <pluginConfiguration id='plugin-id' version='1.0' />
                                  <configuration>
                                    <property><key>url</key><value>http://fake-yum-repo</value></property>
                                    <property><key>username</key><value>godev</value></property>
                                    <property><key>password</key><value>password</value></property>
                                  </configuration>
                                </task> </tasks>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline></pipelines>
                        </cruise>""";
        CruiseConfig cruiseConfig = loadConfigFileWithContent(configString);
        PipelineConfig pipelineConfig = cruiseConfig.getAllPipelineConfigs().get(0);
        JobConfig jobConfig = pipelineConfig.getFirstStageConfig().getJobs().get(0);
        Tasks tasks = jobConfig.getTasks();
        assertThat(tasks.size()).isEqualTo(1);
        assertThat(tasks.get(0) instanceof PluggableTask).isTrue();
    }

    @Test
    public void shouldTrimLeadingAndTrailingWhitespaceFromCommands_asPartOfMigration73() throws Exception {
        String configXml =
                """
                        <cruise schemaVersion='72'>
                          <pipelines group='first'>
                            <pipeline name='Test'>
                              <materials>
                                <hg url='manual-testing/ant_hg/dummy' />
                              </materials>
                              <stage name='Functional'>
                                <jobs>
                                  <job name='Functional'>
                                    <tasks>
                                      <exec command='  c:\\program files\\cmd.exe    ' args='arguments' />
                                    </tasks>
                                   </job>
                                </jobs>
                              </stage>
                            </pipeline>
                          </pipelines>
                        </cruise>""";
        CruiseConfig migratedConfig = migrateConfigAndLoadTheNewConfig(configXml);
        Task task = migratedConfig.tasksForJob("Test", "Functional", "Functional").get(0);
        Assertions.assertThat(task).isInstanceOf(ExecTask.class);
        Assertions.assertThat(task).isEqualTo(new ExecTask("c:\\program files\\cmd.exe", "arguments", (String) null));
    }

    @Test
    public void shouldTrimLeadingAndTrailingWhitespaceFromCommandsInTemplates_asPartOfMigration73() throws Exception {
        String configXml =
                """
                        <cruise schemaVersion='72'>
                          <pipelines group='first'>
                            <pipeline name='Test' template='test_template'>
                              <materials>
                                <hg url='manual-testing/ant_hg/dummy' />
                              </materials>
                             </pipeline>
                          </pipelines>
                          <templates>
                            <pipeline name='test_template'>
                              <stage name='Functional'>
                                <jobs>
                                  <job name='Functional'>
                                    <tasks>
                                      <exec command='  c:\\program files\\cmd.exe    ' args='arguments' />
                                    </tasks>
                                   </job>
                                </jobs>
                              </stage>
                            </pipeline>
                          </templates>
                        </cruise>""";
        CruiseConfig migratedConfig = migrateConfigAndLoadTheNewConfig(configXml);
        Task task = migratedConfig.tasksForJob("Test", "Functional", "Functional").get(0);
        Assertions.assertThat(task).isInstanceOf(ExecTask.class);
        Assertions.assertThat(task).isEqualTo(new ExecTask("c:\\program files\\cmd.exe", "arguments", (String) null));
    }

    @Test
    public void ShouldTrimEnvironmentVariables_asPartOfMigration85() throws Exception {
        String configXml = """
                <cruise schemaVersion='84'>
                  <pipelines group='first'>
                    <pipeline name='up42'>
                      <environmentvariables>
                        <variable name=" test  ">
                          <value>foobar</value>
                        </variable>
                        <variable name="   PATH " secure="true">
                          <encryptedValue>trMHp15AjUE=</encryptedValue>
                        </variable>
                      </environmentvariables>
                      <materials>
                        <hg url='manual-testing/ant_hg/dummy' />
                      </materials>
                  <stage name='dist'>
                    <jobs>
                      <job name='test'><tasks><exec command='echo'><runif status='passed' /></exec></tasks></job>
                    </jobs>
                  </stage>
                     </pipeline>
                  </pipelines>
                </cruise>""";
        CruiseConfig migratedConfig = migrateConfigAndLoadTheNewConfig(configXml);
        PipelineConfig pipelineConfig = migratedConfig.pipelineConfigByName(new CaseInsensitiveString("up42"));
        EnvironmentVariablesConfig variables = pipelineConfig.getVariables();
        assertThat(variables.getPlainTextVariables().first().getName()).isEqualTo("test");
        assertThat(variables.getPlainTextVariables().first().getValue()).isEqualTo("foobar");
        assertThat(variables.getSecureVariables().first().getName()).isEqualTo("PATH");
        // encrypted value for "abcd" is "trMHp15AjUE=" for the cipher "269298bc31c44620"
        assertThat(variables.getSecureVariables().first().getValue()).isEqualTo("abcd");
    }

    @Test
    public void shouldCreateProfilesFromAgentConfig_asPartOfMigration86And87() throws Exception {
        String configXml = """
                <cruise schemaVersion='85'>
                  <server serverId='dev-id'>
                  </server>
                  <pipelines group='first'>
                    <pipeline name='up42'>
                      <materials>
                        <hg url='manual-testing/ant_hg/dummy' />
                      </materials>
                  <stage name='dist'>
                    <jobs>
                      <job name='test'><tasks><exec command='echo'><runif status='passed' /></exec></tasks>
                       <agentConfig pluginId='docker'>
                         <property>
                           <key>instance-type</key>
                           <value>m1.small</value>
                         </property>
                       </agentConfig>
                      </job>
                    </jobs>
                  </stage>
                   </pipeline>
                  </pipelines>
                </cruise>""";

        CruiseConfig migratedConfig = migrateConfigAndLoadTheNewConfig(configXml);
        PipelineConfig pipelineConfig = migratedConfig.pipelineConfigByName(new CaseInsensitiveString("up42"));
        JobConfig jobConfig = pipelineConfig.getStages().get(0).getJobs().get(0);

        assertThat(migratedConfig.schemaVersion()).isGreaterThan(86);

        ElasticProfiles profiles = migratedConfig.getElasticConfig().getProfiles();
        assertThat(profiles.size()).isEqualTo(1);

        ElasticProfile expectedProfile = new ElasticProfile(jobConfig.getElasticProfileId(), "no-op-cluster-for-docker",
                new ConfigurationProperty(new ConfigurationKey("instance-type"), new ConfigurationValue("m1.small")));

        ElasticProfile elasticProfile = profiles.get(0);
        assertThat(elasticProfile).isEqualTo(expectedProfile);
    }

    @Test
    public void shouldCreateProfilesFromMultipleAgentConfigs_asPartOfMigration86And87() throws Exception {
        String configXml = """
                <cruise schemaVersion='85'>
                  <server serverId='dev-id'>
                  </server>
                  <pipelines group='first'>
                    <pipeline name='up42'>
                      <materials>
                        <hg url='manual-testing/ant_hg/dummy' />
                      </materials>
                  <stage name='dist'>
                    <jobs>
                      <job name='test1'><tasks><exec command='echo'><runif status='passed' /></exec></tasks>
                       <agentConfig pluginId='docker'>
                         <property>
                           <key>instance-type</key>
                           <value>m1.small</value>
                         </property>
                       </agentConfig>
                      </job>
                      <job name='test2'><tasks><exec command='echo'><runif status='passed' /></exec></tasks>
                       <agentConfig pluginId='aws'>
                         <property>
                           <key>ami</key>
                           <value>some.ami</value>
                         </property>
                         <property>
                           <key>ram</key>
                           <value>1024</value>
                         </property>
                         <property>
                           <key>diskSpace</key>
                           <value>10G</value>
                         </property>
                       </agentConfig>
                      </job>
                    </jobs>
                  </stage>
                   </pipeline>
                  </pipelines>
                </cruise>""";

        CruiseConfig migratedConfig = migrateConfigAndLoadTheNewConfig(configXml);
        PipelineConfig pipelineConfig = migratedConfig.pipelineConfigByName(new CaseInsensitiveString("up42"));
        JobConfigs jobs = pipelineConfig.getStages().get(0).getJobs();

        ElasticProfiles profiles = migratedConfig.getElasticConfig().getProfiles();
        assertThat(profiles.size()).isEqualTo(2);

        ElasticProfile expectedDockerProfile = new ElasticProfile(jobs.get(0).getElasticProfileId(), "no-op-cluster-for-docker",
                new ConfigurationProperty(new ConfigurationKey("instance-type"), new ConfigurationValue("m1.small")));
        assertThat(profiles.get(0)).isEqualTo(expectedDockerProfile);

        ElasticProfile expectedAWSProfile = new ElasticProfile(jobs.get(1).getElasticProfileId(), "no-op-cluster-for-aws",
                new ConfigurationProperty(new ConfigurationKey("ami"), new ConfigurationValue("some.ami")),
                new ConfigurationProperty(new ConfigurationKey("ram"), new ConfigurationValue("1024")),
                new ConfigurationProperty(new ConfigurationKey("diskSpace"), new ConfigurationValue("10G")));
        assertThat(profiles.get(1)).isEqualTo(expectedAWSProfile);
    }

    @Test
    public void shouldCreateProfilesFromMultipleAgentConfigsAcrossStages_asPartOfMigration86And87() throws Exception {
        String configXml = """
                <cruise schemaVersion='85'>
                 <server serverId='dev-id'>
                 </server>
                 <pipelines group='first'>
                   <pipeline name='up42'>
                     <materials>
                       <hg url='manual-testing/ant_hg/dummy' />
                     </materials>
                  <stage name='build'>
                    <jobs>
                      <job name='test1'><tasks><exec command='echo'><runif status='passed' /></exec></tasks>
                       <agentConfig pluginId='docker'>
                         <property>
                           <key>instance-type</key>
                           <value>m1.small</value>
                         </property>
                       </agentConfig>
                      </job>
                      <job name='test2'><tasks><exec command='echo'><runif status='passed' /></exec></tasks>
                       <agentConfig pluginId='aws'>
                         <property>
                           <key>ami</key>
                           <value>some.ami</value>
                         </property>
                         <property>
                           <key>ram</key>
                           <value>1024</value>
                         </property>
                         <property>
                           <key>diskSpace</key>
                           <value>10G</value>
                         </property>
                       </agentConfig>
                      </job>
                    </jobs>
                  </stage>
                  <stage name='dist'>
                    <jobs>
                      <job name='package'><tasks><exec command='echo'><runif status='passed' /></exec></tasks>
                       <agentConfig pluginId='docker'>
                         <property>
                           <key>instance-type</key>
                           <value>m1.small</value>
                         </property>
                       </agentConfig>
                      </job>
                    </jobs>
                  </stage>
                   </pipeline>
                  </pipelines>
                </cruise>""";

        CruiseConfig migratedConfig = migrateConfigAndLoadTheNewConfig(configXml);
        PipelineConfig pipelineConfig = migratedConfig.pipelineConfigByName(new CaseInsensitiveString("up42"));
        JobConfigs buildJobs = pipelineConfig.getStages().get(0).getJobs();
        JobConfigs distJobs = pipelineConfig.getStages().get(1).getJobs();

        ElasticProfiles profiles = migratedConfig.getElasticConfig().getProfiles();
        assertThat(profiles.size()).isEqualTo(3);

        ElasticProfile expectedDockerProfile = new ElasticProfile(buildJobs.get(0).getElasticProfileId(), "no-op-cluster-for-docker",
                new ConfigurationProperty(new ConfigurationKey("instance-type"), new ConfigurationValue("m1.small")));
        assertThat(profiles.get(0)).isEqualTo(expectedDockerProfile);

        ElasticProfile expectedAWSProfile = new ElasticProfile(buildJobs.get(1).getElasticProfileId(), "no-op-cluster-for-aws",
                new ConfigurationProperty(new ConfigurationKey("ami"), new ConfigurationValue("some.ami")),
                new ConfigurationProperty(new ConfigurationKey("ram"), new ConfigurationValue("1024")),
                new ConfigurationProperty(new ConfigurationKey("diskSpace"), new ConfigurationValue("10G")));
        assertThat(profiles.get(1)).isEqualTo(expectedAWSProfile);

        ElasticProfile expectedSecondDockerProfile = new ElasticProfile(distJobs.get(0).getElasticProfileId(), "no-op-cluster-for-docker",
                new ConfigurationProperty(new ConfigurationKey("instance-type"), new ConfigurationValue("m1.small")));
        assertThat(profiles.get(2)).isEqualTo(expectedSecondDockerProfile);
    }

    @Test
    public void shouldCreateProfilesFromMultipleAgentConfigsAcrossPipelines_asPartOfMigration86And87() throws Exception {
        String configXml = """
                <cruise schemaVersion='85'>
                 <server serverId='dev-id'>
                 </server>
                 <pipelines group='first'>
                   <pipeline name='up42'>
                     <materials>
                       <hg url='manual-testing/ant_hg/dummy' />
                     </materials>
                  <stage name='build'>
                    <jobs>
                      <job name='test1'><tasks><exec command='echo'><runif status='passed' /></exec></tasks>
                       <agentConfig pluginId='docker'>
                         <property>
                           <key>instance-type</key>
                           <value>m1.small</value>
                         </property>
                       </agentConfig>
                      </job>
                    </jobs>
                  </stage>
                   </pipeline>
                   <pipeline name='up43'>
                     <materials>
                       <hg url='manual-testing/ant_hg/dummy' />
                     </materials>
                  <stage name='build'>
                    <jobs>
                      <job name='test2'><tasks><exec command='echo'><runif status='passed' /></exec></tasks>
                       <agentConfig pluginId='aws'>
                         <property>
                           <key>ami</key>
                           <value>some.ami</value>
                         </property>
                         <property>
                           <key>ram</key>
                           <value>1024</value>
                         </property>
                         <property>
                           <key>diskSpace</key>
                           <value>10G</value>
                         </property>
                       </agentConfig>
                      </job>
                    </jobs>
                  </stage>
                   </pipeline>
                  </pipelines>
                </cruise>""";

        CruiseConfig migratedConfig = migrateConfigAndLoadTheNewConfig(configXml);
        PipelineConfig up42 = migratedConfig.pipelineConfigByName(new CaseInsensitiveString("up42"));
        PipelineConfig up43 = migratedConfig.pipelineConfigByName(new CaseInsensitiveString("up43"));
        JobConfigs up42Jobs = up42.getStages().get(0).getJobs();
        JobConfigs up43Jobs = up43.getStages().get(0).getJobs();

        ElasticProfiles profiles = migratedConfig.getElasticConfig().getProfiles();
        assertThat(profiles.size()).isEqualTo(2);

        ElasticProfile expectedDockerProfile = new ElasticProfile(up42Jobs.get(0).getElasticProfileId(), "no-op-cluster-for-docker",
                new ConfigurationProperty(new ConfigurationKey("instance-type"), new ConfigurationValue("m1.small")));
        assertThat(profiles.get(0)).isEqualTo(expectedDockerProfile);

        ElasticProfile expectedAWSProfile = new ElasticProfile(up43Jobs.get(0).getElasticProfileId(), "no-op-cluster-for-aws",
                new ConfigurationProperty(new ConfigurationKey("ami"), new ConfigurationValue("some.ami")),
                new ConfigurationProperty(new ConfigurationKey("ram"), new ConfigurationValue("1024")),
                new ConfigurationProperty(new ConfigurationKey("diskSpace"), new ConfigurationValue("10G")));
        assertThat(profiles.get(1)).isEqualTo(expectedAWSProfile);
    }

    @Test
    public void shouldAddTokenGenerationKeyAttributeOnServerAsPartOf99To100Migration() {
        try {
            String configXml = """
                    <cruise schemaVersion='99'><server artifactsdir="artifacts" agentAutoRegisterKey="041b5c7e-dab2-11e5-a908-13f95f3c6ef6" webhookSecret="5f8b5eac-1148-4145-aa01-7b2934b6e1ab" commandRepositoryLocation="default" serverId="dev-id">
                        <security>
                          <authConfigs>
                            <authConfig id="9cad79b0-4d9e-4a62-829c-eb4d9488062f" pluginId="cd.go.authentication.passwordfile">
                              <property>
                                <key>PasswordFilePath</key>
                                <value>../manual-testing/ant_hg/password.properties</value>
                              </property>
                            </authConfig>
                          </authConfigs>
                          <roles>
                            <role name="xyz" />
                          </roles>
                          <admins>
                            <user>admin</user>
                          </admins>
                        </security>
                      </server>
                    </cruise>""";

            final CruiseConfig cruiseConfig = migrateConfigAndLoadTheNewConfig(configXml);
            assertThat(StringUtils.isNotBlank(cruiseConfig.server().getTokenGenerationKey())).isTrue();
        } catch (Exception e) {
            System.err.println("jyoti singh: " + e.getMessage());
        }
    }

    @Test
    public void shouldMigrateElasticProfilesOutOfServerConfig_asPartOf100To101Migration() throws Exception {
        String configXml = """
                <cruise schemaVersion='100'>
                <server artifactsdir="artifactsDir" agentAutoRegisterKey="041b5c7e-dab2-11e5-a908-13f95f3c6ef6" webhookSecret="5f8b5eac-1148-4145-aa01-7b2934b6e1ab" commandRepositoryLocation="default" serverId="dev-id">
                <elastic jobStarvationTimeout="3">
                      <profiles>
                        <profile id="dev-build" pluginId="cd.go.contrib.elastic-agent.docker-swarm">
                          <property>
                            <key>Image</key>
                            <value>bar</value>
                          </property>
                          <property>
                            <key>ReservedMemory</key>
                            <value>3GB</value>
                          </property>
                          <property>
                            <key>MaxMemory</key>
                            <value>3GB</value>
                          </property>
                        </profile>
                      </profiles>
                    </elastic>
                    <security allowOnlyKnownUsersToLogin="true"></security>
                  </server>
                  <scms>
                    <scm id="c0758880-10f7-4f38-a0b0-f3dc31e5d907" name="gocd">
                      <pluginConfiguration id="github.pr" version="1"/>
                      <configuration>
                        <property>
                          <key>url</key>
                          <value>https://foo/bar</value>
                        </property>
                      </configuration>
                    </scm>
                  </scms>
                </cruise>""";

        final CruiseConfig cruiseConfig = migrateConfigAndLoadTheNewConfig(configXml);
        Assertions.assertThat(cruiseConfig.getElasticConfig()).isNotNull();
        assertThat(cruiseConfig.server().getAgentAutoRegisterKey()).isEqualTo("041b5c7e-dab2-11e5-a908-13f95f3c6ef6");
        assertThat(cruiseConfig.server().getWebhookSecret()).isEqualTo("5f8b5eac-1148-4145-aa01-7b2934b6e1ab");
        assertThat(cruiseConfig.server().artifactsDir()).isEqualTo("artifactsDir");
        assertThat(cruiseConfig.getElasticConfig().getProfiles()).hasSize(1);
        assertThat(cruiseConfig.getElasticConfig().getProfiles().get(0)).isEqualTo(new ElasticProfile("dev-build", "no-op-cluster-for-cd.go.contrib.elastic-agent.docker-swarm",
                ConfigurationPropertyMother.create("Image", false, "bar"),
                ConfigurationPropertyMother.create("ReservedMemory", false, "3GB"),
                ConfigurationPropertyMother.create("MaxMemory", false, "3GB")
        ));
    }

    @Test
    public void shouldRetainAllOtherServerConfigElements_asPartOf100To101Migration() throws Exception {
        String configXml = """
                <cruise schemaVersion='100'>
                <server artifactsdir="artifactsDir" agentAutoRegisterKey="041b5c7e-dab2-11e5-a908-13f95f3c6ef6" webhookSecret="5f8b5eac-1148-4145-aa01-7b2934b6e1ab" commandRepositoryLocation="default" serverId="dev-id">
                <elastic jobStarvationTimeout="3">
                      <profiles>
                        <profile id="dev-build" pluginId="cd.go.contrib.elastic-agent.docker-swarm">
                          <property>
                            <key>Image</key>
                            <value>bar</value>
                          </property>
                          <property>
                            <key>ReservedMemory</key>
                            <value>3GB</value>
                          </property>
                          <property>
                            <key>MaxMemory</key>
                            <value>3GB</value>
                          </property>
                        </profile>
                      </profiles>
                    </elastic>
                    <security allowOnlyKnownUsersToLogin="false"></security>
                  </server>
                  <scms>
                    <scm id="c0758880-10f7-4f38-a0b0-f3dc31e5d907" name="gocd">
                      <pluginConfiguration id="github.pr" version="1"/>
                      <configuration>
                        <property>
                          <key>url</key>
                          <value>https://foo/bar</value>
                        </property>
                      </configuration>
                    </scm>
                  </scms>
                </cruise>""";

        final CruiseConfig cruiseConfig = migrateConfigAndLoadTheNewConfig(configXml);
        assertThat(cruiseConfig.server().getAgentAutoRegisterKey()).isEqualTo("041b5c7e-dab2-11e5-a908-13f95f3c6ef6");
        assertThat(cruiseConfig.server().getWebhookSecret()).isEqualTo("5f8b5eac-1148-4145-aa01-7b2934b6e1ab");
        assertThat(cruiseConfig.server().artifactsDir()).isEqualTo("artifactsDir");
        Assertions.assertThat(cruiseConfig.server().security()).isEqualTo(new SecurityConfig());
        assertThat(cruiseConfig.getSCMs()).hasSize(1);
    }

    @Test
    public void shouldSkipParamResoulutionForElasticConfig_asPartOf100To101Migration() throws Exception {
        String configXml = """
                <cruise schemaVersion='100'>
                <server artifactsdir="artifactsDir" agentAutoRegisterKey="041b5c7e-dab2-11e5-a908-13f95f3c6ef6" webhookSecret="5f8b5eac-1148-4145-aa01-7b2934b6e1ab" commandRepositoryLocation="default" serverId="dev-id">
                <elastic jobStarvationTimeout="3">
                      <profiles>
                        <profile id="dev-build" pluginId="cd.go.contrib.elastic-agent.docker-swarm">
                          <property>
                            <key>Image</key>
                            <value>#bar</value>
                          </property>
                        </profile>
                      </profiles>
                    </elastic>
                  </server>
                </cruise>""";

        final CruiseConfig cruiseConfig = migrateConfigAndLoadTheNewConfig(configXml);
        assertThat(cruiseConfig.getElasticConfig().getProfiles().get(0)).isEqualTo(new ElasticProfile("dev-build", "no-op-cluster-for-cd.go.contrib.elastic-agent.docker-swarm",
                ConfigurationPropertyMother.create("Image", false, "#bar")
        ));
    }

    @Test
    public void shouldAddHttpPrefixToTrackingToolUrlsIfProtocolNotPresent() throws Exception {
        String configXml = """
                <cruise schemaVersion='104'>
                <pipelines group='first'>
                    <pipeline name='up42'>
                      <trackingtool link='github.com/gocd/gocd/issues/${ID}' regex='##(\\d+)'/>
                      <materials>
                        <git url='test-repo' />
                      </materials>
                      <stage name='up42_stage'>
                        <jobs>
                          <job name='up42_job'>
                            <tasks>
                              <exec command='ls' />
                            </tasks>
                          </job>
                        </jobs>
                      </stage>
                    </pipeline>
                    <pipeline name='up43'>
                      <trackingtool link='https://github.com/gocd/gocd/issues/${ID}' regex='##(\\d+)'/>
                      <materials>
                        <git url='test-repo' />
                      </materials>
                      <stage name='up43_stage'>
                        <jobs>
                          <job name='up43_job'>
                            <tasks>
                              <exec command='ls' />
                            </tasks>
                          </job>
                        </jobs>
                      </stage>
                    </pipeline>
                  </pipelines>
                <pipelines group='second'>
                    <pipeline name='up12'>
                      <trackingtool link='http://github.com/gocd/gocd/issues/${ID}' regex='##(\\d+)'/>
                      <materials>
                        <git url='test-repo' />
                      </materials>
                      <stage name='up42_stage'>
                        <jobs>
                          <job name='up42_job'>
                            <tasks>
                              <exec command='ls' />
                            </tasks>
                          </job>
                        </jobs>
                      </stage>
                    </pipeline>
                    <pipeline name='up13'>
                      <trackingtool link='github.com/gocd/gocd/issues/${ID}' regex='##(\\d+)'/>
                      <materials>
                        <git url='test-repo' />
                      </materials>
                      <stage name='up43_stage'>
                        <jobs>
                          <job name='up43_job'>
                            <tasks>
                              <exec command='ls' />
                            </tasks>
                          </job>
                        </jobs>
                      </stage>
                    </pipeline>
                  </pipelines>
                </cruise>
                """;

        final CruiseConfig cruiseConfig = migrateConfigAndLoadTheNewConfig(configXml);

        assertThat(cruiseConfig.pipelines("first").findBy(str("up42")).getTrackingTool().getLink()).isEqualTo("http://github.com/gocd/gocd/issues/${ID}");
        assertThat(cruiseConfig.pipelines("first").findBy(str("up43")).getTrackingTool().getLink()).isEqualTo("https://github.com/gocd/gocd/issues/${ID}");
        assertThat(cruiseConfig.pipelines("second").findBy(str("up12")).getTrackingTool().getLink()).isEqualTo("http://github.com/gocd/gocd/issues/${ID}");
        assertThat(cruiseConfig.pipelines("second").findBy(str("up13")).getTrackingTool().getLink()).isEqualTo("http://github.com/gocd/gocd/issues/${ID}");
    }

    @Test
    public void shouldRunMigration59_convertLogTypeToArtifact() throws Exception {
        final CruiseConfig cruiseConfig = migrateConfigAndLoadTheNewConfig(ConfigFileFixture.WITH_LOG_ARTIFACT_CONFIG);

        ArtifactTypeConfigs artifactTypeConfigs = cruiseConfig.getAllPipelineConfigs().get(0).getStage(new CaseInsensitiveString("mingle")).getJobs().getJob(
                new CaseInsensitiveString("bluemonkeybutt")).artifactTypeConfigs();

        assertThat(artifactTypeConfigs.getBuiltInArtifactConfigs().get(0).getSource()).isEqualTo("from1");
        assertThat("").isEqualTo(artifactTypeConfigs.getBuiltInArtifactConfigs().get(0).getDestination());
        assertThat(artifactTypeConfigs.getBuiltInArtifactConfigs().get(1).getSource()).isEqualTo("from2");
        assertThat(artifactTypeConfigs.getBuiltInArtifactConfigs().get(1).getDestination()).isEqualTo("to2");
        assertThat(artifactTypeConfigs.getBuiltInArtifactConfigs().get(2).getSource()).isEqualTo("from3");
        assertThat("").isEqualTo(artifactTypeConfigs.getBuiltInArtifactConfigs().get(2).getDestination());
        assertThat(artifactTypeConfigs.getBuiltInArtifactConfigs().get(3).getSource()).isEqualTo("from4");
        assertThat(artifactTypeConfigs.getBuiltInArtifactConfigs().get(3).getDestination()).isEqualTo("to4");
    }

    @Test
    public void shouldRemoveNameFromPluggableTask_asPartOfMigration71() throws Exception {
        String oldConfigWithNameInTask =
                """
                        <cruise schemaVersion='70'> <pipelines>
                        <pipeline name='pipeline1'>
                            <materials>
                              <svn url='svnurl' username='admin' password='%s'/>
                            </materials>
                          <stage name='mingle'>
                            <jobs>
                              <job name='do-something'><tasks>
                                <task name='run-curl'>
                                  <pluginConfiguration id='plugin-id' version='1.0' />
                                  <configuration>
                                    <property><key>url</key><value>http://fake-yum-repo</value></property>
                                    <property><key>username</key><value>godev</value></property>
                                    <property><key>password</key><value>password</value></property>
                                  </configuration>
                              </task> </tasks>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline></pipelines>
                        </cruise>""";

        CruiseConfig cruiseConfig = migrateConfigAndLoadTheNewConfig(oldConfigWithNameInTask);
        String newConfigWithoutNameInTask = Files.readString(configFile, UTF_8);

        XmlAssert.assertThat(newConfigWithoutNameInTask).hasXPath("//cruise/pipelines/pipeline/stage/jobs/job/tasks/task");
        XmlAssert.assertThat(newConfigWithoutNameInTask).doesNotHaveXPath("//cruise/pipelines/pipeline/stage/jobs/job/tasks/task[@name]");
        PipelineConfig pipelineConfig = cruiseConfig.getAllPipelineConfigs().get(0);
        JobConfig jobConfig = pipelineConfig.getFirstStageConfig().getJobs().get(0);

        Configuration configuration = new Configuration(
                create("url", false, "http://fake-yum-repo"),
                create("username", false, "godev"),
                create("password", false, "password"));

        Tasks tasks = jobConfig.getTasks();
        assertThat(tasks.size()).isEqualTo(1);
        Assertions.assertThat(tasks.get(0)).isEqualTo(new PluggableTask(new PluginConfiguration("plugin-id", "1.0"), configuration));
    }

    @Test
    public void shouldDefineNoOpClustersAsPartOfMigration119() throws Exception {
        String configContent = """
                 <elastic>
                    <profiles>
                      <profile id="profile1" pluginId="cd.go.contrib.elastic-agent.docker">
                        <property>
                          <key>Image</key>
                          <value>alpine:latest</value>
                        </property>
                      </profile>
                      <profile id="profile2" pluginId="cd.go.contrib.elasticagent.kubernetes">
                        <property>
                          <key>Image</key>
                          <value>alpine:latest</value>
                        </property>
                      </profile>
                      <profile id="profile3" pluginId="cd.go.contrib.elastic-agent.docker">
                        <property>
                          <key>Image</key>
                          <value>alpine:latest</value>
                        </property>
                      </profile>
                      <profile id="profile4" pluginId="com.thoughtworks.gocd.elastic-agent.azure">
                        <property>
                          <key>Image</key>
                          <value>alpine:latest</value>
                        </property>
                      </profile>
                      <profile id="profile5" pluginId="com.thoughtworks.gocd.elastic-agent.azure">
                        <property>
                          <key>Image</key>
                          <value>alpine:latest</value>
                        </property>
                      </profile>
                    </profiles>
                  </elastic>\
                """;

        String configXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<cruise schemaVersion=\"118\">\n"
                + configContent
                + "</cruise>";

        ClusterProfile azureProfile = new ClusterProfile("no-op-cluster-for-com.thoughtworks.gocd.elastic-agent.azure", "com.thoughtworks.gocd.elastic-agent.azure");
        ClusterProfile dockerProfile = new ClusterProfile("no-op-cluster-for-cd.go.contrib.elastic-agent.docker", "cd.go.contrib.elastic-agent.docker");
        ClusterProfile kubernetesProfile = new ClusterProfile("no-op-cluster-for-cd.go.contrib.elasticagent.kubernetes", "cd.go.contrib.elasticagent.kubernetes");

        CruiseConfig migratedConfig = migrateConfigAndLoadTheNewConfig(configXml);
        ClusterProfiles newlyDefinedClusters = migratedConfig.getElasticConfig().getClusterProfiles();
        ElasticProfiles migratedElasticAgentProfiles = migratedConfig.getElasticConfig().getProfiles();

        assertThat(newlyDefinedClusters).hasSize(3);
        assertThat(newlyDefinedClusters.find("no-op-cluster-for-com.thoughtworks.gocd.elastic-agent.azure")).isEqualTo(azureProfile);
        assertThat(newlyDefinedClusters.find("no-op-cluster-for-cd.go.contrib.elastic-agent.docker")).isEqualTo(dockerProfile);
        assertThat(newlyDefinedClusters.find("no-op-cluster-for-cd.go.contrib.elasticagent.kubernetes")).isEqualTo(kubernetesProfile);


        ElasticProfile profile1 = new ElasticProfile("profile1", "no-op-cluster-for-cd.go.contrib.elastic-agent.docker", new ConfigurationProperty(new ConfigurationKey("Image"), new ConfigurationValue("alpine:latest")));
        ElasticProfile profile2 = new ElasticProfile("profile2", "no-op-cluster-for-cd.go.contrib.elasticagent.kubernetes", new ConfigurationProperty(new ConfigurationKey("Image"), new ConfigurationValue("alpine:latest")));
        ElasticProfile profile3 = new ElasticProfile("profile3", "no-op-cluster-for-cd.go.contrib.elastic-agent.docker", new ConfigurationProperty(new ConfigurationKey("Image"), new ConfigurationValue("alpine:latest")));
        ElasticProfile profile4 = new ElasticProfile("profile4", "no-op-cluster-for-com.thoughtworks.gocd.elastic-agent.azure", new ConfigurationProperty(new ConfigurationKey("Image"), new ConfigurationValue("alpine:latest")));
        ElasticProfile profile5 = new ElasticProfile("profile5", "no-op-cluster-for-com.thoughtworks.gocd.elastic-agent.azure", new ConfigurationProperty(new ConfigurationKey("Image"), new ConfigurationValue("alpine:latest")));

        assertThat(migratedElasticAgentProfiles.find("profile1")).isEqualTo(profile1);
        assertThat(migratedElasticAgentProfiles.find("profile2")).isEqualTo(profile2);
        assertThat(migratedElasticAgentProfiles.find("profile3")).isEqualTo(profile3);
        assertThat(migratedElasticAgentProfiles.find("profile4")).isEqualTo(profile4);
        assertThat(migratedElasticAgentProfiles.find("profile5")).isEqualTo(profile5);
    }

    @Test
    public void shouldMigrateAgentsOutOfXMLIntoDBAsPartOf128() throws Exception {
        String configContent = """
                  <environments>
                    <environment name="bar">
                    </environment>
                    <environment name="baz">
                      <agents>
                        <physical uuid="elastic-one" />
                        <physical uuid="elastic-two" />
                      </agents>
                    </environment>
                    <environment name="foo">
                      <agents>
                        <physical uuid="one" />
                        <physical uuid="two" />
                        <physical uuid="elastic-two" />
                      </agents>
                    </environment>
                  </environments>
                  <agents>
                    <agent uuid='one' hostname='one-host' ipaddress='127.0.0.1' >
                        <resources>
                           <resource>repos</resource>
                           <resource>db</resource>
                        </resources>
                    </agent>
                    <agent uuid='two' hostname='two-host' ipaddress='127.0.0.2'/>
                    <agent uuid='elastic-one' hostname='one-elastic-host' ipaddress='172.10.20.30' elasticAgentId='docker.foo1' elasticPluginId='docker'/>
                    <agent uuid='elastic-two' hostname='two-elastic-host' ipaddress='172.10.20.31' elasticAgentId='docker.foo2' elasticPluginId='docker'/>
                  </agents>\
                """;

        String configXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<cruise schemaVersion=\"127\">\n"
                + configContent
                + "</cruise>";

        int initialAgentCountInDb = agentDao.getAllAgents().size();
        migrateConfigAndLoadTheNewConfig(configXml);
        String newConfigFile = Files.readString(configFile, UTF_8);

        // clearing out the hibernate cache so that the service fetches from the DB
        Cache cache = sessionFactory.getCache();
        if (cache != null) {
            cache.evictDefaultQueryRegion();
        }
        int newAgentCountInDb = agentDao.getAllAgents().size();
        assertThat(newAgentCountInDb).isEqualTo(initialAgentCountInDb + 4);

        Agent staticAgent = agentDao.fetchAgentFromDBByUUID("one");

        assertThat(staticAgent.getResourcesAsList()).contains("repos", "db");
        assertThat(staticAgent.getEnvironmentsAsList()).contains("foo");
        assertThat(staticAgent.getHostname()).isEqualTo("one-host");
        assertThat(staticAgent.getIpaddress()).isEqualTo("127.0.0.1");
        assertThat(staticAgent.isDisabled()).isFalse();
        assertThat(staticAgent.isDeleted()).isFalse();

        Agent staticAgent2 = agentDao.fetchAgentFromDBByUUID("two");

        assertThat(staticAgent2.getResources()).isEmpty();
        assertThat(staticAgent2.getEnvironments()).isEqualTo("foo");
        assertThat(staticAgent2.getHostname()).isEqualTo("two-host");
        assertThat(staticAgent2.getIpaddress()).isEqualTo("127.0.0.2");
        assertThat(staticAgent2.isDisabled()).isFalse();
        assertThat(staticAgent2.isDeleted()).isFalse();

        Agent elasticAgent = agentDao.fetchAgentFromDBByUUID("elastic-two");

        assertThat(elasticAgent.getEnvironmentsAsList()).contains("foo", "baz");
        assertThat(elasticAgent.getHostname()).isEqualTo("two-elastic-host");
        assertThat(elasticAgent.getIpaddress()).isEqualTo("172.10.20.31");
        assertThat(elasticAgent.getElasticPluginId()).isEqualTo("docker");
        assertThat(elasticAgent.getElasticAgentId()).isEqualTo("docker.foo2");
        assertThat(elasticAgent.isDisabled()).isFalse();
        assertThat(elasticAgent.isDeleted()).isFalse();

        Agent elasticAgent1 = agentDao.fetchAgentFromDBByUUID("elastic-one");

        assertThat(elasticAgent1.getEnvironments()).isEqualTo("baz");
        assertThat(elasticAgent1.getHostname()).isEqualTo("one-elastic-host");
        assertThat(elasticAgent1.getIpaddress()).isEqualTo("172.10.20.30");
        assertThat(elasticAgent1.getElasticPluginId()).isEqualTo("docker");
        assertThat(elasticAgent1.getElasticAgentId()).isEqualTo("docker.foo1");
        assertThat(elasticAgent1.isDisabled()).isFalse();
        assertThat(elasticAgent1.isDeleted()).isFalse();

        XmlAssert.assertThat(newConfigFile).doesNotHaveXPath("//agents");
        XmlAssert.assertThat(newConfigFile).doesNotHaveXPath("//environments/environment/agents");
    }

    @Test
    public void shouldUpdateAnExistingAgentRecordInDBAsPartOfXMLToDBMigration_128() throws Exception {
        String configContent = """
                  <environments>
                    <environment name="foo">
                      <agents>
                        <physical uuid="one" />
                      </agents>
                    </environment>
                  </environments>
                  <agents>
                    <agent uuid='one' hostname='one-host' ipaddress='127.0.0.1' >
                        <resources>
                           <resource>repos</resource>
                           <resource>db</resource>
                        </resources>
                    </agent>
                  </agents>\
                """;

        String configXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<cruise schemaVersion=\"127\">\n"
                + configContent
                + "</cruise>";

        Agent agent = new Agent("one", "old-host", "old-ip", "cookie");
        agentDao.saveOrUpdate(agent);

        migrateConfigAndLoadTheNewConfig(configXml);
        String newConfigFile = Files.readString(configFile, UTF_8);

        Agent staticAgent = agentDao.fetchAgentFromDBByUUID("one");

        assertThat(staticAgent.getResourcesAsList()).contains("repos", "db");
        assertThat(staticAgent.getEnvironments()).isEqualTo("foo");
        assertThat(staticAgent.getHostname()).isEqualTo("one-host");
        assertThat(staticAgent.getIpaddress()).isEqualTo("127.0.0.1");
        assertThat(staticAgent.isDisabled()).isFalse();
        assertThat(staticAgent.isDeleted()).isFalse();

        XmlAssert.assertThat(newConfigFile).doesNotHaveXPath("//agents");
    }

    private TimerConfig createTimerConfigWithAttribute(String valueForOnChangesInTimer) throws Exception {
        final String content = configWithTimerBasedPipeline(valueForOnChangesInTimer);
        CruiseConfig configAfterMigration = migrateConfigAndLoadTheNewConfig(content);

        PipelineConfig pipelineConfig = configAfterMigration.pipelineConfigByName(new CaseInsensitiveString("old-timer"));
        return pipelineConfig.getTimer();
    }

    private String configWithTimerBasedPipeline(String valueForOnChangesInTimer) {
        return ConfigFileFixture.configWithPipeline("<pipeline name='old-timer'>\n"
                + "  <timer " + valueForOnChangesInTimer + ">0 0 1 * * ?</timer>\n"
                + "  <materials>\n"
                + "    <git url='/tmp/git' />\n"
                + "  </materials>\n"
                + "  <stage name='dist'>\n"
                + "    <jobs>\n"
                + "      <job name='test'><tasks><exec command='echo'><runif status='passed' /></exec></tasks></job>\n"
                + "    </jobs>\n"
                + "  </stage>\n"
                + "</pipeline>", 63);
    }

    private CruiseConfig migrateConfigAndLoadTheNewConfig(String content) throws Exception {
        Files.writeString(configFile, content, UTF_8);
        GoConfigHolder configHolder = goConfigMigrator.migrate();
        assert configHolder != null;
        return configHolder.config;
    }

    private CruiseConfig loadConfigFileWithContent(String content) throws Exception {
        Files.writeString(configFile, content, UTF_8);
        goConfigMigrator.migrate();
        return goFileConfigDataSource.forceLoad(configFile).config;
    }

    private void assertConfiguration(Configuration configuration, List<List<?>> expectedKeyValuePair) {
        int position = 0;
        for (ConfigurationProperty configurationProperty : configuration) {
            assertThat(configurationProperty.getConfigurationKey().getName()).isEqualTo(expectedKeyValuePair.get(position).get(0));
            assertThat(configurationProperty.isSecure()).isEqualTo(expectedKeyValuePair.get(position).get(1));
            assertThat(configurationProperty.getConfigurationValue().getValue()).isEqualTo(expectedKeyValuePair.get(position).get(2));
            position++;
        }
    }
}
