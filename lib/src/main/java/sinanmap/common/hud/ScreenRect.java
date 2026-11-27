package sinanmap.common.hud;

/**
 * 屏幕上的像素矩形，原点为屏幕左上角。
 *
 * @param x      左边缘
 * @param y      上边缘
 * @param width  宽度
 * @param height 高度
 */
public record ScreenRect(int x, int y, int width, int height) {

	public int right() {
		return x + width;
	}

	public int bottom() {
		return y + height;
	}

	public boolean contains(int px, int py) {
		return px >= x && px < right() && py >= y && py < bottom();
	}
}
