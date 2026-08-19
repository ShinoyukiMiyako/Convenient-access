package com.shinoyuki.accesshub.event;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.shinoyuki.accesshub.auth.PlayerAuthService;
import com.shinoyuki.accesshub.config.AccessHubConfig;
import com.shinoyuki.accesshub.deviceauth.DeviceAuthServer;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.FillBucketEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.PlayerSetSpawnEvent;
import net.minecraftforge.event.entity.player.PlayerSleepInBedEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 玩家离线认证拦截器.
 *
 * 仅当 auth.enabled 时生效。玩家进服 (已通过白名单的 PlayerLoggedInEvent) 后立即标记为"未认证"
 * 并记录登录点; 在 /login 成功前, 取消其对世界/他人/自身物品的【所有】影响向量, 命令仅放行登录类。
 *
 * 安全门: 未认证玩家不能破坏/放置/交互/攻击/丢捡物/开容器/聊天/骑乘/睡觉/设重生点/装桶/收投射物,
 * 且被冻结在登录点 (每 tick 拉回 + 失明 + 缓慢 + 无敌 + 强关容器 + 清空光标物品), 命令除登录类全部取消。
 *
 * 物品栏拖拽 (含盔甲槽穿脱) 在 Forge 1.20.1 无可取消事件, 故于每 tick 服务端复位:
 * 关闭非默认容器 + 清空 carried 光标并重下发, 使改造客户端的槽位/光标操作无法在服务端落地。
 *
 * 取消机制严格遵循 eventbus 契约: 仅对 @Cancelable 事件调用 setCanceled;
 * 不可取消的事件 (PlayerContainerEvent.Open / PlayerSleepInBedEvent / PlayerTickEvent) 用替代手段。
 */
public final class PlayerAuthListener {

    private static final Logger logger = LoggerFactory.getLogger(PlayerAuthListener.class);

    /** 未认证期施加的属性时长 (tick). 每 tick 续期, 取较长值避免登录瞬间过期闪烁。 */
    private static final int EFFECT_DURATION_TICKS = 100;

    private final AccessHubConfig config;
    private final PlayerAuthService authService;
    private final DeviceAuthServer deviceAuthServer;

    /** 登录点快照: 进服瞬间坐标 + 朝向, 用于每 tick 钉死未认证玩家。 */
    private final Map<UUID, LoginAnchor> anchors = new ConcurrentHashMap<>();
    /** 进服时间戳 (ms), 用于超时踢出判定。 */
    private final Map<UUID, Long> joinedAt = new ConcurrentHashMap<>();

    public PlayerAuthListener(AccessHubConfig config, PlayerAuthService authService,
                              DeviceAuthServer deviceAuthServer) {
        this.config = config;
        this.authService = authService;
        this.deviceAuthServer = deviceAuthServer;
    }

    private boolean disabled() {
        return !config.isPlayerAuthEnabled();
    }

    /** 统一未认证判定: 服务端玩家 + 不在已认证集合。 */
    private boolean blocked(Entity entity) {
        return entity instanceof ServerPlayer sp && !authService.isAuthed(sp.getUUID());
    }

    /**
     * 认证成功后立即解除未认证期施加的限制 (失明 / 缓慢 / 无敌), 避免等效果自然过期残留约 5 秒。
     * 由密码登录 (AuthCommand.doLogin) 与免密 (DeviceAuthServer) 在 markAuthed 后于主线程调用;
     * markAuthed 已置位, onPlayerTick 下一 tick 因 isAuthed 短路不再续期, 故移除一次即可。
     */
    public static void liftRestrictions(ServerPlayer player) {
        player.removeEffect(MobEffects.BLINDNESS);
        player.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        player.setInvulnerable(false);
    }

    // ==================== 进服 / 退服 / 超时 ====================

    /**
     * 进服 (已过白名单). 标记未认证, 记录登录点, 提示注册/登录, 起超时计时。
     * 该事件不可取消, 不做拦截, 仅初始化未认证会话状态。
     */
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (disabled()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        UUID uuid = player.getUUID();
        // 重新进服一律重置为未认证 (清掉可能残留的会话)
        authService.clearSession(uuid);
        anchors.put(uuid, new LoginAnchor(player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot()));
        joinedAt.put(uuid, System.currentTimeMillis());

        boolean registered;
        try {
            registered = authService.isRegistered(player.getGameProfile().getName());
        } catch (RuntimeException e) {
            // 查询失败: 不暴露内部错误, 提示稍后重试; 玩家仍处于未认证全限制下 (fail-closed)
            player.sendSystemMessage(Component.literal("§c认证系统繁忙, 请稍后重试登录"));
            logger.warn("进服查询注册状态失败: {}", player.getGameProfile().getName(), e);
            return;
        }
        if (registered) {
            player.sendSystemMessage(Component.literal("§e请使用 §a/login <密码> §e登录以解除限制"));
        } else {
            // 注册码临时停用: 恢复时把用法改回 "<密码> <确认密码> <注册码>", 并把下一行换回"注册码需向管理员索取"
            player.sendSystemMessage(Component.literal(
                    "§e首次进入, 请使用 §a/register <密码> <确认密码> §e注册"));
            player.sendSystemMessage(Component.literal("§7例: §f/register abcd1234 abcd1234 §7(密码自己定, 两次输一样即可)"));
        }

        // 免密 (best-effort): 有设备公钥 + 客户端装了本 mod 则自动挑战-验签解冻; 失败由上面的密码提示兜底。
        // 此刻通道可能尚未协商完 (Connector), 发不出去不要紧: 客户端 hello 与 onPlayerTick 复检会补发
        if (deviceAuthServer != null) {
            deviceAuthServer.onPlayerJoin(player);
        }
    }

    /**
     * 管理员重置在线玩家认证后调用: 丢弃其锚点与超时计时。
     *
     * 不在这里重新登记, 是因为 onPlayerTick 已有惰性补登记 —— 下一 tick 它会以玩家当前坐标建锚点、
     * 以当前时刻起算超时。若沿用进服时写下的 joinedAt, 玩家在线超过 auth.timeout-seconds 后被重置
     * 就会立刻判超时踢下线, 与"原地降级为未认证、给完整时间重新 /register"的预期相悖。
     */
    public void onAdminReset(UUID uuid) {
        anchors.remove(uuid);
        joinedAt.remove(uuid);
    }

    /** 退服: 清理会话与计时, 防止 UUID 残留已认证状态。 */
    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        UUID uuid = player.getUUID();
        authService.clearSession(uuid);
        anchors.remove(uuid);
        joinedAt.remove(uuid);
        if (deviceAuthServer != null) {
            deviceAuthServer.clear(uuid);
        }
    }

    /**
     * 每 tick 冻结未认证玩家 + 超时踢出.
     * PlayerTickEvent 不可取消, 冻结靠主动改玩家状态: 拉回登录点 + 清零速度 + 失明/缓慢 + 无敌。
     */
    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (disabled() || event.phase != TickEvent.Phase.END || event.side != LogicalSide.SERVER) {
            return;
        }
        if (!(event.player instanceof ServerPlayer sp) || authService.isAuthed(sp.getUUID())) {
            return;
        }
        UUID uuid = sp.getUUID();

        // 边界: 玩家在 auth.enabled=false 时进服 (onPlayerLoggedIn 提前 return, 未写 anchor/joinedAt),
        // 之后 reload 打开 auth.enabled. 此时若不补登记, joinedAt==null 永不超时、anchor==null 不纠正位置,
        // 玩家被永久软冻结且无法被清理. 在此惰性补登记 (以当前 tick 起算超时, 当前坐标为锚点), 进入正常未认证流程.
        joinedAt.computeIfAbsent(uuid, k -> System.currentTimeMillis());
        anchors.computeIfAbsent(uuid, k -> new LoginAnchor(sp.getX(), sp.getY(), sp.getZ(),
                sp.getYRot(), sp.getXRot()));

        // 超时踢出
        Long joined = joinedAt.get(uuid);
        if (joined != null) {
            long timeoutMs = (long) config.getPlayerAuthTimeoutSeconds() * 1000L;
            if (System.currentTimeMillis() - joined >= timeoutMs) {
                sp.connection.disconnect(Component.literal("§c登录超时, 请重新进入并尽快 /login"));
                anchors.remove(uuid);
                joinedAt.remove(uuid);
                return;
            }
        }

        // 免密握手复检: 客户端通道晚就绪时补发挑战, 挑战超时时重发并落日志.
        // 必须按 tick 复检 —— 进服那一刻的 isRemotePresent 在 Connector 下常常还是 false, 只判一次会永久漏发.
        if (deviceAuthServer != null) {
            deviceAuthServer.tick(sp);
        }

        // 物品栏硬化 (协议无关, 每 tick 服务端复位): 关闭任意非默认容器, 清空光标 (carried) 物品并重新下发,
        // 使改造客户端即便绕过交互事件打开容器/拖拽物品, 其槽位与光标状态每 tick 被服务端权威覆盖、无法落地.
        if (sp.hasContainerOpen()) {
            sp.closeContainer();
        }
        if (!sp.containerMenu.getCarried().isEmpty()) {
            sp.containerMenu.setCarried(ItemStack.EMPTY);
            sp.containerMenu.sendAllDataToRemote();
        }

        // 钉死在登录点: setPos 仅服务端, connection.teleport 发包纠正客户端预测, 防橡皮筋
        LoginAnchor anchor = anchors.get(uuid);
        if (anchor != null) {
            double dx = sp.getX() - anchor.x;
            double dy = sp.getY() - anchor.y;
            double dz = sp.getZ() - anchor.z;
            if (dx * dx + dy * dy + dz * dz > 0.01) {
                sp.connection.teleport(anchor.x, anchor.y, anchor.z, anchor.yaw, anchor.pitch);
            }
        }
        sp.setDeltaMovement(0, 0, 0);
        sp.hurtMarked = false;

        // 体验 + 防绕过: 失明遮挡视野, 缓慢满级配合钉死; 无敌防登录期被打死/掉落/岩浆
        sp.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, EFFECT_DURATION_TICKS, 0, false, false), null);
        sp.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, EFFECT_DURATION_TICKS, 250, false, false), null);
        if (!sp.isInvulnerable()) {
            sp.setInvulnerable(true);
        }
    }

    // ==================== 世界影响向量拦截 (全部 @Cancelable) ====================

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (disabled()) return;
        if (blocked(event.getPlayer())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        // EntityMultiPlaceEvent 继承自 EntityPlaceEvent, 同一处理覆盖床等多格放置
        if (disabled()) return;
        if (blocked(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (disabled()) return;
        if (blocked(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (disabled()) return;
        if (blocked(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (disabled()) return;
        if (blocked(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (disabled()) return;
        if (blocked(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (disabled()) return;
        if (blocked(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onAttackEntity(AttackEntityEvent event) {
        if (disabled()) return;
        if (blocked(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    /** 承伤前完全拦截: 未认证玩家无敌 (比 LivingHurt 更早), 防登录期被人/怪/环境杀死。 */
    @SubscribeEvent
    public void onLivingAttack(LivingAttackEvent event) {
        if (disabled()) return;
        if (blocked(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onItemToss(ItemTossEvent event) {
        if (disabled()) return;
        if (blocked(event.getPlayer())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onItemPickup(EntityItemPickupEvent event) {
        if (disabled()) return;
        if (blocked(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onFillBucket(FillBucketEvent event) {
        if (disabled()) return;
        if (blocked(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onSetSpawn(PlayerSetSpawnEvent event) {
        if (disabled()) return;
        if (blocked(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    /** 骑乘/上下载具. getEntityMounting 可能是任意实体, 判 Player。 */
    @SubscribeEvent
    public void onEntityMount(EntityMountEvent event) {
        if (disabled()) return;
        if (blocked(event.getEntityMounting())) {
            event.setCanceled(true);
        }
    }

    /** 投射物落点 (深度防御): 投掷动作已被 RightClickItem 拦, 此处再拦未认证玩家发出的投射物命中。 */
    @SubscribeEvent
    public void onProjectileImpact(ProjectileImpactEvent event) {
        if (disabled()) return;
        Entity owner = event.getProjectile().getOwner();
        if (blocked(owner)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        if (disabled()) return;
        if (blocked(event.getPlayer())) {
            // 防止密码或任意消息进公屏; 提示走登录命令
            event.setCanceled(true);
            event.getPlayer().sendSystemMessage(Component.literal("§c登录前无法聊天, 请先 /login"));
        }
    }

    // ==================== 不可取消事件: 替代手段 ====================

    /** 容器打开不可取消 (无 @Cancelable): 上游交互已被 RightClickBlock 拦, 此处强制关闭兜底。 */
    @SubscribeEvent
    public void onContainerOpen(PlayerContainerEvent.Open event) {
        if (disabled()) return;
        if (event.getEntity() instanceof ServerPlayer sp && !authService.isAuthed(sp.getUUID())) {
            sp.closeContainer();
        }
    }

    /** 睡觉不可取消: 用 setResult 阻止入睡 (源头通常已被 RightClickBlock 拦)。 */
    @SubscribeEvent
    public void onSleep(PlayerSleepInBedEvent event) {
        if (disabled()) return;
        if (blocked(event.getEntity())) {
            event.setResult(Player.BedSleepingProblem.OTHER_PROBLEM);
        }
    }

    // ==================== 命令拦截 ====================

    /** 未认证玩家仅放行登录类命令, 其余一律取消。 */
    @SubscribeEvent
    public void onCommand(CommandEvent event) {
        if (disabled()) return;
        ParseResults<CommandSourceStack> parse = event.getParseResults();
        CommandSourceStack source = parse.getContext().getSource();
        if (!(source.getEntity() instanceof ServerPlayer sp) || authService.isAuthed(sp.getUUID())) {
            return;
        }
        String root = rootLiteral(parse);
        if (root != null && AuthCommandNames.isAllowed(root)) {
            return;
        }
        event.setCanceled(true);
        sp.sendSystemMessage(Component.literal("§c登录前仅可使用 /login /register /changepassword"));
    }

    /** 取解析树的第一个节点名 (根命令字面量), 用于白名单放行判定。 */
    private String rootLiteral(ParseResults<CommandSourceStack> parse) {
        var nodes = parse.getContext().getNodes();
        if (nodes.isEmpty()) {
            return null;
        }
        ParsedCommandNode<CommandSourceStack> first = nodes.get(0);
        return first.getNode().getName();
    }

    /** 登录点快照. */
    private static final class LoginAnchor {
        final double x;
        final double y;
        final double z;
        final float yaw;
        final float pitch;

        LoginAnchor(double x, double y, double z, float yaw, float pitch) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }
}
