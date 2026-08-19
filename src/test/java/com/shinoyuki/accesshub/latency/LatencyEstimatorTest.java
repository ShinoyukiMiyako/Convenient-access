package com.shinoyuki.accesshub.latency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

class LatencyEstimatorTest {

    private static final double JUMP_RATIO = 1.3;
    private static final long JUMP_FLOOR_NANOS = 5_000_000L;
    private static final int JUMP_CONFIRMATIONS = 2;

    private static long ms(double value) {
        return (long) (value * 1_000_000.0);
    }

    private static LatencyEstimator estimator(int windowSamples) {
        return new LatencyEstimator(windowSamples, JUMP_RATIO, JUMP_FLOOR_NANOS, JUMP_CONFIRMATIONS);
    }

    private static void feed(LatencyEstimator estimator, long sampleNanos, int times) {
        for (int i = 0; i < times; i++) {
            estimator.accept(sampleNanos);
        }
    }

    /**
     * 核心主张: 测量噪声单边正向时, 窗口取 min 逼近真值, 而取均值会被噪声整体抬高。
     * 客户端 handlePing 要等下一帧才回包, 60fps 下这份排队噪声就是 [0, 16.7ms] 的正向抖动。
     */
    /** 真实 RTT 20ms, 叠加客户端一帧 (60fps) 的排队噪声 —— 这就是通道 1 的实际样本分布。 */
    private static final long TRUTH_NANOS = ms(20);
    private static final long FRAME_NOISE_CEILING_NANOS = ms(16.7);

    /** 写死的种子: 既覆盖不同的随机噪声序列, 又保证结果完全可复现, 不会偶发挂测试。 */
    private static final long[] SEEDS = {1L, 20260819L, 777L, -3L, Long.MAX_VALUE};

    /**
     * 核心主张: 测量噪声单边正向时, 窗口取 min 逼近真值, 而取均值会被噪声整体抬高。
     * 客户端 handlePing 要等下一帧才回包, 60fps 下这份排队噪声就是 [0, 16.7ms] 的正向抖动。
     *
     * <p>单个 16 样本窗口的 min 方差不小 (偶尔会抽到 5ms 以上的最小值), 故"显著优于均值"
     * 用多轮聚合表达长期表现, 逐轮只断言恒成立的关系与工程上界。
     */
    @Test
    void windowMinimumRejectsOneSidedPositiveNoise() {
        long minSum = 0L;
        long meanSum = 0L;
        for (long seed : SEEDS) {
            Random random = new Random(seed);
            LatencyEstimator estimator = estimator(16);
            long sum = 0L;
            for (int i = 0; i < 16; i++) {
                long sample = TRUTH_NANOS + (long) (random.nextDouble() * FRAME_NOISE_CEILING_NANOS);
                sum += sample;
                estimator.accept(sample);
            }
            int estimate = estimator.estimateMillis();
            int mean = (int) ((sum / 16 + 500_000L) / 1_000_000L);

            assertTrue(estimate >= 20, "估计不得低于真实 RTT 20ms, seed=" + seed + " 实际=" + estimate);
            assertTrue(estimate <= 26, "16 个样本取 min 应贴近真值, seed=" + seed + " 实际=" + estimate);
            assertTrue(estimate <= mean, "min 口径不可能劣于均值口径, seed=" + seed
                    + " min=" + estimate + " mean=" + mean);
            minSum += estimate;
            meanSum += mean;
        }
        double avgMin = (double) minSum / SEEDS.length;
        double avgMean = (double) meanSum / SEEDS.length;
        assertTrue(avgMin < avgMean - 5,
                "长期看取 min 必须显著优于取均值, 实际 min 均值=" + avgMin + " mean 均值=" + avgMean);
    }

    /**
     * 回归: 稳态噪声不得被误判成跳变。
     *
     * <p>噪声上界 16.7ms 远超"当前 min 的 30% 且至少 5ms"这个门槛, 若跳变判据只看这两条,
     * 连着两个高噪声样本就会清窗, 估计随即跳到噪声峰值附近。窗口一旦被清空 sampleCount 就会掉下来,
     * 逐样本检查它是对误清窗零漏报也零误报的判据。
     */
    @Test
    void steadyStateNoiseNeverTriggersFalseJumpReset() {
        for (long seed : SEEDS) {
            Random random = new Random(seed);
            LatencyEstimator estimator = estimator(16);
            // 先填满窗口: 冷启动阶段样本不足, 估计等于头几个样本本身, 不属于稳态行为
            for (int i = 0; i < 16; i++) {
                estimator.accept(TRUTH_NANOS + (long) (random.nextDouble() * FRAME_NOISE_CEILING_NANOS));
            }

            int worst = 0;
            for (int i = 0; i < 200; i++) {
                estimator.accept(TRUTH_NANOS + (long) (random.nextDouble() * FRAME_NOISE_CEILING_NANOS));
                assertEquals(16, estimator.sampleCount(),
                        "稳态噪声不得清空窗口, seed=" + seed + " 第 " + i + " 个样本后");
                worst = Math.max(worst, estimator.estimateMillis());
            }
            // 真值 20ms + 噪声上界 16.7ms, 估计再差也超不过 37; 超过就说明被误判的跳变整体抬高了
            assertTrue(worst <= 37, "稳态估计不得被抬高到噪声上界之上, seed=" + seed + " 最差估计=" + worst);
        }
    }

    /**
     * 噪声残差随窗口增大而收敛 (期望 T/(N+1)) —— 这是"提高采样率即可提准"的依据,
     * 也是配置项 latency.window-samples 的调参含义。
     */
    @Test
    void largerWindowShrinksNoiseResidual() {
        int rounds = 50;
        double residualSmall = 0.0;
        double residualLarge = 0.0;
        for (int seed = 0; seed < rounds; seed++) {
            LatencyEstimator small = estimator(8);
            LatencyEstimator large = estimator(64);
            Random randomSmall = new Random(seed);
            Random randomLarge = new Random(seed);
            for (int i = 0; i < 8; i++) {
                small.accept(TRUTH_NANOS + (long) (randomSmall.nextDouble() * FRAME_NOISE_CEILING_NANOS));
            }
            for (int i = 0; i < 64; i++) {
                large.accept(TRUTH_NANOS + (long) (randomLarge.nextDouble() * FRAME_NOISE_CEILING_NANOS));
            }
            residualSmall += small.estimateNanos() - TRUTH_NANOS;
            residualLarge += large.estimateNanos() - TRUTH_NANOS;
        }
        residualSmall /= rounds;
        residualLarge /= rounds;
        assertTrue(residualLarge < residualSmall / 2.0,
                "64 样本窗口的残差应远小于 8 样本窗口, 实际 8=" + (long) residualSmall
                        + "ns 64=" + (long) residualLarge + "ns");
    }

    /**
     * 加严跳变判据后, 真正的大幅跳变仍必须被快速捕获 —— 否则等于把跳变检测废掉,
     * 一切上升都退化成"等窗口滑完"。
     */
    @Test
    void largeRiseIsStillDetectedUnderNoise() {
        Random random = new Random(20260819L);
        LatencyEstimator estimator = estimator(16);
        for (int i = 0; i < 32; i++) {
            estimator.accept(TRUTH_NANOS + (long) (random.nextDouble() * FRAME_NOISE_CEILING_NANOS));
        }

        long risen = ms(120);
        int samplesToFollow = 0;
        while (estimator.estimateMillis() < 100 && samplesToFollow <= 16) {
            estimator.accept(risen + (long) (random.nextDouble() * FRAME_NOISE_CEILING_NANOS));
            samplesToFollow++;
        }

        assertTrue(samplesToFollow <= 3,
                "20ms -> 120ms 的跳变应在 3 个样本内跟随, 实际用了 " + samplesToFollow + " 个");
    }

    /** 真值上移时, 连续两个高样本即清窗重建, 而不是干等旧的小样本滑出整个窗口。 */
    @Test
    void sustainedRiseResetsWindowAfterConfirmation() {
        LatencyEstimator estimator = estimator(16);
        feed(estimator, ms(20), 16);
        assertEquals(20, estimator.estimateMillis());

        estimator.accept(ms(100));
        assertEquals(20, estimator.estimateMillis(), "首个高样本只是候选, 不应立刻改变估计");

        estimator.accept(ms(100));
        assertEquals(100, estimator.estimateMillis(), "第二个高样本确认跳变, 应清窗后立即跟随");
        assertEquals(1, estimator.sampleCount(), "清窗后窗口内只剩这一个新样本");
    }

    /** 单次 GC 停顿或一个重传包不该把估计抬上去 —— 这正是要求"连续"确认的原因。 */
    @Test
    void isolatedOutlierLeavesEstimateUntouched() {
        LatencyEstimator estimator = estimator(16);
        feed(estimator, ms(20), 16);

        estimator.accept(ms(500));
        estimator.accept(ms(20));
        estimator.accept(ms(20));

        assertEquals(20, estimator.estimateMillis(), "孤立离群点不得改变估计");
        assertTrue(estimator.sampleCount() > 1, "未确认跳变时不应清窗, 实际样本数=" + estimator.sampleCount());
    }

    /**
     * 低延迟场景下 30% 的相对涨幅只有几毫秒, 若只看倍数会被正常抖动反复误判成跳变。
     * 绝对门槛 5ms 就是为此存在。
     */
    @Test
    void smallAbsoluteRiseIsNotTreatedAsJumpDespiteRatio() {
        LatencyEstimator estimator = estimator(16);
        feed(estimator, ms(10), 16);

        // 14 / 10 = 1.4 已超过 1.3 倍, 但绝对差 4ms 未过 5ms 门槛
        feed(estimator, ms(14), 5);

        assertEquals(10, estimator.estimateMillis(), "未过绝对门槛不得清窗, 估计应仍由窗口内的低样本决定");
    }

    /** min 对真值下降天然瞬时跟随, 不需要任何额外机制。 */
    @Test
    void dropIsTrackedImmediately() {
        LatencyEstimator estimator = estimator(16);
        feed(estimator, ms(100), 16);
        assertEquals(100, estimator.estimateMillis());

        estimator.accept(ms(10));
        assertEquals(10, estimator.estimateMillis(), "下降应在一个样本内完全跟随");
    }

    /** 缓慢上升 (每步都够不上跳变门槛) 时, 靠旧样本滑出窗口来跟随。 */
    @Test
    void gradualRiseIsTrackedByWindowSlide() {
        LatencyEstimator estimator = estimator(4);
        estimator.accept(ms(20));
        estimator.accept(ms(22));
        estimator.accept(ms(24));
        estimator.accept(ms(26));
        assertEquals(20, estimator.estimateMillis(), "窗口未滑完时仍由最旧的小样本决定");

        feed(estimator, ms(26), 4);
        assertEquals(26, estimator.estimateMillis(), "旧样本全部滑出后估计应升到新水平");
    }

    /** 容量 1 的退化窗口: 每个样本都是当前估计。 */
    @Test
    void singleSampleWindowTracksEverySample() {
        LatencyEstimator estimator = estimator(1);
        estimator.accept(ms(42));
        assertEquals(42, estimator.estimateMillis());
        estimator.accept(ms(7));
        assertEquals(7, estimator.estimateMillis());
    }

    /** 无样本时必须能被调用方识别, 而不是返回 0 被当成"延迟极低"渲染出去。 */
    @Test
    void missingEstimateIsExplicitRatherThanZero() {
        LatencyEstimator estimator = estimator(16);
        assertFalse(estimator.hasEstimate());
        assertEquals(LatencyEstimator.NO_ESTIMATE, estimator.estimateMillis());
        assertThrows(IllegalStateException.class, estimator::estimateNanos);
    }

    @Test
    void invalidSamplesAndParametersAreRejected() {
        LatencyEstimator estimator = estimator(16);
        assertThrows(IllegalArgumentException.class, () -> estimator.accept(0L));
        assertThrows(IllegalArgumentException.class, () -> estimator.accept(-1L));

        assertThrows(IllegalArgumentException.class,
                () -> new LatencyEstimator(0, JUMP_RATIO, JUMP_FLOOR_NANOS, JUMP_CONFIRMATIONS));
        assertThrows(IllegalArgumentException.class,
                () -> new LatencyEstimator(16, 1.0, JUMP_FLOOR_NANOS, JUMP_CONFIRMATIONS));
        assertThrows(IllegalArgumentException.class,
                () -> new LatencyEstimator(16, JUMP_RATIO, JUMP_FLOOR_NANOS, 0));
    }

    /** 四舍五入而非截断: 1.6ms 显示成 1ms 会让低延迟段的量化误差单向偏低。 */
    @Test
    void millisecondConversionRoundsHalfUp() {
        LatencyEstimator estimator = estimator(1);
        estimator.accept(ms(1.4));
        assertEquals(1, estimator.estimateMillis());
        estimator.accept(ms(1.6));
        assertEquals(2, estimator.estimateMillis());
    }
}
