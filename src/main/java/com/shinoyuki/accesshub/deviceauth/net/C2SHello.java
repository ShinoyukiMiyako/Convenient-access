package com.shinoyuki.accesshub.deviceauth.net;

import java.util.function.Supplier;

import com.shinoyuki.accesshub.deviceauth.DeviceAuthServer;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * 客户端 -> 服务端: 进入 PLAY 且本地玩家已创建后的主动宣告 (无载荷, 存在本身即信息)。
 *
 * 为什么需要它: 服务端原本只在 PlayerLoggedInEvent 那一刻用 isRemotePresent 判客户端有没有本 mod,
 * 而 Connector/兼容层下 PLAY 通道协商可能尚未完成 -> 判成"没装"便再也不发挑战, 线上表现为免密整局不触发。
 * 改由客户端反向宣告后, 时序完全由客户端自己保证 (它一定在本地玩家就绪之后才发), 不依赖服务端某一时刻的
 * 通道视图。服务端收到即确证对端装了本 mod 且能处理挑战。
 *
 * 本包不能承载进服版本门控：它在 PLAY 阶段才发送，晚于 PlayerNegotiationEvent。整合包版本改由
 * PackVersionChannel 在 Forge LOGIN 握手的通道版本表中携带，避免玩家进入世界后才发现版本不符。
 *
 * 包 id 追加在既有两个包之后, 老客户端不会发也不会收本包; 反过来新客户端连老服务端时, 老服务端只会
 * 记一条 "invalid discriminator" 并丢弃, 不影响密码登录。
 */
public final class C2SHello {

    /** 无字段, 单例即可, 避免每次登录多造一个对象。 */
    public static final C2SHello INSTANCE = new C2SHello();

    private C2SHello() {}

    public void encode(FriendlyByteBuf buf) {
        // 无载荷: Forge 仍会写入包 id 判别字节, 收到即代表客户端就绪
    }

    public static C2SHello decode(FriendlyByteBuf buf) {
        return INSTANCE;
    }

    public static void handle(C2SHello msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender(); // 权威身份, 零信任客户端自报
            if (sender == null) {
                return;
            }
            DeviceAuthServer server = AuthChannel.server();
            if (server == null) {
                return; // 未就绪 (内置服务器 / mod 启动失败) -> 忽略
            }
            server.onClientHello(sender);
        });
        ctx.setPacketHandled(true);
    }
}
