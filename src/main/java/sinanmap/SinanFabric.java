package sinanmap;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sinanmap.client.SinanClient;
import sinanmap.common.SinanConstants;
import sinanmap.common.SinanCore;
import sinanmap.common.net.HelloC2S;
import sinanmap.common.net.TrackedPlayersS2C;
import sinanmap.common.net.WorldInfoS2C;
import sinanmap.server.SinanServer;

/**
 * Fabric 入口：<b>双端都加载</b>。
 *
 * <p>{@code fabric.mod.json} 的 {@code environment} 是 {@code *}——服务端的两个可选增强
 * （世界标识、玩家位置同步）需要专用服务器也装上；不装照样能玩，客户端会自动降级成
 * 纯本地模式。因此这里的 {@link #onInitialize()} 里<b>一个客户端类都不能碰</b>，
 * 客户端的东西全归 {@link #onInitializeClient()}。
 */
public final class SinanFabric implements ModInitializer, ClientModInitializer {

	public static final Logger LOGGER = LoggerFactory.getLogger(SinanConstants.MOD_ID);

	@Override
	public void onInitialize() {
		// 包类型必须在两端都注册：只注册一侧的话，另一侧收到包会因为"未知类型"直接断连
		PayloadTypeRegistry.serverboundPlay().register(HelloC2S.TYPE, HelloC2S.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(WorldInfoS2C.TYPE, WorldInfoS2C.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TrackedPlayersS2C.TYPE, TrackedPlayersS2C.STREAM_CODEC);

		ServerPlayNetworking.registerGlobalReceiver(HelloC2S.TYPE,
				(payload, context) -> SinanServer.onHello(context.player(), payload));
		ServerPlayConnectionEvents.DISCONNECT.register(
				(handler, server) -> SinanServer.onDisconnect(handler.getPlayer()));
		ServerTickEvents.END_SERVER_TICK.register(SinanServer::tick);
	}

	@Override
	public void onInitializeClient() {
		LOGGER.info(SinanCore.banner("Fabric"));
		SinanClient.register();
	}
}
