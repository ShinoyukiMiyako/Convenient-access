package com.shinoyuki.accesshub.pack;

import java.sql.SQLException;
import java.util.Optional;

import com.shinoyuki.accesshub.config.AccessHubConfig;

/**
 * 进服整合包版本门控的纯业务判定。
 *
 * 客户端版本只是防止玩家忘记更新的提示信号，并非安全凭据；服务端的唯一权威版本始终来自
 * {@link PackManifestRepository#findCurrentPublished()}，发布后无需再同步手写配置版本号。
 */
public final class PackVersionGate {

    private final AccessHubConfig config;
    private final PackManifestRepository repository;

    public PackVersionGate(AccessHubConfig config, PackManifestRepository repository) {
        this.config = config;
        this.repository = repository;
    }

    /**
     * 比较客户端自报版本与数据库当前发布版本。
     *
     * 门控开启后，未上报版本、空版本、无已发布版本和不匹配均明确拒绝。数据库异常直接上抛，
     * 由连接入口统一记录并按门控开启语义拒绝，不能把存储故障静默伪装成版本匹配。
     */
    public Decision evaluate(String clientVersion) throws SQLException {
        if (!config.isPackVersionGateEnabled()) {
            return Decision.allowed(Status.DISABLED, clientVersion, null);
        }

        Optional<PackVersion> published = repository.findCurrentPublished();
        if (published.isEmpty()) {
            return Decision.rejected(Status.NO_PUBLISHED_VERSION, clientVersion, null);
        }

        PackVersion required = published.get();
        if (required.status() != PackVersionStatus.PUBLISHED) {
            throw new IllegalStateException("Current pack query returned a non-published version");
        }
        if (required.version() == null || required.version().isBlank()) {
            throw new IllegalStateException("Current published pack has an empty version");
        }
        if (clientVersion == null || clientVersion.isBlank()) {
            return Decision.rejected(Status.CLIENT_VERSION_MISSING, clientVersion, required.version());
        }
        if (!required.version().equals(clientVersion)) {
            return Decision.rejected(Status.VERSION_MISMATCH, clientVersion, required.version());
        }
        return Decision.allowed(Status.MATCH, clientVersion, required.version());
    }

    public enum Status {
        DISABLED,
        MATCH,
        CLIENT_VERSION_MISSING,
        VERSION_MISMATCH,
        NO_PUBLISHED_VERSION
    }

    public record Decision(boolean allowed, Status status, String clientVersion, String requiredVersion) {

        private static Decision allowed(Status status, String clientVersion, String requiredVersion) {
            return new Decision(true, status, clientVersion, requiredVersion);
        }

        private static Decision rejected(Status status, String clientVersion, String requiredVersion) {
            return new Decision(false, status, clientVersion, requiredVersion);
        }
    }
}
