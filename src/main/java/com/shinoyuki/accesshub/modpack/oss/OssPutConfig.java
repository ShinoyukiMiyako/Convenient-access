package com.shinoyuki.accesshub.modpack.oss;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;
import java.util.regex.Pattern;

/** OSS PutObject 所需的最小配置。endpoint 使用阿里云定义的地域 Endpoint。 */
public final class OssPutConfig {

    private static final Pattern BUCKET_PATTERN =
            Pattern.compile("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]");

    private final URI endpoint;
    private final String bucket;
    private final String accessKeyId;
    private final String accessKeySecret;
    private final URI publicBaseUrl;

    public OssPutConfig(URI endpoint,
                        String bucket,
                        String accessKeyId,
                        String accessKeySecret,
                        URI publicBaseUrl) {
        this.bucket = requireBucket(bucket);
        this.endpoint = resolveBucketEndpoint(requireHttpBaseUri(endpoint, "endpoint", false), this.bucket);
        this.accessKeyId = requireNonBlank(accessKeyId, "accessKeyId");
        this.accessKeySecret = requireNonBlank(accessKeySecret, "accessKeySecret");
        this.publicBaseUrl = requireHttpBaseUri(publicBaseUrl, "publicBaseUrl", true);
    }

    public URI endpoint() {
        return endpoint;
    }

    public String bucket() {
        return bucket;
    }

    public String accessKeyId() {
        return accessKeyId;
    }

    String accessKeySecret() {
        return accessKeySecret;
    }

    public URI publicBaseUrl() {
        return publicBaseUrl;
    }

    URI objectUri(String objectKey) {
        return appendPath(endpoint, objectKey);
    }

    URI publicObjectUri(String objectKey) {
        return appendPath(publicBaseUrl, objectKey);
    }

    String canonicalizedResource(String objectKey) {
        return "/" + bucket + "/" + objectKey;
    }

    @Override
    public String toString() {
        return "OssPutConfig[endpoint=" + endpoint
                + ", bucket=" + bucket
                + ", accessKeyId=<redacted>"
                + ", accessKeySecret=<redacted>"
                + ", publicBaseUrl=" + publicBaseUrl + "]";
    }

    private static URI requireHttpBaseUri(URI value, String name, boolean allowPath) {
        Objects.requireNonNull(value, name);
        String scheme = value.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException(name + " 必须使用 http 或 https");
        }
        if (value.getHost() == null || value.getUserInfo() != null
                || value.getQuery() != null || value.getFragment() != null) {
            throw new IllegalArgumentException(name + " 必须是无凭据、查询参数和片段的绝对地址");
        }
        if ("http".equalsIgnoreCase(scheme) && !isLoopbackHost(value.getHost())) {
            throw new IllegalArgumentException(name + " 必须使用 https，只有本机测试地址可使用 http");
        }
        String path = value.getPath();
        if (!allowPath && path != null && !path.isEmpty() && !"/".equals(path)) {
            throw new IllegalArgumentException(name + " 不得包含路径");
        }
        return value.normalize();
    }

    private static String requireBucket(String value) {
        String bucket = requireNonBlank(value, "bucket");
        if (!BUCKET_PATTERN.matcher(bucket).matches()) {
            throw new IllegalArgumentException("bucket 必须是 3 至 63 位小写字母、数字或连字符");
        }
        return bucket;
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " 不得为空");
        }
        return value;
    }

    private static URI appendPath(URI base, String objectKey) {
        String prefix = base.toString();
        if (!prefix.endsWith("/")) {
            prefix += "/";
        }
        return URI.create(prefix + objectKey);
    }

    private static URI resolveBucketEndpoint(URI endpoint, String bucket) {
        String host = endpoint.getHost();
        if (isLoopbackHost(host) || host.startsWith(bucket + ".")) {
            return endpoint;
        }
        try {
            return new URI(
                    endpoint.getScheme(),
                    null,
                    bucket + "." + host,
                    endpoint.getPort(),
                    endpoint.getPath(),
                    null,
                    null);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("无法由 bucket 与 endpoint 构造 OSS 访问域名", exception);
        }
    }

    private static boolean isLoopbackHost(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "[::1]".equals(host);
    }
}
