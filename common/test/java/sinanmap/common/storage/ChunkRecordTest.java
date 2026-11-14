package sinanmap.common.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sinanmap.common.map.SurfaceShading;

class ChunkRecordTest {

	private static final int REFERENCE_Y = 63;

	private static ChunkRecord flatRecord(int rgb, int height, int skyLight, int blockLight) {
		ChunkRecord record = new ChunkRecord();
		for (int i = 0; i < ChunkRecord.COLUMNS; i++) {
			record.setColumn(i, rgb, height, skyLight, blockLight);
		}
		return record;
	}

	@Test
	@DisplayName("序列化再反序列化后每个字段都不变")
	void roundTrip() {
		ChunkRecord original = new ChunkRecord();
		for (int i = 0; i < ChunkRecord.COLUMNS; i++) {
			original.setColumn(i, 0x102030 + i, -64 + i, i % 16, (i * 3) % 16);
			original.setFlatSheet(i, i % 3 == 0);
		}

		byte[] buffer = new byte[ChunkRecord.BYTES];
		original.encode(buffer, 0);
		ChunkRecord restored = ChunkRecord.decode(buffer, 0);

		for (int i = 0; i < ChunkRecord.COLUMNS; i++) {
			assertEquals(original.rgb(i), restored.rgb(i), "本色 #" + i);
			assertEquals(original.height(i), restored.height(i), "高度 #" + i);
			assertEquals(original.skyLight(i), restored.skyLight(i), "天空光 #" + i);
			assertEquals(original.blockLight(i), restored.blockLight(i), "方块光 #" + i);
			assertEquals(original.isFlatSheet(i), restored.isFlatSheet(i), "平铺流体标记 #" + i);
		}
	}

	@Test
	@DisplayName("水面只吸收一部分地形明暗：不会黑一块亮一块")
	void flatSheetColumnsIgnoreSlope() {
		// 流体层列按 WATER_RELIEF 只吸收一部分地形明暗，海底的起伏不会被照单全收
		assertTrue(spread(sawtoothRecord(true)) * 2 < spread(sawtoothRecord(false)),
				"水面的明暗起伏应明显小于普通地形，当前 " + spread(sawtoothRecord(true))
						+ " vs " + spread(sawtoothRecord(false)));
	}

	@Test
	@DisplayName("水面按比例吸收地形明暗：既不是纯色，也不会黑一块亮一块")
	void flatSheetAbsorbsPartOfTerrainShading() {
		int[] waterShallow = flatSheetRecord(0x808080, REFERENCE_Y, 15, 0).toColors(REFERENCE_Y, 0);
		int[] waterDeep = flatSheetRecord(0x808080, REFERENCE_Y - 20, 15, 0).toColors(REFERENCE_Y, 0);
		int[] landShallow = flatRecord(0x808080, REFERENCE_Y, 15, 0).toColors(REFERENCE_Y, 0);
		int[] landDeep = flatRecord(0x808080, REFERENCE_Y - 20, 15, 0).toColors(REFERENCE_Y, 0);

		int waterDelta = Math.abs(luminance(waterShallow[0]) - luminance(waterDeep[0]));
		int landDelta = Math.abs(luminance(landShallow[0]) - luminance(landDeep[0]));

		assertTrue(waterDelta > 0, "水面必须能看出水底的高低差，否则水底就是一块纯色");
		assertTrue(waterDelta < landDelta, "水面的起伏要明显小于地形本身，否则水面像一块起伏的布");
	}

	/** 整块都是"平铺的流体层"（水），见 {@link ChunkRecord#FLAG_FLAT_SHEET}。 */
	private static ChunkRecord flatSheetRecord(int rgb, int height, int skyLight, int blockLight) {
		ChunkRecord record = new ChunkRecord();
		for (int i = 0; i < ChunkRecord.COLUMNS; i++) {
			record.setColumn(i, rgb, height, skyLight, blockLight);
			record.setFlatSheet(i, true);
		}
		return record;
	}

	/** 沿 X 的高低锯齿；{@code flatSheet} 为真时整块标成平铺的流体层（水）。 */
	private static ChunkRecord sawtoothRecord(boolean flatSheet) {
		ChunkRecord record = new ChunkRecord();
		for (int z = 0; z < ChunkRecord.CHUNK_SIZE; z++) {
			for (int x = 0; x < ChunkRecord.CHUNK_SIZE; x++) {
				int index = z * ChunkRecord.CHUNK_SIZE + x;
				record.setColumn(index, 0x808080, REFERENCE_Y + (x % 2 == 0 ? 0 : 3), 15, 0);
				record.setFlatSheet(index, flatSheet);
			}
		}
		return record;
	}

	/** 一行里最大与最小的亮度差，用来衡量"起伏"。 */
	private static int spread(ChunkRecord record) {
		int[] colors = record.toColors(REFERENCE_Y, 0);
		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;
		for (int x = 0; x < ChunkRecord.CHUNK_SIZE; x++) {
			int value = luminance(colors[x]);
			min = Math.min(min, value);
			max = Math.max(max, value);
		}
		return max - min;
	}

	@Test
	@DisplayName("负高度能正确往返（half 是有符号的）")
	void negativeHeightSurvives() {
		ChunkRecord original = flatRecord(0x123456, -64, 15, 0);

		byte[] buffer = new byte[ChunkRecord.BYTES];
		original.encode(buffer, 0);

		assertEquals(-64, ChunkRecord.decode(buffer, 0).height(0));
	}

	@Test
	@DisplayName("扁平满光照的地块：合成结果等于方块本色（不放大）")
	void flatLitTerrainKeepsBaseColor() {
		ChunkRecord record = flatRecord(0x8899AA, REFERENCE_Y, 15, 0);

		int[] colors = record.toColors(REFERENCE_Y, 0);

		// 高度等于参考高度、坡度为 0、满光照时三项乘积恰为 1.0
		assertEquals(0xFF8899AA, colors[0]);
	}

	@Test
	@DisplayName("昼夜衰减按 max(方块光, 天空光 - 衰减) 生效")
	void skyDarkenDimsTheMap() {
		ChunkRecord record = flatRecord(0xFFFFFF, REFERENCE_Y, 15, 0);

		int[] noon = record.toColors(REFERENCE_Y, 0);
		int[] night = record.toColors(REFERENCE_Y, 11);

		assertTrue(luminance(night[0]) < luminance(noon[0]), "夜晚应比正午暗");
		assertEquals(night[0], record.toColors(REFERENCE_Y, 11)[0], "同一输入结果稳定");
	}

	@Test
	@DisplayName("有方块光时不受昼夜影响（地下火把附近）")
	void blockLightIsIndependentOfDayTime() {
		ChunkRecord record = flatRecord(0xFFFFFF, REFERENCE_Y, 0, 14);

		int[] noon = record.toColors(REFERENCE_Y, 0);
		int[] night = record.toColors(REFERENCE_Y, 11);

		assertEquals(noon[0], night[0], "方块光为主时昼夜归一");
	}

	@Test
	@DisplayName("合成本身走的就是 SurfaceShading，不是另一套公式")
	void colorsMatchSurfacingShading() {
		ChunkRecord record = flatRecord(0x6688CC, REFERENCE_Y + 10, 15, 0);

		int[] colors = record.toColors(REFERENCE_Y, 0);

		assertEquals(SurfaceShading.shade(0x6688CC, REFERENCE_Y + 10, REFERENCE_Y, 15,
				REFERENCE_Y + 10, REFERENCE_Y + 10), colors[0]);
	}

	@Test
	@DisplayName("两条记录交错着色互不干扰（着色用的临时数组是共用的）")
	void toColorsIsStableAcrossInterleavedRecords() {
		// 逐列系数与光照中间量是 static（每条记录各留一份要白搭 4 KB），交错着色时必须各算各的：
		// 只要有一次忘了整体覆盖，结果就会串。
		ChunkRecord first = flatRecord(0xFF0000, REFERENCE_Y, 15, 0);
		ChunkRecord second = flatRecord(0x00FF00, REFERENCE_Y - 30, 3, 0);

		int[] firstAlone = first.toColors(REFERENCE_Y, 0).clone();
		second.toColors(REFERENCE_Y, 11);
		int[] firstAfterOther = first.toColors(REFERENCE_Y, 0);

		assertArrayEquals(firstAlone, firstAfterOther, "中间夹了另一次着色，结果必须一样");
	}

	@Test
	@DisplayName("区块最外一圈沿用内部列的明暗：边界处不再出现一条明暗缝")
	void chunkEdgesDoNotShowASeam() {
		// 沿 X 做斜坡让坡面明暗非平凡；本色与光照处处一致，相邻列亮度不同只可能来自坡面系数。
		ChunkRecord record = new ChunkRecord();
		for (int z = 0; z < ChunkRecord.CHUNK_SIZE; z++) {
			for (int x = 0; x < ChunkRecord.CHUNK_SIZE; x++) {
				record.setColumn(z * ChunkRecord.CHUNK_SIZE + x, 0x808080, REFERENCE_Y + x, 15, 0);
			}
		}

		int[] colors = record.toColors(REFERENCE_Y, 0);

		// 边界列缺西侧邻居，按"没有落差"直接算会与相邻内部列差一档，连起来正是网格状明暗缝。
		int naiveEdge = SurfaceShading.shade(0x808080, REFERENCE_Y, REFERENCE_Y, 15,
				REFERENCE_Y, REFERENCE_Y);
		int interior = SurfaceShading.shade(0x808080, REFERENCE_Y + 1, REFERENCE_Y, 15,
				REFERENCE_Y, REFERENCE_Y + 1);
		assertNotEquals(naiveEdge, interior, "这正是明暗缝的成因");

		assertEquals(colors[1], colors[0], "修正后最左列应与第二列同亮度");
	}

	@Test
	@DisplayName("夜晚压到白天约三成亮度：整张图不发灰，光源位置能跳出来")
	void nightIsDarkEnoughForLightsToStandOut() {
		int[] noon = flatRecord(0xFFFFFF, REFERENCE_Y, 15, 0).toColors(REFERENCE_Y, 0);
		int[] night = flatRecord(0xFFFFFF, REFERENCE_Y, 15, 0).toColors(REFERENCE_Y, 11);
		// 同一列的方块光（火把附近）：夜里几乎保持满亮
		int[] lit = flatRecord(0xFFFFFF, REFERENCE_Y, 15, 14).toColors(REFERENCE_Y, 11);

		double ratio = luminance(night[0]) / (double) luminance(noon[0]);
		assertTrue(ratio < 0.4, "夜间应明显变暗，当前白天亮度的 " + "%.2f".formatted(ratio));
		assertTrue(ratio > 0.2, "也不该暗到读不出地形，当前 " + "%.2f".formatted(ratio));
		assertTrue(luminance(lit[0]) > luminance(night[0]) * 2,
				"有方块光的地方应明显亮于夜间地表，光源才看得见");
	}

	@Test
	@DisplayName("打包后每个字段都能原样取回（含通道与高度的边界值）")
	void packedFieldsRoundTripThroughGetters() {
		ChunkRecord record = new ChunkRecord();
		record.setColumn(0, 0x000000, -64, 0, 0);
		assertEquals(0x000000, record.rgb(0));
		assertEquals(-64, record.height(0), "世界底为负高度，必须原样往返");

		record.setColumn(1, 0xFFFFFF, 319, 15, 15);
		assertEquals(0xFFFFFF, record.rgb(1));
		assertEquals(319, record.height(1));
		assertEquals(15, record.skyLight(1));
		assertEquals(15, record.blockLight(1));

		record.setColumn(2, 0xABCDEF, 320, 3, 12);
		assertEquals(0xABCDEF, record.rgb(2));
		assertEquals(320, record.height(2));
		assertEquals(3, record.skyLight(2));
		assertEquals(12, record.blockLight(2));

		// 天空光/方块光是 0..15，越界要被夹住，且不能溢出到相邻字段
		record.setColumn(3, 0x010203, 0, 99, -5);
		assertEquals(15, record.skyLight(3));
		assertEquals(0, record.blockLight(3));
		assertEquals(0x010203, record.rgb(3));
		assertEquals(0, record.height(3));
	}

	@Test
	@DisplayName("落盘字节与打包前逐字节一致：旧存档天然可读，无需递增格式版本")
	void encodeKeepsTheOriginalByteLayout() {
		ChunkRecord record = new ChunkRecord();
		record.setColumn(0, 0x112233, -64, 7, 9); // 高度 -64 → 0xFFC0
		record.setEmissive(0, true); // 标志位 = FLAG_EMISSIVE = 2

		byte[] buffer = new byte[ChunkRecord.BYTES];
		record.encode(buffer, 0);

		// 本色 R/G/B、高度高/低、天空光、方块光、标志，逐字节与旧格式相同
		assertArrayEquals(new byte[] {0x11, 0x22, 0x33, (byte) 0xFF, (byte) 0xC0, 7, 9, 2},
				Arrays.copyOf(buffer, 8));
		assertEquals(2048, ChunkRecord.BYTES, "每列仍是 8 字节");
	}

	@Test
	@DisplayName("序列化往返：自发光与'没有数据'标记也不丢")
	void roundTripKeepsEmissiveAndNoData() {
		ChunkRecord original = new ChunkRecord();
		for (int i = 0; i < ChunkRecord.COLUMNS; i++) {
			if (i % 5 == 0) {
				original.setNoData(i); // 空列：整列清空并置位
			} else {
				original.setColumn(i, 0x112233 + i, 300 - i, i % 16, 15 - i % 16);
				original.setFlatSheet(i, i % 2 == 0);
				original.setEmissive(i, i % 3 == 0);
			}
		}

		byte[] buffer = new byte[ChunkRecord.BYTES];
		original.encode(buffer, 0);
		ChunkRecord restored = ChunkRecord.decode(buffer, 0);

		for (int i = 0; i < ChunkRecord.COLUMNS; i++) {
			assertEquals(original.rgb(i), restored.rgb(i), "本色 #" + i);
			assertEquals(original.height(i), restored.height(i), "高度 #" + i);
			assertEquals(original.skyLight(i), restored.skyLight(i), "天空光 #" + i);
			assertEquals(original.blockLight(i), restored.blockLight(i), "方块光 #" + i);
			assertEquals(original.isFlatSheet(i), restored.isFlatSheet(i), "平铺流体标记 #" + i);
			assertEquals(original.isEmissive(i), restored.isEmissive(i), "自发光标记 #" + i);
			assertEquals(original.isNoData(i), restored.isNoData(i), "无数据标记 #" + i);
		}
		assertTrue(original.sameContent(restored), "往返后内容必须一致");
	}

	@Test
	@DisplayName("sameContent 只比逐列原始数据，着色临时量不参与")
	void sameContentComparesRawColumnsOnly() {
		ChunkRecord first = new ChunkRecord();
		ChunkRecord second = new ChunkRecord();
		for (int i = 0; i < ChunkRecord.COLUMNS; i++) {
			first.setColumn(i, 0x203040 + i, 64 + i, i % 16, (i * 7) % 16);
			second.setColumn(i, 0x203040 + i, 64 + i, i % 16, (i * 7) % 16);
		}
		assertTrue(first.sameContent(second), "同一份输入必须判为相同");

		// 两份都跑过着色：共用的 static 暂存数组里留着各自的中间量，但不该影响判定
		first.toColors(REFERENCE_Y, 0);
		second.toColors(REFERENCE_Y, 11);
		assertTrue(first.sameContent(second), "着色用的临时数组不算数据");

		second.setColumn(7, 0x999999, 64 + 7, 7 % 16, (7 * 7) % 16);
		assertFalse(first.sameContent(second), "任何一列本色变了都要判为不同");

		ChunkRecord low = new ChunkRecord();
		low.setColumn(0, 0x203040, 64, 0, 0);
		ChunkRecord high = new ChunkRecord();
		high.setColumn(0, 0x203040, 65, 0, 0);
		assertFalse(low.sameContent(high), "高度不同要判为不同");
	}

	@Test
	@DisplayName("逐像素等价：同一份输入，着色结果稳定（golden 值随自发光取样起伏的改动更新过）")
	void toColorsIsPixelIdenticalToPreChange() {
		// golden 值由"每列五个独立数组"的实现生成，覆盖负高度、超 255 高度、自发光、水、无数据。
		// 第二十八批给自发光方块（岩浆）加了"只含地形起伏、不含光照"的系数（见 SurfaceShading#reliefFactor），
		// 自发光列的着色结果随之变化，这里的 golden 值已按新实现重新生成。
		ChunkRecord record = goldenRecord();

		int[] day = record.toColors(REFERENCE_Y, 0);
		assertEquals(905488936, Arrays.hashCode(day), "正午逐像素结果应与当前实现一致");
		assertEquals(-8331202, day[1], "普通陆地列");
		assertEquals(-16572899, day[16], "自发光/普通列逐像素一致");
		assertEquals(0, day[17], "无数据列全透明（17 % 17 == 0）");

		assertEquals(-1058541826, Arrays.hashCode(record.toColors(REFERENCE_Y, 11)), "夜晚逐像素一致");
		assertEquals(845582080, Arrays.hashCode(record.toColors(80, 4)), "另一参考高度也一致");
	}

	/** 确定性地造一块覆盖各种边界的区块：负高度、超 255 高度、自发光、水、无数据。 */
	private static ChunkRecord goldenRecord() {
		ChunkRecord record = new ChunkRecord();
		Random random = new Random(20240927L);
		for (int i = 0; i < ChunkRecord.COLUMNS; i++) {
			if (i % 17 == 0) {
				record.setNoData(i);
				continue;
			}
			int rgb = random.nextInt(0x1000000);
			int height = -64 + random.nextInt(385); // 世界高度 -64..320
			record.setColumn(i, rgb, height, random.nextInt(16), random.nextInt(16));
			record.setFlatSheet(i, random.nextBoolean());
			record.setEmissive(i, random.nextBoolean());
		}
		return record;
	}

	private static int luminance(int argb) {
		return ((argb >> 16) & 0xFF) + ((argb >> 8) & 0xFF) + (argb & 0xFF);
	}
}
