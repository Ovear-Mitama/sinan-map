package sinanmap.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/**
 * 自绘滑条基类：<b>原版滑条的形态（整高槽 + 竖条把手）+ 本模组的暗色配色</b>，不用原版 {@code widget/slider} 贴图。
 *
 * <p>画法集中在 {@link SinanStyle#drawSlider}，世界地图上的"显示层级"滑条复用同一份；
 * 本类只管"怎么画"，值域与挡位吸附交给子类（{@code StepSlider} / {@code SettingSlider}）。
 */
public abstract class SinanSlider extends AbstractSliderButton {

	protected SinanSlider(int x, int y, int width, int height, Component message, double value) {
		super(x, y, width, height, message, value);
	}

	@Override
	public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int x = getX();
		int y = getY();
		int w = getWidth();
		int h = getHeight();
		boolean active = isActive();
		boolean hover = active && isHoveredOrFocused();

		SinanStyle.drawSlider(graphics, x, y, w, h, value, active, hover);
		if (isFocused()) {
			// 聚焦时再套一圈亮边框，与按钮一致
			SinanStyle.outline(graphics, x, y, w, h, SinanStyle.BORDER_FOCUS);
		}

		Font font = Minecraft.getInstance().font;
		int textColor = active ? SinanStyle.TEXT : SinanStyle.TEXT_INACTIVE;
		graphics.centeredText(font, getMessage(), x + w / 2, y + (h - font.lineHeight + 1) / 2, textColor);
	}
}
