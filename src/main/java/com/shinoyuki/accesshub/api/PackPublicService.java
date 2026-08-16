package com.shinoyuki.accesshub.api;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.shinoyuki.accesshub.pack.PackEntry;
import com.shinoyuki.accesshub.pack.PackEntryValidator;
import com.shinoyuki.accesshub.pack.PackManifestRepository;
import com.shinoyuki.accesshub.pack.PackPathValidator;
import com.shinoyuki.accesshub.pack.PackVersion;
import com.shinoyuki.accesshub.pack.PackVersionStatus;
import com.shinoyuki.accesshub.pack.PackVersionValidator;

/** Builds the public wire models from immutable released pack data. */
public final class PackPublicService {

    public static final String PACK_ID = "wok";
    public static final int MANIFEST_SCHEMA = 1;
    public static final String MIN_LAUNCHER_VERSION = "0.1.0";

    private static final String MANIFEST_ROOT = "https://api.mcwok.cn/api/v1/pack/manifest/";

    private final PackManifestRepository repository;

    public PackPublicService(PackManifestRepository repository) {
        this.repository = java.util.Objects.requireNonNull(repository, "repository");
    }

    public Optional<PackLatestResponse> getLatest() throws SQLException {
        Optional<PackVersion> published = repository.findCurrentPublished();
        if (published.isEmpty()) {
            return Optional.empty();
        }

        PackVersion pack = published.get();
        if (pack.status() != PackVersionStatus.PUBLISHED) {
            throw new IllegalStateException("Current pack query returned a non-published version");
        }
        String version = PackVersionValidator.requireValidVersion(pack.version());
        Long publishedAt = pack.publishedAt();
        if (publishedAt == null) {
            throw new IllegalStateException("Published pack version has no publication timestamp");
        }

        return Optional.of(new PackLatestResponse(
                PACK_ID,
                version,
                MANIFEST_ROOT + encodePathSegment(version),
                Instant.ofEpochSecond(publishedAt).toString(),
                PackVersionValidator.requireValidNote(pack.note()),
                MIN_LAUNCHER_VERSION));
    }

    public Optional<PackManifestResponse> getManifest(String version) throws SQLException {
        if (!isValidVersion(version)) {
            return Optional.empty();
        }

        Optional<PackVersion> released = repository.findReleasedByVersion(version);
        if (released.isEmpty()) {
            return Optional.empty();
        }

        PackVersion pack = released.get();
        if (pack.status() != PackVersionStatus.PUBLISHED
                && pack.status() != PackVersionStatus.ARCHIVED) {
            throw new IllegalStateException("Released pack query returned a draft version");
        }
        if (!version.equals(pack.version())) {
            throw new IllegalStateException("Pack query returned a different version");
        }

        List<PackEntry> entries = new ArrayList<>(repository.findEntriesByVersionId(pack.id()));
        entries.sort(Comparator.comparing(PackEntry::path));

        Set<String> paths = new HashSet<>();
        List<PackManifestResponse.FileEntry> files = new ArrayList<>(entries.size());
        for (PackEntry entry : entries) {
            if (entry.versionId() != pack.id()) {
                throw new IllegalStateException("Pack entry belongs to a different version");
            }

            String path = PackPathValidator.requireValid(entry.path());
            if (!paths.add(path)) {
                throw new IllegalStateException("Duplicate path in released pack: " + path);
            }
            String sha1 = PackEntryValidator.requireValidSha1(entry.sha1());
            long size = PackEntryValidator.requireValidSize(entry.size());
            String downloadUrl = PackEntryValidator.requireValidDownloadUrl(entry.downloadUrl());
            if (entry.policy() == null) {
                throw new IllegalStateException("Pack entry has no file policy: " + path);
            }

            files.add(new PackManifestResponse.FileEntry(
                    path,
                    sha1,
                    size,
                    entry.policy().databaseValue(),
                    List.of(downloadUrl)));
        }

        return Optional.of(new PackManifestResponse(
                MANIFEST_SCHEMA,
                PACK_ID,
                PackVersionValidator.requireValidVersion(pack.version()),
                PackVersionValidator.requireValidMinecraft(pack.minecraft()),
                new PackManifestResponse.Loader(
                        PackVersionValidator.requireValidLoaderKind(pack.loaderKind()),
                        PackVersionValidator.requireValidLoaderVersion(pack.loaderVersion())),
                files));
    }

    public static boolean isValidVersion(String version) {
        try {
            PackVersionValidator.requireValidVersion(version);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
