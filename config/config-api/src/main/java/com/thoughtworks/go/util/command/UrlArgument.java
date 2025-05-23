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
package com.thoughtworks.go.util.command;

import com.thoughtworks.go.config.ConfigAttributeValue;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.utils.URIBuilder;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

import static com.thoughtworks.go.util.ExceptionUtils.bombIfNull;
import static org.apache.commons.lang3.StringUtils.isBlank;

@ConfigAttributeValue(fieldName = "url")
public class UrlArgument extends CommandArgument {
    private static final String URL_DUMB_VALIDATION_REGEX = "^[a-zA-Z0-9/#].*";
    private static final Pattern pattern = Pattern.compile(URL_DUMB_VALIDATION_REGEX);

    protected String url;

    public UrlArgument(String url) {
        bombIfNull(url, "Url cannot be null.");
        this.url = url;
    }

    @Override
    public String originalArgument() {
        return url;
    }

    //TODO: Change this later to use URIBuilder
    @Override
    public String forDisplay() {
        try {
            URI uri = new URI(sanitizeUrl());
            if (uri.getUserInfo() != null) {
                uri = new URI(uri.getScheme(), clean(uri.getScheme(), uri.getUserInfo()), uri.getHost(), uri.getPort(), uri.getPath(), uri.getQuery(), uri.getFragment());
            }
            return uri.toString();
        } catch (URISyntaxException e) {
            return url;
        }
    }

    private String clean(String scheme, String userInfo) {
        if (userInfo.contains(":")) {
            return userInfo.replaceFirst(":.*", ":******");
        } else if ("ssh".equals(scheme) || "svn+ssh".equals(scheme)) {
            return userInfo;
        }
        return "******";
    }

    @Override
    public String forCommandLine() {
        return this.url;
    }

    protected String sanitizeUrl() {
        return this.url;
    }


    public static UrlArgument create(String url) {
        return new UrlArgument(url);
    }

    @Override
    public String replaceSecretInfo(String line) {
        if (StringUtils.isBlank(line)) {
            return line;
        }

        if (isBlank(this.url)) {
            return line;
        }

        try {
            final URIBuilder uriBuilder = new URIBuilder(this.url).setPath(null).setCustomQuery(null).setFragment(null);
            final UrlUserInfo urlUserInfo = new UrlUserInfo(uriBuilder.getUserInfo());
            if (uriBuilder.getUserInfo() != null) {
                line = line.replace(uriBuilder.getUserInfo(), urlUserInfo.maskedUserInfo());
            }
        } catch (URISyntaxException e) {
            //Ignore as url is not according to URI specs
        }

        return line;
    }

    @Override
    public boolean equal(CommandArgument that) {
        //BUG #3276 - on windows svn info includes a password in svn+ssh
        if (url.startsWith("svn+ssh")) {
            return this.originalArgument().equals(that.originalArgument());
        }
        return cleanPath(this).equals(cleanPath(that));
    }

    private String cleanPath(CommandArgument commandArgument) {
        String path = commandArgument.originalArgument();
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    public String withoutCredentials() {
        try {
            return new URIBuilder(this.sanitizeUrl()).setUserInfo(null).build().toString();
        } catch (URISyntaxException e) {
            return url;
        }
    }

    public boolean isValidURLOrLocalPath() {
        return pattern.matcher(url).matches();
    }
}
