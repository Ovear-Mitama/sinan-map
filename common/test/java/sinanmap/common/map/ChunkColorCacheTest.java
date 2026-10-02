package sinanmap.common.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChunkColorCacheTest {

	private static final int SURFACE = LayerSelector.SURFACE;
	private static final int CAVE_Y = 48;

	private static int[] colors(int fill) {
		int[] array = new int[ChunkColorCache.COLUMNS];
		Arrays.fill(array, fill);
		return array;
	}

	@Test
	@DisplayName("写入后可读回，负坐标同样有效")
	void putAndGet() {
		ChunkColorCache cache = new ChunkColorCache(4);

		cache.put(0, 0, SURFACE, colors(0xFF112233));
		cache.put(-3, 7, SURFACE, colors(0xFF445566));

		assertEquals(0xFF112233, cache.get(0, 0, SURFACE)[0]);
		assertEquals(0xFF445566, cache.get(-3, 7, SURFACE)[0]);
		assertNull(cache.get(9, 9, SURFACE));
	}

	@Test
	@DisplayName("x 与 z 互换不会命中同一个键")
	void keyDistinguishesAxes() {
		ChunkColorCache cache = new ChunkColorCache(4);

		cache.put(1, 2, SURFACE, colors(0xFFAAAAAA));

		assertNotNull(cache.get(1, 2, SURFACE));
		assertNull(cache.get(2, 1, SURFACE));
	}

	@Test
	@DisplayName("同一区块的地表与洞穴颜色互不覆盖")
	void keyDistinguishesLayers() {
		ChunkColorCache cache = new ChunkColorCache(4);

		cache.put(0, 0, SURFACE, colors(0xFF111111));
		cache.put(0, 0, CAVE_Y, colors(0xFF222222));

		assertEquals(0xFF111111, cache.get(0, 0, SURFACE)[0]);
		assertEquals(0xFF222222, cache.get(0, 0, CAVE_Y)[0]);
		assertNull(cache.get(0, 0, CAVE_Y - 4));
	}

	@Test
	@DisplayName("层 Y=0 不会与地表哨兵撞键")
	void zeroLayerDoesNotCollideWithSurface() {
		assertNull(new ChunkColorCache(2).get(0, 0, 0));
		assertEquals(0, LayerSelector.quantize(0), "层 0 是合法洞穴层");

		ChunkColorCache cache = new ChunkColorCache(4);
		cache.put(0, 0, 0, colors(0xFF333333));
		cache.put(0, 0, SURFACE, colors(0xFF444444));

		assertEquals(0xFF333333, cache.get(0, 0, 0)[0]);
		assertEquals(0xFF444444, cache.get(0, 0, SURFACE)[0]);
	}

	@Test
	@DisplayName("超出容量后淘汰最久未使用的条目")
	void evictsLeastRecentlyUsed() {
		ChunkColorCache cache = new ChunkColorCache(2);

		cache.put(0, 0, SURFACE, colors(1));
		cache.put(1, 0, SURFACE, colors(2));
		cache.get(0, 0, SURFACE);
		cache.put(2, 0, SURFACE, colors(3));

		assertEquals(2, cache.size());
		assertNotNull(cache.get(0, 0, SURFACE), "最近使用过的区块应保留");
		assertNotNull(cache.get(2, 0, SURFACE));
		assertNull(cache.get(1, 0, SURFACE), "最久未使用的区块应被淘汰");
	}

	@Test
	@DisplayName("数组长度不对直接抛异常")
	void wrongColumnCountIsRejected() {
		ChunkColorCache cache = new ChunkColorCache(2);

		assertThrows(IllegalArgumentException.class, () -> cache.put(0, 0, SURFACE, new int[10]));
	}

	@Test
	@DisplayName("容量必须为正")
	void capacityMustBePositive() {
		assertThrows(IllegalArgumentException.class, () -> new ChunkColorCache(0));
	}

	@Test
	@DisplayName("clear 清空全部缓存")
	void clearRemovesEverything() {
		ChunkColorCache cache = new ChunkColorCache(4);
		cache.put(0, 0, SURFACE, colors(1));

		cache.clear();

		assertEquals(0, cache.size());
		assertNull(cache.get(0, 0, SURFACE));
	}
}
