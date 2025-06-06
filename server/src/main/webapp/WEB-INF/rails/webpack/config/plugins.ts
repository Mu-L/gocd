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

import {CleanWebpackPlugin} from "clean-webpack-plugin";
import ESLintPlugin from "eslint-webpack-plugin";
import ForkTsCheckerWebpackPlugin from "fork-ts-checker-webpack-plugin";
import fs from "fs";
import HtmlWebpackPlugin from "html-webpack-plugin";
import _ from "lodash";
import MiniCssExtractPlugin from "mini-css-extract-plugin";
import path from "path";
import webpack from "webpack";
import {ConfigOptions, getEntries} from "./variables";
import {LicensePlugins} from "./webpack-license-plugin";

const jasmineCore                = require("jasmine-core");
const StatsPlugin                = require("stats-webpack-plugin");
const StylelintPlugin            = require("stylelint-webpack-plugin");
const UnusedWebpackPlugin        = require("unused-webpack-plugin");

export function plugins(configOptions: ConfigOptions): webpack.Plugin[] {
  const plugins = [
    new ESLintPlugin({
      extensions: ["js", "msx"],
      exclude: ["node_modules", "webpack/gen"],
      failOnWarning: true,
      threads: true
    }),
    new CleanWebpackPlugin(),
    new UnusedWebpackPlugin({
                              directories: [
                                path.join(configOptions.railsRoot, "webpack"),
                                path.join(configOptions.railsRoot, "spec", "webpack")
                              ],
                              exclude: ["config/**/*.*", "*.d.ts", 'tsconfig.json'],
                            }) as webpack.Plugin,
    new StylelintPlugin({configFile: path.join(configOptions.railsRoot, ".stylelintrc.yml"), files: configOptions.assetsDir, failOnWarning: true}) as webpack.Plugin,
    new StatsPlugin("manifest.json", {
      chunkModules: false,
      source: false,
      chunks: false,
      modules: false,
      assets: true
    }) as webpack.Plugin,
    new webpack.ProvidePlugin({
                                "$": "jquery",
                                "jQuery": "jquery",
                                "window.jQuery": "jquery"
                              }) as webpack.Plugin,
    new LicensePlugins(configOptions.licenseReportFile),
    new ForkTsCheckerWebpackPlugin({
      typescript: { memoryLimit: 512, diagnosticOptions: { semantic: true, syntactic: true } }
    })
  ];

  if (configOptions.production) {
    plugins.push(new MiniCssExtractPlugin({
                                            filename: "[name]-[contenthash].css",
                                            chunkFilename: "[id]-[contenthash].css",
                                            ignoreOrder: true
                                          }) as unknown as webpack.Plugin);
  } else {
    const jasmineFiles = jasmineCore.files;

    const entries = getEntries(configOptions);
    delete entries.specRoot;

    const jasmineIndexPage = {
      // rebuild every time; without this, `_specRunner.html` disappears in webpack-watch
      // after a code change (because of the `clean-webpack-plugin`), unless the template
      // itself changes.
      cache: false,

      inject: true,
      xhtml: true,
      filename: "_specRunner.html",
      template: path.join(configOptions.railsRoot, "spec", "webpack", "_specRunner.html.ejs"),
      jasmineJsFiles: _.map(jasmineFiles.jsFiles.concat(jasmineFiles.bootFiles), (file) => {
        return `__jasmine/${file}`;
      }),
      jasmineCssFiles: _.map(jasmineFiles.cssFiles, (file) => {
        return `__jasmine/${file}`;
      }),
      excludeChunks: _.keys(entries)
    };

    class JasmineAssetsPlugin {
      apply(compiler: webpack.Compiler) {
        compiler.hooks.emit.tapAsync("JasmineAssetsPlugin",
                                     (compilation: webpack.compilation.Compilation, callback: () => any) => {
                                       const allJasmineAssets = jasmineFiles.jsFiles.concat(jasmineFiles.bootFiles)
                                                                            .concat(jasmineFiles.cssFiles);

                                       _.each(allJasmineAssets, (asset) => {
                                         const file = path.join(jasmineFiles.path, asset);

                                         const contents = fs.readFileSync(file).toString();

                                         compilation.assets[`__jasmine/${asset}`] = {
                                           source() {
                                             return contents;
                                           },
                                           size() {
                                             return contents.length;
                                           }
                                         };
                                       });

                                       callback();
                                     });
      }
    }

    plugins.push(new HtmlWebpackPlugin(jasmineIndexPage));
    plugins.push(new JasmineAssetsPlugin());
  }
  return plugins;
}
