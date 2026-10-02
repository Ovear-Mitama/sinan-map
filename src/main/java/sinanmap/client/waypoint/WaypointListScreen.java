package sinanmap.client.waypoint;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import com.mojang.blaze3d.platform.cursor.CursorType;
import com.mojang.blaze3d.platform.cursor.CursorTypes;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import sinanmap.common.coord.WorldPos;
import sinanmap.common.waypoint.Waypoint;
import sinanmap.common.waypoint.WaypointStore;
import sinanmap.client.map.WorldMapScreen;

/**
 * 路径点管理器：左边一列维度、右边是路径点列表（按分组分段），可右键弹出操作菜单。
 *
 * <p><b>维度是左边一列可点的列表</b>（第一项"自动"= 跟随当前维度），不是"按一下换一个"的循环按钮：
 * 同一个 (x, z) 在几个维度是完全不同的地方，混列出来只会点错地方传送，而循环按钮要点好几下、还看不见有哪些可选。
 *
 * <p><b>分组</b>是玩家自己起的名字，列表里按分组分段显示、组名可改：分组只是路径点上的一个字段，没有单独的"组对象"，
 * 所以改名就是把组里每一条改一遍。
 *
 * <p><b>操作走右键菜单</b>：分组标题上 = 新建 / 重命名 / 删除分组；路径点行上 = 重命名 / 编辑 / 传送 / 显示隐藏 / 删除 / 移到分组；
 * 空白处 = 新建路径点 / 新建分组。底部那排按钮保留给"选中的那一条"。
 *
 * <p><b>改名是右键菜单里的一项</b>：点"重命名"才在该行进入行内改名。行内输入框是<b>自绘的</b>（不挂原版控件）：
 * 回车确认、Esc 取消、点别处也算确认；颜色/坐标仍走"编辑…"。
 *
 * <p><b>从世界地图打开时叠在地图上</b>：先把地图画在下面，再压一层半透明底，地图还能对着地形点路径点。
 */
public final class WaypointListScreen extends Screen {

	private static final int ROW_HEIGHT = 14;
	private static final int LIST_WIDTH = 300;
	private static final int LIST_TOP = 68;

	/** 左边维度列表的宽度上限与和路径点列表之间的空隙（实际宽度按窗口收窄，见 {@link #dimensionWidth}）。 */
	private static final int DIMENSION_WIDTH = 104;
	private static final int DIMENSION_GAP = 8;

	/**
	 * 底部那排按钮的宽度上限、最小宽度与缝。六颗到上限就是三百多像素，窗口一小整排会顶出去、
	 * 两端按钮看不见，所以实际宽度按可用宽度分摊（见 {@link #init}）。
	 */
	private static final int BUTTON_WIDTH_MAX = 62;
	private static final int BUTTON_WIDTH_MIN = 44;
	private static final int BUTTON_GAP = 4;

	/**
	 * 一行的高亮底色 / 分组下划线相对列表边界往外让出多少。<b>所有行共用这一个值</b>（分组下划线、
	 * 路径点选中/悬停底色、维度行底色），各自写一个内缩值必然长短不一。
	 */
	private static final int ROW_PAD = 2;

	/** 压在世界地图上的那层底色：要能看穿到地形，alpha 不高。 */
	private static final int MAP_OVERLAY = 0xA8101014;

	/** 菜单的排版参数，与世界地图那个右键菜单<b>完全同一套</b>（行高 = 字高 + 2，上下边距 1），改参数得两处一起改。 */
	private static final int MENU_ROW_HEIGHT = 11;
	private static final int MENU_PADDING = 3;
	private static final int MENU_V_PADDING = 1;
	private static final long MENU_APPEAR_NANOS = 120_000_000L;

	/** 从哪一层界面打开的；关掉时回到它（null = 直接回游戏）。 */
	private final Screen parent;

	/** 滚动了多少像素（向下为正）。 */
	private int scroll;

	/**
	 * 维度列表的一项。
	 *
	 * @param dimension 维度 id；{@code null} 表示"跟随当前维度"
	 */
	private record DimensionEntry(@Nullable String dimension, String label, String suffix) {
	}

	private final List<DimensionEntry> dimensionEntries = new ArrayList<>();
	private int dimensionIndex;

	/**
	 * 列表里的一行。
	 *
	 * @param groupKey 分组标题行的分组名（路径点行为 null）；展开/收起、右键菜单都用它
	 * @param label    分组标题行显示的文字（组名 + 条数）
	 * @param waypoint 路径点行的那一条（分组标题行为 null）
	 */
	private record Row(@Nullable String groupKey, @Nullable String label, @Nullable Waypoint waypoint) {

		static Row group(String key, String label) {
			return new Row(key, label, null);
		}

		static Row waypoint(Waypoint waypoint) {
			return new Row(null, null, waypoint);
		}
	}

	private List<Row> rows = List.of();

	/** 收起来的分组（按分组名记）：只影响列表显示，组里的路径点还在、地图上照常画。 */
	private final Set<String> collapsedGroups = new HashSet<>();

	private EditBox searchBox;
	private int dimensionLeft;
	/** 左边维度列表这一帧的实际宽度（窄窗口下比 {@link #DIMENSION_WIDTH} 窄）。 */
	private int dimensionWidth = DIMENSION_WIDTH;
	private int listLeft;
	private int listRight;
	private int listBottom;

	/**
	 * 正在<b>行内改名</b>的那一条路径点（null = 没有）。由右键菜单"重命名"进入（见 {@link #waypointMenu}）：
	 * 在原来那一行开一个<b>自绘的</b>输入框，不弹编辑界面、不挂原版控件（原版 {@code EditBox} 样式与本列表对不上）。
	 */
	private UUID renamingId;
	/** 改名框里的文字与光标位置（自绘输入框的全部状态）。 */
	private String renameText = "";
	private int renameCursor;
	/** 改名框钉在第几行（改名期间不许滚动，所以这个下标是稳的）。 */
	private int renamingRow = -1;

	/** 行内改名框的宽度：够放常见名字，又不至于盖住右边的坐标与距离。 */
	private static final int NAME_EDIT_WIDTH = 120;

	/** 自绘改名框的样式（与自绘输入框一致：深底 + 底边高光 + 浅色文字）。 */
	private static final int RENAME_PAD_X = 3;
	private static final int RENAME_BACKGROUND = 0xC02A2A32;
	private static final int RENAME_HIGHLIGHT = 0x60FFFFFF;
	private static final int RENAME_TEXT_COLOR = 0xFFE6E6E6;

	/** 右键菜单。 */
	private record MenuItem(String label, Runnable action) {
	}

	private final List<MenuItem> menu = new ArrayList<>();
	private int menuX;
	private int menuY;
	private long menuOpenedNanos;

	/** 输入名字的小提示框（新建分组 / 重命名分组共用）。 */
	private String promptTitle = "";
	private Consumer<String> promptAction;
	private EditBox promptBox;
	private Button promptConfirm;

	public WaypointListScreen(Screen parent) {
		super(Component.translatable("screen.sinanmap.waypoint.manager"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		// 底部按钮行占掉 36 像素；窗口再矮也给它留出一点列表高度，免得列表区间倒挂
		listBottom = Math.max(LIST_TOP + 24, height - 36);
		rebuildDimensionEntries();

		// 左边一列维度 + 右边路径点列表，整块居中。两边的宽度都按窗口收窄：
		// 维度列最多占三分之一屏，剩下的归路径点列表，窄窗口下整块仍在屏内
		dimensionWidth = Math.max(48, Math.min(DIMENSION_WIDTH, (width - 16) / 3));
		int listWidth = Math.max(80, Math.min(LIST_WIDTH, width - dimensionWidth - DIMENSION_GAP - 16));
		int blockWidth = dimensionWidth + DIMENSION_GAP + listWidth;
		dimensionLeft = Math.max(4, (width - blockWidth) / 2);
		listLeft = dimensionLeft + dimensionWidth + DIMENSION_GAP;
		listRight = listLeft + listWidth;

		searchBox = new EditBox(font, listLeft, 40, listWidth, 20,
				Component.translatable("screen.sinanmap.waypoint.search"));
		searchBox.setMaxLength(Waypoint.MAX_NAME_LENGTH);
		searchBox.setHint(Component.translatable("screen.sinanmap.waypoint.search"));
		searchBox.setResponder(text -> rebuildRows());
		addRenderableWidget(searchBox);

		String[] labels = {
				"screen.sinanmap.waypoint.new_edit",
				"screen.sinanmap.waypoint.toggle",
				"screen.sinanmap.waypoint.teleport",
				"screen.sinanmap.waypoint.share",
				"screen.sinanmap.waypoint.delete",
				"screen.sinanmap.waypoint.back",
		};
		Runnable[] actions = {
				() -> openEditor(selectedWaypoint()),
				() -> toggleVisible(selectedWaypoint()),
				() -> teleport(selectedWaypoint()),
				() -> share(selectedWaypoint()),
				() -> delete(selectedWaypoint()),
				this::onClose,
		};
		// 六颗按钮按可用宽度分摊：窗口窄时一起变窄，绝不把两端的按钮挤到屏外
		int buttonWidth = Math.clamp((width - 16 - BUTTON_GAP * (labels.length - 1)) / labels.length,
				BUTTON_WIDTH_MIN, BUTTON_WIDTH_MAX);
		int x = (width - (buttonWidth * labels.length + BUTTON_GAP * (labels.length - 1))) / 2;
		int y = height - 28;
		for (int i = 0; i < labels.length; i++) {
			Runnable action = actions[i];
			addRenderableWidget(Button.builder(Component.translatable(labels[i]),
							button -> action.run())
					.bounds(x, y, buttonWidth, 20).build());
			x += buttonWidth + BUTTON_GAP;
		}

		rebuildRows();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		// 从世界地图打开时先把地图画在下面：管理器是浮在地图上的一层
		if (parent instanceof WorldMapScreen map) {
			map.extractRenderState(graphics, -1, -1, partialTick);
		}
		graphics.fill(0, 0, width, height, MAP_OVERLAY);
		graphics.centeredText(font, title, width / 2, 14, 0xFFFFFFFF);

		drawDimensionList(graphics, mouseX, mouseY);

		if (rows.isEmpty()) {
			graphics.centeredText(font, Component.translatable("screen.sinanmap.waypoint.empty"),
					(listLeft + listRight) / 2, (LIST_TOP + listBottom) / 2, 0xFF9CA3AF);
		}

		Waypoint selected = selectedWaypoint();
		LocalPlayer player = Minecraft.getInstance().player;
		int hoveredRow = rowAt(mouseX, mouseY);
		graphics.enableScissor(0, LIST_TOP, width, listBottom);
		for (int i = 0; i < rows.size(); i++) {
			int rowY = rowY(i);
			if (rowY + ROW_HEIGHT < LIST_TOP || rowY > listBottom) {
				continue;
			}
			Row row = rows.get(i);
			int textY = rowTextY(rowY);
			boolean hovered = i == hoveredRow;
			if (row.waypoint() == null) {
				// 分组标题：组名 + 条数；名字下面一条铺满整行的下划线；右边那个 -/+ 是展开/收起（整行都能点，见 mouseClicked）
				String label = row.label();
				int underlineY = underlineY(rowY);
				graphics.text(font, label, listLeft, textY, hovered ? 0xFFFFFFFF : 0xFFB4B4B4, false);
				graphics.fill(rowLeft(), underlineY, rowRight(), underlineY + 1, 0x60FFFFFF);
				boolean collapsed = collapsedGroups.contains(row.groupKey());
				String mark = collapsed ? "+" : "-";
				graphics.text(font, mark, listRight - font.width(mark), textY,
						hovered ? 0xFFFFFFFF : 0xFF9CA3AF, false);
				continue;
			}
			Waypoint waypoint = row.waypoint();
			boolean isSelected = selected != null && waypoint.id().equals(selected.id());
			// 直接写名字（行内改名时跳过：那一格让给输入框，别把字压在框底下）
			boolean renaming = waypoint.id().equals(renamingId);
			String name = waypoint.name();
			String coords = "%d %d %d".formatted(
					waypoint.pos().blockX(), waypoint.pos().blockY(), waypoint.pos().blockZ());
			String distance = "";
			if (player != null) {
				double meters = Math.hypot(waypoint.pos().x() - player.getX(),
						waypoint.pos().z() - player.getZ());
				distance = "%d m".formatted(Math.round(meters));
			}
			int distanceX = listRight - (distance.isEmpty() ? 0 : font.width(distance));
			int coordsX = distanceX - 8 - font.width(coords);
			// 选中/悬停的底铺整行（左右边界与分组下划线共用 rowLeft()/rowRight()），否则会比组的下划线短一截、看着没对齐
			if (isSelected) {
				graphics.fill(rowLeft(), rowY, rowRight(), rowY + ROW_HEIGHT, 0x40FFFFFF);
			} else if (hovered && !renaming) {
				graphics.fill(rowLeft(), rowY, rowRight(), rowY + ROW_HEIGHT, 0x20FFFFFF);
			}
			if (!renaming) {
				int nameColor = waypoint.visible() ? waypoint.color() : 0xFF6E6E6E;
				graphics.text(font, name, listLeft, textY, nameColor, false);
			}
			// 坐标（灰）在距离（右对齐）左边
			graphics.text(font, coords, coordsX, textY, 0xFF8A8A8A, false);
			if (!distance.isEmpty()) {
				graphics.text(font, distance, distanceX, textY, 0xFF9CA3AF, false);
			}
		}
		graphics.disableScissor();

		drawMenu(graphics, mouseX, mouseY);
		drawPrompt(graphics);
		// 行内改名框钉在那一行上（改名期间禁止滚动，所以下标是稳的）：自绘，不挂原版控件
		if (renamingId != null) {
			drawRenameBox(graphics);
		}

		// 控件画在最上层
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		// 鼠标样式放在最后：一帧里最后一次 requestCursor 才生效（见 updateCursor）
		updateCursor(graphics, mouseX, mouseY);
	}

	/**
	 * 按光标位置请求鼠标样式。
	 *
	 * <p>{@link GuiGraphicsExtractor#requestCursor(CursorType)} 一帧里<b>最后</b>一次请求生效（{@code GameRenderer}
	 * 在 GUI 画完之后才 apply），所以必须排在 {@code super.extractRenderState} 之后，才能盖过控件自己的请求。
	 *
	 * <ul>
	 *   <li>输入框（搜索框 / 行内改名框 / 提示框）→ {@link CursorTypes#IBEAM}</li>
	 *   <li>能点的地方（底部按钮、右键菜单项、维度行、列表行）→ {@link CursorTypes#POINTING_HAND}</li>
	 *   <li>其余（滚动的列表本身）→ {@link CursorTypes#ARROW}</li>
	 * </ul>
	 */
	private void updateCursor(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		CursorType cursor = CursorTypes.ARROW;
		if (pointsAtInput(mouseX, mouseY)) {
			cursor = CursorTypes.IBEAM;
		} else if (pointsAtWidget(mouseX, mouseY)
				|| menuIndexAt(mouseX, mouseY) >= 0
				|| rowAt(mouseX, mouseY) >= 0
				|| dimensionIndexAt(mouseX, mouseY) >= 0) {
			cursor = CursorTypes.POINTING_HAND;
		}
		graphics.requestCursor(cursor);
	}

	/** 光标是不是停在输入框上（自绘的改名框 + 原版 {@link EditBox}）。 */
	private boolean pointsAtInput(double mouseX, double mouseY) {
		if (renamingId != null && insideRenameBox(mouseX, mouseY)) {
			return true;
		}
		for (GuiEventListener child : children()) {
			if (child instanceof EditBox box && box.isMouseOver(mouseX, mouseY)) {
				return true;
			}
		}
		return false;
	}

	/** 光标是不是停在一个"能按的控件"上（底部按钮、提示框的确定/取消）。 */
	private boolean pointsAtWidget(double mouseX, double mouseY) {
		for (GuiEventListener child : children()) {
			if (child instanceof AbstractWidget widget && widget.isMouseOver(mouseX, mouseY)) {
				return true;
			}
		}
		return false;
	}

	/** 左边的维度列表：一行一个维度，选中的那行高亮。 */
	private void drawDimensionList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		for (int i = 0; i < dimensionEntries.size(); i++) {
			int rowY = dimensionRowY(i);
			boolean hovered = mouseX >= dimensionLeft && mouseX <= dimensionLeft + dimensionWidth
					&& mouseY >= rowY && mouseY <= rowY + ROW_HEIGHT;
			// 左右内缩与列表那边共用 ROW_PAD，上下也正好落在 rowY..rowY+ROW_HEIGHT，
			// 于是"左边这一列"和右边列表的行是同一套对齐关系
			if (i == dimensionIndex) {
				graphics.fill(dimensionLeft - ROW_PAD, rowY, dimensionLeft + dimensionWidth + ROW_PAD,
						rowY + ROW_HEIGHT, 0x40FFFFFF);
			} else if (hovered) {
				graphics.fill(dimensionLeft - ROW_PAD, rowY, dimensionLeft + dimensionWidth + ROW_PAD,
						rowY + ROW_HEIGHT, 0x20FFFFFF);
			}
			graphics.text(font, dimensionEntries.get(i).label(), dimensionLeft, rowY + 3,
					i == dimensionIndex ? 0xFFFFFFFF : 0xFFB4B4B4, false);
			String suffix = dimensionEntries.get(i).suffix();
			if (!suffix.isEmpty()) {
				graphics.text(font, suffix,
						dimensionLeft + dimensionWidth - font.width(suffix), rowY + 3, 0xFF9CA3AF, false);
			}
		}
	}

	private int dimensionRowY(int index) {
		return LIST_TOP + index * rowPitch();
	}

	private void drawMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (menu.isEmpty()) {
			return;
		}
		int menuWidth = menuWidth();
		int menuHeight = menu.size() * MENU_ROW_HEIGHT + MENU_V_PADDING * 2;
		int hovered = menuIndexAt(mouseX, mouseY);

		float appear = Math.clamp((System.nanoTime() - menuOpenedNanos) / (float) MENU_APPEAR_NANOS,
				0.0F, 1.0F);
		float fade = appear * appear * (3.0F - 2.0F * appear);

		graphics.fill(menuX, menuY, menuX + menuWidth, menuY + menuHeight, alpha(fade, 0xF0101014));
		graphics.fill(menuX, menuY, menuX + menuWidth, menuY + 1, alpha(fade, 0x60FFFFFF));
		graphics.fill(menuX, menuY + menuHeight - 1, menuX + menuWidth, menuY + menuHeight,
				alpha(fade, 0x60FFFFFF));
		for (int i = 0; i < menu.size(); i++) {
			int rowY = menuY + MENU_V_PADDING + i * MENU_ROW_HEIGHT;
			if (i == hovered) {
				graphics.fill(menuX + 1, rowY, menuX + menuWidth - 1, rowY + MENU_ROW_HEIGHT,
						alpha(fade, 0x40FFFFFF));
			}
			// 行高 11、字高 9 → 上下各 1 像素，文字正好落在高亮行块的正中（与世界地图那个菜单同一条口径）
			graphics.text(font, menu.get(i).label(), menuX + MENU_PADDING * 2,
					rowY + (MENU_ROW_HEIGHT - font.lineHeight + 1) / 2,
					alpha(fade, i == hovered ? 0xFFFFFFFF : 0xFFDDDDDD), false);
		}
	}

	private int menuWidth() {
		int textWidth = 0;
		for (MenuItem item : menu) {
			textWidth = Math.max(textWidth, font.width(item.label()));
		}
		return textWidth + MENU_PADDING * 4;
	}

	private int menuIndexAt(double mouseX, double mouseY) {
		if (menu.isEmpty()) {
			return -1;
		}
		int menuWidth = menuWidth();
		int menuHeight = menu.size() * MENU_ROW_HEIGHT + MENU_V_PADDING * 2;
		if (mouseX < menuX || mouseX >= menuX + menuWidth
				|| mouseY < menuY + MENU_V_PADDING || mouseY >= menuY + menuHeight - MENU_V_PADDING) {
			return -1;
		}
		int index = (int) ((mouseY - menuY - MENU_V_PADDING) / MENU_ROW_HEIGHT);
		return index >= 0 && index < menu.size() ? index : -1;
	}

	/** 在给定位置弹出菜单；超出屏幕时往回收，不让它跑出去。 */
	private void openMenu(List<MenuItem> items, double mouseX, double mouseY) {
		menu.clear();
		menu.addAll(items);
		int menuWidth = menuWidth();
		int menuHeight = menu.size() * MENU_ROW_HEIGHT + MENU_V_PADDING * 2;
		menuX = (int) Math.clamp(mouseX, 0, Math.max(0, width - menuWidth));
		menuY = (int) Math.clamp(mouseY, 0, Math.max(0, height - menuHeight));
		menuOpenedNanos = System.nanoTime();
	}

	private void closeMenu() {
		menu.clear();
	}

	private static int alpha(float fade, int color) {
		int a = (int) (((color >>> 24) & 0xFF) * Math.clamp(fade, 0.0F, 1.0F));
		return a << 24 | color & 0xFFFFFF;
	}

	/** 输入名字的小提示框：新建分组与重命名分组共用。用原版 {@link EditBox}（中文输入法才可用），回车等于确定。 */
	private void openPrompt(String titleKey, String initial, Consumer<String> action) {
		if (promptAction != null) {
			return;
		}
		promptTitle = Component.translatable(titleKey).getString();
		promptAction = action;
		int boxWidth = 200;
		int centerX = width / 2;
		int boxY = height / 2 - 10;
		promptBox = new EditBox(font, centerX - boxWidth / 2, boxY, boxWidth, 20,
				Component.literal(promptTitle));
		promptBox.setMaxLength(Waypoint.MAX_GROUP_LENGTH);
		promptBox.setValue(initial == null ? "" : initial);
		addRenderableWidget(promptBox);
		promptConfirm = Button.builder(Component.translatable("screen.sinanmap.waypoint.confirm"),
						button -> confirmPrompt())
				.bounds(centerX - boxWidth / 2, boxY + 24, (boxWidth - 4) / 2, 20).build();
		addRenderableWidget(promptConfirm);
		addRenderableWidget(Button.builder(Component.translatable("screen.sinanmap.waypoint.cancel"),
						button -> closePrompt())
				.bounds(centerX + 4, boxY + 24, (boxWidth - 4) / 2, 20).build());
		setFocused(promptBox);
	}

	private void drawPrompt(GuiGraphicsExtractor graphics) {
		if (promptAction == null) {
			return;
		}
		graphics.fill(0, 0, width, height, 0xC0000000);
		int boxWidth = 200;
		int centerX = width / 2;
		int boxY = height / 2 - 10;
		graphics.fill(centerX - boxWidth / 2 - 8, boxY - 20, centerX + boxWidth / 2 + 8, boxY + 52,
				0xF0101014);
		graphics.text(font, promptTitle, centerX - font.width(promptTitle) / 2, boxY - 14, 0xFFFFFFFF, false);
	}

	private void confirmPrompt() {
		if (promptAction == null) {
			return;
		}
		Consumer<String> action = promptAction;
		String value = promptBox.getValue().strip();
		closePrompt();
		if (!value.isEmpty()) {
			action.accept(value);
		}
	}

	private void closePrompt() {
		if (promptBox != null) {
			removeWidget(promptBox);
			promptBox = null;
		}
		if (promptConfirm != null) {
			removeWidget(promptConfirm);
			promptConfirm = null;
		}
		promptAction = null;
		setFocused(searchBox);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().setScreen(parent);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// 提示框开着时只认它自己的控件
		if (promptAction != null) {
			return super.mouseClicked(event, doubleClick);
		}
		// 行内改名中：点在框里就继续改名；点在别处当作"确认"，再继续处理这一下点击——点到别的行就顺手选中它，不必再点第二次
		if (renamingId != null) {
			if (insideRenameBox(event.x(), event.y())) {
				return true;
			}
			finishRename(true);
		}
		// 菜单开着时：点在菜单里就执行，点在外面就关掉（那一下不再做别的事）
		if (!menu.isEmpty()) {
			int index = menuIndexAt(event.x(), event.y());
			Runnable action = index < 0 ? null : menu.get(index).action();
			closeMenu();
			if (action != null) {
				action.run();
			}
			return true;
		}
		// 先给控件（搜索框、底部按钮）
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (clickDimension(event.x(), event.y())) {
			return true;
		}
		int index = rowAt(event.x(), event.y());
		if (index < 0) {
			// 空白处右键：新建路径点 / 新建分组
			if (event.button() == 1 && event.x() >= listLeft - 3 && event.x() <= listRight + 3
					&& event.y() >= LIST_TOP && event.y() <= listBottom) {
				openMenu(emptyMenu(), event.x(), event.y());
				return true;
			}
			return false;
		}
		Row row = rows.get(index);
		if (event.button() == 1) {
			if (row.waypoint() != null) {
				// 右键落在路径点行上：弹出"更多"菜单（重命名是其中一项，点了才进入改名）
				WaypointManager.instance().select(row.waypoint().id());
				openMenu(waypointMenu(row.waypoint()), event.x(), event.y());
				return true;
			}
			// 分组标题仍然走右键菜单（新建 / 重命名 / 删除分组）
			openMenu(groupMenu(row.groupKey()), event.x(), event.y());
			return true;
		}
		if (row.waypoint() == null) {
			// 分组标题：整行都能点，点一下展开/收起这一组（不再只认右边那个 -/+）
			toggleGroup(row.groupKey());
			return true;
		}
		WaypointManager.instance().select(row.waypoint().id());
		return true;
	}

	/**
	 * 改名期间的按键：回车确认、Esc 取消、左右/Home/End 移动光标、退格/Delete 删字。自绘输入框不走原版控件，这些键要自己接住。
	 */
	@Override
	public boolean keyPressed(KeyEvent event) {
		if (renamingId != null) {
			switch (event.key()) {
				case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
					finishRename(true);
					return true;
				}
				case GLFW.GLFW_KEY_ESCAPE -> {
					// Esc 只退出改名，不关整个管理器
					finishRename(false);
					return true;
				}
				case GLFW.GLFW_KEY_BACKSPACE -> {
					if (renameCursor > 0) {
						renameText = renameText.substring(0, renameCursor - 1)
								+ renameText.substring(renameCursor);
						renameCursor--;
					}
					return true;
				}
				case GLFW.GLFW_KEY_DELETE -> {
					if (renameCursor < renameText.length()) {
						renameText = renameText.substring(0, renameCursor)
								+ renameText.substring(renameCursor + 1);
					}
					return true;
				}
				case GLFW.GLFW_KEY_LEFT -> {
					renameCursor = Math.max(0, renameCursor - 1);
					return true;
				}
				case GLFW.GLFW_KEY_RIGHT -> {
					renameCursor = Math.min(renameText.length(), renameCursor + 1);
					return true;
				}
				case GLFW.GLFW_KEY_HOME -> {
					renameCursor = 0;
					return true;
				}
				case GLFW.GLFW_KEY_END -> {
					renameCursor = renameText.length();
					return true;
				}
				default -> {
				}
			}
		}
		return super.keyPressed(event);
	}

	/** 改名期间输入字符：插到光标处（超长截断）。 */
	@Override
	public boolean charTyped(CharacterEvent event) {
		if (renamingId != null) {
			if (event.isAllowedChatCharacter() && renameText.length() < Waypoint.MAX_NAME_LENGTH) {
				renameText = renameText.substring(0, renameCursor) + event.codepointAsString()
						+ renameText.substring(renameCursor);
				renameCursor++;
			}
			return true;
		}
		return super.charTyped(event);
	}

	/** 路径点行上的右键菜单。 */
	private List<MenuItem> waypointMenu(Waypoint waypoint) {
		return List.of(
				// 重命名：名字是路径点的身份，这里只改名字，颜色/坐标等仍走"编辑…"
				new MenuItem("重命名", () -> startRename(waypoint)),
				new MenuItem(Component.translatable("screen.sinanmap.menu.edit").getString(),
						() -> openEditor(waypoint)),
				new MenuItem(Component.translatable("screen.sinanmap.waypoint.teleport").getString(),
						() -> teleport(waypoint)),
				new MenuItem(Component.translatable("screen.sinanmap.waypoint.toggle").getString(),
						() -> toggleVisible(waypoint)),
				new MenuItem(Component.translatable("screen.sinanmap.waypoint.delete").getString(),
						() -> delete(waypoint)),
				new MenuItem(Component.translatable("screen.sinanmap.menu.set_group").getString(),
						() -> openPrompt("screen.sinanmap.waypoint.group_move", waypoint.group(),
								name -> moveToGroup(waypoint, name))));
	}

	/** 把一条路径点挪到另一个分组（分组只是路径点上的字段，改这一条就成了）。 */
	private void moveToGroup(Waypoint waypoint, String group) {
		if (group.isBlank() || group.equals(waypoint.group())) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		WaypointManager.instance().update(minecraft, waypoint.withGroup(group));
		rebuildDimensionEntries();
		rebuildRows();
	}

	/** 进入行内改名：把当前名字填进自绘输入框，光标放到末尾。 */
	private void startRename(Waypoint waypoint) {
		int rowIndex = rowIndexOf(waypoint.id());
		if (rowIndex < 0) {
			return;
		}
		cancelRename();
		renamingId = waypoint.id();
		renamingRow = rowIndex;
		renameText = waypoint.name();
		renameCursor = renameText.length();
	}

	/**
	 * 结束行内改名。
	 *
	 * @param commit 真 = 把输入框里的名字写回去（空名字不写，名字是路径点的身份）
	 */
	private void finishRename(boolean commit) {
		if (renamingId == null) {
			return;
		}
		UUID id = renamingId;
		String name = renameText.strip();
		cancelRename();
		if (!commit || name.isEmpty()) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		for (Waypoint waypoint : WaypointManager.instance().store(minecraft).all()) {
			if (waypoint.id().equals(id)) {
				if (!name.equals(waypoint.name())) {
					WaypointManager.instance().update(minecraft, waypoint.withName(name));
				}
				break;
			}
		}
		rebuildRows();
	}

	/** 收起改名框（不写回）。 */
	private void cancelRename() {
		renamingId = null;
		renamingRow = -1;
		renameText = "";
		renameCursor = 0;
	}

	/** 光标是不是落在行内改名框上。 */
	private boolean insideRenameBox(double mouseX, double mouseY) {
		int y = rowY(renamingRow);
		return mouseX >= listLeft && mouseX < listLeft + NAME_EDIT_WIDTH
				&& mouseY >= y && mouseY < y + ROW_HEIGHT;
	}

	/**
	 * 画行内改名框：底纹 + <b>底边</b>高光 + 文字 + 闪烁光标，样式与自绘输入框（{@code SinanTextField}）一致。
	 */
	private void drawRenameBox(GuiGraphicsExtractor graphics) {
		int x = listLeft;
		int y = rowY(renamingRow);
		graphics.fill(x, y, x + NAME_EDIT_WIDTH, y + ROW_HEIGHT, RENAME_BACKGROUND);
		graphics.fill(x, y + ROW_HEIGHT - 1, x + NAME_EDIT_WIDTH, y + ROW_HEIGHT, RENAME_HIGHLIGHT);

		int textY = rowTextY(y);
		int inner = Math.max(1, NAME_EDIT_WIDTH - RENAME_PAD_X * 2);
		int cursorPixels = font.width(renameText.substring(0, Math.min(renameCursor, renameText.length())));
		// 文字比框宽时横向滚动，让光标始终留在可见范围内
		int maxScroll = Math.max(0, font.width(renameText) - inner);
		int scroll = Math.clamp(cursorPixels - inner, 0, maxScroll);

		graphics.enableScissor(x + RENAME_PAD_X, y, x + NAME_EDIT_WIDTH - RENAME_PAD_X, y + ROW_HEIGHT);
		graphics.text(font, renameText, x + RENAME_PAD_X - scroll, textY, RENAME_TEXT_COLOR, false);
		if (System.currentTimeMillis() / 300L % 2L == 0L) {
			int caretX = x + RENAME_PAD_X - scroll + cursorPixels;
			graphics.fill(caretX, textY - 1, caretX + 1, textY + font.lineHeight, 0xFFFFFFFF);
		}
		graphics.disableScissor();
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (renamingId != null) {
			// 改名期间不许滚动：输入框是钉在那一行上的
			return true;
		}
		scroll = (int) Math.clamp(scroll - scrollY * rowPitch(), 0, maxScroll());
		return true;
	}

	private static int rowPitch() {
		return ROW_HEIGHT;
	}

	/**
	 * 一行里文字画在哪：上下留白按字体高度居中。不能都用 {@code rowY + 3} 硬凑——换行高之后就会偏。
	 */
	private int rowTextY(int rowY) {
		return rowY + (ROW_HEIGHT - font.lineHeight + 1) / 2;
	}

	/**
	 * 分组名下面那条下划线：画在<b>这一行的最后一行像素</b>上（{@code rowY + ROW_HEIGHT - 1}），正好压在组名行与组内路径点的交界上；
	 * 原来贴文字基线画（早 2 像素），离下面的路径点还差一截，看着没贴合。
	 */
	private int underlineY(int rowY) {
		return rowY + ROW_HEIGHT - 1;
	}

	private int rowY(int index) {
		return LIST_TOP + index * rowPitch() - scroll;
	}

	/** 列表里一行（高亮底色 / 分组下划线）的左边界：整条列表的左边再往外让 {@link #ROW_PAD}。 */
	private int rowLeft() {
		return listLeft - ROW_PAD;
	}

	/** 列表里一行的右边界，与 {@link #rowLeft()} 同一个口径，于是所有行都等长。 */
	private int rowRight() {
		return listRight + ROW_PAD;
	}

	private int rowAt(double mouseX, double mouseY) {
		if (mouseX < listLeft - 3 || mouseX > listRight + 3
				|| mouseY < LIST_TOP || mouseY > listBottom) {
			return -1;
		}
		int index = (int) ((mouseY - LIST_TOP + scroll) / rowPitch());
		return index >= 0 && index < rows.size() ? index : -1;
	}

	private int maxScroll() {
		int content = rows.size() * rowPitch();
		return Math.max(0, content - (listBottom - LIST_TOP));
	}

	/** 光标落在第几个维度行上；不在维度列表里返回 -1（点击与鼠标样式共用同一套判定）。 */
	private int dimensionIndexAt(double mouseX, double mouseY) {
		if (mouseX < dimensionLeft - 3 || mouseX > dimensionLeft + dimensionWidth + 3) {
			return -1;
		}
		for (int i = 0; i < dimensionEntries.size(); i++) {
			int rowY = dimensionRowY(i);
			if (mouseY >= rowY && mouseY <= rowY + ROW_HEIGHT) {
				return i;
			}
		}
		return -1;
	}

	private boolean clickDimension(double mouseX, double mouseY) {
		int index = dimensionIndexAt(mouseX, mouseY);
		if (index < 0) {
			return false;
		}
		dimensionIndex = index;
		rebuildRows();
		return true;
	}

	/** 分组标题上的右键菜单。 */
	private List<MenuItem> groupMenu(String group) {
		return List.of(
				new MenuItem(Component.translatable("screen.sinanmap.menu.new_here").getString(),
						() -> openEditorForGroup(group)),
				new MenuItem(Component.translatable("screen.sinanmap.menu.rename_group").getString(),
						() -> openPrompt("screen.sinanmap.menu.rename_group", group,
								name -> renameGroup(group, name))),
				new MenuItem(Component.translatable("screen.sinanmap.menu.new_group").getString(),
						this::promptNewGroup),
				new MenuItem(Component.translatable("screen.sinanmap.waypoint.delete_group").getString(),
						() -> deleteGroup(group)));
	}

	/** 空白处的右键菜单。 */
	private List<MenuItem> emptyMenu() {
		return List.of(
				new MenuItem(Component.translatable("screen.sinanmap.waypoint.new").getString(),
						() -> openEditor(null)),
				new MenuItem(Component.translatable("screen.sinanmap.menu.new_group").getString(),
						this::promptNewGroup));
	}

	private void promptNewGroup() {
		openPrompt("screen.sinanmap.menu.new_group", "",
				name -> openEditorForGroup(name));
	}

	/**
	 * 把一整组改名：分组只是路径点上的一个字段，所以改名就是把组里每一条改一遍，不会出现"组还在、里面的点不见了"。
	 */
	private void renameGroup(String oldGroup, String newGroup) {
		int changed = WaypointManager.instance().renameGroup(Minecraft.getInstance(),
				currentDimension(), oldGroup, newGroup);
		rebuildDimensionEntries();
		rebuildRows();
		WaypointManager.notify(Minecraft.getInstance().player,
				Component.translatable("message.sinanmap.waypoint.group_renamed", oldGroup, newGroup, changed));
	}

	private void deleteGroup(String group) {
		if (Waypoint.DEFAULT_GROUP.equals(group)) {
			return;
		}
		WaypointManager.instance().renameGroup(Minecraft.getInstance(),
				currentDimension(), group, Waypoint.DEFAULT_GROUP);
		rebuildRows();
	}

	/** 在某个分组里新建一条（位置取玩家脚下）。 */
	private void openEditorForGroup(String group) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null) {
			return;
		}
		// 用玩家所在的地方当落点：管理器里没有光标，用玩家位置最自然
		minecraft.setScreen(new WaypointEditScreen(this, null,
				new WorldPos(minecraft.level.dimension().identifier().toString(),
						player.getX(), player.getY(), player.getZ()), group));
	}

	private void toggleVisible(@Nullable Waypoint waypoint) {
		if (waypoint != null) {
			WaypointManager.instance().toggleVisible(Minecraft.getInstance(), waypoint);
			rebuildRows();
		}
	}

	private void delete(@Nullable Waypoint waypoint) {
		if (waypoint != null) {
			WaypointManager.instance().remove(Minecraft.getInstance(), waypoint.id());
			rebuildRows();
		}
	}

	/**
	 * 重建维度列表：第一项是"跟随当前维度"（<b>左侧写当前维度 id</b>，右侧标"自动"），后面是<b>真的有路径点</b>的维度。
	 * 没有路径点的维度不列（注册表里可能一大堆，全列出来这一列长得没法看）。
	 */
	private void rebuildDimensionEntries() {
		String previous = dimensionEntries.isEmpty() ? null : dimensionEntries.get(dimensionIndex).dimension();
		dimensionEntries.clear();
		String current = currentDimension();
		// "自动"那一项左侧写的就是它将跟随的那个维度，右侧标出来它是自动的
		dimensionEntries.add(new DimensionEntry(null, dimensionDisplayName(current),
				Component.translatable("screen.sinanmap.waypoint.dimension_auto_suffix").getString()));
		Minecraft minecraft = Minecraft.getInstance();
		List<String> found = new ArrayList<>();
		for (Waypoint waypoint : WaypointManager.instance().store(minecraft).all()) {
			String dimension = waypoint.pos().dimension();
			if (!found.contains(dimension)) {
				found.add(dimension);
			}
		}
		found.sort(String::compareTo);
		for (String dimension : found) {
			dimensionEntries.add(new DimensionEntry(dimension, dimensionDisplayName(dimension), ""));
		}
		dimensionIndex = 0;
		for (int i = 0; i < dimensionEntries.size(); i++) {
			if (Objects.equals(dimensionEntries.get(i).dimension(), previous)) {
				dimensionIndex = i;
				break;
			}
		}
	}

	/**
	 * 维度的显示名：直接用它的 id。原版从不给维度起可翻译的显示名（调试屏也是 id），自己编一套只会和别处对不上；
	 * 省略 {@code minecraft} 命名空间，读起来就是 {@code overworld} / {@code the_nether}，模组维度保留完整 id。
	 */
	private static String dimensionDisplayName(String id) {
		int colon = id.indexOf(':');
		return colon < 0 || !"minecraft".equals(id.substring(0, colon)) ? id : id.substring(colon + 1);
	}

	private String currentDimension() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.level == null ? "" : minecraft.level.dimension().identifier().toString();
	}

	/** 重排列表：先按维度与搜索词筛，再按分组分段，段内按离玩家远近。 */
	private void rebuildRows() {
		Minecraft minecraft = Minecraft.getInstance();
		WaypointStore store = WaypointManager.instance().store(minecraft);
		String currentDimension = currentDimension();
		String option = dimensionEntries.isEmpty() ? null : dimensionEntries.get(dimensionIndex).dimension();
		String query = searchBox == null ? "" : searchBox.getValue().strip().toLowerCase(Locale.ROOT);
		String wanted = option == null ? currentDimension : option;

		List<Waypoint> matching = new ArrayList<>();
		for (Waypoint waypoint : store.all()) {
			if (!waypoint.pos().dimension().equals(wanted)) {
				continue;
			}
			if (!query.isEmpty() && !waypoint.name().toLowerCase(Locale.ROOT).contains(query)) {
				continue;
			}
			matching.add(waypoint);
		}
		sortByDistance(matching);

		List<Row> built = new ArrayList<>();
		if (!query.isEmpty()) {
			// 搜索时不分组：找东西时只关心"有没有这一条"
			for (Waypoint waypoint : matching) {
				built.add(Row.waypoint(waypoint));
			}
		} else {
			for (String group : WaypointManager.instance().groupsIn(minecraft, wanted)) {
				List<Waypoint> members = new ArrayList<>();
				for (Waypoint waypoint : matching) {
					if (waypoint.group().equals(group)) {
						members.add(waypoint);
					}
				}
				if (members.isEmpty()) {
					continue;
				}
				built.add(Row.group(group, group + "  ·  " + members.size()));
				if (collapsedGroups.contains(group)) {
					// 收起来的那一组只有标题：组里的路径点还在，只是不占这一列的地方
					continue;
				}
				for (Waypoint waypoint : members) {
					built.add(Row.waypoint(waypoint));
				}
			}
		}
		rows = List.copyOf(built);
		scroll = (int) Math.clamp(scroll, 0, maxScroll());
		// 改名的那一行被筛掉/挪走了：输入框没有了依附对象，收起来
		// （不写回：输入到一半的名字不该落到别的行上）
		if (renamingId != null && rowIndexOf(renamingId) < 0) {
			cancelRename();
		}
	}

	/** 某条路径点在当前列表里是第几行；不在列表里（被筛掉、或分组被收起）返回 -1。 */
	private int rowIndexOf(UUID id) {
		for (int i = 0; i < rows.size(); i++) {
			Waypoint waypoint = rows.get(i).waypoint();
			if (waypoint != null && waypoint.id().equals(id)) {
				return i;
			}
		}
		return -1;
	}

	/** 展开/收起一个分组（列表里那个 {@code -}/{@code +}）。 */
	private void toggleGroup(String group) {
		if (!collapsedGroups.remove(group)) {
			collapsedGroups.add(group);
		}
		rebuildRows();
	}

	private void sortByDistance(List<Waypoint> waypoints) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			waypoints.sort((first, second) -> first.name().compareTo(second.name()));
			return;
		}
		waypoints.sort((first, second) -> Double.compare(
				Math.hypot(first.pos().x() - player.getX(), first.pos().z() - player.getZ()),
				Math.hypot(second.pos().x() - player.getX(), second.pos().z() - player.getZ())));
	}

	/** 当前选中的那一条；选中的不在列表里（被筛掉了、或还没选过）时退回第一条路径点。 */
	@Nullable
	private Waypoint selectedWaypoint() {
		WaypointManager manager = WaypointManager.instance();
		UUID selected = manager.selected();
		if (selected != null) {
			for (Row row : rows) {
				if (row.waypoint() != null && row.waypoint().id().equals(selected)) {
					return row.waypoint();
				}
			}
		}
		for (Row row : rows) {
			if (row.waypoint() != null) {
				return row.waypoint();
			}
		}
		return null;
	}

	private void openEditor(@Nullable Waypoint editing) {
		Minecraft minecraft = Minecraft.getInstance();
		if (editing != null) {
			minecraft.setScreen(new WaypointEditScreen(this, editing, null));
			return;
		}
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null) {
			return;
		}
		// 管理器里的"新建"落在玩家脚下：管理器没有光标，用玩家位置最自然
		minecraft.setScreen(new WaypointEditScreen(this, null,
				new WorldPos(minecraft.level.dimension().identifier().toString(),
						player.getX(), player.getY(), player.getZ())));
	}

	/**
	 * 传送到一条路径点，然后关掉本界面。能不能传（跨维度、没开作弊、没权限）统一在 {@link WaypointManager#teleport} 里判，
	 * 世界地图的右键菜单走同一个入口。
	 */
	private void teleport(@Nullable Waypoint waypoint) {
		if (waypoint == null) {
			return;
		}
		WaypointManager.teleport(Minecraft.getInstance(), waypoint);
		onClose();
	}

	/** 分享一条路径点到聊天里。不关界面：分享完还能接着分享别的（与"传送"要关界面不同）。 */
	private void share(@Nullable Waypoint waypoint) {
		if (waypoint == null) {
			return;
		}
		WaypointManager.shareWaypoint(waypoint);
	}
}
