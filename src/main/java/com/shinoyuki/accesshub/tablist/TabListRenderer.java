package com.shinoyuki.accesshub.tablist;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;

/**
 * Tab 列表的纯渲染层: 把采样值拼成原版 Component。
 *
 * 全部由服务端渲染后经原版协议下发, 客户端用原版 PlayerTabOverlay 显示, 因此玩家不需要装任何 mod。
 */
final class TabListRenderer {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final int MEM_BAR_WIDTH = 10;

    private TabListRenderer() {
    }

    static Component buildHeader(long uptimeMs) {
        LocalDateTime now = LocalDateTime.now();
        MutableComponent line1 = Component.empty()
                .append(Component.literal("时间 ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(now.format(DATE_FMT) + " " + now.format(TIME_FMT))
                        .withStyle(ChatFormatting.WHITE));
        MutableComponent line2 = Component.empty()
                .append(Component.literal("运行 ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(formatDuration(Duration.ofMillis(uptimeMs)))
                        .withStyle(ChatFormatting.WHITE));
        return Component.empty().append(line1).append(Component.literal("\n")).append(line2);
    }

    static Component buildFooter(double tps, double mspt, double cpuLoad, long heapUsed, long heapMax) {
        MutableComponent line1 = Component.empty();
        line1.append(Component.literal("TPS ").withStyle(ChatFormatting.GRAY));
        line1.append(Component.literal(String.format("%.1f", tps)).withStyle(colorForTps(tps)));
        line1.append(separator());
        line1.append(Component.literal("MSPT ").withStyle(ChatFormatting.GRAY));
        line1.append(Component.literal(String.format("%.1f", mspt) + "ms").withStyle(colorForMspt(mspt)));
        line1.append(separator());
        line1.append(Component.literal("CPU ").withStyle(ChatFormatting.GRAY));
        line1.append(Component.literal(formatPercent(cpuLoad)).withStyle(colorForLoad(cpuLoad)));

        long usedMb = Math.max(0L, Math.round(heapUsed / 1048576.0));
        long maxMb = Math.max(1L, Math.round(heapMax / 1048576.0));
        double load = Math.min(1.0, usedMb / (double) maxMb);
        int filled = clamp((int) Math.round(load * MEM_BAR_WIDTH), 0, MEM_BAR_WIDTH);

        MutableComponent line2 = Component.empty();
        line2.append(Component.literal("内存 ").withStyle(ChatFormatting.GRAY));
        line2.append(Component.literal(usedMb + "/" + maxMb + "MB").withStyle(ChatFormatting.WHITE));
        line2.append(Component.literal(" "));
        line2.append(Component.literal(repeat('|', filled)).withStyle(colorForLoad(load)));
        line2.append(Component.literal(repeat('|', MEM_BAR_WIDTH - filled)).withStyle(ChatFormatting.DARK_GRAY));
        line2.append(Component.literal(" " + Math.round(load * 100) + "%").withStyle(colorForLoad(load)));

        return Component.empty().append(line1).append(Component.literal("\n")).append(line2);
    }

    /**
     * Tab 行的显示名: 队伍前缀 + 队伍配色的名字 + 队伍后缀 + 彩色延迟。
     */
    static Component buildDisplayName(ServerPlayer player, int latencyMs, int greenThreshold, int yellowThreshold) {
        return teamFormattedName(player).append(pingSuffix(latencyMs, greenThreshold, yellowThreshold));
    }

    /** 延迟后缀单独成函数: 不依赖 ServerPlayer, 阈值分档因此可以直接单测。 */
    static Component pingSuffix(int latencyMs, int greenThreshold, int yellowThreshold) {
        int latency = Math.max(0, latencyMs);
        return Component.literal(" " + latency + "ms")
                .withStyle(colorForPing(latency, greenThreshold, yellowThreshold));
    }

    private static MutableComponent teamFormattedName(ServerPlayer player) {
        Component name = Component.literal(player.getGameProfile().getName());
        if (!(player.getTeam() instanceof PlayerTeam team)) {
            return Component.empty().append(name);
        }
        ChatFormatting color = team.getColor();
        Style style = (color != null && color != ChatFormatting.RESET)
                ? Style.EMPTY.applyFormat(color)
                : Style.EMPTY;
        return Component.empty()
                .append(team.getPlayerPrefix().copy())
                .append(name.copy().withStyle(style))
                .append(team.getPlayerSuffix().copy());
    }

    private static Component separator() {
        return Component.literal("    ").withStyle(ChatFormatting.GRAY);
    }

    /** 负值代表该指标取不到, 显示为 "--%" 而不是伪造一个 0。 */
    private static String formatPercent(double value) {
        if (value < 0) {
            return "--%";
        }
        return Math.round(Math.max(0.0, Math.min(1.0, value)) * 100) + "%";
    }

    private static ChatFormatting colorForPing(int latencyMs, int greenThreshold, int yellowThreshold) {
        if (latencyMs < greenThreshold) {
            return ChatFormatting.GREEN;
        }
        return latencyMs < yellowThreshold ? ChatFormatting.YELLOW : ChatFormatting.RED;
    }

    private static ChatFormatting colorForTps(double tps) {
        if (tps >= 19.5) {
            return ChatFormatting.GREEN;
        }
        return tps >= 15.0 ? ChatFormatting.YELLOW : ChatFormatting.RED;
    }

    private static ChatFormatting colorForMspt(double mspt) {
        if (mspt < 50.0) {
            return ChatFormatting.GREEN;
        }
        return mspt < 100.0 ? ChatFormatting.YELLOW : ChatFormatting.RED;
    }

    private static ChatFormatting colorForLoad(double value) {
        if (value < 0) {
            return ChatFormatting.GRAY;
        }
        if (value < 0.70) {
            return ChatFormatting.GREEN;
        }
        return value < 0.90 ? ChatFormatting.YELLOW : ChatFormatting.RED;
    }

    private static String repeat(char c, int count) {
        if (count <= 0) {
            return "";
        }
        char[] buffer = new char[count];
        Arrays.fill(buffer, c);
        return new String(buffer);
    }

    private static int clamp(int value, int low, int high) {
        return value < low ? low : Math.min(value, high);
    }

    private static String formatDuration(Duration duration) {
        long seconds = duration.getSeconds();
        long days = seconds / 86400;
        long hours = (seconds / 3600) % 24;
        long minutes = (seconds / 60) % 60;
        long secs = seconds % 60;
        if (days > 0) {
            return String.format("%dd %02d:%02d:%02d", days, hours, minutes, secs);
        }
        return String.format("%02d:%02d:%02d", hours, minutes, secs);
    }
}
