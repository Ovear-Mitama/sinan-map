package sinanmap.common.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegionStoreTest {

	private static final int REFERENCE_Y = 63;

	private static ChunkRecord record(int rgb) {
		ChunkRecord record = new ChunkRecord();
		for (int i = 0; i < ChunkRecord.COLUMNS; i++) {
			record.setColumn(i, rgb, REFERENCE_Y + i % 8, 15, 0);
		}
		return record;
	}

	@Test
	@DisplayName("写入后能读回同一份数据")
	void writeThenRead(@TempDir Path directory) throws IOException {
		try (RegionStore store = new RegionStore(RegionStore.pathFor(directory, 3, 5))) {
			store.write(3, 5, record(0xAABBCC));

			ChunkRecord loaded = store.read(3, 5);
			assertNotNull(loaded);
			assertEquals(0xAABBCC, loaded.rgb(0));
			assertEquals(REFERENCE_Y, loaded.height(0));
			assertEquals(15, loaded.skyLight(0));
		}
	}

	@Test
	@DisplayName("文件不存在时读取返回 null，且不会创建文件")
	void readMissingFileReturnsNull(@TempDir Path directory) throws IOException {
		Path path = RegionStore.pathFor(directory, 0, 0);

		try (RegionStore store = new RegionStore(path)) {
			assertNull(store.read(0, 0));
		}

		assertFalse(Files.exists(path), "只读不该凭空创建文件");
	}

	@Test
	@DisplayName("同一区域内没写过的区块返回 null")
	void unwrittenSlotReturnsNull(@TempDir Path directory) throws IOException {
		try (RegionStore store = new RegionStore(RegionStore.pathFor(directory, 0, 0))) {
			store.write(0, 0, record(0x111111));

			assertNull(store.read(1, 0), "邻居区块没写过");
			assertNull(store.read(0, 1));
			assertNotNull(store.read(0, 0));
		}
	}

	@Test
	@DisplayName("覆盖写入取最后一次的值")
	void overwrite(@TempDir Path directory) throws IOException {
		try (RegionStore store = new RegionStore(RegionStore.pathFor(directory, 0, 0))) {
			store.write(2, 2, record(0x111111));
			store.write(2, 2, record(0x222222));

			assertEquals(0x222222, store.read(2, 2).rgb(0));
		}
	}

	@Test
	@DisplayName("负坐标落入正确的区域与槽位")
	void negativeCoordinates(@TempDir Path directory) throws IOException {
		int chunkX = -33;
		int chunkZ = -1;
		Path path = RegionStore.pathFor(directory, chunkX, chunkZ);

		assertEquals("r.-2.-1.sinanmap", path.getFileName().toString(), "负坐标向负无穷取整");

		try (RegionStore store = new RegionStore(path)) {
			store.write(chunkX, chunkZ, record(0x334455));

			assertEquals(0x334455, store.read(chunkX, chunkZ).rgb(0));
			assertNull(store.read(-32, -1), "同一区域内的另一列不应有数据");
		}
	}

	@Test
	@DisplayName("跨区域写入落到不同文件")
	void separateRegionsUseSeparateFiles(@TempDir Path directory) throws IOException {
		Path first = RegionStore.pathFor(directory, 0, 0);
		Path second = RegionStore.pathFor(directory, RegionStore.REGION_CHUNKS, 0);

		assertTrue(!first.equals(second), "跨区域必须是两个文件");

		try (RegionStore a = new RegionStore(first); RegionStore b = new RegionStore(second)) {
			a.write(0, 0, record(0xAA0000));
			b.write(RegionStore.REGION_CHUNKS, 0, record(0x00AA00));

			assertEquals(0xAA0000, a.read(0, 0).rgb(0));
			assertEquals(0x00AA00, b.read(RegionStore.REGION_CHUNKS, 0).rgb(0));
		}
	}

	@Test
	@DisplayName("区域内的每个槽位互不干扰")
	void slotsAreIndependent(@TempDir Path directory) throws IOException {
		try (RegionStore store = new RegionStore(RegionStore.pathFor(directory, 0, 0))) {
			store.write(0, 0, record(0x000001));
			store.write(31, 31, record(0x000002));
			store.write(15, 7, record(0x000003));

			assertEquals(0x000001, store.read(0, 0).rgb(0));
			assertEquals(0x000002, store.read(31, 31).rgb(0));
			assertEquals(0x000003, store.read(15, 7).rgb(0));
		}
	}

	@Test
	@DisplayName("版本不符的旧文件按无数据处理")
	void staleVersionIsIgnored(@TempDir Path directory) throws IOException {
		Path path = RegionStore.pathFor(directory, 0, 0);

		// 手工造一个魔数正确但版本为 1 的旧文件，且位图声称第 0 个槽位有数据
		byte[] stale = new byte[512];
		stale[0] = 0x53;
		stale[1] = 0x4E;
		stale[2] = 0x41;
		stale[3] = 0x4E;
		stale[4] = 0;
		stale[5] = 0;
		stale[6] = 0;
		stale[7] = 1;
		stale[16] = 1;
		Files.write(path, stale);

		try (RegionStore store = new RegionStore(path)) {
			assertNull(store.read(0, 0), "旧版本数据必须失效，否则会显示改配方前的老颜色");
		}
	}

	@Test
	@DisplayName("写入时遇到旧版本文件会重建")
	void writeOverStaleVersionStartsFresh(@TempDir Path directory) throws IOException {
		Path path = RegionStore.pathFor(directory, 0, 0);
		Files.write(path, new byte[512]);

		try (RegionStore store = new RegionStore(path)) {
			store.write(4, 4, record(0x556677));

			assertEquals(0x556677, store.read(4, 4).rgb(0));
			assertNull(store.read(0, 0), "重建后不该残留旧索引表声称的数据");
		}
	}

	@Test
	@DisplayName("索引表容量与区块数一致")
	void slotTableMatchesRegionSize() {
		assertEquals(RegionStore.REGION_CHUNKS * RegionStore.REGION_CHUNKS, RegionStore.slotCount());
	}

	@Test
	@DisplayName("整区写满之后文件仍远小于未压缩的 2 MB（逐块 deflate）")
	void fullRegionIsCompressed(@TempDir Path directory) throws IOException {
		Path path = RegionStore.pathFor(directory, 0, 0);
		try (RegionStore store = new RegionStore(path)) {
			for (int x = 0; x < RegionStore.REGION_CHUNKS; x++) {
				for (int z = 0; z < RegionStore.REGION_CHUNKS; z++) {
					store.write(x, z, record(0x336699));
				}
			}
			// 压完还得原样读得回来
			assertEquals(0x336699, store.read(7, 9).rgb(0));
			assertEquals(REFERENCE_Y, store.read(7, 9).height(0));
		}
		long size = Files.size(path);
		assertTrue(size < 512 * 1024,
				"1024 块压完应远小于未压缩的 2 MB（实际 " + size + " 字节）");
	}

	@Test
	@DisplayName("数据只读到半截时抛异常，而不是当成'没有这一块'（黑格回归）")
	void truncatedPayloadThrowsInsteadOfReportingAbsent(@TempDir Path directory) throws IOException {
		Path path = RegionStore.pathFor(directory, 0, 0);
		try (RegionStore writer = new RegionStore(path)) {
			writer.write(0, 0, record(0x123456));
		}
		// 索引表（16 + 每块 8 字节）留着，把后面那一段数据截掉一大半：解压必然拿不到完整一条
		int headerSize = 16 + RegionStore.REGION_CHUNKS * RegionStore.REGION_CHUNKS * 8;
		byte[] full = Files.readAllBytes(path);
		assertTrue(full.length > headerSize + 8, "前置条件：文件里确实有数据区");
		Files.write(path, Arrays.copyOf(full, headerSize + 8));

		try (RegionStore store = new RegionStore(path)) {
			assertThrows(IOException.class, () -> store.read(0, 0),
					"读不全必须抛异常：返回 null 会被上层记成'这一块没有数据'，那一格永远是黑的");
		}
	}

	@Test
	@DisplayName("hasRecord 只看索引表，能区分写过与没写过")
	void hasRecordReadsBitmapOnly(@TempDir Path directory) throws IOException {
		try (RegionStore store = new RegionStore(RegionStore.pathFor(directory, 0, 0))) {
			store.write(3, 5, record(0x123456));

			assertTrue(store.hasRecord(3, 5));
			assertFalse(store.hasRecord(5, 3), "x 与 z 互换是另一个槽位");
			assertFalse(store.hasRecord(0, 0));
		}
	}

	@Test
	@DisplayName("文件不存在时 hasRecord 为假，也不会创建文件")
	void hasRecordOnMissingFile(@TempDir Path directory) throws IOException {
		Path path = RegionStore.pathFor(directory, 9, 9);

		try (RegionStore store = new RegionStore(path)) {
			assertFalse(store.hasRecord(9, 9));
		}

		assertFalse(Files.exists(path));
	}

	@Test
	@DisplayName("先读后写：索引表缓存不会把刚写进去的数据否掉")
	void writeAfterFailedReadIsVisible(@TempDir Path directory) throws IOException {
		try (RegionStore store = new RegionStore(RegionStore.pathFor(directory, 0, 0))) {
			// 这一次读会把"头部不可用"缓存下来，写入时必须同步更新这份缓存
			assertNull(store.read(1, 1), "文件还不存在");

			store.write(1, 1, record(0x654321));

			assertNotNull(store.read(1, 1), "同实例内写进去的数据必须读得回来");
			assertEquals(0x654321, store.read(1, 1).rgb(0));
		}
	}

	@Test
	@DisplayName("写入新数据后使读取侧失效，才能看到后写入的区块（黑块回归测试）")
	void invalidateHeaderPicksUpLaterWrites(@TempDir Path directory) throws IOException {
		try (RegionStore reader = new RegionStore(RegionStore.pathFor(directory, 0, 0));
				RegionStore writer = new RegionStore(RegionStore.pathFor(directory, 0, 0))) {
			writer.write(0, 0, record(0x111111));
			assertTrue(reader.hasRecord(0, 0), "读过头部之后应能看到已有的区块");

			// 模拟"玩家走开又回来"：同一区域文件里又写了新区块，
			// 而读取侧的索引表还是写入前的快照
			writer.write(1, 0, record(0x222222));
			assertFalse(reader.hasRecord(1, 0), "未失效前读取侧看不到后写入的区块，这正是黑块的来源");

			reader.invalidateCache();

			assertTrue(reader.hasRecord(1, 0), "失效后必须能看到新写入的区块");
			assertEquals(0x222222, reader.read(1, 0).rgb(0));
			assertTrue(reader.hasRecord(0, 0), "旧区块不应被一起丢掉");
		}
	}

	@Test
	@DisplayName("内存模式：整份载入后读写结果与逐区块读一致")
	void memoryModeServesTheSameData(@TempDir Path directory) throws IOException {
		Path path = RegionStore.pathFor(directory, 0, 0);
		try (RegionStore writer = new RegionStore(path)) {
			writer.write(3, 5, record(0xAABBCC));
			writer.write(-1, 0, record(0x123456));
		}

		try (RegionStore memory = new RegionStore(path, true)) {
			assertTrue(memory.hasRecord(3, 5));
			assertEquals(0xAABBCC, memory.read(3, 5).rgb(0));
			assertEquals(0x123456, memory.read(-1, 0).rgb(0));
			assertFalse(memory.hasRecord(0, 0), "没写过的槽位仍应报告没有");
			assertNull(memory.read(0, 0));
		}
	}

	@Test
	@DisplayName("内存模式：文件不存在时不会凭空创建")
	void memoryModeOnMissingFile(@TempDir Path directory) throws IOException {
		Path path = RegionStore.pathFor(directory, 7, 7);

		try (RegionStore memory = new RegionStore(path, true)) {
			assertFalse(memory.hasRecord(7, 7));
			assertNull(memory.read(7, 7));
		}

		assertFalse(Files.exists(path));
	}

	@Test
	@DisplayName("按索引表判定不存在时不需要读数据区")
	void absentSlotSkipsSlotRead(@TempDir Path directory) throws IOException {
		try (RegionStore store = new RegionStore(RegionStore.pathFor(directory, 0, 0))) {
			store.write(31, 31, record(0x0000AA));

			for (int i = 0; i < 32; i++) {
				for (int j = 0; j < 32; j++) {
					if (i != 31 || j != 31) {
						assertFalse(store.hasRecord(i, j));
						assertNull(store.read(i, j));
					}
				}
			}
		}
	}
}
