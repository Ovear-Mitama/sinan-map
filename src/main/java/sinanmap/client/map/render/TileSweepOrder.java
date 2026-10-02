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
	 * 叶页：<b>从页中心一圈圈向外</b>（切比雪夫距离相同的算同一圈）。
	 *
	 * <p>块索引本身仍是 {@code z * chunksPerSide + x}（写入、待办、重试都按它算），这张表只决定
	 * "先问哪一块"。
	 *
	 * <p><b>为什么不再是"一列一列"。</b>按列推进时，一块正在长的页看起来是一条条<b>竖带</b>从上往下
	 * 拉出来，"多列加载"就是这么来的（用户反馈）。同心圈则是从中间一小块开始、四周一圈圈铺开——
	 * 页与页之间本来就是"离玩家由近到远"，两下一叠，观感就是地图从玩家附近均匀地长出来。
	 *
	 * <p><b>刻意不做"跟着朝向走"</b>：试过让条带垂直于视线，斜着走时地图上真的出现一条条<b>斜着</b>
	 * 长出来的带子、一转头方向还会跳。
	 */
	static int[] pageCenterOut(int chunksPerSide) {
		int count = chunksPerSide * chunksPerSide;
		int[] order = new int[count];
		int center = chunksPerSide / 2;
		int at = 0;
		for (int ring = 0; at < count; ring++) {
			for (int z = 0; z < chunksPerSide; z++) {
				for (int x = 0; x < chunksPerSide; x++) {
					if (Math.max(Math.abs(x - center), Math.abs(z - center)) != ring) {
						continue;
					}
					order[at++] = z * chunksPerSide + x;
				}
			}
		}
		return order;
	}

	/** 一"小块"的边长（区块）：与 {@link TilePage#SUB_SIDE} 同一个值，也就是<b>一次上屏的单位</b>。 */
	static final int GROUP_CHUNKS = TilePage.SUB_SIDE;

	/**
	 * 粗页：<b>按"小块"（{@value #GROUP_CHUNKS}×{@value #GROUP_CHUNKS} 个区块）从页中心一圈圈向外</b>，
	 * 小块内部再按"区块优先、区块内像素连续"走。
	 *
	 * <p><b>为什么也要同心圈。</b>以前是整页按"区块列"顺序扫（先一列区块从上到下，再右边一列），
	 * 缩到最远时一屏全是粗页，看到的就是一条条<b>竖着长出来的带子</b>；而叶页那边是从中心向外，
	 * 于是同一个地图上两种走向（用户反馈："有的只有一行加载，后面又变成列"）。现在两边都是"由中心向外"。
	 *
	 * <p><b>小块内部仍是区块优先。</b>粗页一个像素覆盖多个方块，一个区块占 {@code pixelsPerChunk}² 个
	 * 粗像素：按像素列优先扫会把同一区块的像素拉成一条竖线、跨区块时缓存反复失效。区块优先让同一区块的
	 * 像素连续处理，填充里那个区块缓存（{@code shadeChunkPair} 是重的）才真正起作用。
	 *
	 * <p>小块取 {@value #GROUP_CHUNKS}×{@value #GROUP_CHUNKS} 个区块，正好与"一次上屏的单位"对齐：
	 * 填满一小块才允许上屏，扫的顺序与看得见的顺序因此是一致的（一块块从中心铺开）。
	 *
	 * @param size           这一档的贴图边长（像素）
	 * @param pixelsPerChunk 一个区块在这一档里占的像素边长（2 档 8、4 档 4）
	 * @param chunksPerSide  一页管多少个区块（每轴）
	 */
	static int[] coarseCenterOut(int size, int pixelsPerChunk, int chunksPerSide) {
		int[] order = new int[size * size];
		int groupsPerSide = chunksPerSide / GROUP_CHUNKS;
		int center = groupsPerSide / 2;
		int step = 0;
		for (int ring = 0; step < order.length; ring++) {
			for (int groupZ = 0; groupZ < groupsPerSide; groupZ++) {
				for (int groupX = 0; groupX < groupsPerSide; groupX++) {
					if (Math.max(Math.abs(groupX - center), Math.abs(groupZ - center)) != ring) {
						continue;
					}
					// 这一"小块"里的区块：行优先（与叶页同一口径），块内像素也是行优先、整块走完再换区块
					for (int cz = 0; cz < GROUP_CHUNKS; cz++) {
						int chunkZ = groupZ * GROUP_CHUNKS + cz;
						for (int cx = 0; cx < GROUP_CHUNKS; cx++) {
							int chunkX = groupX * GROUP_CHUNKS + cx;
							for (int pz = 0; pz < pixelsPerChunk; pz++) {
								int pixelZ = chunkZ * pixelsPerChunk + pz;
								for (int px = 0; px < pixelsPerChunk; px++) {
									int pixelX = chunkX * pixelsPerChunk + px;
									order[step++] = pixelZ * size + pixelX;
								}
							}
						}
					}
				}
			}
		}
		return order;
	}
}
