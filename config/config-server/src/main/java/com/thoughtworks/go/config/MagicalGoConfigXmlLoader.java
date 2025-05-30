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
package com.thoughtworks.go.config;

import com.thoughtworks.go.config.exceptions.GoConfigInvalidException;
import com.thoughtworks.go.config.exceptions.GoConfigInvalidMergeException;
import com.thoughtworks.go.config.parser.ConfigReferenceElements;
import com.thoughtworks.go.config.preprocessor.ConfigParamPreprocessor;
import com.thoughtworks.go.config.preprocessor.ConfigRepoPartialPreprocessor;
import com.thoughtworks.go.config.registry.ConfigElementImplementationRegistry;
import com.thoughtworks.go.config.remote.FileConfigOrigin;
import com.thoughtworks.go.config.validation.*;
import com.thoughtworks.go.domain.ConfigErrors;
import com.thoughtworks.go.security.GoCipher;
import com.thoughtworks.go.util.SystemEnvironment;
import com.thoughtworks.go.util.XmlUtils;
import org.apache.commons.codec.digest.DigestUtils;
import org.jdom2.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static com.thoughtworks.go.config.parser.GoConfigClassLoader.classParser;

public class MagicalGoConfigXmlLoader {
    public static final List<GoConfigPreprocessor> PREPROCESSORS = List.of(
            new ConfigRepoPartialPreprocessor(),
            new TemplateExpansionPreprocessor(),
            new ConfigParamPreprocessor());
    public static final List<GoConfigXMLValidator> XML_VALIDATORS = List.of(new UniqueOnCancelValidator());
    private static final Logger LOGGER = LoggerFactory.getLogger(MagicalGoConfigXmlLoader.class);
    private static final SystemEnvironment systemEnvironment = new SystemEnvironment();
    public static final List<GoConfigValidator> VALIDATORS = List.of(
            new ArtifactDirValidator(),
            new ServerIdImmutabilityValidator(),
            new TokenGenerationKeyImmutabilityValidator(systemEnvironment)
    );
    private static final GoConfigCloner CLONER = new GoConfigCloner();
    private final ConfigElementImplementationRegistry registry;
    private final ConfigCache configCache;

    public MagicalGoConfigXmlLoader(ConfigCache configCache, ConfigElementImplementationRegistry registry) {
        this.configCache = configCache;
        this.registry = registry;
    }

    public static void setMd5(CruiseConfig configForEdit, String md5) throws NoSuchFieldException, IllegalAccessException {
        Field field = BasicCruiseConfig.class.getDeclaredField("md5");
        field.setAccessible(true);
        field.set(configForEdit, md5);
    }

    public static List<ConfigErrors> validate(CruiseConfig config) {
        preprocess(config);
        return new ArrayList<>(config.validateAfterPreprocess());
    }

    public static void preprocess(CruiseConfig cruiseConfig) {
        for (GoConfigPreprocessor preProcessor : PREPROCESSORS) {
            preProcessor.process(cruiseConfig);
        }
    }

    public static void validateDom(Element element, final ConfigElementImplementationRegistry registry) {
        for (GoConfigXMLValidator xmlValidator : XML_VALIDATORS) {
            xmlValidator.validate(element, registry);
        }
    }

    public GoConfigHolder loadConfigHolder(final String content, Callback callback) throws Exception {
        CruiseConfig configForEdit;
        CruiseConfig config;
        LOGGER.debug("[Config Save] Loading config holder");
        configForEdit = deserializeConfig(content);
        if (callback != null) callback.call(configForEdit);
        config = preprocessAndValidate(configForEdit);

        return new GoConfigHolder(config, configForEdit);
    }

    public GoConfigHolder loadConfigHolder(final String content) throws Exception {
        return loadConfigHolder(content, null);
    }

    public CruiseConfig deserializeConfig(String content) throws Exception {
        Element element = parseInputStream(new ByteArrayInputStream(content.getBytes()));
        LOGGER.debug("[Config Save] Updating config cache with new XML");

        CruiseConfig configForEdit = classParser(element, BasicCruiseConfig.class, configCache, new GoCipher(), registry, new ConfigReferenceElements()).parse();
        setMd5(configForEdit, DigestUtils.md5Hex(content));
        configForEdit.setOrigins(new FileConfigOrigin());
        return configForEdit;
    }

    public CruiseConfig preprocessAndValidate(CruiseConfig config) {
        LOGGER.debug("[Config Validation] In preprocessAndValidate: Cloning.");
        CruiseConfig cloned = CLONER.deepClone(config);
        LOGGER.debug("[Config Validation] In preprocessAndValidate: Validating.");
        validateCruiseConfig(cloned);
        LOGGER.debug("[Config Validation] In preprocessAndValidate: Done.");
        config.encryptSecureProperties(cloned);
        return cloned;
    }

    public CruiseConfig validateCruiseConfig(CruiseConfig config) {
        LOGGER.debug("[Config Save] In validateCruiseConfig: Starting.");
        List<ConfigErrors> allErrors = validate(config);
        if (!allErrors.isEmpty()) {
            throw config.isLocal()
                ? new GoConfigInvalidException(config, allErrors)
                : new GoConfigInvalidMergeException(config, allErrors);
        }

        LOGGER.debug("[Config Save] In validateCruiseConfig: Running validate.");
        for (GoConfigValidator validator : VALIDATORS) {
            validator.validate(config);
        }

        LOGGER.debug("[Config Save] In validateCruiseConfig: Done.");
        return config;
    }

    private Element parseInputStream(InputStream inputStream) throws Exception {
        Element rootElement = XmlUtils.buildValidatedXmlDocument(inputStream, GoConfigSchema.getCurrentSchema()).getRootElement();
        validateDom(rootElement, registry);
        return rootElement;
    }

    public <T> T fromXmlPartial(String partial, Class<T> o) throws Exception {
        return parse(o, XmlUtils.buildXmlDocument(partial).getRootElement());
    }

    public <T> T fromXmlPartial(InputStream inputStream, Class<T> o) throws Exception {
        return parse(o, XmlUtils.buildXmlDocument(inputStream).getRootElement());
    }

    private <T> T parse(Class<T> o, Element element) {
        return classParser(element, o, configCache, new GoCipher(), registry, new ConfigReferenceElements()).parse();
    }

    public GoConfigPreprocessor getPreprocessorOfType(final Class<? extends GoConfigPreprocessor> clazz) {
        return MagicalGoConfigXmlLoader.PREPROCESSORS.stream().filter(item -> item.getClass().isAssignableFrom(clazz)).findFirst().orElse(null);
    }

    public interface Callback {
        void call(CruiseConfig cruiseConfig);
    }
}
