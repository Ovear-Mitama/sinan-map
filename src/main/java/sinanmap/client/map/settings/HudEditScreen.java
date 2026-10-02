package sinanmap.client.map.settings;

import com.mojang.blaze3d.platform.cursor.CursorType;
import com.mojang.blaze3d.platform.cursor.CursorTypes;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import sinanmap.common.hud.MinimapLayout;
import sinanmap.common.hud.MinimapSettings;
import sinanmap.common.hud.ScreenAnchor;
import sinanmap.common.hud.ScreenRect;
import sinanmap.client.gui.SinanButton;
import sinanmap.client.map.MinimapHud;

/**
 * HUD 编辑界面（设置里"编辑 HUD"打开）。
 *
 * <p><b>独立成页、不铺底色</b>：位置必须对着真实画面调才准，世界画面直接透出，
 * 小地图画在它真正待着的位置，拖的就是 {@link MinimapHud#renderPreview} 渲染的那一份。
 *
 * <p><b>八个拖点</b>（四角 + 四边中点）：拖角<b>按长宽比</b>同时改宽高，拖边中点只改那一条边、对边不动；
 * 悬停时切换对应的缩放光标。
 */
public final class HudEditScreen extends Screen {

	/** 拖点命中框的边长。 */
	private static final int HANDLE_HIT = 9;
	/** 拖点画出来的小方块边长。 */
	private static final int HANDLE_SIZE = 5;

	/**
	 * 斜向缩放光标：{@link CursorTypes} 只有上下/左右/全向这三个，斜向两个按 GLFW 标准光标自建。
	 *
	 * <p>id 取 GLFW 的取值：{@code 0x36007} = 左上↔右下（沿对角线调整大小 1）、
	 * {@code 0x36008} = 右上↔左下（…2）。<b>别写成 0x36006</b>——那一位是"上下缩放"：
	 * 之前两个 id 整体错了一位（0x36006/0x36007），于是左上/右下角画出来是竖箭头、
	 * 右上/左下角画成了左上↔右下。
	 */
	private static final CursorType RESIZE_NWSE =
			CursorType.createStandardCursor(0x00036007, "resize_nwse", CursorTypes.RESIZE_ALL);
	private static final CursorType RESIZE_NESW =
			CursorType.createStandardCursor(0x00036008, "resize_nesw", CursorTypes.RESIZE_ALL);

	/** 八个拖点：0..3 依次是左上/上/右上/右，4..7 依次是右下/下/左下/左。 */
	private static final int NW = 0;
	private static final int N = 1;
	private static final int NE = 2;
	private static final int E = 3;
	private static final int SE = 4;
	private static final int S = 5;
	private static final int SW = 6;
	private static final int W = 7;
	private static final int HANDLE_COUNT = 8;

	private final Screen parent;
	private final MinimapHud minimap;
	private final MinimapSettings settings;

	/** 正在拖哪个拖点（-1 = 没拖）。 */
	private int draggingHandle = -1;
	/** 是否正在拖本体（挪位置）。 */
	private boolean draggingBody;
	private int grabX;
	private int grabY;
	/** 按下那一刻的小地图矩形：缩放都以它为基准，避免逐帧累积误差。 */
	private ScreenRect dragStart = new ScreenRect(0, 0, 1, 1);

	public HudEditScreen(@Nullable Screen parent, MinimapHud minimap, MinimapSettings settings) {
		super(Component.translatable("screen.sinanmap.hud_edit"));
		this.parent = parent;
		this.minimap = minimap;
		this.settings = settings;
	}

	@Override
	protected void init() {
		draggingHandle = -1;
		draggingBody = false;
		// 宽高不放滑条，改用方向键微调，见 #keyPressed
		addRenderableWidget(new SinanButton(this.width / 2 - 45, this.height - 24, 90, 20,
				Component.translatable("screen.sinanmap.minimap_settings.done")
						.withColor(COLOR_CONFIRM),
				this::onClose));
	}

	/** 小地图这一帧真正的矩形（与 HUD 用的是同一套计算）。 */
	private ScreenRect minimapRect() {
		return MinimapLayout.compute(settings.anchor(), settings.offsetX(), settings.offsetY(),
				settings.width(), settings.height(), width, height);
	}

	/** 第 {@code handle} 个拖点的中心。 */
	private int handleX(int handle, ScreenRect rect) {
		return switch (handle) {
			case NW, W, SW -> rect.x();
			case N, S -> rect.x() + rect.width() / 2;
			default -> rect.x() + rect.width();
		};
	}

	private int handleY(int handle, ScreenRect rect) {
		return switch (handle) {
			case NW, N, NE -> rect.y();
			case W, E -> rect.y() + rect.height() / 2;
			default -> rect.y() + rect.height();
		};
	}

	/** 光标落在哪个拖点上（-1 = 没有）。 */
	private int handleAt(double mouseX, double mouseY, ScreenRect rect) {
		for (int handle = 0; handle < HANDLE_COUNT; handle++) {
			int hx = handleX(handle, rect) - HANDLE_HIT / 2;
			int hy = handleY(handle, rect) - HANDLE_HIT / 2;
			if (inside(mouseX, mouseY, hx, hy, HANDLE_HIT, HANDLE_HIT)) {
				return handle;
			}
		}
		return -1;
	}

	/** 拖点对应的鼠标样式：角是斜向缩放，左右边是水平、上下边是垂直。 */
	private static CursorType cursorFor(int handle) {
		return switch (handle) {
			// 左上/右下 = "沿对角线调整大小 1"（↖↘），右上/左下 = "…2"（↗↙）。
			// 样式由上面两个常量的 GLFW id 决定，见那里的说明（之前错了一位，画出来是反的）
			case NW, SE -> RESIZE_NWSE;
			case NE, SW -> RESIZE_NESW;
			case W, E -> CursorTypes.RESIZE_EW;
			default -> CursorTypes.RESIZE_NS;
		};
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		// 不铺底色，世界画面直接透出
		minimap.renderPreview(graphics, DeltaTracker.ZERO);

		ScreenRect rect = minimapRect();
		// 一圈提示边框
		graphics.fill(rect.x() - 1, rect.y() - 1, rect.x() + rect.width() + 1, rect.y(), FRAME_COLOR);
		graphics.fill(rect.x() - 1, rect.y() + rect.height(), rect.x() + rect.width() + 1,
				rect.y() + rect.height() + 1, FRAME_COLOR);
		graphics.fill(rect.x() - 1, rect.y(), rect.x(), rect.y() + rect.height(), FRAME_COLOR);
		graphics.fill(rect.x() + rect.width(), rect.y(), rect.x() + rect.width() + 1,
				rect.y() + rect.height(), FRAME_COLOR);

		int hoveredHandle = handleAt(mouseX, mouseY, rect);
		for (int handle = 0; handle < HANDLE_COUNT; handle++) {
			int hx = handleX(handle, rect);
			int hy = handleY(handle, rect);
			boolean active = handle == hoveredHandle || handle == draggingHandle;
			int half = HANDLE_SIZE / 2;
			graphics.fill(hx - half - 1, hy - half - 1, hx + half + 1, hy + half + 1, 0xC0000000);
			graphics.fill(hx - half, hy - half, hx + half, hy + half,
					active ? 0xFFFFFFFF : 0xE0E0E0E0);
		}

		drawHint(graphics);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		// 鼠标样式最后设（一帧里最后一次请求生效）
		if (draggingHandle >= 0) {
			graphics.requestCursor(cursorFor(draggingHandle));
		} else if (hoveredHandle >= 0) {
			graphics.requestCursor(cursorFor(hoveredHandle));
		}
	}

	/** 顶部提示条：告诉玩家"拖哪儿改什么"，以及当前尺寸。 */
	private void drawHint(GuiGraphicsExtractor graphics) {
		Component hint = Component.translatable("screen.sinanmap.hud_edit.hint");
		Component size = Component.translatable("screen.sinanmap.hud_edit.size",
				settings.width(), settings.height());
		int lineHeight = font.lineHeight;
		int boxWidth = Math.max(font.width(hint), font.width(size)) + 12;
		int x = width / 2 - boxWidth / 2;
		graphics.fill(x, 6, x + boxWidth, 6 + lineHeight * 2 + 10, 0xC0101014);
		graphics.centeredText(font, hint, width / 2, 11, 0xFFFFFFFF);
		graphics.centeredText(font, size, width / 2, 13 + lineHeight, 0xFFB4B4B4);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != 0) {
			return super.mouseClicked(event, doubleClick);
		}
		ScreenRect rect = minimapRect();
		dragStart = rect;
		int handle = handleAt(event.x(), event.y(), rect);
		if (handle >= 0) {
			draggingHandle = handle;
			return true;
		}
		if (inside(event.x(), event.y(), rect.x(), rect.y(), rect.width(), rect.height())) {
			draggingBody = true;
			grabX = (int) event.x() - rect.x();
			grabY = (int) event.y() - rect.y();
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (event.button() != 0) {
			return super.mouseDragged(event, dragX, dragY);
		}
		if (draggingHandle >= 0) {
			resizeFromHandle(draggingHandle, event.x(), event.y());
			return true;
		}
		if (draggingBody) {
			moveTo(event.x() - grabX, event.y() - grabY);
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	/**
	 * 按拖点把矩形改到新的大小：<b>角</b>——对角那个角固定不动，宽高<b>按原来的比例</b>一起变（比例由横向位移驱动）；
	 * <b>边中点</b>——只改那一条边，对边固定。
	 */
	private void resizeFromHandle(int handle, double mouseX, double mouseY) {
		double x = dragStart.x();
		double y = dragStart.y();
		double w = dragStart.width();
		double h = dragStart.height();
		switch (handle) {
			case NW -> {
				double scale = Math.max(0.1, (dragStart.x() + w - mouseX) / w);
				double nw = Math.max(1.0, w * scale);
				double nh = Math.max(1.0, h * scale);
				applyRect(x + (w - nw), y + (h - nh), nw, nh);
			}
			case NE -> {
				double scale = Math.max(0.1, (mouseX - dragStart.x()) / w);
				double nw = Math.max(1.0, w * scale);
				double nh = Math.max(1.0, h * scale);
				applyRect(x, y + (h - nh), nw, nh);
			}
			case SW -> {
				double scale = Math.max(0.1, (dragStart.x() + w - mouseX) / w);
				double nw = Math.max(1.0, w * scale);
				double nh = Math.max(1.0, h * scale);
				applyRect(x + (w - nw), y, nw, nh);
			}
			case SE -> {
				double scale = Math.max(0.1, (mouseX - dragStart.x()) / w);
				double nw = Math.max(1.0, w * scale);
				double nh = Math.max(1.0, h * scale);
				applyRect(x, y, nw, nh);
			}
			case N -> applyRect(x, Math.min(mouseY, y + h - 1), w, y + h - mouseY);
			case S -> applyRect(x, y, w, Math.max(1.0, mouseY - y));
			case W -> applyRect(Math.min(mouseX, x + w - 1), y, x + w - mouseX, h);
			default -> applyRect(x, y, Math.max(1.0, mouseX - x), h);
		}
	}

	private void applyRect(double x, double y, double w, double h) {
		settings.setWidth((int) Math.round(w));
		settings.setHeight((int) Math.round(h));
		moveTo(x, y);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		draggingHandle = -1;
		draggingBody = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		// 滚轮微调大小：拖动是粗调，滚轮用来"再大一点点"
		if (vertical == 0.0) {
			return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
		}
		int step = vertical > 0 ? 4 : -4;
		settings.setWidth(settings.width() + step);
		settings.setHeight(settings.height() + step);
		return true;
	}

	/**
	 * 方向键微调：按一下小地图挪 1 像素。挪位置仍走 {@link #moveTo}，会按当前所在半边自动吸附到最近的角。
	 */
	@Override
	public boolean keyPressed(KeyEvent event) {
		ScreenRect rect = minimapRect();
		int dx = 0;
		int dy = 0;
		switch (event.key()) {
			case GLFW.GLFW_KEY_LEFT -> dx = -1;
			case GLFW.GLFW_KEY_RIGHT -> dx = 1;
			case GLFW.GLFW_KEY_UP -> dy = -1;
			case GLFW.GLFW_KEY_DOWN -> dy = 1;
			default -> {
				return super.keyPressed(event);
			}
		}
		moveTo(rect.x() + dx, rect.y() + dy);
		return true;
	}

	/**
	 * 把左上角挪到 {@code (realX, realY)}。
	 *
	 * <p>小地图只有"吸附到某个角 + 一个偏移"这套表示（见 {@link MinimapLayout}）：按中心落在屏幕哪半边决定吸到哪个角，
	 * 再把落点折成该角下的偏移量。
	 */
	private void moveTo(double realX, double realY) {
		int w = settings.width();
		int h = settings.height();
		int margin = MinimapLayout.DEFAULT_MARGIN;
		boolean right = realX + w / 2.0 > width / 2.0;
		boolean bottom = realY + h / 2.0 > height / 2.0;
		settings.setAnchor(ScreenAnchor.values()[(bottom ? 2 : 0) + (right ? 1 : 0)]);
		settings.setOffsetX(right
				? (int) Math.round(width - margin - w - realX)
				: (int) Math.round(realX - margin));
		settings.setOffsetY(bottom
				? (int) Math.round(height - margin - h - realY)
				: (int) Math.round(realY - margin));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().setScreen(parent);
	}

	/** 边框颜色：淡白，压在任何地形上都看得见。 */
	private static final int FRAME_COLOR = 0xC0FFFFFF;

	/** "完成"按钮的文字色（与设置界面同一套绿色）。 */
	private static final int COLOR_CONFIRM = 0xFFB7F3C8;

	private static boolean inside(double mouseX, double mouseY, int x, int y, int w, int h) {
		return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
	}
}
