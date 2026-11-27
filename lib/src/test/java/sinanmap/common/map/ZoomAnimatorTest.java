package sinanmap.common.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ZoomAnimatorTest {

	@Test
	@DisplayName("初始值即当前值与目标值，且不在动画中")
	void startsSettled() {
		ZoomAnimator animator = new ZoomAnimator(1.0);

		assertEquals(1.0, animator.current());
		assertEquals(1.0, animator.target());
		assertFalse(animator.isAnimating());
	}

	@Test
	@DisplayName("逐帧逼近目标，不会一次跳到位")
	void approachesTargetGradually() {
		ZoomAnimator animator = new ZoomAnimator(1.0);
		animator.setTarget(0.5);

		boolean animating = animator.update(16.0);
		assertTrue(animating, "一帧之后应仍在动画中");
		assertTrue(animator.current() < 1.0, "应向目标逼近");
		assertTrue(animator.current() > 0.5, "不应一帧到位");
	}

	@Test
	@DisplayName("足够时间后精确吸附到目标值")
	void snapsToTarget() {
		ZoomAnimator animator = new ZoomAnimator(1.0);
		animator.setTarget(0.25);

		for (int i = 0; i < 200; i++) {
			animator.update(8.0);
		}

		assertEquals(0.25, animator.current());
		assertFalse(animator.isAnimating());
	}

	@Test
	@DisplayName("单调逼近，不会越过目标来回振荡")
	void convergesMonotonicallyForZoomIn() {
		ZoomAnimator animator = new ZoomAnimator(1.0);
		animator.setTarget(0.5);

		double previous = animator.current();
		for (int i = 0; i < 50; i++) {
			animator.update(8.0);
			double now = animator.current();
			assertTrue(now <= previous + 1.0e-9, "放大过程中缩放值应单调下降");
			assertTrue(now >= 0.5 - 1.0e-9, "不应缩过目标值");
			previous = now;
		}
	}

	@Test
	@DisplayName("snapTo 立即到位，用于首次打开界面")
	void snapToJumpsImmediately() {
		ZoomAnimator animator = new ZoomAnimator(1.0);

		animator.snapTo(2.5);

		assertEquals(2.5, animator.current());
		assertFalse(animator.isAnimating());
	}

	@Test
	@DisplayName("长帧不会让动画一步到位（单帧步长封顶）")
	void longFrameDoesNotJumpToTarget() {
		ZoomAnimator longFrame = new ZoomAnimator(1.0);
		longFrame.setTarget(0.25);
		// 重合成一整张贴图会带来几百毫秒的长帧，不封顶的话这一帧就跳到目标值了
		longFrame.update(500.0);

		ZoomAnimator oneStep = new ZoomAnimator(1.0);
		oneStep.setTarget(0.25);
		oneStep.update(ZoomAnimator.MAX_STEP_MILLIS);

		assertEquals(oneStep.current(), longFrame.current(), 1.0e-9, "超长帧应与一帧上限等效");
		assertTrue(longFrame.current() > 0.5, "长帧之后应仍在动画中，而不是直接到位");
		assertTrue(longFrame.isAnimating());
	}

	@Test
	@DisplayName("snapCurrent 只弹当前值，仍会动画回到原目标（切层过场）")
	void snapCurrentKeepsTarget() {
		ZoomAnimator animator = new ZoomAnimator(1.0);

		// 切换层级：先拉远到 2 倍，再让动画推回原来那一档
		animator.snapCurrent(2.0);

		assertEquals(1.0, animator.target(), "目标值不该被过场改掉，否则动画没有终点");
		assertTrue(animator.isAnimating(), "弹出后应处于动画中");
		for (int i = 0; i < 200; i++) {
			animator.update(8.0);
		}
		assertEquals(1.0, animator.current(), "过场结束后应回到原来那一档");
	}

	@Test
	@DisplayName("目标被连续改变时仍然收敛到最后的目标")
	void followsChangingTarget() {
		ZoomAnimator animator = new ZoomAnimator(1.0);

		// 模拟连续滚轮：每帧都改目标
		for (int i = 0; i < 10; i++) {
			animator.setTarget(animator.target() / 1.25);
			animator.update(16.0);
		}
		for (int i = 0; i < 200; i++) {
			animator.update(8.0);
		}

		assertEquals(animator.target(), animator.current());
		assertTrue(animator.target() < 0.4, "连续放大后目标应明显变小");
	}
}
