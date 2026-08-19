package com.shinoyuki.accesshub.latency;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 单个玩家的延迟探测状态: 在飞探测包的时间戳表 + RTT 估计器 + 探测节奏。
 *
 * <p>线程模型 (无锁, 靠职责划分保证安全):
 * <ul>
 *   <li>主线程 (server tick) 独占: {@link #allocateProbeId()}、{@link #tickDueForProbe}、
 *       {@link #publishedMillis()} 的读取;</li>
 *   <li>该连接的 Netty eventLoop 线程独占: {@link #markSent}、{@link #onPong}, 以及内部的
 *       pending 表与 estimator。两者天然串行 —— Netty 保证同一 channel 的 write future 回调与
 *       channelRead 都在这一个线程上顺序执行, 因此"先 markSent 后 onPong"的顺序不需要额外同步;</li>
 *   <li>跨线程只经 {@code volatile publishedMillis} 单值发布。</li>
 * </ul>
 */
final class PlayerLatencyProbe {

    /** 在飞探测包上限。丢包时旧槽被后续探测自然覆盖, 因此不需要额外的超时清理逻辑。 */
    private static final int PENDING_CAPACITY = 16;

    /** 空槽哨兵。探测 id 恒带 {@link ProbeId} 的高位魔数, 不可能为 0, 故 0 可安全用作空值。 */
    private static final int EMPTY_SLOT = 0;

    private final int[] pendingIds = new int[PENDING_CAPACITY];
    private final long[] pendingSentNanos = new long[PENDING_CAPACITY];
    private final LatencyEstimator estimator;

    private int pendingCursor;
    private int nextSequence;
    private int ticksUntilNextProbe = 1;
    private int burstRemaining;

    private volatile int publishedMillis = LatencyEstimator.NO_ESTIMATE;

    /** 诊断用: 收到过但配不上任何在飞包的 pong 数 (迟到、重放, 或对端乱回)。清理权归运维, 只增不减。 */
    private volatile int unmatchedPongs;

    PlayerLatencyProbe(LatencyEstimator estimator, int burstSamples) {
        this.estimator = estimator;
        this.burstRemaining = Math.max(0, burstSamples);
    }

    /** 主线程: 递减倒计时, 返回本 tick 是否该发探测包。 */
    boolean tickDueForProbe(int intervalTicks, int jitterTicks) {
        if (--ticksUntilNextProbe > 0) {
            return false;
        }
        if (burstRemaining > 0) {
            // 登录后的突发探测: 每 tick 一发, 让首个可信估计在几百毫秒内出来, 而不是等一个完整探测周期
            burstRemaining--;
            ticksUntilNextProbe = 1;
        } else {
            ticksUntilNextProbe = nextInterval(intervalTicks, jitterTicks);
        }
        return true;
    }

    /** 主线程: 取下一个探测包 id。 */
    int allocateProbeId() {
        return ProbeId.of(nextSequence++);
    }

    /** 网络线程: 探测包真正写出后记时间戳。在此处而非构造包时打点, 是为了排除 send 排队进 eventLoop 的耗时。 */
    void markSent(int id, long sentNanos) {
        pendingIds[pendingCursor] = id;
        pendingSentNanos[pendingCursor] = sentNanos;
        pendingCursor = (pendingCursor + 1) % PENDING_CAPACITY;
    }

    /** 网络线程: 收到 pong, 配对成功则产生一个 RTT 样本。 */
    void onPong(int id, long receivedNanos) {
        for (int i = 0; i < PENDING_CAPACITY; i++) {
            if (pendingIds[i] != id) {
                continue;
            }
            // 立即置空: 对端重发或恶意重放同一个 id 时, 第二次不应再产生样本
            pendingIds[i] = EMPTY_SLOT;
            long rtt = receivedNanos - pendingSentNanos[i];
            if (rtt <= 0L) {
                // nanoTime 单调且两个时间点同线程串行取, 走到这里说明线程模型假设已被打破。
                // 不抛异常是因为这里在 Netty 入站路径上, 抛出会触发 exceptionCaught 直接断掉玩家连接 ——
                // 拿玩家在线状态去换一个显示指标不划算, 故记为不可配对样本, 由计数器暴露。
                unmatchedPongs++;
                return;
            }
            estimator.accept(rtt);
            publishedMillis = estimator.estimateMillis();
            return;
        }
        unmatchedPongs++;
    }

    /** 主线程: 当前估计, 无样本时为 {@link LatencyEstimator#NO_ESTIMATE}。 */
    int publishedMillis() {
        return publishedMillis;
    }

    int unmatchedPongs() {
        return unmatchedPongs;
    }

    /**
     * 探测间隔加抖动。固定间隔会与客户端的帧周期形成相位锁定 —— 原版客户端的
     * {@code handlePing} 带 ensureRunningOnSameThread, 回包要等下一帧, 若两个周期锁相,
     * 每个样本吃到的帧内排队时间就恒定, 窗口取 min 也滤不掉这份系统性偏差。
     */
    private static int nextInterval(int intervalTicks, int jitterTicks) {
        int base = Math.max(1, intervalTicks);
        if (jitterTicks <= 0) {
            return base;
        }
        return Math.max(1, base + ThreadLocalRandom.current().nextInt(-jitterTicks, jitterTicks + 1));
    }
}
