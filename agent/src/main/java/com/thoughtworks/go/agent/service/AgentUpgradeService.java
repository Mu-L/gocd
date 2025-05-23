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
package com.thoughtworks.go.agent.service;

import com.thoughtworks.go.agent.URLService;
import com.thoughtworks.go.agent.common.ssl.GoAgentServerHttpClient;
import com.thoughtworks.go.util.SystemEnvironment;
import org.apache.http.Header;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.Map;

import static com.thoughtworks.go.agent.common.util.HeaderUtil.parseExtraProperties;
import static com.thoughtworks.go.util.SystemEnvironment.*;

@Service
public class AgentUpgradeService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentUpgradeService.class);
    private static final Marker FATAL = MarkerFactory.getMarker("FATAL");
    private static final RequestConfig NO_FOLLOW_REDIRECT = RequestConfig.custom().setRedirectsEnabled(false).build();

    private final GoAgentServerHttpClient httpClient;
    private final SystemEnvironment systemEnvironment;
    private final URLService urlService;
    private final JvmExitter jvmExitter;

    interface JvmExitter {
        void jvmExit(String type, String oldChecksum, String newChecksum);
    }

    static class DefaultJvmExitter implements JvmExitter {
        @Override
        public void jvmExit(String type, String oldChecksum, String newChecksum) {
            LOGGER.error(FATAL, "[Agent Upgrade] Agent needs to upgrade {}. Currently has md5 {} but server version has md5 {}. Exiting.", type, oldChecksum, newChecksum);
            System.exit(0);
        }
    }

    @Autowired
    AgentUpgradeService(URLService urlService, GoAgentServerHttpClient httpClient, SystemEnvironment systemEnvironment) {
        this(urlService, httpClient, systemEnvironment, new DefaultJvmExitter());
    }

    AgentUpgradeService(URLService urlService, GoAgentServerHttpClient httpClient, SystemEnvironment systemEnvironment, JvmExitter jvmExitter) {
        this.httpClient = httpClient;
        this.systemEnvironment = systemEnvironment;
        this.urlService = urlService;
        this.jvmExitter = jvmExitter;
    }

    public void checkForUpgradeAndExtraProperties() throws IOException {
        if (upgradesEnabled()) {
            checkForUpgradeAndExtraProperties(systemEnvironment.getAgentMd5(), systemEnvironment.getGivenAgentLauncherMd5(),
                    systemEnvironment.getAgentPluginsMd5(), systemEnvironment.getTfsImplMd5());
        } else {
            LOGGER.debug("[Agent Upgrade] Skipping check as there is no wrapping launcher to relaunch the agent JVM...");
        }
    }

    private boolean upgradesEnabled() {
        return !"".equals(systemEnvironment.getAgentMd5());
    }

    private void checkForUpgradeAndExtraProperties(String agentMd5, String launcherMd5, String agentPluginsMd5, String tfsImplMd5) throws IOException {
        HttpGet method = getAgentLatestStatusGetMethod();
        try (final CloseableHttpResponse response = httpClient.execute(method)) {
            if (response.getStatusLine().getStatusCode() != HttpURLConnection.HTTP_OK) {
                LOGGER.error("[Agent Upgrade] Got status {} {} from GoCD", response.getStatusLine().getStatusCode(), response.getStatusLine());
                return;
            }
            validateMd5(agentMd5, response, AGENT_CONTENT_MD5_HEADER, "itself");
            validateMd5(launcherMd5, response, AGENT_LAUNCHER_CONTENT_MD5_HEADER, "launcher");
            validateMd5(agentPluginsMd5, response, AGENT_PLUGINS_ZIP_MD5_HEADER, "plugins");
            validateMd5(tfsImplMd5, response, AGENT_TFS_SDK_MD5_HEADER, "tfs-impl jar");
            updateExtraProperties(response.getFirstHeader(AGENT_EXTRA_PROPERTIES_HEADER));
        } catch (IOException ioe) {
            String message = String.format("[Agent Upgrade] Couldn't connect to: %s: %s", urlService.getAgentLatestStatusUrl(), ioe);
            LOGGER.error(message);
            LOGGER.debug(message, ioe);
            throw ioe;
        } finally {
            method.releaseConnection();
        }
    }

    private void updateExtraProperties(Header extraPropertiesHeader) {
        final Map<String, String> extraProperties = parseExtraProperties(extraPropertiesHeader);
        extraProperties.forEach(System::setProperty);
    }

    private void validateMd5(String currentMd5, CloseableHttpResponse response, String agentContentMd5Header, String what) {
        final Header md5Header = response.getFirstHeader(agentContentMd5Header);
        if (md5Header == null) {
            LOGGER.warn("[Agent Upgrade] Expected MD5 header {} was missing from server response.", agentContentMd5Header);
        } else if (!"".equals(currentMd5) && !currentMd5.equals(md5Header.getValue())) {
            jvmExitter.jvmExit(what, currentMd5, md5Header.getValue());
        }
    }

    HttpGet getAgentLatestStatusGetMethod() {
        HttpGet httpGet = new HttpGet(urlService.getAgentLatestStatusUrl());
        httpGet.setConfig(NO_FOLLOW_REDIRECT);
        return httpGet;
    }

}
