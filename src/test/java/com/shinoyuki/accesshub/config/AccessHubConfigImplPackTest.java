package com.shinoyuki.accesshub.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccessHubConfigImplPackTest {

    @TempDir
    Path tempDir;

    @Test
    void ossUploadDefaultsToDisabledWithEmptyCredentials() throws Exception {
        Path configFile = tempDir.resolve("accesshub").resolve("common.toml");

        AccessHubConfigImpl config = new AccessHubConfigImpl(configFile);

        assertFalse(config.isPackOssEnabled());
        assertEquals("", config.getPackOssEndpoint());
        assertEquals("", config.getPackOssBucket());
        assertEquals("", config.getPackOssAccessKeyId());
        assertEquals("", config.getPackOssAccessKeySecret());
        assertEquals("", config.getPackOssPublicBaseUrl());
        String persisted = Files.readString(configFile, StandardCharsets.UTF_8);
        assertTrue(persisted.contains("access-key-secret = \"\""));
    }

    @Test
    void readsExplicitOssAndVersionGateSettings() throws Exception {
        Path configFile = tempDir.resolve("configured").resolve("common.toml");
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, """
                [pack.oss]
                enabled = true
                endpoint = "https://oss-cn-hangzhou.aliyuncs.com"
                bucket = "wok-pack"
                access-key-id = "configured-id"
                access-key-secret = "configured-secret"
                public-base-url = "https://cdn.example.test/wok"

                [pack.version-gate]
                enabled = true
                reject-message = "version mismatch"
                """, StandardCharsets.UTF_8);

        AccessHubConfigImpl config = new AccessHubConfigImpl(configFile);

        assertTrue(config.isPackOssEnabled());
        assertEquals("https://oss-cn-hangzhou.aliyuncs.com", config.getPackOssEndpoint());
        assertEquals("wok-pack", config.getPackOssBucket());
        assertEquals("configured-id", config.getPackOssAccessKeyId());
        assertEquals("configured-secret", config.getPackOssAccessKeySecret());
        assertEquals("https://cdn.example.test/wok", config.getPackOssPublicBaseUrl());
        assertTrue(config.isPackVersionGateEnabled());
        assertEquals("version mismatch", config.getPackVersionRejectMessage());
    }

    @Test
    void upgradesLegacyConfigWithoutOverwritingExistingValuesOrComments() throws Exception {
        Path configFile = tempDir.resolve("legacy").resolve("common.toml");
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, """
                [http]
                enabled = false

                [pack.version-gate]
                enabled = true

                [pack.oss]
                # deployment-specific endpoint
                endpoint = "https://legacy-oss.example.test"
                access-key-id = "legacy-id"
                """, StandardCharsets.UTF_8);

        AccessHubConfigImpl config = new AccessHubConfigImpl(configFile);

        assertFalse(config.isHttpEnabled());
        assertTrue(config.isPackVersionGateEnabled());
        assertEquals("https://legacy-oss.example.test", config.getPackOssEndpoint());
        assertEquals("legacy-id", config.getPackOssAccessKeyId());
        assertFalse(config.isPackOssEnabled());
        assertEquals("", config.getPackOssBucket());
        assertEquals("", config.getPackOssAccessKeySecret());
        assertEquals("", config.getPackOssPublicBaseUrl());

        String persisted = Files.readString(configFile, StandardCharsets.UTF_8);
        assertTrue(persisted.contains("deployment-specific endpoint"));
        assertFalse(persisted.contains("阿里云 OSS 地域 Endpoint"));
        assertTrue(persisted.contains("reject-message ="));
        assertTrue(persisted.contains("access-key-secret = \"\""));
        assertTrue(persisted.contains("整合包版本门控总开关"));
        assertTrue(persisted.contains("版本不匹配时的拒绝文案"));
        assertTrue(persisted.contains("自研整合包文件上传总开关"));
        assertTrue(persisted.contains("OSS Bucket 名称"));
        assertTrue(persisted.contains("仅授予目标 bucket 写权限"));
        assertTrue(persisted.contains("本程序不会自动生成或输出到日志"));
        assertTrue(persisted.contains("客户端公开下载基地址"));
    }
}
