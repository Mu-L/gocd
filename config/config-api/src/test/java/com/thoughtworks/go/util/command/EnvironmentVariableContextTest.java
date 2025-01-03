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
package com.thoughtworks.go.util.command;

import com.thoughtworks.go.util.SerializationTester;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

public class EnvironmentVariableContextTest {

    private static final String PROPERTY_NAME = "PROPERTY_NAME";
    private static final String PROPERTY_VALUE = "property value";
    private static final String NEW_VALUE = "new value";

    @Test
    public void shouldBeAbleToAddProperties() {
        EnvironmentVariableContext context = new EnvironmentVariableContext();
        context.setProperty(PROPERTY_NAME, PROPERTY_VALUE, false);
        assertThat(context.getProperty(PROPERTY_NAME)).isEqualTo(PROPERTY_VALUE);
    }

    @Test
    public void shouldReportLastAddedAsPropertyValue() {
        EnvironmentVariableContext context = new EnvironmentVariableContext();
        context.setProperty(PROPERTY_NAME, PROPERTY_VALUE, false);
        context.setProperty(PROPERTY_NAME, NEW_VALUE, false);
        assertThat(context.getProperty(PROPERTY_NAME)).isEqualTo(NEW_VALUE);
    }

    @Test
    public void shouldReportWhenAVariableIsSet() {
        EnvironmentVariableContext context = new EnvironmentVariableContext();
        context.setProperty(PROPERTY_NAME, PROPERTY_VALUE, false);
        List<String> report = context.report(Collections.emptyList());
        assertThat(report.size()).isEqualTo(1);
        assertThat(report.get(0)).isEqualTo("[go] setting environment variable 'PROPERTY_NAME' to value 'property value'");
    }

    @Test
    public void shouldReportWhenAVariableIsOverridden() {
        EnvironmentVariableContext context = new EnvironmentVariableContext();
        context.setProperty(PROPERTY_NAME, PROPERTY_VALUE, false);
        context.setProperty(PROPERTY_NAME, NEW_VALUE, false);
        List<String> report = context.report(Collections.emptyList());
        assertThat(report.size()).isEqualTo(2);
        assertThat(report.get(0)).isEqualTo("[go] setting environment variable 'PROPERTY_NAME' to value 'property value'");
        assertThat(report.get(1)).isEqualTo("[go] overriding environment variable 'PROPERTY_NAME' with value 'new value'");
    }

    @Test
    public void shouldMaskOverRiddenSecureVariable() {
        EnvironmentVariableContext context = new EnvironmentVariableContext();
        context.setProperty(PROPERTY_NAME, PROPERTY_VALUE, true);
        context.setProperty(PROPERTY_NAME, NEW_VALUE, true);
        List<String> report = context.report(Collections.emptyList());
        assertThat(report.size()).isEqualTo(2);
        assertThat(report.get(0)).isEqualTo(String.format("[go] setting environment variable 'PROPERTY_NAME' to value '%s'", EnvironmentVariableContext.EnvironmentVariable.MASK_VALUE));
        assertThat(report.get(1)).isEqualTo(String.format("[go] overriding environment variable 'PROPERTY_NAME' with value '%s'", EnvironmentVariableContext.EnvironmentVariable.MASK_VALUE));
    }

    @Test
    public void shouldReportSecureVariableAsMaskedValue() {
        EnvironmentVariableContext context = new EnvironmentVariableContext();
        context.setProperty(PROPERTY_NAME, PROPERTY_VALUE, true);
        List<String> report = context.report(Collections.emptyList());
        assertThat(report.size()).isEqualTo(1);
        assertThat(report.get(0)).isEqualTo(String.format("[go] setting environment variable 'PROPERTY_NAME' to value '%s'", EnvironmentVariableContext.EnvironmentVariable.MASK_VALUE));
    }

    @Test
    public void testReportOverrideForProcessEnvironmentVariables() {
        EnvironmentVariableContext context = new EnvironmentVariableContext();
        context.setProperty("PATH", "/foo", false);
        List<String> report = context.report(Set.of("PATH"));
        assertThat(report.size()).isEqualTo(1);
        assertThat(report.get(0)).isEqualTo("[go] overriding environment variable 'PATH' with value '/foo'");
    }

    @Test
    public void shouldBeAbleToSerialize() throws ClassNotFoundException, IOException {
        EnvironmentVariableContext original = new EnvironmentVariableContext("blahKey", "blahValue");
        EnvironmentVariableContext clone = SerializationTester.objectSerializeAndDeserialize(original);
        assertThat(clone).isEqualTo(original);
    }

    @Test
    public void shouldSaveSecureStateAboutAEnvironmentVariable() {
        EnvironmentVariableContext environmentVariableContext = new EnvironmentVariableContext();
        environmentVariableContext.setProperty(PROPERTY_NAME, PROPERTY_VALUE, false);

        assertThat(environmentVariableContext.getProperty(PROPERTY_NAME)).isEqualTo(PROPERTY_VALUE);
        assertThat(environmentVariableContext.getPropertyForDisplay(PROPERTY_NAME)).isEqualTo(PROPERTY_VALUE);
        assertThat(environmentVariableContext.isPropertySecure(PROPERTY_NAME)).isFalse();
    }

    @Test
    public void shouldSaveSecureStateForASecureEnvironmentVariable() {
        EnvironmentVariableContext environmentVariableContext = new EnvironmentVariableContext();
        environmentVariableContext.setProperty(PROPERTY_NAME, PROPERTY_VALUE, true);

        assertThat(environmentVariableContext.getProperty(PROPERTY_NAME)).isEqualTo(PROPERTY_VALUE);
        assertThat(environmentVariableContext.getPropertyForDisplay(PROPERTY_NAME)).isEqualTo(EnvironmentVariableContext.EnvironmentVariable.MASK_VALUE);
        assertThat(environmentVariableContext.isPropertySecure(PROPERTY_NAME)).isTrue();
    }

    @Test
    public void shouldGetSecureEnvironmentVariables() {
        EnvironmentVariableContext environmentVariableContext = new EnvironmentVariableContext();
        environmentVariableContext.setProperty("secure_foo", "secure_foo_value", true);
        environmentVariableContext.setProperty("plain_foo", "plain_foo_value", false);
        List<EnvironmentVariableContext.EnvironmentVariable> secureEnvironmentVariables = environmentVariableContext.getSecureEnvironmentVariables();
        assertThat(secureEnvironmentVariables.size()).isEqualTo(1);
        assertThat(secureEnvironmentVariables).contains(new EnvironmentVariableContext.EnvironmentVariable("secure_foo", "secure_foo_value", true));
    }

}


