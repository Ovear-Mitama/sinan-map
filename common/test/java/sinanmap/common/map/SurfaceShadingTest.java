package sinanmap.common.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SurfaceShadingTest {

	private static final int REFERENCE = 63;

	@Test
	@DisplayName("高度项：参考高度为 1.0，越高越亮、越低越暗，并被钳位")
	void heightFactorBehaves() {
		assertEquals(1.0, SurfaceShading.heightFactor(REFERENCE, REFERENCE), 1.0e-9);
		assertTrue(SurfaceShading.heightFactor(REFERENCE + 40, REFERENCE) > 1.0);
		assertTrue(SurfaceShading.heightFactor(REFERENCE - 40, REFERENCE) < 1.0);

		assertEquals(SurfaceShading.MIN_HEIGHT_FACTOR, SurfaceShading.heightFactor(-500, REFERENCE), 1.0e-9);
		assertEquals(SurfaceShading.MAX_HEIGHT_FACTOR, SurfaceShading.heightFactor(500, REFERENCE), 1.0e-9);
	}

	@Test
	@DisplayName("光照平滑：均匀场不变，硬边变成过渡")
	void smoothLightSoftensEdges() {
		int size = 8;
		int[] uniform = new int[size * size];
		java.util.Arrays.fill(uniform, 7);
		int[] uniformOut = new int[size * size];
		SurfaceShading.smoothLight(uniform, uniformOut, size);
		for (int value : uniformOut) {
			assertEquals(7, value, "均匀的光照场不该被改变");
		}

		// 左半 0、右半 15 的一条硬边
		int[] step = new int[size * size];
		for (int z = 0; z < size; z++) {
			for (int x = 0; x < size; x++) {
				step[z * size + x] = x < size / 2 ? 0 : 15;
			}
		}
		int[] stepOut = new int[size * size];
		SurfaceShading.smoothLight(step, stepOut, size);

		int leftOfEdge = stepOut[3 * size + (size / 2 - 1)];
		int rightOfEdge = stepOut[3 * size + (size / 2)];
		assertTrue(leftOfEdge > 0, "边界左侧该被提亮一点，当前为 " + leftOfEdge);
		assertTrue(rightOfEdge < 15, "边界右侧该被压暗一点，当前为 " + rightOfEdge);
		assertTrue(leftOfEdge < rightOfEdge, "整体仍是右亮左暗，方向没变");
		assertEquals(0, stepOut[0], "远离边界的地方不该被波及");
		assertEquals(15, stepOut[size - 1], "远离边界的地方不该被波及");
	}

	@Test
	@DisplayName("光照项：满亮为 1.0，全黑为最低倍率，中间单调递增")
	void lightFactorBehaves() {
		assertEquals(1.0, SurfaceShading.lightFactor(15), 1.0e-9);
		assertEquals(SurfaceShading.MIN_LIGHT_FACTOR, SurfaceShading.lightFactor(0), 1.0e-9);

		double previous = -1.0;
		for (int light = 0; light <= 15; light++) {
			double factor = SurfaceShading.lightFactor(light);
			assertTrue(factor >= previous, "光照等级 " + light + " 的亮度不应低于更低等级");
			previous = factor;
		}

		assertEquals(SurfaceShading.MIN_LIGHT_FACTOR, SurfaceShading.lightFactor(-3), 1.0e-9, "非法低级光照按 0 处理");
		assertEquals(1.0, SurfaceShading.lightFactor(99), 1.0e-9, "非法高级光照按 15 处理");
	}

	@Test
	@DisplayName("坡面项：平地不改变亮度")
	void flatTerrainHasNoSlopeShading() {
		assertEquals(1.0, SurfaceShading.slopeFactor(70, 70, 70), 1.0e-9);
	}

	@Test
	@DisplayName("坡面项：西侧或北侧更高时本列变暗，更低时变亮")
	void slopeFactorFollowsLightDirection() {
		double plain = SurfaceShading.slopeFactor(70, 70, 70);

		double facingEast = SurfaceShading.slopeFactor(70, 75, 70);
		double facingSouth = SurfaceShading.slopeFactor(70, 70, 75);
		assertTrue(facingEast < plain, "西侧更高（本列朝东）应更暗");
		assertTrue(facingSouth < plain, "北侧更高（本列朝南）应更暗");

		double facingNorthWest = SurfaceShading.slopeFactor(70, 65, 65);
		assertTrue(facingNorthWest > plain, "西北侧更低应更亮");
	}

	@Test
	@DisplayName("坡面项被钳位，悬崖不会变成纯黑或纯白")
	void slopeFactorIsClamped() {
		assertEquals(SurfaceShading.MIN_SLOPE_FACTOR, SurfaceShading.slopeFactor(0, 100, 100), 1.0e-9);
		assertEquals(SurfaceShading.MAX_SLOPE_FACTOR, SurfaceShading.slopeFactor(100, 0, 0), 1.0e-9);
	}

	@Test
	@DisplayName("综合着色：亮处比暗处亮，结果始终不透明")
	void combinedShadingOrdersBrightness() {
		int bright = SurfaceShading.shade(0x808080, REFERENCE + 20, REFERENCE, 15, REFERENCE, REFERENCE);
		int dark = SurfaceShading.shade(0x808080, REFERENCE - 20, REFERENCE, 4, REFERENCE + 10, REFERENCE + 10);

		assertEquals(0xFF000000, bright & 0xFF000000);
		assertEquals(0xFF000000, dark & 0xFF000000);
		assertTrue(brightness(bright) > brightness(dark));
	}

	@Test
	@DisplayName("通道不会互相串位")
	void channelsDoNotBleed() {
		assertEquals(0x102030, SurfaceShading.scale(0x102030, 1.0) & 0xFFFFFF);
	}

	private static int brightness(int argb) {
		return ((argb >> 16) & 0xFF) + ((argb >> 8) & 0xFF) + (argb & 0xFF);
	}
}
