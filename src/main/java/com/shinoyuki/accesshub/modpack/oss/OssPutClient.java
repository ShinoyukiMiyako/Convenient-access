package com.shinoyuki.accesshub.modpack.oss;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/** 使用 JDK HttpClient 执行内容寻址的 OSS PutObject。 */
public final class OssPutClient {

    public static final long MAX_FILE_SIZE = 200L * 1024 * 1024;

    private static final String CONTENT_TYPE = "application/octet-stream";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration UPLOAD_TIMEOUT = Duration.ofMinutes(15);
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneOffset.UTC);

    private final OssPutConfig config;
    private final HttpClient httpClient;
    private final Clock clock;

    public OssPutClient(OssPutConfig config) {
        this(config, HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(), Clock.systemUTC());
    }

    OssPutClient(OssPutConfig config, HttpClient httpClient, Clock clock) {
        this.config = config;
        this.httpClient = httpClient;
        this.clock = clock;
    }

    public UploadResult upload(Path file) throws IOException, InterruptedException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("上传源不是普通文件: " + file);
        }
        long size = Files.size(file);
        if (size > MAX_FILE_SIZE) {
            throw new IOException("文件超过 200 MB 上传上限: " + size + " bytes");
        }

        FileDigest digest = digest(file);
        String objectKey = contentAddressedKey(digest.sha1());
        String date = HTTP_DATE.format(clock.instant());
        String authorization = OssV1Signer.authorization(
                config.accessKeyId(),
                config.accessKeySecret(),
                "PUT",
                digest.contentMd5(),
                CONTENT_TYPE,
                date,
                Map.of(),
                config.canonicalizedResource(objectKey));

        HttpRequest request = HttpRequest.newBuilder(config.objectUri(objectKey))
                .header("Content-MD5", digest.contentMd5())
                .header("Content-Type", CONTENT_TYPE)
                .header("Date", date)
                .header("Authorization", authorization)
                .timeout(UPLOAD_TIMEOUT)
                .PUT(HttpRequest.BodyPublishers.ofFile(file))
                .build();
        HttpResponse<InputStream> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream ignored = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException(errorMessage(response));
            }
        }
        return new UploadResult(
                objectKey, digest.sha1(), size, config.publicObjectUri(objectKey).toString());
    }

    public static String contentAddressedKey(String sha1) {
        String normalized = sha1.toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("sha1 必须是 40 位十六进制字符串");
        }
        return "files/" + normalized.substring(0, 2)
                + "/" + normalized.substring(2, 4)
                + "/" + normalized;
    }

    private static FileDigest digest(Path file) throws IOException, InterruptedException {
        MessageDigest sha1 = messageDigest("SHA-1");
        MessageDigest md5 = messageDigest("MD5");
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = Files.newInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("OSS 上传摘要计算已取消");
                }
                sha1.update(buffer, 0, read);
                md5.update(buffer, 0, read);
            }
        }
        return new FileDigest(
                HexFormat.of().formatHex(sha1.digest()),
                Base64.getEncoder().encodeToString(md5.digest()));
    }

    private static MessageDigest messageDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 不支持 " + algorithm, e);
        }
    }

    private static String errorMessage(HttpResponse<?> response) {
        StringBuilder message = new StringBuilder("OSS PutObject 失败，HTTP ")
                .append(response.statusCode());
        response.headers().firstValue("x-oss-ec")
                .ifPresent(value -> message.append("，errorCode=").append(value));
        response.headers().firstValue("x-oss-request-id")
                .ifPresent(value -> message.append("，requestId=").append(value));
        return message.toString();
    }

    private record FileDigest(String sha1, String contentMd5) {
    }

    public record UploadResult(String objectKey, String sha1, long size, String downloadUrl) {
    }
}
