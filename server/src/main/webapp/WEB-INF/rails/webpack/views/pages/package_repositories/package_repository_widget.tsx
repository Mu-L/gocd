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
import {MithrilViewComponent} from "jsx/mithril-component";
import m from "mithril";
import Stream from "mithril/stream";
import {PackageRepository} from "models/package_repositories/package_repositories";
import s from "underscore.string";
import {Anchor, ScrollManager} from "views/components/anchor/anchor";
import {ButtonIcon, Secondary} from "views/components/buttons";
import {CollapsiblePanel} from "views/components/collapsible_panel";
import {Clone, Delete, Edit, IconGroup, InfoCircle} from "views/components/icons";
import {KeyValuePair} from "views/components/key_value_pair";
import {PackageOperations} from "views/pages/package_repositories";
import {CloneOperation, DeleteOperation, EditOperation} from "views/pages/page_operations";
import {ConfigurationDetailsWidget} from "./configuration_details_widget";
import styles from "./index.scss";
import {PackagesWidget} from "./packages_widget";
import {PackageRepoScrollOptions} from "./package_repositories_widget";

interface Attrs extends EditOperation<PackageRepository>, CloneOperation<PackageRepository>, DeleteOperation<PackageRepository> {
  packageRepository: PackageRepository;
  packageOperations: PackageOperations;
  disableActions: boolean;
  scrollOptions: PackageRepoScrollOptions;
}

export interface PackageRepositoryScrollOptions {
  sm: ScrollManager;
  shouldOpenEditView: boolean;
  shouldOpenCreatePackageView: boolean;
}

export class PackageRepositoryWidget extends MithrilViewComponent<Attrs> {
  expanded: Stream<boolean> = Stream();

  public static getPkgRepoDetails(packageRepository: PackageRepository) {
    const pkgRepoProperties = packageRepository.configuration() ? packageRepository.configuration()!.asMap() : [];
    return new Map([
                     ["Repo Id", packageRepository.repoId()],
                     ["Plugin Id", packageRepository.pluginMetadata().id()],
                     ...Array.from(pkgRepoProperties)
                   ]);
  }

  oninit(vnode: m.Vnode<Attrs, this>): any {
    const linked = vnode.attrs.scrollOptions.package_repo_sm.sm.getTarget() === vnode.attrs.packageRepository.name();

    this.expanded(linked);
  }

  view(vnode: m.Vnode<Attrs, this>): m.Children | void | null {
    const header = <KeyValuePair inline={true}
                                 data={PackageRepositoryWidget.headerMap(vnode.attrs.packageRepository)}/>;

    const packageRepository = vnode.attrs.packageRepository;
    const pkgRepoDetails    = PackageRepositoryWidget.getPkgRepoDetails(packageRepository);
    const disabled          = vnode.attrs.disableActions;
    const warningIcon       = disabled
      ? <span className={styles.warning}><InfoCircle title={`Plugin '${packageRepository.pluginMetadata().id()}' was not found!`}
                                                     iconOnly={true}/></span>
      : undefined;
    const actionButtons     = [
      <Secondary onclick={vnode.attrs.packageOperations.onAdd.bind(vnode.attrs, packageRepository)}
                 data-test-id={"package-create"}
                 disabled={disabled}
                 title={PackageRepositoryWidget.getMsgForOperation(disabled, packageRepository.name(), "create package for")}
                 icon={ButtonIcon.ADD}>
        Create Package
      </Secondary>,

      <div className={styles.packageRepositoryCrudActions}>
        <IconGroup>
          <Edit data-test-id="package-repo-edit"
                disabled={disabled}
                title={PackageRepositoryWidget.getMsgForOperation(disabled, packageRepository.name(), "edit")}
                onclick={vnode.attrs.onEdit.bind(vnode.attrs, packageRepository)}/>
          <Clone data-test-id="package-repo-clone"
                 disabled={disabled}
                 title={PackageRepositoryWidget.getMsgForOperation(disabled, packageRepository.name(), "clone")}
                 onclick={vnode.attrs.onClone.bind(vnode.attrs, packageRepository)}/>
          <Delete data-test-id="package-repo-delete"
                  title={PackageRepositoryWidget.getMsgForOperation(disabled, packageRepository.name(), "delete")}
                  onclick={vnode.attrs.onDelete.bind(vnode.attrs, packageRepository)}/>
        </IconGroup>
      </div>
    ];

    const onNavigate = () => {
      const scrollOptions = vnode.attrs.scrollOptions.package_repo_sm;
      this.expanded(true);
      if (scrollOptions.sm.getTarget() === packageRepository.name() && !disabled) {
        if (scrollOptions.shouldOpenEditView) {
          vnode.attrs.onEdit(packageRepository, new MouseEvent("click"));
        } else if (scrollOptions.shouldOpenCreatePackageView) {
          vnode.attrs.packageOperations.onAdd(packageRepository, new MouseEvent("click"));
        }
      }
    };
    return <Anchor id={packageRepository.name()} sm={vnode.attrs.scrollOptions.package_repo_sm.sm}
                   onnavigate={onNavigate}>
      <CollapsiblePanel key={vnode.attrs.packageRepository.repoId()}
                        header={header} error={disabled}
                        actions={[warningIcon, actionButtons]}
                        dataTestId={"package-repository-panel"}
                        vm={this}>
        <ConfigurationDetailsWidget header={"Package Repository configuration"} data={pkgRepoDetails}/>
        <PackagesWidget packageRepoName={packageRepository.name()}
                        packages={vnode.attrs.packageRepository.packages}
                        disableActions={disabled}
                        scrollOptions={vnode.attrs.scrollOptions}
                        packageOperations={vnode.attrs.packageOperations}/>
      </CollapsiblePanel>
    </Anchor>;
  }

  private static headerMap(packageRepository: PackageRepository) {
    const map = new Map();
    map.set("Name", packageRepository.name());
    map.set("Plugin Id", packageRepository.pluginMetadata().id());
    return map;
  }

  private static getMsgForOperation(disabled: boolean, pkgRepoName: string, operation: "edit" | "clone" | "delete" | "create package for"): string | undefined {
    if (disabled && operation !== "delete") {
      return "Plugin not found!";
    }
    return `${s.capitalize(operation)} package repository '${pkgRepoName}'`;
  }
}
