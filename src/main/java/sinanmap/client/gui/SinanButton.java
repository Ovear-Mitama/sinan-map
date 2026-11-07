package sinanmap.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;

/**
 * 自绘按钮：<b>扁平、半透明深底 + 着色文字</b>，<b>没有底边高光</b>；平时描一圈暗边框、悬停变亮、聚焦最亮。
 *
 * <p>文字颜色取自 {@link Component} 的样式色（用于绿色确认 / 红色取消），没写就默认白；
 * 与 {@link SinanTextField}、{@link SinanSlider} 同一套样式。
 */
public class SinanButton extends AbstractButton {

	private final Runnable action;

	/** 整块淡入进度（0~1，默认 1），见 {@link SinanStyle#fade(int, float)}。 */
	private float fade = 1.0F;

	/**
	 * 常态是否用<b>更透的"磨砂"底</b>（而不是普通按钮底色）。
	 *
	 * <p>给设置页用：背景保持清晰（MC 的模糊只能整帧一次、没法只糊按钮），按钮用<b>半透明深色磨砂底</b>
	 * 让背后内容透出来；悬停再铺一层更亮的淡色，保证"点到哪儿"看得见。
	 */
	private boolean transparentBackground;

	/** 见 {@link #transparentBackground}：比 {@link SinanStyle#BACKGROUND} 更透的磨砂底。 */
	private static final int FROSTED_BACKGROUND = 0x70101014;

	public SinanButton(int x, int y, int width, int height, Component message, Runnable action) {
		super(x, y, width, height, message);
		this.action = action;
	}

	/**
	 * 整块淡入进度（0~1，默认 1）：给"浮层展开时整块淡入"用。
	 *
	 * <p>浮层里除了按钮还有底框与文字，各自都要乘同一个进度，整块才会一起显出来，
	 * 而不是底框还没出来、按钮先跳出来。
	 */
	public void setFade(float fade) {
		this.fade = Math.clamp(fade, 0.0F, 1.0F);
	}

	/** 见 {@link #transparentBackground}：true = 常态不铺底色（悬停仍有淡色）。 */
	public void setTransparentBackground(boolean transparentBackground) {
		this.transparentBackground = transparentBackground;
	}

	@Override
	public void onPress(InputWithModifiers modifiers) {
		action.run();
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int x = getX();
		int y = getY();
		int w = getWidth();
		int h = getHeight();
		boolean active = isActive();
		boolean hover = active && isHoveredOrFocused();

		int background = hover ? SinanStyle.BACKGROUND_ACTIVE
				: (transparentBackground ? FROSTED_BACKGROUND : SinanStyle.BACKGROUND);
		graphics.fill(x, y, x + w, y + h, SinanStyle.fade(background, fade));
		// 边框：常态就描一圈暗的，悬停变亮、聚焦最亮
		if (isFocused()) {
			SinanStyle.outline(graphics, x, y, w, h, SinanStyle.fade(SinanStyle.BORDER_FOCUS, fade));
		} else if (hover) {
			SinanStyle.outline(graphics, x, y, w, h, SinanStyle.fade(SinanStyle.BORDER_HOVER, fade));
		} else {
			SinanStyle.outline(graphics, x, y, w, h,
					SinanStyle.fade(SinanStyle.BORDER_BUTTON_IDLE, fade));
		}

		Font font = Minecraft.getInstance().font;
		int color = active ? textColor(getMessage()) : SinanStyle.TEXT_INACTIVE;
		graphics.centeredText(font, getMessage(), x + w / 2, y + (h - font.lineHeight + 1) / 2,
				SinanStyle.fade(color, fade));
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}

	/** 取组件样式里写的颜色，没写就用白。 */
	private static int textColor(Component message) {
		TextColor color = message.getStyle().getColor();
		return color == null ? 0xFFFFFFFF : (0xFF000000 | color.getValue());
	}
}
