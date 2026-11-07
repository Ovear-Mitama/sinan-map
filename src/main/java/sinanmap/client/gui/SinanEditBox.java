package sinanmap.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * 本模组样式的单行输入框：<b>原版 {@link EditBox} 的文字/光标/输入法</b> + <b>本模组的外壳</b>。
 *
 * <p><b>为什么还包一层 EditBox</b>：中文输入法（IME 预编辑浮层）与焦点/keyboard 转发都由原版
 * {@code EditBox} 管，自己从头写一个（如 {@link SinanTextField}）就要把这些全部重接一遍。
 *
 * <p><b>为什么要挪位置再包</b>：原版 {@code setBordered(false)} 之后文字纵向位置变成
 * {@code getY()}（<b>贴顶</b>），不会在框里居中——直接关掉边框，文字就会"跑上边"。
 * 所以这里把真正的 EditBox 缩成<b>一行字高</b>（{@value #LINE_HEIGHT} 像素）、放到视觉框的垂直正中，
 * 外壳（底色 + 边框）由本类按"视觉框"自己画。
 *
 * <p>鼠标命中按<b>视觉框</b>算（覆盖整个框，不是只有那一行字），所以点框内任意位置都能聚焦。
 */
public final class SinanEditBox extends EditBox {

	/** 一行字的高度：原版字体在 GUI 里就是 9 行高，这里取 8（字形高度）留 1 像素给光标。 */
	private static final int LINE_HEIGHT = 8;

	/** 文字相对视觉框的水平内边距（与原版有边框时一样是 3~4 像素）。 */
	private static final int PAD_X = 3;

	private final int visualX;
	private final int visualY;
	private final int visualWidth;
	private final int visualHeight;

	public SinanEditBox(Font font, int x, int y, int width, int height, Component message) {
		super(font, x + PAD_X, y + (height - LINE_HEIGHT) / 2, width - PAD_X * 2, LINE_HEIGHT, message);
		this.visualX = x;
		this.visualY = y;
		this.visualWidth = width;
		this.visualHeight = height;
		setBordered(false);
		setTextColor(SinanStyle.TEXT);
		setTextColorUneditable(SinanStyle.TEXT_INACTIVE);
	}

	/** 命中按视觉框算：点框里任意位置都能聚焦，而不是只有那一行字。 */
	@Override
	public boolean isMouseOver(double mouseX, double mouseY) {
		return mouseX >= visualX && mouseX < visualX + visualWidth
				&& mouseY >= visualY && mouseY < visualY + visualHeight;
	}

	@Override
	public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		if (!isVisible()) {
			return;
		}
		// 外壳：与 SinanTextField / SinanButton 同一套底色与边框，聚焦时亮边
		graphics.fill(visualX, visualY, visualX + visualWidth, visualY + visualHeight,
				isFocused() ? SinanStyle.BACKGROUND_ACTIVE : SinanStyle.BACKGROUND);
		SinanStyle.outline(graphics, visualX, visualY, visualWidth, visualHeight,
				isFocused() ? SinanStyle.BORDER_FOCUS : SinanStyle.BORDER_IDLE);
		// 文字/光标/输入法浮层：EditBox 已经缩到"一行字"的高度并落在正中，所以这里不会再贴顶
		super.extractWidgetRenderState(graphics, mouseX, mouseY, partialTick);
	}
}
