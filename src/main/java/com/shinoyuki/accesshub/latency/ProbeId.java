package com.shinoyuki.accesshub.latency;

/**
 * 探测包 id 的编码。
 *
 * <p>{@code ClientboundPingPacket} 的 id 是全服共享的 int 空间, 别的 mod / 插件也可能拿它做自己的事。
 * 高 8 位打上魔数, 使拦截器能只认自己发出去的 pong, 既不误把别人的回包算成延迟样本,
 * 也不会因为别人先发了 ping 而污染我们的 pending 表。
 */
final class ProbeId {

    private static final int MAGIC = 0xAC;
    private static final int SEQUENCE_MASK = 0x00FF_FFFF;

    private ProbeId() {
    }

    static int of(int sequence) {
        return (MAGIC << 24) | (sequence & SEQUENCE_MASK);
    }

    static boolean isProbe(int id) {
        return (id >>> 24) == MAGIC;
    }
}
