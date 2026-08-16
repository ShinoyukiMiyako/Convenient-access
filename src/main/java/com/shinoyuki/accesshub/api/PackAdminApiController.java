package com.shinoyuki.accesshub.api;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.shinoyuki.accesshub.pack.PackAdminException;
import com.shinoyuki.accesshub.pack.PackAdminService;
import com.shinoyuki.accesshub.pack.PackDraftRequest;
import com.shinoyuki.accesshub.pack.PackEntry;
import com.shinoyuki.accesshub.pack.PackEntryKind;
import com.shinoyuki.accesshub.pack.PackEntryPolicy;
import com.shinoyuki.accesshub.pack.PackEntryRequest;
import com.shinoyuki.accesshub.pack.PackReleaseRequest;
import com.shinoyuki.accesshub.pack.PackVersion;
import com.shinoyuki.accesshub.pack.PackVersionDiff;
import com.shinoyuki.accesshub.pack.PackVersionUpdateRequest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Authenticated HTTP boundary for modpack administration. */
public final class PackAdminApiController {

    private static final Logger logger = LoggerFactory.getLogger(PackAdminApiController.class);
    private static final Pattern INTEGER = Pattern.compile("0|[1-9][0-9]*");
    private static final Pattern DIFF_REVISION = Pattern.compile("[0-9a-f]{64}");

    private static final Set<String> DRAFT_FIELDS = Set.of(
            "version", "minecraft", "loaderKind", "loaderVersion", "note", "copyFromVersionId");
    private static final Set<String> VERSION_UPDATE_FIELDS = Set.of(
            "version", "minecraft", "loaderKind", "loaderVersion", "note");
    private static final Set<String> ENTRY_FIELDS = Set.of(
            "path", "kind", "policy", "sha1", "size", "downloadUrl",
            "platform", "projectId", "projectName", "externalVersionId");
    private static final Set<String> RELEASE_FIELDS = Set.of("confirmRemovals", "expectedDiffRevision");

    private final PackAdminService service;

    public PackAdminApiController(PackAdminService service) {
        this.service = java.util.Objects.requireNonNull(service, "service");
    }

    public void handleListVersions(HttpServletRequest request, HttpServletResponse response) throws IOException {
        execute(response, () -> {
            JsonArray versions = new JsonArray();
            service.listVersions().forEach(version -> versions.add(versionJson(version)));
            ApiSupport.sendJsonWithNulls(response, HttpServletResponse.SC_OK,
                    ApiResponse.success(versions, "整合包版本列表获取成功"));
        });
    }

    public void handleCreateVersion(HttpServletRequest request, HttpServletResponse response) throws IOException {
        execute(response, () -> {
            PackVersion created = service.createDraft(parseDraft(request));
            ApiSupport.sendJsonWithNulls(response, HttpServletResponse.SC_CREATED,
                    ApiResponse.success(versionJson(created), "整合包草稿创建成功"));
        });
    }

    public void handleUpdateVersion(String versionId, HttpServletRequest request,
                                    HttpServletResponse response) throws IOException {
        execute(response, () -> {
            PackVersion updated = service.updateDraft(parsePositiveId(versionId), parseVersionUpdate(request));
            ApiSupport.sendJsonWithNulls(response, HttpServletResponse.SC_OK,
                    ApiResponse.success(versionJson(updated), "整合包草稿更新成功"));
        });
    }

    public void handleListEntries(String versionId, HttpServletRequest request,
                                  HttpServletResponse response) throws IOException {
        execute(response, () -> {
            JsonArray entries = entriesJson(service.listEntries(parsePositiveId(versionId)));
            ApiSupport.sendJsonWithNulls(response, HttpServletResponse.SC_OK,
                    ApiResponse.success(entries, "整合包条目列表获取成功"));
        });
    }

    public void handleAddEntry(String versionId, HttpServletRequest request,
                               HttpServletResponse response) throws IOException {
        execute(response, () -> {
            PackEntry created = service.addEntry(parsePositiveId(versionId), parseEntry(request));
            ApiSupport.sendJsonWithNulls(response, HttpServletResponse.SC_CREATED,
                    ApiResponse.success(entryJson(created), "整合包条目添加成功"));
        });
    }

    public void handleUpdateEntry(String entryId, HttpServletRequest request,
                                  HttpServletResponse response) throws IOException {
        execute(response, () -> {
            PackEntry updated = service.updateEntry(parsePositiveId(entryId), parseEntry(request));
            ApiSupport.sendJsonWithNulls(response, HttpServletResponse.SC_OK,
                    ApiResponse.success(entryJson(updated), "整合包条目更新成功"));
        });
    }

    public void handleDeleteEntry(String entryId, HttpServletRequest request,
                                  HttpServletResponse response) throws IOException {
        execute(response, () -> {
            long id = parsePositiveId(entryId);
            service.deleteEntry(id);
            JsonObject data = new JsonObject();
            data.addProperty("id", id);
            data.addProperty("deleted", true);
            ApiSupport.sendJsonWithNulls(response, HttpServletResponse.SC_OK,
                    ApiResponse.success(data, "整合包条目删除成功"));
        });
    }

    public void handleDiff(String versionId, HttpServletRequest request,
                           HttpServletResponse response) throws IOException {
        execute(response, () -> {
            PackVersionDiff diff = service.diffFromPublished(parsePositiveId(versionId));
            ApiSupport.sendJsonWithNulls(response, HttpServletResponse.SC_OK,
                    ApiResponse.success(diffJson(diff), "整合包版本差异获取成功"));
        });
    }

    public void handlePublish(String versionId, HttpServletRequest request,
                              HttpServletResponse response) throws IOException {
        execute(response, () -> {
            PackReleaseRequest release = parseRelease(request);
            PackVersion published = service.publish(
                    parsePositiveId(versionId), release.confirmRemovals(), release.expectedDiffRevision());
            ApiSupport.sendJsonWithNulls(response, HttpServletResponse.SC_OK,
                    ApiResponse.success(versionJson(published), "整合包版本发布成功"));
        });
    }

    public void handleRollback(String versionId, HttpServletRequest request,
                               HttpServletResponse response) throws IOException {
        execute(response, () -> {
            PackReleaseRequest release = parseRelease(request);
            PackVersion published = service.rollback(
                    parsePositiveId(versionId), release.confirmRemovals(), release.expectedDiffRevision());
            ApiSupport.sendJsonWithNulls(response, HttpServletResponse.SC_OK,
                    ApiResponse.success(versionJson(published), "整合包版本回滚成功"));
        });
    }

    public static long parsePositiveId(String value) {
        if (value == null || !INTEGER.matcher(value).matches()) {
            throw new IllegalArgumentException("资源 ID 必须是正整数");
        }
        try {
            long id = Long.parseLong(value);
            if (id <= 0) {
                throw new IllegalArgumentException("资源 ID 必须是正整数");
            }
            return id;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("资源 ID 超出有效范围", exception);
        }
    }

    private PackDraftRequest parseDraft(HttpServletRequest request) throws IOException {
        JsonObject body = readStrictObject(request, DRAFT_FIELDS);
        return new PackDraftRequest(
                requiredString(body, "version"),
                optionalString(body, "minecraft"),
                optionalString(body, "loaderKind"),
                optionalString(body, "loaderVersion"),
                optionalString(body, "note"),
                optionalLong(body, "copyFromVersionId"));
    }

    private PackVersionUpdateRequest parseVersionUpdate(HttpServletRequest request) throws IOException {
        JsonObject body = readStrictObject(request, VERSION_UPDATE_FIELDS);
        return new PackVersionUpdateRequest(
                requiredString(body, "version"),
                requiredString(body, "minecraft"),
                requiredString(body, "loaderKind"),
                requiredString(body, "loaderVersion"),
                optionalString(body, "note"));
    }

    private PackEntryRequest parseEntry(HttpServletRequest request) throws IOException {
        JsonObject body = readStrictObject(request, ENTRY_FIELDS);
        return new PackEntryRequest(
                requiredString(body, "path"),
                PackEntryKind.fromDatabase(requiredString(body, "kind")),
                PackEntryPolicy.fromDatabase(requiredString(body, "policy")),
                requiredString(body, "sha1"),
                requiredLong(body, "size"),
                requiredString(body, "downloadUrl"),
                optionalString(body, "platform"),
                optionalString(body, "projectId"),
                optionalString(body, "projectName"),
                optionalString(body, "externalVersionId"));
    }

    private PackReleaseRequest parseRelease(HttpServletRequest request) throws IOException {
        JsonObject body = readStrictObject(request, RELEASE_FIELDS);
        JsonElement value = body.get("confirmRemovals");
        if (value == null) {
            return new PackReleaseRequest(false, parseExpectedDiffRevision(body));
        }
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isBoolean()) {
            throw new IllegalArgumentException("confirmRemovals 必须是布尔值");
        }
        return new PackReleaseRequest(primitive.getAsBoolean(), parseExpectedDiffRevision(body));
    }

    private static String parseExpectedDiffRevision(JsonObject body) {
        String revision = requiredString(body, "expectedDiffRevision");
        if (!DIFF_REVISION.matcher(revision).matches()) {
            throw new IllegalArgumentException(
                    "expectedDiffRevision must be a 64-character lowercase hexadecimal string");
        }
        return revision;
    }

    private static JsonObject readStrictObject(HttpServletRequest request, Set<String> allowedFields)
            throws IOException {
        String body = ApiSupport.readRequestBody(request);
        if (body.isBlank()) {
            throw new IllegalArgumentException("请求体必须是 JSON 对象");
        }

        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(body);
        } catch (JsonParseException exception) {
            throw new IllegalArgumentException("请求体不是有效 JSON", exception);
        }
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("请求体必须是 JSON 对象");
        }

        JsonObject object = parsed.getAsJsonObject();
        for (String field : object.keySet()) {
            if (!allowedFields.contains(field)) {
                throw new IllegalArgumentException("请求体包含未知字段: " + field);
            }
        }
        return object;
    }

    private static String requiredString(JsonObject object, String field) {
        String value = optionalString(object, field);
        if (value == null) {
            throw new IllegalArgumentException("缺少必要字符串字段: " + field);
        }
        return value;
    }

    private static String optionalString(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isString()) {
            throw new IllegalArgumentException(field + " 必须是字符串或 null");
        }
        return primitive.getAsString();
    }

    private static long requiredLong(JsonObject object, String field) {
        Long value = optionalLong(object, field);
        if (value == null) {
            throw new IllegalArgumentException("缺少必要整数字段: " + field);
        }
        return value;
    }

    private static Long optionalLong(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isNumber()
                || !INTEGER.matcher(primitive.getAsString()).matches()) {
            throw new IllegalArgumentException(field + " 必须是非负整数");
        }
        try {
            return Long.parseLong(primitive.getAsString());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " 超出有效范围", exception);
        }
    }

    private static JsonArray entriesJson(List<PackEntry> entries) {
        JsonArray result = new JsonArray();
        entries.forEach(entry -> result.add(entryJson(entry)));
        return result;
    }

    private static JsonObject versionJson(PackVersion version) {
        JsonObject json = new JsonObject();
        json.addProperty("id", version.id());
        json.addProperty("version", version.version());
        json.addProperty("status", version.status().databaseValue());
        json.addProperty("minecraft", version.minecraft());
        json.addProperty("loaderKind", version.loaderKind());
        json.addProperty("loaderVersion", version.loaderVersion());
        addNullable(json, "note", version.note());
        json.addProperty("createdAt", version.createdAt());
        addNullable(json, "publishedAt", version.publishedAt());
        return json;
    }

    private static JsonObject entryJson(PackEntry entry) {
        JsonObject json = new JsonObject();
        json.addProperty("id", entry.id());
        json.addProperty("versionId", entry.versionId());
        json.addProperty("path", entry.path());
        json.addProperty("kind", entry.kind().databaseValue());
        json.addProperty("policy", entry.policy().databaseValue());
        json.addProperty("sha1", entry.sha1());
        json.addProperty("size", entry.size());
        json.addProperty("downloadUrl", entry.downloadUrl());
        addNullable(json, "platform", entry.platform());
        addNullable(json, "projectId", entry.projectId());
        addNullable(json, "projectName", entry.projectName());
        addNullable(json, "externalVersionId", entry.externalVersionId());
        return json;
    }

    private static JsonObject diffJson(PackVersionDiff diff) {
        JsonObject json = new JsonObject();
        json.addProperty("revision", diff.revision());
        json.add("publishedVersion", diff.publishedVersion() == null
                ? JsonNull.INSTANCE : versionJson(diff.publishedVersion()));
        json.add("targetVersion", versionJson(diff.targetVersion()));
        json.add("added", entriesJson(diff.added()));
        json.add("removed", entriesJson(diff.removed()));

        JsonArray changed = new JsonArray();
        for (PackVersionDiff.EntryChange change : diff.changed()) {
            JsonObject item = new JsonObject();
            item.add("before", entryJson(change.before()));
            item.add("after", entryJson(change.after()));
            JsonArray fields = new JsonArray();
            change.changedFields().forEach(fields::add);
            item.add("changedFields", fields);
            changed.add(item);
        }
        json.add("changed", changed);
        return json;
    }

    private static void addNullable(JsonObject object, String field, String value) {
        object.add(field, value == null ? JsonNull.INSTANCE : new JsonPrimitive(value));
    }

    private static void addNullable(JsonObject object, String field, Long value) {
        object.add(field, value == null ? JsonNull.INSTANCE : new JsonPrimitive(value));
    }

    private void execute(HttpServletResponse response, Action action) throws IOException {
        try {
            action.run();
        } catch (PackAdminException exception) {
            int status = switch (exception.reason()) {
                case NOT_FOUND -> HttpServletResponse.SC_NOT_FOUND;
                case CONFLICT, IMMUTABLE_VERSION, REMOVAL_CONFIRMATION_REQUIRED, STALE_DIFF ->
                        HttpServletResponse.SC_CONFLICT;
            };
            ApiSupport.sendJson(response, status, ApiResponse.error(status, exception.getMessage()));
        } catch (IllegalArgumentException exception) {
            ApiSupport.sendJson(response, HttpServletResponse.SC_BAD_REQUEST,
                    ApiResponse.badRequest(exception.getMessage()));
        } catch (SQLException | IllegalStateException exception) {
            logger.error("处理整合包管理请求失败", exception);
            ApiSupport.sendJson(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    ApiResponse.error("整合包管理操作失败"));
        }
    }

    @FunctionalInterface
    private interface Action {
        void run() throws SQLException, IOException;
    }
}
