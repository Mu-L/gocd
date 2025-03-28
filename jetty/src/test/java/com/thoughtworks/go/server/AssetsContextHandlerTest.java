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
package com.thoughtworks.go.server;

import com.thoughtworks.go.util.ReflectionUtil;
import com.thoughtworks.go.util.SystemEnvironment;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AssetsContextHandlerTest {

    private AssetsContextHandler handler;
    @Mock
    private SystemEnvironment systemEnvironment;
    @Mock
    private WebAppContext webAppContext;

    @BeforeEach
    public void setUp() throws Exception {
        when(systemEnvironment.getWebappContextPath()).thenReturn("/go");
        when(webAppContext.getInitParameter("rails.root")).thenReturn("/rails.root");
        when(webAppContext.getWebInf()).thenReturn(Resource.newResource("WEB-INF"));
        handler = new AssetsContextHandler(systemEnvironment);

        handler.init(webAppContext);
    }

    @Test
    public void shouldSetHeadersAndBaseDirectory() {
        assertThat(handler.getContextPath()).isEqualTo("/go/assets");
        assertThat(((HandlerWrapper) handler.getHandler()).getHandler() instanceof AssetsContextHandler.AssetsHandler).isEqualTo(true);
        AssetsContextHandler.AssetsHandler assetsHandler = (AssetsContextHandler.AssetsHandler) ((HandlerWrapper) handler.getHandler()).getHandler();
        ResourceHandler resourceHandler = ReflectionUtil.getField(assetsHandler, "resourceHandler");
        assertThat(resourceHandler.getCacheControl()).isEqualTo("max-age=31536000,public");
        assertThat(resourceHandler.getResourceBase()).isEqualTo(new File("WEB-INF/rails.root/public/assets").toPath().toAbsolutePath().toUri().toString());
    }

    @Test
    public void shouldPassOverHandlingToResourceHandler() throws Exception {
        String target = "/go/assets/junk";
        Request request = mock(Request.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        Request baseRequest = mock(Request.class);
        AssetsContextHandler.AssetsHandler resourceHandler = mock(AssetsContextHandler.AssetsHandler.class);
        handler.setHandler(resourceHandler);

        handler.getHandler().handle(target, baseRequest, request, response);
        verify(resourceHandler).handle(target, baseRequest, request, response);
    }

    @Test
    public void shouldNotHandleForRails4DevelopmentMode() throws IOException, ServletException {
        when(systemEnvironment.useCompressedJs()).thenReturn(false);

        String target = "/go/assets/junk";
        Request request = mock(Request.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        Request baseRequest = mock(Request.class);
        ResourceHandler resourceHandler = mock(ResourceHandler.class);
        ReflectionUtil.setField(((HandlerWrapper) handler.getHandler()).getHandler(), "resourceHandler", resourceHandler);

        handler.getHandler().handle(target, baseRequest, request, response);
        verify(resourceHandler, never()).handle(any(String.class), any(Request.class), any(HttpServletRequest.class), any(HttpServletResponse.class));
    }
}
