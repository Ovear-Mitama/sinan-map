package sinanmap.client.compat.waystones;

import net.fabricmc.loader.api.FabricLoader;

import sinanmap.common.hud.MinimapSettings;

/**
 * 「传送石碑（Waystones）」兼容的总入口：装了才去接它。
 *
 * <p><b>为什么要多这一层</b>：{@link WaystoneIntegration} 引用了 Waystones 的类与方法签名，一旦被加载，
 * 对方没装时就会直接抛 {@code NoClassDefFoundError}。所以这里只做"装没装"的判断，确认装了才碰那个类。
 *
 * <p>对应的编译期依赖在 {@code build.gradle} 里（{@code compileOnly}，不打包进产物）。
 */
public final class WaystoneCompat {

	/** 传送石碑的 mod id。 */
	private static final String MOD_ID = "waystones";

	private WaystoneCompat() {
	}

	/** 注册兼容（客户端装配时调一次）。 */
	public static void register(MinimapSettings settings) {
		if (isPresent()) {
			WaystoneIntegration.register(settings);
		}
	}

	/** 每帧一次的空转检查：设置开关改了要立刻生效（详见 {@link WaystoneIntegration#tick()}）。 */
	public static void tick() {
		if (isPresent()) {
			WaystoneIntegration.tick();
		}
	}

	/**
	 * 断开连接：把服务器给的石碑清单清掉。<b>不动已落盘的路径点</b>——清单只对刚才那个服务器有效，
	 * 但路径点是玩家自己的地图数据，下次进同一个世界还会再收一遍。
	 */
	public static void onDisconnect() {
		if (isPresent()) {
			WaystoneIntegration.clearCache();
		}
	}

	private static boolean isPresent() {
		return FabricLoader.getInstance().isModLoaded(MOD_ID);
	}
}
