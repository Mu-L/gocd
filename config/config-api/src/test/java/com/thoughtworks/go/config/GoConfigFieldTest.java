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

import com.thoughtworks.go.util.SystemEnvironment;
import org.jdom2.Element;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class GoConfigFieldTest {
    public SystemEnvironment systemEnvironment;
    private final ConfigCache configCache = new ConfigCache();

    @Test
    public void shouldConvertFromXmlToJavaObjectCorrectly() throws Exception {
        final Foo object = new Foo();
        final GoConfigFieldWriter field = new GoConfigFieldWriter(Foo.class.getDeclaredField("number"), object, configCache, null);
        final Element element = new Element("foo");
        element.setAttribute("number", "100");
        field.setValueIfNotNull(element, object);
        assertThat(object.number).isEqualTo(100L);
    }

    @Test
    public void shouldConvertFileCorrectly() throws Exception {
        final Foo object = new Foo();
        final GoConfigFieldWriter field = new GoConfigFieldWriter(Foo.class.getDeclaredField("directory"), object, configCache, null);
        final Element element = new Element("foo");
        element.setAttribute("directory", "foo" + File.separator + "dir");
        field.setValueIfNotNull(element, object);
        assertThat(object.directory.getPath()).isEqualTo("foo" + File.separator + "dir");
    }

    @Test
    public void shouldSetFileToNullifValueIsNotSpecified() throws Exception {
        final Foo object = new Foo();
        final GoConfigFieldWriter field = new GoConfigFieldWriter(Foo.class.getDeclaredField("directory"), object, configCache, null);
        final Element element = new Element("foo");
        field.setValueIfNotNull(element, object);
        assertThat(object.directory).isNull();
    }

    @Test
    public void shouldValidateAndConvertOnlyIfAppropriate() {
        assertThrows(RuntimeException.class, () -> {
            final Foo object = new Foo();
            final GoConfigFieldWriter field = new GoConfigFieldWriter(Foo.class.getDeclaredField("number"), object, configCache, null);
            final Element element = new Element("foo");
            element.setAttribute("number", "anything");
            field.setValueIfNotNull(element, object);
        });
    }

    @BeforeEach
    public void setUp() {
        systemEnvironment = new SystemEnvironment();
    }

    private static class Foo {
        @ConfigAttribute("number")
        private Long number;

        @ConfigAttribute(value = "directory", allowNull = true)
        private File directory;
    }
}
