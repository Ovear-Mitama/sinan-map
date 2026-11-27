package sinanmap.common.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MinimapLayoutTest {

	private static final int SCREEN_W = 640;
	private static final int SCREEN_H = 360;

	@Test
	@DisplayName("右上角：贴右边与上边，留出默认边距")
	void topRight() {
		ScreenRect rect = MinimapLayout.compute(ScreenAnchor.TOP_RIGHT, 0, 0, 160, 110, SCREEN_W, SCREEN_H);

		assertEquals(SCREEN_W - MinimapLayout.DEFAULT_MARGIN - 160, rect.x());
		assertEquals(MinimapLayout.DEFAULT_MARGIN, rect.y());
		assertEquals(160, rect.width());
		assertEquals(110, rect.height());
	}

	@Test
	@DisplayName("四个角都能正确落位")
	void allAnchors() {
		assertEquals(MinimapLayout.DEFAULT_MARGIN,
				MinimapLayout.compute(ScreenAnchor.TOP_LEFT, 0, 0, 160, 110, SCREEN_W, SCREEN_H).x());
		assertEquals(SCREEN_W - MinimapLayout.DEFAULT_MARGIN,
				MinimapLayout.compute(ScreenAnchor.TOP_RIGHT, 0, 0, 160, 110, SCREEN_W, SCREEN_H).right());
		assertEquals(SCREEN_H - MinimapLayout.DEFAULT_MARGIN,
				MinimapLayout.compute(ScreenAnchor.BOTTOM_LEFT, 0, 0, 160, 110, SCREEN_W, SCREEN_H).bottom());
		assertEquals(SCREEN_H - MinimapLayout.DEFAULT_MARGIN,
				MinimapLayout.compute(ScreenAnchor.BOTTOM_RIGHT, 0, 0, 160, 110, SCREEN_W, SCREEN_H).bottom());
	}

	@Test
	@DisplayName("偏移把地图往屏幕内推")
	void offsetsMoveInward() {
		ScreenRect rect = MinimapLayout.compute(ScreenAnchor.TOP_RIGHT, 20, 10, 160, 110, SCREEN_W, SCREEN_H);

		assertEquals(SCREEN_W - 4 - 20 - 160, rect.x());
		assertEquals(4 + 10, rect.y());
	}

	@Test
	@DisplayName("尺寸大于屏幕时钳位在屏幕内")
	void oversizeIsClamped() {
		ScreenRect rect = MinimapLayout.compute(ScreenAnchor.TOP_LEFT, 0, 0, 5000, 5000, SCREEN_W, SCREEN_H);

		assertEquals(0, rect.x());
		assertEquals(0, rect.y());
		assertEquals(SCREEN_W, rect.width());
		assertEquals(SCREEN_H, rect.height());
	}

	@Test
	@DisplayName("偏移过大也不会跑到屏幕外")
	void hugeOffsetStaysOnScreen() {
		ScreenRect rect = MinimapLayout.compute(ScreenAnchor.BOTTOM_RIGHT, 100000, 100000, 160, 110, SCREEN_W, SCREEN_H);

		// 偏移被钳位到屏幕内，而不是原样生效导致整块跑到屏幕外
		assertTrue(rect.x() >= 0, "x 不应为负");
		assertTrue(rect.y() >= 0, "y 不应为负");
		assertTrue(rect.right() <= SCREEN_W, "右边缘不应超出屏幕");
		assertTrue(rect.bottom() <= SCREEN_H, "下边缘不应超出屏幕");
	}

	@Test
	@DisplayName("屏幕尺寸非法时返回退化矩形而不是抛异常")
	void invalidScreenReturnsDegenerateRect() {
		ScreenRect rect = MinimapLayout.compute(ScreenAnchor.TOP_LEFT, 0, 0, 160, 110, 0, 0);

		assertEquals(0, rect.x());
		assertEquals(0, rect.y());
		assertEquals(1, rect.width());
		assertEquals(1, rect.height());
	}
}
