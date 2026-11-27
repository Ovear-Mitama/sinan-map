package sinanmap.client.waypoint;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import org.joml.Matrix3x2fStack;

import sinanmap.common.hud.MinimapSettings;
import sinanmap.common.waypoint.Waypoint;

/**
 * 路径点标记的绘制。世界地图与小地图共用一套；游戏内（HUD）的标记另有 {@link #drawOnHud}——
 * 更小、没有倒三角、收起时垫一块"路径点自身颜色"的半透明底。小地图复用 {@link #drawMarker}，
 * 但固定按"贴在点上的标签"画（{@code pinned = false}），<b>没有倒三角</b>。
 *
 * <p>标记从坐标点往上排，只有两样东西：
 * <ul>
 *   <li><b>倒三角</b>（填色 {@value #ARROW_HEIGHT} 像素高、尖端朝下）：上沿紧贴标签下沿，描边比填色整体膨胀 1 像素，
 *       最后收在第 0 列那一格——<b>正对着坐标点</b>的那个黑像素（行宽表见 {@link #drawArrowHead}）。</li>
 *   <li>标签：<b>没展开时只画名字首字</b>（死亡地点画骷髅）；悬停时展开成全名并画下划线。
 *       <b>底色：未选中 = 路径点原色；选中按展开进度压暗，且是不透明实色</b>；<b>下划线始终是路径点原色</b>，
 *       两端与底板边界<b>完全对齐</b>。文字颜色随底色亮度：浅底深字、深底白字（默认色 {@code #B1EAC2} 很浅）。</li>
 * </ul>
 *
 * <p><b>横向居中：以"倒三角的水平中心"为准，也就是坐标点所在的那一整像素列。</b>局部坐标里中轴列固定是第 0 列
 * （{@code [0, 1)}，中心 {@code x = 0.5}）：三角每行与标签底板都取<b>奇数宽</b>；文字落点由 {@link #centeredTextX} 算，
 * 把文字<b>左右各 1 像素的描边</b>一起算进去。
 * 文字位置<b>不能</b>按"标签宽 - 文字宽"算——标签宽是逐帧插值的整数，左边界会随奇偶性抖 1 像素；
 * {@code boxLeft + (labelWidth - font.width(text)) / 2} 就是<b>反例（不要改回去）</b>。
 *
 * <p><b>展开 / 收起的动画链路（改之前先读）：</b>{@code WorldMapScreen} 每帧算一次"光标停在哪一条"并传给
 * {@link #drawOnWorldMap}；它是<b>唯一</b>调用 {@link #advance} 的地方，推进 {@link #STATES} 里的进度再交给
 * {@link #drawMarker} 插值出标签宽度与两段文字的透明度。
 *
 * <p><b>动画状态只能由世界地图推进（修过的真实 bug）：</b>HUD 比 Screen 先抽取，且开着世界地图时 HUD 一样会被抽取；
 * 小地图与游戏内标记每帧也把同一批路径点按"收起"画一遍，若它们也推进同一个按 id 记的进度，
 * 一帧内"推回 0、又推向 1"增减相抵，进度<b>永远停在 0</b>（悬停看不到全名）。所以
 * {@link #drawOnMinimap} 固定按收起画、<b>不碰 {@link #STATES}</b>；{@link #drawOnHud} 不走 {@link #drawMarker}，
 * 用自己那套 {@link #HUD_STATES}。
 *
 * <p>不做贴图：颜色可由玩家改，用固定贴图得为每种颜色各烘一份（骷髅也是逐像素矩形，见 {@link #DEATH_ICON_MASK}）。
 *
 * <p><b>位置必须是亚像素的。</b>标记按整数像素定位会在平滑滑动时整格跳。整个标记在局部坐标系里排版，
 * 最后只做一次平移（+缩放），所以三种地图上的样子永远一致。
 */
public final class WaypointRenderer {

	/**
	 * 死亡地点的骷髅图标：{@code '#'} 是骨头，其余是空的（露出底板暗色，就是眼窝与牙缝）。
	 *
	 * <p><b>逐像素画矩形、不用贴图</b>：16 像素的骷髅缩到 9 像素时贴图会被采样糊成一团，只剩外轮廓；
	 * 点阵一个像素一格，任何缩放下都清楚。
	 *
	 * <p>颜色由调用方给（{@code textColor}，见 {@code drawMarker}）。
	 */
	private static final String[] DEATH_ICON_MASK = {
			".#####.",
			"#######",
			"##.#.##",
			"##.#.##",
			"#######",
			".#####.",
			".#.#.#.",
	};

	/**
	 * 骷髅占的边长（像素）：就是点阵本身的边长。
	 *
	 * <p><b>不跟 {@code font.lineHeight} 走</b>——标签宽度（{@link #labelWidth}）与绘制必须用同一个值，
	 * 否则会"看着在标记里、命中却偏了"。要改大小就直接改点阵（每行等长即可）。
	 */
	private static final int DEATH_ICON_SIZE = DEATH_ICON_MASK.length;

	/**
	 * 倒三角的<b>填色</b>高度（像素）：也是"标签下沿离坐标点多远"（见 {@link #drawMarker} 的 {@code labelBottom}）。
	 *
	 * <p>这个值决定行宽表：填色行宽 {@code 9, 7, 5, 3, 1}，描边膨胀 1 像素后 {@code 11, 9, 7, 5, 3, 1}；
	 * 最后一行只有第 0 列一格且只画描边（"收成一点"的那个黑像素，正落在坐标点上）。行宽表见 {@link #drawArrowHead}。
	 */
	private static final int ARROW_HEIGHT = 5;

	/**
	 * 标记整体在调用方给的缩放之上再缩的比例（{@value}）。
	 *
	 * <p>缩小只能靠变换矩阵（文字同一套矩阵画），所以命中判定、{@link #markerWidth} 与 {@link #markerHeight}
	 * 都必须乘上它——画多大就按多大判，否则会"看着在标记上、其实没命中"。
	 */
	private static final float MARKER_SCALE = 0.68F;

	/**
	 * 世界地图上标记的整体缩放（在 {@link #MARKER_SCALE} 之上再乘一档）。
	 *
	 * <p>展开的路径点宽度偏大，只把底板调窄会让长名字溢出（文字与底板同一套矩阵画），所以整条标记一起缩。
	 * 命中判定（{@link #hitBox}）必须乘同一系数，否则会"看着在标记上、其实没命中"。<b>只作用于世界地图</b>。
	 *
	 * <p>取固定 <b>GUI 比例</b>：窗口 / GUI 缩放变化时整条标记跟着界面等比放大，相对界面的比例不变
	 * （曾试过"设备像素"口径，最大化后图标相对小地图显得变小，已回退）。
	 */
	private static final float WORLD_MAP_SCALE = 0.75F;

	/** 标签的内边距：左右各 {@value #PAD_X}、上下各 {@value #PAD_Y}。 */
	private static final int PAD_X = 2;
	private static final int PAD_Y = 1;

	/** 名字太长被截断时补的省略号。 */
	private static final String ELLIPSIS = "…";

	/**
	 * 世界地图上展开标签的宽度上限（屏幕 GUI 像素）：名字再长也只按字符截断加省略号，不靠继续缩小整体去硬塞。
	 *
	 * <p>标签在<b>局部坐标系</b>里排版、再乘 {@link #WORLD_MAP_SCALE} 与 {@link #MARKER_SCALE}，换算成局部单位要除以这两档；
	 * 再减左右内边距就是文字宽度上限。<b>只作用于世界地图</b>（HUD 与小地图不截断）。
	 */
	private static final int WORLD_MAP_MAX_LABEL_PIXELS = 96;
	private static final int WORLD_MAP_MAX_TEXT_WIDTH = Math.max(1,
			Math.round(WORLD_MAP_MAX_LABEL_PIXELS / (WORLD_MAP_SCALE * MARKER_SCALE)) - PAD_X * 2);

	/**
	 * 文字颜色：按底色亮度二选一，浅底配深色字、深底配白字。
	 *
	 * <p>默认色 {@code #B1EAC2} 是浅薄荷绿，浅底压白字看不清，所以必须自适应；选中压暗后自动切白字。
	 */
	private static final int TEXT_COLOR_DARK = 0x0A0A0A;
	private static final int TEXT_COLOR_LIGHT = 0xFFFFFF;

	/** 亮 / 暗底色的分界（相对亮度 0..255）：底色亮于此值用深色字，否则用白字。 */
	private static final int DARK_TEXT_LUMINANCE = 140;

	/**
	 * 倒三角描边的颜色。
	 *
	 * <p>文字改用原版阴影后，这个色值只给倒三角用；传进文字绘制时只当"要不要阴影"的开关（见 {@link #drawOutlinedText}），
	 * <b>深色字一律不画</b>。
	 */
	private static final int OUTLINE_DARK = 0x14141A;

	/** 不画阴影的哨兵（见 {@link #drawOutlinedText}）。 */
	private static final int NO_OUTLINE = 0;

	/** 文字的透明度（展开/收起时会跟着淡入淡出）。 */
	private static final int TEXT_ALPHA = 0xF0;

	/**
	 * 标签底板的压暗系数与透明度。
	 *
	 * <p><b>未选中不压暗</b>（底色就是路径点原色），选中时按展开进度压到 {@value #BACKGROUND_DARKEN}；
	 * {@value #BACKGROUND_ALPHA} 是<b>不透明</b>的（{@code 0xFF}），实色不被地形透出。
	 */
	private static final float BACKGROUND_DARKEN = 0.28F;
	private static final int BACKGROUND_ALPHA = 0xFF;

	/** 倒三角的填色透明度 / 描边透明度。 */
	private static final int ARROW_FILL_ALPHA = 0xF0;
	private static final int ARROW_OUTLINE_ALPHA = 0xE0;

	/** 展开 / 收起这一段动画的时长。 */
	private static final long EXPAND_NANOS = 150_000_000L;

	/**
	 * 命中判定的余量：让"刚好挨着边缘"的光标也稳定命中。判定框按<b>完全展开</b>的目标尺寸算（{@link #hit}）、
	 * 不跟动画进度变，否则光标在边缘时"悬停变宽→移开变窄"会让状态来回抖。
	 */
	private static final double HIT_MARGIN = 1.0;

	/**
	 * 已经悬停着的那一条，判定框再多出这么多（滞回）：两条标记挨得近时，光标抖动会让"谁被悬停"每帧翻转，
	 * 给正在悬停的那一条更大框它就"粘"住，只有明显移开才换人。
	 */
	private static final double HIT_HYSTERESIS = 3.0;

	/** 小地图上的整体缩放：那么小的地方按原尺寸画会把地形盖住（固定 GUI 比例 → 跟着界面等比）。 */
	public static final float MINIMAP_SCALE = 0.6F;

	/** 一屏最多画多少条：路径点再多也不该把地图糊死。 */
	public static final int MAX_WORLD_MAP_MARKERS = 200;

	/** 状态缓存的容量上限：超过就整体清掉（路径点被删之后那些条目也就不必留了）。 */
	private static final int MAX_CACHED_STATES = 512;

	/**
	 * 世界地图上每条路径点的展开进度。<b>只有 {@link #drawOnWorldMap} 读它、也只有它推进它</b>（原因见类注释：
	 * 小地图与游戏内标记在世界地图开着时也会被抽取，它们若也推进会把动画推回去）。按路径点 id 记。只在渲染线程访问。
	 */
	private static final Map<UUID, State> STATES = new HashMap<>();

	/** 缓存里的一条。 */
	private static final class State {
		private String name = "";
		private String initial = "?";
		/** 0 = 完全收起，1 = 完全展开，见 {@link #EXPAND_NANOS}。 */
		private float progress;
		/** 上一次推进的时刻（纳秒）。 */
		private long lastNanos;
	}

	private WaypointRenderer() {
	}

	/**
	 * 世界地图上的绘制。
	 *
	 * @param expanded 光标是不是停在这条上（悬停即展开成全名，见类注释的调用顺序）
	 * @param settings 设置：只读"地图上路径点不透明度"与"多少米换成公里"
	 */
	public static void drawOnWorldMap(GuiGraphicsExtractor graphics, Font font,
			double screenX, double screenY, Waypoint waypoint, boolean expanded,
			MinimapSettings settings) {
		// 唯一的动画推进点：推进后的进度直接交给 drawMarker（不再读一次 STATES）
		float progress = advance(waypoint, expanded);
		drawMarker(graphics, font, screenX, screenY, waypoint, progress, WORLD_MAP_SCALE, true,
				WORLD_MAP_MAX_TEXT_WIDTH, (float) settings.mapWaypointOpacity());
	}

	/**
	 * 小地图上的绘制：与世界地图同一套样式，但整体缩小、<b>永远按收起画</b>、<b>没有倒三角</b>。
	 *
	 * <p>小地图是常驻 HUD、每帧都会被抽取，若在这里推进展开进度就会把世界地图的动画推回去（见类注释），
	 * 所以按"贴在点上的标签"画（{@code pinned = false}：以坐标点为中心、只画底板与首字/骷髅）。
	 */
	public static void drawOnMinimap(GuiGraphicsExtractor graphics, Font font,
			double screenX, double screenY, Waypoint waypoint, MinimapSettings settings) {
		drawMarker(graphics, font, screenX, screenY, waypoint, 0.0F, MINIMAP_SCALE, false, 0,
				(float) settings.mapWaypointOpacity());
	}

	// ───────────────────────── 游戏内（HUD）专用：与世界地图 / 小地图那套完全分开 ─────────────────────────
	//
	// HUD 的样子与地图上不一样，所以不复用 drawMarker，另画一套：
	//   · 收起：名字首字（死亡点是骷髅）+ 一块<b>路径点自身颜色的半透明底</b>；
	//   · 展开：全名 + 下方一条距离（"12m"），底板随之变宽；无倒三角；
	//   · <b>锚点固定</b>：首字 / 全名 / 距离共用同一左边界，展开只是右边变长；
	//   · 整体比地图标记再小一档，大小由设置里的"标记大小"给（见 {@link #drawOnHud}）。
	// 展开进度按 id 记在 {@link #HUD_STATES} 里，与地图的 {@link #STATES} 互不干扰。

	/** HUD 底板的不透明度：底板用<b>路径点自己的颜色</b>，取 {@value #HUD_BACKGROUND_ALPHA} 的 alpha，地形还看得见。 */
	private static final int HUD_BACKGROUND_ALPHA = 0x88;

	/** HUD 文字与描边：白字配深色描边（半透明底色上靠这圈描边保证可读）。 */
	private static final int HUD_TEXT_COLOR = 0xFFFFFF;
	private static final int HUD_OUTLINE_COLOR = 0x14141A;

	/** HUD 标记与坐标点之间留的空隙（未缩放 GUI 像素）。 */
	private static final int HUD_POINT_GAP = 2;

	/** HUD 展开 / 收回的动画时长（纳秒）。 */
	private static final long HUD_EXPAND_NANOS = 150_000_000L;

	/**
	 * 游戏内标记的展开进度：与世界地图的 {@link #STATES} <b>分开存</b>。HUD 的"展开"由"玩家是否正对着它"触发
	 * （判定在 {@code WaypointHud}），与"光标悬停"是两回事，各存各的动画才不会互相推。
	 */
	private static final Map<UUID, HudState> HUD_STATES = new HashMap<>();

	/** 缓存里的一条 HUD 状态。 */
	private static final class HudState {
		/** 0 = 完全收起（只画首字），1 = 完全展开（全名 + 距离 + 暗底）。 */
		private float progress;
		/** 上一次推进的时刻（纳秒）。 */
		private long lastNanos;
		/** 距离文本（如 {@code "12m"}）：只在取整后的米数变了才重建，免得每帧拼字符串。 */
		private String distanceText = "";
		private int distanceMeters = Integer.MIN_VALUE;
	}

	/**
	 * 游戏内的绘制。
	 *
	 * <p>布局（局部坐标原点就是投影出的坐标点、y 向上为负）：底板<b>上沿固定在收起态的高度</b>，
	 * 展开时只让<b>下沿朝坐标点往下长</b>；首字与全名画在第一行（交叉淡入淡出，y 不动），距离在第二行；
	 * 底板<b>始终</b>画（收起态也有底色）。
	 *
	 * <p><b>以文本为中心展开</b>：底板与三行文字都<b>以坐标点那一列为中轴</b>居中（{@link #centeredTextX}），
	 * 展开时左右同时变宽。
	 *
	 * @param lookedAt     玩家视角是不是正对着这条（锥角判定见 {@code WaypointHud}）
	 * @param meters       到玩家的水平距离
	 * @param settings     设置：是否显示距离、标记缩放、不透明度、多少米换成公里，全从它读
	 */
	public static void drawOnHud(GuiGraphicsExtractor graphics, Font font,
			double screenX, double screenY, Waypoint waypoint, boolean lookedAt, double meters,
			MinimapSettings settings) {
		HudState state = hudState(waypoint);
		// 走到跟前就一定展开全名：这么近了还只写一个字，反而看不出这是哪个路径点
		boolean nearName = settings.nearShowFullName() > 0 && meters <= settings.nearShowFullName();
		// 唯一的 HUD 动画推进点：按 nanoTime 把进度往目标推一帧
		float progress = advanceHud(state, lookedAt || nearName);
		updateDistance(state, meters, settings.kilometerThreshold());

		// 近到一定程度就不再写距离那一行（与"全名"那项各自独立，都是设置里的米数）
		boolean nearDistance = settings.nearHideDistance() > 0 && meters <= settings.nearHideDistance();
		// <b>距离只在"看向它"时出现</b>：靠"近处显示全名"自动展开的那一种是<b>不带距离的全名</b>。
		// 不加这一条的话，走出近处范围的那一刻距离行会突然冒出来、再跟着一起缩回去，看着像是"两行一起收"
		boolean showDistance = settings.waypointHudDistance() && !nearDistance && lookedAt;
		float scale = (float) settings.effectiveHudScale();
		float opacity = (float) settings.hudWaypointOpacity();
		String name = waypoint.name();
		String initial = initial(waypoint);
		String distance = showDistance ? state.distanceText : "";
		int lineHeight = font.lineHeight;

		// 底板宽随进度在"首字 / 骷髅"与"全名 / 距离中较宽者"之间插值（不取奇数：底板居中靠下面那列）
		// 死亡点收起时画的是骷髅图标，宽度按图标边长算，不按名字首字算
		int collapsedWidth = (waypoint.death() ? DEATH_ICON_SIZE : font.width(initial)) + PAD_X * 2;
		int expandedWidth = Math.max(font.width(name), font.width(distance)) + PAD_X * 2;
		int boxWidth = Math.round(collapsedWidth + (expandedWidth - collapsedWidth) * progress);
		// 高度：收起一行；展开时带距离才是两行（关掉距离就还是一行）
		int collapsedHeight = lineHeight + PAD_Y * 2;
		int expandedHeight = (showDistance ? lineHeight * 2 : lineHeight) + PAD_Y * 2;
		int boxHeight = Math.round(collapsedHeight + (expandedHeight - collapsedHeight) * progress);

		// 上沿固定 = 收起态高度：展开只改下沿，首行文字 y 在任何进度下都不动（以前按高度反推上沿，文字会往上跑）
		int boxTop = -HUD_POINT_GAP - collapsedHeight;
		int boxBottom = boxTop + boxHeight;
		// 底板以坐标点那一列为中轴向两边长
		int boxLeft = -(boxWidth / 2);
		int contentY = boxTop + PAD_Y;
		// 三行文字各自居中在中轴上：首字、全名（第一行交叉淡入淡出）、距离（第二行）
		int initialX = centeredTextX(font.width(initial));
		int nameX = centeredTextX(font.width(name));
		int distanceX = centeredTextX(font.width(distance));

		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate((float) screenX, (float) screenY);
		pose.scale(scale, scale);

		// 底板：路径点自己的颜色 + 半透明；收起态也画
		graphics.fill(boxLeft, boxTop, boxLeft + boxWidth, boxBottom,
				withAlpha(waypoint.color(), Math.round(HUD_BACKGROUND_ALPHA * opacity)));

		// 第一行：首字（死亡点是骷髅）淡出、全名淡入（都居中在中轴上）
		if (progress < 1.0F) {
			int alpha = Math.round(TEXT_ALPHA * (1.0F - progress) * opacity);
			if (waypoint.death()) {
				// HUD 底板是半透明的，所以按 HUD 文字那套"本体 + 深色描边"画
				drawDeathIcon(graphics, centeredTextX(DEATH_ICON_SIZE), deathIconY(contentY, font),
						withAlpha(HUD_TEXT_COLOR, alpha), withAlpha(HUD_OUTLINE_COLOR, alpha));
			} else {
				drawOutlinedText(graphics, font, initial, initialX, contentY,
						alpha, HUD_TEXT_COLOR, HUD_OUTLINE_COLOR);
			}
		}
		if (progress > 0.0F) {
			int alpha = Math.round(TEXT_ALPHA * progress * opacity);
			drawOutlinedText(graphics, font, name, nameX, contentY,
					alpha, HUD_TEXT_COLOR, HUD_OUTLINE_COLOR);
			// 第二行：距离（全名下方），设置里关掉就整行不画
			if (showDistance) {
				drawOutlinedText(graphics, font, distance, distanceX, contentY + lineHeight,
						alpha, HUD_TEXT_COLOR, HUD_OUTLINE_COLOR);
			}
		}
		pose.popMatrix();
	}

	/** 把这条路径点的 HUD 展开进度往目标推一帧，并返回推进后的进度（速率算法同 {@link #advance}）。 */
	private static float advanceHud(HudState state, boolean lookedAt) {
		long now = System.nanoTime();
		if (state.lastNanos == 0L) {
			state.lastNanos = now;
		}
		float delta = (now - state.lastNanos) / (float) HUD_EXPAND_NANOS;
		state.lastNanos = now;
		float target = lookedAt ? 1.0F : 0.0F;
		if (state.progress < target) {
			state.progress = Math.min(target, state.progress + delta);
		} else if (state.progress > target) {
			state.progress = Math.max(target, state.progress - delta);
		}
		return state.progress;
	}

	/** 刷新缓存的"多少米"文本：HUD 每帧都调，所以只在取整后的米数变了才重建字符串。 */
	private static void updateDistance(HudState state, double meters, int threshold) {
		int rounded = (int) Math.round(meters);
		if (rounded != state.distanceMeters) {
			state.distanceMeters = rounded;
			state.distanceText = distanceText(rounded, threshold);
		}
	}

	/**
	 * 距离文本：满 {@code threshold} 格改用公里（阈值可在设置里改，默认 10000 米）。公里取一位小数、
	 * 整数时省掉 {@code ".0"}，读起来是 {@code 1km / 1.5km / 12km}。
	 */
	private static String distanceText(int meters, int threshold) {
		if (meters < threshold) {
			return meters + "m";
		}
		String text = "%.1f".formatted(meters / 1000.0);
		if (text.endsWith(".0")) {
			text = text.substring(0, text.length() - 2);
		}
		return text + "km";
	}

	/** 取（必要时新建）一条路径点的 HUD 状态，见 {@link #HUD_STATES}。 */
	private static HudState hudState(Waypoint waypoint) {
		HudState state = HUD_STATES.get(waypoint.id());
		if (state == null) {
			if (HUD_STATES.size() >= MAX_CACHED_STATES) {
				HUD_STATES.clear();
			}
			state = new HudState();
			HUD_STATES.put(waypoint.id(), state);
		}
		return state;
	}

	/**
	 * 光标是不是落在这条路径点的标记上（倒三角、标签都算）。判定宽度按<b>完全展开</b>的目标尺寸算，
	 * 不跟展开状态变，否则光标在边缘时"悬停变宽→移开变窄"会让标记来回抖。
	 */
	public static boolean hit(Font font, double screenX, double screenY, Waypoint waypoint,
			double mouseX, double mouseY) {
		return hitBox(font, screenX, screenY, waypoint, mouseX, mouseY, HIT_MARGIN);
	}

	/**
	 * 同 {@link #hit}，但给已经悬停着的那一条多留一点余量（滞回，见 {@link #HIT_HYSTERESIS}）：
	 * 展开后就"粘"住，光标在边缘抖动不会把状态抖回 0。
	 */
	public static boolean stickyHit(Font font, double screenX, double screenY, Waypoint waypoint,
			double mouseX, double mouseY) {
		return hitBox(font, screenX, screenY, waypoint, mouseX, mouseY, HIT_MARGIN + HIT_HYSTERESIS);
	}

	/** 命中框：以坐标点所在那一格为准，往上一直罩到标签顶端，宽度取完全展开时的目标宽度。 */
	private static boolean hitBox(Font font, double screenX, double screenY, Waypoint waypoint,
			double mouseX, double mouseY, double margin) {
		// 判定宽度在"完全展开"基础上再收一档（完全展开时框比画出来的标签宽不少），
		// 仍按完全展开算（不随动画进度变），否则悬停→变宽→移开→变窄会把状态抖回去
		double half = labelWidth(font, waypoint, true, WORLD_MAP_MAX_TEXT_WIDTH)
				* MARKER_SCALE * WORLD_MAP_SCALE * HIT_WIDTH_FACTOR / 2.0 + margin;
		double top = screenY - markerHeight(font) * WORLD_MAP_SCALE - margin;
		// 底下只多留一点点：以前按倒三角的高度留，标签已没有三角，留那么宽会把坐标点下方一大片也算成命中
		double bottom = screenY + HIT_BOTTOM_GRAB + margin;
		return mouseX >= screenX - half && mouseX <= screenX + half
				&& mouseY >= top && mouseY <= bottom;
	}

	/** 命中框宽度相对"画出来的标签宽度"的比例，见 {@link #hitBox}。 */
	private static final double HIT_WIDTH_FACTOR = 0.72;

	/**
	 * 命中框在坐标点下方额外罩住的宽度（GUI 像素）。取 {@value}：<b>不再往下多罩</b>——坐标点以下只留
	 * {@link #HIT_MARGIN}（悬停时再加 {@link #HIT_HYSTERESIS}）那点余量。
	 */
	private static final double HIT_BOTTOM_GRAB = 0.0;

	/** 没展开时那个标记有多宽（HUD 上要贴着它写名字、写距离）。 */
	static int markerWidth(Font font, Waypoint waypoint) {
		return Math.round(labelWidth(font, waypoint, false) * MARKER_SCALE);
	}

	/** 标记竖向占多高（从标签顶到坐标点），HUD 上用来避开屏幕边缘。 */
	static int markerHeight(Font font) {
		return Math.round((ARROW_HEIGHT + labelHeight(font)) * MARKER_SCALE);
	}

	/**
	 * 画一个标记。局部坐标系<b>原点就是路径点所在的那一点</b>，y 向上为负，因此排版与屏幕位置、缩放都无关。
	 *
	 * @param progress 展开进度（0 = 完全收起，1 = 完全展开）：标签宽度、两段文字的透明度都按它插值
	 * @param pinned   是"插在点上的标记"（画倒三角 + 标签），还是"贴在点上的标签"（只画标签）
	 * @param maxTextWidth 展开后文字的宽度上限（局部单位，{@code <= 0} = 不限）：超长按字符截断加省略号
	 * @param opacity  整体不透明度（设置里的"地图上路径点不透明度"），乘在每一处颜色上
	 */
	private static void drawMarker(GuiGraphicsExtractor graphics, Font font,
			double screenX, double screenY, Waypoint waypoint, float progress,
			float scale, boolean pinned, int maxTextWidth, float opacity) {
		int color = waypoint.color();
		boolean death = waypoint.death();
		String name = waypoint.name();
		String initial = initial(waypoint);
		// 展开后真正要画的文本：世界地图这条链超长就截断（其余链 maxTextWidth = 0，原样显示）
		String expandedName = truncatedName(font, name, maxTextWidth);

		// 收起 / 展开的内容宽度按进度插值 —— 就是"宽度动画"
		int collapsedWidth = labelWidth(font, waypoint, false, 0);
		int expandedWidth = labelWidth(font, waypoint, true, maxTextWidth);
		// 底板宽度恒取奇数（{@code | 1}）：偶数时中轴落在两列之间，与奇数宽的倒三角差半像素，看着整体偏一格
		int labelWidth = (Math.round(collapsedWidth + (expandedWidth - collapsedWidth) * progress)) | 1;
		int labelHeight = labelHeight(font);

		// 标签下沿：插在点上的从倒三角上沿开始，贴边/贴点的以坐标点为中心
		int labelBottom = pinned ? -ARROW_HEIGHT : labelHeight / 2;
		int labelTop = labelBottom - labelHeight;
		int boxLeft = -(labelWidth / 2);

		// 底色：未选中 = 路径点原色，选中按进度压到暗色（同一条插值即"平滑变暗"动画）
		float darken = 1.0F - (1.0F - BACKGROUND_DARKEN) * Math.clamp(progress, 0.0F, 1.0F);
		int background = scaleColor(color, darken);
		// 文字颜色跟着底色亮度走：默认色 #B1EAC2 浅，未选中用深色字；选中压暗后自动切白字
		boolean darkText = luminance(background) >= DARK_TEXT_LUMINANCE;
		int textColor = darkText ? TEXT_COLOR_DARK : TEXT_COLOR_LIGHT;
		int outlineColor = darkText ? NO_OUTLINE : OUTLINE_DARK;

		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate((float) screenX, (float) screenY);
		pose.scale(scale * MARKER_SCALE, scale * MARKER_SCALE);

		if (pinned) {
			// 倒三角：上沿紧贴标签下沿，收口的黑像素正落在坐标点上（见 drawArrowHead 行宽表）
			drawArrowHead(graphics, labelBottom, color, opacity);
		}

		// 标签底板：不透明实色；左右以"坐标点所在整像素列"为轴居中（boxLeft = -(labelWidth/2)）
		graphics.fill(boxLeft, labelTop, boxLeft + labelWidth, labelBottom,
				withAlpha(background, Math.round(BACKGROUND_ALPHA * opacity)));

		// 内容：收起=首字（死亡点是骷髅）、展开=全名 + 下划线，交叉淡入淡出；文字按"倒三角中心"居中，不跟标签宽走
		int contentY = labelTop + PAD_Y;
		if (progress < 1.0F) {
			int alpha = Math.round(TEXT_ALPHA * (1.0F - progress) * opacity);
			if (death) {
				// 必须用 textColor：收起时底板就是 color 本身，用 color 画骷髅等于"同色画同色"，标签看着空空如也
				drawDeathIcon(graphics, centeredTextX(DEATH_ICON_SIZE), deathIconY(contentY, font),
						withAlpha(textColor, alpha), 0);
			} else {
				drawOutlinedText(graphics, font, initial,
						centeredTextX(font.width(initial)), contentY, alpha, textColor, outlineColor);
			}
		}
		if (progress > 0.0F) {
			int alpha = Math.round(TEXT_ALPHA * progress * opacity);
			drawOutlinedText(graphics, font, expandedName,
					centeredTextX(font.width(expandedName)), contentY,
					alpha, textColor, outlineColor);
			// 下划线：始终是路径点原色，画在底板最下面那一行（不压文字）；左右与底板边界完全对齐
			// （用 boxLeft 与 boxLeft + labelWidth，而非"文字宽 - 1"那种内缩写法）
			graphics.fill(boxLeft, labelBottom - 1, boxLeft + labelWidth, labelBottom,
					withAlpha(color, Math.round(alpha * opacity)));
		}
		pose.popMatrix();
	}

	/**
	 * 内容（文字 / 骷髅图）相对"倒三角水平中心"居中的左边界。
	 *
	 * <p>中轴固定是坐标点所在那一整像素列（局部列 {@code [0, 1)}，中心 {@code x = 0.5}）。文字带原版阴影，
	 * 真正占位的是 {@code [x, x+宽+1)}（阴影往右下各偏 1 像素），要让这一块的中心落在 {@code 0.5} 上，
	 * 解出 {@code x = 0.5 - (宽+1)/2}。
	 *
	 * <p><b>刻意不用标签宽去算</b>（见类注释反例）：标签宽是逐帧插值的整数，会让文字随奇偶性抖 1 像素。
	 */
	private static int centeredTextX(int contentWidth) {
		return Math.round(0.5F - (contentWidth + 1) / 2.0F);
	}

	/**
	 * 文字：白字配<b>原版阴影</b>（右下各偏 1 像素的深色副本），深色字不画。
	 *
	 * <p>原来用"上下左右各描一遍"的四周描边，底边那条在标签下沿像一条黑线；改用原版 {@code dropShadow}，与物品名、聊天一致。
	 */
	private static void drawOutlinedText(GuiGraphicsExtractor graphics, Font font, String text,
			int x, int y, int alpha, int textColor, int outlineColor) {
		graphics.text(font, text, x, y, withAlpha(textColor, alpha), outlineColor != NO_OUTLINE);
	}

	/**
	 * 坐标点上那个倒三角。局部坐标：原点就是坐标点、y 向上为负；<b>尖端朝下、上沿紧贴标签下沿</b>
	 * （{@code labelBottom = -ARROW_HEIGHT = -5}）。描边三角 = 填色三角<b>整体膨胀 1 像素</b>。
	 *
	 * <p><b>行宽表</b>（自上而下；每行宽 {@code = 2 * half + 1}，恒为奇数，中轴列恒为 {@code [0, 1)}）：
	 * <pre>
	 *   行号 r |   y   | 填色 half | 填色宽 | 描边 half | 描边宽
	 *   -------+-------+-----------+--------+-----------+-------
	 *     0    |  -5   |     4     |    9   |     5     |   11
	 *     1    |  -4   |     3     |    7   |     4     |    9
	 *     2    |  -3   |     2     |    5   |     3     |    7
	 *     3    |  -2   |     1     |    3   |     2     |    5
	 *     4    |  -1   |     0     |    1   |     1     |    3
	 *     5    |   0   |     —     |    —   |     0     |    1  ← 尖端那一格，只有描边
	 * </pre>
	 * 描边三角比填色三角<b>多一行</b>（r 取 {@code 0..ARROW_HEIGHT}，共 6 行）：最后一行 {@code half = 0}
	 * 只有第 0 列一格、且<b>刻意不画填色</b>，保证"收成一点"的那个黑像素被画出来。
	 * <b>旧写法只把描边左右各加宽 1 像素、上下不膨胀，尖端会缺一个黑像素（改之前注意）。</b>
	 */
	private static void drawArrowHead(GuiGraphicsExtractor graphics, int labelBottom, int color,
			float opacity) {
		int outline = withAlpha(OUTLINE_DARK, Math.round(ARROW_OUTLINE_ALPHA * opacity));
		int fill = withAlpha(color, Math.round(ARROW_FILL_ALPHA * opacity));
		for (int r = 0; r <= ARROW_HEIGHT; r++) {
			// 描边 half 从 ARROW_HEIGHT 一路收到 0：r = ARROW_HEIGHT 那一行就是尖端的单格
			int half = ARROW_HEIGHT - r;
			int y = labelBottom + r;
			graphics.fill(-half, y, half + 1, y + 1, outline);
			// 填色比描边左右各窄 1 像素；尖端行（half = 0）无填色，保持描边色——"不能少的黑像素"
			if (half > 0) {
				graphics.fill(-half + 1, y, half, y + 1, fill);
			}
		}
	}

	/**
	 * 死亡图标：按点阵逐格画（见 {@link #DEATH_ICON_MASK}）。
	 *
	 * @param outlineColor 描边色，{@code 0} = 不描边：地图底板是不透明实色本就能压住背景，HUD 底板是<b>半透明</b>色、得加一圈深色描边才看得清
	 */
	private static void drawDeathIcon(GuiGraphicsExtractor graphics, int x, int y,
			int color, int outlineColor) {
		if (outlineColor != 0) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					if (dx != 0 || dy != 0) {
						drawDeathMask(graphics, x + dx, y + dy, outlineColor);
					}
				}
			}
		}
		drawDeathMask(graphics, x, y, color);
	}

	/** 点阵本体（不含描边）：同一行连续的骨头合成一条色块，相邻格子共用边界，缩放后不会留下缝。 */
	private static void drawDeathMask(GuiGraphicsExtractor graphics, int x, int y, int color) {
		for (int row = 0; row < DEATH_ICON_MASK.length; row++) {
			String line = DEATH_ICON_MASK[row];
			int col = 0;
			while (col < line.length()) {
				if (line.charAt(col) != '#') {
					col++;
					continue;
				}
				int start = col;
				while (col < line.length() && line.charAt(col) == '#') {
					col++;
				}
				graphics.fill(x + start, y + row, x + col, y + row + 1, color);
			}
		}
	}

	/** 骷髅在"一行文字"里的竖直位置：按字高居中（点阵比字体行高矮，顶格画会沉在下边）。 */
	private static int deathIconY(int lineTop, Font font) {
		return lineTop + (font.lineHeight - DEATH_ICON_SIZE + 1) / 2;
	}

	/** 标签整条有多宽：左右内边距 + 内容（首字 / 全名 / 骷髅图标）。 */
	private static int labelWidth(Font font, Waypoint waypoint, boolean expanded) {
		return labelWidth(font, waypoint, expanded, 0);
	}

	/**
	 * 同 {@link #labelWidth(Font, Waypoint, boolean)}，但展开时按 {@code maxTextWidth} 截断名字。
	 * 命中框（{@link #hitBox}）必须用同一上限，否则会"看着在标记上、其实没命中"。
	 */
	private static int labelWidth(Font font, Waypoint waypoint, boolean expanded, int maxTextWidth) {
		String shown = expanded || waypoint.death()
				? truncatedName(font, waypoint.name(), expanded ? maxTextWidth : 0)
				: initial(waypoint);
		int content = waypoint.death() && !expanded ? DEATH_ICON_SIZE : font.width(shown);
		return content + PAD_X * 2;
	}

	/**
	 * 展开后要画的文本：给定上限（局部单位，{@code <= 0} = 不限）时按字符截断并补省略号。
	 * 只影响世界地图的显示文本，不动点位置，也不动 HUD 与小地图（它们传 0）。
	 */
	private static String truncatedName(Font font, String name, int maxTextWidth) {
		if (maxTextWidth <= 0 || font.width(name) <= maxTextWidth) {
			return name;
		}
		return font.plainSubstrByWidth(name, Math.max(1, maxTextWidth - font.width(ELLIPSIS))) + ELLIPSIS;
	}

	/** 标签整条有多高。 */
	private static int labelHeight(Font font) {
		return font.lineHeight + PAD_Y * 2;
	}

	/**
	 * 把这条路径点的展开进度往目标推一帧，并返回推进后的进度。<b>只由 {@link #drawOnWorldMap} 调用</b>
	 * （见类注释：多一个调用方就会把动画推回去）。速率按 {@link #EXPAND_NANOS} 折算，动画时长不随帧率变。
	 */
	private static float advance(Waypoint waypoint, boolean expanded) {
		State state = state(waypoint);
		long now = System.nanoTime();
		if (state.lastNanos == 0L) {
			state.lastNanos = now;
		}
		float delta = (now - state.lastNanos) / (float) EXPAND_NANOS;
		state.lastNanos = now;
		float target = expanded ? 1.0F : 0.0F;
		if (state.progress < target) {
			state.progress = Math.min(target, state.progress + delta);
		} else if (state.progress > target) {
			state.progress = Math.max(target, state.progress - delta);
		}
		return state.progress;
	}

	/** 取（必要时新建）一条路径点的绘制状态，见 {@link #STATES}。 */
	private static State state(Waypoint waypoint) {
		State state = STATES.get(waypoint.id());
		if (state == null) {
			if (STATES.size() >= MAX_CACHED_STATES) {
				STATES.clear();
			}
			state = new State();
			STATES.put(waypoint.id(), state);
		}
		// 收起态显示什么：填了"简称"就用简称，没填才退回名字首字（见 Waypoint#shortName）。
		// 名字与简称<b>任一变了都要重算</b>，只盯名字的话单独改简称不生效
		String collapsed = waypoint.shortName().isEmpty()
				? (waypoint.name().isEmpty() ? "?" : waypoint.name().substring(0, 1))
				: waypoint.shortName();
		if (!state.name.equals(waypoint.name()) || !state.initial.equals(collapsed)) {
			state.name = waypoint.name();
			state.initial = collapsed;
		}
		return state;
	}

	/**
	 * 标记<b>收起时</b>显示的那几个字（缓存见 {@link State#initial}）：优先用路径点自己的"简称"，没填就退回名字首字。
	 */
	private static String initial(Waypoint waypoint) {
		return state(waypoint).initial;
	}

	/** 给颜色配上透明度。 */
	private static int withAlpha(int color, int alpha) {
		return Math.clamp(alpha, 0, 255) << 24 | color & 0xFFFFFF;
	}

	/** 把颜色的三个通道各乘一个系数（{@code factor = 1} 即原色，见 {@code BACKGROUND_DARKEN}）。 */
	private static int scaleColor(int color, float factor) {
		int red = Math.round(((color >> 16) & 0xFF) * factor);
		int green = Math.round(((color >> 8) & 0xFF) * factor);
		int blue = Math.round((color & 0xFF) * factor);
		return red << 16 | green << 8 | blue;
	}

	/** 相对亮度（0..255，人眼对绿最敏感）：用来决定文字用深色还是白色，见 {@link #DARK_TEXT_LUMINANCE}。 */
	private static int luminance(int color) {
		int red = (color >> 16) & 0xFF;
		int green = (color >> 8) & 0xFF;
		int blue = color & 0xFF;
		return (red * 299 + green * 587 + blue * 114) / 1000;
	}
}
