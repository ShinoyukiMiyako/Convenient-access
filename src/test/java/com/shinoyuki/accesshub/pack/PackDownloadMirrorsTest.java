package com.shinoyuki.accesshub.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 兜底源展开规则。
 *
 * <p>被测的是「自有 CDN 挂了还能不能装上游戏」这条命门：清单每条只给一个地址时，
 * 那个地址一旦不可达就是彻底失败——整合包分发翻过一次车，就是因为地址是单点。
 */
class PackDownloadMirrorsTest {

    private static final String CDN = "https://pack.shinoyuki.cn";
    private static final String ENDPOINT = "https://oss-cn-shanghai.aliyuncs.com";
    private static final String BUCKET = "world-of-kivotos";
    private static final String OSS = "https://world-of-kivotos.oss-cn-shanghai.aliyuncs.com";
    private static final String KEY = "/files/17/21/1721992da220bd4a79265c3f393bb5c50eea71af";

    @Test
    @DisplayName("走 CDN 的地址展开为 CDN 优先、OSS 直连兜底")
    void cdnUrlExpandsWithOssFallback() {
        PackDownloadMirrors mirrors = PackDownloadMirrors.from(CDN, ENDPOINT, BUCKET);

        assertEquals(List.of(CDN + KEY, OSS + KEY), mirrors.expand(CDN + KEY));
    }

    @Test
    @DisplayName("兜底地址只换主机名，对象路径逐字保留")
    void fallbackKeepsObjectPathVerbatim() {
        PackDownloadMirrors mirrors = PackDownloadMirrors.from(CDN, ENDPOINT, BUCKET);

        List<String> urls = mirrors.expand(CDN + "/files/ab/cd/abcd0123456789");

        assertEquals(2, urls.size());
        assertEquals(OSS + "/files/ab/cd/abcd0123456789", urls.get(1));
    }

    @Test
    @DisplayName("CDN 基址带尾斜杠不会拼出双斜杠")
    void trailingSlashInBaseIsNormalized() {
        PackDownloadMirrors mirrors = PackDownloadMirrors.from(CDN + "/", ENDPOINT, BUCKET);

        assertEquals(List.of(CDN + KEY, OSS + KEY), mirrors.expand(CDN + KEY));
    }

    @Test
    @DisplayName("endpoint 已是 bucket 三级域名时不重复拼接")
    void bucketPrefixedEndpointIsNotDoubled() {
        PackDownloadMirrors mirrors = PackDownloadMirrors.from(CDN, OSS, BUCKET);

        assertEquals(List.of(CDN + KEY, OSS + KEY), mirrors.expand(CDN + KEY));
    }

    @Test
    @DisplayName("地址不属于自有 CDN 时不追加兜底，免得伪造出 OSS 上不存在的对象")
    void foreignUrlGetsNoFallback() {
        PackDownloadMirrors mirrors = PackDownloadMirrors.from(CDN, ENDPOINT, BUCKET);

        String foreign = "https://cdn.modrinth.com/data/AANobbMI/versions/x/sodium.jar";
        assertEquals(List.of(foreign), mirrors.expand(foreign));
    }

    @Test
    @DisplayName("仅前缀相似但不是同一主机的地址不被误判")
    void lookalikeHostIsNotTreatedAsCdn() {
        PackDownloadMirrors mirrors = PackDownloadMirrors.from(CDN, ENDPOINT, BUCKET);

        String lookalike = "https://pack.shinoyuki.cn.evil.example/files/aa/bb/cc";
        assertEquals(List.of(lookalike), mirrors.expand(lookalike));
    }

    @Test
    @DisplayName("未配 public-base-url 时退化为单地址")
    void missingCdnBaseDisablesFallback() {
        PackDownloadMirrors mirrors = PackDownloadMirrors.from("", ENDPOINT, BUCKET);

        assertEquals(List.of(OSS + KEY), mirrors.expand(OSS + KEY));
    }

    @Test
    @DisplayName("public-base-url 就是 OSS 直连（没挂 CDN）时不产生重复地址")
    void cdnEqualToOssDisablesFallback() {
        PackDownloadMirrors mirrors = PackDownloadMirrors.from(OSS, ENDPOINT, BUCKET);

        assertEquals(List.of(OSS + KEY), mirrors.expand(OSS + KEY));
    }

    @Test
    @DisplayName("endpoint 非法时退回单地址，不让公开清单接口崩掉")
    void malformedEndpointDegradesGracefully() {
        PackDownloadMirrors mirrors = PackDownloadMirrors.from(CDN, "not a uri", BUCKET);

        assertEquals(List.of(CDN + KEY), mirrors.expand(CDN + KEY));
    }

    @Test
    @DisplayName("disabled() 对任何地址都只回一个")
    void disabledAlwaysReturnsSingle() {
        PackDownloadMirrors mirrors = PackDownloadMirrors.disabled();

        assertEquals(List.of(CDN + KEY), mirrors.expand(CDN + KEY));
    }
}
