package com.shinoyuki.accesshub.modpack.oss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;

import org.junit.jupiter.api.Test;

class OssPutConfigTest {

    @Test
    void resolvesOfficialEndpointToVirtualHostedBucketDomain() {
        OssPutConfig config = new OssPutConfig(
                URI.create("https://oss-cn-hangzhou.aliyuncs.com"),
                "example-bucket",
                "access-key-id",
                "access-key-secret",
                URI.create("https://cdn.example.test/wok"));

        assertEquals(
                URI.create("https://example-bucket.oss-cn-hangzhou.aliyuncs.com"),
                config.endpoint());
        assertEquals(
                URI.create("https://example-bucket.oss-cn-hangzhou.aliyuncs.com/files/ab/object"),
                config.objectUri("files/ab/object"));
        assertEquals(
                URI.create("https://cdn.example.test/wok/files/ab/object"),
                config.publicObjectUri("files/ab/object"));
    }

    @Test
    void doesNotPrefixAnExistingBucketDomainTwice() {
        OssPutConfig config = new OssPutConfig(
                URI.create("https://example-bucket.oss-cn-hangzhou.aliyuncs.com"),
                "example-bucket",
                "access-key-id",
                "access-key-secret",
                URI.create("https://cdn.example.test"));

        assertEquals(
                URI.create("https://example-bucket.oss-cn-hangzhou.aliyuncs.com"),
                config.endpoint());
    }

    @Test
    void rejectsPlainHttpOutsideLoopback() {
        assertThrows(IllegalArgumentException.class, () -> new OssPutConfig(
                URI.create("http://oss-cn-hangzhou.aliyuncs.com"),
                "example-bucket",
                "access-key-id",
                "access-key-secret",
                URI.create("https://cdn.example.test")));
    }

    @Test
    void redactsAccessKeySecretFromDiagnosticString() {
        OssPutConfig config = new OssPutConfig(
                URI.create("https://oss-cn-hangzhou.aliyuncs.com"),
                "example-bucket",
                "access-key-id",
                "do-not-log-this-secret",
                URI.create("https://cdn.example.test"));

        assertFalse(config.toString().contains("access-key-id"));
        assertFalse(config.toString().contains("do-not-log-this-secret"));
    }
}
