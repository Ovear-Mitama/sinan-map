package sinanmap.client.gui;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * 自绘"循环切换"按钮：外观沿用 {@link SinanButton}，左键切到下一个值、右键切回上一个值。
 *
 * <p>替掉原版 {@code CycleButton}（它的灰底贴图与自绘控件不搭）；开关传一个布尔值列表即可。
 */
public final class SinanCycleButton<T> extends SinanButton {

	private final Component label;
	private final List<T> values;
	private final Function<T, String> valueText;
	private final Consumer<T> onChange;

	private T value;

	public SinanCycleButton(int x, int y, int width, int height, Component label,
			List<T> values, T value, Function<T, String> valueText, Consumer<T> onChange) {
		super(x, y, width, height, label, () -> {
			// 真正的动作在 onPress / mouseClicked 里，这里只是占位
		});
		this.label = label;
		this.values = values;
		this.valueText = valueText;
		this.onChange = onChange;
		this.value = value;
		refreshMessage();
	}

	/** 左键：切到下一个值。 */
	@Override
	public void onPress(InputWithModifiers modifiers) {
		cycle(1);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 1 && isMouseOver(event.x(), event.y())) {
			// 右键：切回上一个值（与原版 CycleButton 的手感一致）
			cycle(-1);
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	private void cycle(int step) {
		if (values.isEmpty()) {
			return;
		}
		int index = values.indexOf(value);
		if (index < 0) {
			index = 0;
		}
		T next = values.get(Math.floorMod(index + step, values.size()));
		value = next;
		refreshMessage();
		onChange.accept(next);
	}

	/** 显示成"标签: 当前值"。 */
	private void refreshMessage() {
		setMessage(Component.literal(label.getString() + ": " + valueText.apply(value)));
	}
}
