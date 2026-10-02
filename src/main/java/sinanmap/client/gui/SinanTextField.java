package sinanmap.client.gui;

import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

/**
 * 自绘的单行输入框：世界地图上的<b>路径点搜索框</b>与<b>坐标跳转框</b>共用同一个实现。
 *
 * <p>不用原版 {@code EditBox}：原版输入框的边框、底色、内边距与地图界面手画的一套对不上。
 *
 * <p>它<b>不是</b> GUI 控件（不继承 {@code AbstractWidget}，也不挂到 {@code Screen} 上），
 * 事件由 {@code WorldMapScreen} 在 {@code mouseClicked / keyPressed / charTyped} 里显式转进来；
 * "聚焦时不要把按键透传给地图"只需在调用方判断一次。
 *
 * <p>支持鼠标定位光标、左右方向键、Home/End、退格/Delete、Ctrl+A/C/X/V、
 * {@link #charTyped}、闪烁竖线光标、超出宽度时横向滚动。
 */
public final class SinanTextField {

	/** 内边距（左右各 {@value}）：文字与光标都留出这么多，别贴到框边上。 */
	private static final int PAD_X = 3;

	/** 样式统一取 {@link SinanStyle}，与自绘按钮 / 滑条看着是一家。 */
	private static final int BACKGROUND = SinanStyle.BACKGROUND;
	private static final int BACKGROUND_FOCUSED = SinanStyle.BACKGROUND_ACTIVE;
	private static final int BORDER_FOCUSED = SinanStyle.BORDER_FOCUS;
	private static final int TEXT_COLOR = SinanStyle.TEXT;
	private static final int HINT_COLOR = SinanStyle.TEXT_HINT;
	private static final int CURSOR_COLOR = 0xFFFFFFFF;

	/** 光标闪烁周期的一半（毫秒）：亮 300ms、灭 300ms。 */
	private static final long BLINK_MILLIS = 300L;

	private final Font font;

	private int x;
	private int y;
	private int width;
	private int height;

	private String text = "";
	private String hint = "";
	private int cursor;
	private int maxLength = Integer.MAX_VALUE;
	private int scroll;
	private boolean focused;
	private boolean frame = true;
	private float fade = 1.0F;
	private Consumer<String> responder;

	public SinanTextField(Font font) {
		this.font = font;
	}

	// ---------------------------------------------------------------- 状态

	public void setBounds(int x, int y, int width, int height) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
		// 宽度变了，之前那点横向滚动可能就不需要了，重算一次免得文字还偏着
		ensureCursorVisible();
	}

	public void setHint(Component hint) {
		this.hint = hint == null ? "" : hint.getString();
	}

	public void setMaxLength(int maxLength) {
		this.maxLength = Math.max(1, maxLength);
		if (text.length() > this.maxLength) {
			text = text.substring(0, this.maxLength);
			clampCursor();
		}
	}

	/** 输入内容变化的回调（搜索框用它实时过滤）。 */
	public void setResponder(Consumer<String> responder) {
		this.responder = responder;
	}

	public String getValue() {
		return text;
	}

	/** 直接写入内容（外部用当前值回填输入框时用）；光标挪到末尾，不动 responder。 */
	public void setValue(String value) {
		this.text = value == null ? "" : value;
		if (text.length() > maxLength) {
			text = text.substring(0, maxLength);
		}
		this.cursor = text.length();
		clampCursor();
		ensureCursorVisible();
	}

	public boolean isFocused() {
		return focused;
	}

	public void setFocused(boolean focused) {
		this.focused = focused;
	}

	/**
	 * 要不要画常态那圈暗边框：默认画；<b>嵌在别的条里时关掉</b>——底色与宿主一致、又不描边，
	 * 看上去才是"内嵌的一段"。聚焦时仍会套亮边，"能打字"照样看得见。
	 */
	public void setFrame(boolean frame) {
		this.frame = frame;
	}

	/**
	 * 整框的淡入进度（0~1，默认 1）。
	 *
	 * <p>给"浮层展开时整块淡入"用：浮层里除了输入框还有文字与滑条，各自都要乘同一个进度，
	 * 整块才会一起显出来，而不是某几件先跳出来。
	 */
	public void setFade(float fade) {
		this.fade = Math.clamp(fade, 0.0F, 1.0F);
	}

	public boolean isMouseOver(double mouseX, double mouseY) {
		return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
	}

	// ---------------------------------------------------------------- 鼠标

	/** 点进来了：聚焦并把光标放到点击处（相当于原版的 onClick）。 */
	public void mouseClicked(double mouseX, double mouseY) {
		focused = true;
		cursor = indexAt(mouseX);
		ensureCursorVisible();
	}

	private int indexAt(double mouseX) {
		int relative = (int) Math.round(mouseX) - (x + PAD_X) + scroll;
		if (relative <= 0) {
			return 0;
		}
		int acc = 0;
		for (int i = 0; i < text.length(); i++) {
			int charWidth = font.width(text.substring(i, i + 1));
			// 光标放到离点击处最近的那一格边界上：落在这一格右半边的，光标就在它后面
			if (relative < acc + (charWidth + 1) / 2) {
				return i;
			}
			acc += charWidth;
		}
		return text.length();
	}

	// ---------------------------------------------------------------- 键盘

	/**
	 * 处理一次按键。
	 *
	 * @return 有没有吃掉这个按键；{@code Enter} 一律不吃（交给调用方：跳转框要拿它提交坐标）
	 */
	public boolean keyPressed(KeyEvent event) {
		if (event.isPaste()) {
			insert(clipboard());
			return true;
		}
		if (event.isSelectAll()) {
			// 不做选区，退而求其次：全选 = 光标到末尾（配合退格就是"清空重打"）
			cursor = text.length();
			ensureCursorVisible();
			return true;
		}
		if (event.isCopy() || event.isCut()) {
			Minecraft.getInstance().keyboardHandler.setClipboard(text);
			if (event.isCut()) {
				setText("");
			}
			return true;
		}
		switch (event.input()) {
			case GLFW.GLFW_KEY_LEFT -> {
				cursor = Math.max(0, cursor - 1);
				ensureCursorVisible();
				return true;
			}
			case GLFW.GLFW_KEY_RIGHT -> {
				cursor = Math.min(text.length(), cursor + 1);
				ensureCursorVisible();
				return true;
			}
			case GLFW.GLFW_KEY_HOME -> {
				cursor = 0;
				ensureCursorVisible();
				return true;
			}
			case GLFW.GLFW_KEY_END -> {
				cursor = text.length();
				ensureCursorVisible();
				return true;
			}
			case GLFW.GLFW_KEY_BACKSPACE -> {
				if (cursor > 0) {
					text = text.substring(0, cursor - 1) + text.substring(cursor);
					cursor--;
					changed();
				}
				return true;
			}
			case GLFW.GLFW_KEY_DELETE -> {
				if (cursor < text.length()) {
					text = text.substring(0, cursor) + text.substring(cursor + 1);
					changed();
				}
				return true;
			}
			// 回车交给调用方（跳转框要用它提交、搜索框按了也不该有别的作用）
			case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
				return false;
			}
			default -> {
				return false;
			}
		}
	}

	/** 普通字符 / 中文输入法提交上来的字符：插到光标处。 */
	public boolean charTyped(CharacterEvent event) {
		if (!event.isAllowedChatCharacter()) {
			return false;
		}
		insert(event.codepointAsString());
		return true;
	}

	/** 往光标处插入一段文本（粘贴也走这里），超长就截断，控制字符丢掉。 */
	private void insert(String addition) {
		if (addition == null || addition.isEmpty()) {
			return;
		}
		StringBuilder clean = new StringBuilder(addition.length());
		for (int i = 0; i < addition.length(); i++) {
			char c = addition.charAt(i);
			if (c >= ' ' && c != 0x7F) {
				clean.append(c);
			}
		}
		if (clean.isEmpty()) {
			return;
		}
		int room = maxLength - text.length();
		if (room <= 0) {
			return;
		}
		String insert = clean.length() > room ? clean.substring(0, room) : clean.toString();
		text = text.substring(0, cursor) + insert + text.substring(cursor);
		cursor += insert.length();
		changed();
	}

	private void setText(String value) {
		text = value.length() > maxLength ? value.substring(0, maxLength) : value;
		clampCursor();
		changed();
	}

	private void changed() {
		clampCursor();
		ensureCursorVisible();
		if (responder != null) {
			responder.accept(text);
		}
	}

	private void clampCursor() {
		cursor = Math.clamp(cursor, 0, text.length());
	}

	private static String clipboard() {
		String value = Minecraft.getInstance().keyboardHandler.getClipboard();
		return value == null ? "" : value;
	}

	/**
	 * 让光标始终留在可见范围内：跑到右边就跟着滚，回到左边就滚回来。滚动量以像素计，只在文字比框宽时才大于 0。
	 */
	private void ensureCursorVisible() {
		int inner = Math.max(1, width - PAD_X * 2);
		int cursorX = font.width(text.substring(0, cursor));
		if (cursorX - scroll > inner) {
			scroll = cursorX - inner;
		} else if (cursorX - scroll < 0) {
			scroll = cursorX;
		}
		// 文字本身还没那么长时不该有滚动量，否则光标会停在"半截空白"上
		int overflow = Math.max(0, font.width(text) - inner);
		scroll = Math.clamp(scroll, 0, overflow);
	}

	// ---------------------------------------------------------------- 绘制

	/**
	 * 画这个输入框：底色 + 边框 + 文字（空着时是提示）+ 闪烁的光标。
	 *
	 * <p>每一处颜色都过一遍 {@link #fade}：嵌入浮层时整框要跟着浮层一起淡入（见 {@link #setFade(float)}）。
	 */
	public void render(GuiGraphicsExtractor graphics) {
		graphics.fill(x, y, x + width, y + height,
				SinanStyle.fade(focused ? BACKGROUND_FOCUSED : BACKGROUND, fade));
		// 边框：常态一圈暗的（可关掉，见 setFrame）、聚焦最亮，与自绘按钮同一套表现
		if (focused) {
			SinanStyle.outline(graphics, x, y, width, height, SinanStyle.fade(BORDER_FOCUSED, fade));
		} else if (frame) {
			SinanStyle.outline(graphics, x, y, width, height, SinanStyle.fade(SinanStyle.BORDER_IDLE, fade));
		}

		int innerX = x + PAD_X;
		int innerRight = x + width - PAD_X;
		int textY = y + (height - font.lineHeight + 1) / 2;

		// 超出宽度时靠裁剪 + 横向滚动显示：文字左边界整体左移 scroll
		graphics.enableScissor(innerX, y, innerRight, y + height);
		if (text.isEmpty() && !hint.isEmpty()) {
			graphics.text(font, hint, innerX, textY, SinanStyle.fade(HINT_COLOR, fade), false);
		} else {
			graphics.text(font, text, innerX - scroll, textY, SinanStyle.fade(TEXT_COLOR, fade), false);
		}
		if (focused && cursorVisible()) {
			int cursorX = innerX - scroll + font.width(text.substring(0, cursor));
			graphics.fill(cursorX, textY - 1, cursorX + 1, textY + font.lineHeight,
					SinanStyle.fade(CURSOR_COLOR, fade));
		}
		graphics.disableScissor();
	}

	/** 光标闪烁：以半周期取反，亮灭各占 {@link #BLINK_MILLIS}。 */
	private static boolean cursorVisible() {
		return System.currentTimeMillis() / BLINK_MILLIS % 2L == 0L;
	}
}
