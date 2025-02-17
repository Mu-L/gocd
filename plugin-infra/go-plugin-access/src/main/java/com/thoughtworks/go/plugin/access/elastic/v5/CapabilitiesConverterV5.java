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
package com.thoughtworks.go.plugin.access.elastic.v5;

import com.thoughtworks.go.plugin.access.elastic.DataConverter;
import com.thoughtworks.go.plugin.domain.elastic.Capabilities;

class CapabilitiesConverterV5 implements DataConverter<Capabilities, CapabilitiesDTO> {
    @Override
    public Capabilities fromDTO(CapabilitiesDTO capabilitiesDTO) {
        return new Capabilities(capabilitiesDTO.supportsPluginStatusReport(), capabilitiesDTO.supportsClusterStatusReport(), capabilitiesDTO.supportsAgentStatusReport());
    }

    @Override
    public CapabilitiesDTO toDTO(Capabilities object) {
        throw unsupportedOperationException(object.getClass().getName(), CapabilitiesDTO.class.getName());
    }
}
