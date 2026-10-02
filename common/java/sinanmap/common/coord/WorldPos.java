package sinanmap.common.coord;

/**
 * 完整的路径点位置：所在维度 + 三维坐标。
 *
 * @param dimension 维度 ID，例如 {@code minecraft:overworld}
 * @param x         X 轴坐标
 * @param y         Y 轴坐标
 * @param z         Z 轴坐标
 */
public record WorldPos(String dimension, double x, double y, double z) {

	public WorldPos {
		if (dimension == null || dimension.isBlank()) {
			throw new IllegalArgumentException("维度 ID 不能为空");
		}
	}

	/** 创建水平方向精确到方块的路径点位置。 */
	public static WorldPos of(String dimension, int x, int y, int z) {
		return new WorldPos(dimension, x, y, z);
	}

	/** 向下取整的方块 X 坐标。 */
	public int blockX() {
		return (int) Math.floor(x);
	}

	/** 向下取整的方块 Y 坐标。 */
	public int blockY() {
		return (int) Math.floor(y);
	}

	/** 向下取整的方块 Z 坐标。 */
	public int blockZ() {
		return (int) Math.floor(z);
	}

	/** 所在区块的 X 坐标。 */
	public int chunkX() {
		return CoordinateSpaces.blockToChunk(blockX());
	}

	/** 所在区块的 Z 坐标。 */
	public int chunkZ() {
		return CoordinateSpaces.blockToChunk(blockZ());
	}

	/** 用于界面与聊天框显示的坐标文本。 */
	public String describe() {
		return "X=%d Y=%d Z=%d".formatted(blockX(), blockY(), blockZ());
	}
}
