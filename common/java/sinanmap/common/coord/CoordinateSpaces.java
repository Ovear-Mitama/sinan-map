package sinanmap.common.coord;

import sinanmap.common.SinanConstants;

/**
 * 方块 / 区块 / 区域坐标系之间的整数换算。
 *
 * <p>全部为纯函数，负坐标一律向负无穷方向取整（与 Minecraft 自身的区块划分一致）。地图数据的索引、
 * 缓存键与存储路径都建立在这些换算之上，因此这里是单元测试的重点。
 *
 * <p><b>方块 ↔ 区块的换算用位移与掩码，不用 {@code floorDiv}/{@code floorMod}。</b>区块边长是 2 的幂，
 * 两者对正负数完全等价；而取整除法是一次真正的整数除法（二十几个周期、还不能流水），小地图逐像素采样时
 * 每个像素要算十几次——实测换成位移后一次合成从十几毫秒降到几毫秒。
 */
public final class CoordinateSpaces {

	/** 区块边长的位移量与掩码，由 {@link SinanConstants#CHUNK_SIZE} 推出（必须是 2 的幂）。 */
	private static final int CHUNK_SHIFT = Integer.numberOfTrailingZeros(SinanConstants.CHUNK_SIZE);
	private static final int CHUNK_MASK = SinanConstants.CHUNK_SIZE - 1;

	private CoordinateSpaces() {
	}

	/** 方块坐标 → 所在区块坐标。 */
	public static int blockToChunk(int block) {
		return block >> CHUNK_SHIFT;
	}

	/** 区块坐标 → 该区块起始方块的坐标。 */
	public static int chunkToBlock(int chunk) {
		return Math.multiplyExact(chunk, SinanConstants.CHUNK_SIZE);
	}

	/** 区块坐标 → 所在区域坐标。 */
	public static int chunkToRegion(int chunk) {
		return Math.floorDiv(chunk, SinanConstants.REGION_CHUNKS);
	}

	/** 区域坐标 → 该区域起始区块的坐标。 */
	public static int regionToChunk(int region) {
		return Math.multiplyExact(region, SinanConstants.REGION_CHUNKS);
	}

	/** 方块坐标 → 所在区域坐标。 */
	public static int blockToRegion(int block) {
		return Math.floorDiv(block, SinanConstants.REGION_BLOCKS);
	}

	/** 区域坐标 → 该区域起始方块的坐标。 */
	public static int regionToBlock(int region) {
		return Math.multiplyExact(region, SinanConstants.REGION_BLOCKS);
	}

	/** 方块坐标在其所属区块内的偏移，取值 0..15。 */
	public static int blockOffsetInChunk(int block) {
		return block & CHUNK_MASK;
	}

	/** 区域文件覆盖的方块边长。 */
	public static int regionSpanBlocks() {
		return SinanConstants.REGION_BLOCKS;
	}

	/** 生成区域键，例如 {@code r.-1.2}，用于区域文件名与缓存键。 */
	public static String regionKey(int regionX, int regionZ) {
		return "r." + regionX + "." + regionZ;
	}
}
