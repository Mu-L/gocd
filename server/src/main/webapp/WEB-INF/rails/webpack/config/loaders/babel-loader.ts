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

import path from "path";
import webpack from "webpack";
import {ConfigOptions} from "../variables";
import {getCacheLoader} from "./cache-loader";
import {threadLoader} from "./thread-loader";

export function getBabelLoader(configOptions: ConfigOptions): webpack.RuleSetRule {
  const loaderName = "babel-loader";

  return {
    test: /\.(msx|js)$/,
    exclude: /node_modules/,
    use: [
      threadLoader(configOptions),
      getCacheLoader(configOptions),
      {
        loader: loaderName,
        options: {
          cacheDirectory: path.join(configOptions.cacheDir, loaderName)
        }
      }
    ]
  };
}
