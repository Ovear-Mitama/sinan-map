package sinanmap.common.storage;

/**
 * 由世界推导出地图数据的目录名。
 *
 * <p><b>数据一律放在游戏目录下的 {@code sinanmap/} 里</b>（见 {@code MapStorage#currentRoot}），
 * 不放进存档：存档是要拿去分享、备份、迁移的，地图数据（走到过哪里、地形长什么样）和路径点属于
 * 个人痕迹，跟着存档走等于"把存档发给别人也把自己的地图发过去了"。
 *
 * <p>单人按<b>存档目录名</b>分：同一台机器上存档目录名唯一，而世界显示名可以改、也不保证唯一，
 * 拿它分目录会把两份数据混到一起。
 */
public final class WorldKeys {

	/** 目录名长度上限。 */
	private static final int MAX_LENGTH = 64;

	/** 无法推导时的兜底名字。 */
	public static final String FALLBACK = "unknown";

	private WorldKeys() {
	}

	/** 多人服务器：用服务器地址。 */
	public static String multiplayer(String address) {
		return "mp_" + sanitize(address);
	}

	/** 单人存档：用存档目录名（{@code saves/} 下的那层目录名）。 */
	public static String singleplayer(String saveFolder) {
		return "sp_" + sanitize(saveFolder);
	}

	/** 去掉不能出现在目录名里的字符，并处理末尾的点与空格。 */
	static String sanitize(String raw) {
		if (raw == null) {
			return FALLBACK;
		}
		// Windows 不允许目录名以点或空格结尾（会被静默去掉），先在这里去掉
		String cleaned = raw.trim().replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("[. ]+$", "");
		if (cleaned.isEmpty()) {
			return FALLBACK;
		}
		return cleaned.length() > MAX_LENGTH ? cleaned.substring(0, MAX_LENGTH) : cleaned;
	}
}
