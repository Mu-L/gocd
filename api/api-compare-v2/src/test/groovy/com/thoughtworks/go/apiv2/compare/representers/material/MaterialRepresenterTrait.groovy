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
package com.thoughtworks.go.apiv2.compare.representers.material

import com.thoughtworks.go.apiv2.compare.representers.MaterialRepresenter
import org.junit.jupiter.api.Test

import static com.thoughtworks.go.api.base.JsonUtils.toObjectString
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson

trait MaterialRepresenterTrait {

  @Test
  void 'should render material with hal representation'() {
    def actualJson = toObjectString({
      MaterialRepresenter.toJSON(it, existingMaterial())
    })

    assertThatJson(actualJson).isEqualTo(materialHash)
  }

  @Test
  void "should render errors"() {
    def actualJson = toObjectString({
      MaterialRepresenter.toJSON(it, existingMaterialWithErrors())
    })

    assertThatJson(actualJson).isEqualTo(expectedMaterialHashWithErrors)
  }
}

