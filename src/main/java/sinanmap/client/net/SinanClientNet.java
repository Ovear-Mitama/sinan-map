package sinanmap.client.net;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;

import sinanmap.client.map.data.ServerWorldIndex;
import sinanmap.client.radar.TrackedPlayers;
import sinanmap.common.hud.MinimapSettings;
import sinanmap.common.net.HelloC2S;
import sinanmap.common.net.SinanNet;
import sinanmap.common.net.TrackedPlayersS2C;
import sinanmap.common.net.WorldInfoS2C;

/**
 * 客户端侧的司南网络：握手、收世界标识、收其他玩家坐标。
 *
 * <p><b>连上就报到一次</b>（{@link HelloC2S}），把协议版本与"愿不愿意共享位置"一并告诉服务端；
 * 设置里那个开关一改就重发一次。服务端没装司南时这几步全是空转——{@code canSend} 会拦下发送，
 * 收包那边则永远不会有消息进来，功能自动降级成纯本地模式。
 */
public final class SinanClientNet {

	private static MinimapSettings settings;

	/** 上一次告诉服务端的共享意愿；null = 这个连接还没报到过。 */
	private static Boolean lastShared;

	private SinanClientNet() {
	}

	/** 注册收包与连接事件（客户端装配时调一次）。 */
	public static void register(MinimapSettings settings) {
		SinanClientNet.settings = settings;

		ClientPlayNetworking.registerGlobalReceiver(WorldInfoS2C.TYPE, (payload, context) ->
				context.client().execute(() -> ServerWorldIndex.setWorldId(payload.worldId())));
		ClientPlayNetworking.registerGlobalReceiver(TrackedPlayersS2C.TYPE, (payload, context) ->
				context.client().execute(() -> TrackedPlayers.replace(payload.players())));

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			lastShared = null;
			sendHello();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			lastShared = null;
			// 这两份都是"刚才那个服务器的实时数据"，退出即作废；已落盘的路径点不在此列
			ServerWorldIndex.setWorldId(null);
			TrackedPlayers.clear();
		});
		// 设置里改了"共享位置"要立刻生效：没有服务端事件会来通知我们
		ClientTickEvents.END_CLIENT_TICK.register(client -> syncHello());
	}

	/** 每帧一次：只比"共享开关变没变"，变了才重发。 */
	private static void syncHello() {
		if (Minecraft.getInstance().getConnection() == null || lastShared == null) {
			return;
		}
		if (lastShared == settings.sharePlayerPosition()) {
			return;
		}
		sendHello();
	}

	private static void sendHello() {
		if (!ClientPlayNetworking.canSend(HelloC2S.TYPE)) {
			// 服务端没装司南（或还没握手完）：什么都不发，纯本地模式
			return;
		}
		boolean share = settings.sharePlayerPosition();
		lastShared = share;
		ClientPlayNetworking.send(new HelloC2S(SinanNet.PROTOCOL_VERSION, share));
	}
}
