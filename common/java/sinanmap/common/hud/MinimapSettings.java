package sinanmap.common.hud;

import java.util.ArrayList;
import java.util.List;

import sinanmap.common.map.LayerMode;

/**
 * 小地图的全部可调项。
 *
 * <p>配置界面与代码共用同一份数据，避免两处各写一套默认值。
 * 所有 setter 都会做范围校验与钳位，写入非法值也不会让渲染崩掉。
 */
public final class MinimapSettings {

	/** 宽高各自的钳位范围。 */
	public static final int MIN_SIDE = 32;

	public static final int MAX_SIDE = 640;

	/** 手动缩放的范围：每个设备像素代表多少方块。 */
	public static final double MIN_BLOCKS_PER_PIXEL = 0.125;

	public static final double MAX_BLOCKS_PER_PIXEL = 16.0;

	/** 生物雷达半径的钳位范围（方块，以玩家为中心）。下限别取太小，否则雷达只在脚边冒点。 */
	public static final double MIN_RADAR_RADIUS = 16.0;

	public static final double MAX_RADAR_RADIUS = 128.0;

	/** 游戏内路径点标记的缩放范围（1.0 = 与"看向时展开"的原始大小一致）。 */
	public static final double MIN_HUD_SCALE = 0.3;

	public static final double MAX_HUD_SCALE = 1.0;

	/** 路径点标记的默认缩放（"自动"档用的就是它）。 */
	public static final double DEFAULT_HUD_SCALE = 0.5;

	/** 路径点不透明度的钳位范围：留个下限，免得调到 0 之后"路径点好像没了"却找不到原因。 */
	public static final double MIN_OPACITY = 0.1;

	public static final double MAX_OPACITY = 1.0;

	/** 距离换算成公里的阈值（米）：默认 {@value #DEFAULT_KM_THRESHOLD}。可按挡选，见 {@link #KM_THRESHOLD_STEPS}。 */
	public static final int DEFAULT_KM_THRESHOLD = 10000;

	/** 可选的几挡：100m / 1km / 10km / 100km / 1000km。 */
	public static final List<Integer> KM_THRESHOLD_STEPS =
			List.of(100, 1000, 10000, 100000, 1000000);

	public static final int MIN_KM_THRESHOLD = 100;

	public static final int MAX_KM_THRESHOLD = 1000000;

	// ---------------------------------------------------------------- 游戏内标记的"近处"两项

	/** "近处"两项的可选距离（米）：0 = 不生效。 */
	public static final List<Integer> NEAR_DISTANCE_STEPS = List.of(0, 5, 10, 15, 25, 50, 100);

	/** "近处"两项的默认距离（米）。 */
	public static final int DEFAULT_NEAR_DISTANCE = 25;

	// ---------------------------------------------------------------- 叠加层

	/**
	 * 区块网格颜色的可选项（ARGB）。
	 *
	 * <p>全部取低不透明度：网格是"量距离用"的参考线，压在地形上得能看见、又不能把画面糊住。
	 * 想更淡 / 更明显就改这里的 alpha 档。
	 */
	public static final List<Integer> GRID_COLOR_STEPS = List.of(
			0x40FFFFFF, 0x40000000, 0x40FF5555, 0x40FFAA33,
			0x40FFFF55, 0x4055FF55, 0x4055FFFF, 0x40AA55FF);

	/** 默认网格颜色：白色，任何地形上都看得清。 */
	public static final int DEFAULT_GRID_COLOR = 0x40FFFFFF;

	// ---------------------------------------------------------------- 方位字母（小地图边缘的 N / E / S / W）

	/** 方位字母颜色的默认值：白色，压在黑底上最醒目。 */
	public static final int DEFAULT_COMPASS_COLOR = 0xFFFFFFFF;

	/** 方位字母背景色的默认值：半透明近黑，衬得住字、又不会像纯黑那样把边缘地形压死。 */
	public static final int DEFAULT_COMPASS_BACKGROUND_COLOR = 0xD0101014;

	/**
	 * 方位字母大小的可选挡位。
	 *
	 * <p>存的是<b>相对基准字号的倍率</b>（见 {@code MinimapHud} 里的方位字母基准缩放）：
	 * 1.0 = 基准大小，大于 1 更大、小于 1 更小。做成倍率而不是像素值，改基准字号时所有挡位一起跟着走。
	 */
	public static final List<Double> COMPASS_SCALE_STEPS = List.of(0.6, 0.8, 1.0, 1.2, 1.5, 2.0);

	/** 方位字母倍率的钳位范围（与 {@link #COMPASS_SCALE_STEPS} 一致）。 */
	public static final double MIN_COMPASS_SCALE = 0.6;

	public static final double MAX_COMPASS_SCALE = 2.0;

	/** 方位字母倍率的默认值：基准大小。 */
	public static final double DEFAULT_COMPASS_SCALE = 1.0;

	// ---------------------------------------------------------------- 性能（默认全自动）

	/** "自动"档：手动值取这个数时就表示"用默认"。 */
	public static final int AUTO = -1;

	/** 后台读盘线程：自动时<b>按机器核数给</b>（见 {@link #autoPrefetchThreads()}），这是下限。 */
	public static final int DEFAULT_PREFETCH_THREADS = 6;
	public static final int MIN_PREFETCH_THREADS = 1;
	public static final int MAX_PREFETCH_THREADS = 64;

	/**
	 * 自动档的读盘线程数上限。
	 *
	 * <p>核数再多也压在这个数：读一块的活是"两次 pread + 解压 + 解析 NBT"，线程多到一定程度收益被
	 * 磁盘排队吃掉，而每条线程手里可能正拎着一份解好的区块 NBT。
	 */
	public static final int AUTO_PREFETCH_THREADS_CAP = 32;

	/**
	 * 自动档实际用几条读盘线程：<b>按可用核数给</b>——一部分活是解压/解析（吃 CPU），一部分是等 I/O，
	 * 给到接近核数不会互相抢；留两个核给游戏主线程与渲染线程。
	 *
	 * <p>以前自动固定 {@code 4}：离出生点远、数据全得从世界存档读时，那一片页面的吞吐就压在 4 条线程上
	 * （实测约 1300~5800 块/秒，远处明显更慢）。机器越好越该多给，所以改成跟着核数走。
	 */
	public static int autoPrefetchThreads() {
		return Math.clamp(Runtime.getRuntime().availableProcessors() - 2,
				DEFAULT_PREFETCH_THREADS, AUTO_PREFETCH_THREADS_CAP);
	}

	/** 洞穴扫描线程：自动 {@value #DEFAULT_CAVE_SCAN_THREADS}。 */
	public static final int DEFAULT_CAVE_SCAN_THREADS = 3;
	public static final int MIN_CAVE_SCAN_THREADS = 1;
	public static final int MAX_CAVE_SCAN_THREADS = 8;

	/**
	 * 每帧最多把多少块读盘结果搬进缓存：自动 {@value #DEFAULT_APPLIED_PER_FRAME}。
	 *
	 * <p>只是<b>块数</b>上限，真正的时间闸是 {@link #DEFAULT_APPLY_BUDGET_MILLIS}——取 256 是让"块都很便宜"
	 * 的场合别被块数卡住（实测数据读好了却搬不过来时，"本秒问"上千、`预算停` 却是 0）。
	 */
	public static final int DEFAULT_APPLIED_PER_FRAME = 256;
	public static final int MIN_APPLIED_PER_FRAME = 32;
	public static final int MAX_APPLIED_PER_FRAME = 512;

	/** 每帧搬运的时间预算（毫秒）：自动 {@value #DEFAULT_APPLY_BUDGET_MILLIS}。 */
	public static final int DEFAULT_APPLY_BUDGET_MILLIS = 3;
	public static final int MIN_APPLY_BUDGET_MILLIS = 1;
	public static final int MAX_APPLY_BUDGET_MILLIS = 16;

	/** 世界地图每页每次推进多少块：自动 {@value #DEFAULT_CHUNKS_PER_VISIT}。 */
	public static final int DEFAULT_CHUNKS_PER_VISIT = 32;
	public static final int MIN_CHUNKS_PER_VISIT = 8;
	public static final int MAX_CHUNKS_PER_VISIT = 256;

	/**
	 * 同时在填的页数上限：自动 {@value #DEFAULT_FILLING_PAGES}。
	 *
	 * <p><b>取 3 而不是更大</b>：之前给到 8，一屏会同时"长"出七八块半成品，进度条到处都有、都长不完，
	 * 看起来乱；压到 3 之后最靠近玩家的那几页先把一块地方填透，整体是从玩家附近成片铺开，整齐得多。
	 * 真正决定"每帧干多少"的是暖预算与 {@link #DEFAULT_FILLING_PAGES_PER_FRAME}，减页数不会拖慢总量。
	 */
	public static final int DEFAULT_FILLING_PAGES = 3;
	public static final int MIN_FILLING_PAGES = 1;
	public static final int MAX_FILLING_PAGES = 32;

	/**
	 * 每帧并行推进几页：自动 {@value #DEFAULT_FILLING_PAGES_PER_FRAME}。
	 *
	 * <p><b>取 3 与上面页数配套</b>：一次只喂一页时，若那页在等后台读盘，这一帧就白转；同时喂当前
	 * 最靠近的几页，请求并行在路上，单位时间拿到的数据更多、加载更快，而总时间仍由暖预算兜底
	 * （预算是全局一份、先到先得，这里只是让它在几页之间摊开用）。
	 */
	public static final int DEFAULT_FILLING_PAGES_PER_FRAME = 3;
	public static final int MIN_FILLING_PAGES_PER_FRAME = 1;
	public static final int MAX_FILLING_PAGES_PER_FRAME = 4;

	/** 世界地图细节档的范围：{@value #MAX_DETAIL_TIER} = 不限制（按缩放自动），越小越粗、越省显存与带宽。 */
	public static final int MIN_DETAIL_TIER = 1;

	public static final int MAX_DETAIL_TIER = 8;

	/**
	 * 小地图下面可以显示的信息行。
	 *
	 * <p>顺序就是显示顺序（从上到下），由玩家在设置里排；不想要哪一行就把它关掉。
	 */
	public enum InfoLine {

		/** 玩家坐标（x, y, z）。 */
		COORDS,

		/** 游戏内时间（12 / 24 小时制可切）。 */
		TIME,

		/** 当前群系。 */
		BIOME,

		/** 当前维度。 */
		DIMENSION,
	}

	/** 信息文本的对齐方式（相对小地图那一条竖线）。 */
	public enum TextAlign {
		LEFT,
		CENTER,
		RIGHT,
	}

	/**
	 * 自动缩放时"每个 GUI 像素代表多少方块"，也就是小地图的倍率。
	 *
	 * <p><b>这是小地图看多大范围的唯一开关</b>：数值越大，视野越广、地形显示得越小。按默认 96×64 的
	 * 小地图算，一屏约 {@code 240×160} 方块。嫌地形太大（放大过头）就调大，嫌看不清细节就调小。
	 */
	public static final double AUTO_BLOCKS_PER_GUI_PIXEL = 2.5;

	private boolean enabled = true;
	private int width = 96;
	private int height = 64;

	private double blocksPerPixel = 1.0;
	private boolean autoZoom = true;
	private boolean rotateWithPlayer;
	private LayerMode layerMode = LayerMode.AUTO;
	private ScreenAnchor anchor = ScreenAnchor.TOP_RIGHT;
	private int offsetX = 0;
	private int offsetY = 0;
	private boolean entityRadar = true;
	private double entityRadarRadius = 64.0;
	private boolean entityRadarHostileOnly;

	/**
	 * 雷达是否做"高度指示"：在玩家<b>上方</b>的生物画得半透、在<b>下方</b>的画得暗一点、同层原样。
	 *
	 * <p>默认开：小图标上光看位置很难判断高低，这一层明暗是最省事的提示。
	 */
	private boolean entityRadarHeightIndicator = true;

	/**
	 * 是否把自己的位置共享给同服务器的其他司南玩家。
	 *
	 * <p>默认开：多人一起玩时这是最有用的一条（队友在地图上看得见彼此）。关掉之后<b>自己不被别人看到</b>，
	 * 但自己看别人不受影响——各人管各人的开关。需要服务器也装了司南：这条要经服务端转手。
	 */
	private boolean sharePlayerPosition = true;

	/**
	 * 游戏内路径点标记展开时是否带距离。
	 *
	 * <p>默认开：不写距离就只能靠肉眼估远近。关掉之后标签只有名字一行，占的地方更小。
	 */
	private boolean waypointHudDistance = true;

	/** 游戏内路径点标记的整体缩放（见 {@link #MIN_HUD_SCALE}）。 */
	private double waypointHudScale = DEFAULT_HUD_SCALE;

	/**
	 * 游戏内标记缩放是否走"自动"。
	 *
	 * <p>开（默认）时用 {@link #DEFAULT_HUD_SCALE}，手动滑条置灰——与"自动缩放"同一套做法。
	 */
	private boolean hudScaleAuto = true;

	/** 地图上是否画路径点（世界地图与小地图都算"地图上"）。 */
	private boolean showMapWaypoints = true;

	/** 3D 世界里是否画路径点标记。 */
	private boolean showHudWaypoints = true;

	/** 是否显示死亡地点（骷髅标记那几条）。 */
	private boolean showDeathPoints = true;

	/** 死亡时是否<b>保留上一次</b>的死亡地点。
	 *
	 * <p>关掉的话每次死亡都会把上一条覆盖掉，地图上永远只有一条。默认开（历史行为）。
	 */
	private boolean keepOldDeathPoints = true;

	/**
	 * 是否兼容"传送石碑（Waystones）"：把它数据库里的石碑自动同步成路径点。
	 *
	 * <p>默认开。只同步<b>玩家真的能用</b>的那些（已激活的、全局的、分享石），未激活的普通石碑不列——
	 * 否则地图上会多出一堆点了也没用的标记。
	 */
	private boolean waystoneIntegration = true;

	/** 游戏内路径点标记的不透明度。 */
	private double hudWaypointOpacity = MAX_OPACITY;

	/** 地图上路径点标记的不透明度。 */
	private double mapWaypointOpacity = MAX_OPACITY;

	/** 距离超过多少米就换算成公里显示。 */
	private int kilometerThreshold = DEFAULT_KM_THRESHOLD;

	/** 走到离路径点多近就不再显示距离（米）；0 = 一直显示。 */
	private int nearHideDistance = DEFAULT_NEAR_DISTANCE;

	/** 走到离路径点多近就一定展开全名（米）；0 = 不强制，只按"看向才展开"。 */
	private int nearShowFullName = DEFAULT_NEAR_DISTANCE;

	/** 地图上是否画区块网格。 */
	private boolean chunkGrid;

	/** 区块网格的颜色（ARGB），从 {@link #GRID_COLOR_STEPS} 里挑。 */
	private int chunkGridColor = DEFAULT_GRID_COLOR;

	/** 地图上是否把史莱姆区块标出来。 */
	private boolean slimeChunks;

	/**
	 * 是否在小地图边缘标出东南西北（N / E / S / W）。
	 *
	 * <p>默认开：方向是最常用的参考，开了才不至于看着小地图分不清朝向。
	 */
	private boolean compass = true;

	/** 小地图方位字母的颜色（不透明 ARGB），默认白。 */
	private int compassColor = DEFAULT_COMPASS_COLOR;

	/** 小地图方位字母的背景色（ARGB），默认半透明近黑。 */
	private int compassBackgroundColor = DEFAULT_COMPASS_BACKGROUND_COLOR;

	/** 小地图方位字母的大小倍率（乘在基准字号上）。 */
	private double compassScale = DEFAULT_COMPASS_SCALE;

	/** 小地图下面显示哪几行信息、按什么顺序（默认只显示坐标，其余关着）。 */
	private final List<InfoLine> infoLines = new ArrayList<>(List.of(InfoLine.COORDS));

	/** 信息文本的对齐，默认左对齐（贴着小地图左边界）。 */
	private TextAlign infoAlign = TextAlign.LEFT;

	/** 时间是否用 24 小时制。 */
	private boolean clock24 = true;

	/** 性能项：{@link #AUTO} = 用默认值；其余按各自的 MIN/MAX 钳位。 */
	private int prefetchThreads = AUTO;
	private int caveScanThreads = AUTO;
	private int appliedPerFrame = AUTO;
	private int applyBudgetMillis = AUTO;
	private int chunksPerVisit = AUTO;
	private int fillingPages = AUTO;
	private int fillingPagesPerFrame = AUTO;

	/**
	 * 世界地图的最高细节档（1 / 4 / 8 方块每像素）。
	 *
	 * <p>默认 {@value #MAX_DETAIL_TIER} = 不限制：仍然按缩放自动选档（见 {@code WorldMapScreen#updateTerrainTier}）。
	 * 调小之后画面上限就是那一档，显存与填充量随之下降——低配机想稳帧时可以把它压到 4 甚至 1。
	 */
	private int mapDetailTier = MAX_DETAIL_TIER;

	/** 是否显示小地图。 */
	public boolean enabled() {
		return enabled;
	}

	public void setEnabled(boolean value) {
		this.enabled = value;
	}

	/** 宽度（GUI 像素）。 */
	public int width() {
		return width;
	}

	public void setWidth(int value) {
		this.width = Math.clamp(value, MIN_SIDE, MAX_SIDE);
	}

	/** 高度（GUI 像素）。 */
	public int height() {
		return height;
	}

	public void setHeight(int value) {
		this.height = Math.clamp(value, MIN_SIDE, MAX_SIDE);
	}

	/** 缩放：每个设备像素代表多少方块。数值越大视野越广。 */
	public double blocksPerPixel() {
		return blocksPerPixel;
	}

	public void setBlocksPerPixel(double value) {
		if (!Double.isFinite(value)) {
			throw new IllegalArgumentException("缩放必须是有限值");
		}
		this.blocksPerPixel = Math.clamp(value, MIN_BLOCKS_PER_PIXEL, MAX_BLOCKS_PER_PIXEL);
	}

	/**
	 * 自动缩放：让每个方块恰好占一个<b>设备像素</b>。
	 *
	 * <p>以设备像素为准而不是 GUI 像素：以 GUI 像素为准时，高 GUI 缩放会把每个方块放大好几倍，
	 * 看起来"放太大"。关闭后改用 {@link #blocksPerPixel()} 指定的固定缩放。
	 */
	public boolean autoZoom() {
		return autoZoom;
	}

	public void setAutoZoom(boolean value) {
		this.autoZoom = value;
	}

	/** 是否让玩家朝向始终朝上（false 表示锁定正北朝上，只有玩家标记转动）。 */
	public boolean rotateWithPlayer() {
		return rotateWithPlayer;
	}

	public void setRotateWithPlayer(boolean value) {
		this.rotateWithPlayer = value;
	}

	/**
	 * 显示哪一层：地表 / 洞穴 / 自动。
	 *
	 * <p>自动模式下走进地下会切到洞穴层，回到地表自动切回，见
	 * {@link sinanmap.common.map.LayerSelector}。
	 */
	public LayerMode layerMode() {
		return layerMode;
	}

	public void setLayerMode(LayerMode value) {
		if (value == null) {
			throw new IllegalArgumentException("层模式不能为空");
		}
		this.layerMode = value;
	}

	/** 吸附到屏幕的哪个角。 */
	public ScreenAnchor anchor() {
		return anchor;
	}

	public void setAnchor(ScreenAnchor value) {
		if (value == null) {
			throw new IllegalArgumentException("锚点不能为空");
		}
		this.anchor = value;
	}

	public int offsetX() {
		return offsetX;
	}

	public void setOffsetX(int value) {
		this.offsetX = value;
	}

	public int offsetY() {
		return offsetY;
	}

	public void setOffsetY(int value) {
		this.offsetY = value;
	}

	/**
	 * 是否叠加"生物雷达"（把小地图/世界地图范围内已加载的生物画成小头像）。
	 *
	 * <p><b>默认开</b>：开箱就该看得见；代价是每帧多遍历一次已加载实体表、再叠一层头像绘制，
	 * 嫌吵或嫌耗可以关掉。实体只取<b>已加载区块</b>内的，不会为了雷达去加载区块。
	 */
	public boolean entityRadarEnabled() {
		return entityRadar;
	}

	public void setEntityRadarEnabled(boolean value) {
		this.entityRadar = value;
	}

	/**
	 * 雷达半径（方块，以玩家为中心）。
	 *
	 * <p><b>默认 64</b>：默认小地图一屏约 240×160 方块（{@link #AUTO_BLOCKS_PER_GUI_PIXEL}），
	 * 64 格正好覆盖中央可见的大部分区域，又不会把远处挤成一团。
	 */
	public double entityRadarRadius() {
		return entityRadarRadius;
	}

	public void setEntityRadarRadius(double value) {
		if (!Double.isFinite(value)) {
			throw new IllegalArgumentException("雷达半径必须是有限值");
		}
		this.entityRadarRadius = Math.clamp(value, MIN_RADAR_RADIUS, MAX_RADAR_RADIUS);
	}

	/** 是否只显示敌对生物（原版 {@code MobCategory.MONSTER}）。默认否，敌对/被动都显示。 */
	public boolean entityRadarHostileOnly() {
		return entityRadarHostileOnly;
	}

	public void setEntityRadarHostileOnly(boolean value) {
		this.entityRadarHostileOnly = value;
	}

	/** 雷达是否按高度差做明暗区分（上=半透、下=变暗、同层=原样）。 */
	public boolean entityRadarHeightIndicator() {
		return entityRadarHeightIndicator;
	}

	public void setEntityRadarHeightIndicator(boolean value) {
		this.entityRadarHeightIndicator = value;
	}

	/** 是否把自己的位置共享给同服务器的其他司南玩家。 */
	public boolean sharePlayerPosition() {
		return sharePlayerPosition;
	}

	public void setSharePlayerPosition(boolean value) {
		this.sharePlayerPosition = value;
	}

	/** 游戏内路径点标记展开时是否显示距离。 */
	public boolean waypointHudDistance() {
		return waypointHudDistance;
	}

	public void setWaypointHudDistance(boolean value) {
		this.waypointHudDistance = value;
	}

	/** 游戏内路径点标记的缩放。 */
	public double waypointHudScale() {
		return waypointHudScale;
	}

	public void setWaypointHudScale(double value) {
		if (!Double.isFinite(value)) {
			throw new IllegalArgumentException("缩放必须是有限值");
		}
		this.waypointHudScale = Math.clamp(value, MIN_HUD_SCALE, MAX_HUD_SCALE);
	}

	/** 游戏内标记缩放是否走自动。 */
	public boolean hudScaleAuto() {
		return hudScaleAuto;
	}

	public void setHudScaleAuto(boolean value) {
		this.hudScaleAuto = value;
	}

	/** 实际用哪个缩放：自动就用默认值，否则用手动那个。 */
	public double effectiveHudScale() {
		return hudScaleAuto ? DEFAULT_HUD_SCALE : waypointHudScale;
	}

	/** 地图上是否画路径点（世界地图与小地图共用）。 */
	public boolean showMapWaypoints() {
		return showMapWaypoints;
	}

	public void setShowMapWaypoints(boolean value) {
		this.showMapWaypoints = value;
	}

	/** 3D 世界里是否画路径点标记。 */
	public boolean showHudWaypoints() {
		return showHudWaypoints;
	}

	public void setShowHudWaypoints(boolean value) {
		this.showHudWaypoints = value;
	}

	/** 是否显示死亡地点。 */
	public boolean showDeathPoints() {
		return showDeathPoints;
	}

	public void setShowDeathPoints(boolean value) {
		this.showDeathPoints = value;
	}

	/** 死亡时是否保留上一次的死亡地点。 */
	public boolean keepOldDeathPoints() {
		return keepOldDeathPoints;
	}

	public void setKeepOldDeathPoints(boolean value) {
		this.keepOldDeathPoints = value;
	}

	/** 是否把传送石碑同步成路径点。 */
	public boolean waystoneIntegration() {
		return waystoneIntegration;
	}

	public void setWaystoneIntegration(boolean value) {
		this.waystoneIntegration = value;
	}

	/** 游戏内路径点标记的不透明度。 */
	public double hudWaypointOpacity() {
		return hudWaypointOpacity;
	}

	public void setHudWaypointOpacity(double value) {
		if (!Double.isFinite(value)) {
			throw new IllegalArgumentException("不透明度必须是有限值");
		}
		this.hudWaypointOpacity = Math.clamp(value, MIN_OPACITY, MAX_OPACITY);
	}

	/** 地图上路径点标记的不透明度。 */
	public double mapWaypointOpacity() {
		return mapWaypointOpacity;
	}

	public void setMapWaypointOpacity(double value) {
		if (!Double.isFinite(value)) {
			throw new IllegalArgumentException("不透明度必须是有限值");
		}
		this.mapWaypointOpacity = Math.clamp(value, MIN_OPACITY, MAX_OPACITY);
	}

	/** 距离超过多少米换算成公里。 */
	public int kilometerThreshold() {
		return kilometerThreshold;
	}

	public void setKilometerThreshold(int value) {
		this.kilometerThreshold = Math.clamp(value, MIN_KM_THRESHOLD, MAX_KM_THRESHOLD);
	}

	/** 走到离路径点多近就不再显示距离（米）；0 = 一直显示。 */
	public int nearHideDistance() {
		return nearHideDistance;
	}

	public void setNearHideDistance(int value) {
		this.nearHideDistance = Math.max(0, value);
	}

	/** 走到离路径点多近就一定展开全名（米）；0 = 不强制，只按"看向才展开"。 */
	public int nearShowFullName() {
		return nearShowFullName;
	}

	public void setNearShowFullName(int value) {
		this.nearShowFullName = Math.max(0, value);
	}

	/** 地图上是否画区块网格。 */
	public boolean chunkGrid() {
		return chunkGrid;
	}

	public void setChunkGrid(boolean value) {
		this.chunkGrid = value;
	}

	/** 区块网格颜色（ARGB）。 */
	public int chunkGridColor() {
		return chunkGridColor;
	}

	public void setChunkGridColor(int value) {
		this.chunkGridColor = value;
	}

	/** 地图上是否标出史莱姆区块。 */
	public boolean slimeChunks() {
		return slimeChunks;
	}

	public void setSlimeChunks(boolean value) {
		this.slimeChunks = value;
	}

	/** 是否在小地图边缘标出东南西北。 */
	public boolean compass() {
		return compass;
	}

	public void setCompass(boolean value) {
		this.compass = value;
	}

	/** 小地图方位字母的颜色（不透明 ARGB）。 */
	public int compassColor() {
		return compassColor;
	}

	public void setCompassColor(int value) {
		this.compassColor = value;
	}

	/** 小地图方位字母的背景色（ARGB）。 */
	public int compassBackgroundColor() {
		return compassBackgroundColor;
	}

	public void setCompassBackgroundColor(int value) {
		this.compassBackgroundColor = value;
	}

	/** 小地图方位字母的大小倍率（1.0 = 基准大小）。 */
	public double compassScale() {
		return compassScale;
	}

	public void setCompassScale(double value) {
		if (!Double.isFinite(value)) {
			throw new IllegalArgumentException("缩放必须是有限值");
		}
		this.compassScale = Math.clamp(value, MIN_COMPASS_SCALE, MAX_COMPASS_SCALE);
	}

	/** 小地图下面显示的信息行（只读视图，顺序即显示顺序）。 */
	public List<InfoLine> infoLines() {
		return List.copyOf(infoLines);
	}

	/** 这一行现在开着吗。 */
	public boolean hasInfoLine(InfoLine line) {
		return infoLines.contains(line);
	}

	/**
	 * 开 / 关一行信息。
	 *
	 * <p>开启时<b>追加到末尾</b>（新开的排在最下面），顺序由玩家再用上移 / 下移调。
	 */
	public void setInfoLineEnabled(InfoLine line, boolean enabled) {
		if (enabled == infoLines.contains(line)) {
			return;
		}
		if (enabled) {
			infoLines.add(line);
		} else {
			infoLines.remove(line);
		}
	}

	/** 把某一行往上 / 往下挪一格（{@code delta} 取 -1 / +1）。 */
	public void moveInfoLine(InfoLine line, int delta) {
		int index = infoLines.indexOf(line);
		int target = index + delta;
		if (index < 0 || target < 0 || target >= infoLines.size()) {
			return;
		}
		infoLines.set(index, infoLines.get(target));
		infoLines.set(target, line);
	}

	/** 信息文本的对齐。 */
	public TextAlign infoAlign() {
		return infoAlign;
	}

	public void setInfoAlign(TextAlign value) {
		this.infoAlign = value;
	}

	/** 时间是否 24 小时制。 */
	public boolean clock24() {
		return clock24;
	}

	public void setClock24(boolean value) {
		this.clock24 = value;
	}

	// ---------------------------------------------------------------- 性能：存的是"手动值或 AUTO"，用的时候一律走 effective*

	/** 后台读盘线程（手动值；{@link #AUTO} = 自动）。 */
	public int prefetchThreads() {
		return prefetchThreads;
	}

	public void setPrefetchThreads(int value) {
		this.prefetchThreads = clampOrAuto(value, MIN_PREFETCH_THREADS, MAX_PREFETCH_THREADS);
	}

	/** 实际用几条读盘线程。 */
	public int effectivePrefetchThreads() {
		return prefetchThreads == AUTO ? autoPrefetchThreads() : prefetchThreads;
	}

	/** 洞穴扫描线程（手动值；{@link #AUTO} = 自动）。 */
	public int caveScanThreads() {
		return caveScanThreads;
	}

	public void setCaveScanThreads(int value) {
		this.caveScanThreads = clampOrAuto(value, MIN_CAVE_SCAN_THREADS, MAX_CAVE_SCAN_THREADS);
	}

	public int effectiveCaveScanThreads() {
		return caveScanThreads == AUTO ? DEFAULT_CAVE_SCAN_THREADS : caveScanThreads;
	}

	/** 每帧搬运上限（手动值；{@link #AUTO} = 自动）。 */
	public int appliedPerFrame() {
		return appliedPerFrame;
	}

	public void setAppliedPerFrame(int value) {
		this.appliedPerFrame = clampOrAuto(value, MIN_APPLIED_PER_FRAME, MAX_APPLIED_PER_FRAME);
	}

	public int effectiveAppliedPerFrame() {
		return appliedPerFrame == AUTO ? DEFAULT_APPLIED_PER_FRAME : appliedPerFrame;
	}

	/** 每帧搬运时间预算（毫秒；{@link #AUTO} = 自动）。 */
	public int applyBudgetMillis() {
		return applyBudgetMillis;
	}

	public void setApplyBudgetMillis(int value) {
		this.applyBudgetMillis = clampOrAuto(value, MIN_APPLY_BUDGET_MILLIS, MAX_APPLY_BUDGET_MILLIS);
	}

	public long effectiveApplyBudgetNanos() {
		int millis = applyBudgetMillis == AUTO ? DEFAULT_APPLY_BUDGET_MILLIS : applyBudgetMillis;
		return millis * 1_000_000L;
	}

	/** 每页推进量（手动值；{@link #AUTO} = 自动）。 */
	public int chunksPerVisit() {
		return chunksPerVisit;
	}

	public void setChunksPerVisit(int value) {
		this.chunksPerVisit = clampOrAuto(value, MIN_CHUNKS_PER_VISIT, MAX_CHUNKS_PER_VISIT);
	}

	public int effectiveChunksPerVisit() {
		return chunksPerVisit == AUTO ? DEFAULT_CHUNKS_PER_VISIT : chunksPerVisit;
	}

	/** 同时在填页数上限（手动值；{@link #AUTO} = 自动）。 */
	public int fillingPages() {
		return fillingPages;
	}

	public void setFillingPages(int value) {
		this.fillingPages = clampOrAuto(value, MIN_FILLING_PAGES, MAX_FILLING_PAGES);
	}

	public int effectiveFillingPages() {
		return fillingPages == AUTO ? DEFAULT_FILLING_PAGES : fillingPages;
	}

	/** 每帧并行推进页数（手动值；{@link #AUTO} = 自动）。 */
	public int fillingPagesPerFrame() {
		return fillingPagesPerFrame;
	}

	public void setFillingPagesPerFrame(int value) {
		this.fillingPagesPerFrame = clampOrAuto(value,
				MIN_FILLING_PAGES_PER_FRAME, MAX_FILLING_PAGES_PER_FRAME);
	}

	public int effectiveFillingPagesPerFrame() {
		return fillingPagesPerFrame == AUTO ? DEFAULT_FILLING_PAGES_PER_FRAME : fillingPagesPerFrame;
	}

	/** 手动值统一钳位；{@link #AUTO} 原样留着（它表示"自动"而不是某个数值）。 */
	private static int clampOrAuto(int value, int min, int max) {
		return value == AUTO ? AUTO : Math.clamp(value, min, max);
	}

	/** 世界地图的最高细节档。 */
	public int mapDetailTier() {
		return mapDetailTier;
	}

	public void setMapDetailTier(int value) {
		this.mapDetailTier = Math.clamp(value, MIN_DETAIL_TIER, MAX_DETAIL_TIER);
	}

	/** 复制一份，便于测试与后续配置界面的撤销操作。 */
	public MinimapSettings copy() {
		MinimapSettings copy = new MinimapSettings();
		copy.copyFrom(this);
		return copy;
	}

	/** 把全部设置恢复成默认值（设置界面的"重置设置"用）。 */
	public void resetToDefaults() {
		copyFrom(new MinimapSettings());
	}

	/** 把 {@code other} 的全部字段抄到自己身上（{@link #copy()} 与 {@link #resetToDefaults()} 共用）。 */
	private void copyFrom(MinimapSettings other) {
		this.enabled = other.enabled;
		this.width = other.width;
		this.height = other.height;
		this.blocksPerPixel = other.blocksPerPixel;
		this.autoZoom = other.autoZoom;
		this.rotateWithPlayer = other.rotateWithPlayer;
		this.layerMode = other.layerMode;
		this.anchor = other.anchor;
		this.offsetX = other.offsetX;
		this.offsetY = other.offsetY;
		this.entityRadar = other.entityRadar;
		this.entityRadarRadius = other.entityRadarRadius;
		this.entityRadarHostileOnly = other.entityRadarHostileOnly;
		this.entityRadarHeightIndicator = other.entityRadarHeightIndicator;
		this.sharePlayerPosition = other.sharePlayerPosition;
		this.waypointHudDistance = other.waypointHudDistance;
		this.waypointHudScale = other.waypointHudScale;
		this.hudScaleAuto = other.hudScaleAuto;
		this.showMapWaypoints = other.showMapWaypoints;
		this.showHudWaypoints = other.showHudWaypoints;
		this.showDeathPoints = other.showDeathPoints;
		this.keepOldDeathPoints = other.keepOldDeathPoints;
		this.waystoneIntegration = other.waystoneIntegration;
		this.hudWaypointOpacity = other.hudWaypointOpacity;
		this.mapWaypointOpacity = other.mapWaypointOpacity;
		this.kilometerThreshold = other.kilometerThreshold;
		this.nearHideDistance = other.nearHideDistance;
		this.nearShowFullName = other.nearShowFullName;
		this.chunkGrid = other.chunkGrid;
		this.chunkGridColor = other.chunkGridColor;
		this.slimeChunks = other.slimeChunks;
		this.compass = other.compass;
		this.compassColor = other.compassColor;
		this.compassBackgroundColor = other.compassBackgroundColor;
		this.compassScale = other.compassScale;
		this.infoLines.clear();
		this.infoLines.addAll(other.infoLines);
		this.infoAlign = other.infoAlign;
		this.clock24 = other.clock24;
		this.prefetchThreads = other.prefetchThreads;
		this.caveScanThreads = other.caveScanThreads;
		this.appliedPerFrame = other.appliedPerFrame;
		this.applyBudgetMillis = other.applyBudgetMillis;
		this.chunksPerVisit = other.chunksPerVisit;
		this.fillingPages = other.fillingPages;
		this.fillingPagesPerFrame = other.fillingPagesPerFrame;
		this.mapDetailTier = other.mapDetailTier;
	}
}
