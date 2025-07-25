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
package com.thoughtworks.go.config.policy;

import com.thoughtworks.go.config.ConfigAttribute;
import com.thoughtworks.go.config.ConfigValue;
import com.thoughtworks.go.config.Validatable;
import com.thoughtworks.go.config.ValidationContext;
import com.thoughtworks.go.domain.ConfigErrors;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOCase;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.util.List;
import java.util.Objects;

import static com.thoughtworks.go.config.policy.SupportedEntity.ELASTIC_AGENT_PROFILE;
import static com.thoughtworks.go.config.policy.SupportedEntity.fromString;
import static java.lang.String.format;

public abstract class AbstractDirective implements Directive {
    @ConfigAttribute(value = "action", optional = false)
    protected String action;

    @ConfigAttribute(value = "type", optional = false)
    protected String type;

    @ConfigValue
    private String resource;

    private final ConfigErrors configErrors = new ConfigErrors();

    private DirectiveType directiveType;

    public AbstractDirective(DirectiveType allow) {
        this.directiveType = allow;
    }

    public AbstractDirective(DirectiveType allow, String action, String type, String resource) {
        this.directiveType = allow;
        this.action = action;
        this.type = type;
        this.resource = resource;
    }

    @Override
    public void validate(ValidationContext validationContext) {
        PolicyValidationContext policyValidationContext = validationContext.getPolicyValidationContext();

        if (isInvalidAction(action, policyValidationContext.getAllowedActions())) {
            this.addError("action", format("Invalid action, must be one of %s.", policyValidationContext.getAllowedActions()));
        }

        if (isInvalidType(type, policyValidationContext.getAllowedTypes())) {
            this.addError("type", format("Invalid type, must be one of %s.", policyValidationContext.getAllowedTypes()));
        }

        if (isInvalidResource(resource)) {
            this.addError("resource", format("Invalid resource, %s permissions can not contain ':' separator.", type));
        }
    }

    private boolean isInvalidResource(String resource) {
        if (StringUtils.isEmpty(resource)) {
            return false;
        }

        return resource.contains(":") && !this.isDirectiveOfType(ELASTIC_AGENT_PROFILE);
    }

    private boolean isInvalidType(String type, List<String> allowedTypes) {
        if ("*".equals(type)) {
            return false;
        }

        return allowedTypes.stream().noneMatch(it -> Strings.CI.equals(it, type));
    }

    protected boolean isRequestForClusterProfiles(Class<? extends Validatable> aClass) {
        return SupportedEntity.CLUSTER_PROFILE.getEntityType().isAssignableFrom(aClass);
    }

    protected boolean isRequestForElasticAgentProfiles(Class<? extends Validatable> aClass) {
        return SupportedEntity.ELASTIC_AGENT_PROFILE.getEntityType().isAssignableFrom(aClass);
    }

    private boolean isInvalidAction(String action, List<String> allowedActions) {
        return allowedActions.stream().noneMatch(it -> Strings.CI.equals(it, action));
    }

    protected boolean matchesAction(String action) {
        if (Strings.CI.equals("administer", this.action)) {
            return true;
        }

        return Strings.CI.equals(action, this.action);
    }

    protected boolean isViewAction(String action) {
        return action.equals(SupportedAction.VIEW.getAction());
    }

    protected boolean matchesType(Class<? extends Validatable> entityType) {
        if (Strings.CI.equals("*", this.type)) {
            return true;
        }

        return fromString(this.type).getEntityType().isAssignableFrom(entityType);
    }

    protected boolean matchesResource(String resource) {
        if (Strings.CI.equals("*", this.resource)) {
            return true;
        }

        return FilenameUtils.wildcardMatch(resource, this.resource, IOCase.INSENSITIVE);
    }

    @Override
    public ConfigErrors errors() {
        return this.configErrors;
    }

    @Override
    public void addError(String fieldName, String message) {
        this.configErrors.add(fieldName, message);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AbstractDirective directive = (AbstractDirective) o;
        return Objects.equals(action, directive.action) &&
                Objects.equals(type, directive.type) &&
                Objects.equals(resource, directive.resource);
    }

    @Override
    public String action() {
        return this.action;
    }

    @Override
    public String type() {
        return this.type;
    }

    @Override
    public String resource() {
        return this.resource;
    }

    @Override
    public int hashCode() {
        return Objects.hash(action, type, resource);
    }

    @Override
    public boolean hasErrors() {
        return !this.configErrors.isEmpty();
    }

    @Override
    public DirectiveType getDirectiveType() {
        return this.directiveType;
    }

    protected boolean isDirectiveOfType(SupportedEntity entity) {
        return entity.getType().equals(this.type);
    }
}
