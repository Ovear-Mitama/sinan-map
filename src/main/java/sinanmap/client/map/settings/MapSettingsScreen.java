package sinanmap.client.map.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.Function;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import com.mojang.blaze3d.platform.cursor.CursorType;
import com.mojang.blaze3d.platform.cursor.CursorTypes;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;

import sinanmap.common.hud.MinimapSettings;
import sinanmap.common.map.LayerMode;
import sinanmap.client.gui.SinanButton;
import sinanmap.client.gui.SinanCycleButton;
import sinanmap.client.gui.SinanSlider;
import sinanmap.client.gui.SinanStyle;
import sinanmap.client.gui.SinanTextField;
import sinanmap.client.map.MinimapHud;
import sinanmap.client.map.WorldMapScreen;

/**
 * 地图设置（世界地图左上角那颗设置按钮、或"打开地图设置"快捷键打开）。
 *
 * <p><b>首页是大项入口，点进去才是分项</b>：首页只有一颗颗大项按钮（小地图 / 雷达 / 路径点 / 世界地图），
 * 每一项一页，页内两列排、可平滑滚动——项只会越加越多，分页之后每页都很短。
 *
 * <p><b>控件沿用自绘风格</b>（{@link SinanButton} / {@link SinanSlider} / {@link SinanTextField}）；
 * 位置与大小、形状这类"要对着真实画面拖"的东西放在 {@link HudEditScreen}。
 */
public final class MapSettingsScreen extends Screen {

	private static final int ROW_HEIGHT = 18;
	private static final int ROW_PITCH = 22;
	private static final int COLUMN_GAP = 10;

	/** 同一行里并排的两个控件之间留的缝。 */
	private static final int WIDGET_GAP = 2;

	/** 各页的编号：0 = 首页，其余是大项。 */
	private static final int PAGE_HOME = 0;
	private static final int PAGE_MINIMAP = 1;
	private static final int PAGE_INFO = 2;
	private static final int PAGE_RADAR = 3;
	private static final int PAGE_WAYPOINT = 4;
	private static final int PAGE_WORLD = 5;
	private static final int PAGE_PERFORMANCE = 6;
	private static final int PAGE_OVERLAY = 7;

	/** 信息排序页：从"信息显示"进去的二级页，不在首页那一格里。 */
	private static final int PAGE_INFO_ORDER = 8;

	/** 首页上有几个大项（编号 1..{@value}）。 */
	private static final int PAGE_COUNT = 7;

	/**
	 * 整块面板的宽度上限（两列加起来）。
	 *
	 * <p>取 {@code 150 × 2 + 10}：单列正好是原版按钮的默认宽度 150，列间距 10。
	 */
	private static final int PANEL_MAX = 310;

	/** 顶部标题条的高度：只比标题文字本身高一点点。 */
	private static final int HEADER_HEIGHT = 12;

	/** 标题文字相对原版字号的缩放（比原版字号略大）。 */
	private static final float TITLE_SCALE = 0.8F;

	/** 标题条与两列内容之间留的空隙。 */
	private static final int HEADER_GAP = 6;

	/** 顶部标题条的 y：整体再往下挪，别贴近世界地图顶部那行 XYZ 坐标。 */
	private static final int TITLE_Y = 40;

	/** 平滑滚动的时间常数（秒）：越小越跟手。 */
	private static final double SCROLL_TIME_CONSTANT = 0.055;

	/**
	 * 一列最多摆几行：两列共 {@code 2 × 5 = 10} 行，再多靠滚动；底部按钮落在第 {@value}+1 行。
	 */
	private static final int VISIBLE_ROWS = 5;

	/** 底部固定那条"返回 / 完成"的高度与间距；宽度取一列（见 {@link #addBottomBar()}）。 */
	private static final int BOTTOM_BUTTON_HEIGHT = 18;

	/** 切页时内容从右侧滑入的距离与时长。 */
	private static final int PAGE_SLIDE_DISTANCE = 16;
	private static final long PAGE_SLIDE_NANOS = 140_000_000L;

	/** 提示文字超过这个宽度就折行（GUI 像素）；底色比按钮更透。 */
	private static final int TOOLTIP_MAX_WIDTH = 170;
	private static final int TOOLTIP_BACKGROUND = 0xB0101014;

	/** 按钮文字配色（沿用 DE 的绿色确认 / 红色取消）。 */
	private static final int COLOR_CONFIRM = 0xFFB7F3C8;
	private static final int COLOR_CANCEL = 0xFFFC887E;

	/** 信息排序页那条居中列表的宽度。 */
	private static final int ORDER_LIST_WIDTH = 240;

	/** 换公里显示的距离挡位（这五挡，默认 10000）。 */
	private static final List<Integer> KM_STEPS = MinimapSettings.KM_THRESHOLD_STEPS;

	/** 缩放挡位：最左是"自动"（-1），其余是每个设备像素代表多少方块。 */
	private static final List<Double> ZOOM_STEPS =
			List.of(-1.0, 0.125, 0.25, 0.5, 1.0, 2.0, 4.0, 8.0, 16.0);

	private final Screen parent;
	private final MinimapSettings settings;
	private final MinimapHud minimap;

	/**
	 * 一条设置项：这一行的控件、基准 x、提示、基准 y（滚动与切页动画前的原始位置）。
	 *
	 * <p>基准位置要留着：每帧都是"基准值 + 偏移"重算，累加会随帧率飘。
	 */
	private record Entry(List<AbstractWidget> widgets, List<Integer> baseXs, String tipKey, int baseY) {
	}

	/** 还没分配列的"待排"行：若干个并排的控件（宽度 0 = 占满剩余）。 */
	private static final class Row {

		final List<AbstractWidget> widgets = new ArrayList<>();
		final List<Integer> widths = new ArrayList<>();
		String tipKey;
		int height;

		Row(int height) {
			this.height = height;
		}
	}

	private final List<Entry> entries = new ArrayList<>();
	private final List<Row> pending = new ArrayList<>();

	/** 不参与滚动的那几颗固定按钮（底部"返回 / 完成"、重置确认框）：鼠标样式判定要用到。 */
	private final List<AbstractWidget> fixedWidgets = new ArrayList<>();

	/** 当前页：0 = 首页（大项入口），1..4 = 各分项页。 */
	private int page;

	private int panelX;
	private int panelWidth;
	private int leftX;
	private int rightX;
	private int columnWidth;
	private int listTop;
	private int listBottom;
	private int contentHeight;

	private double scroll;
	private int scrollTarget;
	private long lastScrollNanos;

	/** 切页动画的起始时刻（纳秒）；0 = 不在动画里。 */
	private long pageSlideStartNanos;

	private final int[] cursor = new int[2];

	/** 读盘线程那挡：最左是自动，其余按"核数感"往上走，到 {@link MinimapSettings#MAX_PREFETCH_THREADS}。 */
	private static final List<Integer> PREFETCH_THREAD_STEPS = List.of(
			MinimapSettings.AUTO, 1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64);

	/** 洞穴扫描线程那挡：最左是自动，其余 1~{@link MinimapSettings#MAX_CAVE_SCAN_THREADS} 条。 */
	private static final List<Integer> CAVE_THREAD_STEPS =
			List.of(MinimapSettings.AUTO, 1, 2, 3, 4, 5, 6, 7, 8);

	/** 其余性能项的挡位：最左一律是"自动"，往右依次变大。 */
	private static final List<Integer> APPLIED_STEPS =
			List.of(MinimapSettings.AUTO, 32, 64, 128, 256, 512);
	private static final List<Integer> BUDGET_STEPS =
			List.of(MinimapSettings.AUTO, 1, 2, 3, 4, 6, 8, 16);
	private static final List<Integer> CHUNKS_STEPS =
			List.of(MinimapSettings.AUTO, 8, 16, 32, 64, 128, 256);
	private static final List<Integer> FILLING_PAGES_STEPS =
			List.of(MinimapSettings.AUTO, 2, 4, 8, 16, 32);
	private static final List<Integer> PAGES_PER_FRAME_STEPS =
			List.of(MinimapSettings.AUTO, 1, 2, 3, 4);

	/** 图标缩放的挡位：最左是自动（用 -1 表示），其余是可手选的缩放。 */
	private static final List<Double> HUD_SCALE_STEPS =
			List.of(-1.0, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0);

	/** 用不到的控件置灰。 */
	private SinanSlider radarRadiusSlider;
	private SinanCycleButton<Boolean> radarHostileToggle;
	private SinanCycleButton<Integer> gridColorButton;

	/** 是否正处于"重置设置"的确认态（这一态下只画确认框）。 */
	private boolean confirmingReset;

	/** 确认框那两颗按钮：每帧要一起设淡入进度（它们与底框、文字是两套绘制路径，得手动对齐）。 */
	private final List<SinanButton> confirmButtons = new ArrayList<>();

	/** 确认框出现的时刻（0 = 已经不用动画了），见 {@link #resetConfirmFade}。 */
	private long resetConfirmNanos;

	/** 标题最右侧的全局搜索框（自绘，样式与 x/y 输入框同一套）。 */
	private SinanTextField searchBox;
	private String searchQuery = "";

	/** 全局搜索框的尺寸：贴在标题条最右端、与标题条同高（和标题右侧贴合）。 */
	private static final int SEARCH_WIDTH = 120;
	private static final int SEARCH_HEIGHT = HEADER_HEIGHT;

	/** 一条可被全局搜索命中的设置：它属于哪一页、标签的翻译键。 */
	private record SearchEntry(int page, String labelKey) {
	}

	/**
	 * 全局搜索索引：覆盖所有分项页里每一个设置项（标签用翻译键，命中时再按当前语言转成文本）。
	 * 集中放在这里，改设置项时只需同步一行。
	 */
	private static final List<SearchEntry> SEARCH_INDEX = List.of(
			new SearchEntry(PAGE_MINIMAP, "screen.sinanmap.minimap_settings.enabled"),
			new SearchEntry(PAGE_MINIMAP, "screen.sinanmap.minimap_settings.zoom"),
			new SearchEntry(PAGE_MINIMAP, "screen.sinanmap.minimap_settings.lock_north"),
			new SearchEntry(PAGE_MINIMAP, "screen.sinanmap.minimap_settings.layer"),
			new SearchEntry(PAGE_MINIMAP, "screen.sinanmap.map_settings.edit_hud"),
			new SearchEntry(PAGE_INFO, "screen.sinanmap.map_settings.info.align"),
			new SearchEntry(PAGE_INFO, "screen.sinanmap.map_settings.info.clock24"),
			new SearchEntry(PAGE_INFO, "screen.sinanmap.map_settings.info.order"),
			new SearchEntry(PAGE_INFO_ORDER, "screen.sinanmap.map_settings.info.coords"),
			new SearchEntry(PAGE_INFO_ORDER, "screen.sinanmap.map_settings.info.time"),
			new SearchEntry(PAGE_INFO_ORDER, "screen.sinanmap.map_settings.info.biome"),
			new SearchEntry(PAGE_INFO_ORDER, "screen.sinanmap.map_settings.info.dimension"),
			new SearchEntry(PAGE_RADAR, "screen.sinanmap.minimap_settings.entity_radar"),
			new SearchEntry(PAGE_RADAR, "screen.sinanmap.minimap_settings.radar_radius"),
			new SearchEntry(PAGE_RADAR, "screen.sinanmap.minimap_settings.radar_hostile_only"),
			new SearchEntry(PAGE_RADAR, "screen.sinanmap.minimap_settings.radar_height_indicator"),
			new SearchEntry(PAGE_RADAR, "screen.sinanmap.minimap_settings.share_position"),
			new SearchEntry(PAGE_WAYPOINT, "screen.sinanmap.map_settings.waypoint.show_map"),
			new SearchEntry(PAGE_WAYPOINT, "screen.sinanmap.map_settings.waypoint.show_hud"),
			new SearchEntry(PAGE_WAYPOINT, "screen.sinanmap.map_settings.waypoint.show_death"),
			new SearchEntry(PAGE_WAYPOINT, "screen.sinanmap.map_settings.waypoint.keep_old_death"),
			new SearchEntry(PAGE_WAYPOINT, "screen.sinanmap.map_settings.waypoint.waystone"),
			new SearchEntry(PAGE_WAYPOINT, "screen.sinanmap.map_settings.waypoint.scale"),
			new SearchEntry(PAGE_WAYPOINT, "screen.sinanmap.map_settings.waypoint.distance"),
			new SearchEntry(PAGE_WAYPOINT, "screen.sinanmap.map_settings.waypoint.km_threshold"),
			new SearchEntry(PAGE_WAYPOINT, "screen.sinanmap.map_settings.waypoint.near_hide_distance"),
			new SearchEntry(PAGE_WAYPOINT, "screen.sinanmap.map_settings.waypoint.near_show_name"),
			new SearchEntry(PAGE_WAYPOINT, "screen.sinanmap.map_settings.waypoint.hud_opacity"),
			new SearchEntry(PAGE_WAYPOINT, "screen.sinanmap.map_settings.waypoint.map_opacity"),
			new SearchEntry(PAGE_WORLD, "screen.sinanmap.map_settings.detail_tier"),
			new SearchEntry(PAGE_OVERLAY, "screen.sinanmap.map_settings.overlay.chunk_grid"),
			new SearchEntry(PAGE_OVERLAY, "screen.sinanmap.map_settings.overlay.grid_color"),
			new SearchEntry(PAGE_OVERLAY, "screen.sinanmap.map_settings.overlay.slime_chunks"),
			new SearchEntry(PAGE_PERFORMANCE, "screen.sinanmap.map_settings.perf.prefetch_threads"),
			new SearchEntry(PAGE_PERFORMANCE, "screen.sinanmap.map_settings.perf.cave_threads"),
			new SearchEntry(PAGE_PERFORMANCE, "screen.sinanmap.map_settings.perf.applied_per_frame"),
			new SearchEntry(PAGE_PERFORMANCE, "screen.sinanmap.map_settings.perf.apply_budget"),
			new SearchEntry(PAGE_PERFORMANCE, "screen.sinanmap.map_settings.perf.chunks_per_visit"),
			new SearchEntry(PAGE_PERFORMANCE, "screen.sinanmap.map_settings.perf.filling_pages"),
			new SearchEntry(PAGE_PERFORMANCE, "screen.sinanmap.map_settings.perf.pages_per_frame"));

	public MapSettingsScreen(@Nullable Screen parent, MinimapHud minimap, MinimapSettings settings) {
		super(Component.translatable("screen.sinanmap.map_settings"));
		this.parent = parent;
		this.minimap = minimap;
		this.settings = settings;
	}

	@Override
	protected void init() {
		// 排版必须先算：控件位置全依赖它，不先算会全堆在左上角
		layout();
		ensureSearchBox();
		rebuild();
		// 背后的世界地图不是"当前界面"，窗口尺寸变化时收不到事件，但它要被当背景画、按钮位置也按
		// width/height 算——这里替它同步一次。
		// init 在窗口尺寸变化时会被重新调用，所以这一句就够，不用另外盖 resize。
		if (parent instanceof WorldMapScreen map) {
			map.updateSize(width, height);
		}
	}

	/** 建（或重新摆放）标题最右侧的全局搜索框。 */
	private void ensureSearchBox() {
		if (searchBox == null) {
			searchBox = new SinanTextField(font);
			searchBox.setMaxLength(32);
			// 嵌在标题条里：关掉常态边框，只靠底色与条融合（聚焦时才套亮边）
			searchBox.setFrame(false);
			searchBox.setHint(Component.translatable("screen.sinanmap.map_settings.search"));
			searchBox.setResponder(text -> {
				searchQuery = text;
				rebuild();
			});
		}
		// 右边界与标题条对齐、上下同高，整块看上去像标题条的一部分
		int sx = panelX + panelWidth - SEARCH_WIDTH;
		int sy = TITLE_Y;
		searchBox.setBounds(sx, sy, SEARCH_WIDTH, SEARCH_HEIGHT);
	}

	/** 按窗口算一遍排版：两列宽度、整块位置全从这里出。 */
	private void layout() {
		columnWidth = (Math.max(160, Math.min(PANEL_MAX, width - 40)) - COLUMN_GAP) / 2;
		panelWidth = columnWidth * 2 + COLUMN_GAP;
		panelX = (width - panelWidth) / 2;
		leftX = panelX;
		rightX = panelX + columnWidth + COLUMN_GAP;
		// 标题条横跨两列，两列内容都从它下面开始
		listTop = TITLE_Y + HEADER_HEIGHT + HEADER_GAP;
		// 一列只看得到 VISIBLE_ROWS 行，超出靠滚动
		listBottom = listTop + VISIBLE_ROWS * ROW_PITCH;
	}

	/**
	 * 底部那条固定按钮的 y：摆在列表最后一行的<b>下一行</b>（第 {@value #VISIBLE_ROWS}+1 行），
	 * 既不压住内容，位置也不随内容多少上下跳。
	 */
	private int bottomBarY() {
		return listTop + VISIBLE_ROWS * ROW_PITCH;
	}

	/** 换页：清掉上一页的控件重新搭。 */
	private void rebuild() {
		clearWidgets();
		entries.clear();
		pending.clear();
		fixedWidgets.clear();
		confirmButtons.clear();
		scroll = 0;
		scrollTarget = 0;
		lastScrollNanos = 0L;
		radarRadiusSlider = null;
		radarHostileToggle = null;
		gridColorButton = null;

		// 确认态：底下什么都不搭，只搭确认框那两颗按钮
		if (confirmingReset) {
			buildResetConfirm();
			contentHeight = listTop;
			return;
		}

		// 搜索态：整页换成全局搜索结果
		if (!searchQuery.isBlank()) {
			buildSearchResults();
			placeRows();
			applyTransforms();
			addBottomBar();
			contentHeight = Math.max(cursor[0], cursor[1]);
			return;
		}

		switch (page) {
			case PAGE_HOME -> buildHome();
			case PAGE_MINIMAP -> buildMinimapPage();
			case PAGE_INFO -> buildInfoPage();
			case PAGE_INFO_ORDER -> buildInfoOrderPage();
			case PAGE_RADAR -> buildRadarPage();
			case PAGE_WAYPOINT -> buildWaypointPage();
			case PAGE_PERFORMANCE -> buildPerformancePage();
			case PAGE_OVERLAY -> buildOverlayPage();
			default -> buildWorldPage();
		}
		placeRows();
		applyTransforms();
		refreshControlStates();
		addBottomBar();
		contentHeight = Math.max(cursor[0], cursor[1]);
	}

	/**
	 * 全局搜索结果：把所有标签命中关键字的设置，按"页面名 · 设置名"列成一张居中列表，
	 * 点哪条就跳到那一页。
	 */
	private void buildSearchResults() {
		String query = searchQuery.strip().toLowerCase(Locale.ROOT);
		for (SearchEntry entry : SEARCH_INDEX) {
			String label = Component.translatable(entry.labelKey()).getString();
			if (!label.toLowerCase(Locale.ROOT).contains(query)) {
				continue;
			}
			Component pageName = pageName(entry.page());
			Component text = Component.translatable("screen.sinanmap.map_settings.search.result",
					pageName, Component.translatable(entry.labelKey()));
			add(new SinanButton(0, 0, columnWidth, ROW_HEIGHT, text, () -> {
						searchQuery = "";
						if (searchBox != null) {
							searchBox.setValue("");
						}
						showPage(entry.page());
					}), null);
		}
	}

	/**
	 * 底部那条固定按钮：分项页是"返回 + 完成"（返回在完成左边），首页只有"完成"。
	 *
	 * <p><b>宽度与一列对齐</b>：两颗并排占满两列（取 {@link #leftX} / {@link #rightX}），
	 * 单颗时居中且宽度取一列——与上面的列表同一套竖线。着色：绿色 = 完成，红色 = 返回/重置。
	 */
	private void addBottomBar() {
		int y = bottomBarY();
		// 首页或搜索态只有一颗"完成"（搜索态里"返回"没有明确去处）
		if (page == PAGE_HOME || !searchQuery.isBlank()) {
			fixedWidgets.add(addRenderableWidget(new SinanButton((width - columnWidth) / 2, y,
					columnWidth, BOTTOM_BUTTON_HEIGHT,
					Component.translatable("screen.sinanmap.minimap_settings.done").withColor(COLOR_CONFIRM),
					this::onClose)));
			return;
		}
		fixedWidgets.add(addRenderableWidget(new SinanButton(leftX, y,
				columnWidth, BOTTOM_BUTTON_HEIGHT,
				Component.translatable("screen.sinanmap.map_settings.back").withColor(COLOR_CANCEL),
				() -> showPage(parentPage(page)))));
		fixedWidgets.add(addRenderableWidget(new SinanButton(rightX, y,
				columnWidth, BOTTOM_BUTTON_HEIGHT,
				Component.translatable("screen.sinanmap.minimap_settings.done").withColor(COLOR_CONFIRM),
				this::onClose)));
	}

	/** "重置设置"的确认框：返回（红）/ 重置（红，破坏性）两颗并排按钮，宽度同样与一列对齐。 */
	private void buildResetConfirm() {
		int y = height / 2 + 20;
		confirmButtons.add(addConfirmButton(new SinanButton(leftX, y,
				columnWidth, BOTTOM_BUTTON_HEIGHT,
				Component.translatable("screen.sinanmap.map_settings.back").withColor(COLOR_CANCEL),
				() -> {
					confirmingReset = false;
					rebuild();
				})));
		confirmButtons.add(addConfirmButton(new SinanButton(rightX, y,
				columnWidth, BOTTOM_BUTTON_HEIGHT,
				Component.translatable("screen.sinanmap.map_settings.reset.confirm").withColor(COLOR_CANCEL),
				() -> {
					settings.resetToDefaults();
					confirmingReset = false;
					page = PAGE_HOME;
					rebuild();
				})));
	}

	/** 登记一颗确认框按钮：进控件树，同时记进 {@link #confirmButtons}（每帧要一起设淡入进度）。 */
	private SinanButton addConfirmButton(SinanButton button) {
		fixedWidgets.add(addRenderableWidget(button));
		return button;
	}

	/** 确认框的弹出时长（与切页同一量级：再短就像"跳出来"了）。 */
	private static final long RESET_CONFIRM_NANOS = 160_000_000L;

	/**
	 * "重置设置"确认框的淡入进度（0~1）：<b>底框、文字、两颗按钮用同一个进度</b>，整块一起显出来。
	 *
	 * <p>之前只有底框与文字是当场画的，按钮是控件、不受任何进度影响，于是"按钮先跳出来、底框后到"。
	 */
	private float resetConfirmFade() {
		if (resetConfirmNanos == 0L) {
			return 1.0F;
		}
		long elapsed = System.nanoTime() - resetConfirmNanos;
		if (elapsed >= RESET_CONFIRM_NANOS) {
			return 1.0F;
		}
		float t = elapsed / (float) RESET_CONFIRM_NANOS;
		// smoothstep：两头慢中间快，比线性更像"弹出来"
		return t * t * (3.0F - 2.0F * t);
	}

	/** 换页：重搭上一页控件，并让新内容从右侧滑进来。 */
	private void showPage(int target) {
		pageSlideStartNanos = target == page ? 0L : System.nanoTime();
		page = target;
		rebuild();
	}

	/**
	 * 切页动画的横向偏移（像素）：三次缓出（起步快、收尾慢）。动画结束把起始时刻清 0，之后每帧返回 0。
	 */
	private int pageSlideOffset() {
		if (pageSlideStartNanos == 0L) {
			return 0;
		}
		long elapsed = System.nanoTime() - pageSlideStartNanos;
		if (elapsed >= PAGE_SLIDE_NANOS) {
			pageSlideStartNanos = 0L;
			return 0;
		}
		double t = elapsed / (double) PAGE_SLIDE_NANOS;
		double eased = 1.0 - Math.pow(1.0 - t, 3.0);
		return (int) Math.round(PAGE_SLIDE_DISTANCE * (1.0 - eased));
	}

	/** 首页：大项入口。与分项页<b>同一套排版</b>（同样两列、从中间断开），每行一颗整列宽按钮，最后一行是"重置设置"。 */
	private void buildHome() {
		for (int index = 1; index <= PAGE_COUNT; index++) {
			int target = index;
			add(new SinanButton(0, 0, columnWidth, ROW_HEIGHT, pageName(index), () -> showPage(target)), null);
		}
		// 最后一项：重置设置（带确认）
		add(new SinanButton(0, 0, columnWidth, ROW_HEIGHT,
						Component.translatable("screen.sinanmap.map_settings.reset"),
						() -> {
							confirmingReset = true;
							// 确认框整块淡入（与切页、浮层同一套观感），见 resetConfirmFade
							resetConfirmNanos = System.nanoTime();
							rebuild();
						}),
				null);
	}

	private static Component pageName(int page) {
		return Component.translatable("screen.sinanmap.map_settings.page." + switch (page) {
			case PAGE_MINIMAP -> "minimap";
			case PAGE_INFO -> "info";
			case PAGE_INFO_ORDER -> "info_order";
			case PAGE_RADAR -> "radar";
			case PAGE_WAYPOINT -> "waypoint";
			case PAGE_PERFORMANCE -> "performance";
			case PAGE_OVERLAY -> "overlay";
			default -> "world_map";
		});
	}

	/** "返回"回到哪一页：二级页回它自己的上级，一级页回首页。 */
	private static int parentPage(int page) {
		return page == PAGE_INFO_ORDER ? PAGE_INFO : PAGE_HOME;
	}

	/** 小地图页：位置/大小/形状都在"编辑 HUD"里拖，这里只放开关与倍率。 */
	private void buildMinimapPage() {
		add(toggle("screen.sinanmap.minimap_settings.enabled",
						settings::enabled, settings::setEnabled), null);
		// 自动缩放与手动缩放合并成一条拖动框，最左是自动
		add(stepSlider(ZOOM_STEPS, zoomValue(),
						Component.translatable("screen.sinanmap.minimap_settings.zoom"),
						MapSettingsScreen::zoomName, this::applyZoom),
				"screen.sinanmap.map_settings.tip.auto_zoom");
		add(toggle("screen.sinanmap.minimap_settings.lock_north",
						() -> !settings.rotateWithPlayer(),
						locked -> settings.setRotateWithPlayer(!locked)),
				"screen.sinanmap.map_settings.tip.lock_north");
		add(new SinanCycleButton<>(0, 0, columnWidth, ROW_HEIGHT,
						Component.translatable("screen.sinanmap.minimap_settings.layer"),
						List.of(LayerMode.values()), settings.layerMode(),
						MapSettingsScreen::layerName, settings::setLayerMode),
				"screen.sinanmap.map_settings.tip.layer");
		add(new SinanButton(0, 0, columnWidth, ROW_HEIGHT,
						Component.translatable("screen.sinanmap.map_settings.edit_hud"),
						() -> Minecraft.getInstance().setScreen(new HudEditScreen(this, minimap, settings))),
				null);
	}

	/** 缩放当前该显示哪一挡（自动返回 -1）。 */
	private double zoomValue() {
		return settings.autoZoom() ? -1.0 : settings.blocksPerPixel();
	}

	/** 选到最左的"自动"就交给自动缩放，否则关掉自动、用选定的倍率。 */
	private void applyZoom(double value) {
		if (value < 0) {
			settings.setAutoZoom(true);
			return;
		}
		settings.setAutoZoom(false);
		settings.setBlocksPerPixel(value);
	}

	/** 缩放挡位名：自动档写"自动"，其余写两位小数。 */
	private static String zoomName(double value) {
		return value < 0
				? Component.translatable("screen.sinanmap.world_map.layer_auto").getString()
				: twoDecimals(value);
	}

	/**
	 * 信息显示页：小地图下面那几行小字的"总开关"——只留对齐、时间制式，加一颗进入"信息排序"的按钮。
	 */
	private void buildInfoPage() {
		add(new SinanCycleButton<>(0, 0, columnWidth, ROW_HEIGHT,
						Component.translatable("screen.sinanmap.map_settings.info.align"),
						List.of(MinimapSettings.TextAlign.values()), settings.infoAlign(),
						MapSettingsScreen::alignName, settings::setInfoAlign),
				"screen.sinanmap.map_settings.tip.info_align");
		add(toggle("screen.sinanmap.map_settings.info.clock24",
				settings::clock24, settings::setClock24), "screen.sinanmap.map_settings.tip.clock");
		add(new SinanButton(0, 0, columnWidth, ROW_HEIGHT,
					Component.translatable("screen.sinanmap.map_settings.info.order"),
					() -> showPage(PAGE_INFO_ORDER)),
			"screen.sinanmap.map_settings.tip.info_order");
}

	/**
	 * 信息排序页：一行一种信息，放"开 / 关 + 上移 + 下移"，整条是<b>居中列表</b>（见 {@link #placeRows}）。
	 *
	 * <p>行的顺序必须按设置里存的那份来（先开着的、再关着的），否则上移 / 下移改了顺序界面却仍按枚举顺序画。
	 */
	private void buildInfoOrderPage() {
		List<MinimapSettings.InfoLine> order = new ArrayList<>(settings.infoLines());
		for (MinimapSettings.InfoLine line : MinimapSettings.InfoLine.values()) {
			if (!order.contains(line)) {
				order.add(line);
			}
		}
		int enabledCount = settings.infoLines().size();
		for (MinimapSettings.InfoLine line : order) {
			int position = settings.infoLines().indexOf(line);
			SinanButton up = new SinanButton(0, 0, 18, ROW_HEIGHT, Component.literal("↑"), () -> {
						settings.moveInfoLine(line, -1);
						rebuild();
					});
			SinanButton down = new SinanButton(0, 0, 18, ROW_HEIGHT, Component.literal("↓"), () -> {
						settings.moveInfoLine(line, 1);
						rebuild();
					});
			// 到头了就置灰，免得点了没反应、还以为是坏了
			up.active = position > 0;
			down.active = position >= 0 && position < enabledCount - 1;
			addRow(new int[] {0, 18, 18}, null,
					new SinanCycleButton<>(0, 0, 0, ROW_HEIGHT, Component.translatable(infoKey(line)),
							List.of(Boolean.TRUE, Boolean.FALSE), settings.hasInfoLine(line),
							MapSettingsScreen::onOffName,
							value -> {
								settings.setInfoLineEnabled(line, value);
								rebuild();
							}),
					up, down);
		}
	}

	private static String infoKey(MinimapSettings.InfoLine line) {
		return "screen.sinanmap.map_settings.info." + line.name().toLowerCase(Locale.ROOT);
	}

	/** 对齐那一挡显示成"左 / 中 / 右"。 */
	private static String alignName(MinimapSettings.TextAlign align) {
		return Component.translatable("screen.sinanmap.map_settings.info.align."
				+ align.name().toLowerCase(Locale.ROOT)).getString();
	}

	/** 雷达页。 */
	private void buildRadarPage() {
		add(new SinanCycleButton<>(0, 0, columnWidth, ROW_HEIGHT,
						Component.translatable("screen.sinanmap.minimap_settings.entity_radar"),
						List.of(Boolean.TRUE, Boolean.FALSE), settings.entityRadarEnabled(),
						MapSettingsScreen::onOffName,
						value -> {
							settings.setEntityRadarEnabled(value);
							refreshControlStates();
						}),
				"screen.sinanmap.map_settings.tip.radar");
		radarRadiusSlider = slider(MinimapSettings.MIN_RADAR_RADIUS, MinimapSettings.MAX_RADAR_RADIUS,
				settings.entityRadarRadius(), MapSettingsScreen::plainInt,
				value -> settings.setEntityRadarRadius(value),
				"screen.sinanmap.minimap_settings.radar_radius");
		add(radarRadiusSlider, null);
		radarHostileToggle = new SinanCycleButton<>(0, 0, columnWidth, ROW_HEIGHT,
				Component.translatable("screen.sinanmap.minimap_settings.radar_hostile_only"),
				List.of(Boolean.TRUE, Boolean.FALSE), settings.entityRadarHostileOnly(),
				MapSettingsScreen::onOffName, settings::setEntityRadarHostileOnly);
		add(radarHostileToggle, "screen.sinanmap.map_settings.tip.hostile_only");
		add(toggle("screen.sinanmap.minimap_settings.radar_height_indicator",
				settings::entityRadarHeightIndicator, settings::setEntityRadarHeightIndicator),
				"screen.sinanmap.map_settings.tip.height_indicator");
		// 多人相关：装了司南的服务器上，愿不愿意被别的玩家看到
		add(toggle("screen.sinanmap.minimap_settings.share_position",
				settings::sharePlayerPosition, settings::setSharePlayerPosition),
				"screen.sinanmap.map_settings.tip.share_position");
	}

	/** 路径点页。 */
	private void buildWaypointPage() {
		add(toggle("screen.sinanmap.map_settings.waypoint.show_map",
				settings::showMapWaypoints, settings::setShowMapWaypoints),
				"screen.sinanmap.map_settings.tip.waypoint_map");
		add(toggle("screen.sinanmap.map_settings.waypoint.show_hud",
				settings::showHudWaypoints, settings::setShowHudWaypoints), null);
		add(toggle("screen.sinanmap.map_settings.waypoint.show_death",
				settings::showDeathPoints, settings::setShowDeathPoints), null);
		add(toggle("screen.sinanmap.map_settings.waypoint.keep_old_death",
				settings::keepOldDeathPoints, settings::setKeepOldDeathPoints),
				"screen.sinanmap.map_settings.tip.keep_death");
		// 可选兼容：装了「传送石碑」时把石碑同步成路径点
		add(toggle("screen.sinanmap.map_settings.waypoint.waystone",
				settings::waystoneIntegration, settings::setWaystoneIntegration),
				"screen.sinanmap.map_settings.tip.waystone");

		// 图标缩放：最左为自动，自动档显示值就是默认缩放，格式"数值（自动）"。
		add(new SinanCycleButton<>(0, 0, columnWidth, ROW_HEIGHT,
						Component.translatable("screen.sinanmap.map_settings.waypoint.scale"),
						HUD_SCALE_STEPS, hudScaleValue(),
						MapSettingsScreen::hudScaleName, this::applyHudScale),
				"screen.sinanmap.map_settings.tip.hud_scale");
		add(toggle("screen.sinanmap.map_settings.waypoint.distance",
				settings::waypointHudDistance, settings::setWaypointHudDistance),
				"screen.sinanmap.map_settings.tip.hud_distance");
		// 换成公里的距离：按挡位拖的拖动框
		add(stepSlider(KM_STEPS, settings.kilometerThreshold(),
						Component.translatable("screen.sinanmap.map_settings.waypoint.km_threshold"),
						meters -> meters + " m", settings::setKilometerThreshold),
				"screen.sinanmap.map_settings.tip.km");
		// 走近了就简化标记：这两项各自独立，都是"距离小于多少米时就怎样"
		add(stepSlider(MinimapSettings.NEAR_DISTANCE_STEPS, settings.nearHideDistance(),
						Component.translatable("screen.sinanmap.map_settings.waypoint.near_hide_distance"),
						MapSettingsScreen::metersOrOff, settings::setNearHideDistance),
				"screen.sinanmap.map_settings.tip.near_hide_distance");
		add(stepSlider(MinimapSettings.NEAR_DISTANCE_STEPS, settings.nearShowFullName(),
						Component.translatable("screen.sinanmap.map_settings.waypoint.near_show_name"),
						MapSettingsScreen::metersOrOff, settings::setNearShowFullName),
				"screen.sinanmap.map_settings.tip.near_show_name");
		add(slider(MinimapSettings.MIN_OPACITY, MinimapSettings.MAX_OPACITY,
				settings.hudWaypointOpacity(), MapSettingsScreen::twoDecimals,
				settings::setHudWaypointOpacity,
				"screen.sinanmap.map_settings.waypoint.hud_opacity"), null);
		add(slider(MinimapSettings.MIN_OPACITY, MinimapSettings.MAX_OPACITY,
				settings.mapWaypointOpacity(), MapSettingsScreen::twoDecimals,
				settings::setMapWaypointOpacity,
				"screen.sinanmap.map_settings.waypoint.map_opacity"), null);
	}

	/** "近处简化"两个滑条的数值文本：0 挡写"关"，其余按米。 */
	private static String metersOrOff(int meters) {
		return meters <= 0
				? Component.translatable("options.off").getString()
				: meters + " m";
	}

	/**
	 * 叠加层页：压在地形上的几层参考信息（区块网格、史莱姆区块）。只影响"看得见什么"、不改地形数据，
	 * 所以都做成开关；网格颜色从一组预设里选，不引调色板。
	 */
	private void buildOverlayPage() {
		add(toggle("screen.sinanmap.map_settings.overlay.chunk_grid",
				settings::chunkGrid, settings::setChunkGrid),
				"screen.sinanmap.map_settings.tip.chunk_grid");
		gridColorButton = new SinanCycleButton<>(0, 0, columnWidth, ROW_HEIGHT,
				Component.translatable("screen.sinanmap.map_settings.overlay.grid_color"),
				MinimapSettings.GRID_COLOR_STEPS, settings.chunkGridColor(),
				MapSettingsScreen::gridColorName, settings::setChunkGridColor);
		add(gridColorButton, "screen.sinanmap.map_settings.tip.grid_color");
		add(toggle("screen.sinanmap.map_settings.overlay.slime_chunks",
				settings::slimeChunks, settings::setSlimeChunks),
				"screen.sinanmap.map_settings.tip.slime_chunks");
	}

	/** 网格颜色名：与 {@link MinimapSettings#GRID_COLOR_STEPS} 一一对应。 */
	private static final List<String> GRID_COLOR_NAMES = List.of(
			"white", "black", "red", "orange", "yellow", "green", "cyan", "purple");

	private static String gridColorName(int color) {
		int index = Math.max(0, MinimapSettings.GRID_COLOR_STEPS.indexOf(color));
		String name = index < GRID_COLOR_NAMES.size() ? GRID_COLOR_NAMES.get(index) : "white";
		return Component.translatable("screen.sinanmap.map_settings.color." + name).getString();
	}

	/** 世界地图页。 */
	private void buildWorldPage() {
		// "自动"（= 不限制，按缩放自动选档）放在最左，其后才是固定档
		add(new SinanCycleButton<>(0, 0, columnWidth, ROW_HEIGHT,
						Component.translatable("screen.sinanmap.map_settings.detail_tier"),
						List.of(MinimapSettings.MAX_DETAIL_TIER, 1, 4), settings.mapDetailTier(),
						MapSettingsScreen::detailName, settings::setMapDetailTier),
				"screen.sinanmap.map_settings.tip.detail_tier");
	}

	/**
	 * 性能页：线程数、每帧搬运量、填页节流等。
	 *
	 * <p>每项都是一条<b>拖动框</b>，<b>最左一挡是"自动"</b>、往右依次变大，自动档显示当前默认值。
	 * 范围取各常量已有的安全区间（见 {@link MinimapSettings}）：调大更吃 CPU/内存，调小更省。
	 */
	private void buildPerformancePage() {
		add(stepSlider(PREFETCH_THREAD_STEPS, settings.prefetchThreads(),
						Component.translatable("screen.sinanmap.map_settings.perf.prefetch_threads"),
						// "自动"那一档显示的就是实际会用的条数（跟着核数算，见 MinimapSettings#autoPrefetchThreads）
						value -> numberOrAuto(value, MinimapSettings.autoPrefetchThreads()).getString(),
						settings::setPrefetchThreads),
				"screen.sinanmap.map_settings.tip.prefetch_threads");
		add(stepSlider(CAVE_THREAD_STEPS, settings.caveScanThreads(),
						Component.translatable("screen.sinanmap.map_settings.perf.cave_threads"),
						value -> numberOrAuto(value, MinimapSettings.DEFAULT_CAVE_SCAN_THREADS).getString(),
						settings::setCaveScanThreads),
				"screen.sinanmap.map_settings.tip.cave_threads");
		add(stepSlider(APPLIED_STEPS, settings.appliedPerFrame(),
						Component.translatable("screen.sinanmap.map_settings.perf.applied_per_frame"),
						value -> numberOrAuto(value, MinimapSettings.DEFAULT_APPLIED_PER_FRAME).getString(),
						settings::setAppliedPerFrame),
				"screen.sinanmap.map_settings.tip.applied_per_frame");
		add(stepSlider(BUDGET_STEPS, settings.applyBudgetMillis(),
						Component.translatable("screen.sinanmap.map_settings.perf.apply_budget"),
						value -> unitOrAuto(value, MinimapSettings.DEFAULT_APPLY_BUDGET_MILLIS, " ms").getString(),
						settings::setApplyBudgetMillis),
				"screen.sinanmap.map_settings.tip.apply_budget");
		add(stepSlider(CHUNKS_STEPS, settings.chunksPerVisit(),
						Component.translatable("screen.sinanmap.map_settings.perf.chunks_per_visit"),
						value -> numberOrAuto(value, MinimapSettings.DEFAULT_CHUNKS_PER_VISIT).getString(),
						settings::setChunksPerVisit),
				"screen.sinanmap.map_settings.tip.chunks_per_visit");
		add(stepSlider(FILLING_PAGES_STEPS, settings.fillingPages(),
						Component.translatable("screen.sinanmap.map_settings.perf.filling_pages"),
						value -> numberOrAuto(value, MinimapSettings.DEFAULT_FILLING_PAGES).getString(),
						settings::setFillingPages),
				"screen.sinanmap.map_settings.tip.filling_pages");
		add(stepSlider(PAGES_PER_FRAME_STEPS, settings.fillingPagesPerFrame(),
						Component.translatable("screen.sinanmap.map_settings.perf.pages_per_frame"),
						value -> numberOrAuto(value, MinimapSettings.DEFAULT_FILLING_PAGES_PER_FRAME).getString(),
						settings::setFillingPagesPerFrame),
				"screen.sinanmap.map_settings.tip.pages_per_frame");
	}

	/** 一条"挡位拖动框"（自绘滑条样式），占满整列宽。 */
	private <T> SinanSlider stepSlider(List<T> steps, T value, Component label,
			Function<T, String> valueText, Consumer<T> onChange) {
		return new StepSlider<>(0, 0, columnWidth, ROW_HEIGHT, label, steps, value, valueText, onChange);
	}

	/** 一个"开 / 关"开关行（自绘的循环切换按钮）。 */
	private AbstractWidget toggle(String labelKey,
			java.util.function.BooleanSupplier get, java.util.function.Consumer<Boolean> set) {
		return new SinanCycleButton<>(0, 0, columnWidth, ROW_HEIGHT, Component.translatable(labelKey),
				List.of(Boolean.TRUE, Boolean.FALSE), get.getAsBoolean(),
				MapSettingsScreen::onOffName, set::accept);
	}

	/** 开关值的显示名（用原版的"开 / 关"翻译键）。 */
	private static String onOffName(boolean value) {
		return Component.translatable(value ? "options.on" : "options.off").getString();
	}

	/** 加一个控件（占满整列宽）。 */
	private void add(AbstractWidget widget, @Nullable String tipKey) {
		addRow(new int[] {0}, tipKey, widget);
	}

	/**
	 * 加一行"并排的控件"：宽度数组与控件一一对应，{@code 0} = 占满排完其它控件后剩下的宽度
	 * （如 {@code {0, 18, 18}} = 左边一个大控件 + 右边两个 18 宽小按钮）。
	 */
	private void addRow(int[] widths, @Nullable String tipKey, AbstractWidget... widgets) {
		Row row = new Row(ROW_PITCH);
		row.tipKey = tipKey;
		for (int index = 0; index < widgets.length; index++) {
			row.widgets.add(widgets[index]);
			row.widths.add(index < widths.length ? widths[index] : 0);
		}
		pending.add(row);
	}

	/**
	 * 把待排行分到两列并摆好。
	 *
	 * <p><b>从中间断开</b>（按累计高度找正中那条缝），而不是"左边排满再排右边"——后者在两列内容不等时左列会空一大截。
	 *
	 * <p>信息排序页与全局搜索结果例外：要的是<b>一条居中的列表</b>，整页只用一列、居中摆放。
	 */
	private void placeRows() {
		boolean centeredList = page == PAGE_INFO_ORDER || !searchQuery.isBlank();
		int split = pending.size();
		if (!centeredList) {
			int total = 0;
			for (Row row : pending) {
				total += row.height;
			}
			int half = total / 2;
			int accumulated = 0;
			for (int index = 0; index < pending.size(); index++) {
				accumulated += pending.get(index).height;
				if (accumulated >= half) {
					split = index + 1;
					break;
				}
			}
			if (pending.size() > 1 && split >= pending.size()) {
				split = pending.size() - 1;
			}
		}

		cursor[0] = listTop;
		cursor[1] = listTop;
		for (int index = 0; index < pending.size(); index++) {
			Row row = pending.get(index);
			int column = index < split ? 0 : 1;
			int x = column == 0 ? leftX : rightX;
			int available = columnWidth;
			if (centeredList) {
				column = 0;
				available = Math.min(ORDER_LIST_WIDTH, panelWidth);
				x = (width - available) / 2;
			}
			List<Integer> baseXs = new ArrayList<>();
			if (!row.widgets.isEmpty()) {
				int fixed = 0;
				for (int width : row.widths) {
					if (width > 0) {
						fixed += width;
					}
				}
				int gaps = WIDGET_GAP * (row.widgets.size() - 1);
				int fill = Math.max(16, available - fixed - gaps);
				for (int slot = 0; slot < row.widgets.size(); slot++) {
					int width = row.widths.get(slot) > 0 ? row.widths.get(slot) : fill;
					AbstractWidget widget = row.widgets.get(slot);
					widget.setX(x);
					widget.setWidth(width);
					widget.setY(cursor[column]);
					addRenderableWidget(widget);
					baseXs.add(x);
					x += width + WIDGET_GAP;
				}
			}
			entries.add(new Entry(List.copyOf(row.widgets), List.copyOf(baseXs), row.tipKey, cursor[column]));
			cursor[column] += row.height;
		}
	}

	/**
	 * 把每个控件摆到"基准位置 + 滚动偏移 + 切页动画偏移"，每帧从基准值重算（累加会随帧率飘）。
	 *
	 * <p>摆的同时定"这一行露不露"：只有<b>整行都在列表范围内</b>的才画，否则滚出去的行会盖住标题条与底部按钮。
	 */
	private void applyTransforms() {
		int offset = (int) Math.round(scroll);
		int slide = pageSlideOffset();
		for (Entry entry : entries) {
			int y = entry.baseY() - offset;
			boolean inView = y >= listTop && y + ROW_HEIGHT <= listBottom;
			for (int index = 0; index < entry.widgets().size(); index++) {
				AbstractWidget widget = entry.widgets().get(index);
				widget.setX(entry.baseXs().get(index) + slide);
				widget.setY(y);
				widget.visible = inView;
			}
		}
	}

	/**
	 * 平滑滚动：当前值按时间常数指数逼近目标值。滚轮只改 {@link #scrollTarget}，画出来的位置由这里逐帧追过去。
	 */
	private void tickScroll() {
		if (Math.abs(scrollTarget - scroll) < 0.5) {
			if (scroll != scrollTarget) {
				scroll = scrollTarget;
			}
			lastScrollNanos = 0L;
			return;
		}
		long now = System.nanoTime();
		double dt = lastScrollNanos == 0L ? 1.0 / 60.0 : (now - lastScrollNanos) / 1.0e9;
		lastScrollNanos = now;
		double factor = 1.0 - Math.exp(-Math.clamp(dt, 0.0, 0.1) / SCROLL_TIME_CONSTANT);
		scroll += (scrollTarget - scroll) * factor;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		if (parent instanceof WorldMapScreen map) {
			// 从地图进来的：把地图画一遍当背景
			map.extractRenderState(graphics, -1, -1, partialTick);
		}
		// 两种情况都<b>不压暗背景</b>：设置面板只占中间两列，压暗整屏等于把周围的地图 / 游戏画面
		// 一起糊掉了，直接叠上去反而更清楚

		drawHeader(graphics);
		scrollTarget = clampScroll(scrollTarget);
		tickScroll();
		// 每帧从基准位置重算：滚动与切页动画都是"基准 + 偏移"
		applyTransforms();
		// 确认态：先把确认框的底铺上，再让按钮画在最上面
		if (confirmingReset) {
			float fade = resetConfirmFade();
			for (SinanButton button : confirmButtons) {
				button.setFade(fade);
			}
			drawResetConfirmBox(graphics, fade);
			super.extractRenderState(graphics, mouseX, mouseY, partialTick);
			return;
		}
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		int maxScroll = Math.max(0, contentHeight - (listBottom - listTop));
		if (maxScroll > 0) {
			int trackHeight = listBottom - listTop;
			int barHeight = Math.max(14, Math.round(trackHeight * trackHeight / (float) contentHeight));
			int barY = listTop + Math.round((trackHeight - barHeight) * (float) (scroll / maxScroll));
			graphics.fill(width - 6, listTop, width - 4, listBottom, 0x20FFFFFF);
			graphics.fill(width - 6, barY, width - 4, barY + barHeight, 0x80FFFFFF);
		}
		drawTooltip(graphics, mouseX, mouseY);
		updateCursor(graphics, mouseX, mouseY);
	}

	/**
	 * 鼠标样式：一帧里<b>最后一次</b> {@code requestCursor} 才生效，所以必须放在最后（会盖掉背后世界地图开头的那次请求）。
	 *
	 * <ul>
	 *   <li>搜索框 → {@link CursorTypes#IBEAM}</li>
	 *   <li>拖动框 → {@link CursorTypes#RESIZE_EW}</li>
	 *   <li>其它能点的控件 → {@link CursorTypes#POINTING_HAND}</li>
	 *   <li>其余 → 默认箭头</li>
	 * </ul>
	 */
	private void updateCursor(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		CursorType cursor = CursorTypes.ARROW;
		if (searchBox != null && searchBox.isMouseOver(mouseX, mouseY)) {
			cursor = CursorTypes.IBEAM;
		} else {
			AbstractWidget widget = activeWidgetAt(mouseX, mouseY);
			if (widget != null) {
				cursor = widget instanceof SinanSlider ? CursorTypes.RESIZE_EW : CursorTypes.POINTING_HAND;
			}
		}
		graphics.requestCursor(cursor);
	}

	/** 光标下那个"能用"的控件（列表里的行 + 底部那排固定按钮）；没有就返回 null。 */
	@Nullable
	private AbstractWidget activeWidgetAt(int mouseX, int mouseY) {
		for (AbstractWidget widget : fixedWidgets) {
			if (widget.active && widget.isMouseOver(mouseX, mouseY)) {
				return widget;
			}
		}
		for (Entry entry : entries) {
			for (AbstractWidget widget : entry.widgets()) {
				if (widget.active && widget.isMouseOver(mouseX, mouseY)) {
					return widget;
				}
			}
		}
		return null;
	}

	/**
	 * 确认框的底与两行文字（按钮由 {@link #buildResetConfirm()} 建成真正的控件，画在它上面）。
	 *
	 * @param fade 整块淡入进度，与那两颗按钮用的是同一个值（见 {@link #resetConfirmFade}）
	 */
	private void drawResetConfirmBox(GuiGraphicsExtractor graphics, float fade) {
		int boxWidth = Math.min(260, width - 40);
		int boxHeight = font.lineHeight * 2 + 34;
		int x = (width - boxWidth) / 2;
		int y = height / 2 - boxHeight / 2 - 10;
		graphics.fill(x, y, x + boxWidth, y + boxHeight, SinanStyle.fade(0xE8101014, fade));
		graphics.fill(x, y, x + boxWidth, y + 1, SinanStyle.fade(0x60FFFFFF, fade));
		graphics.fill(x, y + boxHeight - 1, x + boxWidth, y + boxHeight, SinanStyle.fade(0x60FFFFFF, fade));
		graphics.centeredText(font, Component.translatable("screen.sinanmap.map_settings.reset.title"),
				width / 2, y + 9, SinanStyle.fade(0xFFFFFFFF, fade));
		graphics.centeredText(font, Component.translatable("screen.sinanmap.map_settings.reset.message"),
				width / 2, y + 9 + font.lineHeight + 2, SinanStyle.fade(0xFFB4B4B4, fade));
	}

	/**
	 * 顶部标题条：一条压暗的底，标题贴左、最右端是全局搜索框。
	 *
	 * <p>搜索框用<b>和标题条一样的底色</b>、刻意不描常态边框（见 {@link SinanTextField#setFrame(boolean)}），
	 * 看上去是嵌在条里的一段。
	 */
	private void drawHeader(GuiGraphicsExtractor graphics) {
		Component header = page == PAGE_HOME ? title : pageName(page);
		graphics.fill(panelX, TITLE_Y, panelX + panelWidth, TITLE_Y + HEADER_HEIGHT, SinanStyle.BACKGROUND);
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(panelX + 5, TITLE_Y + (HEADER_HEIGHT - font.lineHeight * TITLE_SCALE) / 2.0F);
		pose.scale(TITLE_SCALE, TITLE_SCALE);
		graphics.text(font, header, 0, 0, 0xFFFFFFFF, true);
		pose.popMatrix();

		// 全局搜索框画在标题条最右端
		ensureSearchBox();
		searchBox.render(graphics);
	}

	private int clampScroll(int value) {
		int max = Math.max(0, contentHeight - (listBottom - listTop));
		return (int) Math.clamp(value, 0, max);
	}

	/**
	 * 悬停某一行时在光标旁画它的说明（只有注了 tipKey 的行才有），按 {@link #TOOLTIP_MAX_WIDTH} 自动换行。
	 */
	private void drawTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		for (Entry entry : entries) {
			if (entry.tipKey() == null || entry.widgets().isEmpty()) {
				continue;
			}
			AbstractWidget first = entry.widgets().get(0);
			if (!first.isMouseOver(mouseX, mouseY)
					|| first.getY() + first.getHeight() < listTop
					|| first.getY() > listBottom) {
				continue;
			}
			List<String> lines = wrapText(font,
					Component.translatable(entry.tipKey()).getString(), TOOLTIP_MAX_WIDTH);
			int pad = 3;
			int boxWidth = 0;
			for (String line : lines) {
				boxWidth = Math.max(boxWidth, font.width(line));
			}
			boxWidth += pad * 2;
			int boxHeight = lines.size() * font.lineHeight + pad * 2;
			int x = mouseX + 10;
			int y = mouseY + 12;
			if (x + boxWidth > width) {
				x = mouseX - 10 - boxWidth;
			}
			if (y + boxHeight > height) {
				y = mouseY - 12 - boxHeight;
			}
			x = Math.max(0, Math.min(x, Math.max(0, width - boxWidth)));
			y = Math.max(0, Math.min(y, Math.max(0, height - boxHeight)));
			graphics.fill(x, y, x + boxWidth, y + boxHeight, TOOLTIP_BACKGROUND);
			for (int index = 0; index < lines.size(); index++) {
				graphics.text(font, lines.get(index), x + pad, y + pad + index * font.lineHeight,
						0xFFE6E6E6, false);
			}
			return;
		}
	}

	/**
	 * 把一段文字按像素宽度折行：折行点优先取<b>这一行里最后一个空格</b>（英文单词不被劈开），
	 * 没有空格（中文长句）时就在超出的那个字符前断开。
	 */
	private static List<String> wrapText(Font font, String text, int maxWidth) {
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		int lastSpace = -1;
		for (int index = 0; index < text.length(); index++) {
			char c = text.charAt(index);
			if (font.width(line.toString() + c) > maxWidth && line.length() > 0) {
				if (lastSpace > 0) {
					String rest = line.substring(lastSpace + 1);
					lines.add(line.substring(0, lastSpace));
					line.setLength(0);
					line.append(rest);
				} else {
					lines.add(line.toString());
					line.setLength(0);
				}
				lastSpace = -1;
			}
			if (c == ' ') {
				lastSpace = line.length();
			}
			line.append(c);
		}
		if (line.length() > 0) {
			lines.add(line.toString());
		}
		return lines.isEmpty() ? List.of("") : lines;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// 点搜索框：聚焦并把光标放到点击处；点别处先让它失焦
		if (searchBox != null && searchBox.isMouseOver(event.x(), event.y())) {
			searchBox.mouseClicked(event.x(), event.y());
			return true;
		}
		if (searchBox != null && searchBox.isFocused()) {
			searchBox.setFocused(false);
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (searchBox != null && searchBox.isFocused()) {
			searchBox.charTyped(event);
			return true;
		}
		return super.charTyped(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		// 光标在面板外：滚轮交给背后的世界地图（缩放），设置页开着时地图仍是"活的"
		if (parent instanceof WorldMapScreen map && !insidePanel(mouseX)
				&& map.mouseScrolled(mouseX, mouseY, horizontal, vertical)) {
			return true;
		}
		int maxScroll = Math.max(0, contentHeight - (listBottom - listTop));
		if (maxScroll <= 0 || vertical == 0.0) {
			return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
		}
		scrollTarget = (int) Math.clamp(scrollTarget - vertical * ROW_PITCH * 2.0, 0, maxScroll);
		return true;
	}

	/** 光标是不是落在设置面板这一竖列上（面板之外就是背后的世界地图）。 */
	private boolean insidePanel(double mouseX) {
		return mouseX >= panelX - 4 && mouseX < panelX + panelWidth + 4;
	}

	/**
	 * 设置页开着时，方向键与 ± 仍交给背后的世界地图。
	 *
	 * <p>底下世界地图仍是活动的，转过去后底图能继续平移 / 缩放。<b>只转这几颗</b>——其它键（尤其 ESC）转过去会直接把地图关掉。
	 */
	@Override
	public boolean keyPressed(KeyEvent event) {
		// 搜索框拿焦点时：按键只进搜索框（ESC 先失焦），不透传给背后的地图
		if (searchBox != null && searchBox.isFocused()) {
			if (event.isEscape()) {
				searchBox.setFocused(false);
				return true;
			}
			searchBox.keyPressed(event);
			return true;
		}
		switch (event.key()) {
			case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT,
					GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT,
					GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD -> {
				if (parent instanceof WorldMapScreen map && map.keyPressed(event)) {
					return true;
				}
			}
			default -> {
			}
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		// 只转与按下时同一批键：松开也要转过去，否则地图那边的"按住方向键"状态会一直卡住
		switch (event.key()) {
			case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT,
					GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT,
					GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD -> {
				if (parent instanceof WorldMapScreen map) {
					map.keyReleased(event);
					return true;
				}
			}
			default -> {
			}
		}
		return super.keyReleased(event);
	}

	/**
	 * 加一个数值滑条。<b>这里只建、不注册</b>，注册由 {@link #add} 统一做——两处都调 {@code addRenderableWidget}
	 * 会让控件登记两次，每帧画两遍、点一下触发两次。
	 */
	private SinanSlider slider(double min, double max, double value,
			DoubleFunction<String> formatter, DoubleConsumer onChange, String labelKey) {
		return new SettingSlider(0, 0, columnWidth, ROW_HEIGHT,
				Component.translatable(labelKey), min, max, value, formatter, onChange);
	}

	/** 让"当前用不到的控件"置灰。 */
	private void refreshControlStates() {
		if (radarRadiusSlider != null) {
			radarRadiusSlider.active = settings.entityRadarEnabled();
		}
		if (radarHostileToggle != null) {
			radarHostileToggle.active = settings.entityRadarEnabled();
		}
		if (gridColorButton != null) {
			// 网格没开时选颜色没有意义，先置灰
			gridColorButton.active = settings.chunkGrid();
		}
	}

	private static String plainInt(double value) {
		return Integer.toString((int) Math.round(value));
	}

	private static String twoDecimals(double value) {
		return "%.2f".formatted(value);
	}

	/** "自动"档统一显示成"默认值（自动）"，手动档就显示数值本身。 */
	private static Component autoLabel(String valueText) {
		return Component.translatable("screen.sinanmap.map_settings.value_auto", valueText);
	}

	private static Component numberOrAuto(int value, int autoValue) {
		return value == MinimapSettings.AUTO
				? autoLabel(Integer.toString(autoValue))
				: Component.literal(Integer.toString(value));
	}

	private static Component unitOrAuto(int value, int autoValue, String unit) {
		return value == MinimapSettings.AUTO
				? autoLabel(autoValue + unit)
				: Component.literal(value + unit);
	}

	private static String layerName(LayerMode mode) {
		return Component.translatable("screen.sinanmap.minimap_settings.layer."
				+ mode.name().toLowerCase(Locale.ROOT)).getString();
	}

	/** 细节档：最大那一档就是"不限制（按缩放自动）"，显示成"4 方块/像素（自动）"。 */
	private static String detailName(int tier) {
		String text = tier + " 方块/像素";
		return tier >= MinimapSettings.MAX_DETAIL_TIER ? autoLabel(text).getString() : text;
	}

	/** 图标缩放：最左的"自动"档显示成默认值 + （自动）。 */
	private static String hudScaleName(double value) {
		if (value < 0) {
			return autoLabel(twoDecimals(MinimapSettings.DEFAULT_HUD_SCALE)).getString();
		}
		return twoDecimals(value);
	}

	/** 图标缩放当前该显示哪一挡（自动返回 -1）。 */
	private double hudScaleValue() {
		return settings.hudScaleAuto() ? -1.0 : settings.waypointHudScale();
	}

	/** 选到最左的"自动"就交给自动，否则关掉自动用选定的手数值。 */
	private void applyHudScale(double value) {
		if (value < 0) {
			settings.setHudScaleAuto(true);
			return;
		}
		settings.setHudScaleAuto(false);
		settings.setWaypointHudScale(value);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		// 确认态里按 ESC：等于点"取消"，先退出确认态
		if (confirmingReset) {
			confirmingReset = false;
			rebuild();
			return;
		}
		// 搜索态里按 ESC：先清掉搜索、回到当前页，再按一次才退出/返回
		if (!searchQuery.isBlank()) {
			searchQuery = "";
			if (searchBox != null) {
				searchBox.setValue("");
				searchBox.setFocused(false);
			}
			rebuild();
			return;
		}
		// 分项页里按 ESC：回上一级（与点"返回"一致，二级页回它的上级），首页里再关才真的退出
		if (page != PAGE_HOME) {
			showPage(parentPage(page));
			return;
		}
		Minecraft.getInstance().setScreen(parent);
	}
}
