package com.shinoyuki.accesshub.modpack.oss;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 阿里云 OSS V1 Header 签名。 */
final class OssV1Signer {

    private static final String HMAC_SHA1 = "HmacSHA1";

    private OssV1Signer() {
    }

    static String authorization(String accessKeyId,
                                String accessKeySecret,
                                String method,
                                String contentMd5,
                                String contentType,
                                String date,
                                Map<String, String> ossHeaders,
                                String canonicalizedResource) {
        Objects.requireNonNull(accessKeyId, "accessKeyId");
        Objects.requireNonNull(accessKeySecret, "accessKeySecret");
        String stringToSign = stringToSign(
                method, contentMd5, contentType, date, ossHeaders, canonicalizedResource);
        try {
            Mac mac = Mac.getInstance(HMAC_SHA1);
            mac.init(new SecretKeySpec(accessKeySecret.getBytes(StandardCharsets.UTF_8), HMAC_SHA1));
            String signature = Base64.getEncoder().encodeToString(
                    mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
            return "OSS " + accessKeyId + ":" + signature;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JDK 不支持 HmacSHA1", e);
        }
    }

    static String stringToSign(String method,
                               String contentMd5,
                               String contentType,
                               String date,
                               Map<String, String> ossHeaders,
                               String canonicalizedResource) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(ossHeaders, "ossHeaders");
        Objects.requireNonNull(canonicalizedResource, "canonicalizedResource");
        return method + '\n'
                + emptyIfNull(contentMd5) + '\n'
                + emptyIfNull(contentType) + '\n'
                + date + '\n'
                + canonicalizeOssHeaders(ossHeaders)
                + canonicalizedResource;
    }

    static String canonicalizeOssHeaders(Map<String, String> headers) {
        TreeMap<String, String> normalized = new TreeMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey().toLowerCase(Locale.ROOT).trim();
            if (!name.startsWith("x-oss-")) {
                continue;
            }
            String value = entry.getValue().trim();
            if (name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0
                    || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("OSS 签名头不得包含换行符");
            }
            normalized.merge(name, value, (left, right) -> left + "," + right);
        }

        StringBuilder result = new StringBuilder();
        normalized.forEach((name, value) -> result
                .append(name)
                .append(':')
                .append(value)
                .append('\n'));
        return result.toString();
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }
}
