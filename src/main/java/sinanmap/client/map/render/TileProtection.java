package sinanmap.client.map.render;

/**
 * "不许被淘汰"的页范围：淘汰任何一页之前都要先问它。
 *
 * <p>两份各自记着——全屏地图一屏要铺的那些页，以及小地图那一屏要用的页。<b>不能并成一份</b>：
 * 小地图是常驻 HUD，全屏地图打开时两者<b>同一帧都在渲染</b>、各自报一次范围；共用字段的话后报的会顶掉
 * 前者的范围，另一边的页就变成"可以淘汰"（一屏几十页被自己挤掉，或小地图那几页每帧淘汰重建）。
 * 命中任意一份就算保住。
 *
 * <p>层按<b>归档层</b>记（见 {@code WorldTilePages#keyLayer}）：两个界面看的是同一批页，层号必须一致，
 * 否则会漏判。而且<b>只保护当前显示的那一层</b>——层不进判定的话，刚走过的几个归档档各有一整屏页被保住，
 * 页数会随走过的档数一直涨，而它们正是最该被淘汰的那批。
 */
final class TileProtection {

	/** 全屏地图那一份（层、以及页坐标的闭区间）。 */
	private int layer = Integer.MAX_VALUE;
	private int minX = Integer.MAX_VALUE;
	private int minZ = Integer.MAX_VALUE;
	private int maxX = Integer.MIN_VALUE;
	private int maxZ = Integer.MIN_VALUE;

	/** 小地图那一份。 */
	private int minimapLayer = Integer.MAX_VALUE;
	private int minimapMinX = Integer.MAX_VALUE;
	private int minimapMinZ = Integer.MAX_VALUE;
	private int minimapMaxX = Integer.MIN_VALUE;
	private int minimapMaxZ = Integer.MIN_VALUE;

	/** 更新全屏地图那一份。值没变就直接返回——每帧都会被调一次。 */
	void set(int layer, int minX, int minZ, int maxX, int maxZ) {
		if (layer == this.layer && minX == this.minX && minZ == this.minZ
				&& maxX == this.maxX && maxZ == this.maxZ) {
			return;
		}
		this.layer = layer;
		this.minX = minX;
		this.minZ = minZ;
		this.maxX = maxX;
		this.maxZ = maxZ;
	}

	/** 更新小地图那一份。 */
	void setMinimap(int layer, int minX, int minZ, int maxX, int maxZ) {
		this.minimapLayer = layer;
		this.minimapMinX = minX;
		this.minimapMinZ = minZ;
		this.minimapMaxX = maxX;
		this.minimapMaxZ = maxZ;
	}

	/** 叶页判据：页坐标 {@code (layer, pageX, pageZ)} 在不在范围里。 */
	boolean containsLeaf(int layer, int pageX, int pageZ) {
		if (layer == this.layer
				&& pageX >= minX && pageX <= maxX
				&& pageZ >= minZ && pageZ <= maxZ) {
			return true;
		}
		return layer == minimapLayer
				&& pageX >= minimapMinX && pageX <= minimapMaxX
				&& pageZ >= minimapMinZ && pageZ <= minimapMaxZ;
	}

	/**
	 * 粗页判据：范围按<b>叶页格位</b>记，而粗页覆盖 {@code COARSE_PACK}² 个格位，所以要看"两个矩形有没有
	 * 重叠"——直接拿粗页坐标去比会漏判，正在看的那张粗页就被自己挤掉、显存回收了再重读。
	 */
	boolean overlapsCoarse(int layer, int coarsePageX, int coarsePageZ) {
		int lowX = coarsePageX * WorldTilePages.COARSE_PACK;
		int lowZ = coarsePageZ * WorldTilePages.COARSE_PACK;
		int highX = lowX + WorldTilePages.COARSE_PACK - 1;
		int highZ = lowZ + WorldTilePages.COARSE_PACK - 1;
		if (layer == this.layer
				&& lowX <= maxX && highX >= minX
				&& lowZ <= maxZ && highZ >= minZ) {
			return true;
		}
		return layer == minimapLayer
				&& lowX <= minimapMaxX && highX >= minimapMinX
				&& lowZ <= minimapMaxZ && highZ >= minimapMinZ;
	}
}
