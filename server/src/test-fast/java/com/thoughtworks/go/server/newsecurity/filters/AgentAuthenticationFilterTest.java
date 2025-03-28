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
package com.thoughtworks.go.server.newsecurity.filters;

import com.thoughtworks.go.ClearSingleton;
import com.thoughtworks.go.config.ServerConfig;
import com.thoughtworks.go.http.mocks.HttpRequestBuilder;
import com.thoughtworks.go.server.newsecurity.models.AgentToken;
import com.thoughtworks.go.server.newsecurity.models.AuthenticationToken;
import com.thoughtworks.go.server.newsecurity.utils.SessionUtils;
import com.thoughtworks.go.server.security.GoAuthority;
import com.thoughtworks.go.server.security.userdetail.GoUserPrincipal;
import com.thoughtworks.go.server.service.AgentService;
import com.thoughtworks.go.server.service.GoConfigService;
import com.thoughtworks.go.util.TestingClock;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpSession;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(ClearSingleton.class)
@ExtendWith(MockitoExtension.class)
public class AgentAuthenticationFilterTest {
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final FilterChain filterChain = mock(FilterChain.class);
    private final TestingClock clock = new TestingClock();

    @Mock
    private GoConfigService goConfigService;

    @Mock
    private AgentService agentService;

    @Nested
    class TokenBased {
        @Test
        void shouldPopulateAgentUserInSessionIfAgentExistsInConfig() throws Exception {
            ServerConfig serverConfig = new ServerConfig();
            serverConfig.ensureTokenGenerationKeyExists();

            when(goConfigService.serverConfig()).thenReturn(serverConfig);
            when(agentService.isRegistered("blah")).thenReturn(true);

            AgentAuthenticationFilter filter = new AgentAuthenticationFilter(goConfigService, clock, agentService);

            final MockHttpServletRequest request = HttpRequestBuilder.GET("/")
                    .withHeader("X-Agent-GUID", "blah")
                    .withHeader("Authorization", filter.hmacOf("blah"))
                    .build();

            filter.doFilter(request, response, filterChain);

            final AuthenticationToken<?> authentication = SessionUtils.getAuthenticationToken(request);
            assertThat(authentication.getUser().getUsername())
                    .isEqualTo("_go_agent_blah");
            assertThat(authentication.getUser().getAuthorities())
                    .hasSize(1)
                    .contains(GoAuthority.ROLE_AGENT.asAuthority());
            verify(filterChain).doFilter(request, response);
            assertThat(response.getStatus()).isEqualTo(200);
        }

        @Test
        void shouldRejectRequestIfUUIDIsNotInConfig() throws Exception {
            ServerConfig serverConfig = new ServerConfig();
            serverConfig.ensureTokenGenerationKeyExists();

            when(goConfigService.serverConfig()).thenReturn(serverConfig);
            when(agentService.isRegistered("blah")).thenReturn(false);

            AgentAuthenticationFilter filter = new AgentAuthenticationFilter(goConfigService, clock, agentService);

            final MockHttpServletRequest request = HttpRequestBuilder.GET("/")
                    .withHeader("X-Agent-GUID", "blah")
                    .withHeader("Authorization", filter.hmacOf("blah"))
                    .build();

            filter.doFilter(request, response, filterChain);

            final AuthenticationToken<?> authentication = SessionUtils.getAuthenticationToken(request);
            assertThat(authentication).isNull();
            verifyNoInteractions(filterChain);
            assertThat(response.getStatus()).isEqualTo(403);
        }

        @Test
        void shouldRejectRequestWith403IfCredentialsAreBad() throws Exception {
            ServerConfig serverConfig = new ServerConfig();
            serverConfig.ensureTokenGenerationKeyExists();

            when(goConfigService.serverConfig()).thenReturn(serverConfig);
            when(agentService.isRegistered("blah")).thenReturn(true);

            AgentAuthenticationFilter filter = new AgentAuthenticationFilter(goConfigService, clock, agentService);

            final MockHttpServletRequest request = HttpRequestBuilder.GET("/")
                    .withHeader("X-Agent-GUID", "blah")
                    .withHeader("Authorization", "bad-authorization")
                    .build();

            filter.doFilter(request, response, filterChain);

            assertThat(SessionUtils.getAuthenticationToken(request)).isNull();
            verifyNoInteractions(filterChain);
            assertThat(response.getStatus()).isEqualTo(403);
        }

        @Test
        void shouldReauthenticateIfCredentialsAreProvidedInRequestEvenIfRequestWasPreviouslyAuthenticatedAsANormalUser() throws ServletException, IOException {
            final MockHttpServletRequest originalRequest = HttpRequestBuilder.GET("/")
                    .build();

            HttpSession originalSession = originalRequest.getSession(true);
            com.thoughtworks.go.server.newsecurity.SessionUtilsHelper.loginAsRandomUser(originalRequest);

            String uuid = "blah";

            ServerConfig serverConfig = new ServerConfig();
            serverConfig.ensureTokenGenerationKeyExists();

            when(agentService.isRegistered(uuid)).thenReturn(true);
            when(goConfigService.serverConfig()).thenReturn(serverConfig);

            AgentAuthenticationFilter authenticationFilter = new AgentAuthenticationFilter(goConfigService, clock, agentService);

            String token = authenticationFilter.hmacOf(uuid);

            final MockHttpServletRequest request = HttpRequestBuilder.GET("/")
                    .withSession(originalSession)
                    .withHeader("X-Agent-GUID", uuid)
                    .withHeader("Authorization", token)
                    .build();

            authenticationFilter.doFilter(request, response, filterChain);

            final AuthenticationToken<?> authentication = SessionUtils.getAuthenticationToken(request);
            assertThat(authentication.getUser().getUsername())
                    .isEqualTo("_go_agent_blah");
            assertThat(authentication.getUser().getAuthorities())
                    .hasSize(1)
                    .contains(GoAuthority.ROLE_AGENT.asAuthority());
            verify(filterChain).doFilter(request, response);
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(request.getSession(false)).isNotSameAs(originalSession);
        }

        @Test
        void shouldReAuthenticateIfSessionContainsTheSameTokenAsInRequest() throws ServletException, IOException {
            String uuid = "blah";

            ServerConfig serverConfig = new ServerConfig();
            serverConfig.ensureTokenGenerationKeyExists();

            when(goConfigService.serverConfig()).thenReturn(serverConfig);
            when(agentService.isRegistered(uuid)).thenReturn(true);

            AgentAuthenticationFilter filter = new AgentAuthenticationFilter(goConfigService, clock, agentService);

            MockHttpSession existingSession = new MockHttpSession();
            GoUserPrincipal goodAgentPrincipal = new GoUserPrincipal("_go_agent_blah", "");
            String token = filter.hmacOf(uuid);

            SessionUtils.setAuthenticationTokenWithoutRecreatingSession(new AuthenticationToken<>(goodAgentPrincipal, new AgentToken(uuid, token), null, 0, null), HttpRequestBuilder.GET("/dont-care").withSession(existingSession).build());

            final MockHttpServletRequest request = HttpRequestBuilder.GET("/")
                    .withHeader("X-Agent-GUID", uuid)
                    .withHeader("Authorization", token)
                    .withSession(existingSession)
                    .build();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(200);
            verify(filterChain).doFilter(any(), any());
            assertThat(request.getSession(false)).isSameAs(existingSession);
        }
    }

}
