package sinanmap.common;

/**
 * 双端共享的常量。
 *
 * <p>{@code common} 下的代码不依赖任何 Minecraft 或加载器类型，因此可被 Fabric 与 NeoForge
 * 两个独立工程同时编译。任何引用了 Minecraft 类型的代码都必须写在各自的工程目录内。
 */
public final class SinanConstants {

	/** 模组 ID，同时用作日志名与资源命名空间。 */
	public static final String MOD_ID = "sinanmap";

	/** 英文名。 */
	public static final String MOD_NAME = "Sinan Map";

	/** 中文名。 */
	public static final String MOD_NAME_CN = "司南地图";

	/** 一个区块的边长（方块）。 */
	public static final int CHUNK_SIZE = 16;

	/** 一个区域文件覆盖的区块数量（边长）。 */
	public static final int REGION_CHUNKS = 32;

	/** 一个区域文件覆盖的方块数量（边长）。 */
	public static final int REGION_BLOCKS = CHUNK_SIZE * REGION_CHUNKS;

	private SinanConstants() {
	}
}
