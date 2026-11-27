package sinanmap.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import net.minecraft.client.Minecraft;

import sinanmap.client.compat.waystones.WaystoneCompat;
import sinanmap.client.config.MinimapSettingsIO;
import sinanmap.client.map.data.ClientChunkColorProvider;
import sinanmap.client.map.MinimapHud;
import sinanmap.client.net.SinanClientNet;
import sinanmap.client.radar.EntityRadar;
import sinanmap.client.waypoint.WaypointHud;
import sinanmap.client.waypoint.WaypointManager;
import sinanmap.common.hud.MinimapSettings;

/**
 * 客户端功能装配。
 *
 * <p>小地图 HUD 与世界地图共用同一份地形颜色数据源，避免两个界面各自持有一份缓存、重复采样世界。
 */
public final class SinanClient {

	private SinanClient() {
	}

	/** 注册全部客户端功能。 */
	public static void register() {
		// 设置先建：地形数据源与 HUD 共用同一份，性能项改一处两边都生效
		MinimapSettings settings = new MinimapSettings();
		// 先把上次存的读进来，界面 / 渲染 / 按键拿到的才是同一份"上次调好的"设置
		MinimapSettingsIO.load(settings);
		// 改了设置要落盘：每秒比一次内容，变了才写（见 MinimapSettingsIO#tick）
		ClientTickEvents.END_CLIENT_TICK.register(client -> MinimapSettingsIO.tick(settings));
		ClientChunkColorProvider colorProvider =
				new ClientChunkColorProvider(Minecraft.getInstance(), settings);
		// 区块加载：取色用的群系缓存里可能存着"这块还没到、只能按平原算"的临时值，
		// 跟着原版 ClientLevel#onChunkLoaded 一起作废，加载范围边缘的颜色才会立刻对齐
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) ->
				colorProvider.invalidateBiomeTints(level, chunk.getPos().x(), chunk.getPos().z()));
		MinimapHud minimap = new MinimapHud(colorProvider, settings);
		// 游戏内（3D 世界里）的路径点标记先注册：它是"撒在世界里"的一层，小地图压在它上面更合理
		// （反过来注册的话，路径点会画到小地图的方块上）；它读小地图那份设置里的"HUD 距离/缩放"
		new WaypointHud(minimap.settings()).register();
		minimap.register();
		// 离屏生物头像的推进时机放在"世界画完、GUI 还没开始"那一刻（见 EntityRadar#registerPumpHook）：
		// 放在 HUD 里推进的话，HUD 还没画完的几何会被画进头像那张离屏画布，表现为"每个头像都有杂色"
		EntityRadar.registerPumpHook();
		SinanKeyMappings.register(colorProvider, minimap);
		// 服务端的可选增强：世界标识、其他玩家坐标。服务端没装时这里只是空转
		SinanClientNet.register(settings);
		// 可选兼容：装了「传送石碑」就把它的石碑同步成路径点（没装时这一层是空操作）
		WaystoneCompat.register(settings);
		// 设置里那个兼容开关改了要立刻生效；没有石碑数据时它只是一次布尔比较
		ClientTickEvents.END_CLIENT_TICK.register(client -> WaystoneCompat.tick());

		// 退出世界就把地图数据关掉。数据在存档目录里，文件句柄不关的话整个存档都删不掉（Windows 上会直接报删除失败）；
		// 退出到标题界面后地图不再渲染，所以不能指望"下次打开地图时顺带换一份"那条路
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			colorProvider.closeStorage();
			// 路径点也趁这个时候写完：它按需落盘，退出前可能还有没写下去的改动
			WaypointManager.instance().flush(client);
			// 石碑清单是刚才那个服务器给的，退出就作废（已落盘的路径点留着）
			WaystoneCompat.onDisconnect();
			// 设置补写一次：刚才那一两秒内的改动可能还没等到"写盘检查"那一帧
			MinimapSettingsIO.flush(settings);
		});
		// 退出游戏也补写一次：写盘是每秒轮询的，直接在改完就关游戏会丢掉最后一次改动
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> MinimapSettingsIO.flush(settings));
	}
}
