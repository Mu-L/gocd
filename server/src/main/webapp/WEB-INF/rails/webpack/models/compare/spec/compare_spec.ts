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

import {Comparison, DependencyRevision, MaterialRevision,} from "../compare";
import {DependencyRevisionJSON, MaterialRevisionJSON} from "../compare_json";
import {
  DependencyMaterialAttributes,
  GitMaterialAttributes,
  PackageMaterialAttributes,
  PluggableScmMaterialAttributes
} from "../material";
import {parseDate} from "../pipeline_instance";
import {ComparisonData, MaterialData} from "./test_data";

describe('ComparisonModelSpec', () => {
  it('should parse json into object', () => {
    const json       = ComparisonData.compare();
    const comparison = Comparison.fromJSON(json);

    expect(comparison.pipelineName).toEqual(json.pipeline_name);
    expect(comparison.fromCounter).toEqual(json.from_counter);
    expect(comparison.toCounter).toEqual(json.to_counter);
    expect(comparison.isBisect).toEqual(json.is_bisect);

    expect(comparison.changes.length).toEqual(2);

    const changes = comparison.changes;

    expect(changes[0].material.type()).toEqual("git");
    expect(changes[0].material.attributes()).toBeInstanceOf(GitMaterialAttributes);
    expect(changes[0].revision.length).toEqual(1);

    const revision0     = changes[0].revision[0];
    const revisionJSON0 = json.changes[0].revision[0] as MaterialRevisionJSON;

    expect(revision0).toBeInstanceOf(MaterialRevision);
    expect((revision0 as MaterialRevision).revisionSha).toEqual(revisionJSON0.revision_sha);
    expect((revision0 as MaterialRevision).commitMessage).toEqual(revisionJSON0.commit_message);
    expect((revision0 as MaterialRevision).modifiedAt).toEqual(parseDate(revisionJSON0.modified_at));
    expect((revision0 as MaterialRevision).modifiedBy).toEqual(revisionJSON0.modified_by);

    expect(changes[1].material.type()).toEqual("dependency");
    expect(changes[1].material.attributes()).toBeInstanceOf(DependencyMaterialAttributes);
    expect(changes[1].revision.length).toEqual(1);

    const revision1     = changes[1].revision[0];
    const revisionJSON1 = json.changes[1].revision[0] as DependencyRevisionJSON;

    expect(revision1).toBeInstanceOf(DependencyRevision);
    expect((revision1 as DependencyRevision).revision).toEqual(revisionJSON1.revision);
    expect((revision1 as DependencyRevision).pipelineCounter).toEqual(revisionJSON1.pipeline_counter);
    expect((revision1 as DependencyRevision).completedAt).toEqual(parseDate(revisionJSON1.completed_at));
  });

  it('should parse json into object with package material', () => {
    const json               = ComparisonData.compare();
    json.changes[0].material = MaterialData.package();
    json.changes.splice(1, 1);

    const comparison = Comparison.fromJSON(json);

    expect(comparison.pipelineName).toEqual(json.pipeline_name);
    expect(comparison.fromCounter).toEqual(json.from_counter);
    expect(comparison.toCounter).toEqual(json.to_counter);
    expect(comparison.isBisect).toEqual(json.is_bisect);

    expect(comparison.changes.length).toEqual(1);

    expect(comparison.changes[0].material.type()).toEqual("package");
    expect(comparison.changes[0].material.attributes()).toBeInstanceOf(PackageMaterialAttributes);
    expect(comparison.changes[0].revision.length).toEqual(1);
  });

  it('should parse json into object with pluggable scm material', () => {
    const json               = ComparisonData.compare();
    json.changes[0].material = MaterialData.pluggable();
    json.changes.splice(1, 1);

    const comparison = Comparison.fromJSON(json);

    expect(comparison.pipelineName).toEqual(json.pipeline_name);
    expect(comparison.fromCounter).toEqual(json.from_counter);
    expect(comparison.toCounter).toEqual(json.to_counter);
    expect(comparison.isBisect).toEqual(json.is_bisect);

    expect(comparison.changes.length).toEqual(1);

    expect(comparison.changes[0].material.type()).toEqual("plugin");
    expect(comparison.changes[0].material.attributes()).toBeInstanceOf(PluggableScmMaterialAttributes);
    expect(comparison.changes[0].revision.length).toEqual(1);
  });
});
