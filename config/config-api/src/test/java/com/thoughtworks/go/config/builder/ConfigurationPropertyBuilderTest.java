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
package com.thoughtworks.go.config.builder;

import com.thoughtworks.go.domain.config.ConfigurationProperty;
import com.thoughtworks.go.plugin.api.config.Property;
import com.thoughtworks.go.security.GoCipher;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;

public class ConfigurationPropertyBuilderTest {
    @Test
    public void shouldCreateConfigurationPropertyWithEncyrptedValueForSecureProperty() {
        Property key = new Property("key");
        key.with(Property.SECURE, true);

        ConfigurationProperty property = new ConfigurationPropertyBuilder().create("key", null, "enc_value", true);

        assertThat(property.getConfigKeyName()).isEqualTo("key");
        assertThat(property.getEncryptedValue()).isEqualTo("enc_value");
        assertNull(property.getConfigurationValue());
    }

    @Test
    public void shouldCreateWithEncyrptedValueForOnlyPlainTextInputForSecureProperty() throws Exception {
        Property key = new Property("key");
        key.with(Property.SECURE, true);

        ConfigurationProperty property = new ConfigurationPropertyBuilder().create("key", "value", null, true);

        assertThat(property.getConfigKeyName()).isEqualTo("key");
        assertThat(property.getEncryptedValue()).isEqualTo(new GoCipher().encrypt("value"));
        assertNull(property.getConfigurationValue());
    }

    @Test
    public void shouldCreatePropertyInAbsenceOfPlainAndEncryptedTextInputForSecureProperty() {
        Property key = new Property("key");
        key.with(Property.SECURE, true);

        ConfigurationProperty property = new ConfigurationPropertyBuilder().create("key", null, null, true);

        assertThat(property.errors().size()).isEqualTo(0);
        assertThat(property.getConfigKeyName()).isEqualTo("key");
        assertNull(property.getEncryptedConfigurationValue());
        assertNull(property.getConfigurationValue());
    }

    @Test
    public void shouldCreateWithErrorsIfBothPlainAndEncryptedTextInputAreSpecifiedForSecureProperty() {
        Property key = new Property("key");
        key.with(Property.SECURE, true);

        ConfigurationProperty property = new ConfigurationPropertyBuilder().create("key", "value", "enc_value", true);

        assertThat(property.errors().get("configurationValue").get(0)).isEqualTo("You may only specify `value` or `encrypted_value`, not both!");
        assertThat(property.errors().get("encryptedValue").get(0)).isEqualTo("You may only specify `value` or `encrypted_value`, not both!");
        assertThat(property.getConfigurationValue().getValue()).isEqualTo("value");
        assertThat(property.getEncryptedValue()).isEqualTo("enc_value");
    }

    @Test
    public void shouldCreateWithErrorsIfBothPlainAndEncryptedTextInputAreSpecifiedForUnSecuredProperty() {
        Property key = new Property("key");
        key.with(Property.SECURE, false);

        ConfigurationProperty property = new ConfigurationPropertyBuilder().create("key", "value", "enc_value", false);

        assertThat(property.errors().get("configurationValue").get(0)).isEqualTo("You may only specify `value` or `encrypted_value`, not both!");
        assertThat(property.errors().get("encryptedValue").get(0)).isEqualTo("You may only specify `value` or `encrypted_value`, not both!");
        assertThat(property.getConfigurationValue().getValue()).isEqualTo("value");
        assertThat(property.getEncryptedValue()).isEqualTo("enc_value");
    }

    @Test
    public void shouldCreateWithErrorsInPresenceOfEncryptedTextInputForUnSecuredProperty() {
        Property key = new Property("key");
        key.with(Property.SECURE, false);

        ConfigurationProperty property = new ConfigurationPropertyBuilder().create("key", null, "enc_value", false);

        assertThat(property.errors().get("encryptedValue").get(0)).isEqualTo("encrypted_value cannot be specified to a unsecured property.");
        assertThat(property.getEncryptedValue()).isEqualTo("enc_value");
    }

    @Test
    public void shouldCreateWithValueForAUnsecuredProperty() {
        Property key = new Property("key");
        key.with(Property.SECURE, false);

        ConfigurationProperty property = new ConfigurationPropertyBuilder().create("key", "value", null, false);

        assertThat(property.getConfigurationValue().getValue()).isEqualTo("value");
        assertNull(property.getEncryptedConfigurationValue());
    }
}
