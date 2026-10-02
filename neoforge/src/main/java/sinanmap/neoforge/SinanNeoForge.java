package sinanmap.neoforge;

import net.neoforged.fml.common.Mod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sinanmap.common.SinanConstants;
import sinanmap.common.SinanCore;

/**
 * NeoForge 入口。
 *
 * <p>依赖声明的 side 为 {@code CLIENT}，服务端无需安装；入口本身只做与加载器无关的初始化，
 * 客户端专属内容在后续里程碑中按需注册。
 */
@Mod(SinanConstants.MOD_ID)
public final class SinanNeoForge {

	private static final Logger LOGGER = LoggerFactory.getLogger(SinanConstants.MOD_ID);

	public SinanNeoForge() {
		LOGGER.info(SinanCore.banner("NeoForge"));
	}
}
