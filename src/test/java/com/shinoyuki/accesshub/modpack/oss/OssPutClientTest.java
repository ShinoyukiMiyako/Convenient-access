package com.shinoyuki.accesshub.modpack.oss;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OssPutClientTest {

    private static final Instant FIXED_TIME = Instant.parse("2026-08-17T04:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void uploadsToContentAddressedPathWithIntegrityHeaders() throws Exception {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        Path file = tempDir.resolve("wok-core.jar");
        Files.write(file, content);
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            captured.set(new CapturedRequest(
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Content-MD5"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestHeaders().getFirst("Content-Length"),
                    exchange.getRequestHeaders().getFirst("Date"),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestBody().readAllBytes()));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            OssPutConfig config = new OssPutConfig(
                    endpoint,
                    "example-bucket",
                    "test-key",
                    "secret",
                    URI.create("https://cdn.example.test"));
            OssPutClient client = new OssPutClient(
                    config,
                    HttpClient.newHttpClient(),
                    Clock.fixed(FIXED_TIME, ZoneOffset.UTC));

            OssPutClient.UploadResult result = client.upload(file);

            String sha1 = "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d";
            String key = "files/aa/f4/" + sha1;
            assertEquals(key, result.objectKey());
            assertEquals(sha1, result.sha1());
            assertEquals(5, result.size());
            assertEquals("https://cdn.example.test/" + key, result.downloadUrl());
            assertEquals("/" + key, captured.get().path());
            assertEquals("XUFAKrxLKna5cZ2REBfFkg==", captured.get().contentMd5());
            assertEquals("application/octet-stream", captured.get().contentType());
            assertEquals("5", captured.get().contentLength());
            assertEquals("Mon, 17 Aug 2026 04:00:00 GMT", captured.get().date());
            assertEquals("OSS test-key:9sbn0y0GeG+8mz9MukKZ3RCzkk4=", captured.get().authorization());
            assertArrayEquals(content, captured.get().body());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsFilesAboveConfiguredHardLimitBeforeNetworkIo() throws Exception {
        Path file = tempDir.resolve("too-large.jar");
        try (var channel = java.nio.channels.FileChannel.open(
                file,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE)) {
            channel.position(OssPutClient.MAX_FILE_SIZE);
            channel.write(java.nio.ByteBuffer.wrap(new byte[] { 0 }));
        }
        OssPutClient client = new OssPutClient(new OssPutConfig(
                URI.create("https://example-bucket.oss-cn-hangzhou.aliyuncs.com"),
                "example-bucket",
                "test-key",
                "secret",
                URI.create("https://cdn.example.test")));

        IOException error = assertThrows(IOException.class, () -> client.upload(file));

        assertTrue(error.getMessage().contains("200 MB"));
    }

    @Test
    void validatesContentAddressSha1() {
        assertEquals(
                "files/ab/cd/abcdefabcdefabcdefabcdefabcdefabcdefabcd",
                OssPutClient.contentAddressedKey("ABCDEFABCDEFABCDEFABCDEFABCDEFABCDEFABCD"));
        assertThrows(IllegalArgumentException.class, () -> OssPutClient.contentAddressedKey("../file"));
    }

    @Test
    void reportsOssDiagnosticHeadersWithoutLeakingResponseBody() throws Exception {
        Path file = tempDir.resolve("failed.jar");
        Files.writeString(file, "hello", StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().transferTo(java.io.OutputStream.nullOutputStream());
            exchange.getResponseHeaders().set("x-oss-ec", "SignatureDoesNotMatch");
            exchange.getResponseHeaders().set("x-oss-request-id", "request-123");
            byte[] body = "body-must-not-be-forwarded secret-marker".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            OssPutClient client = new OssPutClient(
                    new OssPutConfig(
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                            "example-bucket",
                            "test-key",
                            "secret",
                            URI.create("https://cdn.example.test")),
                    HttpClient.newHttpClient(),
                    Clock.fixed(FIXED_TIME, ZoneOffset.UTC));

            IOException error = assertThrows(IOException.class, () -> client.upload(file));

            assertTrue(error.getMessage().contains("HTTP 403"));
            assertTrue(error.getMessage().contains("SignatureDoesNotMatch"));
            assertTrue(error.getMessage().contains("request-123"));
            assertFalse(error.getMessage().contains("secret-marker"));
        } finally {
            server.stop(0);
        }
    }

    private record CapturedRequest(
            String path,
            String contentMd5,
            String contentType,
            String contentLength,
            String date,
            String authorization,
            byte[] body) {
    }
}
