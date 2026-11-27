package sinanmap.common.map;

/**
 * 以中心为起点、逐圈向外的栅格遍历。
 *
 * <p>地图补数据时用它决定先补哪一块：从屏幕中心（玩家所在处）开始一圈圈往外扩，
 * 玩家先看到自己附近的地形填好，而不是眼睁睁看着地图从上往下刷。
 *
 * <p>遍历的是"格子"坐标，格子的实际尺寸（多少个贴图像素）由调用方决定。每个格子恰好访问一次，
 * 超出 {@code columns × rows} 范围的坐标由调用方自行忽略。
 */
public final class CenterOutSweep {

	private final int columns;
	private final int rows;
	private final int centerX;
	private final int centerY;
	private final int maxRing;

	private int ring;
	private int cursor;
	private int ringLength;

	/**
	 * @param columns 横向格子数，必须为正
	 * @param rows    纵向格子数，必须为正
	 */
	public CenterOutSweep(int columns, int rows) {
		this(columns, rows, columns / 2, rows / 2);
	}

	/**
	 * @param columns 横向格子数，必须为正
	 * @param rows    纵向格子数，必须为正
	 * @param centerX 起点格子的横向坐标
	 * @param centerY 起点格子的纵向坐标
	 */
	public CenterOutSweep(int columns, int rows, int centerX, int centerY) {
		if (columns <= 0 || rows <= 0) {
			throw new IllegalArgumentException("格子数必须为正，当前为 " + columns + "x" + rows);
		}
		this.columns = columns;
		this.rows = rows;
		this.centerX = centerX;
		this.centerY = centerY;
		// 最远的一个角到中心的圈数
		this.maxRing = Math.max(Math.max(Math.abs(centerX), Math.abs(columns - 1 - centerX)),
				Math.max(Math.abs(centerY), Math.abs(rows - 1 - centerY)));
	}

	/** 是否还有格子没走完。 */
	public boolean hasNext() {
		return ring <= maxRing;
	}

	/** 当前格子的横向坐标，可能落在 {@code [0, columns)} 之外，调用方需要自行跳过。 */
	public int x() {
		return centerX + offsetX();
	}

	/** 当前格子的纵向坐标，可能落在 {@code [0, rows)} 之外，调用方需要自行跳过。 */
	public int y() {
		return centerY + offsetY();
	}

	/** 当前格子是否落在有效范围内。 */
	public boolean inRange() {
		int x = x();
		int y = y();
		return x >= 0 && x < columns && y >= 0 && y < rows;
	}

	/** 前进一格；走完一圈就换到下一圈。 */
	public void advance() {
		cursor++;
		if (cursor >= ringLength) {
			ring++;
			cursor = 0;
			ringLength = Math.max(1, 8 * ring);
		}
	}

	/** 回到中心重新开始。 */
	public void reset() {
		ring = 0;
		cursor = 0;
		ringLength = 1;
	}

	/** 当前圈上第 {@code cursor} 个格子的横向偏移（相对中心）。 */
	private int offsetX() {
		if (ring == 0) {
			return 0;
		}
		if (cursor < 2 * ring + 1) {
			// 上边：从左到右
			return -ring + cursor;
		}
		if (cursor < 4 * ring + 1) {
			// 右边：从上到下
			return ring;
		}
		if (cursor < 6 * ring + 1) {
			// 下边：从右到左（右边的两个角已经走过）
			return ring - 1 - (cursor - (4 * ring + 1));
		}
		// 左边：从下到上
		return -ring;
	}

	/** 当前圈上第 {@code cursor} 个格子的纵向偏移（相对中心）。 */
	private int offsetY() {
		if (ring == 0) {
			return 0;
		}
		if (cursor < 2 * ring + 1) {
			return -ring;
		}
		if (cursor < 4 * ring + 1) {
			// 右边：从上到下（上边的两个角已经走过，所以从 -ring+1 开始）
			return -ring + 1 + (cursor - (2 * ring + 1));
		}
		if (cursor < 6 * ring + 1) {
			return ring;
		}
		return ring - (cursor - (6 * ring + 1)) - 1;
	}
}
