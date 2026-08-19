package com.shinoyuki.accesshub.latency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

class PlayerLatencyProbeTest {

    private static final long BASE_NANOS = 1_000_000_000_000L;

    private static long ms(double value) {
        return (long) (value * 1_000_000.0);
    }

    private static PlayerLatencyProbe probe(int windowSamples, int burstSamples) {
        return new PlayerLatencyProbe(
                new LatencyEstimator(windowSamples, 1.3, 5_000_000L, 2), burstSamples);
    }

    @Test
    void rttIsMeasuredFromWriteCompletionToPongArrival() {
        PlayerLatencyProbe probe = probe(16, 0);
        int id = probe.allocateProbeId();

        probe.markSent(id, BASE_NANOS);
        probe.onPong(id, BASE_NANOS + ms(30));

        assertEquals(30, probe.publishedMillis());
        assertEquals(0, probe.unmatchedPongs());
    }

    /** 对端重发或恶意重放同一个 id 时, 第二次不得再产生样本。 */
    @Test
    void duplicatePongDoesNotProduceSecondSample() {
        PlayerLatencyProbe probe = probe(16, 0);
        int id = probe.allocateProbeId();
        probe.markSent(id, BASE_NANOS);
        probe.onPong(id, BASE_NANOS + ms(50));
        assertEquals(50, probe.publishedMillis());

        // 重放同一个 id 且伪造成极低延迟: 若被计入, min 口径会被直接拉到 1ms
        probe.onPong(id, BASE_NANOS + ms(1));

        assertEquals(50, probe.publishedMillis(), "重放的 pong 不得进入估计");
        assertEquals(1, probe.unmatchedPongs());
    }

    @Test
    void unknownPongIsCountedAndIgnored() {
        PlayerLatencyProbe probe = probe(16, 0);
        int id = probe.allocateProbeId();
        probe.markSent(id, BASE_NANOS);

        probe.onPong(ProbeId.of(999999), BASE_NANOS + ms(5));

        assertEquals(LatencyEstimator.NO_ESTIMATE, probe.publishedMillis(), "配不上的 pong 不得产生估计");
        assertEquals(1, probe.unmatchedPongs());
    }

    /** 在飞表是定长环形: 丢包不会积累, 但超过容量的旧探测会被覆盖, 其 pong 因此配不上。 */
    @Test
    void oldestPendingEntryIsOverwrittenBeyondCapacity() {
        PlayerLatencyProbe probe = probe(16, 0);
        int firstId = probe.allocateProbeId();
        probe.markSent(firstId, BASE_NANOS);
        for (int i = 0; i < 16; i++) {
            probe.markSent(probe.allocateProbeId(), BASE_NANOS + ms(i + 1));
        }

        probe.onPong(firstId, BASE_NANOS + ms(1));

        assertEquals(LatencyEstimator.NO_ESTIMATE, probe.publishedMillis());
        assertEquals(1, probe.unmatchedPongs(), "被覆盖的最旧条目应表现为配不上");
    }

    /** 时钟异常导致的非正 RTT 必须被丢弃, 且不得抛异常 —— 抛在入站路径上会断掉玩家连接。 */
    @Test
    void nonPositiveRttIsDiscardedWithoutThrowing() {
        PlayerLatencyProbe probe = probe(16, 0);
        int id = probe.allocateProbeId();
        probe.markSent(id, BASE_NANOS);

        probe.onPong(id, BASE_NANOS);

        assertEquals(LatencyEstimator.NO_ESTIMATE, probe.publishedMillis());
        assertEquals(1, probe.unmatchedPongs());
    }

    /** 登录突发: 前若干次每 tick 一发, 让首个估计在几百毫秒内出来, 之后回到常规间隔。 */
    @Test
    void loginBurstProbesEveryTickThenFallsBackToInterval() {
        PlayerLatencyProbe probe = probe(16, 5);
        int burstHits = 0;
        for (int tick = 0; tick < 5; tick++) {
            if (probe.tickDueForProbe(4, 0)) {
                burstHits++;
            }
        }
        assertEquals(5, burstHits, "突发阶段应每 tick 都触发");

        int steadyHits = 0;
        for (int tick = 0; tick < 20; tick++) {
            if (probe.tickDueForProbe(4, 0)) {
                steadyHits++;
            }
        }
        assertEquals(5, steadyHits, "突发结束后 20 tick 内应按 4 tick 间隔触发 5 次");
    }

    /** 抖动必须真的抖, 且始终落在 [interval-jitter, interval+jitter] 内。 */
    @Test
    void jitterVariesIntervalWithinBounds() {
        PlayerLatencyProbe probe = probe(16, 0);
        int interval = 4;
        int jitter = 1;
        boolean sawNonDefaultGap = false;
        int gap = 0;
        // 第一次触发发生在初始倒计时耗尽时, 从那之后开始量间隔
        while (!probe.tickDueForProbe(interval, jitter)) {
            gap++;
        }
        for (int round = 0; round < 200; round++) {
            gap = 0;
            while (!probe.tickDueForProbe(interval, jitter)) {
                gap++;
            }
            int actual = gap + 1;
            assertTrue(actual >= interval - jitter && actual <= interval + jitter,
                    "间隔应落在 [3,5], 实际=" + actual);
            if (actual != interval) {
                sawNonDefaultGap = true;
            }
        }
        assertTrue(sawNonDefaultGap, "200 轮内必须出现非默认间隔, 否则抖动没有生效");
    }

    /** 探测 id 必须恒带魔数且永不为 0 —— 在飞表用 0 作空槽哨兵, 这是它的正确性前提。 */
    @Test
    void allocatedIdsAlwaysCarryMagicAndAreNeverZero() {
        PlayerLatencyProbe probe = probe(16, 0);
        Random random = new Random(20260819L);
        for (int i = 0; i < 10_000; i++) {
            int id = probe.allocateProbeId();
            assertTrue(ProbeId.isProbe(id), "分配出的 id 必须可被识别为探测包: " + Integer.toHexString(id));
            assertNotEquals(0, id, "探测 id 不得为 0, 否则会与在飞表的空槽哨兵混淆");
            // 随机跳过若干序号, 覆盖序号非连续增长的情形
            for (int skip = random.nextInt(3); skip > 0; skip--) {
                probe.allocateProbeId();
            }
        }
    }
}
