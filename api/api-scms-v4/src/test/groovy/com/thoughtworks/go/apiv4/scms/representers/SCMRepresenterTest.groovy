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
package com.thoughtworks.go.apiv4.scms.representers

import com.thoughtworks.go.api.util.GsonTransformer
import com.thoughtworks.go.domain.config.Configuration
import com.thoughtworks.go.domain.packagerepository.ConfigurationPropertyMother
import com.thoughtworks.go.domain.scm.SCM
import com.thoughtworks.go.domain.scm.SCMMother
import com.thoughtworks.go.plugin.access.scm.SCMConfiguration
import com.thoughtworks.go.plugin.access.scm.SCMConfigurations
import com.thoughtworks.go.plugin.access.scm.SCMMetadataStore
import com.thoughtworks.go.security.GoCipher
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import static com.thoughtworks.go.CurrentGoCDVersion.apiDocsUrl
import static com.thoughtworks.go.api.base.JsonUtils.toObjectString
import static com.thoughtworks.go.plugin.access.scm.SCMConfiguration.PART_OF_IDENTITY
import static com.thoughtworks.go.plugin.access.scm.SCMConfiguration.SECURE
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson
import static org.assertj.core.api.Assertions.assertThat

class SCMRepresenterTest {
  @AfterEach
  void tearDown() {
    SCMMetadataStore.getInstance().clear()
  }

  @Test
  void 'should serialize to json'() {
    SCM scm = SCMMother.create("1", "foobar", "plugin1", "v1.0", new Configuration(
      ConfigurationPropertyMother.create("key1", false, "value1"),
      ConfigurationPropertyMother.create("key2", true, "secret"),
    ))

    def actualJson = toObjectString({ SCMRepresenter.toJSON(it, scm) })

    assertThatJson(actualJson).isEqualTo([
      "_links"         : [
        "doc" : [
          "href": apiDocsUrl('#scms')
        ],
        "self": [
          "href": "http://test.host/go/api/admin/scms/foobar"
        ],
        "find": [
          "href": "http://test.host/go/api/admin/scms/:material_name"
        ]
      ],
      "id"             : "1",
      "name"           : "foobar",
      "auto_update"    : true,
      "origin"         : [
        "_links": [
          "self": [
            "href": "http://test.host/go/admin/config_xml"
          ],
          "doc" : [
            "href": apiDocsUrl("#get-configuration")
          ]
        ],
        "type"  : "gocd"
      ],
      "plugin_metadata": [
        "id"     : "plugin1",
        "version": "v1.0"
      ],
      "configuration"  : [
        [
          "key"  : "key1",
          "value": "value1"
        ], [
          "key"            : "key2",
          "encrypted_value": new GoCipher().encrypt("secret")
        ]
      ]
    ])
  }

  @Test
  void 'should de-serialize from JSON'() {
    def scmJson = [
      "id"             : "1",
      "name"           : "foobar",
      "auto_update"    : false,
      "plugin_metadata": [
        "id"     : "plugin1",
        "version": "v1.0"
      ],
      "configuration"  : [
        [
          "key"  : "key1",
          "value": "value1"
        ], [
          "key"            : "key2",
          "encrypted_value": new GoCipher().encrypt("secret")
        ]
      ]
    ]

    def jsonReader = GsonTransformer.instance.jsonReaderFrom(scmJson)
    def actualScm = SCMRepresenter.fromJSON(jsonReader)

    assertThat(actualScm.getId()).isEqualTo("1")
    assertThat(actualScm.getName()).isEqualTo("foobar")
    assertThat(actualScm.isAutoUpdate()).isEqualTo(false)
    assertThat(actualScm.getConfiguration()).isEqualTo(new Configuration(
      ConfigurationPropertyMother.create("key1", false, "value1"),
      ConfigurationPropertyMother.create("key2", true, "secret"),
    ))
  }

  @Test
  void 'de-serialized object should encrypt secure values'() {
    SCMConfigurations scmConfiguration = new SCMConfigurations()
    scmConfiguration.add(new SCMConfiguration("key1").with(PART_OF_IDENTITY, true).with(SECURE, true))
    SCMMetadataStore.getInstance().addMetadataFor("plugin1", scmConfiguration, null)

    def scmJson = [
      "id"             : "1",
      "name"           : "foobar",
      "plugin_metadata": [
        "id"     : "plugin1",
        "version": "v1.0"
      ],
      "configuration"  : [
        [
          "key"  : "key1",
          "value": "value1"
        ]
      ]
    ]

    def jsonReader = GsonTransformer.instance.jsonReaderFrom(scmJson)
    def actualScm = SCMRepresenter.fromJSON(jsonReader)

    assertThat(actualScm.getId()).isEqualTo("1")
    assertThat(actualScm.getConfiguration().get(0).isSecure()).isTrue()
  }
}
