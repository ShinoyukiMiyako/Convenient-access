package com.shinoyuki.accesshub.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import com.shinoyuki.accesshub.api.ApiRouter;
import com.shinoyuki.accesshub.config.AccessHubConfig;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpServerMultipartConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void createsDedicatedMultipartDirectoryWithUploadLimits() throws Exception {
        Path multipartDirectory = tempDir.resolve("multipart");

        MultipartConfigElement config = HttpServer.createMultipartConfig(multipartDirectory);

        assertTrue(Files.isDirectory(multipartDirectory));
        assertEquals(multipartDirectory.toAbsolutePath().normalize().toString(), config.getLocation());
        assertEquals(200L * 1024 * 1024, config.getMaxFileSize());
        assertEquals(201L * 1024 * 1024, config.getMaxRequestSize());
        assertEquals(1024 * 1024, config.getFileSizeThreshold());
    }

    @Test
    void configuresRequestIdleTimeoutSoJettyCanRestorePerRequestOverrides() {
        assertEquals(30_000L, HttpServer.createHttpConfiguration(30_000L).getIdleTimeout());
        assertEquals(0L, HttpServer.createHttpConfiguration(-1L).getIdleTimeout());
    }

    @Test
    void lowConfiguredThreadLimitStillStartsAndServesPublicRequests() throws Exception {
        AccessHubConfig config = mock(AccessHubConfig.class);
        when(config.getHttpPort()).thenReturn(0);
        when(config.getHttpHost()).thenReturn("127.0.0.1");
        when(config.getMaxThreads()).thenReturn(2);
        when(config.getTimeout()).thenReturn(30_000);
        ApiRouter router = mock(ApiRouter.class);
        doAnswer(invocation -> {
            HttpServletResponse response = invocation.getArgument(1);
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return null;
        }).when(router).handleRequest(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        HttpServer server = new HttpServer(config, router, tempDir.resolve("server-multipart"));

        try {
            server.start();
            HttpResponse<Void> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + server.getLocalPort() + "/api/v1/pack/latest")).GET().build(),
                    HttpResponse.BodyHandlers.discarding());

            assertTrue(server.isRunning());
            assertEquals(HttpServletResponse.SC_NO_CONTENT, response.statusCode());
        } finally {
            server.stop();
        }
    }
}
