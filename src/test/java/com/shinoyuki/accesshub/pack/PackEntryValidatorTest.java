package com.shinoyuki.accesshub.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PackEntryValidatorTest {
    private static final String SHA1 = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void validatesIntegrityFieldsAtTheirBoundaries() {
        assertEquals(SHA1, PackEntryValidator.requireValidSha1(SHA1));
        assertEquals(0L, PackEntryValidator.requireValidSize(0));
        assertEquals(Long.MAX_VALUE, PackEntryValidator.requireValidSize(Long.MAX_VALUE));

        assertThrows(IllegalArgumentException.class,
                () -> PackEntryValidator.requireValidSha1("0123456789ABCDEF0123456789ABCDEF01234567"));
        assertThrows(IllegalArgumentException.class,
                () -> PackEntryValidator.requireValidSha1(SHA1.substring(1)));
        assertThrows(IllegalArgumentException.class,
                () -> PackEntryValidator.requireValidSha1(SHA1 + "0"));
        assertThrows(IllegalArgumentException.class, () -> PackEntryValidator.requireValidSize(-1));
    }

    @Test
    void acceptsOnlyAbsoluteCredentialFreeHttpUrls() {
        String https = "https://cdn.modrinth.com/data/project/versions/file.jar?download=1";
        assertEquals(https, PackEntryValidator.requireValidDownloadUrl(https));
        assertEquals("http://localhost:8080/file", PackEntryValidator.requireValidDownloadUrl(
                "http://localhost:8080/file"));

        String[] invalidUrls = {
                null, "", "files/a.jar", "ftp://cdn.example/a.jar", "https:///a.jar",
                "https://user:password@example.com/a.jar", "https://example.com/a.jar#fragment",
                "https://exa mple.com/a.jar"
        };
        for (String url : invalidUrls) {
            assertThrows(IllegalArgumentException.class,
                    () -> PackEntryValidator.requireValidDownloadUrl(url), String.valueOf(url));
        }
    }
}
