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
package com.thoughtworks.go.server.domain;

import com.google.gson.JsonObject;
import com.thoughtworks.go.domain.Plugin;
import com.thoughtworks.go.domain.config.ConfigurationKey;
import com.thoughtworks.go.domain.config.ConfigurationProperty;
import com.thoughtworks.go.domain.config.ConfigurationValue;
import com.thoughtworks.go.domain.config.EncryptedConfigurationValue;
import com.thoughtworks.go.plugin.access.common.settings.PluginSettingsConfiguration;
import com.thoughtworks.go.plugin.domain.common.Metadata;
import com.thoughtworks.go.plugin.domain.common.PluggableInstanceSettings;
import com.thoughtworks.go.plugin.domain.common.PluginConfiguration;
import com.thoughtworks.go.plugin.domain.common.PluginInfo;
import com.thoughtworks.go.plugin.domain.configrepo.Capabilities;
import com.thoughtworks.go.plugin.domain.configrepo.ConfigRepoPluginInfo;
import com.thoughtworks.go.plugin.domain.elastic.ElasticAgentPluginInfo;
import com.thoughtworks.go.security.CryptoException;
import com.thoughtworks.go.security.GoCipher;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PluginSettingsTest {
    public static final String PLUGIN_ID = "plugin-id";

    @Test
    public void shouldGetSettingsMapAsKeyValueMap() {
        final ElasticAgentPluginInfo elasticAgentPluginInfo = mock(ElasticAgentPluginInfo.class);
        when(elasticAgentPluginInfo.getPluginSettings()).thenReturn(mock(PluggableInstanceSettings.class));

        final PluginSettings pluginSettings = PluginSettings.from(getPlugin(PLUGIN_ID), elasticAgentPluginInfo);

        Map<String, String> settingsAsKeyValuePair = pluginSettings.getSettingsAsKeyValuePair();
        assertThat(settingsAsKeyValuePair.size()).isEqualTo(3);
        assertThat(settingsAsKeyValuePair.get("key-1")).isEqualTo("value1");
        assertThat(settingsAsKeyValuePair.get("key-2")).isEqualTo("");
        assertThat(settingsAsKeyValuePair.get("key-3")).isEqualTo("");
    }

    @Test
    public void shouldPopulateSettingsMapWithErrorsCorrectly() {
        final ElasticAgentPluginInfo elasticAgentPluginInfo = mock(ElasticAgentPluginInfo.class);
        when(elasticAgentPluginInfo.getPluginSettings()).thenReturn(mock(PluggableInstanceSettings.class));

        final PluginSettings pluginSettings = PluginSettings.from(getPlugin(PLUGIN_ID), elasticAgentPluginInfo);

        pluginSettings.populateErrorMessageFor("key-1", "e1");

        assertThat(pluginSettings.getErrorFor("key-1")).isEqualTo(List.of("e1"));
    }

    @Test
    public void shouldPopulateHasErrorsCorrectly() {
        PluginSettings pluginSettings = new PluginSettings(PLUGIN_ID);
        assertThat(pluginSettings.hasErrors()).isFalse();

        pluginSettings.populateErrorMessageFor("k1", "e1");
        assertThat(pluginSettings.hasErrors()).isTrue();
    }

    @Test
    public void shouldCreatePluginConfigurationFromPluginSettingsCorrectly() {
        final ElasticAgentPluginInfo elasticAgentPluginInfo = mock(ElasticAgentPluginInfo.class);
        when(elasticAgentPluginInfo.getPluginSettings()).thenReturn(mock(PluggableInstanceSettings.class));

        final PluginSettings pluginSettings = PluginSettings.from(getPlugin(PLUGIN_ID), elasticAgentPluginInfo);

        PluginSettingsConfiguration configuration = pluginSettings.toPluginSettingsConfiguration();

        assertThat(configuration.size()).isEqualTo(3);
        assertThat(configuration.get("key-1").getValue()).isEqualTo("value1");
        assertThat(configuration.get("key-2").getValue()).isEqualTo("");
        assertThat(configuration.get("key-3").getValue()).isEqualTo("");
    }

    @Test
    public void shouldAddConfigurationsToSettingsMapCorrectly() throws CryptoException {
        List<PluginConfiguration> pluginConfigurations = new ArrayList<>();
        pluginConfigurations.add(new PluginConfiguration("k1", new Metadata(true, false)));
        pluginConfigurations.add(new PluginConfiguration("k2", new Metadata(true, true)));
        ConfigRepoPluginInfo pluginInfo = new ConfigRepoPluginInfo(null, null, new PluggableInstanceSettings(pluginConfigurations), new Capabilities());

        List<ConfigurationProperty> configurationProperties = new ArrayList<>();
        configurationProperties.add(new ConfigurationProperty(new ConfigurationKey("k1"), new ConfigurationValue("v1")));
        configurationProperties.add(new ConfigurationProperty(new ConfigurationKey("k2"), new EncryptedConfigurationValue(new GoCipher().encrypt("v2"))));

        PluginSettings pluginSettings = new PluginSettings(PLUGIN_ID);
        pluginSettings.addConfigurations(pluginInfo, configurationProperties);

        PluginSettingsConfiguration pluginSettingsProperties = pluginSettings.toPluginSettingsConfiguration();
        assertThat(pluginSettingsProperties.size()).isEqualTo(2);
        assertThat(pluginSettingsProperties.get("k1").getValue()).isEqualTo("v1");
        assertThat(pluginSettingsProperties.get("k2").getValue()).isEqualTo("v2");
    }

    @Test
    public void shouldEncryptedValuesForSecureProperties() throws CryptoException {
        List<PluginConfiguration> pluginConfigurations = new ArrayList<>();
        pluginConfigurations.add(new PluginConfiguration("k1", new Metadata(true, false)));
        pluginConfigurations.add(new PluginConfiguration("k2", new Metadata(true, true)));
        ConfigRepoPluginInfo pluginInfo = new ConfigRepoPluginInfo(null, null, new PluggableInstanceSettings(pluginConfigurations), new Capabilities());

        ConfigurationProperty configProperty1 = new ConfigurationProperty(new ConfigurationKey("k1"), new ConfigurationValue("v1"));
        ConfigurationProperty configProperty2 = new ConfigurationProperty(new ConfigurationKey("k2"), new EncryptedConfigurationValue(new GoCipher().encrypt("v2")));
        List<ConfigurationProperty> configurationProperties = new ArrayList<>();
        configurationProperties.add(configProperty1);
        configurationProperties.add(configProperty2);

        PluginSettings pluginSettings = new PluginSettings(PLUGIN_ID);
        pluginSettings.addConfigurations(pluginInfo, configurationProperties);

        List<ConfigurationProperty> pluginSettingsProperties = pluginSettings.getPluginSettingsProperties();
        assertThat(pluginSettingsProperties.size()).isEqualTo(2);
        assertThat(pluginSettingsProperties.get(0)).isEqualTo(configProperty1);
        assertThat(pluginSettingsProperties.get(1)).isEqualTo(configProperty2);
    }

    @Test
    public void shouldValidateThatEncryptedVariablesAreEncryptedWithTheCorrectCipher() {
        final PluginInfo pluginInfo = mock(PluginInfo.class);
        String secureKey = "supposedly-secure-key";
        when(pluginInfo.isSecure(secureKey)).thenReturn(true);

        PluginSettings pluginSettings = new PluginSettings(PLUGIN_ID);
        pluginSettings.addConfigurations(pluginInfo, List.of(new ConfigurationProperty(new ConfigurationKey(secureKey), new EncryptedConfigurationValue("value_encrypted_by_a_different_cipher"))));
        pluginSettings.validateTree();

        assertThat(pluginSettings.hasErrors()).isTrue();
        assertThat(pluginSettings.getPluginSettingsProperties().get(0).errors().firstError()).isEqualTo("Encrypted value for property with key 'supposedly-secure-key' is invalid. This usually happens when the cipher text is modified to have an invalid value.");
    }

    @Test
    public void shouldPassValidationIfEncryptedVariablesAreEncryptedWithTheCorrectCipher() throws CryptoException {
        final PluginInfo pluginInfo = mock(PluginInfo.class);
        String secureKey = "supposedly-secure-key";
        when(pluginInfo.isSecure(secureKey)).thenReturn(true);

        PluginSettings pluginSettings = new PluginSettings(PLUGIN_ID);
        pluginSettings.addConfigurations(pluginInfo, List.of(new ConfigurationProperty(new ConfigurationKey(secureKey), new EncryptedConfigurationValue(new GoCipher().encrypt("secure")))));
        pluginSettings.validateTree();

        assertThat(pluginSettings.hasErrors()).isFalse();
    }

    private Plugin getPlugin(String pluginId) {
        final Plugin plugin = new Plugin(pluginId, getPluginConfigurationJson().toString());
        plugin.setId(1L);
        return plugin;
    }

    private JsonObject getPluginConfigurationJson() {
        final JsonObject configuration = new JsonObject();
        configuration.addProperty("key-1", "value1");
        configuration.addProperty("key-2", "");
        configuration.addProperty("key-3", (String) null);
        return configuration;
    }
}
