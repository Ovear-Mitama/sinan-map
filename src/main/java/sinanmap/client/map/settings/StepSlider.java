package sinanmap.client.map.settings;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import net.minecraft.network.chat.Component;

import sinanmap.client.gui.SinanSlider;

/**
 * 挡位滑条（自绘样式，见 {@link SinanSlider}）：<b>最左一挡固定是"自动"</b>，往右依次变大；
 * 拖动时吸附到最近一挡，显示成"标签: 数值（自动）"或"标签: 数值"。
 */
final class StepSlider<T> extends SinanSlider {

	private final Component label;
	private final List<T> steps;
	private final Function<T, String> valueText;
	private final Consumer<T> onChange;

	StepSlider(int x, int y, int width, int height, Component label, List<T> steps,
			T value, Function<T, String> valueText, Consumer<T> onChange) {
		super(x, y, width, height, label, normalize(value, steps));
		this.label = label;
		this.steps = steps;
		this.valueText = valueText;
		this.onChange = onChange;
		updateMessage();
	}

	/** 当前值落在第几挡（值不在挡位上时退到"自动"）。 */
	private static <T> double normalize(T value, List<T> steps) {
		int index = Math.max(0, steps.indexOf(value));
		return steps.size() <= 1 ? 0.0 : index / (double) (steps.size() - 1);
	}

	/** 拖动位置换算成挡位下标（四舍五入，所以会吸附）。 */
	private int index() {
		return Math.clamp((int) Math.round(value * (steps.size() - 1)), 0, steps.size() - 1);
	}

	@Override
	protected void updateMessage() {
		setMessage(Component.literal(label.getString() + ": " + valueText.apply(steps.get(index()))));
	}

	@Override
	protected void applyValue() {
		// 吸附：把连续位置压回挡位，手柄就不会停在两挡之间
		value = steps.size() <= 1 ? 0.0 : index() / (double) (steps.size() - 1);
		onChange.accept(steps.get(index()));
	}
}
