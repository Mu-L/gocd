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
package com.thoughtworks.go.plugin.configrepo.contract;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

public class CRPluggableArtifactTest extends AbstractCRTest<CRPluggableArtifact> {
    private CRPluggableArtifact validArtifactWithNoConfiguration;
    private CRPluggableArtifact validArtifactWithConfiguration;
    private CRPluggableArtifact invalidArtifactWithNoId;
    private CRPluggableArtifact invalidArtifactWithNoStoreId;
    private CRPluggableArtifact invalidArtifactWithInvalidConfiguration;

    public CRPluggableArtifactTest() {
        validArtifactWithNoConfiguration = new CRPluggableArtifact("id", "storeId", null);
        validArtifactWithConfiguration = new CRPluggableArtifact("id", "storeId", List.of(new CRConfigurationProperty("foo", "bar")));

        invalidArtifactWithNoId = new CRPluggableArtifact(null, "storeId", null);
        invalidArtifactWithNoStoreId = new CRPluggableArtifact("id", null, null);
        invalidArtifactWithInvalidConfiguration = new CRPluggableArtifact("id", "storeId", List.of(new CRConfigurationProperty("foo", "bar", "baz")));
    }

    @Override
    public void addGoodExamples(Map<String, CRPluggableArtifact> examples) {
        examples.put("validArtifactWithNoConfiguration", validArtifactWithNoConfiguration);
        examples.put("validArtifactWithConfiguration", validArtifactWithConfiguration);
    }

    @Override
    public void addBadExamples(Map<String, CRPluggableArtifact> examples) {
        examples.put("invalidArtifactWithNoId", invalidArtifactWithNoId);
        examples.put("invalidArtifactWithNoStoreId", invalidArtifactWithNoStoreId);
        examples.put("invalidArtifactWithInvalidConfiguration", invalidArtifactWithInvalidConfiguration);
    }

    @Test
    public void shouldDeserializeWhenConfigurationIsNull() {
        String json = """
                {
                           "id" : "id",
                           "store_id" : "s3",
                           "type": "external"
                            }""";

        CRPluggableArtifact crPluggableArtifact = gson.fromJson(json, CRPluggableArtifact.class);

        assertThat(crPluggableArtifact.getId()).isEqualTo("id");
        assertThat(crPluggableArtifact.getStoreId()).isEqualTo("s3");
        assertThat(crPluggableArtifact.getType()).isEqualTo(CRArtifactType.external);
        assertNull(crPluggableArtifact.getConfiguration());
    }


    @Test
    public void shouldCheckForTypeWhileDeserializing() {
        String json = """
                {
                           "id" : "id",
                           "store_id" : "s3"
                            }""";

        CRPluggableArtifact crPluggableArtifact = gson.fromJson(json, CRPluggableArtifact.class);

        assertThat(crPluggableArtifact.getId()).isEqualTo("id");
        assertThat(crPluggableArtifact.getStoreId()).isEqualTo("s3");
        assertNull(crPluggableArtifact.getType());
        assertNull(crPluggableArtifact.getConfiguration());

        assertFalse(crPluggableArtifact.getErrors().isEmpty());
    }

    @Test
    public void shouldDeserializePluggableArtifacts() {
        String json = """
                {
                              "id" : "id",
                              "store_id" : "s3",
                              "type": "external",
                              "configuration": [{"key":"image", "value": "gocd-agent"}]            }""";

        CRPluggableArtifact crPluggableArtifact = gson.fromJson(json, CRPluggableArtifact.class);

        assertThat(crPluggableArtifact.getId()).isEqualTo("id");
        assertThat(crPluggableArtifact.getStoreId()).isEqualTo("s3");
        assertThat(crPluggableArtifact.getConfiguration()).isEqualTo(List.of(new CRConfigurationProperty("image", "gocd-agent")));
    }
}
