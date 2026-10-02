package sinanmap.common.map;

/**
 * 提供按区块划分的地表颜色：数据层与渲染层之间的接缝——实现方负责读取世界数据、做缓存，
 * 渲染方（{@code WorldTilePages}，小地图与全屏地图共用）只负责按区块查表、写贴图；取色入口只在渲染线程调用。
 */
public interface ChunkColorProvider {

	/**
	 * 取某个区块的 16×16 地表颜色。
	 *
	 * @return 长度 {@link ChunkColorCache#COLUMNS} 的 ARGB 数组，索引为 {@code localZ * 16 + localX}；
	 *         数据尚未准备好时返回 {@code null}，地图上会显示为未探索区域
	 */
	int[] colorsForChunk(int chunkX, int chunkZ);

	/**
	 * 取某个区块在"白天"与"夜晚"两档下的颜色，分别写进两个数组。
	 *
	 * <p>地图贴图分两张（见 {@code WorldTilePages}）：白天那张按不衰减烘焙、夜晚那张按原版午夜那一档
	 * 烘焙，绘制时按当前天色把夜晚那张以 alpha 叠上去。于是<b>换天色不需要重算任何像素</b>——整体昼夜
	 * 做成绘制时统一生效；26.1 里带颜色的 {@code blit} 只接受资源包贴图，动态贴图那条不带颜色，
	 * 所以那个 uniform 用"两张贴图 + alpha 混合"顶替。
	 *
	 * <p>默认实现退化成"两张填同一份当前颜色"，给不支持分档的来源用（例如测试用的栅格来源）。
	 *
	 * @return 有没有数据。false 表示这一块确实没有数据，两张都该画成"未探明"
	 */
	default boolean shadeChunkPair(int chunkX, int chunkZ, int[] dayOut, int[] nightOut) {
		int[] colors = colorsForChunk(chunkX, chunkZ);
		if (colors == null) {
			return false;
		}
		// 只搬两边都有的那一截：调用方的缓冲尺寸按视口大小来，不一定与这里的一份等长
		int length = Math.min(colors.length, Math.min(dayOut.length, nightOut.length));
		System.arraycopy(colors, 0, dayOut, 0, length);
		System.arraycopy(colors, 0, nightOut, 0, length);
		return true;
	}

	/**
	 * 这一块的数据是不是<b>正在来的路上</b>（已排给后台读盘、正在读）。
	 *
	 * <p>用来区分两种"现在没有数据"：一种是<b>真的没有</b>（区块没生成过、没加载过、存档里也没有），
	 * 另一种是<b>等一会儿就有</b>。前者该画成"未探明"，后者应当先空着等它——都画成未探明的话，
	 * 地图加载时会先出现一片暗块、过一会儿才变回地形。
	 *
	 * @return 数据在路上的返回 true；默认 false（不支持异步的来源无需关心）
	 */
	default boolean isPending(int chunkX, int chunkZ) {
		return false;
	}
}
