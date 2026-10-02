package sinanmap.common;

import sinanmap.common.coord.CoordinateSpaces;

/**
 * 双端共用的初始化逻辑：当前只提供启动横幅；注册、事件订阅与渲染由各自的入口类完成。
 */
public final class SinanCore {

	private SinanCore() {
	}

	/**
	 * 生成启动横幅。内容会用到共享层计算出的数值，便于在日志里确认共享代码确实被双端链接。
	 *
	 * @param platform 平台名，例如 {@code Fabric} 或 {@code NeoForge}
	 * @return 启动日志文本
	 */
	public static String banner(String platform) {
		return "%s（%s）已加载 | 平台=%s | 区域边长=%d 方块".formatted(
				SinanConstants.MOD_NAME,
				SinanConstants.MOD_NAME_CN,
				platform,
				CoordinateSpaces.regionSpanBlocks());
	}
}
