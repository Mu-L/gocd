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
package com.thoughtworks.go.domain.packagerepository;

import com.thoughtworks.go.config.BasicCruiseConfig;
import com.thoughtworks.go.config.ConfigSaveValidationContext;
import com.thoughtworks.go.config.SecretParam;
import com.thoughtworks.go.config.helper.ConfigurationHolder;
import com.thoughtworks.go.domain.config.*;
import com.thoughtworks.go.plugin.access.packagematerial.PackageConfiguration;
import com.thoughtworks.go.plugin.access.packagematerial.PackageConfigurations;
import com.thoughtworks.go.plugin.access.packagematerial.PackageMetadataStore;
import com.thoughtworks.go.plugin.access.packagematerial.RepositoryMetadataStore;
import com.thoughtworks.go.security.GoCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.thoughtworks.go.domain.packagerepository.ConfigurationPropertyMother.create;
import static com.thoughtworks.go.plugin.access.packagematerial.PackageConfiguration.PART_OF_IDENTITY;
import static com.thoughtworks.go.plugin.access.packagematerial.PackageConfiguration.SECURE;
import static org.assertj.core.api.Assertions.assertThat;

class PackageRepositoryTest extends PackageMaterialTestBase {

    @BeforeEach
    void setUp() {
        RepositoryMetadataStoreHelper.clear();
    }

    @Test
    void shouldCheckEqualityOfPackageRepository() {
        Configuration configuration = new Configuration();
        Packages packages = new Packages(new PackageDefinition());
        PackageRepository packageRepository = createPackageRepository("plugin-id", "version", "id", "name", configuration, packages);
        assertThat(packageRepository).isEqualTo(createPackageRepository("plugin-id", "version", "id", "name", configuration, packages));
    }

    @Test
    void shouldCheckForFieldAssignments() {
        Configuration configuration = new Configuration();
        Packages packages = new Packages(new PackageDefinition());
        PackageRepository packageRepository = createPackageRepository("plugin-id", "version", "id", "name", configuration, packages);
        assertThat(packageRepository.getPluginConfiguration().getId()).isEqualTo("plugin-id");
        assertThat(packageRepository.getPluginConfiguration().getVersion()).isEqualTo("version");
        assertThat(packageRepository.getId()).isEqualTo("id");
        assertThat(packageRepository.getName()).isEqualTo("name");
    }

    @Test
    void shouldSetRepositoryOnAllAssociatedPackages() {
        Configuration configuration = new Configuration();
        PackageDefinition packageDefinition = new PackageDefinition();
        PackageRepository packageRepository = createPackageRepository("plugin-id", "version", "id", "name", configuration, new Packages(packageDefinition));
        packageRepository.setRepositoryReferenceOnPackages();
        assertThat(packageDefinition.getRepository()).isEqualTo(packageRepository);
    }

    @Test
    void shouldOnlyDisplayFieldsWhichAreNonSecureAndPartOfIdentityInGetConfigForDisplayWhenPluginExists() {
        PackageConfigurations repositoryConfiguration = new PackageConfigurations();
        repositoryConfiguration.addConfiguration(new PackageConfiguration("key1").with(PART_OF_IDENTITY, true).with(SECURE, false));
        repositoryConfiguration.addConfiguration(new PackageConfiguration("key2").with(PART_OF_IDENTITY, false).with(SECURE, false));
        repositoryConfiguration.addConfiguration(new PackageConfiguration("key3").with(PART_OF_IDENTITY, true).with(SECURE, true));
        repositoryConfiguration.addConfiguration(new PackageConfiguration("key4").with(PART_OF_IDENTITY, false).with(SECURE, true));
        repositoryConfiguration.addConfiguration(new PackageConfiguration("key5").with(PART_OF_IDENTITY, true).with(SECURE, false));
        RepositoryMetadataStore.getInstance().addMetadataFor("plugin1", repositoryConfiguration);

        Configuration configuration = new Configuration(create("key1", false, "value1"), create("key2", false, "value2"), create("key3", true, "value3"), create("key4", true, "value4"), create("key5", false, "value5"));
        PackageRepository repository = PackageRepositoryMother.create("repo1", "repo1-name", "plugin1", "1", configuration);

        assertThat(repository.getConfigForDisplay()).isEqualTo("Repository: [key1=value1, key5=value5]");
    }

    @Test
    void shouldConvertKeysToLowercaseInGetConfigForDisplay() {
        RepositoryMetadataStore.getInstance().addMetadataFor("some-plugin", new PackageConfigurations());
        PackageMetadataStore.getInstance().addMetadataFor("some-plugin", new PackageConfigurations());

        Configuration configuration = new Configuration(create("kEY1", false, "vALue1"), create("KEY_MORE_2", false, "VALUE_2"), create("key_3", false, "value3"));
        PackageRepository repository = PackageRepositoryMother.create("repo1", "repo1-name", "some-plugin", "1", configuration);

        assertThat(repository.getConfigForDisplay()).isEqualTo("Repository: [key1=vALue1, key_more_2=VALUE_2, key_3=value3]");
    }

    @Test
    void shouldNotDisplayEmptyValuesInGetConfigForDisplay() {
        RepositoryMetadataStore.getInstance().addMetadataFor("some-plugin", new PackageConfigurations());
        PackageMetadataStore.getInstance().addMetadataFor("some-plugin", new PackageConfigurations());

        Configuration configuration = new Configuration(create("rk1", false, ""), create("rk2", false, "some-non-empty-value"), create("rk3", false, null));
        PackageRepository repository = PackageRepositoryMother.create("repo-id", "repo", "some-plugin", "version", configuration);

        assertThat(repository.getConfigForDisplay()).isEqualTo("Repository: [rk2=some-non-empty-value]");
    }

    @Test
    void shouldDisplayAllNonSecureFieldsInGetConfigForDisplayWhenPluginDoesNotExist() {
        Configuration configuration = new Configuration(create("key1", false, "value1"), create("key2", true, "value2"), create("key3", false, "value3"));
        PackageRepository repository = PackageRepositoryMother.create("repo1", "repo1-name", "some-plugin-which-does-not-exist", "1", configuration);

        assertThat(repository.getConfigForDisplay()).isEqualTo("WARNING! Plugin missing for Repository: [key1=value1, key3=value3]");
    }

    @Test
    void shouldMakeConfigurationSecureBasedOnMetadata() throws Exception {
        GoCipher goCipher = new GoCipher();

        /*secure property is set based on metadata*/
        ConfigurationProperty secureProperty = new ConfigurationProperty(new ConfigurationKey("key1"), new ConfigurationValue("value1"), null, goCipher);
        ConfigurationProperty nonSecureProperty = new ConfigurationProperty(new ConfigurationKey("key2"), new ConfigurationValue("value2"), null, goCipher);
        PackageDefinition packageDefinition = new PackageDefinition("go", "name", new Configuration(secureProperty, nonSecureProperty));

        //meta data of package
        PackageConfigurations packageConfigurations = new PackageConfigurations();
        packageConfigurations.addConfiguration(new PackageConfiguration("key1").with(SECURE, true));
        packageConfigurations.addConfiguration(new PackageConfiguration("key2").with(SECURE, false));
        PackageMetadataStore.getInstance().addMetadataFor("plugin-id", packageConfigurations);


        /*secure property is set based on metadata*/
        ConfigurationProperty secureRepoProperty = new ConfigurationProperty(new ConfigurationKey("key1"), new ConfigurationValue("value1"), null, goCipher);
        ConfigurationProperty nonSecureRepoProperty = new ConfigurationProperty(new ConfigurationKey("key2"), new ConfigurationValue("value2"), null, goCipher);
        PackageRepository packageRepository = createPackageRepository("plugin-id", "version", "id", "name",
                new Configuration(secureRepoProperty, nonSecureRepoProperty),
                new Packages(packageDefinition));

        //meta data of repo
        PackageConfigurations repositoryConfiguration = new PackageConfigurations();
        repositoryConfiguration.addConfiguration(new PackageConfiguration("key1").with(SECURE, true));
        repositoryConfiguration.addConfiguration(new PackageConfiguration("key2").with(SECURE, false));
        RepositoryMetadataStore.getInstance().addMetadataFor("plugin-id", repositoryConfiguration);


        packageRepository.applyPackagePluginMetadata();


        //assert package properties
        assertThat(secureProperty.isSecure()).isTrue();
        assertThat(secureProperty.getEncryptedConfigurationValue()).isNotNull();
        assertThat(secureProperty.getEncryptedValue()).isEqualTo(goCipher.encrypt("value1"));

        assertThat(nonSecureProperty.isSecure()).isFalse();
        assertThat(nonSecureProperty.getValue()).isEqualTo("value2");

        //assert repository properties
        assertThat(secureRepoProperty.isSecure()).isTrue();
        assertThat(secureRepoProperty.getEncryptedConfigurationValue()).isNotNull();
        assertThat(secureRepoProperty.getEncryptedValue()).isEqualTo(goCipher.encrypt("value1"));

        assertThat(nonSecureRepoProperty.isSecure()).isFalse();
        assertThat(nonSecureRepoProperty.getValue()).isEqualTo("value2");
    }

    @Test
    void shouldNotUpdateSecurePropertyWhenPluginIsMissing() {
        GoCipher goCipher = new GoCipher();
        ConfigurationProperty secureProperty = new ConfigurationProperty(new ConfigurationKey("key1"), null, new EncryptedConfigurationValue("value"), goCipher);
        ConfigurationProperty nonSecureProperty = new ConfigurationProperty(new ConfigurationKey("key2"), new ConfigurationValue("value2"), null, goCipher);
        PackageDefinition packageDefinition = new PackageDefinition("go", "name", new Configuration(secureProperty, nonSecureProperty));

        ConfigurationProperty nonSecureRepoProperty = new ConfigurationProperty(new ConfigurationKey("key1"), new ConfigurationValue("value1"), null, goCipher);
        ConfigurationProperty secureRepoProperty = new ConfigurationProperty(new ConfigurationKey("key2"), null, new EncryptedConfigurationValue("value"), goCipher);
        PackageRepository packageRepository = createPackageRepository("plugin-id", "version", "id", "name", new Configuration(secureRepoProperty, nonSecureRepoProperty),
                new Packages(packageDefinition));

        packageRepository.applyPackagePluginMetadata();

        assertThat(secureProperty.getEncryptedConfigurationValue()).isNotNull();
        assertThat(secureProperty.getConfigurationValue()).isNull();

        assertThat(nonSecureProperty.getConfigurationValue()).isNotNull();
        assertThat(nonSecureProperty.getEncryptedConfigurationValue()).isNull();

        assertThat(secureRepoProperty.getEncryptedConfigurationValue()).isNotNull();
        assertThat(secureRepoProperty.getConfigurationValue()).isNull();

        assertThat(nonSecureRepoProperty.getConfigurationValue()).isNotNull();
        assertThat(nonSecureRepoProperty.getEncryptedConfigurationValue()).isNull();
    }

    @Test
    void shouldSetConfigAttributesAsAvailable() throws Exception {
        //metadata setup
        PackageConfigurations repositoryConfiguration = new PackageConfigurations();
        repositoryConfiguration.add(new PackageConfiguration("url"));
        repositoryConfiguration.add(new PackageConfiguration("username"));
        repositoryConfiguration.add(new PackageConfiguration("password").with(SECURE, true));
        repositoryConfiguration.add(new PackageConfiguration("secureKeyNotChanged").with(SECURE, true));
        RepositoryMetadataStore.getInstance().addMetadataFor("yum", repositoryConfiguration);

        String name = "go-server";
        String repoId = "repo-id";
        String pluginId = "yum";
        ConfigurationHolder url = new ConfigurationHolder("url", "http://test.com");
        ConfigurationHolder username = new ConfigurationHolder("username", "user");
        String oldEncryptedValue = "oldEncryptedValue";
        ConfigurationHolder password = new ConfigurationHolder("password", "pass", oldEncryptedValue, true, "1");
        ConfigurationHolder secureKeyNotChanged = new ConfigurationHolder("secureKeyNotChanged", "pass", oldEncryptedValue, true, "0");
        Map<String, Object> attributes = createPackageRepositoryConfiguration(name, pluginId, repoId, url, username, password, secureKeyNotChanged);

        PackageRepository packageRepository = new PackageRepository();
        Packages packages = new Packages();
        packageRepository.setPackages(packages);


        packageRepository.setConfigAttributes(attributes);

        assertThat(packageRepository.getName()).isEqualTo(name);
        assertThat(packageRepository.getId()).isEqualTo(repoId);
        assertThat(packageRepository.getPluginConfiguration().getId()).isEqualTo(pluginId);

        assertThat(packageRepository.getConfiguration().get(0).getConfigurationKey().getName()).isEqualTo(url.name);
        assertThat(packageRepository.getConfiguration().get(0).getConfigurationValue().getValue()).isEqualTo(url.value);

        assertThat(packageRepository.getConfiguration().get(1).getConfigurationKey().getName()).isEqualTo(username.name);
        assertThat(packageRepository.getConfiguration().get(1).getConfigurationValue().getValue()).isEqualTo(username.value);

        assertThat(packageRepository.getConfiguration().get(2).getConfigurationKey().getName()).isEqualTo(password.name);
        assertThat(packageRepository.getConfiguration().get(2).getEncryptedValue()).isEqualTo(new GoCipher().encrypt(password.value));
        assertThat(packageRepository.getConfiguration().get(2).getConfigurationValue()).isNull();

        assertThat(packageRepository.getConfiguration().get(3).getConfigurationKey().getName()).isEqualTo(secureKeyNotChanged.name);
        assertThat(packageRepository.getConfiguration().get(3).getEncryptedValue()).isEqualTo(oldEncryptedValue);
        assertThat(packageRepository.getConfiguration().get(3).getConfigurationValue()).isNull();

        assertThat(packages).isSameAs(packageRepository.getPackages());
    }

    @Test
    void shouldValidateIfNameIsMissing() {
        PackageRepository packageRepository = new PackageRepository();
        packageRepository.validate(new ConfigSaveValidationContext(new BasicCruiseConfig(), null));
        assertThat(packageRepository.errors().getAllOn("name")).isEqualTo(List.of("Please provide name"));
    }

    @Test
    void shouldAddPackageDefinitionToRepo() {
        PackageRepository repository = PackageRepositoryMother.create("repo1");
        String existingPackageId = repository.getPackages().get(0).getId();
        PackageDefinition pkg = PackageDefinitionMother.create("pkg");

        repository.addPackage(pkg);

        assertThat(repository.getPackages().size()).isEqualTo(2);
        assertThat(repository.getPackages().get(0).getId()).isEqualTo(existingPackageId);
        assertThat(repository.getPackages().get(1).getId()).isEqualTo(pkg.getId());
    }

    @Test
    void shouldFindPackageById() {
        PackageRepository repository = PackageRepositoryMother.create("repo-id2", "repo2", "plugin-id", "1.0", null);
        PackageDefinition p1 = PackageDefinitionMother.create("id1", "pkg1", null, repository);
        PackageDefinition p2 = PackageDefinitionMother.create("id2", "pkg2", null, repository);
        Packages packages = new Packages(p1, p2);
        repository.setPackages(packages);
        assertThat(repository.findPackage("id2")).isEqualTo(p2);
    }

    @Test
    void shouldClearConfigurationsWhichAreEmptyAndNoErrors() {
        PackageRepository packageRepository = new PackageRepository();
        packageRepository.getConfiguration().add(new ConfigurationProperty(new ConfigurationKey("name-one"), new ConfigurationValue()));
        packageRepository.getConfiguration().add(new ConfigurationProperty(new ConfigurationKey("name-two"), new EncryptedConfigurationValue()));
        packageRepository.getConfiguration().add(new ConfigurationProperty(new ConfigurationKey("name-three"), null, new EncryptedConfigurationValue(), null));

        ConfigurationProperty configurationProperty = new ConfigurationProperty(new ConfigurationKey("name-four"), null, new EncryptedConfigurationValue(), null);
        configurationProperty.addErrorAgainstConfigurationValue("error");
        packageRepository.getConfiguration().add(configurationProperty);

        packageRepository.clearEmptyConfigurations();

        assertThat(packageRepository.getConfiguration().size()).isEqualTo(1);
        assertThat(packageRepository.getConfiguration().get(0).getConfigurationKey().getName()).isEqualTo("name-four");

    }

    @Test
    void shouldValidateName() {
        PackageRepository packageRepository = new PackageRepository();
        packageRepository.setName("some name");
        packageRepository.validate(new ConfigSaveValidationContext(null));
        assertThat(packageRepository.errors().isEmpty()).isFalse();
        assertThat(packageRepository.errors().getAllOn(PackageRepository.NAME).get(0)).isEqualTo("Invalid PackageRepository name 'some name'. This must be alphanumeric and can contain underscores, hyphens and periods (however, it cannot start with a period). The maximum allowed length is 255 characters.");
    }

    @Test
    void shouldRemoveGivenPackageFromTheRepository() {
        PackageDefinition packageDefinitionOne = new PackageDefinition("pid1", "pname1", null);
        PackageDefinition packageDefinitionTwo = new PackageDefinition("pid2", "pname2", null);
        PackageRepository packageRepository = new PackageRepository();
        packageRepository.addPackage(packageDefinitionOne);
        packageRepository.addPackage(packageDefinitionTwo);
        packageRepository.removePackage("pid1");

        assertThat(packageRepository.getPackages().size()).isEqualTo(1);
        assertThat(packageRepository.getPackages()).contains(packageDefinitionTwo);
    }

    @Test
    void shouldThrowErrorWhenGivenPackageNotFoundDuringRemove() {
        PackageRepository packageRepository = new PackageRepository();
        try {
            packageRepository.removePackage("invalid");
        } catch (RuntimeException e) {
            assertThat(e.getMessage()).isEqualTo("Could not find package with id:[invalid]");
        }
    }

    @Test
    void shouldFindPackageDefinitionBasedOnParams() {
        PackageRepository packageRepository = PackageRepositoryMother.create("repo-id1", "packageRepository", "plugin-id", "1.0", null);
        PackageDefinition packageDefinitionOne = PackageDefinitionMother.create("pid1", packageRepository);
        PackageDefinition packageDefinitionTwo = PackageDefinitionMother.create("pid2", packageRepository);
        packageRepository.getPackages().addAll(List.of(packageDefinitionOne, packageDefinitionTwo));

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("packageId", "pid1");

        PackageDefinition actualPackageDefinition = packageRepository.findOrCreatePackageDefinition(attributes);
        assertThat(actualPackageDefinition).isEqualTo(packageDefinitionOne);
    }

    @Test
    void shouldCreatePackageBasedOnParams() {
        PackageRepository packageRepository = PackageRepositoryMother.create("repo-id1", "packageRepository", "plugin-id", "1.0", null);
        Map<String, Object> packageDefAttr = createPackageDefinitionConfiguration("package_name", "pluginId", new ConfigurationHolder("key1", "value1"), new ConfigurationHolder("key2", "value2"));
        Map<String, Object> map = new HashMap<>();
        map.put("package_definition", packageDefAttr);
        PackageDefinition actualPackageDefinition = packageRepository.findOrCreatePackageDefinition(map);
        assertThat(actualPackageDefinition).isEqualTo(PackageDefinitionMother.create(null, "package_name",
                new Configuration(create("key1", false, "value1"), create("key2", false, "value2")), packageRepository));
        assertThat(actualPackageDefinition.getRepository()).isEqualTo(packageRepository);
    }

    @Test
    void shouldValidateUniqueNames() {
        PackageRepository packageRepository = new PackageRepository();
        packageRepository.setName("REPO");
        Map<String, PackageRepository> nameMap = new HashMap<>();
        PackageRepository original = new PackageRepository();
        original.setName("repo");
        nameMap.put("repo", original);
        packageRepository.validateNameUniqueness(nameMap);
        assertThat(packageRepository.errors().getAllOn(PackageRepository.NAME).contains("You have defined multiple repositories called 'REPO'. Repository names are case-insensitive and must be unique.")).isTrue();
        assertThat(original.errors().getAllOn(PackageRepository.NAME).contains("You have defined multiple repositories called 'REPO'. Repository names are case-insensitive and must be unique.")).isTrue();
    }

    @Test
    void shouldValidateUniqueKeysInConfiguration() {
        ConfigurationProperty one = new ConfigurationProperty(new ConfigurationKey("one"), new ConfigurationValue("value1"));
        ConfigurationProperty duplicate1 = new ConfigurationProperty(new ConfigurationKey("ONE"), new ConfigurationValue("value2"));
        ConfigurationProperty duplicate2 = new ConfigurationProperty(new ConfigurationKey("ONE"), new ConfigurationValue("value3"));
        ConfigurationProperty two = new ConfigurationProperty(new ConfigurationKey("two"), new ConfigurationValue());
        PackageRepository repository = new PackageRepository();
        repository.setConfiguration(new Configuration(one, duplicate1, duplicate2, two));
        repository.setName("yum");

        repository.validate(null);
        assertThat(one.errors().isEmpty()).isFalse();
        assertThat(one.errors().getAllOn(ConfigurationProperty.CONFIGURATION_KEY).contains("Duplicate key 'ONE' found for Repository 'yum'")).isTrue();
        assertThat(duplicate1.errors().isEmpty()).isFalse();
        assertThat(one.errors().getAllOn(ConfigurationProperty.CONFIGURATION_KEY).contains("Duplicate key 'ONE' found for Repository 'yum'")).isTrue();
        assertThat(duplicate2.errors().isEmpty()).isFalse();
        assertThat(one.errors().getAllOn(ConfigurationProperty.CONFIGURATION_KEY).contains("Duplicate key 'ONE' found for Repository 'yum'")).isTrue();
        assertThat(two.errors().isEmpty()).isTrue();
    }

    @Test
    void shouldGenerateIdIfNotAssigned() {
        PackageRepository packageRepository = new PackageRepository();
        packageRepository.ensureIdExists();
        assertThat(packageRepository.getId()).isNotNull();

        packageRepository = new PackageRepository();
        packageRepository.setId("id");
        packageRepository.ensureIdExists();
        assertThat(packageRepository.getId()).isEqualTo("id");
    }

    @Nested
    class HasSecretParams {
        @Test
        void shouldBeTrueIfPkgRepoHasSecretParam() {
            ConfigurationProperty k1 = ConfigurationPropertyMother.create("k1", false, "{{SECRET:[secret_config_id][lookup_password]}}");
            ConfigurationProperty k2 = ConfigurationPropertyMother.create("k2", false, "v2");
            PackageRepository pkgRepo = new PackageRepository("pkg-repo-id", "pkg-repo-name", new PluginConfiguration(), new Configuration(k1, k2));

            assertThat(pkgRepo.hasSecretParams()).isTrue();
        }

        @Test
        void shouldBeFalseIfPkgRepoDoesNotHaveSecretParams() {
            PackageRepository pkgRepo = new PackageRepository();

            assertThat(pkgRepo.hasSecretParams()).isFalse();
        }
    }

    @Nested
    class GetSecretParams {
        @Test
        void shouldReturnAListOfSecretParams() {
            ConfigurationProperty k1 = ConfigurationPropertyMother.create("k1", false, "{{SECRET:[secret_config_id][lookup_username]}}");
            ConfigurationProperty k2 = ConfigurationPropertyMother.create("k2", false, "{{SECRET:[secret_config_id][lookup_password]}}");
            PackageRepository pkgRepo = new PackageRepository("pkg-repo-id", "pkg-repo-name", new PluginConfiguration(), new Configuration(k1, k2));

            assertThat(pkgRepo.getSecretParams().size()).isEqualTo(2);
            assertThat(pkgRepo.getSecretParams().get(0)).isEqualTo(new SecretParam("secret_config_id", "lookup_username"));
            assertThat(pkgRepo.getSecretParams().get(1)).isEqualTo(new SecretParam("secret_config_id", "lookup_password"));
        }

        @Test
        void shouldBeAnEmptyListInAbsenceOfSecretParamsInPkgRepo() {
            PackageRepository pkgRepo = new PackageRepository();

            assertThat(pkgRepo.getSecretParams()).isEmpty();
        }
    }

    private PackageRepository createPackageRepository(String pluginId, String version, String id, String name, Configuration configuration, Packages packages) {
        PackageRepository packageRepository = new PackageRepository();
        packageRepository.setConfiguration(configuration);
        packageRepository.setPackages(packages);
        packageRepository.setPluginConfiguration(new PluginConfiguration(pluginId, version));
        packageRepository.setId(id);
        packageRepository.setName(name);
        return packageRepository;
    }

    private Map<String, Object> createPackageRepositoryConfiguration(String name, String pluginId, String repoId, ConfigurationHolder... configurations) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(PackageRepository.NAME, name);
        attributes.put(PackageRepository.REPO_ID, repoId);

        Map<String, Object> pluginConfiguration = new HashMap<>();
        pluginConfiguration.put(PluginConfiguration.ID, pluginId);
        attributes.put(PackageRepository.PLUGIN_CONFIGURATION, pluginConfiguration);

        createPackageConfigurationsFor(attributes, configurations);
        return attributes;
    }
}
