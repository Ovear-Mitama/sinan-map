package sinanmap.common.net;

import net.minecraft.resources.Identifier;

import sinanmap.common.SinanConstants;

/**
 * 司南的网络协议：通道 id 与协议版本的统一出处。
 *
 * <p><b>协议版本对不上就当作"对方没有这个功能"</b>，不做兼容处理。双端都是同一版模组时才握手成功；
 * 版本不一致时静静降级回纯客户端模式——绝不因为一个可选功能把游戏搞崩。
 */
public final class SinanNet {

	/** 当前协议版本。改动任何一个包的结构（含删包）都要把它加一。 */
	public static final int PROTOCOL_VERSION = 2;

	private SinanNet() {
	}

	/** 造一个本模组的通道 id。 */
	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(SinanConstants.MOD_ID, path);
	}
}
