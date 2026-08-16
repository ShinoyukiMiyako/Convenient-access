package com.shinoyuki.accesshub.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.junit.jupiter.api.Test;

import com.shinoyuki.accesshub.config.AccessHubConfig;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class ApiRouterPackPublicTest {

    @Test
    void getLatestBypassesAuthenticationAndUsesThePackHandler() throws Exception {
        PackPublicApiHandler handler = mock(PackPublicApiHandler.class);
        AccessHubConfig config = authenticatedConfig();
        ApiRouter router = router(handler, config);
        HttpServletRequest request = request("GET", "/api/v1/pack/latest");
        HttpServletResponse response = mock(HttpServletResponse.class);

        router.handleRequest(request, response);

        verify(handler).handleLatest(request, response);
        verify(request, never()).getHeader("Authorization");
        verify(request, never()).getHeader("X-API-Key");
    }

    @Test
    void getManifestBypassesAuthenticationOnlyForOneSafeVersionSegment() throws Exception {
        PackPublicApiHandler handler = mock(PackPublicApiHandler.class);
        AccessHubConfig config = authenticatedConfig();
        ApiRouter router = router(handler, config);
        HttpServletRequest request = request("GET", "/api/v1/pack/manifest/2.0.0");
        HttpServletResponse response = mock(HttpServletResponse.class);

        router.handleRequest(request, response);

        verify(handler).handleManifest(request, response, "2.0.0");
        verify(request, never()).getHeader("Authorization");
    }

    @Test
    void malformedManifestPathIsNotInThePublicWhitelist() throws Exception {
        PackPublicApiHandler handler = mock(PackPublicApiHandler.class);
        ApiRouter router = router(handler, authenticatedConfig());
        HttpServletRequest request = request("GET", "/api/v1/pack/manifest/2.0.0/");
        StringWriter body = new StringWriter();
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        router.handleRequest(request, response);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(handler, never()).handleManifest(any(), any(), any());
        assertEquals("{\"success\":false,\"error\":\"Unauthorized: Invalid API key or token\"}",
                body.toString());
    }

    @Test
    void nonGetLatestRequestIsNotInThePublicWhitelist() throws Exception {
        PackPublicApiHandler handler = mock(PackPublicApiHandler.class);
        ApiRouter router = router(handler, authenticatedConfig());
        HttpServletRequest request = request("POST", "/api/v1/pack/latest");
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        router.handleRequest(request, response);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(handler, never()).handleLatest(any(), any());
    }

    private static AccessHubConfig authenticatedConfig() {
        AccessHubConfig config = mock(AccessHubConfig.class);
        when(config.isAuthEnabled()).thenReturn(true);
        return config;
    }

    private static HttpServletRequest request(String method, String path) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getPathInfo()).thenReturn(path);
        return request;
    }

    private static ApiRouter router(PackPublicApiHandler handler, AccessHubConfig config) {
        return new ApiRouter(null, null, null, null, null, null, null, null, null,
                handler, null, config);
    }
}
