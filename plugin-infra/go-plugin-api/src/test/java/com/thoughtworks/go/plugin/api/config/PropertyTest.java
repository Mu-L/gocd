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
package com.thoughtworks.go.plugin.api.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class PropertyTest {

    @Test
    public void shouldGetOptionValue() {
        Property property = new Property("key");
        property.with(Property.DISPLAY_NAME, "some display name");
        property.with(Property.DISPLAY_ORDER, 3);
        assertThat(property.getOption(Property.DISPLAY_NAME)).isEqualTo("some display name");
        assertThat(property.getOption(Property.DISPLAY_ORDER)).isEqualTo(3);
    }

    @Test
    public void shouldReturnDefaultValueWhenNoValueOrEmptyValueIsSpecified() {
        String defaultValue = "test-default";
        Property property = new Property("key", null, defaultValue);
        assertThat(property.getValue()).isEqualTo(defaultValue);
        property = new Property("key", "", defaultValue);
        assertThat(property.getValue()).isEqualTo("");
        property = new Property("key").withDefault(defaultValue);
        assertThat(property.getValue()).isEqualTo(defaultValue);
        property = new Property("key");
        assertThat(property.getValue()).isEqualTo("");
        String value = "yek";
        property = new Property("key", value, defaultValue);
        assertThat(property.getValue()).isEqualTo(value);
    }


}
