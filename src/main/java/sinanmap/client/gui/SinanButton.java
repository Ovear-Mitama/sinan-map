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

	public SinanButton(int x, int y, int width, int height, Component message, Runnable action) {
		super(x, y, width, height, message);
		this.action = action;
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

		graphics.fill(x, y, x + w, y + h, hover ? SinanStyle.BACKGROUND_ACTIVE : SinanStyle.BACKGROUND);
		// 边框：常态就描一圈暗的，悬停变亮、聚焦最亮
		if (isFocused()) {
			SinanStyle.outline(graphics, x, y, w, h, SinanStyle.BORDER_FOCUS);
		} else if (hover) {
			SinanStyle.outline(graphics, x, y, w, h, SinanStyle.BORDER_HOVER);
		} else {
			SinanStyle.outline(graphics, x, y, w, h, SinanStyle.BORDER_IDLE);
		}

		Font font = Minecraft.getInstance().font;
		int color = active ? textColor(getMessage()) : SinanStyle.TEXT_INACTIVE;
		graphics.centeredText(font, getMessage(), x + w / 2, y + (h - font.lineHeight + 1) / 2, color);
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
