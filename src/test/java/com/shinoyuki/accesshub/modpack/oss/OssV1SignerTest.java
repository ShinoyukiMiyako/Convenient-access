package com.shinoyuki.accesshub.modpack.oss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class OssV1SignerTest {

    @Test
    void authorizationMatchesIndependentHmacSha1Vector() {
        String resource = "/example-bucket/files/ab/cd/abcdefabcdefabcdefabcdefabcdefabcdefabcd";
        String actual = OssV1Signer.authorization(
                "test-key",
                "secret",
                "PUT",
                "CY9rzUYh03PK3k6DJie09g==",
                "application/octet-stream",
                "Mon, 17 Aug 2026 04:00:00 GMT",
                Map.of(),
                resource);

        assertEquals("OSS test-key:c+hvF9bG07q5HUtH9ibEy7yR6QE=", actual);
    }

    @Test
    void canonicalHeadersAreLowercasedTrimmedAndSorted() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-OSS-Meta-Magic", " abracadabra ");
        headers.put("Content-Type", "ignored");
        headers.put("x-oss-meta-author", "alice");

        assertEquals(
                "x-oss-meta-author:alice\nx-oss-meta-magic:abracadabra\n",
                OssV1Signer.canonicalizeOssHeaders(headers));
    }

    @Test
    void canonicalHeadersRejectLineBreakInjection() {
        assertThrows(IllegalArgumentException.class, () ->
                OssV1Signer.canonicalizeOssHeaders(Map.of("x-oss-meta-name", "safe\nforged")));
    }

    @Test
    void optionalContentHeadersUseEmptySignatureLines() {
        assertEquals(
                "PUT\n\n\nMon, 17 Aug 2026 04:00:00 GMT\n/example-bucket/object",
                OssV1Signer.stringToSign(
                        "PUT",
                        null,
                        null,
                        "Mon, 17 Aug 2026 04:00:00 GMT",
                        Map.of(),
                        "/example-bucket/object"));
    }
}
