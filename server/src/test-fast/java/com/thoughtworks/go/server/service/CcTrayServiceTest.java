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
package com.thoughtworks.go.server.service;

import com.thoughtworks.go.ClearSingleton;
import com.thoughtworks.go.config.security.users.AllowedUsers;
import com.thoughtworks.go.config.security.users.Users;
import com.thoughtworks.go.domain.activity.ProjectStatus;
import com.thoughtworks.go.domain.cctray.CcTrayCache;
import com.thoughtworks.go.server.domain.Username;
import com.thoughtworks.go.util.Dates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static com.thoughtworks.go.server.newsecurity.SessionUtilsHelper.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@ExtendWith(ClearSingleton.class)
public class CcTrayServiceTest {
    @Mock
    private CcTrayCache ccTrayCache;
    @Mock
    private GoConfigService goConfigService;

    private CcTrayService ccTrayService;

    @BeforeEach
    public void setUp() {
        ccTrayService = new CcTrayService(ccTrayCache, goConfigService);
    }

    @Test
    public void shouldGenerateCcTrayXMLForAnyUserWhenSecurityIsDisabled() {
        when(goConfigService.isSecurityEnabled()).thenReturn(false);
        when(ccTrayCache.allEntriesInOrder()).thenReturn(List.of(statusFor("proj1", "user1"), statusFor("proj2", "user1")));
        loginAs("other_user");

        String xml = ccTrayService.renderCCTrayXML("some-prefix", Username.ANONYMOUS.getUsername().toString(), new StringBuilder(), etag -> {
        }).toString();

        assertCcTrayXmlFor(xml, "some-prefix", "proj1", "proj2");
    }

    @Test
    public void shouldGenerateCcTrayXMLForCurrentUserWhenSecurityIsEnabled() {
        when(goConfigService.isSecurityEnabled()).thenReturn(true);
        when(ccTrayCache.allEntriesInOrder()).thenReturn(List.of(statusFor("proj1", "user1"), statusFor("proj2", "user2")));

        loginAs("USER1");
        String xml = ccTrayService.renderCCTrayXML("some-prefix", "USER1", new StringBuilder(), etag -> {
        }).toString();
        assertCcTrayXmlFor(xml, "some-prefix", "proj1");

        loginAs("uSEr2");
        xml = ccTrayService.renderCCTrayXML("some-prefix", "uSEr2", new StringBuilder(), etag -> {
        }).toString();
        assertCcTrayXmlFor(xml, "some-prefix", "proj2");
    }

    @Test
    public void shouldGenerateEmptyCcTrayXMLWhenCurrentUserIsNotAuthorizedToViewAnyProjects() {
        when(goConfigService.isSecurityEnabled()).thenReturn(true);
        when(ccTrayCache.allEntriesInOrder()).thenReturn(List.of(statusFor("proj1", "user1"), statusFor("proj2", "user2")));

        loginAs("some-user-without-permissions");
        String xml = ccTrayService.renderCCTrayXML("some-prefix", "some-user-without-permissions", new StringBuilder(), etag -> {
        }).toString();
        assertCcTrayXmlFor(xml, "some-prefix");
    }

    @Test
    public void shouldAllowSiteURLPrefixToBeChangedPerCall() {
        when(goConfigService.isSecurityEnabled()).thenReturn(true);
        when(ccTrayCache.allEntriesInOrder()).thenReturn(List.of(statusFor("proj1", "user1"), statusFor("proj2", "user2")));

        loginAs("user1");
        String xml = ccTrayService.renderCCTrayXML("prefix1", "user1", new StringBuilder(), etag -> {
        }).toString();
        assertCcTrayXmlFor(xml, "prefix1", "proj1");

        loginAs("user2");
        xml = ccTrayService.renderCCTrayXML("prefix2", "user2", new StringBuilder(), etag -> {
        }).toString();
        assertCcTrayXmlFor(xml, "prefix2", "proj2");
    }

    @Test
    public void shouldNotAppendNewLinesForNullProjectStatusesInList() {
        when(goConfigService.isSecurityEnabled()).thenReturn(true);
        when(ccTrayCache.allEntriesInOrder()).thenReturn(List.of(statusFor("proj1", "user1"), new ProjectStatus.NullProjectStatus("proj1").updateViewers(viewers("user1"))));

        loginAs("user1");
        String xml = ccTrayService.renderCCTrayXML("prefix1", "user1", new StringBuilder(), etag -> {
        }).toString();

        assertThat(xml).isEqualTo("""
                <?xml version="1.0" encoding="utf-8"?>
                <Projects>
                  <Project name="proj1" activity="activity1" lastBuildStatus="build-status-1" lastBuildLabel="build-label-1" lastBuildTime="2010-05-23T08:00:00Z" webUrl="prefix1/web-url" />
                </Projects>""");
    }

    @Test
    public void shouldChangeEtagIfSitePrefixChanges() {
        when(goConfigService.isSecurityEnabled()).thenReturn(true);
        when(ccTrayCache.allEntriesInOrder()).thenReturn(List.of(statusFor("proj1", "user1"), new ProjectStatus.NullProjectStatus("proj1").updateViewers(viewers("user1"))));

        AtomicReference<String> originalEtag = new AtomicReference<>();
        String originalXML = ccTrayService.renderCCTrayXML("prefix1", "user1", new StringBuilder(), originalEtag::set).toString();

        AtomicReference<String> newEtag = new AtomicReference<>();
        String newXML = ccTrayService.renderCCTrayXML("prefix2", "user1", new StringBuilder(), newEtag::set).toString();

        assertThat(originalEtag.get()).isNotEqualTo(newEtag.get());
        assertThat(originalXML).isNotEqualTo(newXML);
    }

    @Test
    public void shouldChangeEtagIfProjectStatusChanges() {
        when(goConfigService.isSecurityEnabled()).thenReturn(true);
        when(ccTrayCache.allEntriesInOrder())
                .thenReturn(List.of(statusFor("proj1", "user1"), new ProjectStatus.NullProjectStatus("proj1").updateViewers(viewers("user1"))))
                .thenReturn(List.of(statusFor("proj2", "user1"), new ProjectStatus.NullProjectStatus("proj1").updateViewers(viewers("user1"))));

        AtomicReference<String> originalEtag = new AtomicReference<>();
        String originalXML = ccTrayService.renderCCTrayXML("prefix1", "user1", new StringBuilder(), originalEtag::set).toString();


        AtomicReference<String> newEtag = new AtomicReference<>();
        String newXML = ccTrayService.renderCCTrayXML("prefix1", "user1", new StringBuilder(), newEtag::set).toString();

        assertThat(originalEtag.get()).isNotEqualTo(newEtag.get());
        assertThat(originalXML).isNotEqualTo(newXML);
    }

    private ProjectStatus statusFor(String projectName, String... allowedUsers) {
        ProjectStatus status = new ProjectStatus(projectName, "activity1", "build-status-1", "build-label-1", Dates.parseRFC822("Sun, 23 May 2010 10:00:00 +0200"), "web-url");
        status.updateViewers(viewers(allowedUsers));
        return status;
    }

    private void assertCcTrayXmlFor(String actualXml, final String siteUrlPrefix, final String... projects) {
        StringBuilder expectedXml = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<Projects>\n");
        for (String project : projects) {
            expectedXml.append("  <Project name=\"").append(project).append("\" activity=\"activity1\" lastBuildStatus=\"build-status-1\" lastBuildLabel=\"build-label-1\" lastBuildTime=\"2010-05-23T08:00:00Z\" webUrl=\"" + siteUrlPrefix + "/web-url\" />\n");
        }
        expectedXml.append("</Projects>");

        assertThat(actualXml).isEqualTo(expectedXml.toString());
    }

    private Users viewers(String... users) {
        return new AllowedUsers(Set.of(users), Collections.emptySet());
    }
}
