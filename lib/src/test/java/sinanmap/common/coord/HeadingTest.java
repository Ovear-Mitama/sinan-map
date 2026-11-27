package sinanmap.common.coord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HeadingTest {

	private static final double EPSILON = 1.0e-6;

	@Test
	@DisplayName("前向向量按 yaw 指向正确方向")
	void forwardVectorPointsCorrectly() {
		double[][] expected = {
				{0.0, 1.0},    // yaw 0 → 正南
				{-1.0, 0.0},   // yaw 90 → 正西
				{0.0, -1.0},   // yaw 180 → 正北
				{1.0, 0.0},    // yaw 270 → 正东
		};
		float[] yaws = {0.0F, 90.0F, 180.0F, 270.0F};

		for (int i = 0; i < yaws.length; i++) {
			assertEquals(expected[i][0], Heading.forwardX(yaws[i]), EPSILON, "yaw=" + yaws[i]);
			assertEquals(expected[i][1], Heading.forwardZ(yaws[i]), EPSILON, "yaw=" + yaws[i]);
		}
	}

	@ParameterizedTest
	@ValueSource(floats = {0.0F, 37.5F, 90.0F, 180.0F, 270.0F, 359.5F, -90.0F})
	@DisplayName("跟随模式下玩家正前方始终指向屏幕正上方")
	void followModePointsForwardUp(float yaw) {
		ScreenPoint direction = Heading.screenDirection(Heading.followRotation(yaw), yaw);

		assertEquals(0.0, direction.x(), EPSILON, "yaw=" + yaw);
		assertEquals(-1.0, direction.y(), EPSILON, "yaw=" + yaw);
	}

	@Test
	@DisplayName("正北朝上时箭头随视角转动")
	void northUpModeKeepsArrowRelativeToWorld() {
		ScreenPoint facingSouth = Heading.screenDirection(0.0F, 0.0F);
		assertEquals(0.0, facingSouth.x(), EPSILON);
		assertEquals(1.0, facingSouth.y(), EPSILON, "面向正南时箭头朝屏幕下方");

		ScreenPoint facingWest = Heading.screenDirection(0.0F, 90.0F);
		assertEquals(-1.0, facingWest.x(), EPSILON, "面向正西时箭头朝屏幕左方");
		assertEquals(0.0, facingWest.y(), EPSILON);
	}

	@Test
	@DisplayName("箭头方向始终是单位向量")
	void directionStaysNormalized() {
		for (float yaw = -180.0F; yaw <= 180.0F; yaw += 15.0F) {
			ScreenPoint direction = Heading.screenDirection(Heading.followRotation(yaw), yaw);
			assertEquals(1.0, Math.hypot(direction.x(), direction.y()), EPSILON, "yaw=" + yaw);
		}
	}

	@Test
	@DisplayName("非有限 yaw 被拒绝")
	void nonFiniteYawIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> Heading.followRotation(Float.NaN));
	}
}
