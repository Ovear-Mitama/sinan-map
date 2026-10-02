package sinanmap.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * 自绘控件共用的配色与画法（扁平深底风格）：按钮、滑条、输入框都从这里取色，改一处整套界面一起变。
 */
public final class SinanStyle {

	/** 控件底色（半透明深色）。 */
	public static final int BACKGROUND = 0x90101014;

	/** 悬停 / 激活时的底色。 */
	public static final int BACKGROUND_ACTIVE = 0xC02A2A32;

	/** 常态暗边框：平时也描一圈，控件边界才清楚。 */
	public static final int BORDER_IDLE = 0x50000000;

	/** 悬停边框（比常态亮）。 */
	public static final int BORDER_HOVER = 0x90FFFFFF;

	/** 聚焦边框（最亮）。 */
	public static final int BORDER_FOCUS = 0xFFB4B4B4;

	/** 滑条把手：常态 / 悬停。 */
	public static final int KNOB = 0xFFB4B4B4;
	public static final int KNOB_ACTIVE = 0xFFFFFFFF;

	/** 把手宽度（细一点不笨重）。 */
	public static final int HANDLE_WIDTH = 3;

	/** 文字：正常 / 提示 / 禁用。 */
	public static final int TEXT = 0xFFE6E6E6;
	public static final int TEXT_HINT = 0xFF8A8A8A;
	public static final int TEXT_INACTIVE = 0xFF8A8A8A;

	private SinanStyle() {
	}

	/**
	 * 沿四边描 1 像素宽的框。
	 *
	 * <p>左右两条<b>刻意避开上下两条占的那两行</b>：四段画满会让四个角叠 4 次，半透明色显得更白。
	 */
	public static void outline(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int color) {
		graphics.fill(x, y, x + w, y + 1, color);
		graphics.fill(x, y + h - 1, x + w, y + h, color);
		graphics.fill(x, y + 1, x + 1, y + h - 1, color);
		graphics.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
	}

	/**
	 * 画一条自绘滑轨：<b>整高的底 + 一根细竖条把手</b>。
	 *
	 * <p><b>刻意不画中间的横向轨迹线</b>：矮控件里那条线显得脏，去掉后位置全靠把手读。
	 *
	 * @param value 0~1 的当前位置
	 */
	public static void drawSlider(GuiGraphicsExtractor graphics, int x, int y, int w, int h,
			double value, boolean active, boolean hovered) {
		drawSlider(graphics, x, y, w, h, value, active, hovered, 1.0F);
	}

	/**
	 * 同 {@link #drawSlider(GuiGraphicsExtractor, int, int, int, int, double, boolean, boolean)}，但整条
	 * 按 {@code fade} 压一次 alpha。
	 *
	 * <p>给"浮层展开时整块淡入"用：浮层里除了滑条还有文字与输入框，各自都要乘同一个进度，
	 * 整块才会一起显出来，而不是某几件先跳出来。
	 */
	public static void drawSlider(GuiGraphicsExtractor graphics, int x, int y, int w, int h,
			double value, boolean active, boolean hovered, float fade) {
		graphics.fill(x, y, x + w, y + h,
				fade(active && hovered ? BACKGROUND_ACTIVE : BACKGROUND, fade));

		// 把手：细竖条，中心随 value 滑动
		int knobWidth = Math.min(HANDLE_WIDTH, Math.max(2, w - 6));
		int travelLeft = x + 2;
		int travelRight = x + w - 2 - knobWidth;
		int knobX = travelLeft + (int) Math.round(Math.max(0, travelRight - travelLeft) * value);
		int knobColor = !active ? TEXT_INACTIVE : (hovered ? KNOB_ACTIVE : KNOB);
		graphics.fill(knobX, y + 2, knobX + knobWidth, y + h - 2, fade(knobColor, fade));

		// 边框：常态一圈暗的，悬停变亮
		outline(graphics, x, y, w, h, fade(hovered ? BORDER_HOVER : BORDER_IDLE, fade));
	}

	/** 把颜色的 alpha 乘上 {@code amount}（{@code amount = 1} 时原样返回）。 */
	public static int fade(int color, float amount) {
		int alpha = Math.round(((color >>> 24) & 0xFF) * Math.clamp(amount, 0.0F, 1.0F));
		return alpha << 24 | color & 0xFFFFFF;
	}
}
