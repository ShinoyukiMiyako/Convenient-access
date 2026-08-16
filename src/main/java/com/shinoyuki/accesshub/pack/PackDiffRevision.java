package com.shinoyuki.accesshub.pack;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/** Stable content revision used to bind a reviewed diff to an atomic release. */
final class PackDiffRevision {
    private static final String SCHEMA = "accesshub-pack-diff-v1";
    private static final Pattern REVISION = Pattern.compile("[0-9a-f]{64}");

    private PackDiffRevision() {
    }

    static String compute(PackVersion publishedVersion, PackVersion targetVersion,
                          List<PackEntry> publishedEntries, List<PackEntry> targetEntries) {
        Encoder encoder = new Encoder();
        encoder.string(SCHEMA);
        encoder.version(publishedVersion);
        encoder.entries(publishedEntries);
        encoder.version(targetVersion);
        encoder.entries(targetEntries);
        return encoder.finish();
    }

    static String requireValidExpected(String revision) {
        if (revision == null || !REVISION.matcher(revision).matches()) {
            throw new IllegalArgumentException("expectedDiffRevision 必须是 64 位小写十六进制字符串");
        }
        return revision;
    }

    private static final class Encoder {
        private final MessageDigest digest;

        private Encoder() {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }

        private void version(PackVersion version) {
            if (version == null) {
                marker(0);
                return;
            }
            marker(1);
            number(version.id());
            string(version.version());
            string(version.status() == null ? null : version.status().databaseValue());
            string(version.minecraft());
            string(version.loaderKind());
            string(version.loaderVersion());
            string(version.note());
            number(version.createdAt());
            nullableNumber(version.publishedAt());
        }

        private void entries(List<PackEntry> entries) {
            List<PackEntry> ordered = entries.stream()
                    .sorted(Comparator.comparing(PackEntry::path))
                    .toList();
            integer(ordered.size());
            for (PackEntry entry : ordered) {
                string(entry.path());
                string(entry.kind() == null ? null : entry.kind().databaseValue());
                string(entry.policy() == null ? null : entry.policy().databaseValue());
                string(entry.sha1());
                number(entry.size());
                string(entry.downloadUrl());
                string(entry.platform());
                string(entry.projectId());
                string(entry.projectName());
                string(entry.externalVersionId());
            }
        }

        private void nullableNumber(Long value) {
            if (value == null) {
                marker(0);
            } else {
                marker(1);
                number(value);
            }
        }

        private void string(String value) {
            if (value == null) {
                marker(0);
                return;
            }
            marker(1);
            byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
            integer(encoded.length);
            digest.update(encoded);
        }

        private void marker(int value) {
            digest.update((byte) value);
        }

        private void integer(int value) {
            digest.update((byte) (value >>> 24));
            digest.update((byte) (value >>> 16));
            digest.update((byte) (value >>> 8));
            digest.update((byte) value);
        }

        private void number(long value) {
            for (int shift = 56; shift >= 0; shift -= 8) {
                digest.update((byte) (value >>> shift));
            }
        }

        private String finish() {
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
