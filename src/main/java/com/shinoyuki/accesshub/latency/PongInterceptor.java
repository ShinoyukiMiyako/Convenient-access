package com.shinoyuki.accesshub.latency;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.network.protocol.game.ServerboundPongPacket;

/**
 * 在玩家连接的 Netty pipeline 里截取 {@link ServerboundPongPacket} 并打上到达时间戳。
 *
 * <p>插在 {@code "packet_handler"} (即 {@code Connection} 本身) 之前, 于是打点发生在网络线程、
 * 且在包被分发给 {@code ServerGamePacketListenerImpl} 之前, 不含任何服务端 tick 排队时间。
 *
 * <p>一律 {@code fireChannelRead} 放行: 原版 {@code handlePong} 是空实现, 拦下来没有收益,
 * 放行则保证别的 mod 若也监听 pong 不会因为本 mod 而收不到。
 */
final class PongInterceptor extends ChannelInboundHandlerAdapter {

    static final String HANDLER_NAME = "accesshub_latency_probe";

    private final PlayerLatencyProbe probe;

    PongInterceptor(PlayerLatencyProbe probe) {
        this.probe = probe;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ServerboundPongPacket pong && ProbeId.isProbe(pong.getId())) {
            probe.onPong(pong.getId(), System.nanoTime());
        }
        ctx.fireChannelRead(msg);
    }
}
