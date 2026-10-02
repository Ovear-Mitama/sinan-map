package sinanmap.client.map.settings;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

import net.minecraft.network.chat.Component;

import sinanmap.client.gui.SinanSlider;

/**
 * 连续数值滑条（自绘样式，见 {@link SinanSlider}）：内部存 0~1，本类换算成真实值。
 */
final class SettingSlider extends SinanSlider {

	private final Component label;
	private final double min;
	private final double max;
	private final DoubleFunction<String> formatter;
	private final DoubleConsumer onChange;

	SettingSlider(int x, int y, int width, int height, Component label,
			double min, double max, double value,
			DoubleFunction<String> formatter, DoubleConsumer onChange) {
		super(x, y, width, height, label, normalize(value, min, max));
		this.label = label;
		this.min = min;
		this.max = max;
		this.formatter = formatter;
		this.onChange = onChange;
		updateMessage();
	}

	private static double normalize(double value, double min, double max) {
		return max <= min ? 0.0 : Math.clamp((value - min) / (max - min), 0.0, 1.0);
	}

	private double actualValue() {
		return min + (max - min) * value;
	}

	@Override
	protected void updateMessage() {
		setMessage(Component.literal(label.getString() + ": " + formatter.apply(actualValue())));
	}

	@Override
	protected void applyValue() {
		onChange.accept(actualValue());
	}
}
