package com.shinoyuki.accesshub.auth;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shinoyuki.accesshub.deviceauth.DeviceAuthServer;
import com.shinoyuki.accesshub.event.PlayerAuthListener;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 管理侧"重置玩家认证"的唯一实现, 由 HTTP 端点与游戏内 /accesshub auth reset 共用。
 *
 * 重置 = 清密码记录 + 吊销设备免密绑定, 两者缺一不可: 只清密码时 device_keys 里的公钥还在,
 * 装了本 mod 的客户端下次进服照样验签解冻, 重置形同虚设 (本类落地前的命令行正是如此)。
 * 两个入口共用本类, 是为了杜绝"网页重置得干净、命令重置留后门"这种行为分叉。
 */
public final class PlayerAuthAdminService {

    private static final Logger logger = LoggerFactory.getLogger(PlayerAuthAdminService.class);

    private final MinecraftServer server;
    private final PlayerAuthService authService;
    /** 免密子系统. 配置关闭时 AccessHubMod 仍会装配它, 但保留 null 分支以防启动早期调用。 */
    private final DeviceAuthServer deviceAuthServer;
    private final PlayerAuthListener authListener;

    public PlayerAuthAdminService(MinecraftServer server, PlayerAuthService authService,
                                  DeviceAuthServer deviceAuthServer, PlayerAuthListener authListener) {
        this.server = server;
        this.authService = authService;
        this.deviceAuthServer = deviceAuthServer;
        this.authListener = authListener;
    }

    /**
     * 重置指定玩家的认证: 异步清库, 完成后回主线程把在线的同名玩家原地降级为未认证。
     *
     * 返回的 future 只承诺清库结果; 在线降级是随后 enqueue 到主线程的, 不阻塞调用方响应。
     */
    public CompletableFuture<ResetOutcome> reset(String username) {
        return CompletableFuture
                .supplyAsync(() -> {
                    boolean passwordCleared = authService.adminReset(username);
                    boolean deviceRevoked = deviceAuthServer != null && deviceAuthServer.revokeDevice(username);
                    logger.info("重置玩家认证: {} (密码记录={}, 设备绑定={})",
                            username, passwordCleared ? "已清除" : "无", deviceRevoked ? "已吊销" : "无");
                    return new ResetOutcome(passwordCleared, deviceRevoked);
                })
                .thenApply(outcome -> {
                    server.execute(() -> demoteOnline(username));
                    return outcome;
                });
    }

    /**
     * 主线程: 在线同名玩家立即降级为未认证 —— 清已认证会话 (冻结由 PlayerAuthListener.onPlayerTick 接管)、
     * 清免密握手会话 (公钥已删, 留着只会白发一轮必然失败的挑战)、重置超时计时。
     */
    private void demoteOnline(String username) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(username);
        if (online == null) {
            return;
        }
        UUID uuid = online.getUUID();
        authService.clearSession(uuid);
        if (deviceAuthServer != null) {
            deviceAuthServer.clear(uuid);
        }
        authListener.onAdminReset(uuid);
        online.sendSystemMessage(Component.literal("§c你的账号已被管理员重置, 请重新 /register"));
        online.sendSystemMessage(Component.literal("§7免密登记也已一并吊销, 注册后需重新 /enroll"));
    }

    /** 本次重置实际清掉了什么. 两者皆 false 表示该玩家本就没有任何认证记录。 */
    public static final class ResetOutcome {
        private final boolean passwordCleared;
        private final boolean deviceRevoked;

        ResetOutcome(boolean passwordCleared, boolean deviceRevoked) {
            this.passwordCleared = passwordCleared;
            this.deviceRevoked = deviceRevoked;
        }

        public boolean isPasswordCleared() {
            return passwordCleared;
        }

        public boolean isDeviceRevoked() {
            return deviceRevoked;
        }

        public boolean isAnythingCleared() {
            return passwordCleared || deviceRevoked;
        }
    }
}
