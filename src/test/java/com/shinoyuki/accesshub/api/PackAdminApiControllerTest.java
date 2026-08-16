package com.shinoyuki.accesshub.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.shinoyuki.accesshub.pack.PackAdminException;
import com.shinoyuki.accesshub.pack.PackAdminService;
import com.shinoyuki.accesshub.pack.PackDraftRequest;
import com.shinoyuki.accesshub.pack.PackEntry;
import com.shinoyuki.accesshub.pack.PackEntryKind;
import com.shinoyuki.accesshub.pack.PackEntryPolicy;
import com.shinoyuki.accesshub.pack.PackVersion;
import com.shinoyuki.accesshub.pack.PackVersionDiff;
import com.shinoyuki.accesshub.pack.PackVersionStatus;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class PackAdminApiControllerTest {

    private static final String DIFF_REVISION = "a".repeat(64);

    @Test
    void createDraftParsesTheStrictCamelCaseContract() throws Exception {
        PackAdminService service = mock(PackAdminService.class);
        PackVersion created = version(7L, PackVersionStatus.DRAFT);
        PackDraftRequest expected = new PackDraftRequest(
                "2.0.0", "1.20.1", "forge", "47.4.20", "新周目", null);
        when(service.createDraft(expected)).thenReturn(created);
        ResponseCapture capture = new ResponseCapture();

        new PackAdminApiController(service).handleCreateVersion(request("""
                {"version":"2.0.0","minecraft":"1.20.1","loaderKind":"forge",
                 "loaderVersion":"47.4.20","note":"新周目"}
                """), capture.response);

        verify(service).createDraft(expected);
        verify(capture.response).setStatus(HttpServletResponse.SC_CREATED);
        JsonObject data = envelope(capture).getAsJsonObject("data");
        assertEquals("draft", data.get("status").getAsString());
        assertEquals("forge", data.get("loaderKind").getAsString());
    }

    @Test
    void listEntriesSerializesKindAndPolicyAsLowercaseWireValues() throws Exception {
        PackAdminService service = mock(PackAdminService.class);
        when(service.listEntries(7L)).thenReturn(List.of(new PackEntry(
                9L, 7L, "mods/wok.jar", PackEntryKind.CUSTOM, PackEntryPolicy.MANAGED,
                "a".repeat(40), 42L, "https://files.example.test/wok.jar",
                null, null, null, null)));
        ResponseCapture capture = new ResponseCapture();

        new PackAdminApiController(service)
                .handleListEntries("7", mock(HttpServletRequest.class), capture.response);

        JsonObject entry = envelope(capture).getAsJsonArray("data").get(0).getAsJsonObject();
        assertEquals("custom", entry.get("kind").getAsString());
        assertEquals("managed", entry.get("policy").getAsString());
        assertEquals(12, entry.size());
    }

    @Test
    void publishForwardsExplicitRemovalConfirmation() throws Exception {
        PackAdminService service = mock(PackAdminService.class);
        when(service.publish(7L, true, DIFF_REVISION))
                .thenReturn(version(7L, PackVersionStatus.PUBLISHED));
        ResponseCapture capture = new ResponseCapture();

        new PackAdminApiController(service).handlePublish("7",
                request("{\"confirmRemovals\":true,\"expectedDiffRevision\":\""
                        + DIFF_REVISION + "\"}"), capture.response);

        verify(service).publish(7L, true, DIFF_REVISION);
        assertEquals("published",
                envelope(capture).getAsJsonObject("data").get("status").getAsString());
    }

    @Test
    void rollbackForwardsTheReviewedDiffRevision() throws Exception {
        PackAdminService service = mock(PackAdminService.class);
        when(service.rollback(7L, false, DIFF_REVISION))
                .thenReturn(version(7L, PackVersionStatus.PUBLISHED));
        ResponseCapture capture = new ResponseCapture();

        new PackAdminApiController(service).handleRollback("7",
                request("{\"confirmRemovals\":false,\"expectedDiffRevision\":\""
                        + DIFF_REVISION + "\"}"), capture.response);

        verify(service).rollback(7L, false, DIFF_REVISION);
        verify(capture.response).setStatus(HttpServletResponse.SC_OK);
    }

    @Test
    void diffResponseIncludesTheRevisionRequiredForRelease() throws Exception {
        PackAdminService service = mock(PackAdminService.class);
        PackVersion target = version(7L, PackVersionStatus.DRAFT);
        when(service.diffFromPublished(7L)).thenReturn(new PackVersionDiff(
                DIFF_REVISION, null, target, List.of(), List.of(), List.of()));
        ResponseCapture capture = new ResponseCapture();

        new PackAdminApiController(service)
                .handleDiff("7", mock(HttpServletRequest.class), capture.response);

        assertEquals(DIFF_REVISION,
                envelope(capture).getAsJsonObject("data").get("revision").getAsString());
    }

    @Test
    void missingOrMalformedDiffRevisionReturns400BeforeRelease() throws Exception {
        PackAdminService service = mock(PackAdminService.class);
        PackAdminApiController controller = new PackAdminApiController(service);
        ResponseCapture missing = new ResponseCapture();
        ResponseCapture malformed = new ResponseCapture();

        controller.handlePublish("7", request("{\"confirmRemovals\":false}"), missing.response);
        controller.handleRollback("7",
                request("{\"confirmRemovals\":false,\"expectedDiffRevision\":\"ABC\"}"),
                malformed.response);

        verify(missing.response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        verify(malformed.response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        verify(service, never()).publish(anyLong(), anyBoolean(), anyString());
        verify(service, never()).rollback(anyLong(), anyBoolean(), anyString());
    }

    @Test
    void invalidEnumAndUnknownFieldsReturn400BeforeCallingTheService() throws Exception {
        PackAdminService service = mock(PackAdminService.class);
        PackAdminApiController controller = new PackAdminApiController(service);
        ResponseCapture invalidEnum = new ResponseCapture();
        ResponseCapture unknownField = new ResponseCapture();

        controller.handleAddEntry("7", request("""
                {"path":"mods/wok.jar","kind":"CUSTOM","policy":"managed",
                 "sha1":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","size":42,
                 "downloadUrl":"https://files.example.test/wok.jar"}
                """), invalidEnum.response);
        controller.handleCreateVersion(request("""
                {"version":"2.0.0","minecraft":"1.20.1","loaderKind":"forge",
                 "loaderVersion":"47.4.20","unexpected":true}
                """), unknownField.response);

        verify(invalidEnum.response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        verify(unknownField.response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        verify(service, never()).addEntry(anyLong(), any());
        verify(service, never()).createDraft(any());
    }

    @Test
    void invalidIdReturns400() throws Exception {
        PackAdminService service = mock(PackAdminService.class);
        ResponseCapture capture = new ResponseCapture();

        new PackAdminApiController(service)
                .handleListEntries("1.5", mock(HttpServletRequest.class), capture.response);

        verify(capture.response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        verify(service, never()).listEntries(anyLong());
    }

    @Test
    void expectedAdminFailuresMapTo404And409() throws Exception {
        PackAdminService service = mock(PackAdminService.class);
        when(service.listEntries(8L)).thenThrow(new PackAdminException(
                PackAdminException.Reason.NOT_FOUND, "整合包版本不存在: 8"));
        when(service.publish(7L, false, DIFF_REVISION)).thenThrow(new PackAdminException(
                PackAdminException.Reason.REMOVAL_CONFIRMATION_REQUIRED, "必须显式确认"));
        when(service.rollback(7L, false, DIFF_REVISION)).thenThrow(new PackAdminException(
                PackAdminException.Reason.STALE_DIFF, "diff changed"));
        PackAdminApiController controller = new PackAdminApiController(service);
        ResponseCapture notFound = new ResponseCapture();
        ResponseCapture conflict = new ResponseCapture();
        ResponseCapture stale = new ResponseCapture();

        controller.handleListEntries("8", mock(HttpServletRequest.class), notFound.response);
        String releaseBody = "{\"confirmRemovals\":false,\"expectedDiffRevision\":\""
                + DIFF_REVISION + "\"}";
        controller.handlePublish("7", request(releaseBody), conflict.response);
        controller.handleRollback("7", request(releaseBody), stale.response);

        verify(notFound.response).setStatus(HttpServletResponse.SC_NOT_FOUND);
        verify(conflict.response).setStatus(HttpServletResponse.SC_CONFLICT);
        verify(stale.response).setStatus(HttpServletResponse.SC_CONFLICT);
        assertEquals(404, envelope(notFound).get("code").getAsInt());
        assertEquals(409, envelope(conflict).get("code").getAsInt());
        assertEquals(409, envelope(stale).get("code").getAsInt());
    }

    @Test
    void databaseFailureReturns500WithoutLeakingDetails() throws Exception {
        PackAdminService service = mock(PackAdminService.class);
        when(service.listVersions()).thenThrow(new SQLException("database path and secret"));
        ResponseCapture capture = new ResponseCapture();

        new PackAdminApiController(service)
                .handleListVersions(mock(HttpServletRequest.class), capture.response);

        verify(capture.response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        JsonObject response = envelope(capture);
        assertEquals("整合包管理操作失败", response.get("error").getAsString());
    }

    private static HttpServletRequest request(String body) throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getReader()).thenReturn(new BufferedReader(new StringReader(body)));
        return request;
    }

    private static JsonObject envelope(ResponseCapture capture) {
        return JsonParser.parseString(capture.body.toString()).getAsJsonObject();
    }

    private static PackVersion version(long id, PackVersionStatus status) {
        return new PackVersion(id, "2.0.0", status, "1.20.1", "forge", "47.4.20",
                null, 1_700_000_000L,
                status == PackVersionStatus.DRAFT ? null : 1_755_432_000L);
    }

    private static final class ResponseCapture {
        private final StringWriter body = new StringWriter();
        private final HttpServletResponse response = mock(HttpServletResponse.class);

        private ResponseCapture() throws Exception {
            when(response.getWriter()).thenReturn(new PrintWriter(body));
        }
    }
}
