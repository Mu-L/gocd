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

package com.thoughtworks.go.apiv2.apiinfo

import com.thoughtworks.go.api.SecurityTestTrait
import com.thoughtworks.go.api.spring.ApiAuthenticationHelper
import com.thoughtworks.go.apiv2.apiinfo.representers.RouteEntryRepresenter
import com.thoughtworks.go.spark.ControllerTrait
import com.thoughtworks.go.spark.NormalUserSecurity
import com.thoughtworks.go.spark.SecurityServiceTrait
import com.thoughtworks.go.spark.spring.RouteEntry
import com.thoughtworks.go.spark.spring.RouteInformationProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
import spark.RouteImpl
import spark.route.HttpMethod

import static org.mockito.Mockito.when

@MockitoSettings(strictness = Strictness.LENIENT)
class ApiInfoControllerV2Test implements SecurityServiceTrait, ControllerTrait<ApiInfoControllerV2> {
  @Mock
  private RouteInformationProvider provider

  @Override
  ApiInfoControllerV2 createControllerInstance() {
    new ApiInfoControllerV2(new ApiAuthenticationHelper(securityService, goConfigService), provider)
  }

  @Nested
  class Index {

    @BeforeEach
    void setUp() {
      loginAsUser()
    }

    @Test
    void 'should return list of api routes'() {
      def entries = List.of(new RouteEntry(HttpMethod.get, "/api/:foo/:bar", "application/vnd.go.cd+v2.json",
              RouteImpl.create("", () -> null)))
      when(provider.getRoutes()).thenReturn(entries)

      getWithApiHeader(controller.controllerBasePath())

      assertThatResponse()
        .isOk()
        .hasBodyWithJsonArray(RouteEntryRepresenter, entries)
    }

    @Nested
    class Security implements SecurityTestTrait, NormalUserSecurity {

      @Override
      String getControllerMethodUnderTest() {
        return "index"
      }

      @Override
      void makeHttpCall() {
        getWithApiHeader(controller.controllerBasePath())
      }
    }
  }
}
