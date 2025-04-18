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
package com.thoughtworks.go.agent.launcher;

import com.thoughtworks.go.agent.common.UrlConstructor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class UrlConstructorTest {
    @Test
    public void shouldGenerateCorrectUrlForGivenPath() {
        UrlConstructor urlConstructor = new UrlConstructor("https://example.com:8443/go/");
        assertThat(urlConstructor.serverUrlFor("")).isEqualTo("https://example.com:8443/go");
        assertThat(urlConstructor.serverUrlFor("foo/bar")).isEqualTo("https://example.com:8443/go/foo/bar");
        assertThat(urlConstructor.serverUrlFor("admin/agent")).isEqualTo("https://example.com:8443/go/admin/agent");
    }
}
