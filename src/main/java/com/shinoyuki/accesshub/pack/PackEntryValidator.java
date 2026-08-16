package com.shinoyuki.accesshub.pack;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

/** Shared integrity validation for persisted pack entries and public manifests. */
public final class PackEntryValidator {
    private static final Pattern SHA1 = Pattern.compile("[0-9a-f]{40}");

    private PackEntryValidator() {
    }

    public static String requireValidSha1(String sha1) {
        if (sha1 == null || !SHA1.matcher(sha1).matches()) {
            throw new IllegalArgumentException("sha1 必须是 40 位小写十六进制字符串");
        }
        return sha1;
    }

    public static long requireValidSize(long size) {
        if (size < 0) {
            throw new IllegalArgumentException("文件大小不能为负数");
        }
        return size;
    }

    public static String requireValidDownloadUrl(String downloadUrl) {
        if (downloadUrl == null || downloadUrl.isBlank()) {
            throw new IllegalArgumentException("下载地址不能为空");
        }

        final URI uri;
        try {
            uri = new URI(downloadUrl);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("下载地址格式非法: " + downloadUrl, exception);
        }

        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
            throw new IllegalArgumentException("下载地址必须使用 http 或 https: " + downloadUrl);
        }
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("下载地址必须包含有效主机名: " + downloadUrl);
        }
        if (uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("下载地址不能包含用户凭据或片段: " + downloadUrl);
        }
        return downloadUrl;
    }
}
