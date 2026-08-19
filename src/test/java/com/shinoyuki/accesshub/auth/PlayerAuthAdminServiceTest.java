package com.shinoyuki.accesshub.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shinoyuki.accesshub.auth.PlayerAuthAdminService.ResetOutcome;
import com.shinoyuki.accesshub.config.AccessHubConfig;
import com.shinoyuki.accesshub.database.DatabaseManager;
import com.shinoyuki.accesshub.deviceauth.DeviceAuthServer;
import com.shinoyuki.accesshub.deviceauth.DeviceKeyDao;
import com.shinoyuki.accesshub.event.PlayerAuthListener;

import net.minecraft.server.MinecraftServer;

/**
 * 管理侧重置玩家认证 (走真 SQLite)。
 *
 * 守护的核心不变量: 重置必须【同时】清掉密码记录与设备免密绑定。只清密码时 device_keys 里的公钥仍在,
 * 装了本 mod 的客户端下次进服照样被验签自动解冻 —— 管理员以为账号已锁死, 实际门还开着。
 * 这正是本类落地前 /accesshub auth reset 的真实行为。
 * 删掉 PlayerAuthAdminService 里吊销设备那一行, passwordAndDeviceBindingAreClearedTogether 必挂。
 *
 * MinecraftServer 用 mock: 其 execute(Runnable) 默认不执行入参, 于是在线降级那段不会跑,
 * 本类专注断言清库结果 (在线降级依赖真实玩家实体, 属真服验收范畴)。
 */
class PlayerAuthAdminServiceTest {

    @TempDir
    File tempDir;

    private DatabaseManager db;
    private PlayerAuthService auth;
    private DeviceKeyDao deviceKeyDao;
    private PlayerAuthAdminService admin;

    @BeforeEach
    void setUp() throws Exception {
        db = new DatabaseManager(tempDir);
        assertTrue(db.initialize().get(), "数据库初始化应成功");

        AccessHubConfig config = mock(AccessHubConfig.class);
        when(config.getPlayerAuthMinPasswordLength()).thenReturn(8);
        when(config.isPlayerAuthRejectWeakPassword()).thenReturn(true);

        auth = new PlayerAuthService(new PlayerAuthDao(db), new PlayerRegistrationCodeDao(db), config);
        deviceKeyDao = new DeviceKeyDao(db);
        DeviceAuthServer deviceAuthServer = new DeviceAuthServer(deviceKeyDao, auth, config);
        PlayerAuthListener listener = new PlayerAuthListener(config, auth, deviceAuthServer);
        admin = new PlayerAuthAdminService(mock(MinecraftServer.class), auth, deviceAuthServer, listener);
    }

    @AfterEach
    void tearDown() {
        db.shutdown();
    }

    /** 模拟 /enroll 成功后的落库状态 (公钥内容与验签无关, 此处只关心行是否存在)。 */
    private void enrollDevice(String username) throws Exception {
        deviceKeyDao.upsert(username, "MCowBQYDK2VwAyEATestPublicKeyBase64Placeholder00000000000=");
        assertTrue(deviceKeyDao.findPublicKey(username).isPresent(), "前置条件: 设备绑定应已写入");
    }

    @Test
    void passwordAndDeviceBindingAreClearedTogether() throws Exception {
        assertTrue(auth.register("Alice", "Str0ngPass", null).isSuccess(), "前置条件: 注册应成功");
        enrollDevice("Alice");

        ResetOutcome outcome = admin.reset("Alice").get();

        assertTrue(outcome.isPasswordCleared(), "应报告密码记录已清除");
        assertTrue(outcome.isDeviceRevoked(), "应报告设备绑定已吊销");
        assertFalse(auth.isRegistered("Alice"), "密码记录应已从 player_auth 删除");
        assertFalse(deviceKeyDao.findPublicKey("Alice").isPresent(),
                "设备公钥必须一并删除, 否则玩家下次进服仍被免密解冻, 重置形同虚设");
        assertFalse(auth.verify("Alice", "Str0ngPass", "127.0.0.1").isSuccess(),
                "原密码重置后不应还能登录");
    }

    @Test
    void resetReportsNothingClearedForUnknownPlayer() throws Exception {
        ResetOutcome outcome = admin.reset("NeverSeen").get();

        assertFalse(outcome.isPasswordCleared(), "无记录时不应报告清除了密码");
        assertFalse(outcome.isDeviceRevoked(), "无记录时不应报告吊销了设备");
        assertFalse(outcome.isAnythingCleared(), "两项皆无 -> 调用方据此回 404 / 提示没有认证记录");
    }

    @Test
    void resetOnlyPasswordWhenPlayerNeverEnrolled() throws Exception {
        assertTrue(auth.register("Bob", "Str0ngPass", null).isSuccess(), "前置条件: 注册应成功");

        ResetOutcome outcome = admin.reset("Bob").get();

        assertTrue(outcome.isPasswordCleared(), "应清除密码记录");
        assertFalse(outcome.isDeviceRevoked(), "从未登记免密的玩家, device_revoked 应为 false 而非报错");
        assertTrue(outcome.isAnythingCleared(), "清掉了密码就算重置成功");
        assertFalse(auth.isRegistered("Bob"), "密码记录应已删除");
    }

    @Test
    void deviceRevocationMatchesNameCaseInsensitively() throws Exception {
        // player_auth 与 device_keys 都按 toLowerCase(Locale.ROOT) 规范化存储。管理员从网页表格
        // 复制的是原始大小写玩家名, 若吊销走了未规范化的比较, 设备绑定会被漏掉而密码已清 —— 恰是最危险的半吊子状态。
        assertTrue(auth.register("MoMO_Rikka", "Str0ngPass", null).isSuccess(), "前置条件: 注册应成功");
        enrollDevice("MoMO_Rikka");

        ResetOutcome outcome = admin.reset("momo_rikka").get();

        assertTrue(outcome.isPasswordCleared(), "小写名应命中同一条密码记录");
        assertTrue(outcome.isDeviceRevoked(), "小写名应命中同一条设备绑定");
        assertFalse(deviceKeyDao.findPublicKey("MoMO_Rikka").isPresent(), "原大小写查回来也应为空");
    }
}
