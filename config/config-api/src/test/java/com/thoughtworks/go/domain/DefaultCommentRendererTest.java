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
package com.thoughtworks.go.domain;

import org.apache.commons.text.StringEscapeUtils;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class DefaultCommentRendererTest {

    DefaultCommentRenderer trackingTool;

    @Test
    public void shouldReturnOriginalStringIfNothingSpecified() {
        trackingTool = new DefaultCommentRenderer("", "");
        String toRender = "some string";
        String result = trackingTool.render(toRender);
        assertThat(result).isEqualTo(toRender);
    }

    @Test
    public void shouldReturnEmptyStringIfNothingToRender() {
        String link = "http://mingle05/projects/cce/cards/${ID}";
        String regex = "(evo-\\d+)";
        trackingTool = new DefaultCommentRenderer(link, regex);
        String result = trackingTool.render(null);
        assertThat(result).isEqualTo("");
    }

    @Test
    public void shouldRenderStringWithoutSpecifiedRegexAndLinkIfHasGroupsAndNoneMaterialize() {
        String link = "http://mingle05/projects/cce/cards/${ID}";
        String regex = "evo-(\\d+)|evo-";
        trackingTool = new DefaultCommentRenderer(link, regex);

        String result = trackingTool.render("evo-abc: checkin message");
        assertThat(result).isEqualTo("evo-abc: checkin message");
    }

    @Test
    public void shouldRenderStringWithSpecifiedRegexAndLinkIfHasGroupsAndOtherThanFirstMaterializes() {
        String link = "http://mingle05/projects/cce/cards/${ID}";
        String regex = "evo-(\\d+)|evo-(ab)";
        trackingTool = new DefaultCommentRenderer(link, regex);

        String result = trackingTool.render("evo-abc: checkin message");
        assertThat(result).isEqualTo("<a href=\"" + "http://mingle05/projects/cce/cards/ab\" target=\"story_tracker\">evo-ab</a>c: checkin message");
    }

    @Test
    public void shouldRenderStringWithSpecifiedRegexAndLink() {
        String link = "http://mingle05/projects/cce/cards/${ID}";
        String regex = "(evo-\\d+)";
        trackingTool = new DefaultCommentRenderer(link, regex);

        String result = trackingTool.render("evo-111: checkin message");
        assertThat(result).isEqualTo("<a href=\"" + "http://mingle05/projects/cce/cards/evo-111\" "
                        + "target=\"story_tracker\">evo-111</a>: checkin message");
    }

    @Test
    public void shouldRenderStringWithSpecifiedRegexAndLink1() {
        String link = "http://mingle05/projects/cce/cards/${ID}";
        String regex = "(?:Task |#|Bug )(\\d+)";
        trackingTool = new DefaultCommentRenderer(link, regex);

        assertThat(trackingTool.render("Task 111: checkin message")).isEqualTo("<a href=\"" + "http://mingle05/projects/cce/cards/111\" "
                        + "target=\"story_tracker\">Task 111</a>: checkin message");
        assertThat(trackingTool.render("Bug 111: checkin message")).isEqualTo("<a href=\"" + "http://mingle05/projects/cce/cards/111\" "
                        + "target=\"story_tracker\">Bug 111</a>: checkin message");
        assertThat(trackingTool.render("#111: checkin message")).isEqualTo("<a href=\"" + "http://mingle05/projects/cce/cards/111\" "
                        + "target=\"story_tracker\">#111</a>: checkin message");
    }

    @Test
    public void shouldRenderStringWithRegexThatHasSubSelect() {
        String link = "http://mingle05/projects/cce/cards/${ID}";
        String regex = "evo-(\\d+)";
        trackingTool = new DefaultCommentRenderer(link, regex);

        String result = trackingTool.render("evo-111: checkin message");
        assertThat(result).isEqualTo("<a href=\"" + "http://mingle05/projects/cce/cards/111\" "
                        + "target=\"story_tracker\">evo-111</a>: checkin message");
    }

    @Test
    public void shouldReturnMatchedStringIfRegexDoesNotHaveGroup() {
        String link = "http://mingle05/projects/cce/cards/${ID}";
        String regex = "\\d+";
        trackingTool = new DefaultCommentRenderer(link, regex);

        String result = trackingTool.render("evo-1020: checkin message");
        assertThat(result).isEqualTo("evo-<a href=\"" + "http://mingle05/projects/cce/cards/1020\" "
                + "target=\"story_tracker\">1020</a>: checkin message");
    }

    @Test
    public void shouldReturnMatchedStringFromFirstGroupIfMultipleGroupsAreDefined() {
        String link = "http://mingle05/projects/cce/cards/${ID}";
        String regex = "(\\d+)-(evo\\d+)";
        trackingTool = new DefaultCommentRenderer(link, regex);

        String result = trackingTool.render("1020-evo1: checkin message");
        assertThat(result).isEqualTo("<a href=\"" + "http://mingle05/projects/cce/cards/1020\" "
                + "target=\"story_tracker\">1020-evo1</a>: checkin message");
    }

    @Test
    public void shouldReturnOriginalStringIfRegexDoesNotMatch() {
        String link = "http://mingle05/projects/cce/cards/${ID}";
        String regex = "evo-(\\d+)";
        trackingTool = new DefaultCommentRenderer(link, regex);
        String toRender = "evo-abc: checkin message";

        String result = trackingTool.render(toRender);
        assertThat(result).isEqualTo(toRender);
    }

    @Test
    public void shouldReturnOriginalStringIfRegexIsIllegal() {
        String link = "http://mingle05/projects/cce/cards/${ID}";
        String regex = "++";
        trackingTool = new DefaultCommentRenderer(link, regex);
        String toRender = "evo-abc: checkin message";

        String result = trackingTool.render(toRender);
        assertThat(result).isEqualTo(toRender);
    }

    @Test
    public void shouldRenderUsingFixedUrlIfLinkDoesNotContainVariable() {
        String link = "http://mingle05/projects/cce/cards/wall-E";
        String regex = "(evo-\\d+)";
        trackingTool = new DefaultCommentRenderer(link, regex);

        String result = trackingTool.render("evo-111: checkin message");
        assertThat(result).isEqualTo("<a href=\"" + "http://mingle05/projects/cce/cards/wall-E\" "
                        + "target=\"story_tracker\">evo-111</a>: checkin message");
    }

    @Test
    public void shouldUseLinkFromConfigurationRegardlessOfItsValidity() {
        String link = "aaa${ID}";
        String regex = "\\d+";
        trackingTool = new DefaultCommentRenderer(link, regex);

        String result = trackingTool.render("111: checkin message");
        assertThat(result).isEqualTo("<a href=\"aaa111\" target=\"story_tracker\">111</a>: checkin message");
    }

    @Test
    // #2324
    public void shouldRenderAllPossibleMatches() {
        String link = "http://mingle05/projects/cce/cards/${ID}";
        String regex = "#(\\d+)";
        trackingTool = new DefaultCommentRenderer(link, regex);

        String result = trackingTool.render("#111, #222: checkin message; #333: another message");
        assertThat(result).isEqualTo("<a href=\"http://mingle05/projects/cce/cards/111\" target=\"story_tracker\">#111</a>, "
                        + "<a href=\"http://mingle05/projects/cce/cards/222\" "
                        + "target=\"story_tracker\">#222</a>: checkin message; "
                        + "<a href=\"http://mingle05/projects/cce/cards/333\" "
                        + "target=\"story_tracker\">#333</a>: another message");
    }

    @Test
    public void shouldReplaceBasedOnRegexInsteadOfPureStringReplacement() {
        String link = "http://mingle05/projects/cce/cards/${ID}";
        String regex = "evo-(\\d+)";
        trackingTool = new DefaultCommentRenderer(link, regex);

        String result = trackingTool.render("Replace evo-1994.  Don't replace 1994");
        assertThat(result).contains("<a href=\"http://mingle05/projects/cce/cards/1994\" "
                                + "target=\"story_tracker\">evo-1994</a>");
        assertThat(result).contains("Don't replace 1994");
    }

    @Test
    public void shouldSupportUTF8() {
        String link = "http://mingle05/projects/cce/cards/${ID}";
        String regex = "#(\\d+)";
        trackingTool = new DefaultCommentRenderer(link, regex);

        String result = trackingTool.render("The story #111 is fixed by 德里克. #122 is also related to this");
        assertThat(result).isEqualTo("The story " + dynamicLink("111") + " is fixed by " + StringEscapeUtils.escapeHtml4("德里克") + ". "
                        + dynamicLink("122") + " is also related to this");
    }

    @Test
    public void shouldEscapeTheWholeCommentIfNoneIsMatched() {
        trackingTool = new DefaultCommentRenderer("", "");
        String toRender = "some <string>";
        String result = trackingTool.render(toRender);
        assertThat(result).isEqualTo(StringEscapeUtils.escapeHtml4(toRender));
    }

    @Test
    public void shouldEscapeDynamicLink() {
        String link = "http://jira.example.com/${ID}";
        String regex = "^ABC-[^ ]+";
        trackingTool = new DefaultCommentRenderer(link, regex);

        String result = trackingTool.render("ABC-\"><svg/onload=\"alert(1)");
        assertThat(result).isEqualTo("<a href=\"http://jira.example.com/ABC-&quot;&gt;&lt;svg/onload=&quot;alert(1)\" " +
                        "target=\"story_tracker\">ABC-&quot;&gt;&lt;svg/onload=&quot;alert(1)</a>");
    }

    private String dynamicLink(String id) {
        return String.join(id, "<a href=\"http://mingle05/projects/cce/cards/", "\" target=\"story_tracker\">#", "</a>");
    }
}
