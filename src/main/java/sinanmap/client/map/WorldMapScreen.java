package sinanmap.client.map;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.cursor.CursorType;
import com.mojang.blaze3d.platform.cursor.CursorTypes;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sinanmap.common.SinanConstants;
import sinanmap.common.coord.BlockPoint;
import sinanmap.common.coord.Dimensions;
import sinanmap.common.coord.Heading;
import sinanmap.common.coord.WorldPos;
import sinanmap.common.hud.MinimapSettings;
import sinanmap.common.map.CenterOutSweep;
import sinanmap.common.map.LayerMode;
import sinanmap.common.map.LayerSelector;
import sinanmap.common.map.ZoomAnimator;
import sinanmap.common.net.TrackedPlayersS2C;
import sinanmap.common.waypoint.Waypoint;
import sinanmap.common.waypoint.WaypointStore;
import sinanmap.client.gui.SinanStyle;
import sinanmap.client.gui.SinanTextField;
import sinanmap.client.map.data.ClientChunkColorProvider;
import sinanmap.client.map.data.MapStorage;
import sinanmap.client.map.data.WorldSaveChunks;
import sinanmap.client.map.render.MarkerRenderer;
import sinanmap.client.map.render.TilePage;
import sinanmap.client.map.render.TileVariants;
import sinanmap.client.map.render.WorldTilePages;
import sinanmap.client.map.settings.MapSettingsScreen;
import sinanmap.client.radar.EntityRadar;
import sinanmap.client.radar.TrackedPlayers;
import sinanmap.client.waypoint.WaypointEditScreen;
import sinanmap.client.waypoint.WaypointListScreen;
import sinanmap.client.waypoint.WaypointManager;
import sinanmap.client.waypoint.WaypointRenderer;

/**
 * 全屏世界地图（默认按键 M）。
 *
 * <p>地形按<b>世界坐标对齐的瓦片页</b>绘制（见 {@link WorldTilePages}）：页的内容与缩放、平移无关，
 * 因此拖动和缩放<b>完全不重新采样地形</b>（以前每次重画整张屏幕贴图，约两百万像素、二三十毫秒，
 * 表现就是拖动顿挫）。
 *
 * <p>绘制的换算只有一个入口：一组"GUI 原点对应哪个世界坐标、每个 GUI 像素多少方块"的参数
 * （{@link #originWorldX()} 等）。缩放动画期间它指向光标下的锚点、结束后落到视图中心，
 * 因此光标按住的那个世界坐标全程不动。
 *
 * <p>缩放值 {@link ZoomAnimator} 的单位是"每个设备像素代表多少方块"。放大一侧的档位是
 * <b>"一个方块占整数个设备像素"</b>（1、2、3……32）：只有整数倍率下，一个方块才正好落在整数个
 * 设备像素上、取样点落在像素中心，画面才是锐的。档位按 {@value #ZOOM_STEP} 倍的步长走。
 */
public final class WorldMapScreen extends Screen {

	private static final Logger LOGGER = LoggerFactory.getLogger(SinanConstants.MOD_ID);

	/**
	 * 视野内最多允许多少方块（长边）。
	 *
	 * <p>按屏幕尺寸反推，限制"一屏要看多少页"。但<b>它不是"能缩多小"的真正限制</b>：页的创建
	 * 与填充被裁到最近 {@code WorldTilePages#MAX_PAGES} 页（见 {@code rebuildFillOrders}），
	 * 缩得再远只是"远处没有瓦片、露出界面底色"。想把远景也画出来就得靠 LOD 粗档贴图（见 {@link #updateTerrainTier()}）。
	 */
	private static final int MAX_VISIBLE_BLOCKS = 32768;

	/** 每个设备像素代表的方块数上限（缩到最小）。 */
	private static final double MAX_BLOCKS_PER_DEVICE_PIXEL = 16.0;

	/** 每个设备像素代表的方块数下限（放到最大，即一个方块 32 个设备像素）。 */
	private static final double MIN_ZOOM = 1.0 / 32.0;

	/**
	 * 每一格滚轮的缩放倍率；按住 ± 时也用它（两者同一速率）。
	 *
	 * <p>取 {@code 1.18}：缩到最小要跨 8 倍左右的量程，一格太小（试过 1.05）会"滚半天到不了"。
	 * 放大那一侧会吸附到"每方块整数个设备像素"的档位（见 {@link #snapPixelsPerBlock}），
	 * 实际每格 1~2 档，不会因为倍率大而跳。
	 */
	private static final double ZOOM_STEP = 1.18;

	/**
	 * <b>按住 - / + 不放</b>时每格缩放的倍率：取 {@value}（比单击/滚轮的 {@link #ZOOM_STEP} 平缓）。
	 *
	 * <p>"先慢慢、后突然变快"的根源：按住时目标按每格 {@value #ZOOM_STEP}（=1.18）指数叠加，
	 * 叠加得越久相对速度越快——前半秒还能看清、后面越来越猛。按住改用更小的每格倍率 + 固定格时
	 * （{@link #HOLD_STEP_NANOS}），单位时间的变化量被压平，观感接近匀速。
	 */
	private static final double HOLD_ZOOM_STEP = 1.08;

	/**
	 * 定位 / 路径点跳转过场的"剩余距离衰减系数"：每过 <b>1/60 秒</b>，还没走完的距离就乘上这个值。
	 *
	 * <p>起步那一下最快（第一帧约走 14%），越接近目标越慢，所以是<b>软着陆</b>而不是急停。
	 * 按"每 1/60 秒"而不是"每帧"衰减，帧率变了观感不变（见 {@link #tickCenterAnimation} 的 {@code pow} 换算）。
	 *
	 * <p>取 {@value}：约 10 帧走完 77%、30 帧走完 99%。调大更干脆、调小更绵长。
	 */
	private static final double CENTER_ANIM_DECAY = 0.86;

	/**
	 * 剩余距离小于这么多方块就直接落到目标上。
	 *
	 * <p>指数逼近永远只差一点点，没有这个阈值就永远不会结束（位置每帧都在动，"手动接管"判定会一直
	 * 以为过场还没完）。取 {@value} 方块——已远小于一个方块，看不见。
	 */
	private static final double CENTER_ANIM_SNAP_BLOCKS = 0.05;

	/** 切换层级时先拉远的倍率，让层与层之间是一次缩放过场而不是硬切。 */
	private static final double LAYER_PULSE = 1.6;

	/**
	 * 打开世界地图时的开场倍率：先按"更放大"一档出现，再让缩放动画收回到目标值。
	 *
	 * <p>于是地图是"由放大到缩小"地展开，而不是硬切一下冒出来。取 1.9 比常见做法更大一点：
	 * 开场那一下看得见"从远处推近"的过程。
	 */
	private static final double OPENING_ZOOM = 1.9;

	/** 本界面是否已经播过开场过场；每次打开（新实例）都会重新播一次。 */
	private boolean opened;

	/** 光标进到距窗口边缘这么近就把光标折返到对侧，实现"无限拖动"。 */
	private static final double EDGE_WRAP_MARGIN = 4.0;

	/** 折返之后要提防几帧"跨屏大位移"。 */
	private static final int WRAP_GUARD_FRAMES = 2;

	/** 速度采样的时间窗口：鼠标上报能密到 1 ms 一次，逐事件算速度会抖得厉害。 */
	private static final long DRAG_SAMPLE_NANOS = 40_000_000L;

	/** 惯性滑行每秒保留的速度比例（越小停得越快）。 */
	private static final double INERTIA_DECAY_PER_SECOND = 0.01;

	/**
	 * 惯性停下判据：<b>屏幕</b>上的速度低于这么多 GUI 像素每秒就停。
	 *
	 * <p>用固定的"40 方块/秒"不行：同一个数缩得近时是几十像素每秒（还在滑却"咔"地停住）、
	 * 缩得远时不到一个像素每秒（白等），轻甩松手速度本就不大，大多直接落在线下、"甩一下没有惯性"。
	 * 改成按屏幕速度判之后，远近统一成"屏幕上已经看不出在动才停"，轻甩那段滑行也留得住。
	 */
	private static final double INERTIA_MIN_PIXELS_PER_SECOND = 0.75;

	/**
	 * 一帧最多重传多少页瓦片。
	 *
	 * <p>一页是"白天 + 夜晚"两张 512×512，合起来 2 MB。收到 2 页（4 MB）：以前允许 4 页，
	 * 一帧 8 MB 的上传流量本身就能把帧时间顶起来。
	 */
	private static final int MAX_UPLOADS_PER_FRAME = 2;

	/**
	 * 一帧最多<b>新建</b>几张粗页（取用已有粗页不受此限，是免费的）。
	 *
	 * <p>粗页新建很贵：一张最大是昼夜两张 512×512 = 2 MB 显存（4 档 256×256 = 512 KB），
	 * 还要把两张整页各上传一次。缩到最小时一屏有 {@code WorldTilePages#MAX_COARSE_PAGES} = 576 张，
	 * "见到没有就顺手新建"一帧就能全建出来，是确定性的巨型卡顿与显存尖峰。
	 *
	 * <p>所以绘制里<b>只取不建</b>（见 {@code WorldTilePages#findCoarse}），没建过的才花这里的额度；
	 * 超出的那一帧先不建也不画、露界面底色，下一帧接着建。宁可少露几块底色，也不整帧卡住。
	 */
	private static final int MAX_COARSE_CREATES_PER_FRAME = 2;

	/**
	 * 打开地图时，每帧允许用于建数据的时间：跟着帧时间走，下限 {@value #WARM_BUDGET_MIN_MILLIS} ms、
	 * 上限 {@value #WARM_BUDGET_MAX_MILLIS} ms。
	 *
	 * <p>固定 4 ms 加载一屏要好几秒；固定"帧时间的 40%、上限 12 ms"又走到另一头——建数据每帧都吃满，
	 * 刚打开时一屏上万区块，连续几秒每帧被吃掉四成，观感就是"打开地图很卡"。
	 *
	 * <p>按帧时间的 {@value #WARM_BUDGET_RATIO} 取、上限 {@value #WARM_BUDGET_MAX_MILLIS} ms：
	 * 60 帧时约 5 ms、30 帧时约 7 ms。铺满慢一点，但帧率稳住——慢能看出是"在加载"，掉帧只能看出是"卡"。
	 */
	private static final double WARM_BUDGET_RATIO = 0.3;
	private static final double WARM_BUDGET_MIN_MILLIS = 2.5;
	private static final double WARM_BUDGET_MAX_MILLIS = 7.0;

	private static final int BACKGROUND = 0xFF05050A;
	private static final int TEXT_COLOR = 0xFFFFFFFF;

	/**
	 * UI 图标素材：都收进了原版 GUI 图集（见 {@code assets/sinanmap/atlases/gui.json}），
	 * 所以这里返回的是<b>图集里的 sprite id</b>。用 {@code blitSprite} 画会按图集记录的精灵尺寸绘制，
	 * 高分辨率资源包能自动缩放（旧写法把源尺寸写死在代码里，2× 贴图只会采到左上角 1/4）。
	 *
	 * <p>图形都是<b>纯白 + 右下 1px 灰阴影</b>，颜色全靠绘制时乘上去。
	 */
	private static Identifier guiSprite(String name) {
		return Identifier.fromNamespaceAndPath(SinanConstants.MOD_ID, name);
	}

	private static final Identifier ICON_SETTINGS = guiSprite("settings");
	private static final Identifier ICON_ZOOM_IN = guiSprite("zoom_in");
	private static final Identifier ICON_ZOOM_OUT = guiSprite("zoom_out");
	private static final Identifier ICON_LOCATE = guiSprite("locate");

	/** 路径点按钮的图标（菱形，与地图上画的标记同形）。 */
	private static final Identifier ICON_WAYPOINT = guiSprite("waypoint");

	/**
	 * 图标贴图的边长（像素）：<b>16 格本体 + 右下 1px 阴影</b>。取 17 而不是 16：只取 16 会把阴影的
	 * 右、下两边裁掉，设置与定位这两个铺满本体宽度的图形看着就是"没放正"。
	 */
	private static final int ICON_REGION = 17;

	/**
	 * 比例尺素材：一条 16×6 的图。左端含竖线与横线起头、中段是 2px 一小段、右端含竖线，
	 * 三段的源坐标见下面的常量。
	 *
	 * <p><b>它不在 {@code gui/sprites/} 里</b>：这个常量描述的是贴图里的<b>子块</b>（左端/中段/右端），
	 * 走"直接贴图 + 子块"那条路（见 {@link #drawPiece}）；{@code textures/gui/sprites/} 下的 PNG
	 * 会被 GUI 图集收走、不再作为独立贴图存在，所以它必须留在 {@code textures/gui/} 下。
	 */
	private static final Identifier BAR_TEXTURE =
			Identifier.fromNamespaceAndPath(SinanConstants.MOD_ID, "textures/gui/scale_bar.png");
	private static final int BAR_TEXTURE_WIDTH = 16;
	private static final int BAR_TEXTURE_HEIGHT = 6;
	private static final int BAR_LEFT_X = 0;
	private static final int BAR_MID_X = 8;
	private static final int BAR_MID_WIDTH = 2;
	private static final int BAR_RIGHT_X = 12;
	private static final int BAR_CAP_WIDTH = 4;
	private static final int BAR_HEIGHT = 6;

	/** 按钮：判定框就是画出来的图标（边长 {@value #BUTTON_SIZE}），间距 2、离屏幕边 6。 */
	private static final int BUTTON_SIZE = 12;
	private static final int BUTTON_GAP = 2;
	private static final int BUTTON_MARGIN = 6;

	private static final int BUTTON_SETTINGS = 0;
	private static final int BUTTON_WAYPOINTS = 1;
	private static final int BUTTON_DIMENSION = 2;
	private static final int BUTTON_LAYER = 3;
	private static final int BUTTON_LOCATE = 4;
	private static final int BUTTON_ZOOM_IN = 5;
	private static final int BUTTON_ZOOM_OUT = 6;
	private static final int BUTTON_COUNT = 7;

	/**
	 * 右下那一列里，按钮从下往上属第几行（0 是最底下）。
	 *
	 * <p>定位在最底，往上是缩小 / 放大——"± 在定位上方"。设置 / 路径点 / 维度 / 层级贴在<b>左上角</b>，
	 * 不走这一列，见 {@link #buttonY}。
	 */
	private static int buttonRow(int button) {
		return switch (button) {
			case BUTTON_LOCATE -> 0;
			case BUTTON_ZOOM_OUT -> 1;
			case BUTTON_ZOOM_IN -> 2;
			default -> 0;
		};
	}

	/** 在左上角那一列的按钮（设置 / 路径点 / 维度 / 层级）。 */
	private static boolean isTopLeft(int button) {
		return button <= BUTTON_LAYER;
	}

	/**
	 * 图标的三种颜色：常态纯白，悬停与按下都是<b>压暗</b>（按下更暗）。
	 *
	 * <p>不用带色相的变色：地图本身颜色就很杂，彩色图标混在里面像是"选中的东西"，
	 * 而这里要表达的只是"鼠标在这儿"。
	 */
	private static final int ICON_COLOR = 0xFFFFFFFF;
	private static final int ICON_COLOR_HOVER = 0xFFB4B4B4;
	private static final int ICON_COLOR_PRESSED = 0xFF6E6E6E;

	/** 比例尺想占多长（GUI 像素），以及允许取用的"整距离"档位（单位：格=米）。 */
	private static final double BAR_TARGET_WIDTH = 72.0;
	private static final double[] BAR_STEPS = {
			1, 2, 5, 10, 20, 50, 100, 200, 500,
			1000, 2000, 5000, 10000, 20000, 50000,
	};

	/**
	 * 按住 ± 不放时的缩放节奏：<b>不走"先等一段再重复"的老路</b>（那会先愣一下、然后一顿一顿地跳），
	 * 改成<b>按下即连续</b>：每帧按时间比例推进 {@value #HOLD_STEP_NANOS} 走一格 {@value #ZOOM_STEP}，
	 * 起步就是匀速，也不受帧率影响。
	 */
	private static final long HOLD_STEP_NANOS = 100_000_000L;

	/** 当前鼠标悬停 / 按下的按钮下标，-1 表示没有。 */
	private int hoveredButton = -1;
	private int pressedButton = -1;

	/** 按住不放的计时：0 表示还没开始计。 */
	private long holdStartNanos;

	/**
	 * 键盘 - / + 的按住状态：0 没按、1 放大、2 缩小。单独一份、不复用 {@link #pressedButton}：
	 * 混用会让"按着键时点一下鼠标"把键那边的连续缩放也一起掐掉。见 {@link #tickZoomKeys}。
	 */
	private int zoomKeyHold;
	/** 按下 - / + 的时刻与那一刻的缩放目标：连续推进以它为基准按时间算。 */
	private long zoomKeyStartNanos;
	private double zoomKeyBaseTarget;
	/** 按住 ± 按钮那一路的基准目标（同上，见 {@link #tickButtonHold}）。 */
	private double holdBaseTarget;

	/**
	 * 按住方向键时的移动速度：每秒走过多少个屏幕。
	 *
	 * <p>取 {@value}：以前"每按一次跳 {@code 15%} 屏高"，轻点一下跨过一大片、一格一格地跳；
	 * 现在按住一秒走半个屏幕（跨一屏约两秒），观感是"细腻地滑"。
	 */
	private static final double PAN_SCREENS_PER_SECOND = 0.5;

	/**
	 * 方向键的起步缓入：从 0 速度开始，用这么久加速到 {@link #PAN_SCREENS_PER_SECOND}。
	 *
	 * <p>以前按下第一帧先走一段固定距离，<b>刚按下那一瞬会猛地窜一下</b>；现在速度从 0 按时间参数
	 * 涨上来，手感是"推一下才动起来"，松键立即停。
	 */
	private static final long PAN_RAMP_NANOS = 120_000_000L;

	/** 一帧最多推进这么长的按键时间：中间卡了一两帧时不把攒下的位移一口气放出来。 */
	private static final long PAN_MAX_FRAME_NANOS = 100_000_000L;

	/** 方向键的按下状态；见 {@link #tickPanKeys}。 */
	private static final int PAN_UP = 0;
	private static final int PAN_DOWN = 1;
	private static final int PAN_LEFT = 2;
	private static final int PAN_RIGHT = 3;
	private final boolean[] panKeys = new boolean[4];

	/** 按住方向键时上一次推进的时刻（0 = 没在按）。 */
	private long lastPanNanos;

	/** 这一轮方向键按下是从什么时候开始的（0 = 没在按）：起步缓入按它算，见 {@link #PAN_RAMP_NANOS}。 */
	private long panPressNanos;

	// ------------------------------------------------------------ 路径点面板（任务：世界地图上的路径点面板）

	/** 面板宽度、内边距、行高、搜索框高度（GUI 像素）。 */
	private static final int PANEL_WIDTH = 92;
	private static final int PANEL_PADDING = 2;
	private static final int PANEL_TITLE_HEIGHT = 9;
	private static final int PANEL_ROW_HEIGHT = 11;
	private static final int PANEL_SEARCH_HEIGHT = 12;

	/** 列表最多显示这么多行，再多就滚动。 */
	private static final int PANEL_MAX_ROWS = 10;

	// ------------------------------------------------ 维度 / 层级两个浮层（左上角按钮的下拉）

	/** 维度列表的宽（与路径点面板同宽，两个浮层的行排版就完全一致）。 */
	private static final int DIMENSION_POPUP_WIDTH = PANEL_WIDTH;

	/**
	 * 层级浮层：上面一条<b>自绘滑条</b>（与设置界面 {@link SinanStyle#drawSlider} 同一份画法），
	 * 下面一个手输 Y 的输入框（宽度与右下角那个 x/y 跳转框一致，看着才是一套）。
	 */
	private static final int LAYER_SLIDER_WIDTH = 76;
	private static final int LAYER_SLIDER_HEIGHT = 14;
	private static final int LAYER_INPUT_GAP = 1;
	private static final int LAYER_INPUT_WIDTH = 76;
	private static final int LAYER_INPUT_HEIGHT = 14;
	private static final int LAYER_POPUP_WIDTH = LAYER_SLIDER_WIDTH;
	private static final int LAYER_POPUP_HEIGHT =
			LAYER_SLIDER_HEIGHT + LAYER_INPUT_GAP + LAYER_INPUT_HEIGHT;

	/** 手动层级能拖到的范围（Y 值）；默认自动。 */
	private static final int LAYER_MIN = -64;
	private static final int LAYER_MAX = 320;

	/** {@link #manualLayerY} 取这个值表示"自动"（跟着玩家/地表走）。 */
	private static final int AUTO_LAYER = Integer.MIN_VALUE;

	/** 维度列表浮层开着。 */
	private boolean dimensionOpen;
	/** 维度列表顶部的搜索框（开着的时候才有）。 */
	private SinanTextField dimensionSearchBox;
	/** 维度搜索词（已经转成小写）。 */
	private String dimensionQuery = "";
	/** 层级浮层开着。 */
	private boolean layerOpen;
	/** 正在拖层级滑轨。 */
	private boolean layerDragging;

	/**
	 * 地图当前显示哪个维度；{@code null} = 跟着玩家所在的维度。
	 *
	 * <p>切过去只是"看"，不改变玩家所在维度：地形从那个维度存过的数据里读（见
	 * {@code ClientChunkColorProvider#setViewDimension}），切回来时视图中心按维度比例换算。
	 */
	private String displayDimension;

	/** 手动层级 Y；{@link #AUTO_LAYER} 表示自动。 */
	private int manualLayerY = AUTO_LAYER;

	/**
	 * 面板展开动画时长。
	 *
	 * <p>面板本身<b>没有底色</b>（整块透明）：只画标题、行文字、行高亮与滚动指示，底下地形透出来。
	 */
	private static final long PANEL_APPEAR_NANOS = 140_000_000L;

	/** 当前被悬停（正在展开）的那条路径点：命中滞回要用它，见 {@link #waypointForHover}。 */
	private UUID hoveredWaypointId;

	/** 列表里名字太长被截断时的省略号。 */
	private static final String ELLIPSIS = "…";

	/** 面板是不是展开着；面板的输入框在 {@link #searchBox}。 */
	private boolean panelOpen;
	private long panelOpenedNanos;
	private int panelScroll;
	private SinanTextField searchBox;
	private String panelQuery = "";

	/**
	 * 路径点面板当前看哪个维度的路径点；{@code null} = 跟随当前显示维度（{@link #currentDimension()}）。
	 *
	 * <p>界面内的临时状态：不落盘，随面板一起收起（见 {@link #closePanel()}）。它只改"看"——面板列表与
	 * 地图上的标记会切到那个维度，但地形仍是当前维度的；跨维度时按 {@link Dimensions#overworldScale}
	 * 把显示坐标换算过来（地狱 ↔ 主世界 1:8），<b>不改动路径点自身存的坐标</b>。
	 */
	private String panelDimension;

	/**
	 * 维度 / 层级两个浮层的展开时刻（见 {@link #popupFade}）。
	 *
	 * <p>三个浮层用<b>同一套过场</b>——整块淡入，时长与路径点面板一致，
	 * 于是"点开"这件事在哪一层都是同一个手感。
	 */
	private long dimensionOpenedNanos;
	private long layerOpenedNanos;

	/**
	 * 浮层展开的淡入进度（0~1，平滑收尾）。
	 *
	 * <p>掉用方把它乘到<b>每一处颜色</b>上（文字、高亮、滑条、输入框），整块因此是"渐渐显出来"的，
	 * 而不是滑进来或直接跳出来。命中判定与位移无关，所以过场期间点选照样准。
	 */
	private static float popupFade(long openedNanos) {
		float t = Math.clamp((System.nanoTime() - openedNanos) / (float) PANEL_APPEAR_NANOS, 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}

	/** 已经算出来的那一份列表（当前维度 + 搜索词筛过），以及它对应的"依据"。 */
	private List<Waypoint> panelRows = List.of();
	private long panelRevision = -1;
	private String panelBuiltQuery = "";
	private String panelBuiltDimension = "";

	// ------------------------------------------------------------ 刻度尺上的坐标跳转（任务：刻度尺上的"跳转"）

	/** 跳转按钮 / 输入框的尺寸与离底部那条尺子的间距。 */
	private static final int JUMP_WIDTH = 76;
	private static final int JUMP_HEIGHT = 14;
	private static final int JUMP_BOTTOM_GAP = 4;

	/** 跳转坐标的最大绝对值：原版世界边界就是三千万，再远也没有意义（还会让页面索引溢出）。 */
	private static final double JUMP_LIMIT = 30_000_000.0;

	/** 非法输入的提示留多久。 */
	private static final long JUMP_HINT_NANOS = 2_500_000_000L;

	private boolean jumpOpen;
	private SinanTextField jumpBox;
	private String jumpHint = "";
	private long jumpHintUntilNanos;

	/** 层级浮层右侧那个手输 Y 的框（空 = 自动）。 */
	private SinanTextField layerBox;

	/**
	 * 定位 / 跳转过场：在不在跑、跑到哪。
	 *
	 * <p><b>只存终点，不存起点</b>：起点就是"此刻的视口中心"，每帧现取（见 {@link #tickCenterAnimation}）。
	 * 于是过场途中又被叫去别处时，直接从当前位置接着往新目标收，不会跳一下。
	 */
	private boolean centerAnimActive;
	private double centerAnimToX;
	private double centerAnimToZ;
	/** 上一次画尺子时取的整距离，用来只在档位跳变时记日志。 */
	private double lastBarDistance = -1;

	private final ClientChunkColorProvider colorProvider;
	private final WorldTilePages tiles;
	private final MarkerRenderer markerRenderer = new MarkerRenderer();

	/**
	 * 生物雷达：全屏地图上也叠一层附近生物的小头像。
	 *
	 * <p>与小地图各持一份实例（两者不同帧渲染），设置共用同一份 {@link MinimapSettings}，改一处两处都变。
	 */
	private final EntityRadar entityRadar = new EntityRadar();

	/** 与小地图共用的可调项（雷达开关/半径/仅敌对等）。 */
	private final MinimapSettings settings;

	/** 小地图 HUD：设置界面里"编辑 HUD 位置"要进的那个编辑界面需要它来画实时小地图。 */
	private final MinimapHud minimap;

	/** 显示哪一层：地表 / 洞穴 / 自动。 */
	private LayerMode layerMode = LayerMode.AUTO;

	/** 缩放：每设备像素多少方块（1.0 即"一个方块一个设备像素"），按时间常数平滑逼近目标值。 */
	private final ZoomAnimator zoom = new ZoomAnimator(1.0);

	/**
	 * 视口中心（世界坐标）。
	 *
	 * <p>全屏地图固定正北朝上、不旋转，因此视口只需要中心和缩放两个量，不必引入带旋转的地图视口对象。
	 */
	private double centerX = 64.0;
	private double centerZ = 64.0;

	/** 缩放锚点：光标按下滚轮时所在的世界坐标与 GUI 位置。 */
	private double anchorWorldX;
	private double anchorWorldZ;
	private double anchorGuiX;
	private double anchorGuiY;
	private boolean anchoring;

	private boolean autoZoom = true;
	private boolean zoomAnimating;
	private boolean centered;
	private long lastFrameNanos;

	/**
	 * 上一次渲染时看到的 {@link ClientChunkColorProvider#frameSerial()}。打开地图时小地图 HUD 不再渲染、
	 * 帧号本该由它推进，这里据此判断"帧号冻住了没有"，冻住就自己补推一次（见 {@code beginFrameIfStale}）。
	 */
	private long lastSeenFrameSerial;

	/** 是否正在按住左键拖动地图。 */
	private boolean dragging;

	/**
	 * 这一下左键是不是按在<b>盖在地图之上的浮层</b>上（路径点面板、搜索框、跳转按钮/输入框）。
	 *
	 * <p>浮层必须<b>吃掉鼠标事件</b>：按在面板上再拖是在面板里划拉，不该把地图拖走。命中只在按下的
	 * 那一下判一次，之后光标滑出面板也照样算"这一下属于面板"。
	 */
	private boolean overlayDragCaptured;

	/** 上一次拖动事件里光标所在的 GUI 坐标；位移以它为基准自己算（原因见 {@link #mouseDragged}）。 */
	private double lastCursorGuiX;
	private double lastCursorGuiY;

	/** 拖动惯性：松手后继续滑行的速度（方块/秒）。 */
	private double inertiaX;
	private double inertiaZ;

	/** 速度采样窗口的起点与窗口内累计的位移。 */
	private long dragSampleNanos;
	private double dragSampleX;
	private double dragSampleZ;

	/** 还剩几帧要提防"折返造成的大位移"。 */
	private int wrapGuardFrames;

	/** 补数据时比视野多往外铺几页。 */
	private static final int PAGE_MARGIN = 2;

	/** 当前视野覆盖的页范围（含端点）。绘制只用这个范围。 */
	private int viewMinX;
	private int viewMinZ;
	private int viewMaxX = Integer.MIN_VALUE;
	private int viewMaxZ = Integer.MIN_VALUE;

	/**
	 * 补数据用的页范围：在视野基础上多铺 {@value #PAGE_MARGIN} 圈。
	 *
	 * <p>只填"看得见的"那一屏，拖动时新露出来的部分必然是空的、要等填；多铺一圈之后，
	 * 拖过去大多已经是填好的页，观感就从"边拖边长"变成"跟着手滑动"。
	 */
	private int pageMinX;
	private int pageMinZ;
	private int pageMaxX = Integer.MIN_VALUE;
	private int pageMaxZ = Integer.MIN_VALUE;

	/**
	 * 视野内各页的填充顺序：以玩家所在页为起点、一圈圈向外，于是地图由玩家脚下往外长。
	 *
	 * <p><b>不记"走到第几页"这种全局游标</b>：每帧都从数组头部重新扫、跳过已填完的页，页内进度记在
	 * 页自己身上（见 {@link WorldTilePages#hasWork}）。这样玩家一动、顺序一重排，正在加载的就是身边那一页。
	 */
	private long[] fillOrder;

	/** {@link #fillOrder} 是以哪个页为中心排出来的；玩家换了页就得重排。 */
	private int fillOrderCenterPageX = Integer.MIN_VALUE;
	private int fillOrderCenterPageZ = Integer.MIN_VALUE;

	/**
	 * 粗档的填充顺序（<b>粗页坐标</b>打包成 long）。
	 *
	 * <p>粗档一格 = {@code COARSE_PACK}×{@code COARSE_PACK} 张叶页格位，所以按粗页坐标单独排一份
	 * （见 {@link #rebuildFillOrders}），条目数与张数都只有叶页的四分之一，绘制时一页只画一次。
	 */
	private long[] coarseOrder;

	/**
	 * 本帧用哪一档贴图：{@code 1} = 叶页（1 方块/像素）、{@code 2} / {@code 4} = 对应粗档。
	 * 由 {@link #updateTerrainTier()} 每帧按缩放带迟滞选一次；填充、绘制、诊断都读它。
	 */
	private int terrainTier = 1;

	/** 本帧已经重传了几页。 */
	private int uploadsThisFrame;

	private int lastLayer = Integer.MAX_VALUE;

	/**
	 * 当前显示的那一层。瓦片的页键带着层（见 {@link WorldTilePages#key}），填充、绘制、作废都要用它查。
	 * 单独立一个字段而不是复用 {@link #lastLayer}：换世界时 lastLayer 会被重置成"未定"。
	 */
	private int currentLayer = LayerSelector.SURFACE;

	private ClientLevel lastLevel;

	public WorldMapScreen(ClientChunkColorProvider colorProvider, MinimapHud minimap) {
		super(Component.translatable("screen.sinanmap.world_map"));
		this.colorProvider = colorProvider;
		this.minimap = minimap;
		this.settings = minimap.settings();
		this.tiles = WorldTilePages.shared(colorProvider);
	}

	@Override
	protected void init() {
		if (!centered) {
			centered = true;
			LocalPlayer player = Minecraft.getInstance().player;
			if (player != null) {
				viewCenterOn(player.getX(), player.getZ());
			}
			// 首次打开直接取自动缩放值，避免开场那一下缩放动画
			zoom.snapTo(defaultZoom());
		}
		lastFrameNanos = 0L;
		// 换尺寸（或第一次打开）会重建控件：手上那两个输入框的引用随之作废，
		// 面板/跳转框还开着的话在这里补建，否则它们会静默地"消失"（其实是对象已经不在了）
		searchBox = null;
		jumpBox = null;
		layerBox = null;
		if (panelOpen) {
			ensureSearchBox();
		}
		if (jumpOpen) {
			ensureJumpBox();
		}
		if (layerOpen) {
			ensureLayerBox();
		}
	}

	/**
	 * 别的界面（地图设置）把这张地图当背景画时，窗口尺寸变了要手动同步一次。
	 *
	 * <p>它不是"当前界面"，收不到 {@code resize}/{@code init}，而它的按钮位置、面板范围都是按
	 * {@code width}/{@code height} 当场算的——不同步就会一直停在旧尺寸上，当背景画时还会连地形缩放都不对。
	 */
	public void updateSize(int width, int height) {
		this.width = width;
		this.height = height;
		// 会重建搜索框 / 跳转框 / 层级框那几个控件（见 init），所以走同一条路
		init();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, width, height, BACKGROUND);

		long nowNanos = System.nanoTime();
		double deltaMillis = lastFrameNanos == 0L ? 0.0 : (nowNanos - lastFrameNanos) / 1.0e6;
		lastFrameNanos = nowNanos;

		// 天色每帧对齐一次：只在地形检测里刷新的话，进世界第一帧会拿着"白天"的天色去画夜晚的地图
		colorProvider.refreshSky();

		// 按住 ± 不放时连续缩放
		tickButtonHold(nowNanos);
		tickZoomKeys(nowNanos);
		// 按住方向键时连续平移（同样是"按下即连续、按时间比例推进"，见 tickPanKeys）
		tickPanKeys(nowNanos);
		// 定位自身的过场
		tickCenterAnimation(deltaMillis);

		// 自动缩放：一个方块一个 GUI 像素；换算成设备像素要再乘 GUI 缩放
		updateZoom(deltaMillis);
		// 开场过场：先比目标"更放大"一档开始，再让缩放动画收回去，于是地图"由放大到缩小"地出现
		if (!opened) {
			opened = true;
			zoom.snapCurrent(zoom.target() / OPENING_ZOOM);
			// 过场期间用"慢速"时间常数：默认那个（45 ms）一眨眼就走完，等于没有动画
			zoom.setSlow(true);
		}
		// 松手后的滑行：视口在动，但瓦片不用重新采样，只是换个位置贴
		applyInertia(deltaMillis);

		// 决定显示哪一层：层的判定统一在 colorProvider 里（两处界面必须一致）。
		// 手动层级（层级浮层拖出来的）直接盖过自动判定
		int layer = manualLayerY == AUTO_LAYER ? colorProvider.resolveLayer(layerMode) : manualLayerY;
		colorProvider.setLayer(layer);
		currentLayer = layer;
		if (layer != lastLayer) {
			// 洞穴里的显示层逐格跟着玩家高度走（见 LayerSelector#resolve），所以这条每格都会进来：
			// 只有"地表 ↔ 洞穴"这种类型变化才值得记一行、才做缩放过场；洞穴内部的高度变化不换页键。
			if (lastLayer != Integer.MAX_VALUE && !LayerSelector.isSameKind(lastLayer, layer)) {
				LocalPlayer player = Minecraft.getInstance().player;
				LOGGER.info("地图层级切换 | {} → {} | 玩家 Y={}",
						describeLayer(lastLayer), describeLayer(layer),
						player == null ? "-" : player.getBlockY());
				// 只有类型变化才做缩放过场，方向跟着深度走
				startLayerTransition(lastLayer, layer);
			}
			lastLayer = layer;
			// 换层不用动任何一页：每一层各有一套瓦片（见 WorldTilePages#key），新层的页由 fillView
			// 按需建出来，旧层的页原地留着，回到那一层立刻画得出来。
		}

		ensureLevel();
	uploadsThisFrame = 0;
		// 补推帧号：帧号本该由小地图 HUD 推进，但打开地图时那层 HUD 不绘制、帧号会冻住，
		// 那样"每帧一份建数据预算"与"每帧一次改动检测"两条闸都永久失效。必须排在
		// fillView / refreshLoadedChunks 之前。
	colorProvider.beginFrameIfStale(lastSeenFrameSerial);
	lastSeenFrameSerial = colorProvider.frameSerial();
	// 诊断：把"帧时间花在哪一段"量出来（见 logComposition）。卡顿是查出来的，不是猜出来的
	long frameStartNanos = System.nanoTime();
	tiles.beginFrame();
		fillView(deltaMillis);
		long afterFillNanos = System.nanoTime();

		// 地形被改过的地方：把对应瓦片作废，下一帧重填就会画出新的样子。放在 fillView 之后
		colorProvider.refreshLoadedChunks(nowNanos);
		for (long packed : colorProvider.drainChangedChunks()) {
			tiles.markStale(currentLayer, (int) (packed >> 32), (int) packed);
		}
		// 后台刚读到数据的地方：只把那几格补画上，已画着地形的格子不动（否则已加载好的地方又会被涂掉重画）
		for (long packed : colorProvider.drainArrivedChunks()) {
			tiles.markArrived(currentLayer, (int) (packed >> 32), (int) packed);
		}
		long afterRefreshNanos = System.nanoTime();

		drawTerrain(graphics);
		long afterDrawNanos = System.nanoTime();
		// 生物雷达先画、路径点后画：<b>实体头像要在路径点之下</b>，否则头像会盖住
		// 路径点的标记与名字。位置换算与路径点同一口径，头像在屏幕坐标系里正立，
		// 不随地图缩放变形（见 EntityRadar）
		drawEntityRadar(graphics);
		drawTrackedPlayers(graphics);
		drawWaypoints(graphics, mouseX, mouseY);
		drawPlayerMarker(graphics);
		drawText(graphics, mouseX, mouseY);
		// 菜单画在最上层：它是临时弹出来的东西，压在地图与坐标文字之上才对
		drawMenu(graphics, mouseX, mouseY);
		// 两个输入框都是自绘的（见 SinanTextField，已在 drawWaypointPanel / drawJumpInput 里画完）；
		// 这里保留基类调用，是为了其它原版行为（如背景等）仍然生效
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		// 鼠标样式必须放在最后：一帧里最后一次 requestCursor 才生效（见 updateCursor）
		updateCursor(graphics, mouseX, mouseY);
		recordFrameCost(frameStartNanos, afterFillNanos, afterRefreshNanos, afterDrawNanos);
		logComposition();
	}

	/**
	 * 按光标位置请求鼠标样式。
	 *
	 * <p>一帧里<b>最后</b>一次 {@link GuiGraphicsExtractor#requestCursor(CursorType)} 生效
	 * （{@code GameRenderer} 在整帧 GUI 画完后才交给窗口），所以必须放在 {@code super.extractRenderState}
	 * 之后，否则会被控件的请求盖掉。
	 *
	 * <ul>
	 *   <li>输入框（面板搜索 / 坐标跳转）→ 文本光标 {@link CursorTypes#IBEAM}</li>
	 *   <li>可以点的东西（工具条按钮、跳转按钮、列表里的路径点）→ 手型 {@link CursorTypes#POINTING_HAND}</li>
	 *   <li>其余（地图本身、面板的空白处这类滚动区域）→ 默认箭头 {@link CursorTypes#ARROW}</li>
	 * </ul>
	 */
	private void updateCursor(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		CursorType cursor = CursorTypes.ARROW;
		// <b>浮层优先</b>：右键菜单是压在最上面的一层，光标落进它里面就只按菜单本身决定，
		// 被它盖住的输入框 / 按钮 / 地图都不再参与判定——否则菜单压着搜索框 / 跳转框时，
		// 底下那个输入框仍会把光标改成文本光标
		if (menuContains(mouseX, mouseY)) {
			cursor = menuIndexAt(mouseX, mouseY) >= 0 ? CursorTypes.POINTING_HAND : CursorTypes.ARROW;
		} else if (layerTrackContains(mouseX, mouseY) || layerDragging) {
			// 层级滑轨是横着拖的（拖起来之后也要保持这个样式，光标移出轨道也不变）
			cursor = CursorTypes.RESIZE_EW;
		} else if (pointsAtInput(mouseX, mouseY)) {
			cursor = CursorTypes.IBEAM;
		} else if (dimensionPopupContains(mouseX, mouseY) || layerPopupContains(mouseX, mouseY)) {
			// 维度 / 层级浮层也是压在上面的一层：落在它上面就不再判底下的东西
			cursor = CursorTypes.POINTING_HAND;
		} else if (hoveredButton >= 0
				|| jumpHit(mouseX, mouseY)
				|| panelDimensionHit(mouseX, mouseY)
				|| panelContains(mouseX, mouseY) && panelRowAt(mouseY) >= 0) {
			cursor = CursorTypes.POINTING_HAND;
		}
		graphics.requestCursor(cursor);
	}

	/** 光标是不是落在已经弹出的右键菜单里（含内边距那一圈）；菜单没弹时永远为 false。 */
	private boolean menuContains(double mouseX, double mouseY) {
		if (menu.isEmpty()) {
			return false;
		}
		double localX = (mouseX - menuX) / MENU_SCALE;
		double localY = (mouseY - menuY) / MENU_SCALE;
		return localX >= 0 && localX < menuWidth() && localY >= 0 && localY < menuHeight();
	}

	/** 光标是不是停在某个输入框上。 */
	private boolean pointsAtInput(double mouseX, double mouseY) {
		return searchBox != null && searchBox.isMouseOver(mouseX, mouseY)
				|| jumpBox != null && jumpBox.isMouseOver(mouseX, mouseY)
				|| dimensionSearchBox != null && dimensionSearchBox.isMouseOver(mouseX, mouseY)
				|| layerBox != null && layerBox.isMouseOver(mouseX, mouseY);
	}

	/**
	 * 记下这一帧各段的耗时（诊断用）：填充（取世界数据）、作废/补画（refresh + mark）、绘制。
	 * 单帧超过 {@value #SLOW_FRAME_MILLIS} 毫秒会<b>立刻</b>记一行，给"某一帧突然卡住"留下现场。
	 */
	private void recordFrameCost(long frameStart, long afterFill, long afterRefresh, long afterDraw) {
		long fill = afterFill - frameStart;
		long refresh = afterRefresh - afterFill;
		long draw = afterDraw - afterRefresh;
		long total = afterDraw - frameStart;
		fillNanosSum += fill;
		fillNanosMax = Math.max(fillNanosMax, fill);
		refreshNanosSum += refresh;
		refreshNanosMax = Math.max(refreshNanosMax, refresh);
		drawNanosSum += draw;
		drawNanosMax = Math.max(drawNanosMax, draw);
		frameNanosMax = Math.max(frameNanosMax, total);
		framesThisSecond++;
		if (total >= SLOW_FRAME_MILLIS * 1_000_000L) {
			LOGGER.info("世界地图慢帧 | 本帧 {} ms（填充 {} / 作废补画 {} / 绘制 {}）| 页={} 问={} 画={} 待补={} 传={}",
					ms(total), ms(fill), ms(refresh), ms(draw),
					viewPageCount(), tiles.frameAsks(), tiles.framePaints(), pendingOfView(), uploadsThisFrame);
		}
	}

	/** 单帧超过这么多毫秒就立刻记一行，见 {@link #recordFrameCost}。 */
	private static final long SLOW_FRAME_MILLIS = 40L;

	private static String ms(long nanos) {
		return "%.1f".formatted(nanos / 1.0e6);
	}

	/** 纳秒 → 微秒（保留 1 位）；见"世界地图计量"那行。 */
	private static String us(long nanos) {
		return "%.1f".formatted(nanos / 1.0e3);
	}

	/** 已算好的秒数（保留 2 位）；负数表示"还没有完整样本"，显示 {@code -}。 */
	private static String sec(double seconds) {
		return seconds < 0.0 ? "-" : "%.2f".formatted(seconds);
	}

	/** 视野内已经建出来的页数（诊断用）。 */
	private int viewPageCount() {
		int count = 0;
		for (int pageZ = viewMinZ; pageZ <= viewMaxZ; pageZ++) {
			for (int pageX = viewMinX; pageX <= viewMaxX; pageX++) {
				if (tiles.find(currentLayer, pageX, pageZ) != null) {
					count++;
				}
			}
		}
		return count;
	}

	/** 视野内"还在等数据"的块数（诊断用，与 {@link #logComposition} 同一套口径）。 */
	private int pendingOfView() {
		int pending = 0;
		for (int pageZ = viewMinZ; pageZ <= viewMaxZ; pageZ++) {
			for (int pageX = viewMinX; pageX <= viewMaxX; pageX++) {
				TilePage page = tiles.find(currentLayer, pageX, pageZ);
				if (page != null) {
					pending += page.pendingCount();
				}
			}
		}
		return pending;
	}

	/**
	 * 画当前维度的路径点：<b>没展开时只有"名字的首字"</b>，光标停在上面就展开成全名（带下划线），
	 * 坐标点上方是一个尖端朝下的倒三角；标签带"路径点原色（悬停时压暗）"的不透明实色底板
	 * （见 {@link WaypointRenderer}）。
	 *
	 * <p>位置换算与地形、玩家标记同一套（{@link #originWorldX()} 与 {@link #blocksPerGui()}），因此缩放、
	 * 拖拽期间都跟地形严丝合缝。标记不随缩放变大变小——缩到很小时跟着缩就看不见了。
	 *
	 * <p><b>展开按"悬停"而不是"选中"</b>且带<b>滞回</b>（见 {@link #waypointForHover}）：光标扫过去就能
	 * 看清全名，停在边缘抖一抖也不会来回收起展开。
	 *
	 * <p><b>悬停 → 展开的链路（只有这里推进动画）：</b>{@link #waypointForHover} 每帧算出悬停项写进
	 * {@link #hoveredWaypointId}；本方法把它按 id 比出来当 {@code expanded} 传给
	 * {@link WaypointRenderer#drawOnWorldMap}，而后者是 {@code advance} 的<b>唯一</b>调用方，推进进度并按
	 * 进度插值标签宽度与透明度。<b>小地图与游戏内标记不走这条链路</b>（固定按收起画）：HUD 在世界地图
	 * 开着时一样会被抽取，若也推进同一份进度就会把这里的动画推回去、永远停在 0。
	 */
	private void drawWaypoints(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		List<Waypoint> waypoints = currentDimensionWaypoints();
		if (waypoints.isEmpty() || !settings.showMapWaypoints()) {
			hoveredWaypointId = null;
			return;
		}
		Waypoint hovered = waypointForHover(mouseX, mouseY);
		double perGui = blocksPerGui();
		double anchorX = originGuiX();
		double anchorZ = originGuiZ();
		double centerX = originWorldX();
		double centerZ = originWorldZ();
		int drawn = 0;
		for (Waypoint waypoint : waypoints) {
			if (!waypoint.visible()) {
				continue;
			}
			// 设置里关掉了死亡地点：骷髅那几条不画（面板列表仍然列出，那里是"管理"的入口）
			if (waypoint.death() && !settings.showDeathPoints()) {
				continue;
			}
			// 别的维度的路径点先换算到本地图的坐标系（地狱 ↔ 主世界 1:8），见 displayWaypointX
			double x = anchorX + (displayWaypointX(waypoint) - centerX) / perGui;
			double y = anchorZ + (displayWaypointZ(waypoint) - centerZ) / perGui;
			if (x < -WAYPOINT_MARGIN || x > width + WAYPOINT_MARGIN
					|| y < -WAYPOINT_MARGIN || y > height + WAYPOINT_MARGIN) {
				continue;
			}
			WaypointRenderer.drawOnWorldMap(graphics, font, x, y, waypoint,
					hovered != null && hovered.id().equals(waypoint.id()), settings);
			if (++drawn >= WaypointRenderer.MAX_WORLD_MAP_MARKERS) {
				break;
			}
		}
	}

	/** 屏幕外这么多 GUI 像素以内还画一下：标记与名字有一部分会伸进屏幕里。 */
	private static final int WAYPOINT_MARGIN = 48;

	/** 路径点面板当前显示的维度（见 {@link #panelDimension}）；切了面板维度或地图维度都要跟着换。 */
	private String panelDimensionId() {
		return panelDimension != null ? panelDimension : currentDimension();
	}

	/** {@link #panelDimensionId()} 那个维度的路径点；面板列表与地图标记都用它。 */
	private List<Waypoint> currentDimensionWaypoints() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null) {
			return List.of();
		}
		return WaypointManager.instance().store(minecraft).inDimension(panelDimensionId());
	}

	/**
	 * 把一条路径点的世界坐标换算到"当前显示维度"的坐标系，<b>只用于显示</b>。
	 *
	 * <p>比例来自 {@link Dimensions#overworldScale}：先换算到主世界口径、再换算到当前维度口径，
	 * 于是地狱 ↔ 主世界是 1:8（地狱 ×8 = 主世界，反向 ÷8），末地与模组维度 1:1。
	 * <b>不改动路径点自身存的坐标</b>——面板/编辑界面里那一列数值始终是它本来的坐标。
	 */
	private double displayWaypointX(Waypoint waypoint) {
		return waypoint.pos().x() * Dimensions.overworldScale(waypoint.pos().dimension())
				/ Dimensions.overworldScale(currentDimension());
	}

	/** 同 {@link #displayWaypointX}，纵向。 */
	private double displayWaypointZ(Waypoint waypoint) {
		return waypoint.pos().z() * Dimensions.overworldScale(waypoint.pos().dimension())
				/ Dimensions.overworldScale(currentDimension());
	}

	/**
	 * 画全屏地图上的生物雷达。
	 *
	 * <p>位置换算与地形、路径点、玩家标记一致（见 {@link #originWorldX()} 与 {@link #blocksPerGui()}）。
	 * 头像在屏幕坐标系里正立绘制，缩放地图时不变形、也不随（本就不存在的）旋转晃动。
	 *
	 * <p>只收集玩家附近的实体（半径见 {@link MinimapSettings#entityRadarRadius()}），在 {@link EntityRadar} 内部复用数组。
	 */
	private void drawEntityRadar(GuiGraphicsExtractor graphics) {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		LocalPlayer player = minecraft.player;
		if (level == null || player == null) {
			return;
		}
		// 看的是别的维度时雷达整块不画：实体只取玩家附近的已加载实体，它们属于<b>玩家当前</b>维度，
		// 画到另一个维度的地图上是错的——切维度后会显示玩家所在维度的生物
		if (displayDimension != null && !displayDimension.equals(level.dimension().identifier().toString())) {
			return;
		}
		entityRadar.refresh(level, player, settings);
		// 头像边长由 EntityRadar 自己算（和小地图共用同一条规则，见 radarIconSize），这里不再传
		entityRadar.drawOnWorldMap(graphics, width, height,
				originGuiX(), originGuiZ(), originWorldX(), originWorldZ(), blocksPerGui(), player.getY());
	}

	/**
	 * 画服务端同步过来的其他玩家。
	 *
	 * <p>与雷达的分工：雷达画已加载区块里的实体（走远就看不见），这里画服务端同步来的坐标
	 * （见 {@link TrackedPlayers}），可能根本不在客户端视野里。
	 *
	 * <p>只画<b>当前显示的那个维度</b>的：跨维度的 (x,z) 画在这张地图上是错的。
	 */
	private void drawTrackedPlayers(GuiGraphicsExtractor graphics) {
		if (TrackedPlayers.isEmpty()) {
			return;
		}
		String dimension = displayDimension;
		if (dimension == null) {
			LocalPlayer player = Minecraft.getInstance().player;
			if (player == null) {
				return;
			}
			dimension = player.level().dimension().identifier().toString();
		}
		double perGui = blocksPerGui();
		double anchorX = originGuiX();
		double anchorZ = originGuiZ();
		double centerX = originWorldX();
		double centerZ = originWorldZ();
		float opacity = (float) settings.mapWaypointOpacity();
		for (TrackedPlayersS2C.Player entry : TrackedPlayers.view()) {
			if (!entry.dimension().equals(dimension)) {
				continue;
			}
			double x = anchorX + (entry.x() - centerX) / perGui;
			double y = anchorZ + (entry.z() - centerZ) / perGui;
			if (x < -16 || x > width + 16 || y < -16 || y > height + 16) {
				continue;
			}
			TrackedPlayers.draw(graphics, font, x, y, entry, opacity);
		}
	}

	// ───────────────────────── 叠加层：区块网格 / 史莱姆区块 ─────────────────────────

	/**
	 * 与 {@link #drawTerrain} <b>完全相同</b>的那套变换：把矩阵推进"世界方块坐标"。
	 *
	 * <p>叠加层必须挂在这套变换里画，不能自己另算屏幕坐标。地形为了锐利会把落点吸附到设备像素栅格上、
	 * 并在这个栅格上按帧移动；另算一套则两者缩放时步长不同，看起来就是"网格在地图上抖"。
	 */
	private void pushBlockTransform(Matrix3x2fStack pose) {
		pose.pushMatrix();
		pose.translate((float) blockTransformBaseX(), (float) blockTransformBaseZ());
		pose.scale((float) (1.0 / blocksPerGui()));
	}

	/**
	 * 参考页左上角该落在的 GUI 横坐标，落点对齐到设备像素栅格：倍率是 2 的幂时一个方块正好落在整数个
	 * 设备像素上、取样点落在像素中心，画面才锐；不对齐则每帧在相邻像素间插值，整张图发糊。
	 */
	private double blockTransformBaseX() {
		double referenceX = (double) viewMinX * WorldTilePages.PAGE_BLOCKS;
		return snapToDevicePixel(originGuiX() + (referenceX - originWorldX()) / blocksPerGui(), guiScale());
	}

	/** 同 {@link #blockTransformBaseX()}，纵向。 */
	private double blockTransformBaseZ() {
		double referenceZ = (double) viewMinZ * WorldTilePages.PAGE_BLOCKS;
		return snapToDevicePixel(originGuiZ() + (referenceZ - originWorldZ()) / blocksPerGui(), guiScale());
	}

	/** 屏幕 x → 世界坐标 x。 */
	private double worldXAt(double guiX) {
		return originWorldX() + (guiX - originGuiX()) * blocksPerGui();
	}

	/** 屏幕 y → 世界坐标 z。 */
	private double worldZAt(double guiZ) {
		return originWorldZ() + (guiZ - originGuiZ()) * blocksPerGui();
	}

	/** 光标下有没有路径点（判定半径给得比标记本身大一点，小标记不好点）。 */
	private Waypoint waypointAt(double mouseX, double mouseY) {
		double perGui = blocksPerGui();
		double anchorX = originGuiX();
		double anchorZ = originGuiZ();
		double centerX = originWorldX();
		double centerZ = originWorldZ();
		Waypoint best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Waypoint waypoint : currentDimensionWaypoints()) {
			if (!waypoint.visible()) {
				continue;
			}
			// 与绘制同一套换算：别的维度的路径点先落到本地图坐标系里再判定命中
			double x = anchorX + (displayWaypointX(waypoint) - centerX) / perGui;
			double y = anchorZ + (displayWaypointZ(waypoint) - centerZ) / perGui;
			// 判定交给 WaypointRenderer：图标画在哪一格、名字多宽只有它知道，另写一套半径两边会不一致
			if (!WaypointRenderer.hit(font, x, y, waypoint, mouseX, mouseY)) {
				continue;
			}
			// 都命中的取离图标最近的那一条
			double distance = Math.hypot(mouseX - x, mouseY - y);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = waypoint;
			}
		}
		return best;
	}

	/**
	 * 光标停在哪个标记上——这是"悬停即展开"用的判定，<b>带滞回</b>。
	 *
	 * <p>已在悬停的那条用<b>更大的</b>判定框（{@link WaypointRenderer#stickyHit}），其余用普通框：
	 * 避免光标停在边缘时命中在"展开变宽 / 移开变窄"之间每帧翻转，也避免两条标记挨得近时来回换人。
	 *
	 * <p>判定框一律按<b>展开后的目标尺寸</b>算，不跟动画进度变（见 {@link WaypointRenderer#hit}），
	 * 所以展开动画不会反过来改变命中结果。
	 */
	private Waypoint waypointForHover(double mouseX, double mouseY) {
		Waypoint best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Waypoint waypoint : currentDimensionWaypoints()) {
			if (!waypoint.visible()) {
				continue;
			}
			double x = waypointScreenX(waypoint);
			double y = waypointScreenY(waypoint);
			boolean inside = waypoint.id().equals(hoveredWaypointId)
					? WaypointRenderer.stickyHit(font, x, y, waypoint, mouseX, mouseY)
					: WaypointRenderer.hit(font, x, y, waypoint, mouseX, mouseY);
			if (!inside) {
				continue;
			}
			// 都命中的取离标记最近的那一条
			double distance = Math.hypot(mouseX - x, mouseY - y);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = waypoint;
			}
		}
		hoveredWaypointId = best == null ? null : best.id();
		return best;
	}

	/** 一条路径点在屏幕上的横坐标（与地形、玩家标记同一套换算，含跨维度换算）。 */
	private double waypointScreenX(Waypoint waypoint) {
		return originGuiX() + (displayWaypointX(waypoint) - originWorldX()) / blocksPerGui();
	}

	/** 一条路径点在屏幕上的纵坐标。 */
	private double waypointScreenY(Waypoint waypoint) {
		return originGuiZ() + (displayWaypointZ(waypoint) - originWorldZ()) / blocksPerGui();
	}

	/** 右键菜单的一项。 */
	private record MenuItem(Component label, Runnable action) {
	}

	/** 当前弹出的右键菜单（空 = 没弹）。 */
	private final List<MenuItem> menu = new ArrayList<>();
	private int menuX;
	private int menuY;
	private int menuHovered = -1;

	/**
	 * 菜单的一项多高（内部单位，屏幕上还要乘 {@link #MENU_SCALE}）。
	 *
	 * <p><b>必须是"字高 + 偶数"</b>：字体行高 9，取 {@value} 时上下各留 1 像素、文字正中；取 12 的
	 * 多出 3 像素除不尽（上 2 下 1），高亮看着就和文字错位。
	 */
	private static final int MENU_ROW_HEIGHT = 11;

	/** 菜单的<b>左右</b>内边距。 */
	private static final int MENU_PADDING = 3;

	/**
	 * 菜单的<b>上下</b>内边距。取 {@value}：首行/末行的高亮正好贴着上下那两条 1 像素边框线；
	 * 取 2 会在边框线与首/末行之间各空 1 像素，看着就是"上下多了"。
	 */
	private static final int MENU_V_PADDING = 1;

	/**
	 * 菜单整体缩放。按原尺寸画出来偏大、压在大半屏地图上很碍事；缩到 {@value} 后字仍清楚，
	 * 整块只有原来的六成不到。
	 */
	private static final float MENU_SCALE = 0.75F;

	private void openMenu(double mouseX, double mouseY) {
		openMenu(mouseX, mouseY, null);
	}

	/**
	 * 在光标处弹出右键菜单。
	 *
	 * <p>光标底下有路径点就针对它（编辑 / 传送 / 删除），没有就是"在这一格新建"与
	 * "传送到这一格"。菜单最后固定带一条"路径点管理器"。
	 *
	 * @param forced 直接指定这份菜单针对哪条路径点（左侧列表里右键用），{@code null} = 按光标底下的判断
	 */
	private void openMenu(double mouseX, double mouseY, Waypoint forced) {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		if (level == null) {
			return;
		}
		// 先把视图停下来：不然光标底下这一格会随惯性继续滑走，菜单里的坐标与实际点的地方对不上，
		// 关掉菜单时画面也已经漂到别处去了
		centerAnimActive = false;
		inertiaX = 0.0;
		inertiaZ = 0.0;
		flushDragVelocity();

		String dimension = level.dimension().identifier().toString();
		BlockPoint cursor = worldAtGui(mouseX, mouseY);
		int surfaceY = surfaceHeightAt(cursor.x(), cursor.z());
		// 没加载过的地方拿不到地表高度："传送到此处"就用 `~`（保持玩家当前高度），
		// 而不是替他猜一个（猜 64 就是"落到地底"）；新建路径点时仍要塞一个 Y 进去
		// （路径点必须有完整坐标），所以那种情况下退回"洞穴层高 / 海平面"这个估计值。
		boolean yUnknown = surfaceY == NO_SURFACE;
		// 洞穴层里就以当前显示层为准——那一层的 Y 就是玩家所在的高度带
		int y = !yUnknown ? surfaceY
				: (LayerSelector.isCave(currentLayer) ? currentLayer : 64);
		WorldPos here = new WorldPos(dimension, Math.floor(cursor.x()), y, Math.floor(cursor.z()));
		Waypoint hovered = forced != null ? forced : waypointAt(mouseX, mouseY);

		menu.clear();
		if (hovered != null) {
			// 不再放"选中 XXX"这一项：选中路径点改为<b>左键直接点</b>它就完成
			// （见 mouseClicked 里 waypointAt 那一段），右键菜单只剩编辑 / 传送 / 分享 / 删除
			menu.add(new MenuItem(Component.translatable("screen.sinanmap.menu.edit"),
					() -> openEditor(hovered, null)));
			menu.add(new MenuItem(Component.translatable("screen.sinanmap.menu.teleport"),
					() -> WaypointManager.teleport(minecraft, hovered)));
			menu.add(new MenuItem(Component.translatable("screen.sinanmap.menu.share_waypoint"),
					() -> WaypointManager.shareWaypoint(hovered)));
			menu.add(new MenuItem(Component.translatable("screen.sinanmap.menu.delete"),
					() -> WaypointManager.instance().remove(minecraft, hovered.id())));
		} else {
			menu.add(new MenuItem(Component.translatable("screen.sinanmap.menu.create_here"),
					() -> openEditor(null, here)));
			menu.add(new MenuItem(Component.translatable("screen.sinanmap.menu.teleport_here"),
					() -> WaypointManager.teleportTo(minecraft, here, yUnknown)));
			menu.add(new MenuItem(Component.translatable("screen.sinanmap.menu.share_here"),
					() -> WaypointManager.shareLocation(here)));
		}
		menu.add(new MenuItem(Component.translatable("screen.sinanmap.menu.manager"),
				() -> minecraft.setScreen(new WaypointListScreen(this))));

		int menuWidth = Math.round(menuWidth() * MENU_SCALE);
		menuX = (int) Math.clamp(mouseX, 0, Math.max(0, width - menuWidth));
		menuY = (int) Math.clamp(mouseY, 0, Math.max(0, height - Math.round(menuHeight() * MENU_SCALE)));
		menuHovered = -1;
		menuOpenedNanos = System.nanoTime();
	}

	private void closeMenu() {
		menu.clear();
		menuHovered = -1;
	}

	/** 菜单的排版宽度（内部单位，屏幕上还要乘 {@link #MENU_SCALE}）。 */
	private int menuWidth() {
		int textWidth = 0;
		for (MenuItem item : menu) {
			textWidth = Math.max(textWidth, font.width(item.label()));
		}
		return textWidth + MENU_PADDING * 4;
	}

	private int menuHeight() {
		return menu.size() * MENU_ROW_HEIGHT + MENU_V_PADDING * 2;
	}

	/** 右键菜单弹出来的动画时长（淡入 + 微微上浮）。 */
	private static final long MENU_APPEAR_NANOS = 140_000_000L;

	/** 菜单是什么时候弹出来的，见 {@link #MENU_APPEAR_NANOS}。 */
	private long menuOpenedNanos;

	/** 画右键菜单，并算出光标停在那一项上。画在最上层：它是临时弹出来的东西。 */
	private void drawMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (menu.isEmpty()) {
			return;
		}
		int menuWidth = menuWidth();
		int menuHeight = menuHeight();
		menuHovered = menuIndexAt(mouseX, mouseY);

		// 出现的动画：整块淡入 + 从下往上滑进几个像素。位置不参与动画——否则弹出瞬间点不中
		// （命中判定按最终位置算）
		float appear = Math.clamp((System.nanoTime() - menuOpenedNanos) / (float) MENU_APPEAR_NANOS,
				0.0F, 1.0F);
		float fade = appear * appear * (3.0F - 2.0F * appear);

		// 整个菜单都按内部坐标画，再由矩阵统一缩到 MENU_SCALE——逐处算缩放后的坐标容易漏
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(menuX, menuY);
		pose.scale(MENU_SCALE, MENU_SCALE);
		graphics.fill(0, 0, menuWidth, menuHeight, alphaColor(fade, 0xF0101014));
		graphics.fill(0, 0, menuWidth, 1, alphaColor(fade, 0x60FFFFFF));
		graphics.fill(0, menuHeight - 1, menuWidth, menuHeight, alphaColor(fade, 0x60FFFFFF));
		for (int i = 0; i < menu.size(); i++) {
			int rowY = MENU_V_PADDING + i * MENU_ROW_HEIGHT;
			if (i == menuHovered) {
				// 高亮范围与菜单底板用完全相同的左右边界（内缩 1 像素再乘 MENU_SCALE 会看着没对齐）
				graphics.fill(0, rowY, menuWidth, rowY + MENU_ROW_HEIGHT,
						alphaColor(fade, 0x40FFFFFF));
			}
			// 行高 11、字高 9 → 上下各 1 像素，文字正好落在高亮行块的正中（见 MENU_ROW_HEIGHT）
			graphics.text(font, menu.get(i).label(), MENU_PADDING * 2,
					rowY + (MENU_ROW_HEIGHT - font.lineHeight + 1) / 2,
					alphaColor(fade, i == menuHovered ? 0xFFFFFFFF : 0xFFDDDDDD), false);
		}
		pose.popMatrix();
	}

	/** 光标停在菜单的第几项上；-1 = 不在菜单里。坐标要先换算回菜单的内部单位。 */
	private int menuIndexAt(double mouseX, double mouseY) {
		if (menu.isEmpty()) {
			return -1;
		}
		double localX = (mouseX - menuX) / MENU_SCALE;
		double localY = (mouseY - menuY) / MENU_SCALE;
		if (localX < 0 || localX >= menuWidth()
				|| localY < MENU_V_PADDING || localY >= menuHeight() - MENU_V_PADDING) {
			return -1;
		}
		int index = (int) ((localY - MENU_V_PADDING) / MENU_ROW_HEIGHT);
		return index >= 0 && index < menu.size() ? index : -1;
	}

	/** 打开"新建 / 编辑路径点"界面。 */
	private void openEditor(Waypoint editing, WorldPos createdPos) {
		Minecraft.getInstance().setScreen(new WaypointEditScreen(this, editing, createdPos));
	}

	private void updateZoom(double deltaMillis) {
		if (autoZoom) {
			zoom.setTarget(defaultZoom());
		}
		// 单帧步长由 ZoomAnimator 封顶：长帧只会让动画慢一点，不会一步跳到目标值
		boolean animating = zoom.update(deltaMillis);
		if (zoomAnimating && !animating) {
			// 过场结束：把时间常数换回跟手的那个，之后滚轮/± 的缩放仍是快的
			zoom.setSlow(false);
			settleZoom();
		}
		zoomAnimating = animating;
	}

	/**
	 * 打开地图时的默认缩放：<b>一个 GUI 像素 {@value} 个方块</b>。不用"一个方块一个 GUI 像素"，
	 * 那开局太近、看到的范围太小。
	 */
	private static final double DEFAULT_BLOCKS_PER_GUI_PIXEL = 2.0;

	/**
	 * 自动缩放的目标值：见 {@link #DEFAULT_BLOCKS_PER_GUI_PIXEL}。
	 *
	 * <p>再夹一条<b>不小于 1 个方块每设备像素</b>：地图贴图本身就是一个方块一个像素，缩放比 1:1 再近
	 * 就只是把贴图放大——画面会变成一块块色块。夹住之后打开地图永远是
	 * 贴图的原始清晰度；真想再放大看细节，手动缩放仍然可以（那是用户自己的选择）。
	 */
	private double defaultZoom() {
		return Math.max(1.0, DEFAULT_BLOCKS_PER_GUI_PIXEL / guiScale());
	}

	/**
	 * 当前屏幕尺寸下允许的最大"每设备像素方块数"。
	 *
	 * <p>只按 {@link #MAX_BLOCKS_PER_DEVICE_PIXEL} 限制不够：高 GUI 缩放下设备像素本身就有几千，
	 * 一屏上百页会让填充与显存失控。这里再按"视野内最多 {@value #MAX_VISIBLE_BLOCKS} 方块"反推一个更紧的
	 * 上限，同时保证可见页数不超过 {@code WorldTilePages} 的容量。
	 */
	private double maxVisibleZoom() {
		int device = Math.max(1, Math.max(deviceWidth(), deviceHeight()));
		double limit = Math.min(MAX_BLOCKS_PER_DEVICE_PIXEL, MAX_VISIBLE_BLOCKS / (double) device);
		return Math.max(MIN_ZOOM, limit);
	}

	/**
	 * 把"每个方块占几个设备像素"吸附到整数倍率：只有整数倍率下，一个方块才正好落在整数个设备像素上、
	 * 取样点落在像素中心，画面才锐。放大一侧因此是 1、2、3…{@value #SNAP_LIMIT} 个设备像素一个方块。
	 *
	 * <p><b>超过 {@value #SNAP_LIMIT} 后改为按倍率走、不再吸附</b>：档位阶梯等差的，倍率越高每档相对变化
	 * 越小（1→2 翻倍、20→21 只有 5%），两头"手感速度"能差几倍；高倍率下一个方块已占十几个像素，
	 * 半个像素的偏差看不出来，用等比倍率换均匀手感更划算。
	 *
	 * @param magnify 是否在往放大走（放大时向上取整、缩小时向下取整，保证每次真的换了一档，否则卡住不动）
	 */
	private static double snapPixelsPerBlock(double requested, boolean magnify) {
		double pixelsPerBlock = 1.0 / requested;
		if (pixelsPerBlock < 1.0 || pixelsPerBlock > SNAP_LIMIT) {
			return requested;
		}
		double snapped = magnify
				? Math.ceil(pixelsPerBlock - 1.0e-9)
				: Math.floor(pixelsPerBlock + 1.0e-9);
		double snappedRequested = 1.0 / Math.max(1.0, snapped);
		// 吸附不能比请求的那一步还大：请求 1.18 像素/方块时向上取整吸到 2 等于一次放大两倍（滚一格跳一大截），
		// 而 1~2 来回走时又会一步都吸不动。所以只在吸附后的落点仍在 ZOOM_STEP 之内时才吸，超了就按请求值走。
		double ratio = snappedRequested / requested;
		if (ratio > ZOOM_STEP || ratio < 1.0 / ZOOM_STEP) {
			return requested;
		}
		return snappedRequested;
	}

	/**
	 * 吸附到整数倍率的范围上限（每方块多少个设备像素）；超过就按倍率走，见 {@link #snapPixelsPerBlock}。
	 *
	 * <p>只留 1~2：档位阶梯等差的（1→2 翻倍），范围一大就会和等比倍率混出"忽快忽慢"的手感。
	 * 1~2 这一段挡的是"一个方块只有一两像素"时最扎眼的抖动，其余全程按等比走，速度均匀。
	 */
	private static final double SNAP_LIMIT = 2.0;

	@Override
	public void removed() {
		super.removed();
		// 瓦片跨界面复用，这里不能释放（否则每次打开地图都要重新采一遍）。
		// 按住方向键时被关掉界面不会收到松键事件：这里清掉，否则下次打开会"一进来就往一个方向飘"
		panKeys[PAN_UP] = false;
		panKeys[PAN_DOWN] = false;
		panKeys[PAN_LEFT] = false;
		panKeys[PAN_RIGHT] = false;
		lastPanNanos = 0L;
		// 键盘缩放同理：被关掉界面时收不到松键事件，不清掉下次进来会一直缩
		zoomKeyHold = 0;
		zoomKeyStartNanos = 0L;
		// 跳转输入框是挂在界面上的控件，这里撤掉它（面板的搜索框由 init 按面板状态重建）
		closeJump();
		// 关掉地图就回到"跟着玩家所在的维度"；不还原的话，小地图也会去读那个维度的数据
		colorProvider.setViewDimension(null);
		closePopups();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// 菜单弹着的时候它先吃掉点击：点中某一项就执行，点别处就关掉菜单（不再落到地图上）
		if (!menu.isEmpty()) {
			int index = menuIndexAt(event.x(), event.y());
			MenuItem item = index >= 0 ? menu.get(index) : null;
			closeMenu();
			// 吃过的那一下也要把拖动基准对齐到当前光标，否则下一次左键拖动会拿很早以前的坐标当起点，
			// 算出巨大位移——表现就是"关掉菜单后地图猛地飘回去"
			lastCursorGuiX = event.x();
			lastCursorGuiY = event.y();
			if (item != null) {
				item.action().run();
			}
			return true;
		}
		if (event.button() == 1) {
			// 右键也要对齐拖动基准，理由同上
			lastCursorGuiX = event.x();
			lastCursorGuiY = event.y();
			// 右键时把维度 / 层级浮层收掉：菜单要弹在这块地方，留着会被压住
			closePopups();
			// 右键落在左侧列表某一行：等同"在地图上右键那条路径点"，弹出同一套菜单
			int row = panelOpen && panelContains(event.x(), event.y())
					? panelRowAt(event.y()) : -1;
			if (row >= 0) {
				openMenu(event.x(), event.y(), panelRows.get(row));
			} else {
				openMenu(event.x(), event.y());
			}
			return true;
		}
		if (event.button() == 0) {
			// 记下按下的位置，作为第一段位移的基准
			lastCursorGuiX = event.x();
			lastCursorGuiY = event.y();
			// 这一下按在浮层（路径点面板 / 跳转框 / 维度层级浮层）上：整段拖动都归浮层，地图不许跟着动
			overlayDragCaptured = overlayContains(event.x(), event.y());
			// 新的一段拖动：清掉上一段留下的速度与采样窗口，免得一松手就自己滑
			inertiaX = 0.0;
			inertiaZ = 0.0;
			flushDragVelocity();
		}
		if (hoveredButton >= 0) {
			// 悬停状态每帧按鼠标位置算好了（见 drawUi），这里直接用，不必再看事件坐标
			pressedButton = hoveredButton;
			holdStartNanos = 0L;
			activate(hoveredButton);
			return true;
		}
		// 维度 / 层级浮层：它盖在地图之上，且比按钮更靠里，所以排在按钮之后处理
		if (event.button() == 0 && handlePopupClick(event)) {
			return true;
		}
		// 路径点面板与跳转输入浮在地图之上：落在它们身上的点击先由它们处理
		if (handleOverlayClick(event, doubleClick)) {
			return true;
		}
		// 点到路径点上就选中它（与右键菜单里的"选中"是同一条状态）
		Waypoint hit = waypointAt(event.x(), event.y());
		if (hit != null) {
			WaypointManager.instance().select(hit.id());
			if (doubleClick) {
				openEditor(hit, null);
			}
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	/**
	 * 按住 ± 不放时连续缩放：<b>按下即开始、按时间比例推进</b>（见 {@link #HOLD_STEP_NANOS}），没有起手
	 * 延迟也不按固定节拍跳。按住越久走过格数越多；一帧最多推进两格的时间，绝不把攒下的几步一口气放出来。
	 */
	private void tickButtonHold(long now) {
		if (pressedButton != BUTTON_ZOOM_IN && pressedButton != BUTTON_ZOOM_OUT) {
			holdStartNanos = 0L;
			return;
		}
		if (holdStartNanos == 0L) {
			// 刚按下：即时那一格已经在 mouseClicked 里做过了，这里记下基准目标并起个头，
			// 下一帧开始按"按住多久"直接算目标值（见 tickZoomKeys 的说明）
			holdStartNanos = now;
			holdBaseTarget = zoom.target();
			return;
		}
		long elapsed = now - holdStartNanos;
		if (elapsed <= 0L) {
			return;
		}
		double perStep = pressedButton == BUTTON_ZOOM_IN ? 1.0 / HOLD_ZOOM_STEP : HOLD_ZOOM_STEP;
		double steps = elapsed / (double) HOLD_STEP_NANOS;
		stepZoomTo(holdBaseTarget * Math.pow(perStep, steps), width / 2.0, height / 2.0, false);
	}

	/**
	 * 拖动地图。
	 *
	 * <p>位移用 {@code event.x()} 自己的记录算，不用原版给的 {@code dragX}："无限拖动"会把光标折返到
	 * 屏幕另一侧，那一下的坐标差横跨整块屏幕，原版会算成一次巨大位移，且送达时机不确定。
	 *
	 * <p>折返后的 {@value #WRAP_GUARD_FRAMES} 帧里，只丢掉"真的跨了大半块屏幕"的位移；其余照跟
	 * （对普通拖动也限制的话，快速拖动会被误判丢掉，手感发飘）。
	 */
	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (!menu.isEmpty()) {
			// 菜单弹着时只有左键拖动才关掉它：右键拖动不能关——按右键手总会抖一下，一抖就把刚弹出的
			// 菜单关掉，看起来正是"点了右键没反应"
			if (event.button() == 0) {
				closeMenu();
			}
			return true;
		}
		if (event.button() != 0) {
			return super.mouseDragged(event, dragX, dragY);
		}
		if (layerDragging) {
			// 层级滑轨是横着拖的：整段拖动只改层级，地图一步都不动
			setLayerFromMouse(event.x());
			lastCursorGuiX = event.x();
			lastCursorGuiY = event.y();
			return true;
		}
		if (pressedButton >= 0) {
			// 这一下按在按钮上，不能同时当成拖动地图：否则点放大/缩小时鼠标稍微一动，地图就跟着挪一段
			lastCursorGuiX = event.x();
			lastCursorGuiY = event.y();
			return true;
		}
		if (overlayDragCaptured) {
			// 这一下按在浮层上：在它上面拖是在面板里划拉，地图不该动。基准照样对齐，免得松手后拿着旧坐标算
			lastCursorGuiX = event.x();
			lastCursorGuiY = event.y();
			return true;
		}
		dragging = true;
		double dx = event.x() - lastCursorGuiX;
		double dy = event.y() - lastCursorGuiY;
		lastCursorGuiX = event.x();
		lastCursorGuiY = event.y();

		if (wrapGuardFrames > 0) {
			wrapGuardFrames--;
			if (Math.abs(dx) > width * 0.5 || Math.abs(dy) > height * 0.5) {
				return true;
			}
		}

		double perGui = blocksPerGui();
		// 拖地图：内容跟着光标走，所以视口中心朝反方向移动
		double moveX = -dx * perGui;
		double moveZ = -dy * perGui;
		panBy(moveX, moveZ);
		rememberDragVelocity(moveX, moveZ);
		wrapCursorIfAtEdge();
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		pressedButton = -1;
		holdStartNanos = 0L;
		overlayDragCaptured = false;
		layerDragging = false;
		if (event.button() == 0) {
			dragging = false;
			// 松手时把窗口里还没结算的位移算掉：按住不动再松手时，速度应该接近 0，
			// 否则会莫名其妙地滑出去一段
			flushDragVelocity();
		}
		return super.mouseReleased(event);
	}

	/**
	 * 用一段<b>时间窗口</b>内的总位移估算速度，作为松手后惯性滑行的初速度。
	 *
	 * <p>逐事件算速度会抖（鼠标上报能密到 1 ms 一次，分母一小速度就爆表、松手后直接飘走）；
	 * 攒够 {@value #DRAG_SAMPLE_NANOS} 纳秒再算一次速度自然平滑，按住不动时窗口位移为 0、松手不滑。
	 */
	private void rememberDragVelocity(double moveX, double moveZ) {
		long now = System.nanoTime();
		if (dragSampleNanos == 0L) {
			dragSampleNanos = now;
			return;
		}
		dragSampleX += moveX;
		dragSampleZ += moveZ;
		if (now - dragSampleNanos < DRAG_SAMPLE_NANOS) {
			return;
		}
		settleDragVelocity(now);
	}

	/** 把窗口里攒的位移结算成速度并清空窗口。 */
	private void settleDragVelocity(long nowNanos) {
		double seconds = (nowNanos - dragSampleNanos) / 1.0e9;
		if (seconds > 1.0e-3) {
			inertiaX = dragSampleX / seconds;
			inertiaZ = dragSampleZ / seconds;
		}
		dragSampleNanos = nowNanos;
		dragSampleX = 0.0;
		dragSampleZ = 0.0;
	}

	/** 结算并结束这一段拖动。 */
	private void flushDragVelocity() {
		if (dragSampleNanos != 0L) {
			settleDragVelocity(System.nanoTime());
		}
		dragSampleNanos = 0L;
		dragSampleX = 0.0;
		dragSampleZ = 0.0;
	}

	/**
	 * 松手后继续滑行并逐渐减速。
	 *
	 * <p><b>减速曲线是纯指数</b>（{@link #INERTIA_DECAY_PER_SECOND}）：松手那一下与拖动速度完全接得上、
	 * 越滑越慢、收尾渐近。因此不用速度阈值硬切，只按屏幕速度判断"已经看不出在动"
	 * （见 {@link #INERTIA_MIN_PIXELS_PER_SECOND}），短距离的轻甩才留得住那一段滑行。
	 */
	private void applyInertia(double deltaMillis) {
		if (dragging) {
			return;
		}
		// 停不停的判据按<b>屏幕</b>速度算（见 INERTIA_MIN_PIXELS_PER_SECOND）：同一个方块速度在
		// 缩得近与缩得远时差着几十倍，用方块当判据必然一头"刹得太早"、另一头"白等"
		double perGui = blocksPerGui();
		double screenSpeed = Math.hypot(inertiaX, inertiaZ) / Math.max(1.0e-6, perGui);
		if (screenSpeed < INERTIA_MIN_PIXELS_PER_SECOND) {
			inertiaX = 0.0;
			inertiaZ = 0.0;
			return;
		}
		// 卡帧时不要一步滑出去很远
		double seconds = Math.clamp(deltaMillis, 0.0, 100.0) / 1000.0;
		panBy(inertiaX * seconds, inertiaZ * seconds);
		double decay = Math.pow(INERTIA_DECAY_PER_SECOND, seconds);
		inertiaX *= decay;
		inertiaZ *= decay;
	}

	/** 显示器工作区缓存（见 {@link #wrapCursorIfAtEdge}）：值 + 上次查询时刻。 */
	private final int[] monitorXBuffer = new int[1];
	private final int[] monitorYBuffer = new int[1];
	private final int[] monitorWidthBuffer = new int[1];
	private final int[] monitorHeightBuffer = new int[1];
	private int monitorLeft;
	private int monitorTop;
	private int monitorWidth;
	private int monitorHeight;
	private long monitorCacheNanos;

	/** 显示器信息隔多久重问一次。窗口移动/改大小都是人操作，半秒的滞后看不出来。 */
	private static final long MONITOR_CACHE_NANOS = 500_000_000L;

	/**
	 * 拖到<b>显示器</b>边缘时把光标折返到对侧，于是可以一直朝同一方向拖下去。
	 *
	 * <p>边界取显示器而不是窗口：窗口模式下光标可以拖出窗口，能一直拖到屏幕边再折返。光标坐标相对
	 * 窗口内容区，所以要把显示器边界减去窗口位置换算到同一坐标系。
	 *
	 * <p>那几个 {@code glfwGet*} 都是原生调用（X11/Wayland 上还会同步），逐帧问纯属浪费；按
	 * {@value #MONITOR_CACHE_NANOS} 纳秒刷新一次、中间用缓存，窗口位置改问 Minecraft 自己缓存的
	 * {@code Window#getX/getY}，参数数组也复用。
	 */
	private void wrapCursorIfAtEdge() {
		Minecraft minecraft = Minecraft.getInstance();
		Window window = minecraft.getWindow();
		MouseHandler mouse = minecraft.mouseHandler;

		long now = System.nanoTime();
		if (monitorCacheNanos == 0L || now - monitorCacheNanos >= MONITOR_CACHE_NANOS) {
			monitorCacheNanos = now;
			long monitor = GLFW.glfwGetWindowMonitor(window.handle());
			if (monitor == 0L) {
				monitor = GLFW.glfwGetPrimaryMonitor();
			}
			GLFW.glfwGetMonitorWorkarea(monitor, monitorXBuffer, monitorYBuffer, monitorWidthBuffer, monitorHeightBuffer);
			monitorLeft = monitorXBuffer[0];
			monitorTop = monitorYBuffer[0];
			monitorWidth = monitorWidthBuffer[0];
			monitorHeight = monitorHeightBuffer[0];
		}

		double left = monitorLeft - window.getX();
		double top = monitorTop - window.getY();
		double right = left + monitorWidth;
		double bottom = top + monitorHeight;
		if (right - left <= 2.0 * EDGE_WRAP_MARGIN || bottom - top <= 2.0 * EDGE_WRAP_MARGIN) {
			return;
		}
		double x = mouse.xpos();
		double y = mouse.ypos();

		double wrappedX = x <= left + EDGE_WRAP_MARGIN ? right - EDGE_WRAP_MARGIN
				: x >= right - EDGE_WRAP_MARGIN ? left + EDGE_WRAP_MARGIN : x;
		double wrappedY = y <= top + EDGE_WRAP_MARGIN ? bottom - EDGE_WRAP_MARGIN
				: y >= bottom - EDGE_WRAP_MARGIN ? top + EDGE_WRAP_MARGIN : y;
		if (wrappedX == x && wrappedY == y) {
			return;
		}
		GLFW.glfwSetCursorPos(window.handle(), wrappedX, wrappedY);
		wrapGuardFrames = WRAP_GUARD_FRAMES;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		if (panelOpen && panelContains(mouseX, mouseY)) {
			// 面板里滚轮 = 滚这份列表（上下限见 panelMaxScroll），而不是缩放地图
			panelScroll = (int) Math.clamp(panelScroll - vertical * PANEL_ROW_HEIGHT, 0, panelMaxScroll());
			return true;
		}
		if (!menu.isEmpty()) {
			// 菜单弹着时滚轮不该缩放地图（那会让菜单悬在一个已经挪走的视图上）
			closeMenu();
			return true;
		}
		if (vertical == 0.0) {
			return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
		}
		zoomAt(mouseX, mouseY, vertical);
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (!menu.isEmpty() && event.isEscape()) {
			// 菜单弹着时，ESC 先关菜单而不是关界面
			closeMenu();
			return true;
		}
		if (event.isEscape() && (panelOpen || jumpOpen || dimensionOpen || layerOpen)) {
			// 面板 / 跳转框 / 维度层级浮层开着时，ESC 先收起它们而不是把整张地图关掉
			closeInputs();
			return true;
		}
		SinanTextField focused = focusedField();
		if (focused != null) {
			// 有输入框拿着焦点：按键只进输入框，不透传给地图
			return consumeInputKey(event, focused);
		}
		// 只用方向键：WASD 会和"边走边看地图"的习惯打架（想挪地图却在走路），
		// 而方向键在界面里本来就没有别的用途。按住即连续平移，见 tickPanKeys
		return switch (event.input()) {
			case GLFW.GLFW_KEY_UP -> pressPanKey(PAN_UP);
			case GLFW.GLFW_KEY_DOWN -> pressPanKey(PAN_DOWN);
			case GLFW.GLFW_KEY_LEFT -> pressPanKey(PAN_LEFT);
			case GLFW.GLFW_KEY_RIGHT -> pressPanKey(PAN_RIGHT);
			// - / + 直接改缩放，以屏幕中心为锚点（与右上那对 ± 按钮走同一条路）。
			// 这是界面内的固定按键、不进按键绑定。按住**不靠 GLFW 的重发**（它会先等一段才开始
			// 重复，观感是"先愣一下、再一顿一顿"），而是记下按住状态由每帧推进，见 tickZoomKeys
			case GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT -> pressZoomKey(false);
			case GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD -> pressZoomKey(true);
			// ESC 等按键交给原版处理（默认会关闭界面）
			default -> super.keyPressed(event);
		};
	}

	/** 以屏幕中心为锚点缩放一档（右上 ± 按钮与键盘 - / + 共用）。 */
	private boolean zoomStepAtScreenCenter(double factor) {
		stepZoomAt(width / 2.0, height / 2.0, factor);
		return true;
	}

	/** 按下 - / +：先走一格（与点按钮一致），再进入"每帧按时间比例推进"的连续缩放。 */
	private boolean pressZoomKey(boolean zoomIn) {
		int dir = zoomIn ? 1 : 2;
		// GLFW 按住时会自动重复发"按下"事件（起手延迟约 500ms、之后每 ~30ms 一次）。
		// 已经按着同向的键就不重复走"再跳一格 + 重置基准"——那会让目标值在重复事件上
		// 每一下多跳一格、把匀速的连续推进搅成"突然加速"（这正是"先慢慢、后突然变快"的来源）。
		if (zoomKeyHold == dir) {
			return true;
		}
		zoomKeyHold = dir;
		zoomKeyStartNanos = System.nanoTime();
		boolean acted = zoomStepAtScreenCenter(zoomIn ? 1.0 / ZOOM_STEP : ZOOM_STEP);
		// 基准取"按下那一格之后"的目标值：否则连续推进算出的绝对值会把刚走的那一格抹掉
		zoomKeyBaseTarget = zoom.target();
		return acted;
	}

	/**
	 * 每帧推进键盘 - / + 的连续缩放。
	 *
	 * <p><b>不靠 GLFW 的"按住重复"</b>：它有一段起手延迟（默认约 500ms），观感就是
	 * "按下去先一顿、之后才开始连续"。
	 *
	 * <p><b>目标是"按住多久"直接算出来的绝对值</b>，不是每帧在上一帧的基础上再乘一次：
	 * 逐帧相乘时，吸附（{@link #snapPixelsPerBlock}）会把倍率按在整数档上不动、攒够了再猛跳，
	 * 看起来就是"前面慢、后面突然加速"。现在每秒固定走 {@code 1000 / }{@value #HOLD_STEP_NANOS}
	 * 格，且连续缩放不吸附，速率自始至终一样。
	 */
	private void tickZoomKeys(long now) {
		if (zoomKeyHold == 0) {
			return;
		}
		double perStep = zoomKeyHold == 1 ? 1.0 / HOLD_ZOOM_STEP : HOLD_ZOOM_STEP;
		double steps = (now - zoomKeyStartNanos) / (double) HOLD_STEP_NANOS;
		stepZoomTo(zoomKeyBaseTarget * Math.pow(perStep, steps), width / 2.0, height / 2.0, false);
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		switch (event.input()) {
			case GLFW.GLFW_KEY_UP -> panKeys[PAN_UP] = false;
			case GLFW.GLFW_KEY_DOWN -> panKeys[PAN_DOWN] = false;
			case GLFW.GLFW_KEY_LEFT -> panKeys[PAN_LEFT] = false;
			case GLFW.GLFW_KEY_RIGHT -> panKeys[PAN_RIGHT] = false;
			// 松开 - / +：连续缩放立刻停
			case GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT,
					GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD -> zoomKeyHold = 0;
			default -> {
				return super.keyReleased(event);
			}
		}
		return true;
	}

	/**
	 * 按下方向键：记下"还按着"，并从<b>0 速度</b>开始缓入（见 {@link #PAN_RAMP_NANOS}）。
	 *
	 * <p><b>这里一步都不走</b>：以前按下就先预走一小段"轻点反馈"，刚按下的那一瞬间会窜一下；现在只起个
	 * 计时，走多少交给 {@link #tickPanKeys} 按按住时间算。
	 *
	 * <p>GLFW 对按住的键会自动重复发按下事件，靠 {@link #panKeys} 去重：重复事件只是"还按着"，
	 * <b>不能重置计时器</b>，否则按住时会卡在原地不走。
	 */
	private boolean pressPanKey(int key) {
		if (panKeys[key]) {
			return true;
		}
		boolean idle = !panKeys[PAN_UP] && !panKeys[PAN_DOWN] && !panKeys[PAN_LEFT] && !panKeys[PAN_RIGHT];
		panKeys[key] = true;
		long now = System.nanoTime();
		lastPanNanos = now;
		if (idle) {
			// 这一轮刚起步：从 0 速度缓入。已经按着别的方向键时接着原来的计时（换个方向不该重新起步）
			panPressNanos = now;
		}
		return true;
	}

	/**
	 * 每帧推进方向键的平移。
	 *
	 * <p><b>帧率无关</b>：走多远只由两次调用之间的 nanoTime 差值决定；<b>起步平滑</b>：速度 =
	 * 目标速度 × {@code clamp(按住时间 / PAN_RAMP_NANOS, 0, 1)} 的平方（平方导数在起点为 0，真的从 0 起步）。
	 * 水平与竖直各按自己那一侧的屏幕尺寸折算，两个方向的"每秒几个屏幕"是同一个数。单帧最多推进
	 * {@value #PAN_MAX_FRAME_NANOS} 纳秒的量；松键后立即停。
	 */
	private void tickPanKeys(long now) {
		if (!Minecraft.getInstance().getWindow().isFocused()) {
			// 窗口失去焦点（Alt+Tab 之类）之后收不到松键事件：这里把状态清掉，
			// 否则切回来会发现地图一直朝一个方向飘
			panKeys[PAN_UP] = false;
			panKeys[PAN_DOWN] = false;
			panKeys[PAN_LEFT] = false;
			panKeys[PAN_RIGHT] = false;
		}
		int dirX = (panKeys[PAN_RIGHT] ? 1 : 0) - (panKeys[PAN_LEFT] ? 1 : 0);
		int dirZ = (panKeys[PAN_DOWN] ? 1 : 0) - (panKeys[PAN_UP] ? 1 : 0);
		if (dirX == 0 && dirZ == 0) {
			lastPanNanos = 0L;
			panPressNanos = 0L;
			return;
		}
		if (lastPanNanos == 0L) {
			lastPanNanos = now;
			return;
		}
		long elapsed = now - lastPanNanos;
		if (elapsed <= 0L) {
			return;
		}
		lastPanNanos = now;
		double seconds = Math.min(elapsed, PAN_MAX_FRAME_NANOS) / 1.0e9;
		double ramp = Math.clamp((now - panPressNanos) / (double) PAN_RAMP_NANOS, 0.0, 1.0);
		double speed = PAN_SCREENS_PER_SECOND * ramp * ramp;
		double perGui = blocksPerGui();
		panBy(dirX * seconds * speed * width * perGui,
				dirZ * seconds * speed * height * perGui);
	}

	/**
	 * 输入框拿着焦点时的按键处理：先把按键交给输入框自己（移动光标、退格、粘贴），<b>无论它吃没吃下都
	 * 不再往下传</b>（否则方向键会一边挪光标一边把地图挪走）。回车只在跳转框/层级框上触发提交；
	 * ESC 与浮层收起在上面已经处理掉了。
	 */
	private boolean consumeInputKey(KeyEvent event, SinanTextField field) {
		if ((event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)
				&& field == jumpBox) {
			commitJump();
			return true;
		}
		if ((event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)
				&& field == layerBox) {
			// 层级输入框：回车就当"确定"，把这一层应用到地图上
			applyLayerInput();
			blurFields();
			return true;
		}
		field.keyPressed(event);
		return true;
	}

	/** 面板搜索 / 坐标跳转 / 维度搜索 / 层级输入里正在拿焦点的那一个；都没聚焦时返回 {@code null}。 */
	private SinanTextField focusedField() {
		if (searchBox != null && searchBox.isFocused()) {
			return searchBox;
		}
		if (jumpBox != null && jumpBox.isFocused()) {
			return jumpBox;
		}
		if (dimensionSearchBox != null && dimensionSearchBox.isFocused()) {
			return dimensionSearchBox;
		}
		if (layerBox != null && layerBox.isFocused()) {
			return layerBox;
		}
		return null;
	}

	/**
	 * 字符输入：普通字符与中文输入法提交上来的字符都从这里进框。聚焦时一律吃掉、不透传给地图。
	 */
	@Override
	public boolean charTyped(CharacterEvent event) {
		SinanTextField field = focusedField();
		if (field != null) {
			field.charTyped(event);
			return true;
		}
		return super.charTyped(event);
	}

	/** 收起输入这一层：一下收一层（先维度/层级浮层，再跳转框，最后路径点面板）。 */
	private void closeInputs() {
		if (dimensionOpen || layerOpen) {
			closePopups();
			return;
		}
		if (jumpOpen) {
			closeJump();
			return;
		}
		if (panelOpen) {
			closePanel();
		}
	}

	/** 平移视口。不做任何地形重算：瓦片按世界坐标摆好，视图一动自然贴到新位置。 */
	private void panBy(double dxBlocks, double dzBlocks) {
		// 一动手就说明接管了视图：把定位过场停掉，否则两个位移会叠在一起
		centerAnimActive = false;
		// 缩放动画还在跑时画面按"光标下的锚点"摆；一动手就要先把视口中心挪到屏幕中心当前对应的世界坐标，
		// 否则绘制基准从锚点切成中心的那一下画面会整体跳一格——表现就是"缩放中一拖动、松手后弹回去"
		reanchorViewCenter();
		viewCenterOn(viewCenterX() + dxBlocks, viewCenterZ() + dzBlocks);
	}

	/**
	 * 把锚点交给视口中心，且<b>不改变当前屏幕上的画面</b>：反推让"以视口中心为原点"和"以锚点为原点"
	 * 的换算落点一致。缩放动画结束时（{@link #settleZoom()}）和动画中途开始拖动时都用它。
	 */
	private void reanchorViewCenter() {
		if (!anchoring) {
			return;
		}
		double perGui = blocksPerGui();
		viewCenterOn(
				originWorldX() + (width / 2.0 - originGuiX()) * perGui,
				originWorldZ() + (height / 2.0 - originGuiZ()) * perGui);
		anchoring = false;
	}

	/** 滚轮缩放。只记录锚点并设定目标值：动画由变换呈现，不重新采样地形。 */
	private void zoomAt(double guiX, double guiY, double vertical) {
		// 往上滚是放大：倍率直接乘在"每设备像素多少方块"上，所以放大要乘小于 1 的
		stepZoomAt(guiX, guiY, vertical > 0.0 ? 1.0 / ZOOM_STEP : ZOOM_STEP);
	}

	/**
	 * 按倍率缩放，并把 {@code (guiX, guiY)} 下的世界坐标钉住不动。
	 *
	 * <p>{@code factor} 直接乘在"每设备像素多少方块"上：<b>&lt;1 是放大</b>、&gt;1 是缩小。
	 * 滚轮一格 {@value #ZOOM_STEP}，按住 ± 每次 {@value #HOLD_ZOOM_STEP}。
	 */
	private void stepZoomAt(double guiX, double guiY, double factor) {
		stepZoomTo(zoom.target() * factor, guiX, guiY, true);
	}

	/**
	 * 把缩放目标设成一个<b>绝对值</b>，并把 {@code (guiX, guiY)} 下的世界坐标钉住不动。
	 *
	 * @param snap 是否把倍率吸附到"整数设备像素/方块"（见 {@link #snapPixelsPerBlock}）。连续按住缩放时传
	 *             {@code false}：那一段吸附会让倍率卡在同一档再猛地跳过去，观感正是"前面不动、后面加速"
	 */
	private void stepZoomTo(double requested, double guiX, double guiY, boolean snap) {
		double current = zoom.target();
		double next = Math.clamp(snap ? snapPixelsPerBlock(requested, requested < current) : requested,
				MIN_ZOOM, maxVisibleZoom());
		boolean wasAuto = autoZoom;
		if (next == current && !wasAuto) {
			return;
		}

		autoZoom = false;
		zoom.setTarget(next);
		// 开场过场没走完就滚了滚轮：立刻换回跟手的时间常数，否则慢速率套在手动的缩放上，滚一格要等半天
		if (zoomAnimating) {
			zoomAnimating = false;
			zoom.setSlow(false);
		}
		// 手动缩放即接管视图：把定位过场停掉，免得一边放大一边还在往玩家身上挪
		centerAnimActive = false;
		// 缩放打断惯性：滑行与缩放两个位移叠在一起，手感和"缩放结束后回弹"都很难看
		inertiaX = 0.0;
		inertiaZ = 0.0;
		flushDragVelocity();

		BlockPoint anchor = worldAtGui(guiX, guiY);
		anchorWorldX = anchor.x();
		anchorWorldZ = anchor.z();
		anchorGuiX = guiX;
		anchorGuiY = guiY;
		anchoring = true;

		if (wasAuto) {
			// 只在"自动→手动"这一下记一行：按住 ± 时每秒会触发十几次，逐次记就把日志淹了
			LOGGER.info("世界地图缩放 | 自动 → 手动（每设备像素 {} 方块）", "%.3f".formatted(next));
		}
	}

	/**
	 * 切换层级的过场。<b>方向跟着层级走</b>：往深处"推进"（先缩小视野再放大回来）、往浅处"退出"，
	 * 于是层与层之间是一次有方向感的缩放，而不是硬切。
	 */
	private void startLayerTransition(int fromLayer, int toLayer) {
		boolean deeper = LayerSelector.isDeeper(fromLayer, toLayer);
		double factor = deeper ? LAYER_PULSE : 1.0 / LAYER_PULSE;
		double pulsed = Math.clamp(zoom.target() * factor, MIN_ZOOM, maxVisibleZoom());
		// 只把当前值弹出去，目标值仍是原来那档：动画会自己把它推回来
		zoom.snapCurrent(pulsed);
	}

	/** 缩放动画结束时把视图中心落到锚点：光标下的世界坐标保持不动。 */
	private void settleZoom() {
		reanchorViewCenter();
	}

	/**
	 * 换世界就把瓦片清掉，免得画出一堆别的存档的地形。"上次是哪个世界"记在 {@link WorldTilePages} 里：
	 * 瓦片跨界面复用，界面每次打开都是新实例，记在界面里等于每次打开都清空重填。
	 */
	private void ensureLevel() {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == lastLevel) {
			return;
		}
		boolean switched = lastLevel != null;
		lastLevel = level;
		tiles.ensureLevel(level);
		// 页仓库还要跟着"哪个存档"走：换了存档必须整仓库丢掉，否则画出来的还是上个存档的地形。
		// 用数据目录判定而不是世界名——同名世界会复用同一个存档目录名，光看名字分不出来
		tiles.ensureWorld(MapStorage.currentRoot(Minecraft.getInstance()));
		// 换了世界/存档：上一个世界的"这一片没有数据"结论不再成立，整张作废（见 #coarseNoData）
		coarseNoData.clear();
		seenPages.clear();
		coarseNoDataClearedNanos = 0L;
		fillOrder = null;
		viewMaxX = Integer.MIN_VALUE;
		viewMaxZ = Integer.MIN_VALUE;
		if (switched) {
			lastLayer = Integer.MAX_VALUE;
		}
	}

	/**
	 * 把视野内的页按"玩家在中心、一圈圈向外"的顺序填上，每帧只花几毫秒。这是唯一一处去取地形数据
	 * 的地方；平移、缩放都不碰它，因此那些操作不可能触发"重新加载"。
	 */
	private void fillView(double deltaMillis) {
		long t0 = System.nanoTime();
		// 先按当前缩放选本帧用哪一档贴图（带迟滞，见 updateTerrainTier），后面的填充/绘制都读它
		updateTerrainTier();
		updatePageRange();
		// 视野内的页绝不允许被淘汰，否则正在看的那一页会被挤掉、清空重填（画面一块块撕裂）。每帧都要设：
		// 换层时视野范围可能没变，但该保护的页换成了新层那套。粗页一格覆盖 COARSE_PACK² 张叶页格位，
		// 范围由叶页那套折算而来，所以这一份范围同时保住两者（见 WorldTilePages#isProtected）。
		tiles.setProtectedRange(currentLayer, pageMinX, pageMinZ, pageMaxX, pageMaxZ);
		colorProvider.beginWarm(warmBudgetNanos(deltaMillis));
		long t1 = System.nanoTime();
		// 先补"以前没数据、现在有了"的块（每 8 秒才真跑一次），再把这一屏要填的页铺开
		tiles.retryUnexplored(System.nanoTime());
		long t2 = System.nanoTime();

		// 昼夜不需要在填充里做任何事：瓦片是"白天/夜晚"两张固定贴图，天色只影响绘制时夜晚贴图的 alpha

		// 每帧按"离谁近"重排要填的页，再从最近的一页开始补。中心取"你正在看的那一片"，但玩家就在视口
		// 中心附近时以玩家为中心：
		//  · 一律以视口中心 → 丢掉"人往哪走、哪儿先铺"，行进时希望脚下这片先出来；
		//  · 一律以玩家为中心 → 把地图拖到别处看时，屏幕中央那一片排在几十页之后，看着像"一直不加载"。
		// 折中：玩家离视口中心不超过一页时以玩家为中心，拖远了才改用视口中心。
		int viewCenterPageX = Math.floorDiv((int) Math.floor(viewCenterX()), WorldTilePages.PAGE_BLOCKS);
		int viewCenterPageZ = Math.floorDiv((int) Math.floor(viewCenterZ()), WorldTilePages.PAGE_BLOCKS);
		int playerPageX = playerPageX();
		int playerPageZ = playerPageZ();
		boolean playerNearCenter = Math.abs(playerPageX - viewCenterPageX) <= 1
				&& Math.abs(playerPageZ - viewCenterPageZ) <= 1;
		int centerPageX = playerNearCenter ? playerPageX : viewCenterPageX;
		int centerPageZ = playerNearCenter ? playerPageZ : viewCenterPageZ;
		if (fillOrder == null || centerPageX != fillOrderCenterPageX || centerPageZ != fillOrderCenterPageZ) {
			rebuildFillOrders(centerPageX, centerPageZ);
		}
		long t3 = System.nanoTime();

		// 夜色浓度这一帧只在这里算一次，绘制与诊断都读它（见 nightAlpha）。必须放在粗页分支之前：
		// 否则粗页模式下这个值停在旧值，天色与加载指示都不对。
		int nightAlpha = frameNightAlpha = currentNightAlpha();

		// 这一帧要画白天还是夜晚那张（全黑时白天那张被盖住、白天时夜晚那张 alpha 为 0）。提前算好供叶页与
		// 粗页两路上传用（见 WorldTilePages#upload / #uploadCoarse），无条件都传等于白灌一倍显存带宽。
		boolean wantDay = nightAlpha < 255;
		boolean wantNight = nightAlpha > 0;

		// P0：缩得远时画粗档贴图。coarseOrder 按<b>粗页坐标</b>排（粗档一格 = COARSE_PACK² 张叶页格位），
		// 填充按本帧档位 terrainTier 采样，不靠把叶页缩下来——缩得很小时需要的叶页远超 MAX_PAGES。
		//
		// 一次只喂一张（coarseFedOne）：顺着 coarseOrder 从近到远找第一个"有活可干"的喂它，它没进展
		// （在等数据）就继续找下一张，但有进展就停。这样同一时刻只有一张粗页在长，不会满屏"撕裂"。
		boolean coarse = coarseMode();
		if (coarse) {
			int visited = 0;
			boolean coarseFedOne = false;
			for (long cell : coarseOrder) {
				if (coarseFedOne) {
					break;
				}
				int pageX = (int) (cell >> 32);
				int pageZ = (int) cell;
				// 还没建出来 / 已填完 / 刚扫完还在冷却期：跳过且不占"看"的名额，否则一张永远没数据的粗页
				// 会把名额占死。（粗页由 drawTerrain 按需建出来，这里不再建。）
				if (!tiles.coarsePending(currentLayer, terrainTier, pageX, pageZ)) {
					continue;
				}
				if (visited >= COARSE_VISIT_PER_FRAME) {
					break;
				}
				visited++;
				if (tiles.fillCoarse(currentLayer, terrainTier, pageX, pageZ, COARSE_PIXELS_PER_PAGE)) {
					coarseFedOne = true;
					if (uploadsThisFrame < MAX_UPLOADS_PER_FRAME
							&& tiles.uploadCoarse(currentLayer, terrainTier, pageX, pageZ, wantDay, wantNight)) {
						uploadsThisFrame++;
					}
				}
			}
			// 补传"当前要画、但还没上显存"的那张粗页贴图。
			//
			// 必须有这一段：粗页像素由 fillCoarse 写，而上面那句 uploadCoarse 挂在"这一帧真填进了像素"
			// 里面。粗页一旦填完（remaining 归零）就被 coarsePending 跳过、fillCoarse 不再被调，
			// 挂在它后面的 uploadCoarse 也就永远不再跑（1.5s 重扫冷却里的页同理）。于是白天填完的粗页
			// 只被标脏了 dirtyNight、夜晚贴图停在建页时那次"整张空白"的上传上，天一黑整页就是黑的。
			//
			// 这里按 coarseOrder（离中心由近到远）限量补传。不填充、不建页，"同时只推进一张"的不变量不受影响；
			// 上传额度仍与叶页共用 MAX_UPLOADS_PER_FRAME。
			if (uploadsThisFrame < MAX_UPLOADS_PER_FRAME) {
				for (long cell : coarseOrder) {
					if (uploadsThisFrame >= MAX_UPLOADS_PER_FRAME) {
						break;
					}
					if (tiles.uploadCoarse(currentLayer, terrainTier, (int) (cell >> 32), (int) cell,
							wantDay, wantNight)) {
						uploadsThisFrame++;
					}
				}
			}
		}
		// 不要在这里 return：叶页照旧继续建页、上传（缩回去时要用），下面那段夜色/耗时记账与绘制也要走到。
		// 但粗页模式下不再推进叶页的填充游标——两套填充顺序叠在同一帧会"粗页和叶页到处同时在长"。

		// 从最近的一页开始，把还有活的页依次填满；上传只传当前要画的那张（见 wantDay/wantNight）
		long pageNanos = 0;
		long fillNanos = 0;
		long uploadNanos = 0;
		// 这一帧只让"有进展的那一页"开工：每页都推一点会"到处都在长、又都长不完"，一次推一页则是
		// "从近处一页一页铺开"。但没有进展的页不许挡住后面的页（它可能只是在等数据），否则"去加载
		// 新区块不会更新"。也不要因为预算用尽就跳出整个循环——预算只管建数据，后面的上传还得照做。
		int filledPages = 0;
		// P1：同时在填的页数封顶，太多半成品页会"到处都在长、又都长不完"。已开过工的页照常喂，只对
		// "新开一页"限量；名额只算当前层（换层后旧层在等数据的页会永久占死名额，见 WorldTilePages#fillingCount）。
		//
		// 一帧可让最近几页并行推进：只喂一页时，若那页在等后台读盘就会每帧白转、整屏黑区"很久不加载"。
		// 真正的闸门是每帧暖预算（isWarmSpent），预算耗完所有页当场停住；这里只是让最近
		// {@code 设置里的"每帧并行页数"}（自动 1）页按 fillOrder 顺序并行推进。
		int inFlight = tiles.fillingCount(currentLayer);
		// 三项性能设置在本帧开始时取一次（自动分别是 1 页 / 8 页 / 32 块），循环里别再反复取
		int maxFillingPerFrame = settings.effectiveFillingPagesPerFrame();
		int maxFillingPages = settings.effectiveFillingPages();
		int chunksPerVisit = settings.effectiveChunksPerVisit();
		for (long cell : fillOrder) {
			int pageX = (int) (cell >> 32);
			int pageZ = (int) cell;
			// 最粗一档（{@value WorldTilePages#COARSE_TIER_8} 方块/像素）时叶页整套停用：那时一屏上千个页格位，
			// 叶页（一页 2 MB）纯属负担，它只是给"缩回 4 档"备的降采样来源，而 4 档粗页填充在叶页缺失时会
			// 回落去世界采样（见 CoarseTileCache#fillCoarse），照样出图。停用后少一类每秒在动的东西、
			// 显存也不再供两套。4 档仍照常建叶页（粗像素能从叶页直接降采样，出图最快），缩回 4 档时按需重建。
			if (coarse && terrainTier == WorldTilePages.COARSE_TIER_8) {
				continue;
			}
			if (!mayHaveData(pageX, pageZ) && tiles.find(currentLayer, pageX, pageZ) == null) {
				// 连区域文件都没有、且离玩家很远：这一页永远只有"没有数据"，整页跳过（不建贴图、不填、不画转圈）。
				// 但已经建出来的页绝不能跳过：它可能有像素（小地图画的、之前采过，或本地存档里那些还没写进
				// 世界存档的），少了这半句"刚走过、还没落盘的地方一拖远就被判成没有数据，地图一直不更新"。
				continue;
			}
			long p0 = System.nanoTime();
			TilePage page = tiles.page(currentLayer, pageX, pageZ);
			long p1 = System.nanoTime();
			// 叶页一直喂到 4 档为止（8 档在上面就整页跳过了）：4 档的粗页有相当一部分像素是"从叶页降采样"
			// （纯内存读），而叶页不喂就永远没有降采样来源，只能回世界采样——实测就是这样："降采样=0px、
			// 回落 39 万像素/秒（312ms/秒）"，中间那档因此比设计慢一大截。以前这里按 coarse 整段挡住，是怕
			// 两套推进顺序在同一帧"哪儿都在长"；现在两个档位共用同一套"一列一列扫"，不会再出现两种走向。
			// 【诊断】"暖预算"是每帧全局一份、先到先得（见 ClientChunkColorProvider#beginWarm）：别的界面
			// （小地图 HUD、雷达）先把这一帧的额度花光的话，世界地图这边就一块都问不动——"每秒只推进几十块"
			// 正是它的症状。数一下被它挡住的次数，"很慢"的账才能算清。
			boolean warmSpent = colorProvider.isWarmSpent();
			if (warmSpent) {
				warmSpentSkips++;
			}
			if (terrainTier != WorldTilePages.COARSE_TIER_8 && filledPages < maxFillingPerFrame
					&& !warmSpent && tiles.hasWork(page)
					&& (tiles.started(page) || inFlight < maxFillingPages)) {
				if (tiles.fillSome(page, chunksPerVisit)) {
					filledPages++;
				}
			}
			long p2 = System.nanoTime();
			// 有改动就上传。试过"等这一页数据到齐再整页上屏"，结果整张地图什么都不显示：新世界里多数块
			// 要等后台读盘才有结论，而"问"快过"后台给结论"，页永远到不齐（实测：问=929 画=0 待补还在涨）。
			if (uploadsThisFrame < MAX_UPLOADS_PER_FRAME
					&& tiles.upload(page, wantDay, wantNight)) {
				uploadsThisFrame++;
			}
			long p3 = System.nanoTime();
			pageNanos += p1 - p0;
			fillNanos += p2 - p1;
			uploadNanos += p3 - p2;
		}
		long t4 = System.nanoTime();
		// 填充这一段的耗时拆开记：范围/重试/排页/建页/填/上传。卡在哪一段一眼能看出来
		layoutNanosSum += t1 - t0;
		retryNanosSum += t2 - t1;
		orderNanosSum += t3 - t2;
		pageNanosSum += pageNanos;
		stepNanosSum += fillNanos;
		uploadNanosSum += uploadNanos;
		fillViewNanosSum += t4 - t0;
		// 【诊断计量】fillView 整体的每秒均值/最大值，以及"铺满一屏"的墙钟计时（见 logComposition）
		if (ClientChunkColorProvider.STATS_ENABLED) {
			colorProvider.stats().recordFillView(t4 - t0);
			updateScreenFillStat();
		}
	}

	/** 填充段各小段的每秒累计耗时（纳秒），见 {@link #logComposition}。 */
	private long layoutNanosSum;
	private long retryNanosSum;
	private long orderNanosSum;
	private long pageNanosSum;
	private long stepNanosSum;
	private long uploadNanosSum;
	private long fillViewNanosSum;

	/** 本秒有多少次"页本该被喂、却被每帧那份暖预算挡住"（诊断，见 fillView）。 */
	private int warmSpentSkips;

	/** 【诊断计量】"铺满一屏"正在计时的档位（1/2/4）；0 表示当前没在计时。 */
	private int screenFillTier;
	/** 【诊断计量】本轮"铺满一屏"开始计时的时刻；0 表示不在计时。 */
	private long screenFillStartNanos;
	/** 【诊断计量】最近一次铺满一屏的墙钟秒数（按档分开记）；-1 表示还没有完整样本。 */
	private double screenFillLeafSeconds = -1.0;
	private double screenFill2Seconds = -1.0;
	private double screenFill4Seconds = -1.0;

	/**
	 * 【诊断计量】"粗页首图"正在计时的开始时刻（0 = 不在计时）与计时开始时屏幕中心那一页的坐标。
	 * "填满一屏"量的是<b>全部</b>铺完，看不出"多久才<b>开始</b>出图"，所以单独量：切到某个粗档后，
	 * 屏幕中心那一页第一次有真地形的墙钟。它直接对应建页顺序（由近到远时接近一帧，行优先则要等半屏）。
	 */
	private long coarseFirstStartNanos;
	private int coarseFirstPageX;
	private int coarseFirstPageZ;
	/** 【诊断计量】切到该粗档后中心那页第一次出真地形花掉的墙钟秒数（按档分开记）；-1 表示还没有样本。 */
	private double coarseFirst2Seconds = -1.0;
	private double coarseFirst4Seconds = -1.0;

	/**
	 * 【诊断计量】"从某一档刚进入、视野内页有没铺完的"到"都铺完"的墙钟时长。每帧调一次（只在
	 * STATS_ENABLED 下），用"首次发现未铺完"与"首次发现全铺完"两个边沿夹出一轮；换档则作废重来。
	 * 叶页看"还有活要干"，粗页看"还没建出来或还有没填的像素"。它直接回答"铺满一屏要多久"。
	 */
	private void updateScreenFillStat() {
		int tier = terrainTier;
		long now = System.nanoTime();
		if (tier != screenFillTier) {
			// 换档：当前这一轮跨了档，作废；等新档自己再"发现没铺完"重新开始
			screenFillTier = tier;
			screenFillStartNanos = 0L;
			// 换到粗档时重新给"粗页首图"计时。中心页坐标此刻已是本帧的值（fillView 先跑 updateTerrainTier
			// → rebuildFillOrders，本方法在 fillView 末尾才调），所以量的是"切档后中心那页多久出地形"。
			coarseFirstStartNanos = 0L;
			if (tier > 1) {
				coarseFirstStartNanos = now;
				// 粗页按粗页坐标查（见 WorldTilePages#COARSE_PACK）
				coarseFirstPageX = Math.floorDiv(fillOrderCenterPageX, WorldTilePages.COARSE_PACK);
				coarseFirstPageZ = Math.floorDiv(fillOrderCenterPageZ, WorldTilePages.COARSE_PACK);
			}
		}
		// "粗页首图"：中心那一页第一次画出真地形就记一次然后停——每帧只查一页，O(1)，不进热路径。
		if (coarseFirstStartNanos != 0L
				&& tiles.coarseHasRealTerrain(currentLayer, tier, coarseFirstPageX, coarseFirstPageZ)) {
			double seconds = (now - coarseFirstStartNanos) / 1.0e9;
			if (tier == 2) {
				coarseFirst2Seconds = seconds;
			} else {
				coarseFirst4Seconds = seconds;
			}
			coarseFirstStartNanos = 0L;
		}
		if (!viewHasUnfinishedPages(tier)) {
			if (screenFillStartNanos != 0L) {
				double seconds = (now - screenFillStartNanos) / 1.0e9;
				switch (tier) {
					case 1 -> screenFillLeafSeconds = seconds;
					case 2 -> screenFill2Seconds = seconds;
					default -> screenFill4Seconds = seconds;
				}
			}
			screenFillStartNanos = 0L;
		} else if (screenFillStartNanos == 0L) {
			screenFillStartNanos = now;
		}
	}

	/** 【诊断计量】视野内还有没有没铺完的页，见 {@link #updateScreenFillStat}。 */
	private boolean viewHasUnfinishedPages(int tier) {
		int pack = WorldTilePages.COARSE_PACK;
		for (int pageZ = viewMinZ; pageZ <= viewMaxZ; pageZ++) {
			for (int pageX = viewMinX; pageX <= viewMaxX; pageX++) {
				if (tier > 1) {
					// 粗页按粗页坐标查；同一张粗页会被查进来几次（无害，只是问同一个布尔）
					if (tiles.coarseUnfinished(currentLayer, tier,
							Math.floorDiv(pageX, pack), Math.floorDiv(pageZ, pack))) {
						return true;
					}
				} else {
					TilePage page = tiles.find(currentLayer, pageX, pageZ);
					if (page != null && tiles.hasWork(page)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * 档位阈值：每<b>设备</b>像素多少个方块时跨到下一档。三档是 1 / 4 / 8 方块每像素。
	 *
	 * <p>三档：{@code < 4} → 叶页（1 方块/像素）；{@code 4 ≤ x < 12} → 4 档（4 方块/像素，256×256）；
	 * {@code ≥ 12} → 8 档（8 方块/像素，128×128）。
	 *
	 * <p><b>为什么 8 档阈值取 12</b>：按 8 判时刚缩到"每设备像素 8 方块"就切档，4 档贴图才放大 2 倍就换了。
	 * 取 12 后 4 档用到放大 3 倍——代价是 {@code 8 ≤ x < 12} 这一段略软，收益是换档次数更少（正是要的效果）。
	 * 回切阈值跟着这个常量走（{@code × }{@value #TIER_HYSTERESIS}），见 {@link #updateTerrainTier}。
	 *
	 * <p><b>为什么是"设备像素"而不是"GUI 像素"</b>：档位成立的条件是"贴图一个像素正好落在屏幕一个
	 * 物理像素上"，而 {@link #zoom} 的内部单位本来就是"每设备像素多少方块"。用 {@link #blocksPerGui()}
	 * 判会把阈值放大 {@code guiScale} 倍——高 DPI（guiScale=2）下等于提前一倍切档，即"还没缩多少就变糊"。
	 */
	private static final double TIER4_BLOCKS_PER_DEVICE_PIXEL = 4.0;
	private static final double TIER8_BLOCKS_PER_DEVICE_PIXEL = 12.0;

	/** 档位迟滞：向下切换要越过阈值的这个比例（0.9 = 比阈值低 10% 才切回）。 */
	private static final double TIER_HYSTERESIS = 0.9;

	/**
	 * 按当前缩放选本帧的档位（1 / 4 / 8 方块每像素），带迟滞：向上切换用阈值本身，向下要低
	 * {@value #TIER_HYSTERESIS} 倍才切回，免得在边界反复抖动时来回换档（画面忽清忽糊）。
	 * 结果存进 {@link #terrainTier}，填充、绘制、诊断都读它。
	 */
	private void updateTerrainTier() {
		// 按"每设备像素多少方块"判档（不是 GUI 像素——那会按 guiScale 提前，见常量说明）
		double perDevicePixel = zoom.current();
		int tier;
		if (perDevicePixel >= TIER8_BLOCKS_PER_DEVICE_PIXEL) {
			tier = WorldTilePages.COARSE_TIER_8;
		} else if (perDevicePixel >= TIER4_BLOCKS_PER_DEVICE_PIXEL) {
			// 从 8 档往下：还没掉到 12×0.9 = 10.8 以下就先留在 8 档
			tier = terrainTier == WorldTilePages.COARSE_TIER_8
					&& perDevicePixel >= TIER8_BLOCKS_PER_DEVICE_PIXEL * TIER_HYSTERESIS
					? WorldTilePages.COARSE_TIER_8 : WorldTilePages.COARSE_TIER_4;
		} else {
			// 从 4 档往下：还没掉到 4×0.9 = 3.6 以下就先留在 4 档
			tier = terrainTier == WorldTilePages.COARSE_TIER_4
					&& perDevicePixel >= TIER4_BLOCKS_PER_DEVICE_PIXEL * TIER_HYSTERESIS
					? WorldTilePages.COARSE_TIER_4 : 1;
		}
		// 设置里的"细节上限"：把自动选出来的档压到不超过它（{@link MinimapSettings#MAX_DETAIL_TIER} = 不限制）
		tier = Math.min(tier, settings.mapDetailTier());
		if (tier != terrainTier) {
			LOGGER.info("世界地图档位切换 | {} → {} 方块每像素 | 每设备像素 {} 方块（每 GUI 像素 {} 方块）",
					terrainTier, tier, perDevicePixel, blocksPerGui());
			terrainTier = tier;
		}
	}

	/**
	 * 一帧最多"看"几张粗页（含暂时没数据可填的），免得把整屏粗页都遍历一遍。这只是"扫一遍找谁有活"的
	 * 上限；真正往前推进的粗页<b>一帧只有一张</b>（见 {@link #fillView} 的 {@code coarseFedOne}）。
	 */
	private static final int COARSE_VISIT_PER_FRAME = 8;

	/**
	 * 每张粗页每次最多推进多少个粗像素（分帧推进）：4 档一页 65536 像素约 16 批走完、8 档 16384 像素 4 批。
	 * 像素按"一个区块占若干像素"整块取色（见 {@code CoarseTileCache#fillCoarse}），一批的实际采样量有限。
	 */
	private static final int COARSE_PIXELS_PER_PAGE = 4096;

	/** 现在是不是"画粗档贴图"的模式（缩得足够远）：本帧档位不是叶页。 */
	private boolean coarseMode() {
		return terrainTier > 1;
	}

	// 页填充的三项性能设置（都可在设置界面调，默认自动）：
	//
	// 一次访问每页推进多少块 = MinimapSettings#chunksPerVisit()（自动 32 = 正好一列，页内列优先，
	//   一列 PAGE_CHUNKS = 32 块）：一次只推一列，前沿是一条干净竖线。一次推 4 列会"好几列一起长出来"
	//   （加载撕裂感）。<b>不要取整页</b>：一次问 1024 块等于把整页押在后台预读队列上（队列有深度、多出的会被拒），
	//   画面变成"先什么都不出、然后一块块断续往上冒"。
	//
	// 同时"正在填"的页数上限 = MinimapSettings#fillingPages()（自动 8）：太多半成品页会"到处都在长、又都长不完"，
	//   封顶后是"一页铺完再下一页"。已开过工的页不受限制。
	//
	// 一帧最多让多少页各自推进一次 = MinimapSettings#fillingPagesPerFrame()（自动 1）：配合"正好一列"，
	//   一屏任何时刻只有一条竖着的前沿在走。铺开变慢，但数据由后台预读并行准备，嫌慢就调大前两项。

	/**
	 * 离玩家多少区块以内就算"客户端可能正加载着它"。
	 *
	 * <p><b>不能写死</b>：以前写死 24，而实际视距常常 32+，视距外圈那几圈客户端明明有区块、地图却整页
	 * 跳过。现在取"有效视距 + 余量"，跟着视距走。
	 */
	private static int loadedGuardChunks() {
		int renderDistance = Minecraft.getInstance().options.getEffectiveRenderDistance();
		return Math.max(24, renderDistance + 4);
	}

	/**
	 * 这一页有没有可能画得出东西。
	 *
	 * <p>两条判据取或：<b>世界存档里有这个区域文件</b>（以前生成过），或<b>它离玩家足够近</b>。都不成立
	 * 的远处填一整页只会得到 1024 个"没有数据"：白花预算，界面还一直画转圈。
	 *
	 * <p>"区域文件后来才出现"不用特殊处理：存在性缓存带 8 秒有效期，探索到那边后自然开始填。
	 */
	/**
	 * 本局"玩家到过"的页（键 = 页坐标打包）。
	 *
	 * <p>玩家走过去时那一带的区块会被实时采样、也写进本地存档，但世界存档的区域文件可能要过一会儿才落地。
	 * 光看"离玩家近不近"与"区域文件在不在"会把它们判成"永远没有数据"——表现就是"探过了也不加载出来"。
	 * 到过一次就记下来，之后永不当成没数据。
	 */
	private final Set<Long> seenPages = new HashSet<>();

	/**
	 * 粗页里"现在没有数据"的页（键 = 页坐标打包），避免每帧为它们重复探测区域文件。
	 *
	 * <p>缩到最小时视野里有两千多个页格位，绝大多数从没被探索过；不加这张表每帧都要为每个没建出来的页
	 * 问一遍 {@link #mayHaveData}（拼路径 + 算哈希，两千多次/帧）。
	 *
	 * <p>整体按 {@value #COARSE_NO_DATA_TTL_NANOS} 作废一次，与 {@code WorldSaveChunks#PRESENCE_TTL_NANOS}
	 * 同一量级：区域文件是边玩边长的，负结果不能永久留着，否则后来探索到的地方永远不出图。
	 */
	private final LongOpenHashSet coarseNoData = new LongOpenHashSet();

	/** {@link #coarseNoData} 上次整体作废的时刻。 */
	private long coarseNoDataClearedNanos;

	/** {@link #coarseNoData} 的有效期，见那里。 */
	private static final long COARSE_NO_DATA_TTL_NANOS = 8_000_000_000L;

	private boolean mayHaveData(int pageX, int pageZ) {
		long key = (long) pageX << 32 | pageZ & 0xFFFFFFFFL;
		if (seenPages.contains(key)) {
			return true;
		}
		// 看的是别的维度：不能问玩家当前维度的世界存档、也不能按玩家当前位置判"近"——坐标已按维度比例
		// 换算过，两边对不上，会把有数据的页整片误判成"没有数据"（切过去一直不加载）。改问"正在看的那个
		// 维度"的本地存档：这一页任意一处取样点有记录，就认为它值得建出来填。
		if (colorProvider.viewingForeignDimension()) {
			if (!foreignPageHasData(pageX, pageZ)) {
				return false;
			}
			seenPages.add(key);
			return true;
		}
		if (pageNearPlayer(pageX, pageZ)) {
			seenPages.add(key);
			return true;
		}
		return WorldSaveChunks.hasRegion(Minecraft.getInstance(),
				pageX * WorldTilePages.PAGE_CHUNKS, pageZ * WorldTilePages.PAGE_CHUNKS);
	}

	/** 看别的维度时：这一页的几处取样点在"正在看的维度"的本地存档里有没有记录（只查内存位图）。 */
	private boolean foreignPageHasData(int pageX, int pageZ) {
		int side = WorldTilePages.PAGE_CHUNKS;
		int baseX = pageX * side;
		int baseZ = pageZ * side;
		// 一页正好是一个区域文件的量级（32×32 区块 = 512×512 方块），取 5×5 个均匀取样点：
		// 都在同一个区块仓库里，查的是内存位图，几乎不花钱；只要这一页有一片探索过就基本能命中。
		for (int gx = 0; gx < 5; gx++) {
			int cx = baseX + Math.min(side - 1, gx * side / 4);
			for (int gz = 0; gz < 5; gz++) {
				int cz = baseZ + Math.min(side - 1, gz * side / 4);
				if (colorProvider.ownStorageMayHaveData(cx, cz)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * 这一页离玩家够不够近（近到客户端可能正加载着它，随时可能有数据）。单独提出来是因为
	 * {@link #coarseNoData} 的负结果表要"玩家附近永远现问现建"：负结果只对<b>远处</b>的页可信。
	 */
	private boolean pageNearPlayer(int pageX, int pageZ) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return false;
		}
		int chunkX = pageX * WorldTilePages.PAGE_CHUNKS;
		int chunkZ = pageZ * WorldTilePages.PAGE_CHUNKS;
		int guard = loadedGuardChunks();
		return Math.abs(chunkX + WorldTilePages.PAGE_CHUNKS / 2 - (player.getBlockX() >> 4)) <= guard
				&& Math.abs(chunkZ + WorldTilePages.PAGE_CHUNKS / 2 - (player.getBlockZ() >> 4)) <= guard;
	}

	/**
	 * {@link #mayHaveData} 的<b>粗页版本</b>：粗页一格管 {@code COARSE_PACK}² 张叶页格位，只要其中
	 * <b>任意一格</b>可能有数据就算有——只看角上那一格会把另外几格已探索过的数据整页判成"没有数据"。
	 */
	private boolean coarseMayHaveData(int packX, int packZ) {
		int pack = WorldTilePages.COARSE_PACK;
		for (int dz = 0; dz < pack; dz++) {
			for (int dx = 0; dx < pack; dx++) {
				if (mayHaveData(packX * pack + dx, packZ * pack + dz)) {
					return true;
				}
			}
		}
		return false;
	}

	/** {@link #pageNearPlayer} 的粗页版本：管的那几格里任意一格离玩家够近就算近（理由同 {@link #coarseMayHaveData}）。 */
	private boolean coarsePageNearPlayer(int packX, int packZ) {
		int pack = WorldTilePages.COARSE_PACK;
		for (int dz = 0; dz < pack; dz++) {
			for (int dx = 0; dx < pack; dx++) {
				if (pageNearPlayer(packX * pack + dx, packZ * pack + dz)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * 加载指示的<b>共同口径</b>（叶页与粗页两条路共用）：一页除了"这一遍还没扫完"（{@code inProgress}）
	 * 之外，还得<b>确实可能有数据</b>才值得转圈——取或"这一页真的画出过地形"（{@code paintedTerrain}）与
	 * "这一片本来就在可能有数据的范围里"（{@link #mayHaveData}：玩家附近 / 去过 / 存档里有区域文件）。
	 *
	 * <p>加后半条是因为外周那些<b>根本没有存档数据</b>的页也会被建出来（粗页由 {@link #drawTerrain} 按需建、
	 * 叶页由小地图建），它们的块全"还没定论"或"确认空洞"，只看进度会被一直画成"正在加载"。
	 */
	private boolean couldStillHaveData(boolean paintedTerrain, int pageX, int pageZ) {
		return paintedTerrain || mayHaveData(pageX, pageZ);
	}

	/** {@link #couldStillHaveData} 的粗页版本：管的那几格里任意一格可能有数据就算有。 */
	private boolean coarseCouldStillHaveData(boolean paintedTerrain, int packX, int packZ) {
		return paintedTerrain || coarseMayHaveData(packX, packZ);
	}

	/**
	 * 每秒一条：视野内各页的构成（画上地形 / 空洞 / 还没问）。<b>空洞</b>多说明那一带真的取不到数据；
	 * <b>还没问</b>多说明预算不够、还在慢慢铺。
	 */
	private static final long COMPOSITION_LOG_NANOS = 1_000_000_000L;
	private long lastCompositionLogNanos;

	/** 本秒内各段耗时的累计与峰值（纳秒），每秒记一行后清零，见 {@link #recordFrameCost}。 */
	private long fillNanosSum;
	private long fillNanosMax;
	private long refreshNanosSum;
	private long refreshNanosMax;
	private long drawNanosSum;
	private long drawNanosMax;
	private long frameNanosMax;
	private int framesThisSecond;

	private void logComposition() {
		long now = System.nanoTime();
		if (now - lastCompositionLogNanos < COMPOSITION_LOG_NANOS) {
			return;
		}
		lastCompositionLogNanos = now;
		int terrain = 0;
		int holes = 0;
		int undecided = 0;
		int pages = 0;
		int pending = 0;
		for (int pageZ = viewMinZ; pageZ <= viewMaxZ; pageZ++) {
			for (int pageX = viewMinX; pageX <= viewMaxX; pageX++) {
				TilePage page = tiles.find(currentLayer, pageX, pageZ);
				if (page == null) {
					continue;
				}
				pages++;
				int painted = page.terrainCount();
				int decided = page.decidedCount();
				terrain += painted;
				holes += decided - painted;
				undecided += WorldTilePages.PAGE_CHUNKS * WorldTilePages.PAGE_CHUNKS - decided;
				pending += page.pendingCount();
			}
		}
		// 待补的块在哪：落在填充顺序之内的每帧都会被问到，落在之外的永远轮不到
		// ——那正是"地图加载了一点就不动了"的样子，所以单独量出来
		int pendingInFillOrder = 0;
		if (fillOrder != null) {
			for (long cell : fillOrder) {
				TilePage page = tiles.find(currentLayer, (int) (cell >> 32), (int) cell);
				if (page != null) {
					pendingInFillOrder += page.pendingCount();
				}
			}
		}
		// 耗时那一行无论有没有页都要记：卡顿常常发生在"还没建出页"或"刚清空"的那一刻
		int frames = Math.max(framesThisSecond, 1);
		LOGGER.info("世界地图耗时 | 帧={} | 每帧 ms 总={}/{} 填充={}/{} 作废补画={}/{} 绘制={}/{}"
						+ " | 本秒填充内 ms：范围={} 重试={} 排页={} 建页={} 填={} 上传={}"
						+ " | 本秒 问={} 画={} 空洞={} 没定论={} 扫={} 命中={} 预算停={} | 待补={}（顺序内 {}）",
				frames,
				ms((fillNanosSum + refreshNanosSum + drawNanosSum) / frames), ms(frameNanosMax),
				ms(fillNanosSum / frames), ms(fillNanosMax),
				ms(refreshNanosSum / frames), ms(refreshNanosMax),
				ms(drawNanosSum / frames), ms(drawNanosMax),
				ms(layoutNanosSum), ms(retryNanosSum), ms(orderNanosSum),
				ms(pageNanosSum), ms(stepNanosSum), ms(uploadNanosSum),
				tiles.frameAsks(), tiles.framePaints(), tiles.frameHoles(), tiles.frameNotDecided(),
				tiles.frameProbes(), tiles.frameMarked(), tiles.frameBudgetOut(),
				pending, pendingInFillOrder);
		// 【诊断计量】接在既有诊断行之后追加一行：各段的均值/最大值/次数 + "铺满一屏"的墙钟时长。
		// 只有走到这里（本秒的第一条日志）才做字符串拼接；计量累加本身不做任何拼接。
		if (ClientChunkColorProvider.STATS_ENABLED) {
			ClientChunkColorProvider.MapStats stats = colorProvider.stats();
			long surfaceCount = Math.max(stats.surfaceCount, 1);
			long layeredCount = Math.max(stats.layeredCount, 1);
			long caveRealtimeCount = Math.max(stats.caveRealtimeCount, 1);
			long caveBackgroundCount = Math.max(stats.caveBackgroundCount.sum(), 1);
			long fillSomeCount = Math.max(stats.fillSomeCount, 1);
			long fillCoarseCount = Math.max(stats.fillCoarseCount, 1);
			long fillViewCount = Math.max(stats.fillViewCount, 1);
			LOGGER.info("世界地图计量 | 采样={}µs/块(最大 {}) 次数={} | 分层={}µs/次 次数={}"
							+ " | 洞穴实时={}µs/次 次数={} 洞穴后台={}µs/块 次数={}(最大 {})"
							+ " | 叶页填充={}ms/次(写 {} 块) 次数={}"
							+ " | 粗页填充={}ms/次(降采样 {}px / 回落 {}px, 回落 {}ms) 次数={}"
							+ " | 填满一屏 叶={}s 2档={}s 4档={}s | 粗页首图 2档={}s 4档={}s | fillView={}ms/帧(最大 {})",
					us(stats.surfaceNanosSum / surfaceCount), us(stats.surfaceNanosMax), stats.surfaceCount,
					us(stats.layeredNanosSum / layeredCount), stats.layeredCount,
					us(stats.caveRealtimeNanosSum / caveRealtimeCount), stats.caveRealtimeCount,
					us(stats.caveBackgroundNanos.sum() / caveBackgroundCount), stats.caveBackgroundCount.sum(),
					us(stats.caveBackgroundNanosMax.get()),
					ms(stats.fillSomeNanosSum / fillSomeCount), stats.fillSomeChunks, stats.fillSomeCount,
					ms(stats.fillCoarseNanosSum / fillCoarseCount), stats.coarseDownsamplePixels,
					stats.coarseFallbackPixels, ms(stats.coarseFallbackNanos), stats.fillCoarseCount,
					sec(screenFillLeafSeconds), sec(screenFill2Seconds), sec(screenFill4Seconds),
					sec(coarseFirst2Seconds), sec(coarseFirst4Seconds),
					ms(stats.fillViewNanosSum / fillViewCount), ms(stats.fillViewNanosMax));
			stats.reset();
		}
		fillNanosSum = 0;
		fillNanosMax = 0;
		refreshNanosSum = 0;
		refreshNanosMax = 0;
		drawNanosSum = 0;
		drawNanosMax = 0;
		frameNanosMax = 0;
		framesThisSecond = 0;
		layoutNanosSum = 0;
		retryNanosSum = 0;
		orderNanosSum = 0;
		pageNanosSum = 0;
		stepNanosSum = 0;
		uploadNanosSum = 0;
		fillViewNanosSum = 0;
		warmSpentSkips = 0;
		tiles.resetFrameCounters();
		if (pages == 0) {
			return;
		}
		// 粗档单独量：可见粗页数（视野内应铺几张粗页）与已建粗页数（当前档在仓库里实际有几张）。
		// 缩得很小时前者会明显大于后者一开始是正常的（分帧在建），但差值一直收不回来就是有问题。
		// 粗页的一格 = COARSE_PACK×COARSE_PACK 张叶页格位，所以按粗页坐标算。
		int pack = WorldTilePages.COARSE_PACK;
		int packMinX = Math.floorDiv(viewMinX, pack);
		int packMinZ = Math.floorDiv(viewMinZ, pack);
		int packMaxX = Math.floorDiv(viewMaxX, pack);
		int packMaxZ = Math.floorDiv(viewMaxZ, pack);
		int coarseVisible = (packMaxX - packMinX + 1) * (packMaxZ - packMinZ + 1);
		// 【诊断】没建出来的那些粗页<b>为什么</b>没建：分"本来就没数据"与"有数据却没轮到"两种。
		// 后者一直不降就是真的卡住了（新建额度/预算被别处吃光），前者是正常省事（见 coarseMayHaveData）。
		// 每秒算一次、只扫视野内那几十到两百个格位，代价可忽略。
		int missingWithData = 0;
		int missingNoData = 0;
		int builtUnfinished = 0;
		if (coarseMode()) {
			for (int pz = packMinZ; pz <= packMaxZ; pz++) {
				for (int px = packMinX; px <= packMaxX; px++) {
					if (tiles.findCoarse(currentLayer, terrainTier, px, pz) == null) {
						if (coarseMayHaveData(px, pz)) {
							missingWithData++;
						} else {
							missingNoData++;
						}
					} else if (tiles.coarseUnfinished(currentLayer, terrainTier, px, pz)) {
						builtUnfinished++;
					}
				}
			}
		}
		LOGGER.info("世界地图填充 | 视野内页={} | 档={} 方块每像素 粗页模式={} 可见粗页={} 已建粗页={}"
					+ " | 粗页明细 未建有数据={} 未建无数据={} 负表={} 已建未铺完={}"
					+ " | 地形块={} 空洞={} 还没问={} | 本秒暖预算停={} | 夜色={} 层={}",
			pages, terrainTier, coarseMode(), coarseVisible, tiles.coarseCount(terrainTier),
			missingWithData, missingNoData, coarseNoData.size(), builtUnfinished,
			terrain, holes, undecided, warmSpentSkips, nightAlpha(), describeLayer(currentLayer));
	}

	/**
	 * 当地夜色浓度（0~255）：0 = 只画白天那张，255 = 只画夜晚那张，中间是过渡。
	 *
	 * <p>每帧在 {@code fillView} 里算一次存进 {@link #frameNightAlpha}（填充、绘制、诊断三处都要用），
	 * 免得同一帧里算好几遍——它每次都要问一遍环境的天空光属性。
	 */
	private int nightAlpha() {
		return frameNightAlpha;
	}

	/** 本帧的夜色浓度，由 {@code fillView} 在每帧开头写入，见 {@link #nightAlpha()}。 */
	private int frameNightAlpha;

	private int currentNightAlpha() {
		return (int) Math.round(colorProvider.skyDarkenFraction() * 255.0);
	}

	/** 这一帧允许用于建数据的时间：跟着帧时间走，见 {@link #WARM_BUDGET_RATIO}。 */
	private static long warmBudgetNanos(double deltaMillis) {
		double millis = Math.clamp(deltaMillis * WARM_BUDGET_RATIO,
				WARM_BUDGET_MIN_MILLIS, WARM_BUDGET_MAX_MILLIS);
		return (long) (millis * 1.0e6);
	}

	/** 重算视野覆盖的页范围；范围没变就什么也不做，填充顺序因此不会被平移打断。 */
	private void updatePageRange() {
		double perGui = blocksPerGui();
		double originX = originWorldX();
		double originZ = originWorldZ();
		double left = originX - originGuiX() * perGui;
		double top = originZ - originGuiZ() * perGui;
		double right = left + width * perGui;
		double bottom = top + height * perGui;

		int minX = (int) Math.floor(left / WorldTilePages.PAGE_BLOCKS);
		int minZ = (int) Math.floor(top / WorldTilePages.PAGE_BLOCKS);
		int maxX = (int) Math.floor(right / WorldTilePages.PAGE_BLOCKS);
		int maxZ = (int) Math.floor(bottom / WorldTilePages.PAGE_BLOCKS);
		if (minX == viewMinX && minZ == viewMinZ && maxX == viewMaxX && maxZ == viewMaxZ) {
			return;
		}
		viewMinX = minX;
		viewMinZ = minZ;
		viewMaxX = maxX;
		viewMaxZ = maxZ;
		// 补数据多铺几圈：拖动时新露出的页大多已填好。但铺多了会把页仓库挤爆（页互相淘汰、每帧重填），
		// 所以按仓库容量反推能铺几圈
		int margin = PAGE_MARGIN;
		int columns;
		int rows;
		while (true) {
			columns = maxX - minX + 1 + 2 * margin;
			rows = maxZ - minZ + 1 + 2 * margin;
			if (columns * rows <= WorldTilePages.MAX_PAGES || margin == 0) {
				break;
			}
			margin--;
		}
		// 容量跟着"这一屏要铺多少页"走。固定容量在一屏本身就要几十页（拉宽视野）时会小于需求，屏幕边缘
		// 的页根本建不出来，手一移"最近的 N 页"整批换人——看起来正是"离开过的地方再回去又重加载"。
		tiles.setCapacity(columns * rows);
		pageMinX = minX - margin;
		pageMinZ = minZ - margin;
		pageMaxX = maxX + margin;
		pageMaxZ = maxZ + margin;
		fillOrder = null;
	}

	/**
	 * 填充顺序：<b>页与页之间按离玩家的距离由近到远</b>，页内部固定列优先
	 * （见 {@link TilePage#fillCursor}）。度量用页索引的切比雪夫距离一圈圈向外，省掉每帧排序。
	 *
	 * <p><b>刻意不做"朝向优先"</b>：试过把螺旋中心往面朝方向挪几页，结果斜着走时整张图跟着歪、一转头
	 * 方向就跳。"前面先出来"由"离玩家近的先出来"自然带来，不必按朝向另做一套。
	 *
	 * <p>同时排出<b>粗档</b>顺序 {@link #coarseOrder}：粗页一格是 {@code COARSE_PACK}² 张叶页格位，
	 * 与叶页共用同一片视野范围，只是按粗页坐标单独排、裁剪上限不同（叶页裁到仓库容量、粗档裁到
	 * {@code WorldTilePages#MAX_COARSE_PAGES}）。
	 *
	 * @param centerPageX 玩家所在页（横向）
	 * @param centerPageZ 玩家所在页（纵向）
	 */
	private void rebuildFillOrders(int centerPageX, int centerPageZ) {
		int columns = pageMaxX - pageMinX + 1;
		int rows = pageMaxZ - pageMinZ + 1;
		int centerX = Math.clamp(centerPageX - pageMinX, 0, columns - 1);
		int centerZ = Math.clamp(centerPageZ - pageMinZ, 0, rows - 1);
		// 只排最近的这些页：视野拉到很宽时一屏上百页，全排上会把显存和填充拖垮。裁到仓库容量后，缩得
		// 很远时最远的那些页就是"没有瓦片"、露界面底色，而不是整屏一起卡住。
		fillOrder = buildOrder(pageMinX, pageMinZ, columns, rows, centerX, centerZ,
				Math.min(columns * rows, tiles.capacity()));
		// 粗档一页 = COARSE_PACK² 张叶页格位，所以顺序按<b>粗页坐标</b>单独排一遍，条目与张数都只有叶页的四分之一。
		int pack = WorldTilePages.COARSE_PACK;
		int packMinX = Math.floorDiv(pageMinX, pack);
		int packMinZ = Math.floorDiv(pageMinZ, pack);
		int packColumns = Math.floorDiv(pageMaxX, pack) - packMinX + 1;
		int packRows = Math.floorDiv(pageMaxZ, pack) - packMinZ + 1;
		int packCenterX = Math.clamp(Math.floorDiv(centerPageX, pack) - packMinX, 0, packColumns - 1);
		int packCenterZ = Math.clamp(Math.floorDiv(centerPageZ, pack) - packMinZ, 0, packRows - 1);
		coarseOrder = buildOrder(packMinX, packMinZ, packColumns, packRows, packCenterX, packCenterZ,
				Math.min(packColumns * packRows, WorldTilePages.MAX_COARSE_PAGES));
		fillOrderCenterPageX = centerPageX;
		fillOrderCenterPageZ = centerPageZ;
	}

	/** 以 {@code (centerX, centerZ)} 为起点一圈圈向外，取最近 {@code limit} 个页坐标（打包成 long）。 */
	private long[] buildOrder(int originX, int originZ, int columns, int rows,
			int centerX, int centerZ, int limit) {
		CenterOutSweep sweep = new CenterOutSweep(columns, rows, centerX, centerZ);
		long[] order = new long[limit];
		int at = 0;
		while (sweep.hasNext() && at < limit) {
			if (sweep.inRange()) {
				order[at++] = (long) (originX + sweep.x()) << 32 | originZ + sweep.y() & 0xFFFFFFFFL;
			}
			sweep.advance();
		}
		return at == limit ? order : Arrays.copyOf(order, at);
	}

	/** 玩家落在哪一页（横向）。没有玩家时退回视野中心。 */
	private int playerPageX() {
		return Math.floorDiv(playerChunkX(), WorldTilePages.PAGE_CHUNKS);
	}

	/** 玩家落在哪一页（纵向）。 */
	private int playerPageZ() {
		return Math.floorDiv(playerChunkZ(), WorldTilePages.PAGE_CHUNKS);
	}

	/** 玩家所在区块的横向坐标；页内填充顺序以它为中心由内向外。 */
	private int playerChunkX() {
		return Math.floorDiv(playerBlockX(), SinanConstants.CHUNK_SIZE);
	}

	/** 玩家所在区块的纵向坐标。 */
	private int playerChunkZ() {
		return Math.floorDiv(playerBlockZ(), SinanConstants.CHUNK_SIZE);
	}

	private int playerBlockX() {
		LocalPlayer player = Minecraft.getInstance().player;
		return (int) Math.floor(player == null ? viewCenterX() : player.getX());
	}

	private int playerBlockZ() {
		LocalPlayer player = Minecraft.getInstance().player;
		return (int) Math.floor(player == null ? viewCenterZ() : player.getZ());
	}

	/** 层的可读名字，供日志使用。 */
	private static String describeLayer(int layer) {
		return LayerSelector.isCave(layer) ? "洞穴 Y=" + layer : "地表";
	}

	/**
	 * 把视野内的瓦片页贴到屏幕上。
	 *
	 * <p>整块变换只设一次：先平移到"参考页左上角该落在的 GUI 位置"，再按"每方块多少个 GUI 像素"缩放，
	 * 于是每页只要用<b>方块坐标</b>画一个整页矩形即可。未填充的像素透明，因此边缘的页不需要裁剪。
	 */
	private void drawTerrain(GuiGraphicsExtractor graphics) {
		double perGui = blocksPerGui();
		// 夜色浓度：0 = 白天那张原样，1 = 完全用夜晚那张。按未分档的原始天色取，档与档之间是连续的
		// （本帧的值由 fillView 算好，见 nightAlpha）
		int nightAlpha = nightAlpha();
		int size = WorldTilePages.PAGE_BLOCKS;
		long now = System.nanoTime();

		Matrix3x2fStack pose = graphics.pose();
		pushBlockTransform(pose);
		if (coarseMode()) {
			// P0：缩得远 → 画粗档贴图。粗页一格 = COARSE_PACK² 张叶页格位，贴图边长 = 叶页格位 × COARSE_PACK
			// （2 档 512×512、4 档 256×256），每档只差 2 倍、落在"1 texel ≈ 1 屏幕像素"区间里，切换看不出。
			//
			// 采样器用最近邻的那两个名字（daySharpId / nightSharpId）：粗档缩得远时会被放大画，
			// 线性会把格子插成一片渐变（见 ResampledTexture）。
			//
			// 落点换算：矩阵里 1 单位 = 1 方块，先按"一粗页格 = COARSE_PACK 张叶页"折回叶页再乘 PAGE_BLOCKS：
			// x0 = (pageX × COARSE_PACK − viewMinX) × 512。一页画一整张（PAGE_BLOCKS × COARSE_PACK 方块），
			// 同一屏的绘制调用因此只有叶页格位的 1/4。建页顺序 = 由"你正看的那一片"向外的圈序。
			//
			// 以前从视野左上角按行优先扫，而切到更粗一档时页键含档位、全是新页：中心那页排在半屏之后，
			// 要等外圈（多半没数据、画出来一片透明）建完才轮到，看着就是"切到这一档半天不出图"。按由近到远
			// 建之后第一帧就建中心那几页，下一帧立刻能画——遍历范围与原来完全一致，只是换了次序。
			int created = 0;
			// 粗页一页 = COARSE_PACK² 张叶页格位：按<b>粗页坐标</b>遍历，一页只画一次。
			int pack = WorldTilePages.COARSE_PACK;
			int packMinX = Math.floorDiv(viewMinX, pack);
			int packMinZ = Math.floorDiv(viewMinZ, pack);
			int packMaxX = Math.floorDiv(viewMaxX, pack);
			int packMaxZ = Math.floorDiv(viewMaxZ, pack);
			int columns = packMaxX - packMinX + 1;
			int rows = packMaxZ - packMinZ + 1;
			// 中心取填充顺序的中心（与 coarseOrder 对齐）；fillView 先于本方法执行，所以这里一定是最新值。
			int centerPageX = Math.clamp(Math.floorDiv(fillOrderCenterPageX, pack) - packMinX,
					0, columns - 1);
			int centerPageZ = Math.clamp(Math.floorDiv(fillOrderCenterPageZ, pack) - packMinZ,
					0, rows - 1);
			CenterOutSweep sweep = new CenterOutSweep(columns, rows, centerPageX, centerPageZ);
			while (sweep.hasNext()) {
				boolean inRange = sweep.inRange();
				int pageX = packMinX + sweep.x();
				int pageZ = packMinZ + sweep.y();
				sweep.advance();
				if (!inRange) {
					continue;
				}
				// 粗页"没有数据"负结果表到期就整表作废（见 #coarseNoData）。放在这里而不是新建那一支：
				// 新建额度可能被中心几页先花光，那样过期检查就走不到、表再也不作废。
				if (now - coarseNoDataClearedNanos >= COARSE_NO_DATA_TTL_NANOS) {
					coarseNoData.clear();
					coarseNoDataClearedNanos = now;
				}
				// 只取不建（findCoarse）：常驻表里有的直接画，取用是免费的。
				TileVariants textures = tiles.findCoarse(currentLayer, terrainTier, pageX, pageZ);
				if (textures == null) {
					// 没建过的才花"每帧新建额度"。额度用完的这一帧先不建也不画（露界面底色），
					// 下一帧接着建——这是有意的取舍：宁可少露几块底色，也不整帧卡住加显存尖峰（见常量注释）。
					if (created >= MAX_COARSE_CREATES_PER_FRAME) {
						continue;
					}
					// 这一片根本不可能有数据就不建（判据按"这几格里任意一格可能有数据"，见 #coarseMayHaveData）。
					// 缩到最小时一屏两千多个页格位，玩家真正探索过的只占百分之几；不看数据一律建贴图的话，
					// 两千多对的显存与每帧两千多次贴图绑定全花在"本来就全透明"的页上。
					//
					// 负结果进 {@link #coarseNoData} 记着，不每帧重问（上千次路径拼接 + 哈希）；但玩家附近
					// 永远现问现建，负结果只对远处可信（见 {@link #coarsePageNearPlayer}）。
					long cellKey = (long) pageX << 32 | pageZ & 0xFFFFFFFFL;
					if (coarseNoData.contains(cellKey) && !coarsePageNearPlayer(pageX, pageZ)) {
						continue;
					}
					if (!coarseMayHaveData(pageX, pageZ)) {
						coarseNoData.add(cellKey);
						continue;
					}
					textures = tiles.coarse(currentLayer, terrainTier, pageX, pageZ);
					created++;
				}
				// 还没画上任何地形的页是整页全透明的（见 CoarseTileCache#fillCoarseEmpty），画它白费一次绑定 + 绘制。
				// 跳过的页照常填充，数据一到、第一块地形画上去，下一帧自然会被画出来。
				if (!tiles.coarseHasRealTerrain(currentLayer, terrainTier, pageX, pageZ)) {
					continue;
				}
				// 落点按叶页格位折算：矩阵里 1 单位 = 1 方块，一个叶页 = PAGE_BLOCKS 方块，粗页整页 = PAGE_BLOCKS × COARSE_PACK。
				int x0 = (pageX * pack - viewMinX) * size;
				int z0 = (pageZ * pack - viewMinZ) * size;
				// 落点边长是"方块数"，贴图边长是"像素数"：粗页的贴图只有叶页格位那么大（见 drawPage）
				int pageBlocks = size * pack;
				int pageTexture = WorldTilePages.coarseSize(terrainTier);
				if (nightAlpha < 255) {
					drawPage(graphics, textures.daySharpId(), x0, z0, pageBlocks, pageTexture, 0xFFFFFFFF);
				}
				if (nightAlpha > 0) {
					drawPage(graphics, textures.nightSharpId(), x0, z0, pageBlocks, pageTexture,
							nightAlpha << 24 | 0xFFFFFF);
				}
			}
		} else {
			for (int pageZ = viewMinZ; pageZ <= viewMaxZ; pageZ++) {
				// z0 与这一行的 pageX 无关，提到内层循环外
				int z0 = (pageZ - viewMinZ) * size;
				for (int pageX = viewMinX; pageX <= viewMaxX; pageX++) {
					TilePage page = tiles.find(currentLayer, pageX, pageZ);
					if (page == null || !page.loaded()) {
						continue;
					}
					// 新填的页整页淡入：加载看得见。这是纯"绘制时压 alpha"，不用重写像素、也不用重传贴图
					float fade = pageFade(page, now);
					if (fade <= 0.0F) {
						continue;
					}
					int x0 = (pageX - viewMinX) * size;
					TileVariants textures = page.textures();
					// 白天那张打底，夜晚那张按天色压 alpha 叠一层（白天×(1-t) + 夜晚×t）。亮度是绘制时叠的，
					// 换天色不用重算；两张同画只有黄昏/黎明那一小段，各自省掉一半绘制调用。
					// 用最近邻的两个名字：整页贴图下线性会把方块边缘插成一片渐变（小地图走线性的另外两个名字）。
					if (nightAlpha < 255) {
						drawPage(graphics, textures.daySharpId(), x0, z0, size, alphaColor(fade, 0xFFFFFFFF));
					}
					if (nightAlpha > 0) {
						drawPage(graphics, textures.nightSharpId(), x0, z0, size,
								alphaColor(fade, nightAlpha << 24 | 0xFFFFFF));
					}
				}
			}
		}
		pose.popMatrix();

		// 加载指示：画在正在等数据的那几页中心。
		//
		// 判据分两半：①"这一遍还没扫完"（{@code awaitingData / coarseAwaitingData}，见 WorldTilePages）；
		// ②"这一片确实可能有数据"（{@link #couldStillHaveData}）。两半都要过——只看①的话，外周那些根本
		// 没有存档数据的页也会一直转圈。最近几页优先（顺序取 {@link #fillOrder}），只留最近
		// {@value #MAX_SPINNERS} 个，免得缩得很远时满屏都在转。画在矩阵之外，否则转动的方块会被一起缩掉。
		if (coarseMode()) {
			// 粗页模式：叶页这一帧不再推进游标（见 fillView），它们的 awaitingData 不再代表"正在加载的东西"；
			// 改成问粗页自己，正在扫的那张转圈、扫完就停。
			drawCoarseLoadingSpinners(graphics, blockTransformBaseX(), blockTransformBaseZ(), perGui);
		} else {
			int spinnersDrawn = 0;
			for (long cell : fillOrder) {
				if (spinnersDrawn >= MAX_SPINNERS) {
					break;
				}
				int pageX = (int) (cell >> 32);
				int pageZ = (int) cell;
				if (pageX < viewMinX || pageX > viewMaxX || pageZ < viewMinZ || pageZ > viewMaxZ) {
					continue;
				}
				TilePage page = tiles.find(currentLayer, pageX, pageZ);
				// 先过"便宜"的进度判据，再问"这一片有没有数据"（后者要查玩家距离 / 区域文件）
				if (page == null || !tiles.awaitingData(page)
						|| !couldStillHaveData(tiles.hasRealTerrain(page), pageX, pageZ)) {
					continue;
				}
				double centerX = blockTransformBaseX() + ((pageX - viewMinX) * size + size / 2.0) / perGui;
				double centerY = blockTransformBaseZ() + ((pageZ - viewMinZ) * size + size / 2.0) / perGui;
				if (centerX < -SPINNER_SIZE || centerX > width + SPINNER_SIZE
						|| centerY < -SPINNER_SIZE || centerY > height + SPINNER_SIZE) {
					continue;
				}
				drawLoadingSpinner(graphics, centerX, centerY);
				spinnersDrawn++;
			}
		}
	}

	/**
	 * 粗档模式的加载指示：画在"正在扫的那张粗页"中心。顺序取 {@link #coarseOrder}（离视口中心由近到远），
	 * 只留最近 {@value #MAX_SPINNERS} 个——与叶页那条同一个写法；中心点按"一粗页格 = PAGE_BLOCKS ×
	 * COARSE_PACK 方块"算。
	 *
	 * <p>判据与叶页共用 {@link #couldStillHaveData}：粗页由 {@link #drawTerrain} 给<b>视野里每一页</b>都
	 * 按需建出来（含外周毫无数据的那一圈），只看进度的话它们第一次扫掠时一样会转圈。
	 */
	private void drawCoarseLoadingSpinners(GuiGraphicsExtractor graphics, double baseX, double baseZ, double perGui) {
		if (coarseOrder == null) {
			return;
		}
		int drawn = 0;
		// 粗页一格 = COARSE_PACK² 张叶页格位，坐标与方块数都要按它折算
		int pack = WorldTilePages.COARSE_PACK;
		int packMinX = Math.floorDiv(viewMinX, pack);
		int packMinZ = Math.floorDiv(viewMinZ, pack);
		int packMaxX = Math.floorDiv(viewMaxX, pack);
		int packMaxZ = Math.floorDiv(viewMaxZ, pack);
		int pageBlocks = WorldTilePages.PAGE_BLOCKS * pack;
		int leafBlocks = WorldTilePages.PAGE_BLOCKS;
		for (long cell : coarseOrder) {
			if (drawn >= MAX_SPINNERS) {
				break;
			}
			int pageX = (int) (cell >> 32);
			int pageZ = (int) cell;
			if (pageX < packMinX || pageX > packMaxX || pageZ < packMinZ || pageZ > packMaxZ) {
				continue;
			}
			// 先过"便宜"的进度判据（一次查表），再问"这一片有没有数据"（要查玩家距离 / 区域文件）
			if (!tiles.coarseAwaitingData(currentLayer, terrainTier, pageX, pageZ)) {
				continue;
			}
			if (!coarseCouldStillHaveData(
					tiles.coarseHasRealTerrain(currentLayer, terrainTier, pageX, pageZ), pageX, pageZ)) {
				continue;
			}
			double centerX = baseX + ((pageX * pack - viewMinX) * (double) leafBlocks + pageBlocks / 2.0) / perGui;
			double centerY = baseZ + ((pageZ * pack - viewMinZ) * (double) leafBlocks + pageBlocks / 2.0) / perGui;
			if (centerX < -SPINNER_SIZE || centerX > width + SPINNER_SIZE
					|| centerY < -SPINNER_SIZE || centerY > height + SPINNER_SIZE) {
				continue;
			}
			drawLoadingSpinner(graphics, centerX, centerY);
			drawn++;
		}
	}

	/** 一屏最多同时画几个加载指示。等数据的页通常只有一两个，给多点余量，但绝不能满屏。 */
	private static final int MAX_SPINNERS = 4;

	/** 加载指示：一个边长 {@value #SPINNER_SIZE} 的方框，按固定速度转。 */
	private static final int SPINNER_SIZE = 12;

	/** 指示的颜色：半透明白，别抢地形。 */
	private static final int SPINNER_COLOR = 0x99FFFFFF;

	/** 一圈转多久（纳秒）。 */
	private static final long SPINNER_TURN_NANOS = 1_400_000_000L;

	/** 加载指示的 sprite：一个纯白的方框轮廓，见 {@link #drawLoadingSpinner}。 */
	private static final Identifier LOADING_TEXTURE =
			Identifier.fromNamespaceAndPath(SinanConstants.MOD_ID, "loading");

	/**
	 * 加载指示：一个边长 {@value #SPINNER_SIZE} 的方框，按固定速度转。
	 *
	 * <p>方框是 GUI 图集 sprite（{@code sinanmap:loading}）而不是四条 {@code fill}：sprite 纯白、颜色由
	 * {@link #SPINNER_COLOR} 乘上去，资源包替换那一张图就能改样式。
	 */
	private static void drawLoadingSpinner(GuiGraphicsExtractor graphics, double centerX, double centerY) {
		float angle = (float) (System.nanoTime() % SPINNER_TURN_NANOS / (double) SPINNER_TURN_NANOS
				* Math.PI * 2.0);
		int size = SPINNER_SIZE;
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate((float) centerX, (float) centerY);
		pose.rotate(angle);
		// 按 SPINNER_SIZE 1:1 画，blitSprite 会按图集里记录的精灵尺寸自动缩放；以中心为原点
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, LOADING_TEXTURE, -size / 2, -size / 2,
				size, size, SPINNER_COLOR);
		pose.popMatrix();
	}

	/** 整页淡入的时长。 */
	private static final long PAGE_FADE_NANOS = 420_000_000L;

	/** 一页的淡入进度（0~1，带缓入缓出）。 */
	private static float pageFade(TilePage page, long now) {
		long start = page.loadedAtNanos();
		if (start == 0L) {
			// 不淡入：老页、以及被淘汰后重新填回来的页，直接画出来
			return 1.0F;
		}
		float t = Math.clamp((now - start) / (float) PAGE_FADE_NANOS, 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}

	/** 把给定颜色再乘上一个淡入系数：两层贴图都跟着淡入，比例不变。 */
	private static int alphaColor(float fade, int color) {
		int alpha = (int) (((color >>> 24) & 0xFF) * Math.clamp(fade, 0.0F, 1.0F));
		return alpha << 24 | color & 0xFFFFFF;
	}

	/**
	 * 画一页贴图。实现在 {@link WorldTilePages#drawPage}：小地图也画同一套页，"取样范围往内缩半个像素"
	 * 的规矩（页与页拼接处不出缝）只该有一份。
	 */
	private static void drawPage(GuiGraphicsExtractor graphics, Identifier texture, int x, int y, int size, int color) {
		WorldTilePages.drawPage(graphics, texture, x, y, size, color);
	}

	/**
	 * 同上，但落点边长（方块）与贴图边长（像素）分开给：粗页把 COARSE_PACK² 张叶页格位合成一张贴图，
	 * 贴图只有叶页格位那么大，却要铺满 COARSE_PACK 倍的方块范围。
	 */
	private static void drawPage(GuiGraphicsExtractor graphics, Identifier texture, int x, int y,
			int size, int textureSize, int color) {
		WorldTilePages.drawPage(graphics, texture, x, y, size, textureSize, color);
	}

	/**
	 * 光标所在位置的坐标，放屏幕<b>顶部居中</b>：原来标题/中心/缩放三行调试信息去掉，左上角留给原版调试图、
	 * 右上角留给别的模组。
	 */
	/**
	 * 光标那几行文字（Y 与群系）按<b>光标所在方块 + 当前层</b>缓存：这两个查询都可能要读世界存档，
	 * 而鼠标停着不动时结果完全一样，跨到另一个方块才重查。
	 */
	private int cursorCacheBlockX = Integer.MIN_VALUE;
	private int cursorCacheBlockZ = Integer.MIN_VALUE;
	private int cursorCacheLayer = Integer.MIN_VALUE;
	private int cursorCacheY = NO_SURFACE;
	private String cursorCacheText = "";
	private String cursorCacheBiome = "";

	private void drawText(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		BlockPoint cursor = worldAtGui(mouseX, mouseY);
		int blockX = (int) Math.floor(cursor.x());
		int blockZ = (int) Math.floor(cursor.z());
		if (blockX != cursorCacheBlockX || blockZ != cursorCacheBlockZ || currentLayer != cursorCacheLayer) {
			cursorCacheBlockX = blockX;
			cursorCacheBlockZ = blockZ;
			cursorCacheLayer = currentLayer;
			// 洞穴层没有"地表高度"可言，就以当前显示层为准（那一层的 Y 正是这张图切的高度带）；地表才问高度图。
			// 用同一个 Y 查群系，地下也能报出地下的群系（原版群系分层）。
			int y = LayerSelector.isCave(currentLayer)
					? currentLayer
					: surfaceHeightAt(cursor.x(), cursor.z());
			cursorCacheY = y;
			// Y 两条来源都能给：已加载区块问客户端、没加载的问世界存档高度图（见 surfaceHeightAt），远处也报得出 Y
			cursorCacheText = y == NO_SURFACE
					? Component.translatable("screen.sinanmap.world_map.cursor_no_y", blockX, blockZ).getString()
					: Component.translatable("screen.sinanmap.world_map.cursor", blockX, y, blockZ).getString();
			cursorCacheBiome = biomeNameAt(cursor.x(), cursor.z(), y);
		}

		// UI 放在地形之上、文字之下
		drawUi(graphics, mouseX, mouseY);
		graphics.text(font, cursorCacheText, (width - font.width(cursorCacheText)) / 2, 6, TEXT_COLOR);

		// 群系放在坐标下面：看地图时经常要知道"这一片是什么"
		if (!cursorCacheBiome.isEmpty()) {
			graphics.text(font, cursorCacheBiome, (width - font.width(cursorCacheBiome)) / 2,
					6 + font.lineHeight + 2, TEXT_COLOR);
		}
	}

	/**
	 * 光标那一列所在群系的显示名；拿不到就返回空串（不显示这一行）。
	 *
	 * <p>两条来源与地形对应：已加载的区块直接问客户端（最准、不碰磁盘），没加载的远处去世界存档里读。
	 */
	private static String biomeNameAt(double worldX, double worldZ, int surfaceY) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			return "";
		}
		int blockX = (int) Math.floor(worldX);
		int blockZ = (int) Math.floor(worldZ);
		// 有地表高度时按地表那一格取：下界这类维度群系是分层的，按固定高度取会取到别的群系
		int y = surfaceY == NO_SURFACE ? 64 : surfaceY;
		// 门槛放到 BIOMES：群系在这一步就写好，FEATURES/FULL 还要再等好几步；用 FULL 的话加载范围最外
		// 那一圈"有地形色但没有群系"
		if (level.getChunkSource().getChunk(blockX >> 4, blockZ >> 4, ChunkStatus.BIOMES, false) != null) {
			return biomeDisplay(level.getBiome(new BlockPos(blockX, y, blockZ)).unwrapKey()
					.map(key -> key.identifier().toString()).orElse(null));
		}
		return biomeDisplay(WorldSaveChunks.biomeIdAt(Minecraft.getInstance(), blockX, y, blockZ));
	}

	/** 群系 id（{@code minecraft:plains}）→ 本地化的显示名。 */
	private static String biomeDisplay(String biomeId) {
		if (biomeId == null || biomeId.isEmpty()) {
			return "";
		}
		int separator = biomeId.indexOf(':');
		String path = separator < 0 ? biomeId : biomeId.substring(0, separator) + "." + biomeId.substring(separator + 1);
		return Component.translatable("biome." + path).getString();
	}

	/** 光标那一列没有可用的地表高度（区块没加载）。 */
	private static final int NO_SURFACE = Integer.MIN_VALUE;

	/**
	 * 光标那一列的地表高度（<b>站上去那一格</b>），用于坐标里的 Y 与"在此新建路径点"。
	 *
	 * <p>三个来源按可信度排：①客户端高度图（最准最快）；②本地缓存的原始数据（走过但已卸载、存档还没写盘
	 * 的那一段，少了这条就会"走出去一段就看不见 Y"）；③世界存档里的高度图（没走到过但画得出来的远处）。
	 */
	private int surfaceHeightAt(double worldX, double worldZ) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			return NO_SURFACE;
		}
		int blockX = (int) Math.floor(worldX);
		int blockZ = (int) Math.floor(worldZ);
		// 两边都取 MOTION_BLOCKING、都给"第一格空气"（站上去时脚所在的 Y），否则同一条路径点从客户端问
		// 和从存档问会差一格
		if (level.getChunkSource().getChunk(blockX >> 4, blockZ >> 4, ChunkStatus.FULL, false) != null) {
			return level.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ);
		}
		int cached = colorProvider.cachedSurfaceHeight(
				blockX >> 4, blockZ >> 4, blockX & 15, blockZ & 15);
		if (cached != ClientChunkColorProvider.NO_HEIGHT) {
			return cached;
		}
		int fromSave = WorldSaveChunks.surfaceHeightAt(Minecraft.getInstance(), blockX, blockZ);
		return fromSave == WorldSaveChunks.NO_HEIGHT ? NO_SURFACE : fromSave;
	}

	private void drawPlayerMarker(GuiGraphicsExtractor graphics) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return;
		}
		// 看的是别的维度：玩家根本不在这里，画在换算后的坐标上只会是"上一个维度的东西"压在别的地形上
		if (colorProvider.viewingForeignDimension()) {
			return;
		}
		// 标记单独绘制（不烘焙进地形瓦片），因此视野平移或玩家移动时不必重画任何瓦片
		double perGui = blocksPerGui();
		double markerX = originGuiX() + (player.getX() - originWorldX()) / perGui;
		double markerY = originGuiZ() + (player.getZ() - originWorldZ()) / perGui;
		// 箭尖到中心只有贴图边长的一部分（ARROW_RATIO），描边还会再放大一圈，所以这里的边长要比想要的
		// 箭头大一点（跟随界面等比、固定 GUI 像素）
		double markerSize = Math.clamp(height * 0.032, 7.0, 15.0);
		if (markerX < -markerSize || markerY < -markerSize
				|| markerX > width + markerSize || markerY > height + markerSize) {
			return;
		}

		// 全屏地图固定正北朝上（旋转为 0），因此世界坐标里的方向就是屏幕方向，只需归一化
		double forwardX = Heading.forwardX(player.getYRot());
		double forwardZ = Heading.forwardZ(player.getYRot());
		double length = Math.hypot(forwardX, forwardZ);
		double directionX = length < 1.0e-9 ? 0.0 : forwardX / length;
		double directionZ = length < 1.0e-9 ? -1.0 : forwardZ / length;
		markerRenderer.draw(graphics, markerX, markerY, markerSize, directionX, directionZ);
	}

	/** 屏幕 GUI 坐标对应的世界坐标，与当前实际画出来的地图一致（缩放动画期间也正确）。 */
	private BlockPoint worldAtGui(double guiX, double guiY) {
		double perGui = blocksPerGui();
		return new BlockPoint(
				originWorldX() + (guiX - originGuiX()) * perGui,
				originWorldZ() + (guiY - originGuiZ()) * perGui);
	}

	/**
	 * 当前画面上"GUI 原点对应哪个世界坐标"。
	 *
	 * <p>缩放动画期间指向光标下的锚点：动画中途视图中心还没落到最终位置，只有以锚点为原点，
	 * 光标按住的那个世界坐标才会全程不动；动画结束 {@link #settleZoom()} 把它交给视图中心。
	 */
	private double originWorldX() {
		return anchoring ? anchorWorldX : viewCenterX();
	}

	private double originWorldZ() {
		return anchoring ? anchorWorldZ : viewCenterZ();
	}

	private double originGuiX() {
		return anchoring ? anchorGuiX : width / 2.0;
	}

	private double originGuiZ() {
		return anchoring ? anchorGuiY : height / 2.0;
	}

	/**
	 * 视口中心。
	 */
	private double viewCenterX() {
		return centerX;
	}

	private double viewCenterZ() {
		return centerZ;
	}

	private void viewCenterOn(double worldX, double worldZ) {
		this.centerX = worldX;
		this.centerZ = worldZ;
	}

	/** 屏幕宽度（设备像素）。 */
	private int deviceWidth() {
		return width * guiScale();
	}

	/** 屏幕高度（设备像素）。 */
	private int deviceHeight() {
		return height * guiScale();
	}

	/**
	 * 每个 GUI 像素代表多少方块。缩放的内部单位是"每设备像素多少方块"，而鼠标事件与绘制坐标都是 GUI 像素，
	 * 差一个 {@code guiScale}。换算只用这一个入口，免得漏乘出现"拖拽跟不上光标"或"缩放结束时跳一下"。
	 */
	private double blocksPerGui() {
		return Math.max(1.0e-9, zoom.current() * guiScale());
	}

	/** 当前 GUI 缩放。 */
	private static int guiScale() {
		return Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
	}

	/**
	 * 把 GUI 坐标对齐到设备像素栅格：对齐到设备像素比对齐到 GUI 像素精细 {@code guiScale} 倍，
	 * 移动依然平滑，但取样点不会落在像素之间。
	 */
	private static double snapToDevicePixel(double gui, int guiScale) {
		return Math.round(gui * guiScale) / (double) guiScale;
	}

	/**
	 * 屏幕上的 UI：左上自上而下 设置／路径点（点路径点会在它下面展开面板）、
	 * 右侧自上而下 定位／放大／缩小、左下比例尺（尺子上方那一行是坐标跳转）。
	 */
	private void drawUi(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		hoveredButton = buttonAt(mouseX, mouseY);
		for (int button = 0; button < BUTTON_COUNT; button++) {
			drawButton(graphics, button, button == hoveredButton, button == pressedButton);
		}
		drawWaypointPanel(graphics, mouseX, mouseY);
		// 维度 / 层级浮层压在路径点面板之上（同一块地方，同时只会开一个）
		drawDimensionPopup(graphics, mouseX, mouseY);
		drawLayerPopup(graphics, mouseX, mouseY);
		drawJumpInput(graphics, mouseX, mouseY);
		drawScaleBar(graphics);
		// 提示画在最上面：它可能压到刚展开的面板上，压在下面就会被挡掉
		drawButtonTooltip(graphics, mouseX, mouseY);
	}

	/** 按钮的提示语言键；返回 null 表示这一颗按钮<b>不显示悬停提示</b>。 */
	private static @Nullable String buttonTipKey(int button) {
		return switch (button) {
			case BUTTON_SETTINGS -> "screen.sinanmap.world_map.tip.settings";
			case BUTTON_WAYPOINTS -> "screen.sinanmap.world_map.tip.waypoints";
			case BUTTON_DIMENSION -> "screen.sinanmap.world_map.tip.dimension";
			case BUTTON_LAYER -> "screen.sinanmap.world_map.tip.layer";
			case BUTTON_LOCATE -> "screen.sinanmap.world_map.tip.locate";
			case BUTTON_ZOOM_IN -> "screen.sinanmap.world_map.tip.zoom_in";
			default -> "screen.sinanmap.world_map.tip.zoom_out";
		};
	}

	/**
	 * 悬停按钮时画一块提示板。
	 *
	 * <p><b>不用原版 {@code Tooltip}</b>（原版是紫底，与这张地图不是一套）；<b>跟着鼠标走</b>，贴到屏幕
	 * 右边/下边会自动翻到光标另一侧并钳进屏幕。
	 */
	private void drawButtonTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (hoveredButton < 0 || pressedButton == hoveredButton) {
			// 按下的那一下意图已经明确，提示先收起来，别挡着
			return;
		}
		String tipKey = buttonTipKey(hoveredButton);
		if (tipKey == null) {
			// 这颗按钮不显示悬停提示（见 buttonTipKey）
			return;
		}
		Component text = Component.translatable(tipKey);
		int pad = 3;
		int boxWidth = font.width(text) + pad * 2;
		int boxHeight = font.lineHeight + pad * 2;
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
		graphics.fill(x, y, x + boxWidth, y + boxHeight, 0xF0101014);
		graphics.text(font, text, x + pad, y + pad, 0xFFE6E6E6, false);
	}

	/**
	 * 画一张贴图里的一块（带颜色）。素材纯白、颜色由 {@code color} 乘上去，所以必须走<b>带颜色的那条
	 * {@code blit}</b>（{@link RenderPipelines#GUI_TEXTURED}）；不带颜色的 {@code blit} 只会得到一片白。
	 */
	private void drawPiece(GuiGraphicsExtractor graphics, Identifier texture,
			int textureWidth, int textureHeight,
			int u, int v, int x, int y, int w, int h, int color) {
		graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, u, v, w, h,
				textureWidth, textureHeight, color);
	}

	private void drawButton(GuiGraphicsExtractor graphics, int button, boolean hovered, boolean pressed) {
		// 不画底板：按钮就是图标本身，悬停靠图标变色表示（按下时再压暗一档）
		int color = pressed ? ICON_COLOR_PRESSED : (hovered ? ICON_COLOR_HOVER : ICON_COLOR);
		switch (button) {
			case BUTTON_DIMENSION -> drawPortalIcon(graphics, buttonX(button), buttonY(button), color);
			case BUTTON_LAYER -> drawLayersIcon(graphics, buttonX(button), buttonY(button), color);
			default -> drawIcon(graphics, buttonIcon(button),
					buttonX(button) + iconShiftX(button), buttonY(button), color);
		}
	}

	/**
	 * 几个图标的横向补偿（屏幕像素）。实测贴图里 {@code zoom_in}／{@code locate}／{@code settings} 的
	 * 墨迹中心在 x=8，而 {@code waypoint} 是 <b>x=6.5</b>——不补这一处，路径点那个会比其它三个往左偏一个多像素。
	 */
	private static float iconShiftX(int button) {
		if (button != BUTTON_WAYPOINTS) {
			return 0.0F;
		}
		// 墨迹中心从 6.5 补到贴图正中 8，再换算成屏幕像素（×BUTTON_SIZE/ICON_REGION）
		return 1.5F * BUTTON_SIZE / ICON_REGION;
	}

	/**
	 * 自绘图标"本体"的边长。图集里那几个图标是 {@link #ICON_REGION}＝17 的正方形，<b>右下 1 像素是阴影</b>、
	 * 本体只占 16/17；自绘的那两个必须按同一块区域画，否则看着"比别的图标窄一圈、整体偏右"。
	 */
	private static final int ICON_BODY = BUTTON_SIZE * 16 / ICON_REGION;

	/** 维度图标（地狱门）本体的尺寸：比 {@link #ICON_BODY}（当前 11）小一圈，内部保持 2:3。 */
	private static final int PORTAL_WIDTH = 8;
	private static final int PORTAL_HEIGHT = 10;
	private static final int PORTAL_THICKNESS = 2;

	/**
	 * 层级图标的斜二测参数：几层、每层的"东西向宽度"与"南北向进深"、以及每层往上叠多少。
	 *
	 * <p>画法就是<b>斜二测</b>：一块方板斜过来看时顶边仍然水平，侧边往左下斜——每往下一行整体左移一格，
	 * 整块因此是个平行四边形。三层叠起来就是"一摞层"。
	 *
	 * <p>整块的占地正好是图标本体：宽 {@code WIDTH + DEPTH - 1}、高 {@code DEPTH + STEP × 2}，
	 * 两个都取 {@link #ICON_BODY}，于是它和另外三个按钮的图标一样大。
	 *
	 * <p>{@code STEP} 比 {@code DEPTH} 大，两块之间因此<b>空着一行</b>：这样"三层"才分得开，
	 * 连成一片会看成一块斜的实心梯形。
	 */
	private static final int LAYER_ICON_COUNT = 3;
	private static final int LAYER_ICON_WIDTH = 9;
	private static final int LAYER_ICON_DEPTH = 3;
	private static final int LAYER_ICON_STEP = 3;

	/**
	 * 维度按钮：画一个"地狱门"——只画门框、中间留空，内部与标准地狱门一致是 <b>2:3</b>。
	 * 整体比其它图标小一圈、居中摆在图标本体区域里，并与其它自绘图标一样带一层右下投影。
	 */
	private void drawPortalIcon(GuiGraphicsExtractor graphics, int x, int y, int color) {
		// 实测整块再<b>右移一格</b>才与另外三个图标看着是一列
		int offsetX = x + (ICON_BODY - PORTAL_WIDTH) / 2 + 1;
		int offsetY = y + (ICON_BODY - PORTAL_HEIGHT) / 2;
		drawPortalFrame(graphics, offsetX + 1, offsetY + 1, ICON_SHADOW);
		drawPortalFrame(graphics, offsetX, offsetY, color);
	}

	/** 门框本体：总宽 {@value #PORTAL_WIDTH}、总高 {@value #PORTAL_HEIGHT}，框厚 {@value #PORTAL_THICKNESS}。 */
	private void drawPortalFrame(GuiGraphicsExtractor graphics, int x, int y, int color) {
		int t = PORTAL_THICKNESS;
		int left = x;
		int right = x + PORTAL_WIDTH;
		int top = y;
		int bottom = y + PORTAL_HEIGHT;
		graphics.fill(left, top, right, top + t, color);
		graphics.fill(left, bottom - t, right, bottom, color);
		graphics.fill(left, top + t, left + t, bottom - t, color);
		graphics.fill(right - t, top + t, right, bottom - t, color);
	}

	/** 层级按钮：斜二测视角下叠起来的三块板（块与块之间空一行）。 */
	private void drawLayersIcon(GuiGraphicsExtractor graphics, int x, int y, int color) {
		// 竖向居中：整摞 3 行板 + 2 行缝 = 11 行，比图标本体矮 2 行，上下各让 1 行
		int top = y + (ICON_BODY - plateStackHeight()) / 2;
		drawLayerStack(graphics, x + 1, top + 1, ICON_SHADOW);
		drawLayerStack(graphics, x, top, color);
	}

	/** 这一摞板占多高：每块 {@link #LAYER_ICON_DEPTH} 行，块与块之间空 {@link #LAYER_ICON_STEP} 行中的剩下部分。 */
	private static int plateStackHeight() {
		return LAYER_ICON_DEPTH + LAYER_ICON_STEP * (LAYER_ICON_COUNT - 1);
	}

	/** 三块板从下往上排：最上面那块落点最小（y 最小），往下每块加一个 {@link #LAYER_ICON_STEP}。 */
	private void drawLayerStack(GuiGraphicsExtractor graphics, int x, int y, int color) {
		for (int layer = 0; layer < LAYER_ICON_COUNT; layer++) {
			int top = y + (LAYER_ICON_COUNT - 1 - layer) * LAYER_ICON_STEP;
			drawPlate(graphics, x + LAYER_ICON_DEPTH - 1, top, color);
		}
	}

	/**
	 * 一块斜二测的板：顶边水平，<b>每往下一行整体左移一格</b>，画出来是个平行四边形。
	 *
	 * <p>{@code topLeftX} 是<b>顶边</b>的左边：最下面那一行会左移到 {@code topLeftX - DEPTH + 1}，
	 * 所以传进来时先加回 {@code DEPTH - 1}，整块才落在图标本体正中。
	 */
	private void drawPlate(GuiGraphicsExtractor graphics, int topLeftX, int topLeftY, int color) {
		for (int row = 0; row < LAYER_ICON_DEPTH; row++) {
			graphics.fill(topLeftX - row, topLeftY + row,
					topLeftX - row + LAYER_ICON_WIDTH, topLeftY + row + 1, color);
		}
	}

	/**
	 * 自绘图标的投影色。
	 *
	 * <p>取的就是图集里那几个图标（路径点 / 设置）阴影的<b>同一个值</b>：那两张贴图里投影像素是
	 * 半透明黑 {@code 0x96000000}，且阴影像素是黑的，乘图标色（{@code blitSprite} 会把整张 sprite
	 * 按 {@code color} 相乘）之后仍然是黑——所以悬停压暗时投影不会跟着变。自绘的三个必须照这个来，
	 * 否则一边是暗影、一边是"比本体还亮的灰"，看着不是一套图标。
	 */
	private static final int ICON_SHADOW = 0x96000000;

	/**
	 * 画一个图标，边长按 {@value #BUTTON_SIZE} 缩放到屏幕像素。
	 *
	 * <p>{@code icon} 是 GUI 图集 sprite，{@code blitSprite} 按图集记录的精灵尺寸绘制（高分辨率资源包
	 * 自动缩放）；缩放由外面的变换矩阵完成，矩阵会跟着 blit 一起生效。
	 */
	private void drawIcon(GuiGraphicsExtractor graphics, Identifier icon, float x, float y, int color) {
		float scale = BUTTON_SIZE / (float) ICON_REGION;
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(scale, scale);
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, icon, 0, 0, ICON_REGION, ICON_REGION, color);
		pose.popMatrix();
	}

	/**
	 * 左下角比例尺：从固定档位里挑一个"整距离"，让尺子长度最接近 {@value #BAR_TARGET_WIDTH} GUI 像素。
	 * 用整档位是为了报出好读的数（100 m 而不是 137 m）；长度按当前缩放换算，所以缩放变了尺子长短跟着变。
	 */
	private void drawScaleBar(GuiGraphicsExtractor graphics) {
		double blocksPerGui = blocksPerGui();
		if (!(blocksPerGui > 0)) {
			return;
		}
		double chosen = BAR_STEPS[0];
		for (double step : BAR_STEPS) {
			if (step / blocksPerGui <= BAR_TARGET_WIDTH) {
				chosen = step;
			}
		}
		int barWidth = Math.max(BAR_CAP_WIDTH * 2 + 4, (int) Math.round(chosen / blocksPerGui));
		if (chosen != lastBarDistance) {
			// 只在档位跳变时记一行：尺子"不动"这类问题，看实际数值比盯着屏幕猜快
			lastBarDistance = chosen;
			LOGGER.info("世界地图尺子 | 每 GUI 像素 {} 方块，取 {} m，长 {} px",
					String.format("%.4f", blocksPerGui), (long) chosen, barWidth);
		}
		int x = BUTTON_MARGIN;
		int y = height - BUTTON_MARGIN - BAR_HEIGHT;

		// 两端 1:1 画（竖线必须保持 1px，缩放或拉伸会糊）。中段必须照贴图里那 2px 一段反复拼，不能
		// "取一段 middle 宽的"：带颜色的 blit 只平移不缩放，取 middle 宽会把右端端头也一起取进来。
		drawPiece(graphics, BAR_TEXTURE, BAR_TEXTURE_WIDTH, BAR_TEXTURE_HEIGHT,
				BAR_LEFT_X, 0, x, y, BAR_CAP_WIDTH, BAR_HEIGHT, ICON_COLOR);
		int middle = barWidth - BAR_CAP_WIDTH * 2;
		for (int drawn = 0; drawn < middle; drawn += BAR_MID_WIDTH) {
			int piece = Math.min(BAR_MID_WIDTH, middle - drawn);
			drawPiece(graphics, BAR_TEXTURE, BAR_TEXTURE_WIDTH, BAR_TEXTURE_HEIGHT,
					BAR_MID_X, 0, x + BAR_CAP_WIDTH + drawn, y, piece, BAR_HEIGHT, ICON_COLOR);
		}
		drawPiece(graphics, BAR_TEXTURE, BAR_TEXTURE_WIDTH, BAR_TEXTURE_HEIGHT,
				BAR_RIGHT_X, 0, x + barWidth - BAR_CAP_WIDTH, y, BAR_CAP_WIDTH, BAR_HEIGHT, ICON_COLOR);

		// 距离文字不带阴影：尺子本身是几条细线，字下面再来一层灰影只会让它显得脏
		graphics.text(font, barLabel(chosen), x + barWidth + 4, y, TEXT_COLOR, false);
	}

	/** 尺子上的距离文字：一公里以上按公里写，省得写一长串零。 */
	private static String barLabel(double meters) {
		if (meters < 1000.0) {
			return (long) meters + " m";
		}
		double kilometers = meters / 1000.0;
		return kilometers == Math.floor(kilometers)
				? (long) kilometers + " km"
				: "%.1f km".formatted(kilometers);
	}

	/**
	 * 某个按钮的左上角（GUI 像素）。左上角一列自上而下：设置 / 路径点 / 维度 / 层级；右下角一列自下而上：
	 * 定位 / 缩小 / 放大。
	 */
	private int buttonX(int button) {
		if (isTopLeft(button)) {
			return BUTTON_MARGIN;
		}
		return width - BUTTON_MARGIN - BUTTON_SIZE;
	}

	private int buttonY(int button) {
		if (isTopLeft(button)) {
			// 左上角那一列：设置 / 路径点 / 维度 / 层级，自上而下排
			return BUTTON_MARGIN + button * (BUTTON_SIZE + BUTTON_GAP);
		}
		return height - BUTTON_MARGIN - BUTTON_SIZE - buttonRow(button) * (BUTTON_SIZE + BUTTON_GAP);
	}

	private static Identifier buttonIcon(int button) {
		return switch (button) {
			case BUTTON_SETTINGS -> ICON_SETTINGS;
			case BUTTON_WAYPOINTS -> ICON_WAYPOINT;
			case BUTTON_LOCATE -> ICON_LOCATE;
			case BUTTON_ZOOM_IN -> ICON_ZOOM_IN;
			case BUTTON_ZOOM_OUT -> ICON_ZOOM_OUT;
			// 维度与层级这两个按钮的图标是<b>自己画</b>的（图集里没有现成的），见 #drawButton
			default -> null;
		};
	}

	private int buttonAt(double mouseX, double mouseY) {
		for (int button = 0; button < BUTTON_COUNT; button++) {
			int x = buttonX(button);
			int y = buttonY(button);
			if (mouseX >= x && mouseX < x + BUTTON_SIZE && mouseY >= y && mouseY < y + BUTTON_SIZE) {
				return button;
			}
		}
		return -1;
	}

	/** 按钮动作。 */
	private void activate(int button) {
		switch (button) {
			case BUTTON_LOCATE -> centerOnPlayer();
			// 走同一条缩放路径：它已经处理了锚点、上下限与"缩放打断惯性"
			case BUTTON_ZOOM_IN -> stepZoomAt(width / 2.0, height / 2.0, 1.0 / ZOOM_STEP);
			case BUTTON_ZOOM_OUT -> stepZoomAt(width / 2.0, height / 2.0, ZOOM_STEP);
			// 路径点：在按钮下面展开/收起这份面板。这里不换界面——
			// 面板本来就是"一边看着地图一边挑一个地方过去"，换成全屏列表反而把地图盖掉了
			case BUTTON_WAYPOINTS -> togglePanel();
			// 维度 / 层级：各自在自己下面展开一个小浮层
			case BUTTON_DIMENSION -> toggleDimensionPopup();
			case BUTTON_LAYER -> toggleLayerPopup();
			// 设置：切到设置界面（关掉时回到这张地图）
			case BUTTON_SETTINGS -> Minecraft.getInstance().setScreen(new MapSettingsScreen(this, minimap, settings));
		}
	}

	/** 定位自身：把视图中心平滑挪到玩家身上，见 {@link #animateCenterTo}。 */
	private void centerOnPlayer() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return;
		}
		animateCenterTo(player.getX(), player.getZ());
	}

	/**
	 * 把视图中心平滑挪到某个世界坐标（"定位自身"、跳到某条路径点、跳到某个坐标都走这一条）。
	 *
	 * <p>必须改<b>视口中心</b>而不是锚点。视图有两种表示：缩放时会记一个"锚点 + 锚点落在哪个屏幕位置"，
	 * 平移/缩放结束后则回到"视口中心"。只写锚点的话，处于"中心"表示时这一下就完全不起作用——表现就是
	 * "这个按钮有时候点得动、有时候点不动"。
	 *
	 * <p>这里只<b>记下终点并开闸</b>：位移由 {@link #tickCenterAnimation} 每帧按剩余距离收敛，所以没有
	 * "时长"这个概念，也就不需要按距离算时长、不需要上下限。
	 */
	private void animateCenterTo(double worldX, double worldZ) {
		// 当前画面可能是按锚点摆的，先换算成视口中心，过场才有正确的起点（见 tickCenterAnimation）
		reanchorViewCenter();
		centerAnimToX = worldX;
		centerAnimToZ = worldZ;
		centerAnimActive = true;
		// 和缩放一样打断惯性，否则会一边往目标身上对、一边继续滑
		inertiaX = 0.0;
		inertiaZ = 0.0;
		flushDragVelocity();
	}

	/**
	 * 推进"定位自身"的过场。
	 *
	 * <p>每帧把<b>剩余距离</b>乘上 {@link #CENTER_ANIM_DECAY} 的"这一帧折合的 1/60 秒数"次方，也就是
	 * 指数逼近：起步那一帧最快、越近越慢。帧率变了用 {@code pow} 换算，30fps 与 240fps 观感一样。
	 *
	 * <p>用乘方而不是"线性插值 progress"：指数收敛在<b>任何时刻</b>都只依赖"现在离目标多远"，所以中途
	 * 再叫它去别处时，直接从当前位置接着收，不会顿一下或跳一下。
	 */
	private void tickCenterAnimation(double deltaMillis) {
		if (!centerAnimActive) {
			return;
		}
		// 起始点每帧现取：过场期间"手动接管"会把过场关掉，所以这里拿到的就是过场自己的当前位置
		double offX = centerAnimToX - viewCenterX();
		double offZ = centerAnimToZ - viewCenterZ();
		if (Math.hypot(offX, offZ) <= CENTER_ANIM_SNAP_BLOCKS) {
			centerAnimActive = false;
			viewCenterOn(centerAnimToX, centerAnimToZ);
			return;
		}
		// 这一帧折合几个 1/60 秒（掉帧时大于 1，位置按同样的时间比例多收一点；上限防止卡顿后瞬移）
		double frames = Math.clamp(deltaMillis, 0.0, 100.0) / (1000.0 / 60.0);
		double decay = Math.pow(CENTER_ANIM_DECAY, frames);
		viewCenterOn(centerAnimToX - offX * decay, centerAnimToZ - offZ * decay);
	}

	// ================================================================ 路径点面板

	/** 面板上的这一下点击归谁；见 {@link #handleOverlayClick}。 */
	private boolean handleOverlayClick(MouseButtonEvent event, boolean doubleClick) {
		double mouseX = event.x();
		double mouseY = event.y();
		// 输入框自己先拿这一下（聚焦、把光标放到点的地方都是它的事）
		if (searchBox != null && searchBox.isMouseOver(mouseX, mouseY)) {
			focusField(searchBox);
			searchBox.mouseClicked(mouseX, mouseY);
			return true;
		}
		if (jumpBox != null && jumpBox.isMouseOver(mouseX, mouseY)) {
			focusField(jumpBox);
			jumpBox.mouseClicked(mouseX, mouseY);
			return true;
		}
		if (!jumpOpen && jumpHit(mouseX, mouseY)) {
			blurFields();
			openJump();
			return true;
		}
		if (panelDimensionHit(mouseX, mouseY)) {
			// 维度切换那一行：点一下循环到下一个维度（与列表行是两回事，先于下面的通用面板处理）
			blurFields();
			cyclePanelDimension();
			return true;
		}
		if (panelContains(mouseX, mouseY)) {
			blurFields();
			int index = panelRowAt(mouseY);
			if (index >= 0) {
				goToWaypoint(panelRows.get(index));
			}
			// 面板上（含标题与内边距那一带）的点击一律吃掉：落到底下就变成拖动地图了
			return true;
		}
		// 点到别处：收起跳转框、输入框也一并失焦——"点别处"就是把键盘交回地图
		blurFields();
		if (jumpOpen) {
			closeJump();
		}
		// 面板不跟着关：它钉在左上角，点地图看坐标、拖地图时不该把它收走
		// （要收起就再点一次那个按钮，或者按 ESC）
		return false;
	}

	/** 把焦点给其中一个输入框（其余自动失焦）。 */
	private void focusField(SinanTextField field) {
		if (searchBox != null) {
			searchBox.setFocused(searchBox == field);
		}
		if (jumpBox != null) {
			jumpBox.setFocused(jumpBox == field);
		}
		if (dimensionSearchBox != null) {
			dimensionSearchBox.setFocused(dimensionSearchBox == field);
		}
		if (layerBox != null) {
			layerBox.setFocused(layerBox == field);
		}
	}

	/** 四个输入框都失焦：点到面板 / 列表 / 地图上时用，让方向键重新归地图。 */
	private void blurFields() {
		if (searchBox != null) {
			searchBox.setFocused(false);
		}
		if (jumpBox != null) {
			jumpBox.setFocused(false);
		}
		if (dimensionSearchBox != null) {
			dimensionSearchBox.setFocused(false);
		}
		if (layerBox != null) {
			applyLayerInput();
			layerBox.setFocused(false);
		}
	}

	/** 展开 / 收起面板。 */
	private void togglePanel() {
		if (panelOpen) {
			closePanel();
		} else {
			openPanel();
		}
	}

	private void openPanel() {
		panelOpen = true;
		// 路径点面板与维度 / 层级浮层占的是同一块地方，开一个就收起另外的
		closePopups();
		panelOpenedNanos = System.nanoTime();
		panelScroll = 0;
		// 搜索词跟着搜索框一起从头开始：框是新建的（空的），滤词要是留着上一次的，
		// 就会"框里明明没写什么，列表却是筛过的"
		panelQuery = "";
		ensureSearchBox();
	}

	/** 收起面板：连它的搜索框一起撤掉（输入框还留着就会继续吃键盘）。 */
	private void closePanel() {
		searchBox = null;
		panelOpen = false;
		panelScroll = 0;
		panelQuery = "";
		// 维度选择是面板里的临时状态：收起面板就回到"跟随当前显示维度"
		panelDimension = null;
	}

	/** 建（或重新摆放）面板顶部的搜索框。 */
	private void ensureSearchBox() {
		if (searchBox == null) {
			searchBox = new SinanTextField(font);
			searchBox.setMaxLength(Waypoint.MAX_NAME_LENGTH);
			searchBox.setHint(Component.translatable("screen.sinanmap.world_map.waypoint_search"));
			// 边打字边筛：不用按回车，列表就在下面看着
			searchBox.setResponder(text -> {
				panelQuery = text.strip().toLowerCase(Locale.ROOT);
				refreshPanelRows();
			});
		}
		// 宽度与下面的列表行对齐（列表行的高亮是 [x+1, x+PANEL_WIDTH-1)）：搜索框要是再往
		// 里缩一个内边距，就会比列表短一截——"长度没有和输入框对齐"就是这里
		searchBox.setBounds(panelX() + 1, panelSearchY(), PANEL_WIDTH - 2, PANEL_SEARCH_HEIGHT);
	}

	private int panelX() {
		// 左上的按钮列现在有四颗（设置/路径点/维度/层级），面板不能再从屏幕最左边起：
		// 那样会把下面两颗按钮压在底下、点不到。整块右移到按钮列右边。
		return BUTTON_MARGIN + BUTTON_SIZE + BUTTON_GAP;
	}

	// ================================================================ 维度 / 层级浮层

	/** 两个浮层都从按钮列右边起画（与路径点面板同一条左边界）。 */
	private int popupX() {
		return BUTTON_MARGIN + BUTTON_SIZE + BUTTON_GAP;
	}

	/**
	 * 三个浮层（路径点面板 / 维度列表 / 层级滑轨）<b>共用的顶边</b>：统一从路径点按钮那一行起，
	 * 它们在左边是同一条竖列，右边就该是同一条横线。
	 */
	private int popupTop() {
		return buttonY(BUTTON_WAYPOINTS);
	}

	/**
	 * 当前实际显示的维度。没切过时就是玩家所在的维度——传送、新建、分享这些老路径读到的还是原来那个值。
	 */
	private String currentDimension() {
		if (displayDimension != null) {
			return displayDimension;
		}
		ClientLevel level = Minecraft.getInstance().level;
		return level == null ? "" : level.dimension().identifier().toString();
	}

	private int dimensionPopupY() {
		// 与左边那颗"维度"按钮同一行（浮层挂在它自己那颗按钮上）
		return buttonY(BUTTON_DIMENSION);
	}

	private int dimensionTitleY() {
		return dimensionPopupY() + PANEL_PADDING;
	}

	private int dimensionSearchY() {
		return dimensionTitleY() + PANEL_TITLE_HEIGHT + 2;
	}

	private int dimensionListTop() {
		return dimensionSearchY() + PANEL_SEARCH_HEIGHT + 3;
	}

	/** 列表高度按"筛完之后还剩几个维度"算：搜索时面板跟着缩短，不会留一片空白。 */
	private int dimensionPopupHeight() {
		int rows = Math.max(1, filteredDimensions().size());
		return dimensionListTop() - dimensionPopupY() + rows * PANEL_ROW_HEIGHT + PANEL_PADDING;
	}

	private boolean dimensionPopupContains(double mouseX, double mouseY) {
		return dimensionOpen && mouseX >= popupX() && mouseX < popupX() + DIMENSION_POPUP_WIDTH
				&& mouseY >= dimensionPopupY() && mouseY < dimensionPopupY() + dimensionPopupHeight();
	}

	/**
	 * 可切换的维度：<b>服务器有这个维度、而且本地存过它的地图数据</b>（也就是"去过"）。候选来自客户端
	 * 已知的维度集合，但注册表里也包括没去过的维度、切过去只有一片空白，所以再用"本地有没有数据目录"筛
	 * 一遍（走过一步才会落盘）。当前所在的维度无条件列出。
	 */
	private List<String> availableDimensions() {
		Minecraft minecraft = Minecraft.getInstance();
		ClientPacketListener connection = minecraft.getConnection();
		String current = currentDimension();
		if (connection == null) {
			return current.isEmpty() ? List.of() : List.of(current);
		}
		Path root = MapStorage.currentRoot(minecraft);
		List<String> result = new ArrayList<>();
		for (ResourceKey<Level> key : connection.levels()) {
			String dimension = key.identifier().toString();
			if (dimension.equals(current) || hasMapData(root, dimension)) {
				result.add(dimension);
			}
		}
		// 原版三个排前面（主世界 → 下界 → 末地），其余按 id 排：顺序稳定，找起来不用来回扫
		result.sort(Comparator.comparingInt(WorldMapScreen::dimensionOrder)
				.thenComparing(Comparator.naturalOrder()));
		return result;
	}

	/** 这个维度本地存过数据吗（目录存在就说明走过）。 */
	private static boolean hasMapData(@Nullable Path root, String dimension) {
		if (root == null) {
			return false;
		}
		String key = dimension.replace(':', '_').replace('/', '_');
		return Files.isDirectory(root.resolve(key));
	}

	/** 原版三维度的排序权重；其它维度（含模组维度）排在它们后面。 */
	private static int dimensionOrder(String dimension) {
		if (Dimensions.OVERWORLD.equals(dimension)) {
			return 0;
		}
		if (Dimensions.NETHER.equals(dimension)) {
			return 1;
		}
		if (Dimensions.END.equals(dimension)) {
			return 2;
		}
		return 3;
	}

	/** 搜索词筛过的维度列表（做法与路径点面板一致：边打边筛）。 */
	private List<String> filteredDimensions() {
		List<String> all = availableDimensions();
		if (dimensionQuery.isEmpty()) {
			return all;
		}
		List<String> result = new ArrayList<>();
		for (String dimension : all) {
			if (dimensionLabel(dimension).getString().toLowerCase(Locale.ROOT).contains(dimensionQuery)) {
				result.add(dimension);
			}
		}
		return result;
	}

	/** 建（或重新摆放）维度列表顶部的搜索框。 */
	private void ensureDimensionSearchBox() {
		if (dimensionSearchBox == null) {
			dimensionSearchBox = new SinanTextField(font);
			dimensionSearchBox.setMaxLength(24);
			dimensionSearchBox.setHint(
					Component.translatable("screen.sinanmap.world_map.dimension_search"));
			dimensionSearchBox.setResponder(text -> dimensionQuery = text.strip().toLowerCase(Locale.ROOT));
		}
		// 淡入进度不在这里设：这里只在打开时跑一次，那时进度还是 0；每帧的同步见 #drawDimensionPopup
		dimensionSearchBox.setBounds(popupX() + 1, dimensionSearchY(),
				DIMENSION_POPUP_WIDTH - 2, PANEL_SEARCH_HEIGHT);
	}

	private int layerPopupY() {
		// 与左边那颗"层级"按钮同一行（与左侧按钮对齐）；窗口矮到装不下时钳进屏幕
		return Math.max(4, Math.min(buttonY(BUTTON_LAYER), height - LAYER_POPUP_HEIGHT - 4));
	}

	/** 滑轨左右两端（与 {@link SinanStyle#drawSlider} 的把手行程对齐，鼠标位置与把手才不会错位）。 */
	private int layerTrackLeft() {
		return popupX() + 2;
	}

	private int layerTrackRight() {
		return popupX() + LAYER_SLIDER_WIDTH - 2 - SinanStyle.HANDLE_WIDTH;
	}

	/** 层级输入框左边那条边（与浮层同宽）。 */
	private int layerInputX() {
		return popupX();
	}

	/** 层级输入框的顶边：滑轨下方。 */
	private int layerInputY() {
		return layerPopupY() + LAYER_SLIDER_HEIGHT + LAYER_INPUT_GAP;
	}

	/** 整个层级浮层（滑条 + 输入框）的命中。 */
	private boolean layerPopupContains(double mouseX, double mouseY) {
		return layerOpen && mouseX >= popupX() && mouseX < popupX() + LAYER_POPUP_WIDTH
				&& mouseY >= layerPopupY() && mouseY < layerPopupY() + LAYER_POPUP_HEIGHT;
	}

	/** 只有滑条那一段（不含下方的输入框）。 */
	private boolean layerTrackContains(double mouseX, double mouseY) {
		return layerOpen && mouseX >= popupX() && mouseX < popupX() + LAYER_SLIDER_WIDTH
				&& mouseY >= layerPopupY() && mouseY < layerPopupY() + LAYER_SLIDER_HEIGHT;
	}

	/** 输入框那一段的命中。 */
	private boolean layerInputContains(double mouseX, double mouseY) {
		return layerOpen && mouseX >= layerInputX() && mouseX < layerInputX() + LAYER_INPUT_WIDTH
				&& mouseY >= layerInputY() && mouseY < layerInputY() + LAYER_INPUT_HEIGHT;
	}

	/** 两个浮层任意一个开着（命中判定与拖动捕获共用）。 */
	private boolean anyPopupContains(double mouseX, double mouseY) {
		return dimensionPopupContains(mouseX, mouseY) || layerPopupContains(mouseX, mouseY);
	}

	private void toggleDimensionPopup() {
		dimensionOpen = !dimensionOpen;
		layerOpen = false;
		layerDragging = false;
		// 三个浮层都从按钮列右边这块地方展开，同时开着只会互相压住
		closePanel();
		if (dimensionOpen) {
			dimensionOpenedNanos = System.nanoTime();
			// 每次打开都从空搜索、空列表开始；不自动聚焦搜索框——那样一打开就进输入状态，
			// 方向键、ESC 都被它吃掉，想直接点维度反而要先在空白处点一下
			dimensionQuery = "";
			ensureDimensionSearchBox();
		} else {
			dimensionSearchBox = null;
		}
	}

	private void toggleLayerPopup() {
		layerOpen = !layerOpen;
		// 收掉维度浮层（它那份搜索框也一起撤掉），但别用 closePopups——那会把刚打开的自己也关掉
		dimensionOpen = false;
		dimensionSearchBox = null;
		dimensionQuery = "";
		layerDragging = false;
		closePanel();
		if (layerOpen) {
			layerOpenedNanos = System.nanoTime();
			ensureLayerBox();
		} else {
			layerBox = null;
		}
	}

	/** 建（或重新摆放）层级浮层下方那个手输 Y 的框。 */
	private void ensureLayerBox() {
		if (layerBox == null) {
			layerBox = new SinanTextField(font);
			layerBox.setMaxLength(5);
			// 自动时空着，占位文字就是"自动"；手动时里面是具体 Y
			layerBox.setHint(Component.translatable("screen.sinanmap.world_map.layer_auto"));
		}
		layerBox.setFade(popupFade(layerOpenedNanos));
		layerBox.setBounds(layerInputX(), layerInputY(), LAYER_INPUT_WIDTH, LAYER_INPUT_HEIGHT);
	}

	/** 把层级输入框里的内容变成手动层级：空 / 非法当成"自动"，其余钳进合法范围。 */
	private void applyLayerInput() {
		if (layerBox == null) {
			return;
		}
		String text = layerBox.getValue().strip();
		if (!text.isEmpty()) {
			try {
				manualLayerY = Math.clamp(Integer.parseInt(text), LAYER_MIN, LAYER_MAX);
				return;
			} catch (NumberFormatException ignored) {
				// 不是数字：退回自动，框里的内容下一帧会被同步回去
			}
		}
		manualLayerY = AUTO_LAYER;
	}

	private void closePopups() {
		dimensionOpen = false;
		layerOpen = false;
		layerDragging = false;
		dimensionSearchBox = null;
		dimensionQuery = "";
		layerBox = null;
	}

	/**
	 * 切换地图显示的维度。<b>坐标按维度比例换算</b>（主世界 ↔ 下界 8:1，末地与模组维度 1:1）：在下界
	 * 看着某个点切到主世界，视图中心正好落在"从那个门出来"的位置。
	 */
	private void switchDimension(String dimension) {
		String from = currentDimension();
		if (dimension.equals(from)) {
			closePopups();
			return;
		}
		double ratio = Dimensions.overworldScale(from) / Dimensions.overworldScale(dimension);
		reanchorViewCenter();
		double x = viewCenterX() * ratio;
		double z = viewCenterZ() * ratio;

		displayDimension = dimension;
		// 跟着玩家那个维度时传 null：让数据源回到"正常模式"（实时采样 + 落盘）
		ClientLevel level = Minecraft.getInstance().level;
		String playerDimension = level == null ? "" : level.dimension().identifier().toString();
		colorProvider.setViewDimension(dimension.equals(playerDimension) ? null : dimension);
		// 瓦片按"层 + 页坐标"作键、不带维度：不整块丢掉就会把上一个维度的地形接着画上来
		tiles.reset();
		// "这一页可能有数据 / 没有数据"的结论同样按世界坐标作键、不带维度：一起作废，否则切过去会
		// 沿用上一个维度的判断——该建的页被跳过（不加载），或拿旧结论去建新维度的页
		seenPages.clear();
		coarseNoData.clear();
		coarseNoDataClearedNanos = 0L;
		lastLayer = Integer.MAX_VALUE;
		centerAnimActive = false;
		viewCenterOn(x, z);
		closePopups();
		LOGGER.info("地图显示维度切换 | {} → {} | 视图中心 {} , {}", from, dimension,
				(int) x, (int) z);
	}

	/**
	 * 维度列表：与路径点面板<b>完全同一套排版</b>——没有底色（地形透出来），上面一行小标题 + 搜索框，
	 * 下面是可选维度；行高亮与文字左边界都用 {@link #drawPopupRow}，两个列表逐像素对齐。
	 */
	private void drawDimensionPopup(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (!dimensionOpen) {
			return;
		}
		float fade = popupFade(dimensionOpenedNanos);
		int x = popupX();
		graphics.text(font, Component.translatable("screen.sinanmap.world_map.dimension"),
				x + PANEL_PADDING, dimensionTitleY(), alphaColor(fade, 0xFFCCCCCC), false);
		if (dimensionSearchBox != null) {
			// 淡入进度<b>每帧</b>都要同步：搜索框只在打开那一瞬间建出来，那时进度还是 0，
			// 只在那时设一次的话它会永远停在"全透明"——表现就是"别的都淡进来了，搜索框不见了"
			dimensionSearchBox.setFade(fade);
			dimensionSearchBox.render(graphics);
		}
		String current = currentDimension();
		int rowY = dimensionListTop();
		for (String dimension : filteredDimensions()) {
			boolean selected = dimension.equals(current);
			boolean hovered = mouseX >= x && mouseX < x + DIMENSION_POPUP_WIDTH
					&& mouseY >= rowY && mouseY < rowY + PANEL_ROW_HEIGHT;
			drawPopupRow(graphics, x, DIMENSION_POPUP_WIDTH, rowY, selected, hovered,
					dimensionLabel(dimension), selected ? 0xFFFFFFFF : 0xFFC8C8C8, fade);
			rowY += PANEL_ROW_HEIGHT;
		}
	}

	/**
	 * 浮层列表里的一行：选中 / 悬停的高亮条 + 左内边距对齐的一行文字。路径点面板与维度列表共用这一段，
	 * 排版就不会各写一套、越走越偏。
	 *
	 * @param fade 展开过场的淡入进度（1 = 完全显出来）；高亮与文字都按它压 alpha
	 */
	private void drawPopupRow(GuiGraphicsExtractor graphics, int x, int width, int rowY,
			boolean selected, boolean hovered, Component text, int color, float fade) {
		if (selected) {
			graphics.fill(x + 1, rowY, x + width - 1, rowY + PANEL_ROW_HEIGHT,
					alphaColor(fade, 0x40FFFFFF));
		} else if (hovered) {
			graphics.fill(x + 1, rowY, x + width - 1, rowY + PANEL_ROW_HEIGHT,
					alphaColor(fade, 0x20FFFFFF));
		}
		graphics.text(font, text, x + PANEL_PADDING,
				rowY + (PANEL_ROW_HEIGHT - font.lineHeight + 1) / 2, alphaColor(fade, color), false);
	}

	/**
	 * 层级浮层：上面一条自绘滑条（与设置界面同一份 {@link SinanStyle#drawSlider}），下面一个手输 Y 的
	 * 输入框（与跳转框同一套样式）。滑条最左端就是"自动"，往右依次升高。
	 */
	private void drawLayerPopup(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (!layerOpen) {
			return;
		}
		float fade = popupFade(layerOpenedNanos);
		int x = popupX();
		int y = layerPopupY();
		boolean hovered = layerTrackContains(mouseX, mouseY);

		SinanStyle.drawSlider(graphics, x, y, LAYER_SLIDER_WIDTH, LAYER_SLIDER_HEIGHT,
				layerSliderValue(), true, hovered || layerDragging, fade);

		Component text = Component.translatable("screen.sinanmap.world_map.tip.layer_short",
				manualLayerY == AUTO_LAYER ? Component.translatable("screen.sinanmap.world_map.layer_auto")
						: Component.literal(Integer.toString(manualLayerY)));
		graphics.centeredText(font, text, x + LAYER_SLIDER_WIDTH / 2,
				y + (LAYER_SLIDER_HEIGHT - font.lineHeight + 1) / 2, alphaColor(fade, 0xFFFFFFFF));

		// 输入框：没在编辑时始终跟着当前层级走，编辑时就别抢玩家的输入
		ensureLayerBox();
		if (!layerBox.isFocused()) {
			layerBox.setValue(manualLayerY == AUTO_LAYER ? "" : Integer.toString(manualLayerY));
		}
		layerBox.render(graphics);
	}

	/** 滑条该画在 0~1 的哪个位置：自动 = 最左，其余按 Y 在合法区间里的比例。 */
	private double layerSliderValue() {
		if (manualLayerY == AUTO_LAYER) {
			return 0.0;
		}
		int clamp = Math.clamp(manualLayerY, LAYER_MIN, LAYER_MAX);
		return (clamp - LAYER_MIN) / (double) (LAYER_MAX - LAYER_MIN);
	}

	/** 把滑轨上的横向位置换算成层级；落在最左端就是"自动"。 */
	private void setLayerFromMouse(double mouseX) {
		int left = layerTrackLeft();
		int right = layerTrackRight();
		double t = Math.clamp((mouseX - left) / Math.max(1.0, right - left), 0.0, 1.0);
		if (t <= 0.0) {
			manualLayerY = AUTO_LAYER;
			return;
		}
		manualLayerY = (int) Math.round(LAYER_MIN + t * (LAYER_MAX - LAYER_MIN));
	}

	/** 维度 id → 界面上的名字（原版三维度翻译，别的直接写 id）。 */
	private static Component dimensionLabel(String dimension) {
		String key = Dimensions.translationKey(dimension);
		return key == null ? Component.literal(dimension) : Component.translatable(key);
	}

	/** 维度 / 层级浮层上的点击。 */
	private boolean handlePopupClick(MouseButtonEvent event) {
		double mouseX = event.x();
		double mouseY = event.y();
		// 搜索框自己先拿这一下（聚焦、把光标放到点的地方都是它的事）
		if (dimensionOpen && dimensionSearchBox != null && dimensionSearchBox.isMouseOver(mouseX, mouseY)) {
			focusField(dimensionSearchBox);
			dimensionSearchBox.mouseClicked(mouseX, mouseY);
			return true;
		}
		if (dimensionPopupContains(mouseX, mouseY)) {
			blurFields();
			int index = (int) ((mouseY - dimensionListTop()) / PANEL_ROW_HEIGHT);
			List<String> dimensions = filteredDimensions();
			if (index >= 0 && index < dimensions.size() && mouseY >= dimensionListTop()) {
				switchDimension(dimensions.get(index));
			}
			// 浮层上的点击一律吃掉（标题与搜索框那两条也算），不再落到地图上
			return true;
		}
		if (layerInputContains(mouseX, mouseY)) {
			// 右侧输入框自己先拿这一下（聚焦、把光标放到点的地方都是它的事）
			ensureLayerBox();
			layerBox.mouseClicked(mouseX, mouseY);
			focusField(layerBox);
			return true;
		}
		if (layerPopupContains(mouseX, mouseY)) {
			blurFields();
			if (layerTrackContains(mouseX, mouseY)) {
				layerDragging = true;
				setLayerFromMouse(mouseX);
			}
			return true;
		}
		if (dimensionOpen || layerOpen) {
			// 点在浮层外面：收起浮层，这一下不再做别的事
			closePopups();
			return true;
		}
		return false;
	}

	/**
	 * 面板顶：与另外两个浮层共用同一条顶边（见 {@link #popupTop()}）。
	 */
	private int panelTop() {
		return popupTop();
	}

	private int panelTitleY() {
		return panelTop() + PANEL_PADDING;
	}

	private int panelSearchY() {
		return panelTitleY() + PANEL_TITLE_HEIGHT + 2;
	}

	private int panelListTop() {
		// 搜索框与列表之间插了一行"维度切换"（见 panelDimensionY），列表顺次下移
		return panelDimensionY() + PANEL_DIMENSION_HEIGHT + 3;
	}

	/** 面板维度切换那一行的纵坐标：夹在搜索框与列表之间。 */
	private int panelDimensionY() {
		return panelSearchY() + PANEL_SEARCH_HEIGHT + 2;
	}

	/** 维度切换那一行的高度：与列表行同高，绘制、点击、悬停判定共用它。 */
	private static final int PANEL_DIMENSION_HEIGHT = PANEL_ROW_HEIGHT;

	/** 光标是不是落在"维度切换"那一行上。 */
	private boolean panelDimensionHit(double mouseX, double mouseY) {
		return panelOpen && mouseX >= panelX() && mouseX < panelX() + PANEL_WIDTH
				&& mouseY >= panelDimensionY() && mouseY < panelDimensionY() + PANEL_DIMENSION_HEIGHT;
	}

	/** 面板最多能下到哪：给左下角那条尺子（以及它上面的跳转按钮）让出位置。 */
	private int panelListBottomLimit() {
		return jumpY() - 6;
	}

	/** 列表能显示几行：屏幕不够高就少显示几行，剩下的靠滚动。 */
	private int panelRowsVisible() {
		int available = panelListBottomLimit() - panelListTop();
		return (int) Math.clamp(available / PANEL_ROW_HEIGHT, 1, PANEL_MAX_ROWS);
	}

	private int panelBottom() {
		int wanted = panelListTop() + panelRowsVisible() * PANEL_ROW_HEIGHT + PANEL_PADDING;
		// 再挤也至少留一行：否则"列表下沿"会跑到"列表上沿"上面去，裁剪框会反过来
		return Math.max(panelListTop() + PANEL_ROW_HEIGHT, Math.min(wanted, panelListBottomLimit()));
	}

	private boolean panelContains(double mouseX, double mouseY) {
		return panelOpen && mouseX >= panelX() && mouseX < panelX() + PANEL_WIDTH
				&& mouseY >= panelTop() && mouseY < panelBottom();
	}

	/**
	 * 光标是不是落在<b>盖在地图之上的浮层</b>上：路径点面板（含搜索框）与刻度尺上那一行跳转。这只是
	 * "这一下点击归谁"的粗略划分，用在按下时记一次（见 {@link #overlayDragCaptured}）。
	 */
	private boolean overlayContains(double mouseX, double mouseY) {
		if (searchBox != null && searchBox.isMouseOver(mouseX, mouseY)) {
			return true;
		}
		if (jumpBox != null && jumpBox.isMouseOver(mouseX, mouseY)) {
			return true;
		}
		if (dimensionSearchBox != null && dimensionSearchBox.isMouseOver(mouseX, mouseY)) {
			return true;
		}
		if (!jumpOpen && jumpHit(mouseX, mouseY)) {
			return true;
		}
		return panelContains(mouseX, mouseY) || anyPopupContains(mouseX, mouseY);
	}

	/** 列表最多能滚多少像素（内容不足一屏时是 0，滚动条也就不画）。 */
	private int panelMaxScroll() {
		int content = panelRows.size() * PANEL_ROW_HEIGHT;
		return Math.max(0, content - (panelBottom() - panelListTop()));
	}

	/** 光标在第几行上；不在列表里返回 -1。 */
	private int panelRowAt(double mouseY) {
		if (mouseY < panelListTop() || mouseY >= panelBottom()) {
			return -1;
		}
		int index = (int) ((mouseY - panelListTop() + panelScroll) / PANEL_ROW_HEIGHT);
		return index >= 0 && index < panelRows.size() ? index : -1;
	}

	/**
	 * 重算列表：{@link #panelDimensionId()} 那个维度（默认就是当前显示维度）的路径点，按搜索词
	 * （大小写不敏感）筛过、按离玩家远近排。只在"依据变了"（清单 / 搜索词 / 维度）时重排：
	 * 绘制每帧都会问一次，而路径点很少变，每帧重排是白花时间。
	 */
	private void refreshPanelRows() {
		Minecraft minecraft = Minecraft.getInstance();
		WaypointStore store = WaypointManager.instance().store(minecraft);
		String dimension = panelDimensionId();
		if (store.revision() == panelRevision && panelQuery.equals(panelBuiltQuery)
				&& dimension.equals(panelBuiltDimension)) {
			return;
		}
		panelRevision = store.revision();
		panelBuiltQuery = panelQuery;
		panelBuiltDimension = dimension;

		List<Waypoint> matching = new ArrayList<>();
		for (Waypoint waypoint : store.inDimension(dimension)) {
			if (!panelQuery.isEmpty()
					&& !waypoint.name().toLowerCase(Locale.ROOT).contains(panelQuery)) {
				continue;
			}
			matching.add(waypoint);
		}
		LocalPlayer player = minecraft.player;
		if (player == null) {
			matching.sort((first, second) -> first.name().compareTo(second.name()));
		} else {
			// 距离按"换算到当前地图坐标系之后"的位置算：别的维度的路径点才落在与本图同一个尺度上
			matching.sort((first, second) -> Double.compare(
					Math.hypot(displayWaypointX(first) - player.getX(), displayWaypointZ(first) - player.getZ()),
					Math.hypot(displayWaypointX(second) - player.getX(), displayWaypointZ(second) - player.getZ())));
		}
		panelRows = List.copyOf(matching);
		panelScroll = (int) Math.clamp(panelScroll, 0, panelMaxScroll());
	}

	/**
	 * 面板维度切换可选的维度：当前显示维度排第一，其余是"存过路径点的维度"。
	 *
	 * <p>候选刻意取"有路径点的维度"而不是地图按钮那套可切换维度：这个控件就是给路径点列表用的，
	 * 没有路径点的维度切过去只会看到空列表。排序沿用维度按钮那套（原版三维度优先、其余按 id）。
	 */
	private List<String> panelDimensionOptions() {
		Minecraft minecraft = Minecraft.getInstance();
		String current = currentDimension();
		List<String> others = new ArrayList<>();
		for (Waypoint waypoint : WaypointManager.instance().store(minecraft).view()) {
			String dimension = waypoint.pos().dimension();
			if (!dimension.equals(current) && !others.contains(dimension)) {
				others.add(dimension);
			}
		}
		others.sort(Comparator.comparingInt(WorldMapScreen::dimensionOrder)
				.thenComparing(Comparator.naturalOrder()));
		List<String> result = new ArrayList<>(others.size() + 1);
		result.add(current);
		result.addAll(others);
		return result;
	}

	/** 点一下维度切换：在可选维度里循环到下一个；只有当前维度可选时什么都不做。 */
	private void cyclePanelDimension() {
		List<String> options = panelDimensionOptions();
		if (options.size() <= 1) {
			return;
		}
		int index = options.indexOf(panelDimensionId());
		String next = options.get((index + 1) % options.size());
		// 回到"当前显示维度"时清空成 null：之后地图维度按钮一换，面板也跟着换
		panelDimension = next.equals(currentDimension()) ? null : next;
		panelScroll = 0;
		refreshPanelRows();
	}

	/** 点到列表里的某一条：选中它，并把地图<b>平滑</b>挪到它上面（不是瞬移）。 */
	private void goToWaypoint(Waypoint waypoint) {
		WaypointManager.instance().select(waypoint.id());
		// 别的维度的路径点要换算成本地图坐标再居中（地狱 ↔ 主世界 1:8），否则会跳到不相干的地方
		animateCenterTo(displayWaypointX(waypoint), displayWaypointZ(waypoint));
	}

	/**
	 * 画路径点面板：标题 + 搜索框 + 一行"维度切换" + 路径点列表。展开时整块淡入、高度从上往下长出来
	 * （{@link #PANEL_APPEAR_NANOS}）；命中判定一律按<b>最终尺寸</b>算（见 {@link #panelContains}），否则展开那一下点不中。
	 */
	private void drawWaypointPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (!panelOpen) {
			return;
		}
		ensureSearchBox();
		refreshPanelRows();

		float appear = Math.clamp(
				(System.nanoTime() - panelOpenedNanos) / (float) PANEL_APPEAR_NANOS, 0.0F, 1.0F);
		float fade = appear * appear * (3.0F - 2.0F * appear);
		int x = panelX();
		int bottom = panelBottom();

		// 面板底色<b>整块不画</b>（即完全透明，只保留文字 / 行 / 滚动指示），底下地形照旧透出来；
		// 行悬停/选中的高亮另有<b>半透明白底</b>（见下面 panelRow 那一段），仍看得清。
		graphics.text(font, Component.translatable("screen.sinanmap.world_map.waypoints"),
				x + PANEL_PADDING, panelTitleY(), alphaColor(fade, 0xFFCCCCCC), false);

		// 面板顶部的搜索框：自绘输入框（与坐标跳转框同一套），宽度对齐到下面的列表行
		searchBox.render(graphics);

		// 维度切换那一行：画在"空列表"分支之前，列表为空时也要能切维度
		drawPanelDimensionChip(graphics, mouseX, mouseY, fade);

		int listTop = panelListTop();
		if (panelRows.isEmpty()) {
			graphics.text(font, Component.translatable("screen.sinanmap.waypoint.empty"),
					x + PANEL_PADDING, listTop + 2, alphaColor(fade, 0xFF9CA3AF), false);
			return;
		}

		LocalPlayer player = Minecraft.getInstance().player;
		graphics.enableScissor(x, listTop, x + PANEL_WIDTH, bottom);
		for (int i = 0; i < panelRows.size(); i++) {
			int rowY = listTop + i * PANEL_ROW_HEIGHT - panelScroll;
			if (rowY + PANEL_ROW_HEIGHT <= listTop || rowY >= bottom) {
				continue;
			}
			boolean hovered = panelContains(mouseX, mouseY)
					&& mouseY >= rowY && mouseY < rowY + PANEL_ROW_HEIGHT;
			if (hovered) {
				graphics.fill(x + 1, rowY, x + PANEL_WIDTH - 1, rowY + PANEL_ROW_HEIGHT,
						alphaColor(fade, 0x40FFFFFF));
			}
			Waypoint waypoint = panelRows.get(i);
			int textY = rowY + (PANEL_ROW_HEIGHT - font.lineHeight + 1) / 2;
			// 来自别的维度的那几行：右边多标一个维度名、名字用略暗的颜色，一眼能看出它不在本图上
			boolean other = !waypoint.pos().dimension().equals(currentDimension());
			String dimensionTag = other ? shortDimensionLabel(waypoint.pos().dimension()) : "";
			// 名字用路径点自己的颜色（隐藏起来的压暗），右边依次是"维度名 + 离玩家多远（换算后）"
			String distance = player == null ? "" : "%d m".formatted(Math.round(Math.hypot(
					displayWaypointX(waypoint) - player.getX(), displayWaypointZ(waypoint) - player.getZ())));
			// 名字太长就截断（面板这一格放不下，画出去会压到右边的维度名 / 距离）
			int trailing = (distance.isEmpty() ? 0 : font.width(distance) + 4)
					+ (dimensionTag.isEmpty() ? 0 : font.width(dimensionTag) + 4);
			int nameRoom = PANEL_WIDTH - PANEL_PADDING * 2 - trailing;
			String name = waypoint.name();
			if (font.width(name) > nameRoom) {
				name = font.plainSubstrByWidth(name, Math.max(1, nameRoom - font.width(ELLIPSIS))) + ELLIPSIS;
			}
			int nameColor = waypoint.visible() ? waypoint.color() : 0xFF6E6E6E;
			graphics.text(font, name, x + PANEL_PADDING, textY,
					alphaColor(fade, other ? dimColor(nameColor) : nameColor), false);
			int right = x + PANEL_WIDTH - PANEL_PADDING;
			if (!distance.isEmpty()) {
				right -= font.width(distance);
				graphics.text(font, distance, right, textY,
						alphaColor(fade, other ? 0xFF7C828C : 0xFF9CA3AF), false);
				right -= 4;
			}
			if (!dimensionTag.isEmpty()) {
				right -= font.width(dimensionTag);
				graphics.text(font, dimensionTag, right, textY, alphaColor(fade, 0xFF7C828C), false);
			}
		}
		graphics.disableScissor();

		// 一屏放不下时，右端那条细线标出"滚到哪儿了"（滚轮是主要的，它只是个指示）
		int maxScroll = panelMaxScroll();
		if (maxScroll > 0) {
			int trackHeight = bottom - listTop;
			int content = panelRows.size() * PANEL_ROW_HEIGHT;
			int thumbHeight = Math.max(8, trackHeight * trackHeight / Math.max(1, content));
			int thumbY = listTop + (trackHeight - thumbHeight) * panelScroll / maxScroll;
			graphics.fill(x + PANEL_WIDTH - 2, listTop, x + PANEL_WIDTH, bottom,
					alphaColor(fade, 0x30000000));
			graphics.fill(x + PANEL_WIDTH - 2, thumbY, x + PANEL_WIDTH, thumbY + thumbHeight,
					alphaColor(fade, 0x80FFFFFF));
		}
	}

	/**
	 * 面板里的维度切换：搜索框与列表之间的一整行，点一下就循环到下一个可选维度。
	 *
	 * <p>纯自绘（半透明底 + 文字 + 右侧一个 {@code >}），不引原版贴图。选中的不是"当前显示维度"时
	 * 文字换成暖色，提示列表与地图标记已经切到别的维度（坐标也按 1:8 换算过）。
	 */
	private void drawPanelDimensionChip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float fade) {
		int x = panelX();
		int y = panelDimensionY();
		int bottom = y + PANEL_DIMENSION_HEIGHT;
		boolean hovered = panelDimensionHit(mouseX, mouseY);
		graphics.fill(x + 1, y, x + PANEL_WIDTH - 1, bottom,
				alphaColor(fade, hovered ? 0x30FFFFFF : 0x18FFFFFF));
		String dimension = panelDimensionId();
		boolean other = !dimension.equals(currentDimension());
		int textY = y + (PANEL_DIMENSION_HEIGHT - font.lineHeight + 1) / 2;
		graphics.text(font, Component.translatable("screen.sinanmap.world_map.waypoint_dimension",
						dimensionLabel(dimension)),
				x + PANEL_PADDING, textY, alphaColor(fade, other ? 0xFFFFC46B : 0xFFCCCCCC), false);
		// 只有多个维度可选时才画 ">"：只有一个时点了也没反应，画个箭头反而误导
		if (panelDimensionOptions().size() > 1) {
			graphics.text(font, ">", x + PANEL_WIDTH - PANEL_PADDING - font.width(">"), textY,
					alphaColor(fade, hovered ? 0xFFFFFFFF : 0xFF9CA3AF), false);
		}
	}

	/** 列表行上的维度简称：原版三维度用翻译名，其它维度取名字并截断到面板能放下。 */
	private String shortDimensionLabel(String dimension) {
		String label = dimensionLabel(dimension).getString();
		// 列表一格很窄：模组维度那种 "mod:some/dimension" 全画出来会把路径点名字挤没
		int room = PANEL_WIDTH / 3;
		return font.width(label) > room ? font.plainSubstrByWidth(label, room) : label;
	}

	/** 把颜色压暗一档（别的维度的行用）：只降不透明度，保留路径点自身的色相。 */
	private static int dimColor(int color) {
		return (color & 0x00FFFFFF) | 0xB0000000;
	}

	// ================================================================ 刻度尺上的坐标跳转

	private int jumpX() {
		return BUTTON_MARGIN;
	}

	/** 跳转按钮 / 输入框那一行的上沿：紧贴在左下角那条刻度尺的上面。 */
	private int jumpY() {
		return height - BUTTON_MARGIN - BAR_HEIGHT - JUMP_BOTTOM_GAP - JUMP_HEIGHT;
	}

	private boolean jumpHit(double mouseX, double mouseY) {
		return mouseX >= jumpX() && mouseX < jumpX() + JUMP_WIDTH
				&& mouseY >= jumpY() && mouseY < jumpY() + JUMP_HEIGHT;
	}

	/** 建（或重新摆放）跳转输入框：与面板搜索框同一个自绘输入框。 */
	private void ensureJumpBox() {
		if (jumpBox == null) {
			jumpBox = new SinanTextField(font);
			jumpBox.setMaxLength(24);
			jumpBox.setHint(Component.translatable("screen.sinanmap.world_map.jump_hint"));
		}
		jumpBox.setBounds(jumpX(), jumpY(), JUMP_WIDTH, JUMP_HEIGHT);
	}

	private void openJump() {
		jumpOpen = true;
		ensureJumpBox();
		// 点开就聚焦：中文输入法 / 键盘立刻就能用，不必再点一下框里
		focusField(jumpBox);
	}

	private void closeJump() {
		jumpBox = null;
		jumpOpen = false;
	}

	/**
	 * 回车：解析输入框里的坐标，合法就平滑跳过去，不合法则忽略并提示一下。
	 *
	 * <p>不合法时<b>不关输入框</b>：写着 {@code 100 } 这种半截内容时把框关了等于惩罚，留在原地让人补完更顺手。
	 */
	private void commitJump() {
		String text = jumpBox == null ? "" : jumpBox.getValue().strip();
		double[] target = parseCoordinates(text);
		if (target == null) {
			jumpHint = Component.translatable("screen.sinanmap.world_map.jump_invalid").getString();
			jumpHintUntilNanos = System.nanoTime() + JUMP_HINT_NANOS;
			return;
		}
		jumpHint = "";
		jumpHintUntilNanos = 0L;
		animateCenterTo(target[0], target[1]);
		closeJump();
	}

	/**
	 * 解析 {@code x y}（也兼容 {@code x,y}：逗号当空格用）。
	 *
	 * @return 两个坐标（x、z）；空、个数不是 2、不是数字或不是有限数时返回 {@code null}，调用方据此提示"输入非法"
	 */
	private static double[] parseCoordinates(String text) {
		if (text == null || text.isEmpty()) {
			return null;
		}
		String[] parts = text.replace(',', ' ').strip().split("\\s+");
		if (parts.length != 2) {
			return null;
		}
		try {
			double x = Double.parseDouble(parts[0]);
			double z = Double.parseDouble(parts[1]);
			if (!Double.isFinite(x) || !Double.isFinite(z)) {
				return null;
			}
			// 钳到原版世界边界：再远的地方没有地形，页面索引也会溢出
			return new double[] {
					Math.clamp(x, -JUMP_LIMIT, JUMP_LIMIT),
					Math.clamp(z, -JUMP_LIMIT, JUMP_LIMIT)};
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * 画比例尺上面那一行"跳转坐标"：没打开时是按钮，打开后原地变成输入框。挨着尺子最顺手
	 * （跳转本来就是"看着尺子说一个坐标"），也不挡地图中间。
	 */
	private void drawJumpInput(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int x = jumpX();
		int y = jumpY();
		int textY = y + (JUMP_HEIGHT - font.lineHeight + 1) / 2;
		if (jumpOpen) {
			ensureJumpBox();
			// 打开后原地变成自绘输入框（与面板搜索框、按钮是同一套底色与边框）
			jumpBox.render(graphics);
		} else {
			boolean hovered = jumpHit(mouseX, mouseY);
			// 与自绘控件同一套：半透明底 + 一圈边框（常态暗、悬停亮），不再画底边那条白线
			graphics.fill(x, y, x + JUMP_WIDTH, y + JUMP_HEIGHT,
					hovered ? SinanStyle.BACKGROUND_ACTIVE : SinanStyle.BACKGROUND);
			SinanStyle.outline(graphics, x, y, JUMP_WIDTH, JUMP_HEIGHT,
					hovered ? SinanStyle.BORDER_HOVER : SinanStyle.BORDER_IDLE);
			graphics.text(font, Component.translatable("screen.sinanmap.world_map.jump"),
					x + 3, textY, hovered ? 0xFFFFFFFF : 0xFFB4B4B4, false);
		}
		// 输入非法时在框右边提示几秒；平时那里写着格式提示
		boolean hintVisible = !jumpHint.isEmpty() && System.nanoTime() < jumpHintUntilNanos;
		if (hintVisible) {
			graphics.text(font, jumpHint, x + JUMP_WIDTH + 4, textY, 0xFFFF8080, false);
		} else if (!jumpOpen) {
			graphics.text(font, Component.translatable("screen.sinanmap.world_map.jump_hint"),
					x + JUMP_WIDTH + 4, textY, 0xFF8A8A8A, false);
		}
	}
}
