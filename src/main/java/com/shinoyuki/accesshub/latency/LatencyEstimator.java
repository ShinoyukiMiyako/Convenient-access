package com.shinoyuki.accesshub.latency;

/**
 * 链路 RTT 估计器: 滑动窗口取最小值 + 跳变重置。
 *
 * <p>取 min 而不是均值 / EWMA, 是因为 RTT 的测量噪声【单边正向】—— 排队、线程调度、重传只会让样本偏大,
 * 不存在让样本偏小的机制。窗口内最小值因此是真实链路 RTT 的最优估计: N 个样本对均匀分布 [0,T] 的
 * 噪声残差期望是 T/(N+1), 靠提高采样率即可快速收敛, 无需像原版 keep-alive 的 α=0.25 EWMA
 * 那样用 8 个采样周期换取平滑 (在 15 秒采样率下等于 120 秒才到 90% 准确度)。
 *
 * <p>min 对"真值下降"天然瞬时跟随; 对"真值上升"则要等旧的小样本滑出窗口。故额外做跳变检测:
 * 连续若干样本都显著高于当前估计时直接清窗重建, 把上升跟随从一个完整窗口压到几个采样周期。
 * 要求"连续"而非单次即触发, 是为了不让一次 GC 停顿或一个重传包把估计整体抬上去。
 *
 * <p>跳变判据除了倍数与绝对门槛, 还要求新样本【超过窗口内见过的最大值】。这一条不可省:
 * 噪声幅度本身就可能大于 RTT (原版客户端回 pong 要等下一帧, 60fps 下就是 16.7ms 的抖动,
 * 而链路 RTT 可能只有 10ms), 只按"高于当前 min 若干倍"判定的话, 连着两个高噪声样本就会清窗,
 * 估计随即被抬到噪声峰值附近 —— 取 min 抗噪的前提被自己推翻。以窗口最大值为准则后,
 * 噪声再大也只是重现历史极值, 只有真值真的上移到超过既有噪声范围才会触发。
 * 代价是涨幅小于噪声幅度的真值上移不再快速跟随, 由窗口滑出兜底。
 *
 * <p>本类不做任何同步: 实例被单个 Netty eventLoop 线程独占访问 (见 {@link PlayerLatencyProbe})。
 */
final class LatencyEstimator {

    /** 尚无样本时 {@link #estimateMillis()} 的返回值, 调用方据此显示"未知"而不是伪造一个 0。 */
    static final int NO_ESTIMATE = -1;

    private final long[] window;
    private final double jumpRatio;
    private final long jumpFloorNanos;
    private final int jumpConfirmations;

    private int size;
    private int cursor;
    private int consecutiveHighSamples;
    private long jumpBaselineMin;
    private long jumpBaselineMax;

    /**
     * @param windowSamples     窗口容量, 决定上升跟随的最坏延迟与噪声抑制强度
     * @param jumpRatio         新样本高于当前估计多少倍才算跳变候选 (如 1.3)
     * @param jumpFloorNanos    倍数之外的绝对门槛, 防止低延迟下 30% 只有零点几毫秒时被抖动反复触发
     * @param jumpConfirmations 连续多少个跳变候选才真正清窗
     */
    LatencyEstimator(int windowSamples, double jumpRatio, long jumpFloorNanos, int jumpConfirmations) {
        if (windowSamples < 1) {
            throw new IllegalArgumentException("窗口容量必须为正: " + windowSamples);
        }
        if (jumpRatio <= 1.0) {
            throw new IllegalArgumentException("跳变倍数必须大于 1: " + jumpRatio);
        }
        if (jumpConfirmations < 1) {
            throw new IllegalArgumentException("跳变确认次数必须为正: " + jumpConfirmations);
        }
        this.window = new long[windowSamples];
        this.jumpRatio = jumpRatio;
        this.jumpFloorNanos = jumpFloorNanos;
        this.jumpConfirmations = jumpConfirmations;
    }

    void accept(long rttNanos) {
        if (rttNanos <= 0L) {
            throw new IllegalArgumentException("RTT 必须为正: " + rttNanos);
        }
        // 窗口未满时不做跳变判定: 此时 max 还没见过完整的噪声范围, 拿它当基线会把正常抖动判成跳变。
        // 窗口未满意味着刚开始测量或刚清过窗, 本就没有可信基线, 而 min 对下降的跟随不受影响。
        if (size == window.length) {
            if (consecutiveHighSamples == 0) {
                // 候选序列期间冻结基线: 候选样本自己也会进窗口并抬高 max,
                // 不冻结的话第二个同水平样本就够不上门槛, 跳变永远确认不了。
                jumpBaselineMin = estimateNanos();
                jumpBaselineMax = maxNanos();
            }
            if (isJumpCandidate(rttNanos)) {
                if (++consecutiveHighSamples >= jumpConfirmations) {
                    clear();
                }
            } else {
                consecutiveHighSamples = 0;
            }
        } else {
            consecutiveHighSamples = 0;
        }
        window[cursor] = rttNanos;
        cursor = (cursor + 1) % window.length;
        if (size < window.length) {
            size++;
        }
    }

    boolean hasEstimate() {
        return size > 0;
    }

    /**
     * @throws IllegalStateException 尚无样本时调用。估计值缺失是调用方必须显式处理的状态,
     *                               返回 0 会被当成"延迟极低"渲染出去, 属于用空值掩盖问题。
     */
    long estimateNanos() {
        if (size == 0) {
            throw new IllegalStateException("尚无 RTT 样本, 调用前应先判 hasEstimate()");
        }
        long min = Long.MAX_VALUE;
        for (int i = 0; i < size; i++) {
            if (window[i] < min) {
                min = window[i];
            }
        }
        return min;
    }

    /** 四舍五入到毫秒; 无样本时返回 {@link #NO_ESTIMATE}。 */
    int estimateMillis() {
        if (size == 0) {
            return NO_ESTIMATE;
        }
        return (int) ((estimateNanos() + 500_000L) / 1_000_000L);
    }

    int sampleCount() {
        return size;
    }

    private boolean isJumpCandidate(long rttNanos) {
        if (rttNanos <= (long) (jumpBaselineMin * jumpRatio)
                || rttNanos - jumpBaselineMin <= jumpFloorNanos) {
            return false;
        }
        // 噪声幅度用窗口的 (max - min) 自适应估计, 要求新样本再高出这一整个幅度才算真值上移。
        // 只跟 max 比是不够的: max 只是有限样本的极值, 后续噪声超过它并不罕见 (16 样本下约 1/17),
        // 连续两次就足以误清窗。多留一个幅度的余量, 稳态噪声便再也够不着门槛。
        return rttNanos > 2 * jumpBaselineMax - jumpBaselineMin;
    }

    private long maxNanos() {
        long max = Long.MIN_VALUE;
        for (int i = 0; i < size; i++) {
            if (window[i] > max) {
                max = window[i];
            }
        }
        return max;
    }

    private void clear() {
        size = 0;
        cursor = 0;
        consecutiveHighSamples = 0;
    }
}
