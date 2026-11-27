package sinanmap.common.hud;

/**
 * 小地图吸附到屏幕的哪个角。
 */
public enum ScreenAnchor {

	TOP_LEFT,
	TOP_RIGHT,
	BOTTOM_LEFT,
	BOTTOM_RIGHT;

	/** 是否贴屏幕右侧。 */
	public boolean isRight() {
		return this == TOP_RIGHT || this == BOTTOM_RIGHT;
	}

	/** 是否贴屏幕底部。 */
	public boolean isBottom() {
		return this == BOTTOM_LEFT || this == BOTTOM_RIGHT;
	}
}
