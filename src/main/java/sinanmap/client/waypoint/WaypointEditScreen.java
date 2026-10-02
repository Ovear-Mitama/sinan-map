package sinanmap.client.waypoint;

import java.util.ArrayList;
import java.util.List;

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
import net.minecraft.network.chat.Component;

import com.mojang.blaze3d.platform.cursor.CursorType;
import com.mojang.blaze3d.platform.cursor.CursorTypes;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import sinanmap.client.gui.ColorPicker;
import sinanmap.client.gui.SinanStyle;
import sinanmap.common.coord.WorldPos;
import sinanmap.common.waypoint.Waypoint;

/**
 * 新建 / 编辑一条路径点。五块内容：<b>名字</b>、<b>简称</b>、<b>分组</b>、<b>坐标</b>、<b>颜色</b>。
 *
 * <ul>
 *   <li><b>名字</b>用原版 {@link EditBox}（只有走原版那条路中文输入法才可用）。新建时不预填；
 *       名字空着时"保存"置灰（名字是这条路径点的唯一标识）。</li>
 *   <li><b>简称</b>是标记<b>收起时</b>显示的那几个字（见 {@code WaypointRenderer#initial}）：留空就用名字首字，可不填。</li>
 *   <li><b>分组</b>是列表里的一列分组名 + <b>最后一行的输入框</b>：点一行选中那一组，在最后一行打字、回车即新建并选中。</li>
 *   <li><b>坐标</b>自动取当前落点，但<b>只当提示（灰字占位），不填进输入框</b>：留空 = 用自动取来的那个。三个框从左到右就是 X、Y、Z。</li>
 *   <li><b>颜色</b>是预览方块 + 右边色号输入框：<b>点一下方块</b>才弹出取色板（"饱和度/明度方块 + 横向色相条"），
 *       也可在框里敲 {@code #RRGGBB}，两边实时同步。</li>
 * </ul>
 *
 * <p><b>布局网格</b>（"看着是一列"的保证）：水平分两列——左<b>标签列</b>、右<b>输入列</b>；名字框、简称框、
 * 三个坐标框、色号框与分组列表都从「输入列左边界」起画、共用同一条左边界，分组行与新分组输入框再统一内缩。
 * 竖直方向所有行高都是 {@code ROW_HEIGHT}、行距都是 {@code ROW_GAP}，颜色预览方块以 {@code ROW_HEIGHT} 为边长。
 */
public final class WaypointEditScreen extends Screen {

	// ---------------------------------------------------------------- 布局网格（一套常量推导到底）

	/** 输入列宽度：名字框的宽度，也是分组列表的宽度、三个坐标框拼起来的总宽。 */
	private static final int FIELD_WIDTH = 200;

	/** 标签列宽度：放得下最宽的那个字段名（英文 "Position"）再留一点缝。 */
	private static final int LABEL_WIDTH = 48;

	/** 内容区总宽（标签列 + 输入列）：整块按它水平居中，所以两列的左边界都从它算出来。 */
	private static final int CONTENT_WIDTH = LABEL_WIDTH + FIELD_WIDTH;

	/** 行高：所有输入框、按钮、颜色预览方块都是它，谁也不会比别人高半格。 */
	private static final int ROW_HEIGHT = 20;

	/** 行距上限/默认值：上下相邻两行（块）之间的空隙，整张表单统一。 */
	private static final int ROW_GAP = 8;

	/** 窗口矮时行距能压到的最小值（再小各块就贴在一起了）。 */
	private static final int MIN_ROW_GAP = 3;

	/** 行内文字相对行顶的竖直偏移：与原版 {@code EditBox} 同一套居中方式（文字高 8 像素）。 */
	private static final int ROW_TEXT_OFFSET = (ROW_HEIGHT - 8) / 2;

	/** 标题文字相对面板顶部的偏移。 */
	private static final int TITLE_TOP = 6;

	/** 标题那一行占的高度：内容（名字行）从它下面开始。 */
	private static final int HEADER_HEIGHT = 24;

	/** 分组列表的行高（与列表其它地方一致、行与行相连不留缝）。 */
	private static final int GROUP_ROW_HEIGHT = 13;

	/** 分组列表默认显示几行分组（下面还有一行"新分组"输入框）。 */
	private static final int GROUP_ROWS = 5;

	/** 分组列表在矮窗口下最少留几行。 */
	private static final int MIN_GROUP_ROWS = 2;

	/** 坐标框宽度：三个框加两道缝正好铺满输入列（{@code 3 * 62 + 2 * 7 = 200}）。 */
	private static final int COORD_BOX_WIDTH = 62;

	/** 坐标框之间的缝（由输入列宽反推，保证三个框右沿也是齐的）。 */
	private static final int COORD_GAP = (FIELD_WIDTH - COORD_BOX_WIDTH * 3) / 2;

	/** 颜色预览方块与色号输入框之间的缝。 */
	private static final int COLOR_GAP = 6;

	/** 手动输入色号那个输入框的宽度。 */
	private static final int COLOR_BOX_WIDTH = 76;

	/** 按钮行下沿到面板底部的空隙。 */
	private static final int BOTTOM_PAD = 6;

	/**
	 * 颜色预览方块的边长。取 {@link #ROW_HEIGHT}：与右边输入框同高才上下沿齐平（{@link #init} 里也不为它做竖直居中补偿）。
	 */
	private static final int PREVIEW_SIZE = ROW_HEIGHT;

	/** 从哪一层界面打开的；关掉时回到它（null = 直接回游戏）。 */
	private final Screen parent;

	/** 正在编辑的那一条；新建时为 null。 */
	private final Waypoint editing;

	/** 落点（在地图上右键就是在光标处新建；按 B 是玩家脚下）。 */
	private final WorldPos createdPos;

	/** 新建时预填的名字；只有"从聊天分享点进来"那一路非空，见构造函数的说明。 */
	private final String presetName;

	private EditBox nameBox;
	private EditBox shortBox;
	private EditBox boxX;
	private EditBox boxY;
	private EditBox boxZ;

	/** 手动输入色号的输入框（{@code #RRGGBB}）：与取色板是<b>两条并行</b>的入口，两边实时同步。 */
	private EditBox colorBox;

	/**
	 * 正在由代码回写色号（不是手敲的）。{@code setValue} 也会触发 responder，不设这个闸就会"回写 → 解析 → 再回写"绕圈。
	 */
	private boolean syncingColorText;

	/** "保存"：名字空着时置灰不可按（见 {@link #extractRenderState}）。 */
	private Button saveButton;

	private String group;
	/** 是不是"正在这一行里输入新分组名"。 */
	private boolean typingGroup;
	/** 已经打进去的分组名（回车即新建并选中它）。 */
	private String groupInput = "";

	private int color;

	/**
	 * 取色板：路径点颜色与叠加层网格颜色共用的<b>唯一</b>一份实现（见 {@link ColorPicker}）。
	 * 颜色由它算，游标落点、拖动、命中全在它内部；这里只用 {@code setColor / color / open / close} 与它打交道。
	 */
	private final ColorPicker colorPicker = new ColorPicker();

	/** 屏幕上可点的分组行（y → 分组名），每帧重算。 */
	private final List<int[]> groupRows = new ArrayList<>();
	private final List<String> groupNames = new ArrayList<>();
	/** 分组名输入框那一行在屏幕上的位置（每帧重算）。 */
	private int groupInputTop;

	/** 颜色预览方块的位置（每帧重算）。<b>高度与输入框一致</b>（见 {@link #PREVIEW_SIZE}）。 */
	private int previewLeft;
	private int previewTop;

	// ------------------------------------------------ 竖直排版（按窗口算，见 #layout）
	// 行距与分组行数都能收，整块高度随之变小，保证底部按钮在矮窗口下也留在窗内

	/** 这一帧的行距（默认 {@link #ROW_GAP}，窗口矮时收紧）。 */
	private int rowGap = ROW_GAP;
	/** 这一帧分组列表显示几行（默认 {@link #GROUP_ROWS}，窗口矮时减少）。 */
	private int groupRowCount = GROUP_ROWS;

	/**
	 * 分组列表的滚动（像素）：{@link #groupScrollTarget} 是目标，{@link #groupScroll} 每帧追过去。
	 *
	 * <p><b>为什么要能滚</b>：以前只画前 {@code groupRowCount} 个分组，多出来的分组<b>根本不显示</b>
	 * （看着像"分组有上限"）。现在列表可滚，还能滚多少由内容决定。
	 *
	 * <p>平滑手法与设置界面同一套：滚轮只改目标，实际位置按时间常数指数逼近，所以不会一格一格地跳。
	 */
	private double groupScroll;
	private double groupScrollTarget;
	private long groupScrollNanos;

	/** 分组列表平滑滚动的时间常数（秒）：越小越跟手。 */
	private static final double GROUP_SCROLL_TIME_CONSTANT = 0.055;

	private int nameTop;
	private int shortTop;
	private int groupTop;
	private int coordTop;
	private int colorTop;
	private int buttonTop;
	/** 表单整块的高度（由当前行距与分组行数推出）。 */
	private int formHeight;

	public WaypointEditScreen(Screen parent, Waypoint editing, WorldPos createdPos) {
		this(parent, editing, createdPos, null, "");
	}

	/**
	 * @param group 新建时预先选中的分组（管理器里"在某个分组下新建"用）；null = 默认分组
	 */
	WaypointEditScreen(Screen parent, Waypoint editing, WorldPos createdPos, @Nullable String group) {
		this(parent, editing, createdPos, group, "");
	}

	/**
	 * @param presetName 新建时预填的名字：聊天里点"[添加]"（别人分享过来的路径点）走这一条；平时新建传空字符串
	 */
	WaypointEditScreen(Screen parent, Waypoint editing, WorldPos createdPos, @Nullable String group,
			String presetName) {
		super(Component.translatable(editing == null
				? "screen.sinanmap.waypoint.title_new"
				: "screen.sinanmap.waypoint.title_edit"));
		this.parent = parent;
		this.editing = editing;
		this.createdPos = createdPos;
		this.presetName = presetName == null ? "" : presetName;
		this.group = editing != null ? editing.group()
				: (group == null || group.isBlank() ? Waypoint.DEFAULT_GROUP : group);
		this.color = editing != null ? editing.color() : Waypoint.DEFAULT_COLOR;
		// 先把颜色喂给取色板，打开时游标就落在当前颜色上（见 ColorPicker 的说明）
		colorPicker.setColor(color);
	}

	@Override
	protected void init() {
		layout();
		int top = formTop();
		int inputLeft = inputLeft();

		nameBox = new EditBox(font, inputLeft, top + nameTop, FIELD_WIDTH, ROW_HEIGHT,
				Component.translatable("screen.sinanmap.waypoint.name"));
		nameBox.setMaxLength(Waypoint.MAX_NAME_LENGTH);
		// 新建：只有"从聊天分享点进来"那一路才预填名字，其余留空
		nameBox.setValue(editing != null ? editing.name() : presetName);
		addRenderableWidget(nameBox);

		shortBox = new EditBox(font, inputLeft, top + shortTop, FIELD_WIDTH, ROW_HEIGHT,
				Component.translatable("screen.sinanmap.waypoint.short_name"));
		shortBox.setMaxLength(Waypoint.MAX_SHORT_NAME_LENGTH);
		shortBox.setHint(Component.translatable("screen.sinanmap.waypoint.short_name_hint"));
		shortBox.setValue(editing != null ? editing.shortName() : "");
		addRenderableWidget(shortBox);

		WorldPos pos = editing != null ? editing.pos() : createdPos;
		int coordY = top + coordTop;
		// 三个框从输入列左边界起、按 (COORD_BOX_WIDTH + COORD_GAP) 步进：加起来正好等于 FIELD_WIDTH，
		// 所以这一排与上面的名字框左右沿都对齐。顺序固定是 X、Y、Z
		boxX = coordinateBox(inputLeft, coordY, COORD_BOX_WIDTH, pos == null ? 0 : pos.blockX());
		boxY = coordinateBox(inputLeft + COORD_BOX_WIDTH + COORD_GAP, coordY,
				COORD_BOX_WIDTH, pos == null ? 64 : pos.blockY());
		boxZ = coordinateBox(inputLeft + (COORD_BOX_WIDTH + COORD_GAP) * 2, coordY,
				COORD_BOX_WIDTH, pos == null ? 0 : pos.blockZ());

		// 颜色那一行：预览方块（点它开关取色板）+ 手动输入色号的框。方块与输入框同高（PREVIEW_SIZE == ROW_HEIGHT），
		// 所以两者都从 COLOR_TOP 起、上下沿齐平；方块占输入列的起点，色号框紧跟在它右边
		colorBox = new EditBox(font, inputLeft + PREVIEW_SIZE + COLOR_GAP,
				top + colorTop, COLOR_BOX_WIDTH, ROW_HEIGHT,
				Component.translatable("screen.sinanmap.waypoint.color"));
		colorBox.setMaxLength(7);
		colorBox.setHint(Component.literal("#RRGGBB"));
		colorBox.setResponder(this::onColorTextChanged);
		setColorText(hex(color));
		addRenderableWidget(colorBox);

		// 按钮：取消在最左、保存在最右（与"先取消、后确认"的读法一致）
		int buttonWidth = 68;
		int gap = 4;
		int count = editing == null ? 2 : 3;
		// 按钮也按窗口分摊宽度：三颗加起来（212）在窄窗口下会顶到屏外
		buttonWidth = Math.clamp((width - 16 - gap * (count - 1)) / count, 50, buttonWidth);
		int x = width / 2 - (buttonWidth * count + gap * (count - 1)) / 2;
		int buttonY = top + buttonTop;
		addRenderableWidget(Button.builder(Component.translatable("screen.sinanmap.waypoint.cancel"),
						button -> onClose())
				.bounds(x, buttonY, buttonWidth, ROW_HEIGHT).build());
		x += buttonWidth + gap;
		if (editing != null) {
			addRenderableWidget(Button.builder(Component.translatable("screen.sinanmap.waypoint.delete"),
							button -> delete())
					.bounds(x, buttonY, buttonWidth, ROW_HEIGHT).build());
			x += buttonWidth + gap;
		}
		saveButton = Button.builder(Component.translatable("screen.sinanmap.waypoint.save"),
						button -> save())
				.bounds(x, buttonY, buttonWidth, ROW_HEIGHT).build();
		addRenderableWidget(saveButton);

		// 名字框先拿到焦点：中文输入法立刻就绪
		setInitialFocus(nameBox);
	}

	/**
	 * 按当前窗口算一遍竖直排版：默认行距与分组行数放不下时，先把行距压到 {@link #MIN_ROW_GAP}，
	 * 还不够就减少分组行数，直到整块（含底部按钮）落在窗口里。
	 */
	private void layout() {
		rowGap = ROW_GAP;
		groupRowCount = GROUP_ROWS;
		while (true) {
			computeTops();
			if (formHeight <= height - 4
					|| (rowGap <= MIN_ROW_GAP && groupRowCount <= MIN_GROUP_ROWS)) {
				break;
			}
			if (rowGap > MIN_ROW_GAP) {
				rowGap--;
			} else {
				groupRowCount--;
			}
		}
	}

	/** 由行距与分组行数推出各行顶部（{@link #layout} 反复调用它试算）。 */
	private void computeTops() {
		nameTop = HEADER_HEIGHT;
		shortTop = nameTop + ROW_HEIGHT + rowGap;
		groupTop = shortTop + ROW_HEIGHT + rowGap;
		coordTop = groupTop + (groupRowCount + 1) * GROUP_ROW_HEIGHT + rowGap;
		colorTop = coordTop + ROW_HEIGHT + rowGap;
		buttonTop = colorTop + ROW_HEIGHT + rowGap;
		formHeight = buttonTop + ROW_HEIGHT + BOTTOM_PAD;
	}

	/** 表单顶部（内容竖直居中；窗口太矮时也留出 4 像素）。 */
	private int formTop() {
		return Math.max(4, (height - formHeight) / 2);
	}

	/**
	 * 内容区的左边界：整块（标签列 + 输入列）的水平居中位置。所有横向坐标都从这里派生，所以各控件不会各偏各的；
	 * 窄窗口下至少留 4 像素边距。
	 */
	private int contentLeft() {
		return Math.max(4, (width - CONTENT_WIDTH) / 2);
	}

	/** 输入列的左边界：名字框、坐标框、色号框、分组列表（含标题）都从这里起画，是全界面唯一那条"控件左边界"。 */
	private int inputLeft() {
		return contentLeft() + LABEL_WIDTH;
	}

	/** 行内文字的竖直位置：与原版输入框同一套居中方式，标签与框的基线因此始终一致。 */
	private static int rowTextY(int rowTop) {
		return rowTop + ROW_TEXT_OFFSET;
	}

	/**
	 * 一个坐标输入框：框里是空的，自动取来的坐标只作为<b>灰色提示</b>（{@code hint}）显示，
	 * 这样"用自动值"与"手动改过"一眼分得开，也不必先删预填内容。三个框从左到右就是 X、Y、Z。
	 */
	private EditBox coordinateBox(int x, int y, int boxWidth, int value) {
		EditBox box = new EditBox(font, x, y, boxWidth, ROW_HEIGHT, Component.literal(""));
		box.setMaxLength(9);
		box.setHint(Component.literal(String.valueOf(value)));
		addRenderableWidget(box);
		return box;
	}

	/** 输入框里的坐标；留空（没写）就用自动取来的那个。 */
	private static int coordinate(EditBox box, int fallback) {
		String text = box.getValue().strip();
		if (text.isEmpty() || text.equals("-")) {
			return fallback;
		}
		try {
			return Integer.parseInt(text);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		// 压一层半透明底：这是浮在地图（或游戏画面）之上的对话框
		graphics.fill(0, 0, width, height, 0xC0000000);

		layout();
		int top = formTop();
		graphics.centeredText(font, title, width / 2, top + TITLE_TOP, 0xFFFFFFFF);

		// 名字空着就不能保存：路径点的名字是它的身份，存储与界面都要求非空
		saveButton.active = !nameBox.getValue().isBlank();

		// 每一行都从同一套网格常量取位置：标签在标签列、控件在输入列
		rowLabel(graphics, "screen.sinanmap.waypoint.name", top + nameTop);
		rowLabel(graphics, "screen.sinanmap.waypoint.short_name", top + shortTop);
		drawGroupTitle(graphics, top);
		drawGroupList(graphics, mouseX, mouseY, top);
		rowLabel(graphics, "screen.sinanmap.waypoint.coord", top + coordTop);
		drawColorRow(graphics, top, mouseX, mouseY);
		layoutColorPanel(top);

		// 控件画在最上层，所以放在最后；取色板又要压在控件之上（它是弹出来的）
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		// 取色板只有在打开时才会画，开没开由它自己判断
		colorPicker.render(graphics);
		// 鼠标样式最后设：一帧里最后一次 requestCursor 才生效（见 updateCursor）
		updateCursor(graphics, mouseX, mouseY);
	}

	/**
	 * 按光标位置请求鼠标样式。
	 *
	 * <p>{@link GuiGraphicsExtractor#requestCursor(CursorType)} 一帧里<b>最后</b>一次请求生效（{@code GameRenderer}
	 * 在 GUI 画完之后才 apply），所以这一段排在最后，盖过控件自己的请求。
	 *
	 * <p><b>取色板开着 / 正在拖时不往下判</b>：它浮在最上面，底下露出来的输入框不该把光标抢过去。
	 *
	 * <ul>
	 *   <li>输入框（名字 / 简称 / 坐标 / 色号）→ {@link CursorTypes#IBEAM}</li>
	 *   <li>取色板：色相条 → {@link CursorTypes#RESIZE_EW}；饱和度/明度方块 → {@link CursorTypes#CROSSHAIR}；内边距 → 默认箭头</li>
	 *   <li>分组行 / 新分组输入框 / 颜色方块 / 按钮 → {@link CursorTypes#POINTING_HAND}</li>
	 *   <li>其余 → {@link CursorTypes#ARROW}</li>
	 * </ul>
	 */
	private void updateCursor(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		// 正在拖：拖到哪儿都保持那个样式（拖动中本来就不该看底下的控件）
		int dragKind = colorPicker.dragKind();
		if (dragKind != ColorPicker.DRAG_NONE) {
			graphics.requestCursor(dragKind == ColorPicker.DRAG_SV
					? CursorTypes.CROSSHAIR : CursorTypes.RESIZE_EW);
			return;
		}
		// 取色板浮在最上面：落在板上就按板上的东西给样式，并<b>到此为止</b>（外框那圈描边也算在板内，
		// 否则压着边框时还是会漏到底下的控件）。contains 只在板打开时才为真
		if (colorPicker.contains(mouseX, mouseY)) {
			int hover = colorPicker.hoverKind(mouseX, mouseY);
			CursorType onPanel = hover == ColorPicker.DRAG_SV ? CursorTypes.CROSSHAIR
					: hover == ColorPicker.DRAG_HUE ? CursorTypes.RESIZE_EW : CursorTypes.ARROW;
			graphics.requestCursor(onPanel);
			return;
		}
		CursorType cursor = CursorTypes.ARROW;
		if (pointsAtInput(mouseX, mouseY)) {
			cursor = CursorTypes.IBEAM;
		} else if (pointsAtWidget(mouseX, mouseY)
				|| groupIndexAt(mouseX, mouseY) >= 0
				|| inside(mouseX, mouseY, inputLeft(), groupInputTop, FIELD_WIDTH, GROUP_ROW_HEIGHT)
				|| inside(mouseX, mouseY, previewLeft - 1, previewTop - 1, PREVIEW_SIZE + 2, PREVIEW_SIZE + 2)) {
			cursor = CursorTypes.POINTING_HAND;
		}
		graphics.requestCursor(cursor);
	}

	/** 光标是不是停在输入框（名字 / 坐标 / 色号）上。 */
	private boolean pointsAtInput(double mouseX, double mouseY) {
		for (GuiEventListener child : children()) {
			if (child instanceof EditBox box && box.isMouseOver(mouseX, mouseY)) {
				return true;
			}
		}
		return false;
	}

	/** 光标是不是停在一个"能按的控件"上（取消 / 删除 / 保存）。 */
	private boolean pointsAtWidget(double mouseX, double mouseY) {
		for (GuiEventListener child : children()) {
			if (child instanceof AbstractWidget widget && widget.isMouseOver(mouseX, mouseY)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 颜色那一行：预览方块 + 右边紧跟的色号输入框。方块<b>点一下才展开</b>取色板（见 {@link ColorPicker}）；
	 * 方块画在输入列起点、与名字框共用左边界，边长取 {@link #ROW_HEIGHT}，与右边框上下沿齐平。
	 */
	private void drawColorRow(GuiGraphicsExtractor graphics, int top, int mouseX, int mouseY) {
		rowLabel(graphics, "screen.sinanmap.waypoint.color", top + colorTop);
		previewLeft = inputLeft();
		previewTop = top + colorTop;
		// 这一小块本身就是"点开取色板"的按钮，所以边框跟着鼠标走：常态一圈暗的、
		// 悬停/打开时套一圈亮的——与自绘按钮同一套表现
		boolean hovered = inside(mouseX, mouseY, previewLeft, previewTop, PREVIEW_SIZE, PREVIEW_SIZE);
		// 边框画在方块<b>自己的边界上</b>（不是往外扩一圈）：往外扩的话整块是 22 像素高，比右边
		// ROW_HEIGHT 的输入框高出两像素，看着就是"没对齐"。画在边界上，外沿正好齐平。
		SinanStyle.outline(graphics, previewLeft, previewTop, PREVIEW_SIZE, PREVIEW_SIZE,
				hovered || colorPicker.isOpen() ? SinanStyle.BORDER_HOVER : SinanStyle.BORDER_IDLE);
		graphics.fill(previewLeft + 1, previewTop + 1,
				previewLeft + PREVIEW_SIZE - 1, previewTop + PREVIEW_SIZE - 1, color);
	}

	/**
	 * 把取色板摆到屏幕上的哪块位置（大小由 {@link ColorPicker} 自己定）。
	 *
	 * <p><b>绝不盖住颜色那一行</b>：优先贴在颜色行<b>下面</b>；下面被按钮挡住就整块抬到颜色行<b>上面</b>——
	 * 名字、简称、坐标那几行多半已填完，被压住没关系，颜色行必须看得见。
	 */
	private void layoutColorPanel(int top) {
		int panelWidth = colorPicker.width();
		int panelHeight = colorPicker.height();
		int colorRowTop = top + colorTop;
		int below = colorRowTop + ROW_HEIGHT + 4;
		int panelTopWanted = below + panelHeight <= height - 4
				? below
				: colorRowTop - 4 - panelHeight;
		int panelTop = (int) Math.clamp(panelTopWanted, 4, Math.max(4, height - panelHeight - 4));
		int panelLeft = Math.clamp(previewLeft, 4, Math.max(4, width - panelWidth - 4));
		colorPicker.setPosition(panelLeft, panelTop);
	}

	/** 一个颜色写成 {@code #RRGGBB}。 */
	private static String hex(int argb) {
		return "#%06X".formatted(argb & 0xFFFFFF);
	}

	/** 手输色号：接受 {@code #RRGGBB} 或 {@code RRGGBB}，写得不成样子就先不管（继续敲就是了）。 */
	private void onColorTextChanged(String text) {
		if (syncingColorText) {
			return;
		}
		String digits = text.strip();
		if (digits.startsWith("#")) {
			digits = digits.substring(1);
		}
		if (digits.length() != 6) {
			return;
		}
		int parsed;
		try {
			parsed = Integer.parseInt(digits, 16);
		} catch (NumberFormatException e) {
			return;
		}
		color = 0xFF000000 | parsed;
		// 手敲的色号也是"当前颜色"：喂回取色板，游标跟着挪过去，两边始终是同一个颜色
		colorPicker.setColor(color);
	}

	/** 从取色板取回颜色，并把色号同步回右边那个输入框（拖动 / 点击取色时调）。 */
	private void syncColorFromPicker() {
		color = colorPicker.color();
		setColorText(hex(color));
	}

	/** 把色号回写进输入框（拖动取色板时用；{@link #syncingColorText} 挡住 responder 的绕圈）。 */
	private void setColorText(String text) {
		syncingColorText = true;
		try {
			colorBox.setValue(text);
		} finally {
			syncingColorText = false;
		}
	}

	private boolean inside(double mouseX, double mouseY, int x, int y, int w, int h) {
		return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
	}

	/**
	 * 分组列表（含标题、最后一行那个"新分组"输入框）的左边界：直接取 {@link #inputLeft()}，
	 * 与名字框、坐标框、色号框共用同一条左边界，整张界面看上去就是一列对齐的。
	 */
	private int groupListLeft() {
		return inputLeft();
	}

	/**
	 * 分组行的文字相对左边线往里缩多少（标题与每行文字共用，三者左边界严格一致）。
	 * 底纹（选中 / 悬停 / 输入框）从 {@link #groupListLeft()} 起画、宽 {@link #FIELD_WIDTH}，与上面的输入框严丝合缝。
	 */
	private static final int GROUP_TEXT_INSET = 4;

	/** 光标是不是落在分组这一列的第 {@code y} 行上（列表行与"新分组"输入框共用同一块矩形）。 */
	private boolean insideGroupColumn(double mouseX, double mouseY, int y) {
		return inside(mouseX, mouseY, groupListLeft(), y, FIELD_WIDTH, GROUP_ROW_HEIGHT);
	}

	/**
	 * 分组的标题：与左边那列"名称 / 坐标 / 颜色"标签同处一列（左边界 {@link #contentLeft()}），并与分组第一行居中对齐。
	 * y 用 {@link #groupRowTextY}（分组行高 {@link #GROUP_ROW_HEIGHT} 与其他行不同，不能套 {@link #rowTextY}）。
	 */
	private void drawGroupTitle(GuiGraphicsExtractor graphics, int top) {
		graphics.text(font, Component.translatable("screen.sinanmap.waypoint.group"),
				contentLeft(), groupRowTextY(top + groupTop), 0xFFA0A0A0, false);
	}

	/** 光标落在第几个分组行上；不在分组列表里返回 -1（点击与鼠标样式共用同一套判定）。 */
	private int groupIndexAt(double mouseX, double mouseY) {
		for (int i = 0; i < groupRows.size(); i++) {
			int[] row = groupRows.get(i);
			if (insideGroupColumn(mouseX, mouseY, row[0])) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * 分组那一列：一行一个分组，选中的那行高亮；最后一行是输入新分组的输入框。
	 *
	 * <p>列表<b>可滚动</b>：一屏只放得下 {@link #groupRowCount} 行，装不下的靠滚轮（见
	 * {@link #mouseScrolled}），滚动是平滑的。行只在视口内才登记进 {@link #groupRows}
	 * （命中判定与点击取的是同一份），画出去的行由裁剪框切掉。
	 */
	private void drawGroupList(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int top) {
		String dimension = currentDimension();
		groupRows.clear();
		groupNames.clear();
		List<String> groups = WaypointManager.instance().groupsIn(Minecraft.getInstance(), dimension);
		if (!groups.contains(group)) {
			// 正在编辑的这一条落在一个已经不存在的分组里（比如组里别的都删了）：也列出来
			groups.add(group);
		}
		int left = groupListLeft();
		int listTop = top + groupTop;
		int viewport = groupRowCount * GROUP_ROW_HEIGHT;
		int content = groups.size() * GROUP_ROW_HEIGHT;
		groupScrollTarget = Math.clamp(groupScrollTarget, 0.0, Math.max(0, content - viewport));
		tickGroupScroll();

		int y = listTop - (int) Math.round(groupScroll);
		graphics.enableScissor(left, listTop, left + FIELD_WIDTH, listTop + viewport);
		for (String name : groups) {
			boolean selected = !typingGroup && name.equals(group);
			boolean hovered = insideGroupColumn(mouseX, mouseY, y);
			if (selected) {
				graphics.fill(left, y, left + FIELD_WIDTH, y + GROUP_ROW_HEIGHT, 0x40FFFFFF);
			} else if (hovered) {
				graphics.fill(left, y, left + FIELD_WIDTH, y + GROUP_ROW_HEIGHT, 0x20FFFFFF);
			}
			graphics.text(font, name, left + GROUP_TEXT_INSET, groupRowTextY(y),
					selected ? 0xFFFFFFFF : 0xFFC8C8C8, false);
			// 只登记"整行都在视口里"的那些：滚动到一半的行点上去该点不到
			if (y >= listTop && y + GROUP_ROW_HEIGHT <= listTop + viewport) {
				groupRows.add(new int[] {y, y + GROUP_ROW_HEIGHT});
				groupNames.add(name);
			}
			y += GROUP_ROW_HEIGHT;
		}
		graphics.disableScissor();
		drawGroupScrollbar(graphics, left, listTop, viewport, content);
		// 输入框紧跟"最后一行分组"之后——它本身就是列表的最后一项。
		// 分组多到装不下时才退回贴住列表底边：那时"最后一行"已经被挤到视口外了，跟着它只会跑到看不见的地方。
		drawGroupInput(graphics, mouseX, mouseY, left,
				content <= viewport ? y : listTop + viewport);
	}

	/** 分组列表的滚动条：装得下就不画（没有可滚的东西时那条灰杠反而是干扰）。 */
	private void drawGroupScrollbar(GuiGraphicsExtractor graphics, int left, int listTop,
			int viewport, int content) {
		int maxScroll = content - viewport;
		if (maxScroll <= 0) {
			return;
		}
		int barHeight = Math.max(8, Math.round(viewport * viewport / (float) content));
		int barY = listTop + Math.round((viewport - barHeight) * (float) (groupScroll / maxScroll));
		int right = left + FIELD_WIDTH;
		graphics.fill(right - 3, listTop, right - 2, listTop + viewport, 0x20FFFFFF);
		graphics.fill(right - 3, barY, right - 2, barY + barHeight, 0x80FFFFFF);
	}

	/** 让 {@link #groupScroll} 按时间常数指数逼近目标（与设置界面同一套）。 */
	private void tickGroupScroll() {
		if (Math.abs(groupScrollTarget - groupScroll) < 0.5) {
			groupScroll = groupScrollTarget;
			groupScrollNanos = 0L;
			return;
		}
		long now = System.nanoTime();
		double dt = groupScrollNanos == 0L ? 1.0 / 60.0 : (now - groupScrollNanos) / 1.0e9;
		groupScrollNanos = now;
		double factor = 1.0 - Math.exp(-Math.clamp(dt, 0.0, 0.1) / GROUP_SCROLL_TIME_CONSTANT);
		groupScroll += (groupScrollTarget - groupScroll) * factor;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		// 光标在分组那一列上才滚分组列表；其余位置（表单别处）没有可滚的东西
		if (inside(mouseX, mouseY, groupListLeft(), groupTopOfList(), FIELD_WIDTH,
				groupRowCount * GROUP_ROW_HEIGHT)) {
			groupScrollTarget -= vertical * GROUP_ROW_HEIGHT * 2.0;
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
	}

	/** 分组列表的竖直范围（滚动判定与滚动条共用）。 */
	private int groupTopOfList() {
		return formTop() + groupTop;
	}

	/**
	 * 分组列表最后一行的输入框：<b>就在这里打字，回车即新建分组</b>。样式与上面的分组行一致，
	 * 只是底纹更亮表示"这里可以打字"；空着且没在输入时显示灰字提示。
	 */
	private void drawGroupInput(GuiGraphicsExtractor graphics, double mouseX, double mouseY, int left, int y) {
		groupInputTop = y;
		boolean hovered = insideGroupColumn(mouseX, mouseY, y);
		graphics.fill(left, y, left + FIELD_WIDTH, y + GROUP_ROW_HEIGHT,
				typingGroup ? 0x40FFFFFF : hovered ? 0x28FFFFFF : 0x18FFFFFF);
		if (typingGroup || !groupInput.isEmpty()) {
			graphics.text(font, groupInput, left + GROUP_TEXT_INSET, groupRowTextY(y), 0xFFFFFFFF, false);
		} else {
			graphics.text(font, Component.translatable("screen.sinanmap.waypoint.group_new_hint").getString(),
					left + GROUP_TEXT_INSET, groupRowTextY(y), 0xFF8A8A8A, false);
		}
		if (typingGroup) {
			// 光标：半秒一闪，画在已经打进去的字后面
			int caretX = left + GROUP_TEXT_INSET + font.width(groupInput);
			if (System.nanoTime() / 500_000_000L % 2L == 0L) {
				graphics.fill(caretX, groupRowTextY(y), caretX + 1,
						groupRowTextY(y) + font.lineHeight, 0xFFFFFFFF);
			}
		}
	}

	/** 分组行里的文字画在哪（行高与文字高度的差取一半，上下就对称）。 */
	private int groupRowTextY(int rowY) {
		return rowY + (GROUP_ROW_HEIGHT - font.lineHeight) / 2;
	}

	/**
	 * 行标题（名字 / 坐标 / 颜色）：<b>左对齐到标签列</b>（{@link #contentLeft()}），竖直位置取 {@link #rowTextY}，
	 * 三个标签因此排成一条竖线、且与各自那行的输入框基线一致。
	 */
	private void rowLabel(GuiGraphicsExtractor graphics, String key, int rowTop) {
		graphics.text(font, Component.translatable(key), contentLeft(), rowTextY(rowTop), 0xFFA0A0A0, false);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mouseX = event.x();
		double mouseY = event.y();

		// 取色板开着时它最优先：点方块 / 点色相条都算在选颜色
		if (colorPicker.isOpen()) {
			if (colorPicker.mouseClicked(mouseX, mouseY)) {
				syncColorFromPicker();
				return true;
			}
			// 点在取色板之外（含内边距那圈）：收起它，这一下不再做别的事
			// （否则刚合上就顺手点到了底下的控件）
			colorPicker.close();
			return true;
		}
		// 点颜色方块：展开取色板（见 {@link ColorPicker}），先把当前颜色喂进去，游标才落在它上面
		if (inside(mouseX, mouseY, previewLeft - 1, previewTop - 1, PREVIEW_SIZE + 2, PREVIEW_SIZE + 2)) {
			colorPicker.setColor(color);
			colorPicker.open();
			return true;
		}
		int groupRow = groupIndexAt(mouseX, mouseY);
		if (groupRow >= 0) {
			stopTypingGroup();
			group = groupNames.get(groupRow);
			return true;
		}
		if (insideGroupColumn(mouseX, mouseY, groupInputTop)) {
			startTypingGroup();
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (colorPicker.dragKind() != ColorPicker.DRAG_NONE) {
			colorPicker.mouseDragged(event.x(), event.y());
			syncColorFromPicker();
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		colorPicker.mouseReleased();
		return super.mouseReleased(event);
	}

	private void startTypingGroup() {
		if (!typingGroup) {
			typingGroup = true;
			groupInput = "";
			// 焦点从名字框上拿开：不然打字会同时进名字框（原版输入框会吃掉键盘事件）
			setFocused(null);
		}
	}

	private void stopTypingGroup() {
		if (typingGroup) {
			typingGroup = false;
			groupInput = "";
			setFocused(nameBox);
		}
	}

	/** 回车：把输入框里的分组名收下来（空的不算），并选中它。 */
	private void commitGroupInput() {
		String typed = groupInput.strip();
		stopTypingGroup();
		if (!typed.isEmpty()) {
			group = typed;
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (typingGroup) {
			if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
				commitGroupInput();
				return true;
			}
			if (event.key() == GLFW.GLFW_KEY_BACKSPACE) {
				if (!groupInput.isEmpty()) {
					groupInput = groupInput.substring(0, groupInput.length() - 1);
				}
				return true;
			}
			if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
				// 第一下 Esc 只是退出"输入分组名"，不关整个界面
				stopTypingGroup();
				return true;
			}
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (typingGroup) {
			if (event.isAllowedChatCharacter() && groupInput.length() < Waypoint.MAX_GROUP_LENGTH) {
				groupInput += event.codepointAsString();
			}
			return true;
		}
		return super.charTyped(event);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		// 直接换回上一层，而不是依赖 GUI 层的栈：从按键直接打开时没有上一层，那时回游戏画面
		Minecraft.getInstance().setScreen(parent);
	}

	private String currentDimension() {
		Minecraft minecraft = Minecraft.getInstance();
		if (editing != null) {
			return editing.pos().dimension();
		}
		if (createdPos != null) {
			return createdPos.dimension();
		}
		return minecraft.level == null ? "" : minecraft.level.dimension().identifier().toString();
	}

	/** 保存：新建走 {@code create}、编辑走 {@code update}；坐标以调用方给的那个为基准，框里填了就按填的来。 */
	private void save() {
		Minecraft minecraft = Minecraft.getInstance();
		WaypointManager manager = WaypointManager.instance();
		String name = nameBox.getValue().strip();
		if (name.isEmpty()) {
			// 名字是这条路径点的身份："保存"在这种时候本来就是灰的，这里再挡一道
			return;
		}
		if (typingGroup) {
			commitGroupInput();
		}
		String shortName = shortBox.getValue().strip();
		// 基准位置 = 调用方给的那个（地图上右键的那一格 / 玩家脚下）；三个框留空就等于用它，
		// 填了就用填的（分享过来的路径点 Y 不对时也能在这里校准）
		WorldPos base = editing != null ? editing.pos() : createdPos;
		if (base == null) {
			return;
		}
		WorldPos pos = new WorldPos(base.dimension(),
				coordinate(boxX, base.blockX()),
				coordinate(boxY, base.blockY()),
				coordinate(boxZ, base.blockZ()));
		if (editing != null) {
			manager.update(minecraft, editing.withName(name).withShortName(shortName)
					.withGroup(group).withPos(pos).withColor(color));
		} else {
			manager.create(minecraft, name, pos, group, color, shortName);
		}
		onClose();
	}

	private void delete() {
		if (editing != null) {
			WaypointManager.instance().remove(Minecraft.getInstance(), editing.id());
		}
		onClose();
	}
}
