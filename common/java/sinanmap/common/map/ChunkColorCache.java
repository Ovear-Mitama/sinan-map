package sinanmap.common.map;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按区块缓存的地表颜色。
 *
 * <p>逐像素采样必须走缓存：128 像素、1 方块/像素的小地图覆盖 64 个区块，若每次移动都重新采样地表
 * 会直接卡住渲染线程。缓存采用访问顺序的 LRU，超出容量后淘汰最久未用的区块。
 */
public final class ChunkColorCache {

	/** 单个区块的列数（16×16）。 */
	public static final int COLUMNS = 256;

	private final int maxEntries;
	private final Map<Long, int[]> entries;

	public ChunkColorCache(int maxEntries) {
		if (maxEntries <= 0) {
			throw new IllegalArgumentException("容量必须为正");
		}
		this.maxEntries = maxEntries;
		this.entries = new LinkedHashMap<>(Math.min(maxEntries, 256), 0.75F, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<Long, int[]> eldest) {
				return size() > ChunkColorCache.this.maxEntries;
			}
		};
	}

	/**
	 * 把区块坐标与层高度打包成缓存键，避免每次查找都创建对象。
	 *
	 * <p>位分配：层 16 位、区块 Z 22 位、区块 X 22 位。
	 * 层必须进键：同一区块的地表颜色与洞穴颜色完全不同。
	 *
	 * <p>真实层高度加偏移后落在 2048..6144，{@link LayerSelector#SURFACE} 用 0xFFFF 表示，
	 * 两者不会碰撞（若直接用 {@code layer + 2048} 打包，Y=0 会与地表哨兵撞键）。
	 */
	public static long key(int chunkX, int chunkZ, int layer) {
		long layerBits = layer == LayerSelector.SURFACE ? 0xFFFFL : (layer + 4096) & 0xFFFFL;
		return ((long) (chunkX & 0x3FFFFF) << 38)
				| ((long) (chunkZ & 0x3FFFFF) << 16)
				| layerBits;
	}

	/** 取缓存，未命中返回 null，命中会刷新其"最近使用"顺序。 */
	public int[] get(int chunkX, int chunkZ, int layer) {
		return entries.get(key(chunkX, chunkZ, layer));
	}

	/** 写入缓存。数组长度必须是 {@link #COLUMNS}。 */
	public void put(int chunkX, int chunkZ, int layer, int[] colors) {
		if (colors.length != COLUMNS) {
			throw new IllegalArgumentException("颜色数组长度必须是 " + COLUMNS + "，当前为 " + colors.length);
		}
		entries.put(key(chunkX, chunkZ, layer), colors);
	}

	public int size() {
		return entries.size();
	}

	public void clear() {
		entries.clear();
	}
}
