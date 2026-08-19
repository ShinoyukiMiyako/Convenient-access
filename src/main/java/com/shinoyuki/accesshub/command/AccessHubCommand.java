package com.shinoyuki.accesshub.command;

import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.shinoyuki.accesshub.AccessHubMod;
import com.shinoyuki.accesshub.auth.PlayerAuthAdminService;
import com.shinoyuki.accesshub.auth.PlayerAuthRecord;
import com.shinoyuki.accesshub.auth.PlayerAuthService;
import com.shinoyuki.accesshub.config.AccessHubConfig;
import com.shinoyuki.accesshub.http.HttpServer;
import com.shinoyuki.accesshub.whitelist.WhitelistEntry;
import com.shinoyuki.accesshub.whitelist.WhitelistManager;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * AccessHub 服务端命令注册.
 *
 * 主命令: /accesshub
 * 别名:  /ca, /ahub (通过 Brigadier redirect 共享同一棵子树, /ca status 等价于 /accesshub status)
 *
 * 权限: 默认要求 OP level 2 (.requires(src -&gt; src.hasPermission(2))),
 * 这是 Forge 命令的标准做法, 跟原版 /gamemode 等命令一致.
 *
 * 子命令:
 *   /accesshub status  - 显示运行状态 (HTTP 服务、配置文件路径)
 *   /accesshub reload  - 重载 common.toml 配置 (不重启 HTTP, 仅刷新内存缓存的配置值)
 *   /accesshub help    - 显示帮助 (默认子命令)
 */
public final class AccessHubCommand {

    /** 默认权限等级: OP level 2, 跟原版管理命令一致. */
    public static final int PERMISSION_LEVEL = 2;

    private AccessHubCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, AccessHubMod mod) {
        LiteralCommandNode<CommandSourceStack> root = dispatcher.register(
                Commands.literal("accesshub")
                        .requires(src -> src.hasPermission(PERMISSION_LEVEL))
                        .then(Commands.literal("status").executes(ctx -> doStatus(ctx, mod)))
                        .then(Commands.literal("reload").executes(ctx -> doReload(ctx, mod)))
                        .then(Commands.literal("whitelist")
                                .then(Commands.literal("add")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(ctx -> doWhitelistAdd(ctx, mod))))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(ctx -> doWhitelistRemove(ctx, mod))))
                                .then(Commands.literal("check")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(ctx -> doWhitelistCheck(ctx, mod))))
                                .then(Commands.literal("list")
                                        .executes(ctx -> doWhitelistList(ctx, mod))))
                        .then(Commands.literal("auth")
                                .then(Commands.literal("reset")
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .executes(ctx -> doAuthReset(ctx, mod))))
                                .then(Commands.literal("unregister")
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .executes(ctx -> doAuthReset(ctx, mod))))
                                .then(Commands.literal("info")
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .executes(ctx -> doAuthInfo(ctx, mod))))
                                .then(Commands.literal("gencode")
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .executes(ctx -> doAuthGenCode(ctx, mod)))
                                        .executes(ctx -> doAuthGenCodeAll(ctx, mod))))
                        .then(Commands.literal("help").executes(AccessHubCommand::doHelp))
                        .executes(AccessHubCommand::doHelp)
        );

        // alias 通过 redirect 共享根节点的所有子树
        dispatcher.register(Commands.literal("ca")
                .requires(src -> src.hasPermission(PERMISSION_LEVEL))
                .redirect(root));
        dispatcher.register(Commands.literal("ahub")
                .requires(src -> src.hasPermission(PERMISSION_LEVEL))
                .redirect(root));
    }

    private static int doStatus(CommandContext<CommandSourceStack> ctx, AccessHubMod mod) {
        CommandSourceStack source = ctx.getSource();
        AccessHubConfig config = mod.getConfig();
        HttpServer httpServer = mod.getHttpServer();

        source.sendSuccess(() -> headerLine("AccessHub 状态"), false);

        if (config == null) {
            source.sendSuccess(() -> Component.literal("  配置未加载 (mod 启动失败)")
                    .withStyle(ChatFormatting.RED), false);
            return 0;
        }

        boolean httpRunning = httpServer != null && httpServer.isRunning();
        source.sendSuccess(() -> kv("HTTP 服务", httpRunning
                        ? Component.literal("运行中 :" + config.getHttpPort()).withStyle(ChatFormatting.GREEN)
                        : Component.literal("未运行").withStyle(ChatFormatting.RED)),
                false);
        source.sendSuccess(() -> kv("白名单功能",
                        config.isWhitelistEnabled()
                                ? Component.literal("启用").withStyle(ChatFormatting.GREEN)
                                : Component.literal("禁用").withStyle(ChatFormatting.YELLOW)),
                false);
        source.sendSuccess(() -> kv("严格模式",
                        config.isWhitelistStrictMode()
                                ? Component.literal("开 (查询失败踢人)").withStyle(ChatFormatting.YELLOW)
                                : Component.literal("关 (查询失败放行)").withStyle(ChatFormatting.GRAY)),
                false);

        // 性能: 用 vanilla getAverageTickTime 计算 TPS/MSPT (无需 spark mod)
        MinecraftServer server = source.getServer();
        double mspt = Math.max(0.01, server.getAverageTickTime());
        double tps = Math.min(20.0, 1000.0 / mspt);
        ChatFormatting tpsColor = tps >= 19.0 ? ChatFormatting.GREEN
                : tps >= 15.0 ? ChatFormatting.YELLOW : ChatFormatting.RED;
        source.sendSuccess(() -> kv("性能", Component.literal(
                String.format("TPS %.1f / MSPT %.1fms", tps, mspt)).withStyle(tpsColor)), false);
        source.sendSuccess(() -> Component.literal("  (详细性能数据见 GET /api/v1/server/performance)")
                .withStyle(ChatFormatting.DARK_GRAY), false);

        return httpRunning ? 1 : 0;
    }

    private static int doReload(CommandContext<CommandSourceStack> ctx, AccessHubMod mod) {
        CommandSourceStack source = ctx.getSource();
        AccessHubConfig config = mod.getConfig();
        if (config == null) {
            source.sendFailure(Component.literal("配置未加载, 无法重载 (mod 启动失败)"));
            return 0;
        }
        try {
            config.reload();
            source.sendSuccess(() -> Component.literal("配置已重载: common.toml")
                    .withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("配置重载失败: " + e.getMessage())
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    // ==================== 白名单子命令 ====================
    // WhitelistManager 操作返回 CompletableFuture, 异步完成后需回服务器主线程发包 (reply)。

    private static int doWhitelistAdd(CommandContext<CommandSourceStack> ctx, AccessHubMod mod) {
        CommandSourceStack src = ctx.getSource();
        WhitelistManager wm = mod.getWhitelistManager();
        if (wm == null) {
            src.sendFailure(Component.literal("白名单系统未就绪 (mod 启动失败)"));
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        // 渠道标记 (存入 addedByUuid): 游戏内玩家用其真实 uuid (前端识别为"游戏内"), 控制台用 CONSOLE ("终端")
        String operatorUuid = src.getEntity() instanceof ServerPlayer sp ? sp.getStringUUID() : "CONSOLE";
        // 被加玩家的 UUID 留空, 由其首次登录时 PlayerLoggedInEvent 补全
        wm.addPlayerByNameOnly(name, src.getTextName(), operatorUuid, WhitelistEntry.Source.ADMIN)
                .thenAccept(ok -> {
                    if (!ok) {
                        reply(src, Component.literal("添加失败: " + name + " 可能已在白名单中").withStyle(ChatFormatting.RED));
                        return;
                    }
                    // 注册码临时停用: 加白不再随回执签发码, 玩家进服直接两参数 /register 即可。
                    // 恢复时取消下方整块注释, 并删掉那条无码回执。
                    // PlayerAuthService auth = mod.getPlayerAuthService();
                    // AccessHubConfig config = mod.getConfig();
                    // if (auth != null && config != null && config.isPlayerAuthEnabled()) {
                    //     String regCode = auth.generateRegistrationCode(name);
                    //     if (regCode != null) {
                    //         reply(src, Component.literal("已添加 " + name + " 到白名单\n注册码: " + regCode
                    //                 + " (一次性, 仅限该用户名; 转交该玩家用 /register <密码> <确认> " + regCode + ")")
                    //                 .withStyle(ChatFormatting.GREEN));
                    //         return;
                    //     }
                    //     reply(src, Component.literal("已添加 " + name + " 到白名单, 但注册码生成失败 (可用 /accesshub auth gencode "
                    //             + name + " 重试)").withStyle(ChatFormatting.YELLOW));
                    //     return;
                    // }
                    reply(src, Component.literal("已添加 " + name + " 到白名单 (UUID 待首次登录补全; 玩家进服用 /register <密码> <确认密码> 注册)")
                            .withStyle(ChatFormatting.GREEN));
                })
                .exceptionally(t -> {
                    reply(src, Component.literal("添加异常: " + t.getMessage()).withStyle(ChatFormatting.RED));
                    return null;
                });
        return 1;
    }

    private static int doWhitelistRemove(CommandContext<CommandSourceStack> ctx, AccessHubMod mod) {
        CommandSourceStack src = ctx.getSource();
        WhitelistManager wm = mod.getWhitelistManager();
        if (wm == null) {
            src.sendFailure(Component.literal("白名单系统未就绪 (mod 启动失败)"));
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        wm.removePlayerByName(name)
                .thenAccept(ok -> reply(src, ok
                        ? Component.literal("已从白名单移除 " + name).withStyle(ChatFormatting.GREEN)
                        : Component.literal("移除失败: " + name + " 不在白名单中").withStyle(ChatFormatting.RED)))
                .exceptionally(t -> {
                    reply(src, Component.literal("移除异常: " + t.getMessage()).withStyle(ChatFormatting.RED));
                    return null;
                });
        return 1;
    }

    private static int doWhitelistCheck(CommandContext<CommandSourceStack> ctx, AccessHubMod mod) {
        CommandSourceStack src = ctx.getSource();
        WhitelistManager wm = mod.getWhitelistManager();
        if (wm == null) {
            src.sendFailure(Component.literal("白名单系统未就绪 (mod 启动失败)"));
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        wm.isPlayerWhitelistedByName(name)
                .thenAccept(in -> reply(src, in
                        ? Component.literal(name + " 在白名单中").withStyle(ChatFormatting.GREEN)
                        : Component.literal(name + " 不在白名单中").withStyle(ChatFormatting.YELLOW)))
                .exceptionally(t -> {
                    reply(src, Component.literal("查询异常: " + t.getMessage()).withStyle(ChatFormatting.RED));
                    return null;
                });
        return 1;
    }

    private static int doWhitelistList(CommandContext<CommandSourceStack> ctx, AccessHubMod mod) {
        CommandSourceStack src = ctx.getSource();
        WhitelistManager wm = mod.getWhitelistManager();
        if (wm == null) {
            src.sendFailure(Component.literal("白名单系统未就绪 (mod 启动失败)"));
            return 0;
        }
        // 取前 20 条, 按添加时间降序; 命令行只展示前 10 条 + 余量提示, 完整列表走 HTTP API/面板
        wm.getWhitelistPaginated(1, 20, null, null, null, "added_at", "DESC", null, null)
                .thenAccept(result -> {
                    StringBuilder sb = new StringBuilder("§6=== 白名单 (共 " + result.getTotal() + " 人) ===");
                    int count = 0;
                    for (WhitelistEntry entry : result.getItems()) {
                        count++;
                        sb.append("\n§e").append(count).append(". §f").append(entry.getName());
                        String uuid = entry.getUuid();
                        sb.append(uuid == null || uuid.isEmpty() ? " §8(UUID 待补全)" : " §8" + uuid);
                        if (count >= 10) {
                            long rest = result.getTotal() - 10;
                            if (rest > 0) {
                                sb.append("\n§7... 还有 ").append(rest).append(" 人, 完整列表见后台/API");
                            }
                            break;
                        }
                    }
                    if (count == 0) {
                        sb.append("\n§7白名单为空");
                    }
                    reply(src, Component.literal(sb.toString()));
                })
                .exceptionally(t -> {
                    reply(src, Component.literal("获取白名单失败: " + t.getMessage()).withStyle(ChatFormatting.RED));
                    return null;
                });
        return 1;
    }

    // ==================== 玩家认证管理子命令 ====================
    // 离线认证管理 (清密码 / 注销 / 查询). DB 操作阻塞, 放 supplyAsync 后回主线程发包。

    /**
     * auth reset / auth unregister: 清密码记录 + 吊销设备免密绑定, 在线则原地降级为未认证。
     *
     * 两件事都做才算重置: 曾经这里只删密码记录, 而 device_keys 里的公钥仍在, 玩家用原客户端进服
     * 照样验签解冻, 重置等于没做。清库与在线降级统一由 PlayerAuthAdminService 承担, 与 HTTP 端点同源。
     */
    private static int doAuthReset(CommandContext<CommandSourceStack> ctx, AccessHubMod mod) {
        CommandSourceStack src = ctx.getSource();
        PlayerAuthAdminService admin = mod.getPlayerAuthAdminService();
        if (admin == null) {
            src.sendFailure(Component.literal("玩家认证系统未就绪 (未启用或 mod 启动失败)"));
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "player");
        MinecraftServer server = src.getServer();
        admin.reset(name)
                .thenAccept(outcome -> server.execute(() -> {
                    if (!outcome.isAnythingCleared()) {
                        src.sendSuccess(() -> Component.literal(name + " 没有认证记录")
                                .withStyle(ChatFormatting.YELLOW), false);
                        return;
                    }
                    src.sendSuccess(() -> Component.literal("已重置玩家认证: " + name
                                    + " (密码记录" + (outcome.isPasswordCleared() ? "已清除" : "无")
                                    + ", 免密登记" + (outcome.isDeviceRevoked() ? "已吊销" : "无") + ")")
                            .withStyle(ChatFormatting.GREEN), true);
                }))
                .exceptionally(t -> {
                    reply(src, Component.literal("重置异常: " + t.getMessage()).withStyle(ChatFormatting.RED));
                    return null;
                });
        return 1;
    }

    /** auth info: 查询玩家认证记录摘要 (不展示密码哈希)。 */
    private static int doAuthInfo(CommandContext<CommandSourceStack> ctx, AccessHubMod mod) {
        CommandSourceStack src = ctx.getSource();
        PlayerAuthService auth = mod.getPlayerAuthService();
        if (auth == null) {
            src.sendFailure(Component.literal("玩家认证系统未就绪 (未启用或 mod 启动失败)"));
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "player");
        MinecraftServer server = src.getServer();
        CompletableFuture.supplyAsync(() -> auth.adminInfo(name))
                .thenAccept(opt -> server.execute(() -> {
                    if (opt.isEmpty()) {
                        src.sendSuccess(() -> Component.literal(name + " 未注册").withStyle(ChatFormatting.YELLOW), false);
                        return;
                    }
                    PlayerAuthRecord r = opt.get();
                    StringBuilder sb = new StringBuilder("§6=== 认证信息: " + r.getUsername() + " ===");
                    sb.append("\n§7注册时间: §f").append(r.getRegisteredAt());
                    sb.append("\n§7最后登录: §f").append(r.getLastLoginAt());
                    sb.append("\n§7最后登录 IP: §f").append(r.getLastLoginIp());
                    sb.append("\n§7累计失败 (自上次成功登录): §f").append(r.getFailCount());
                    src.sendSuccess(() -> Component.literal(sb.toString()), false);
                }))
                .exceptionally(t -> {
                    reply(src, Component.literal("查询异常: " + t.getMessage()).withStyle(ChatFormatting.RED));
                    return null;
                });
        return 1;
    }

    /**
     * auth gencode &lt;player&gt;: 为指定玩家生成一次性、绑定其用户名、会过期的码, 由 OP 转交该玩家。
     * 注册码在 /register 环节已临时停用, 此命令签发的码当前只对 /enroll (换机免密登记) 有效。
     */
    private static int doAuthGenCode(CommandContext<CommandSourceStack> ctx, AccessHubMod mod) {
        CommandSourceStack src = ctx.getSource();
        PlayerAuthService auth = mod.getPlayerAuthService();
        AccessHubConfig config = mod.getConfig();
        if (auth == null || config == null || !config.isPlayerAuthEnabled()) {
            src.sendFailure(Component.literal("玩家认证未启用或未就绪"));
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "player");
        MinecraftServer server = src.getServer();
        CompletableFuture.supplyAsync(() -> auth.generateRegistrationCode(name))
                .thenAccept(code -> server.execute(() -> {
                    if (code == null) {
                        src.sendFailure(Component.literal("生成注册码失败 (系统繁忙), 请稍后重试"));
                    } else {
                        src.sendSuccess(() -> Component.literal("§a已为 " + name + " 生成码: §e" + code
                                + " §7(一次性, 仅限该用户名, 有效期 " + config.getPlayerAuthCodeExpiryMinutes()
                                + " 分钟)\n§7注册码已停用, 该码仅用于换机登记: §f/enroll " + code), false);
                    }
                }))
                .exceptionally(t -> {
                    reply(src, Component.literal("生成注册码异常: " + t.getMessage()).withStyle(ChatFormatting.RED));
                    return null;
                });
        return 1;
    }

    /**
     * auth gencode (无参): 为所有白名单中尚未注册的玩家批量生成码, 汇总回执给 OP。
     * 同 gencode &lt;player&gt;: 注册码停用期间这些码只对 /enroll 有效。
     */
    private static int doAuthGenCodeAll(CommandContext<CommandSourceStack> ctx, AccessHubMod mod) {
        CommandSourceStack src = ctx.getSource();
        PlayerAuthService auth = mod.getPlayerAuthService();
        WhitelistManager wm = mod.getWhitelistManager();
        AccessHubConfig config = mod.getConfig();
        if (auth == null || wm == null || config == null || !config.isPlayerAuthEnabled()) {
            src.sendFailure(Component.literal("玩家认证或白名单未就绪"));
            return 0;
        }
        wm.getWhitelistPaginated(1, 1000, null, null, null, "added_at", "DESC", null, null)
                .thenAccept(result -> {
                    StringBuilder sb = new StringBuilder("§6=== 批量生成码 (未注册的白名单玩家; 仅 /enroll 可用) ===");
                    int generated = 0, skipped = 0, failed = 0;
                    for (WhitelistEntry entry : result.getItems()) {
                        String pname = entry.getName();
                        boolean registered;
                        try {
                            registered = auth.isRegistered(pname);
                        } catch (RuntimeException e) {
                            failed++; // fail-closed: 查询失败跳过该玩家, 绝不误发
                            continue;
                        }
                        if (registered) {
                            skipped++;
                            continue;
                        }
                        String code = auth.generateRegistrationCode(pname);
                        if (code == null) {
                            failed++;
                            continue;
                        }
                        generated++;
                        sb.append("\n§f").append(pname).append(": §e").append(code);
                    }
                    sb.append("\n§7已生成 ").append(generated).append(" 个, 跳过已注册 ")
                            .append(skipped).append(" 个");
                    if (failed > 0) {
                        sb.append(", §c失败 ").append(failed).append(" 个");
                    }
                    // 不静默截断: 白名单超过单页上限时明示
                    if (result.getTotal() > result.getItems().size()) {
                        sb.append("\n§c注意: 白名单共 ").append(result.getTotal()).append(" 人, 本次仅处理前 ")
                                .append(result.getItems().size()).append(" 人, 请重复执行或分批处理");
                    }
                    if (generated == 0 && skipped == 0 && failed == 0) {
                        sb.append("\n§7白名单为空");
                    }
                    reply(src, Component.literal(sb.toString()));
                })
                .exceptionally(t -> {
                    reply(src, Component.literal("批量生成失败: " + t.getMessage()).withStyle(ChatFormatting.RED));
                    return null;
                });
        return 1;
    }

    /** 异步回调结果回服务器主线程发送给命令源 (sendSuccess 须在主线程调用)。 */
    private static void reply(CommandSourceStack src, Component message) {
        MinecraftServer server = src.getServer();
        server.execute(() -> src.sendSuccess(() -> message, false));
    }

    private static int doHelp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> headerLine("AccessHub 命令"), false);
        source.sendSuccess(() -> usage("/accesshub status", "显示运行状态"), false);
        source.sendSuccess(() -> usage("/accesshub reload", "重载 common.toml"), false);
        source.sendSuccess(() -> usage("/accesshub whitelist add <名字>", "加白名单 (UUID 登录补全)"), false);
        source.sendSuccess(() -> usage("/accesshub whitelist remove <名字>", "移除白名单"), false);
        source.sendSuccess(() -> usage("/accesshub whitelist check <名字>", "检查是否在白名单"), false);
        source.sendSuccess(() -> usage("/accesshub whitelist list", "列出白名单 (前 10)"), false);
        source.sendSuccess(() -> usage("/accesshub auth reset <玩家>", "清除密码强制重注册"), false);
        source.sendSuccess(() -> usage("/accesshub auth unregister <玩家>", "注销玩家认证记录"), false);
        source.sendSuccess(() -> usage("/accesshub auth info <玩家>", "查询玩家认证信息"), false);
        source.sendSuccess(() -> usage("/accesshub auth gencode <玩家>", "生成绑定码 (注册码已停用, 仅 /enroll 可用)"), false);
        source.sendSuccess(() -> usage("/accesshub auth gencode", "为未注册白名单玩家批量生成码"), false);
        source.sendSuccess(() -> usage("/accesshub help", "显示此帮助"), false);
        source.sendSuccess(() -> Component.literal("别名: /ca /ahub").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static MutableComponent headerLine(String title) {
        return Component.literal("=== ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(title).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" ===").withStyle(ChatFormatting.GRAY));
    }

    private static MutableComponent kv(String key, Component value) {
        return Component.literal("  " + key + ": ").withStyle(ChatFormatting.GRAY).append(value);
    }

    private static MutableComponent usage(String command, String desc) {
        return Component.literal("  " + command).withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(" - " + desc).withStyle(ChatFormatting.GRAY));
    }
}
