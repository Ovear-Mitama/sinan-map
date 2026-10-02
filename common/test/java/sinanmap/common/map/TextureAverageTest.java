package sinanmap.common.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TextureAverageTest {

	private static final int OPAQUE = 0xFF000000;

	private static int rgb(int value) {
		return value & 0xFFFFFF;
	}

	@Test
	@DisplayName("纯色贴图：灰色被轻微提亮，且仍是中性灰")
	void solidColorIsMuted() {
		int[] pixels = new int[16];
		java.util.Arrays.fill(pixels, OPAQUE | 0x808080);

		int result = TextureAverage.average(pixels);

		// 灰色没有色相可去，只有整体亮度系数在起作用：略提亮，但不能明显跳亮一大截
		assertTrue(rgb(result) >= 0x808080, "柔和化应整体略提亮，当前为 " + Integer.toHexString(rgb(result)));
		assertTrue(rgb(result) <= 0x909090, "提亮幅度应很小，不该跳亮一大截");
		assertEquals(0, saturation(rgb(result)), "中性灰不该被染上色相");
	}

	@Test
	@DisplayName("全透明像素被跳过，不参与平均")
	void transparentPixelsAreSkipped() {
		int[] opaqueOnly = {OPAQUE | 0x204060, OPAQUE | 0x204060};
		int[] withTransparent = {OPAQUE | 0x204060, OPAQUE | 0x204060, 0x00123456, 0x00000000};

		assertEquals(TextureAverage.average(opaqueOnly), TextureAverage.average(withTransparent));
	}

	@Test
	@DisplayName("没有任何有效像素时返回兜底色")
	void noValidPixelFallsBack() {
		assertEquals(TextureAverage.FALLBACK, TextureAverage.average(new int[] {0x00000000, 0x01FFFFFF}));
		assertEquals(TextureAverage.FALLBACK, TextureAverage.averageBrightest(new int[] {0x00000000}));
	}

	@Test
	@DisplayName("发光方块：两极分布时取到的是明亮那一档，且比普通平均明显更亮")
	void emissivePicksTheBrightHalf() {
		// 模拟熔岩：一半暗色渣壳、一半明亮熔体
		int crust = OPAQUE | 0x4A1E00;
		int melt = OPAQUE | 0xFF9A2E;
		int[] pixels = {crust, crust, melt, melt};

		int emissive = TextureAverage.averageBrightest(pixels);

		assertTrue(TextureAverage.luminance(emissive) > TextureAverage.luminance(melt),
				"应取到熔体那一档并再提亮");
		// 普通平均本身也会被柔和化提亮，所以这里比的是"明显更亮"而不是成倍
		assertTrue(TextureAverage.luminance(emissive) > TextureAverage.luminance(TextureAverage.average(pixels)) * 1.5,
				"应比普通平均明显更亮");
	}

	@Test
	@DisplayName("发光方块：只提亮、不改变色相")
	void emissiveKeepsHue() {
		int[] pixels = new int[16];
		java.util.Arrays.fill(pixels, OPAQUE | 0xFF8D22);

		int result = TextureAverage.averageBrightest(pixels);
		int red = rgb(result) >> 16;
		int green = (rgb(result) >> 8) & 0xFF;
		int blue = rgb(result) & 0xFF;

		assertTrue(TextureAverage.luminance(result) >= TextureAverage.luminance(0xFF8D22),
				"发光方块只应被提亮，不该被压暗");
		assertTrue(red > green && green > blue, "应仍是 R>G>B 的橙，色相不变");
	}

	@Test
	@DisplayName("发光方块：明暗连续的贴图也取偏亮的一档，而不是整张的平均")
	void emissiveHandlesGradient() {
		// 亮度从暗到亮连续过渡
		int[] pixels = new int[100];
		for (int i = 0; i < pixels.length; i++) {
			int level = i * 255 / (pixels.length - 1);
			pixels[i] = OPAQUE | level << 16 | level << 8 | level;
		}

		int result = TextureAverage.averageBrightest(pixels);

		// 分位线切在偏亮一侧，取到的不该只是整张的均值，更不该比普通取色还暗
		assertTrue(rgb(result) > 0xA0A0A0, "应取到偏亮的一档，当前为 " + Integer.toHexString(rgb(result)));
		assertTrue(rgb(result) >= rgb(TextureAverage.average(pixels)), "发光取色不应比普通平均还暗");
	}

	@Test
	@DisplayName("普通方块会被柔和化：不再是最初那个鲜艳色")
	void plainBlocksAreMutedAndSlightlyBrightened() {
		int[] pixels = new int[16];
		java.util.Arrays.fill(pixels, OPAQUE | 0xFF8D22);

		int result = TextureAverage.average(pixels);

		// 去饱和会把最鲜艳的橙往灰里收
		assertTrue(saturation(result) < saturation(0xFF8D22), "普通方块应被去饱和");
	}

	/** 粗略的"鲜艳度"：最大通道与最小通道之差。 */
	private static int saturation(int rgb) {
		int red = (rgb >> 16) & 0xFF;
		int green = (rgb >> 8) & 0xFF;
		int blue = rgb & 0xFF;
		return Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue));
	}

	@Test
	@DisplayName("感知明度按权重计算")
	void luminanceUsesWeights() {
		assertEquals(255, TextureAverage.luminance(0xFFFFFF));
		assertEquals(0, TextureAverage.luminance(0x000000));
		assertTrue(TextureAverage.luminance(0x00FF00) > TextureAverage.luminance(0x0000FF),
				"绿色比蓝色的感知明度更高");
	}
}
