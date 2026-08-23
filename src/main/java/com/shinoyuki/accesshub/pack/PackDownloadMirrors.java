package com.shinoyuki.accesshub.pack;

import java.net.URI;
import java.util.List;
import java.util.Objects;

import com.shinoyuki.accesshub.modpack.oss.OssPutConfig;

/**
 * 把清单条目里的单个下载地址展开成「主源 + 兜底源」。
 *
 * <p>起因是一次真实事故：整合包近半文件的下载地址指向境外 CDN，而清单每条只给一个地址，
 * 客户端换源机制对单候选无能为力，一旦那个地址连不上就是彻底失败。把文件全量镜像到自有 OSS
 * 并挂上 CDN 之后，如果清单仍然只给一个 CDN 地址，等于把同一个单点故障换了个位置。
 *
 * <p>兜底地址不入库：自有文件走内容寻址（{@code files/<sha1前2>/<sha1第3-4>/<sha1>}），CDN 地址与
 * OSS 直连地址指向同一个对象，仅主机名不同，因此由前缀替换推导即可。两者内容天然一致，
 * 客户端的 sha1 校验也保证了取哪个都等价。
 *
 * <p>只对确实走自有 CDN 的条目追加兜底：地址不以 CDN 基址开头的（例如仍指向平台 CDN 的历史条目）
 * 原样返回单元素，不去伪造一个 OSS 上并不存在的对象地址。
 */
public final class PackDownloadMirrors {

    private final String cdnBase;
    private final String ossBase;

    private PackDownloadMirrors(String cdnBase, String ossBase) {
        this.cdnBase = cdnBase;
        this.ossBase = ossBase;
    }

    /** 兜底能力关闭：每条地址原样返回。 */
    public static PackDownloadMirrors disabled() {
        return new PackDownloadMirrors("", "");
    }

    /**
     * 由 pack.oss 配置构造。任一配置缺失、或 CDN 基址与 OSS 直连基址相同（即压根没挂 CDN），
     * 都退化为 {@link #disabled()}——此时追加的地址会与主地址重复，没有兜底意义。
     */
    public static PackDownloadMirrors from(String publicBaseUrl, String endpoint, String bucket) {
        if (isBlank(publicBaseUrl) || isBlank(endpoint) || isBlank(bucket)) {
            return disabled();
        }
        String cdn = stripTrailingSlash(publicBaseUrl.trim());
        String oss;
        try {
            oss = stripTrailingSlash(
                    OssPutConfig.resolveBucketEndpoint(URI.create(endpoint.trim()), bucket.trim()).toString());
        } catch (RuntimeException exception) {
            // 配置不合法时不该让公开清单接口崩掉：退回单地址，行为与未配置 CDN 时一致。
            return disabled();
        }
        if (cdn.isEmpty() || oss.isEmpty() || cdn.equals(oss)) {
            return disabled();
        }
        return new PackDownloadMirrors(cdn, oss);
    }

    /**
     * 展开为按优先级排列的地址列表：自有 CDN 在前，OSS 直连兜底在后。
     *
     * <p>客户端按顺序尝试，前一个重试耗尽才切下一个。
     */
    public List<String> expand(String downloadUrl) {
        Objects.requireNonNull(downloadUrl, "downloadUrl");
        if (cdnBase.isEmpty() || !downloadUrl.startsWith(cdnBase + "/")) {
            return List.of(downloadUrl);
        }
        String fallback = ossBase + downloadUrl.substring(cdnBase.length());
        if (fallback.equals(downloadUrl)) {
            return List.of(downloadUrl);
        }
        return List.of(downloadUrl, fallback);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
