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
package com.thoughtworks.go.domain.materials.tfs;

import com.thoughtworks.go.domain.materials.Material;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class TfsMaterialInstanceTest {

    @Test
    public void shouldHaveDifferentFingerprintsForTwoDifferentMaterials() {
        TfsMaterialInstance instance1 = new TfsMaterialInstance("http://foo.com", "username", "CORPORATE", "some_project_path", "blah");
        TfsMaterialInstance instance2 = new TfsMaterialInstance("http://foo.com", "username", "CORPORATE", "some_other_project_path", "some_other_blah");
        assertThat(instance1.getFingerprint()).isNotEqualTo(instance2.getFingerprint());
    }

    @Test
    public void testTfsMaterialsInstanceEquality() {
        TfsMaterialInstance instance1 = new TfsMaterialInstance("http://foo.com", "username", "CORPORATE", "some_project_path", "blah");
        TfsMaterialInstance instance2 = new TfsMaterialInstance("http://foo.com", "username", "CORPORATE", "some_other_project_path", "some_other_blah");
        assertThat(instance1).isNotEqualTo(instance2);

        TfsMaterialInstance instance = new TfsMaterialInstance("http://foo.com", "username", "CORPORATE", "some_project_path", "blah");
        TfsMaterialInstance similarInstance = new TfsMaterialInstance("http://foo.com", "username", "CORPORATE", "some_project_path", "blah");
        assertThat(instance).isEqualTo(similarInstance);
    }

    @Test
    public void shouldPassFolderAlong() {
        TfsMaterialInstance instance1 = new TfsMaterialInstance("http://foo.com", "username", "CORPORATE", "some_project_path", "blah");
        Material material = instance1.toOldMaterial("", "some_folder", "password");
        assertThat(material.getFolder()).isEqualTo("some_folder");
    }


    @Test
    public void shouldSetNameAsNullIfNoNameSpecified() {
        TfsMaterialInstance instance1 = new TfsMaterialInstance("http://foo.com", "username", "CORPORATE", "some_project_path", "blah");
        Material material = instance1.toOldMaterial(null, "some_folder", "password");
        assertThat(material.getName()).isNull();
    }
}
