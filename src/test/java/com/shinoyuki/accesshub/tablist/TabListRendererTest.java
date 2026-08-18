package com.shinoyuki.accesshub.tablist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.TextColor;

class TabListRendererTest {

    private static final long MB = 1048576L;

    @Test
    void unavailableCpuRendersPlaceholderInsteadOfZero() {
        // -1 是"取不到"的哨兵值。渲染成 0% 会让人误以为服务器闲着, 必须与真正的 0% 区分开。
        String unavailable = TabListRenderer.buildFooter(20.0, 5.0, -1.0, 100 * MB, 1000 * MB).getString();
        assertTrue(unavailable.contains("CPU --%"), "取不到 CPU 负载时应显示 --%, 实际: " + unavailable);

        String idle = TabListRenderer.buildFooter(20.0, 5.0, 0.0, 100 * MB, 1000 * MB).getString();
        assertTrue(idle.contains("CPU 0%"), "真实 0 负载应显示 0%, 实际: " + idle);
    }

    @Test
    void memoryBarFillsProportionallyAndReportsPercent() {
        String half = TabListRenderer.buildFooter(20.0, 5.0, 0.5, 512 * MB, 1024 * MB).getString();
        assertTrue(half.contains("512/1024MB"), "应展示实际用量, 实际: " + half);
        assertTrue(half.contains("50%"), "512/1024 应为 50%, 实际: " + half);
        // 进度条总宽固定 10 格, 半载填 5 格
        assertEquals(10, countBars(half), "进度条总格数应恒为 10, 实际: " + half);

        String empty = TabListRenderer.buildFooter(20.0, 5.0, 0.5, 0L, 1024 * MB).getString();
        assertTrue(empty.contains(" 0%"), "零占用应为 0%, 实际: " + empty);

        String full = TabListRenderer.buildFooter(20.0, 5.0, 0.5, 1024 * MB, 1024 * MB).getString();
        assertTrue(full.contains("100%"), "满载应为 100%, 实际: " + full);
        assertEquals(10, countBars(full), "满载进度条仍应是 10 格, 实际: " + full);
    }

    @Test
    void heapLargerThanMaxIsClampedRatherThanOverflowingTheBar() {
        // ZGC 等场景下 used 可能短暂超过 max, 不能让进度条画出 10 格以上
        String overflow = TabListRenderer.buildFooter(20.0, 5.0, 0.5, 2048 * MB, 1024 * MB).getString();
        assertEquals(10, countBars(overflow), "超额占用时进度条不得溢出, 实际: " + overflow);
        assertTrue(overflow.contains("100%"), "超额占用应封顶在 100%, 实际: " + overflow);
    }

    @Test
    void tpsAndMsptKeepOneDecimal() {
        String footer = TabListRenderer.buildFooter(19.96, 5.04, 0.42, 100 * MB, 1000 * MB).getString();
        assertTrue(footer.contains("TPS 20.0"), "TPS 应保留一位小数, 实际: " + footer);
        assertTrue(footer.contains("MSPT 5.0ms"), "MSPT 应保留一位小数并带单位, 实际: " + footer);
        assertTrue(footer.contains("CPU 42%"), "CPU 应四舍五入成整数百分比, 实际: " + footer);
    }

    @Test
    void uptimeSwitchesToDayPrefixOnlyAfterAFullDay() {
        String underOneDay = TabListRenderer.buildHeader(hours(23) + minutes(59) + seconds(59)).getString();
        assertTrue(underOneDay.contains("23:59:59"), "不足一天应为 时:分:秒, 实际: " + underOneDay);
        assertTrue(!underOneDay.contains("d "), "不足一天不应出现天数前缀, 实际: " + underOneDay);

        String overOneDay = TabListRenderer.buildHeader(hours(25) + minutes(2) + seconds(3)).getString();
        assertTrue(overOneDay.contains("1d 01:02:03"), "满一天应带天数前缀, 实际: " + overOneDay);
    }

    @Test
    void pingSuffixSwitchesColorOnConfiguredThresholds() {
        // 阈值取"小于"语义: 59 还在绿档, 到 60 就转黄, 到 120 转红
        assertEquals(" 59ms", TabListRenderer.pingSuffix(59, 60, 120).getString());
        assertEquals(colorOf(ChatFormatting.GREEN), TabListRenderer.pingSuffix(59, 60, 120).getStyle().getColor());
        assertEquals(colorOf(ChatFormatting.YELLOW), TabListRenderer.pingSuffix(60, 60, 120).getStyle().getColor());
        assertEquals(colorOf(ChatFormatting.YELLOW), TabListRenderer.pingSuffix(119, 60, 120).getStyle().getColor());
        assertEquals(colorOf(ChatFormatting.RED), TabListRenderer.pingSuffix(120, 60, 120).getStyle().getColor());
    }

    @Test
    void negativeLatencyIsClampedToZero() {
        // 玩家刚连上时 latency 尚未采样, 原版会给 -1, 不能把负数直接展示出去
        assertEquals(" 0ms", TabListRenderer.pingSuffix(-5, 60, 120).getString());
        assertEquals(colorOf(ChatFormatting.GREEN), TabListRenderer.pingSuffix(-5, 60, 120).getStyle().getColor());
    }

    private static TextColor colorOf(ChatFormatting formatting) {
        return TextColor.fromLegacyFormat(formatting);
    }

    private static int countBars(String rendered) {
        return (int) rendered.chars().filter(c -> c == '|').count();
    }

    private static long hours(long h) {
        return h * 3600_000L;
    }

    private static long minutes(long m) {
        return m * 60_000L;
    }

    private static long seconds(long s) {
        return s * 1000L;
    }
}
