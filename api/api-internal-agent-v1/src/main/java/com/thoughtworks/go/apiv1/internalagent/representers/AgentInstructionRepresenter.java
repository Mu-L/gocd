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

package com.thoughtworks.go.apiv1.internalagent.representers;

import com.google.gson.Gson;
import com.thoughtworks.go.remote.AgentInstruction;
import com.thoughtworks.go.remote.Serialization;

public class AgentInstructionRepresenter {
    private static final Gson gson = Serialization.instance();

    public static String toJSON(AgentInstruction agentInstruction) {
        return gson.toJson(agentInstruction);
    }
}
