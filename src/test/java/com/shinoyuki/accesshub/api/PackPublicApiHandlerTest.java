package com.shinoyuki.accesshub.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.shinoyuki.accesshub.pack.PackEntry;
import com.shinoyuki.accesshub.pack.PackEntryKind;
import com.shinoyuki.accesshub.pack.PackEntryPolicy;
import com.shinoyuki.accesshub.pack.PackManifestRepository;
import com.shinoyuki.accesshub.pack.PackVersion;
import com.shinoyuki.accesshub.pack.PackVersionStatus;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class PackPublicApiHandlerTest {

    @Test
    void latestUsesTheUnwrappedWireContractAndIncludesNullNote() throws Exception {
        PackManifestRepository repository = mock(PackManifestRepository.class);
        when(repository.findCurrentPublished()).thenReturn(Optional.of(new PackVersion(
                1L, "2.0.0", PackVersionStatus.PUBLISHED, "1.20.1", "forge", "47.4.20",
                null, 1_700_000_000L, 1_755_432_000L)));
        ResponseCapture capture = new ResponseCapture();

        new PackPublicApiHandlerImpl(new PackPublicService(repository))
                .handleLatest(mock(HttpServletRequest.class), capture.response);

        JsonObject json = JsonParser.parseString(capture.body.toString()).getAsJsonObject();
        assertEquals(200, capture.status());
        assertEquals("application/json", capture.contentType());
        assertEquals("no-cache, no-store", capture.header("Cache-Control"));
        assertEquals(6, json.size());
        assertEquals("wok", json.get("pack_id").getAsString());
        assertEquals("2.0.0", json.get("version").getAsString());
        assertEquals("https://api.mcwok.cn/api/v1/pack/manifest/2.0.0",
                json.get("manifest_url").getAsString());
        assertEquals("2025-08-17T12:00:00Z", json.get("released_at").getAsString());
        assertTrue(json.get("note").isJsonNull());
        assertEquals("0.1.0", json.get("min_launcher_version").getAsString());
    }

    @Test
    void manifestUsesTheUnwrappedWireContract() throws Exception {
        PackManifestRepository repository = mock(PackManifestRepository.class);
        when(repository.findReleasedByVersion("2.0.0")).thenReturn(Optional.of(new PackVersion(
                1L, "2.0.0", PackVersionStatus.PUBLISHED, "1.20.1", "forge", "47.4.20",
                null, 1_700_000_000L, 1_755_432_000L)));
        when(repository.findEntriesByVersionId(1L)).thenReturn(List.of(new PackEntry(
                2L, 1L, "mods/wok-core.jar", PackEntryKind.CUSTOM, PackEntryPolicy.MANAGED,
                "a".repeat(40), 42L, "https://files.example.test/wok-core.jar",
                null, null, null, null)));
        ResponseCapture capture = new ResponseCapture();

        new PackPublicApiHandlerImpl(new PackPublicService(repository))
                .handleManifest(mock(HttpServletRequest.class), capture.response, "2.0.0");

        JsonObject json = JsonParser.parseString(capture.body.toString()).getAsJsonObject();
        assertEquals(200, capture.status());
        assertEquals("public, max-age=31536000, immutable", capture.header("Cache-Control"));
        assertEquals(6, json.size());
        assertEquals(1, json.get("schema").getAsInt());
        assertEquals("wok", json.get("pack_id").getAsString());
        assertEquals("forge", json.getAsJsonObject("loader").get("kind").getAsString());
        JsonObject file = json.getAsJsonArray("files").get(0).getAsJsonObject();
        assertEquals(5, file.size());
        assertEquals("managed", file.get("policy").getAsString());
        assertEquals("https://files.example.test/wok-core.jar",
                file.getAsJsonArray("urls").get(0).getAsString());
    }

    @Test
    void noPublishedVersionReturns404() throws Exception {
        PackManifestRepository repository = mock(PackManifestRepository.class);
        when(repository.findCurrentPublished()).thenReturn(Optional.empty());
        ResponseCapture capture = new ResponseCapture();

        new PackPublicApiHandlerImpl(new PackPublicService(repository))
                .handleLatest(mock(HttpServletRequest.class), capture.response);

        JsonObject json = JsonParser.parseString(capture.body.toString()).getAsJsonObject();
        assertEquals(404, capture.status());
        assertEquals(false, json.get("success").getAsBoolean());
        assertEquals(404, json.get("code").getAsInt());
        assertEquals("No published pack version", json.get("error").getAsString());
        assertEquals("no-cache, no-store", capture.header("Cache-Control"));
    }

    @Test
    void unknownManifestCannotBeNegativelyCached() throws Exception {
        PackManifestRepository repository = mock(PackManifestRepository.class);
        when(repository.findReleasedByVersion("2.1.0")).thenReturn(Optional.empty());
        ResponseCapture capture = new ResponseCapture();

        new PackPublicApiHandlerImpl(new PackPublicService(repository))
                .handleManifest(mock(HttpServletRequest.class), capture.response, "2.1.0");

        JsonObject json = JsonParser.parseString(capture.body.toString()).getAsJsonObject();
        assertEquals(404, capture.status());
        assertEquals("no-store", capture.header("Cache-Control"));
        assertEquals("Pack version not found", json.get("error").getAsString());
    }

    private static final class ResponseCapture {
        private final StringWriter body = new StringWriter();
        private final HttpServletResponse response = mock(HttpServletResponse.class);

        private ResponseCapture() throws Exception {
            when(response.getWriter()).thenReturn(new PrintWriter(body));
        }

        private int status() {
            return org.mockito.Mockito.mockingDetails(response).getInvocations().stream()
                    .filter(invocation -> invocation.getMethod().getName().equals("setStatus"))
                    .reduce((first, second) -> second)
                    .map(invocation -> (Integer) invocation.getArgument(0))
                    .orElseThrow();
        }

        private String contentType() {
            return org.mockito.Mockito.mockingDetails(response).getInvocations().stream()
                    .filter(invocation -> invocation.getMethod().getName().equals("setContentType"))
                    .reduce((first, second) -> second)
                    .map(invocation -> (String) invocation.getArgument(0))
                    .orElse(null);
        }

        private String header(String name) {
            return org.mockito.Mockito.mockingDetails(response).getInvocations().stream()
                    .filter(invocation -> invocation.getMethod().getName().equals("setHeader"))
                    .filter(invocation -> name.equals(invocation.getArgument(0)))
                    .reduce((first, second) -> second)
                    .map(invocation -> (String) invocation.getArgument(1))
                    .orElse(null);
        }
    }
}
