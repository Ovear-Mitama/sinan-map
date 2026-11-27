package sinanmap.common.hud;

/**
 * 依据锚点、偏移、小地图尺寸与屏幕尺寸计算它在屏幕上的矩形。
 *
 * <p>纯函数。所有结果都会被钳位在屏幕内，因此即使尺寸大于屏幕或偏移写得很大，也不会画到屏幕外。
 */
public final class MinimapLayout {

	/** 与屏幕边缘的默认间距（像素）。 */
	public static final int DEFAULT_MARGIN = 4;

	private MinimapLayout() {
	}

	/** 使用默认边距计算布局。 */
	public static ScreenRect compute(ScreenAnchor anchor, int offsetX, int offsetY,
			int width, int height, int screenWidth, int screenHeight) {
		return compute(anchor, offsetX, offsetY, width, height, screenWidth, screenHeight, DEFAULT_MARGIN);
	}

	/**
	 * @param anchor       吸附的角
	 * @param offsetX      自吸附角向内（右/左）的额外偏移
	 * @param offsetY      自吸附角向内（下/上）的额外偏移
	 * @param width        小地图宽度（GUI 像素）
	 * @param height       小地图高度（GUI 像素）
	 * @param screenWidth  屏幕可用宽度（GUI 缩放后的坐标）
	 * @param screenHeight 屏幕可用高度
	 * @param margin       与屏幕边缘的间距
	 * @return 钳位后的矩形；屏幕尺寸非法时返回 1x1 的退化矩形
	 */
	public static ScreenRect compute(ScreenAnchor anchor, int offsetX, int offsetY,
			int width, int height, int screenWidth, int screenHeight, int margin) {
		if (screenWidth <= 0 || screenHeight <= 0) {
			return new ScreenRect(0, 0, 1, 1);
		}

		// 尺寸不能超过屏幕，否则钳位后会出现负的位置
		int w = Math.clamp(width, 1, screenWidth);
		int h = Math.clamp(height, 1, screenHeight);

		int x = anchor.isRight()
				? screenWidth - margin - offsetX - w
				: margin + offsetX;
		int y = anchor.isBottom()
				? screenHeight - margin - offsetY - h
				: margin + offsetY;

		return new ScreenRect(
				Math.clamp(x, 0, Math.max(0, screenWidth - w)),
				Math.clamp(y, 0, Math.max(0, screenHeight - h)),
				w,
				h);
	}
}
