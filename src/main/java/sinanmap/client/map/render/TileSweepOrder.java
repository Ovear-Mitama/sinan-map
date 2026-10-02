package sinanmap.client.map.render;

/**
 * 页内的推进顺序：一张表决定"先问哪一块"。
 *
 * <p><b>为什么集中在一个文件里。</b>以前叶页问新块、叶页补画、未探明重试、粗页填充各写过一套推进顺序，
 * 于是同一张地图上同时看得到"扫列""扫行""由内向外"三种走向。
 * 现在全在这里，并且<b>页内只有一种走向</b>：
 *
 * <ul>
 *   <li>叶页：从页中心一圈圈向外（{@link #pageCenterOut}）；</li>
 *   <li>粗页：区块优先、区块内像素连续（{@link #coarseChunkMajor}）。</li>
 * </ul>
 *
 * <p>页与页之间"离玩家由近到远"是另一回事（见 {@code CenterOutSweep}），这里只管页内。
 * 三处用到顺序表的地方（问新块、补画、重试）共用同一张表，所以补上的块不会以另一种方向冒出来。
 */
final class TileSweepOrder {

	private TileSweepOrder() {
	}

	/**
	 * 叶页：<b>一列一列地扫</b>（先从上到下走完一列区块，再走右边一列）。
	 *
	 * <p>块索引本身仍是 {@code z * chunksPerSide + x}（写入、待办、重试都按它算），这张表只决定
	 * "先问哪一块"。
	 *
	 * <p><b>为什么是列而不是"从中心向外"。</b>试过同心圈（页中心向外），观感是"从中间往外放射"、
	 * 一屏几十页同时长，看起来像"哪儿都在长、又都长不完"。
	 * 按列扫则是把一条竖带<b>扫透</b>再扫下一条：完成一块地方是一块地方，进度看得见。
	 *
	 * <p>页与页之间仍是"离玩家由近到远"（见 {@code CenterOutSweep}），所以整体是"从玩家附近开始、
	 * 一列一列铺开"。
	 *
	 * <p><b>刻意不做"跟着朝向走"</b>：试过让条带垂直于视线，斜着走时地图上真的出现一条条<b>斜着</b>
	 * 长出来的带子、一转头方向还会跳。
	 */
	static int[] pageColumns(int chunksPerSide) {
		int count = chunksPerSide * chunksPerSide;
		int[] order = new int[count];
		int at = 0;
		for (int x = 0; x < chunksPerSide; x++) {
			for (int z = 0; z < chunksPerSide; z++) {
				order[at++] = z * chunksPerSide + x;
			}
		}
		return order;
	}

	/**
	 * 粗页：<b>按区块一列一列地扫</b>（先一列区块从上到下，再右边一列），区块内像素连续走。
	 *
	 * <p>与叶页同一口径（{@link #pageColumns}）：整屏只有一种走向"一列一列铺开"，不再出现
	 * "有的从中心放射、有的按列"两种。
	 *
	 * <p><b>区块要连续走完。</b>粗页一个像素覆盖多个方块，一个区块占 {@code pixelsPerChunk}² 个粗像素：
	 * 按像素列优先扫会把同一区块的像素拉成一条竖线、跨区块时缓存反复失效。区块优先让同一区块的像素连续
	 * 处理，填充里那个区块缓存（{@code shadeChunkPair} 是重的）才真正起作用。
	 *
	 * @param size           这一档的贴图边长（像素）
	 * @param pixelsPerChunk 一个区块在这一档里占的像素边长（2 档 8、4 档 4）
	 * @param chunksPerSide  一页管多少个区块（每轴）
	 */
	static int[] coarseColumns(int size, int pixelsPerChunk, int chunksPerSide) {
		int[] order = new int[size * size];
		int step = 0;
		for (int chunkX = 0; chunkX < chunksPerSide; chunkX++) {
			for (int chunkZ = 0; chunkZ < chunksPerSide; chunkZ++) {
				// 区块内像素整体走完，保证同一区块的像素紧挨着（缓存只在换区块时失效）
				for (int pz = 0; pz < pixelsPerChunk; pz++) {
					int pixelZ = chunkZ * pixelsPerChunk + pz;
					for (int px = 0; px < pixelsPerChunk; px++) {
						int pixelX = chunkX * pixelsPerChunk + px;
						order[step++] = pixelZ * size + pixelX;
					}
				}
			}
		}
		return order;
	}
}
