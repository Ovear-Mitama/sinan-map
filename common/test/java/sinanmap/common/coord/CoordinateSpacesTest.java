package sinanmap.common.coord;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CoordinateSpacesTest {

	@ParameterizedTest
	@CsvSource({"0, 0", "15, 0", "16, 1", "31, 1", "32, 2", "-1, -1", "-16, -1", "-17, -2"})
	@DisplayName("方块坐标换算区块坐标时向负无穷取整")
	void blockToChunkRoundsTowardNegativeInfinity(int block, int expectedChunk) {
		assertEquals(expectedChunk, CoordinateSpaces.blockToChunk(block));
	}

	@ParameterizedTest
	@CsvSource({"0, 0", "1, 0", "31, 0", "32, 1", "-1, -1", "-32, -1", "-33, -2"})
	@DisplayName("区块坐标换算区域坐标覆盖负坐标")
	void chunkToRegionHandlesNegativeCoordinates(int chunk, int expectedRegion) {
		assertEquals(expectedRegion, CoordinateSpaces.chunkToRegion(chunk));
	}

	@Test
	@DisplayName("区块与方块坐标互为逆运算")
	void chunkAndBlockAreInverse() {
		for (int chunk = -200; chunk <= 200; chunk++) {
			int origin = CoordinateSpaces.chunkToBlock(chunk);
			assertEquals(chunk, CoordinateSpaces.blockToChunk(origin));
			assertEquals(chunk, CoordinateSpaces.blockToChunk(origin + 15));
			assertEquals(chunk + 1, CoordinateSpaces.blockToChunk(origin + 16));
		}
	}

	@Test
	@DisplayName("区域与区块坐标互为逆运算")
	void regionAndChunkAreInverse() {
		for (int region = -50; region <= 50; region++) {
			int origin = CoordinateSpaces.regionToChunk(region);
			assertEquals(region, CoordinateSpaces.chunkToRegion(origin));
			assertEquals(region, CoordinateSpaces.chunkToRegion(origin + 31));
			assertEquals(region + 1, CoordinateSpaces.chunkToRegion(origin + 32));
		}
	}

	@Test
	@DisplayName("区块内偏移始终落在 0..15")
	void blockOffsetIsAlwaysWithinChunk() {
		assertEquals(0, CoordinateSpaces.blockOffsetInChunk(0));
		assertEquals(15, CoordinateSpaces.blockOffsetInChunk(15));
		assertEquals(0, CoordinateSpaces.blockOffsetInChunk(16));
		assertEquals(0, CoordinateSpaces.blockOffsetInChunk(-16));
		assertEquals(15, CoordinateSpaces.blockOffsetInChunk(-1));
	}

	@Test
	@DisplayName("区域键按 r.X.Z 的形式生成")
	void regionKeyUsesDotSeparator() {
		assertEquals("r.0.0", CoordinateSpaces.regionKey(0, 0));
		assertEquals("r.-1.2", CoordinateSpaces.regionKey(-1, 2));
	}

	@Test
	@DisplayName("区域边长等于 32 区块、512 方块")
	void regionSpanCoversThirtyTwoChunks() {
		assertEquals(512, CoordinateSpaces.regionSpanBlocks());
		assertEquals(16, CoordinateSpaces.chunkToRegion(512));
		assertEquals(0, CoordinateSpaces.blockToRegion(511));
		assertEquals(1, CoordinateSpaces.blockToRegion(512));
		assertEquals(-1, CoordinateSpaces.blockToRegion(-1));
	}
}
