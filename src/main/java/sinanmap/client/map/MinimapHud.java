package sinanmap.client.map;

import java.util.List;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.level.biome.Biome;

import org.joml.Matrix3x2fStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sinanmap.common.SinanConstants;
import sinanmap.common.coord.CoordinateSpaces;
import sinanmap.common.coord.Dimensions;
import sinanmap.common.coord.Heading;
import sinanmap.common.coord.ScreenPoint;
import sinanmap.common.hud.MinimapLayout;
import sinanmap.common.hud.MinimapSettings;
import sinanmap.common.hud.ScreenAnchor;
import sinanmap.common.hud.ScreenRect;
import sinanmap.common.map.CenterOutSweep;
import sinanmap.common.map.LayerSelector;
import sinanmap.common.map.ZoomAnimator;
import sinanmap.common.net.TrackedPlayersS2C;
import sinanmap.common.waypoint.Waypoint;
import sinanmap.client.map.data.ClientChunkColorProvider;
import sinanmap.client.map.data.MapStorage;
import sinanmap.client.map.render.MarkerRenderer;
import sinanmap.client.map.render.TilePage;
import sinanmap.client.map.render.TileVariants;
import sinanmap.client.map.render.WorldTilePages;
import sinanmap.client.mixin.ToastManagerAccessor;
import sinanmap.client.radar.EntityRadar;
import sinanmap.client.radar.TrackedPlayers;
import sinanmap.client.waypoint.WaypointManager;
import sinanmap.client.waypoint.WaypointRenderer;

/**
 * 小地图 HUD（矩形）。
 *
 * <p><b>底图与全屏地图共用同一套瓦片页</b>（{@link WorldTilePages}）：这里只做三件事——
 * 按这一屏真正看得见的区块把页补出来（{@link #warmVisible}）、在一个"平移到小地图中心 →
 * 按视角旋转 → 缩放到方块坐标系 → 平移到玩家坐标"的矩阵里把页整页贴出去（{@link #drawTerrain}）、
 * 再画路径点与玩家箭头。页是烘焙好的，平移、转动、缩放都只是换个位置贴
 * （以前每帧逐像素合成整张，实测 10~35 ms，是移动卡顿的根源）。
 *
 * <p>玩家标记是独立的小贴图，每帧按朝向旋转绘制，因此转身不会触发任何重算。
 *
 * <p>绘制位置<b>刻意不对齐设备像素</b>，按连续位置画，交给页贴图的线性过滤抹平半个像素以内的偏差
 * （见 {@link SmoothedDynamicTexture}）：对齐会让走动时整片地形一格一格地跳。
 */
public final class MinimapHud {

	private static final Logger LOGGER = LoggerFactory.getLogger(SinanConstants.MOD_ID);

	/**
	 * 未探明区域那层暗色。
	 *
	 * <p>瓦片页里"没有数据"的像素是<b>全透明</b>的，全屏地图有自己的底色、小地图没有；不铺这一层，
	 * 未探明的部分就是彻底透明、看不出小地图的边界。
	 */
	private static final int UNEXPLORED_BACKGROUND = 0x80000000;

	/** 小地图每帧最多把几页传上显存。一页两张各 1 MB，不设上限会白灌显存带宽。 */
	private static final int MAX_UPLOADS_PER_FRAME = 2;

	/** 切换层级时先放大的倍率，让层与层之间是一次缩放过场而不是硬切。 */
	private static final double LAYER_PULSE = 1.35;

	/** 坐标文字的缩放（原版字体没有小号）。 */
	private static final float TEXT_SCALE = 0.7F;

	/** 每次缩放按键的倍率。 */
	public static final double ZOOM_STEP = 1.15;

	/** 玩家标记在屏幕上的边长相对小地图短边的比例（固定 GUI 比例 → 跟着界面等比放大）。 */
	private static final double MARKER_SCREEN_RATIO = 0.12;

	private final MinimapSettings settings;
	private final ClientChunkColorProvider colorProvider;
	private final MarkerRenderer markerRenderer = new MarkerRenderer();
	private final ZoomAnimator zoom = new ZoomAnimator(1.0);

	/** 生物雷达：附近已加载生物的小头像。只在渲染线程用，见 {@link EntityRadar}。 */
	private final EntityRadar entityRadar = new EntityRadar();

	/**
	 * 与全屏地图<b>共用</b>的那一套瓦片页。
	 *
	 * <p>在构造函数里取（不在这里初始化）：它按 {@code colorProvider} 认主，而那个字段要等
	 * 构造函数才赋值。
	 */
	private final WorldTilePages tiles;

	/**
	 * 切层过场的显示倍率：切层时瞬间放大一点，再平滑回到 1。
	 *
	 * <p>只改绘制时的缩放，<b>不重新采样地形</b>，所以这个动画是免费的。
	 */
	private final ZoomAnimator layerTransition = new ZoomAnimator(1.0);

	/**
	 * 右上角被原版药水图标 / 成就弹窗占着时，HUD 整体下移的动画偏移（GUI 像素）。
	 *
	 * <p>偏移量按时间指数逼近目标（见 {@link ZoomAnimator}），弹窗出现/消失都是一次过渡而不是瞬移，
	 * 不会让小地图"啪"地跳一下。只改绘制落点，<b>不动用户设置的锚点</b>。
	 */
	private final ZoomAnimator topRightShift = new ZoomAnimator(0.0);

	/** 进世界后画第一帧时记一行：小地图现在画的是与全屏地图共用的那套瓦片。 */
	private boolean firstFrameLogged;

	/**
	 * 小地图是否已经"出现过"。
	 *
	 * <p>进世界、以及从配置里重新打开小地图时置否，那一帧播放一次"由大缩小"的过场
	 * （见 {@link #APPEAR_SCALE}）；离开世界或关掉小地图时重置，下次再进来会重播。
	 */
	private boolean appeared;

	private long lastFrameNanos;

	/** 上一帧显示的是哪一层；只在"地表 ↔ 洞穴"这种类型变化时放过场。 */
	private int lastLayer = Integer.MAX_VALUE;

	/** 预热扫描的游标：以玩家为中心，一圈圈向外补这一屏看得见的区块。 */
	private CenterOutSweep fillSweep;
	private int fillSweepColumns;
	private int fillSweepRows;
	private int fillSweepLayer = Integer.MAX_VALUE;

	/**
	 * 这一帧小地图覆盖的<b>区块</b>范围与<b>页</b>范围。
	 *
	 * <p>补数据与绘制都用它：前者按区块点名（只看得到几十块，没必要把整页填满），
	 * 后者按页整页贴。
	 */
	private int viewMinChunkX = Integer.MAX_VALUE;
	private int viewMinChunkZ = Integer.MAX_VALUE;
	private int viewMaxChunkX = Integer.MIN_VALUE;
	private int viewMaxChunkZ = Integer.MIN_VALUE;
	private int viewMinPageX;
	private int viewMinPageZ;
	private int viewMaxPageX;
	private int viewMaxPageZ;

	public MinimapHud(ClientChunkColorProvider colorProvider, MinimapSettings settings) {
		this.colorProvider = colorProvider;
		this.settings = settings;
		this.tiles = WorldTilePages.shared(colorProvider);
		// 让位用"过场"那种较慢的指数逼近：45ms 的时间常数一闪而过，看不出是一次过渡
		this.topRightShift.setSlow(true);
	}

	/** 注册到 Fabric 的 HUD 元素注册表，画在所有原版 HUD 之上。 */
	public void register() {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(SinanConstants.MOD_ID, "minimap"), this::render);
		LOGGER.info("小地图 HUD 已注册 | {}×{} 自动缩放={} 锁定正北={} 位置={}",
				settings.width(), settings.height(), settings.autoZoom(),
				!settings.rotateWithPlayer(), settings.anchor());
	}

	/** 供配置界面与按键使用的可调项。 */
	public MinimapSettings settings() {
		return settings;
	}

	/**
	 * 给 HUD 编辑界面用：把这一帧的小地图按当前设置画一遍。
	 *
	 * <p>编辑界面开着的时候，HUD 这一层本身不会被渲染（原版开界面时就不画 HUD 了），所以要由
	 * 编辑界面自己把"要拖的那个东西"画出来——画的必须是同一套布局计算结果，
	 * 否则"看见的位置"和"拖动改的位置"会对不上。
	 */
	public void renderPreview(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		render(graphics, deltaTracker);
	}

	/** 按倍率调整小地图缩放（&gt;1 缩小视野、&lt;1 放大）。会关闭自动缩放。 */
	public void zoomBy(double factor) {
		settings.setAutoZoom(false);
		settings.setBlocksPerPixel(zoom.target() * factor);
	}

	/**
	 * 把缩放目标设成一个<b>绝对值</b>（每设备像素多少方块），会关闭自动缩放。
	 *
	 * <p>给"按住 - / + 连续缩放"用（见 {@code SinanKeyMappings}）：那一路按"按住多久"直接算出目标值，
	 * 不再一次一格地跳，小地图渲染里的 {@link ZoomAnimator} 会把它平滑地追平，免得一格一停。
	 */
	public void zoomTo(double value) {
		settings.setAutoZoom(false);
		settings.setBlocksPerPixel(value);
	}

	/** 当前缩放目标值（每设备像素多少方块），供连续缩放算基准用。 */
	public double zoomTarget() {
		return zoom.target();
	}

	private void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		// 每帧心跳：小地图 HUD 每帧必执行且早于全屏地图（HUD 先于 Screen 抽取），
		// 由它给感知器标出帧边界，两个界面同一帧的预算与改动检测据此认成同一帧（见 beginFrame）。
		// 放在 enabled 判断之前：关了 HUD 帧号也要照常推进。
		colorProvider.beginFrame();
		if (!settings.enabled()) {
			// 关掉再打开时重新播一次"出现"过场
			appeared = false;
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null) {
			// 离开世界（回主菜单、切存档）后再进来也要重播
			appeared = false;
			return;
		}

		int guiScale = Math.max(1, minecraft.getWindow().getGuiScale());
		long now = System.nanoTime();
		double deltaMillis = lastFrameNanos == 0L ? 0.0 : (now - lastFrameNanos) / 1.0e6;
		lastFrameNanos = now;

		// 天色每帧对齐一次，否则进世界的第一帧会拿着"白天"的天色去画夜晚的小地图
		colorProvider.refreshSky();

		// 自动档：取 AUTO_BLOCKS_PER_GUI_PIXEL（按"每 GUI 像素多少方块"），再除以 GUI 缩放换算成设备
		// 像素（zoom 存的是设备像素口径）。夹一条"不小于 1 个方块每设备像素"：贴图就是一个方块一个
		// 像素，比 1:1 更近只是把贴图放大、地形变成一块块色块；高 GUI 缩放下不夹的话自动档很容易
		// 落到 1 以下，小地图就一直处于放大状态。夹住之后窗口/GUI 缩放怎么变，覆盖范围都不变。
		//
		// 手动档：单位就是"每设备像素多少方块"，1 就是 1:1、正好是贴图的原始清晰度，所以<b>既不除以
		// GUI 缩放、也不夹</b>。曾经为了"覆盖范围不随窗口变"把它也除了 GUI 缩放，等于强行把贴图放大成
		// 2 倍，小地图立刻变糊。覆盖范围随 GUI 缩放变化是手动档的固有代价，
		// 想"范围不随窗口变"就用自动档。
		zoom.setTarget(settings.autoZoom()
				? Math.max(1.0, MinimapSettings.AUTO_BLOCKS_PER_GUI_PIXEL / guiScale)
				: settings.blocksPerPixel());
		zoom.update(deltaMillis);

		float yaw = player.getYRot();
		float rotation = settings.rotateWithPlayer() ? Heading.followRotation(yaw) : 0.0F;
		double playerX = player.getX();
		double playerZ = player.getZ();
		// zoom 给的是"一个设备像素代表多少方块"，换算到 GUI 像素要再乘 GUI 缩放。
		// 页贴图是"一个像素一个方块"，所以这两个数就是"一页画多大"的全部依据
		double blocksPerDevice = zoom.current();
		double blocksPerGui = blocksPerDevice * guiScale;

		// 决定显示哪一层：地表或洞穴切面（自动模式下走进地下会自己切过去）。
		// 层怎么判定统一在 colorProvider 里，不和全屏地图各算一套
		int layer = colorProvider.resolveLayer(settings.layerMode());
		if (!appeared) {
			// 小地图刚出现：由大缩回正常尺寸，而不是"啪"地一下跳出来
			appeared = true;
			layerTransition.snapTo(APPEAR_SCALE);
		}
		if (layer != lastLayer && lastLayer != Integer.MAX_VALUE
				&& !LayerSelector.isSameKind(lastLayer, layer)) {
			// 只有"地表 ↔ 洞穴"这种类型变化才做过场：洞穴模式下显示层逐格跟着玩家高度变
			// （见 LayerSelector#resolve），否则在洞里走动会不停放大缩小。
			// 方向：往深处"推进"、往浅处"退出"。只改绘制倍率、不重采样地形，动画免费。
			layerTransition.snapTo(LayerSelector.isDeeper(lastLayer, layer) ? 1.0 / LAYER_PULSE : LAYER_PULSE);
		}
		lastLayer = layer;
		layerTransition.setTarget(1.0);
		layerTransition.update(deltaMillis);
		colorProvider.setLayer(layer);

		// 层类型变化（地表 ↔ 洞穴）与日夜切换：左下角闪一下对应的图标。
		// 洞穴模式下显示层逐格变（同一个洞穴里的高度），不算"换了层"——闪图标反而吵
		int underground = LayerSelector.isCave(layer) ? 1 : 0;
		if (lastUnderground < 0) {
			lastUnderground = underground;
		} else if (underground != lastUnderground) {
			lastUnderground = underground;
			showSwitchIcon(underground == 1 ? UI_CAVE_TEXTURE : UI_SURFACE_TEXTURE, now);
		}
		int night = isNight() ? 1 : 0;
		if (lastNight < 0) {
			lastNight = night;
		} else if (night != lastNight) {
			lastNight = night;
			showSwitchIcon(night == 1 ? UI_NIGHT_TEXTURE : UI_DAY_TEXTURE, now);
		}
		// 天气切换：与"白天/夜晚"同一套淡入—停留—淡出。转晴不闪（雨停后没必要再提示一次"晴"），
		// 只有真的进入下雨/雷暴/下雪才提示。
		int weather = weatherState();
		if (lastWeather < 0) {
			lastWeather = weather;
		} else if (weather != lastWeather) {
			lastWeather = weather;
			if (weather != WEATHER_CLEAR) {
				showWeatherIcon(weather, now);
			}
		}

		// 地形改动检测：与全屏地图共用同一条通道，谁取走改动列表谁负责作废对应瓦片
		// （两边画的都是同一批页，谁做都一样）。若留着给全屏地图处理，它没打开时那些改动就没人管。
		colorProvider.refreshLoadedChunks(now);
		for (long packed : colorProvider.drainChangedChunks()) {
			tiles.markStale(layer, (int) (packed >> 32), (int) packed);
		}
		// 后台刚读到数据的地方：只补画那几格，已经画着地形的格子不动（见 WorldTilePages#markArrived）
		for (long packed : colorProvider.drainArrivedChunks()) {
			tiles.markArrived(layer, (int) (packed >> 32), (int) packed);
		}

		// 右上角被原版的药水效果图标或成就弹窗占着时往下让开，见 topRightAvoidance。
		// 偏移按时间平滑逼近目标：弹窗出现/消失都是一次过渡，不会瞬移抖动。
		boolean topRight = settings.anchor() == ScreenAnchor.TOP_RIGHT;
		topRightShift.setTarget(topRight ? topRightAvoidance(player) : 0.0);
		topRightShift.update(deltaMillis);
		int pushDown = (int) Math.round(topRightShift.current());
		ScreenRect rect = MinimapLayout.compute(settings.anchor(), settings.offsetX(),
				settings.offsetY() + pushDown,
				settings.width(), settings.height(), graphics.guiWidth(), graphics.guiHeight());

		// 换世界就换一套页（同一个世界则什么都不做，界面反复开关也不会重填）
		tiles.ensureLevel(minecraft.level);
		tiles.ensureWorld(MapStorage.currentRoot(minecraft));

		// 补数据只补"这一屏真正看得见的那些块"，不把整页填满：一页 512 方块，
		// 小地图一屏只看得到几十块，按页序推进要等很久才轮到玩家脚底下。
		updateViewRange(rect, playerX, playerZ, rotation, blocksPerGui);
		// 一笔预算，先后两件事：先补"以前没数据、现在有了"的块（重试轮转，每 8 秒才真跑一次），
		// 再把这一屏看得见的块补上。
		//
		// 小地图也要调重试：它是常驻界面，区块"当时没数据、过一会儿就有"很常见；
		// 只由全屏地图调的话，小地图上那几块会一直空着。
		colorProvider.beginWarm();
		tiles.retryUnexplored(now);
		warmVisible(layer, playerX, playerZ);

		drawTerrain(graphics, rect, layer, playerX, playerZ, rotation, blocksPerGui, blocksPerDevice < 1.0);
		drawWaypoints(graphics, rect, playerX, playerZ, rotation, guiScale, blocksPerDevice);
		drawTrackedPlayers(graphics, rect, playerX, playerZ, rotation, guiScale, blocksPerDevice);
		// 生物雷达叠在地形与路径点之上：位置与路径点用同一套换算，头像在屏幕坐标系里正着画，
		// 不跟着地图转/翻。收集与绘制只在渲染线程执行，复用内部数组、不产生每帧分配（见 EntityRadar）
		entityRadar.refresh(minecraft.level, player, settings);
		// 头像边长由 EntityRadar 自己算（和全屏地图共用同一条规则），这里不再传
		entityRadar.drawOnMinimap(graphics, rect, rotation, playerX, player.getY(), playerZ,
				layerTransition.current() / Math.max(1.0e-6, blocksPerGui));
		drawSwitchIcon(graphics, rect, now);

		// 标记单独绘制：它随视角转动，烘焙进地形贴图会导致每帧重合成整张地图。
		// 方向与地形、路径点用的是同一个变换（见 Heading#screenDirection），
		// 于是锁定正北时箭头跟着罗盘转、跟随视角时箭头永远朝上
		ScreenPoint direction = Heading.screenDirection(rotation, yaw);
		double markerSize = Math.min(rect.width(), rect.height()) * MARKER_SCREEN_RATIO;
		markerRenderer.draw(graphics,
				rect.x() + rect.width() / 2.0, rect.y() + rect.height() / 2.0,
				markerSize, direction.x(), direction.y());

		// 方位字母画在小地图里所有东西的最上层：路径点、生物头像、玩家箭头、切换图标之后，
		// 免得被它们盖住（位置与地形同一套旋转，见 drawCompass）
		drawCompass(graphics, rect, rotation);

		if (!firstFrameLogged) {
			firstFrameLogged = true;
			LOGGER.info("小地图已改为画共享瓦片 | {}×{} GUI × 缩放 {} | 每方块={} 设备像素 覆盖={}×{} 方块 层={}",
					settings.width(), settings.height(), guiScale,
					"%.2f".formatted(1.0 / Math.max(1.0e-9, blocksPerDevice)),
					Math.round(rect.width() * blocksPerGui), Math.round(rect.height() * blocksPerGui),
					LayerSelector.isCave(layer) ? "洞穴 Y=" + layer : "地表");
		}

		drawInfoLines(graphics, minecraft, player, rect);
	}

	/**
	 * 小地图下面那一行行信息（坐标 / 时间 / 群系 / 维度）。
	 *
	 * <p>显示哪几行、什么顺序、左/中/右对齐，全按设置来（见 {@code MinimapSettings#infoLines()}），
	 * 加行或调顺序都不用改这里。
	 */
	private void drawInfoLines(GuiGraphicsExtractor graphics, Minecraft minecraft, LocalPlayer player,
			ScreenRect rect) {
		List<MinimapSettings.InfoLine> lines = settings.infoLines();
		if (lines.isEmpty()) {
			return;
		}
		Font font = minecraft.font;
		double lineHeight = font.lineHeight * TEXT_SCALE + 1;
		double y = rect.bottom() + 3;
		MinimapSettings.TextAlign align = settings.infoAlign();
		for (MinimapSettings.InfoLine line : lines) {
			String text = infoText(minecraft, player, line);
			if (text == null || text.isEmpty()) {
				continue;
			}
			double textWidth = font.width(text) * TEXT_SCALE;
			double x = switch (align) {
				case LEFT -> rect.x();
				case CENTER -> rect.x() + (rect.width() - textWidth) / 2.0;
				case RIGHT -> rect.right() - textWidth;
			};
			drawSmallText(graphics, font, text, x, y);
			y += lineHeight;
		}
	}

	/** 一行的文本；这一行现在没法显示（比如世界还没加载）时返回 {@code null}。 */
	private String infoText(Minecraft minecraft, LocalPlayer player, MinimapSettings.InfoLine line) {
		ClientLevel level = minecraft.level;
		return switch (line) {
			case COORDS -> player.getBlockX() + ", " + player.getBlockY() + ", " + player.getBlockZ();
			case TIME -> level == null ? null : clockText(level.getLevelData().getGameTime());
			case BIOME -> level == null ? null : biomeName(level, player);
			case DIMENSION -> level == null ? null : Component
					.translatable(Dimensions.translationKey(level.dimension().identifier().toString()))
					.getString();
		};
	}

	/**
	 * 游戏内时间：把 0~24000 的 tick 换算成时刻。
	 *
	 * <p>原版第 0 tick 是早上 6 点，所以先加 6000 再取模（与"天亮了"的观感一致）；
	 * 12 小时制带上 上午 / 下午。
	 */
	private String clockText(long dayTime) {
		long ticks = Math.floorMod(dayTime, 24000L);
		long totalMinutes = (ticks + 6000L) % 24000L * 60L / 1000L;
		int hour = (int) (totalMinutes / 60L);
		int minute = (int) (totalMinutes % 60L);
		if (settings.clock24()) {
			return "%02d:%02d".formatted(hour, minute);
		}
		String suffix = Component.translatable(hour < 12
				? "screen.sinanmap.hud_edit.am" : "screen.sinanmap.hud_edit.pm").getString();
		int hour12 = hour % 12;
		if (hour12 == 0) {
			hour12 = 12;
		}
		return "%d:%02d %s".formatted(hour12, minute, suffix);
	}

	/** 当前群系的名字（走原版的群系翻译键，别的模组注册的群系也有各自的名字）。 */
	private static String biomeName(ClientLevel level, LocalPlayer player) {
		return level.getBiome(player.blockPosition()).unwrapKey()
				.map(key -> Component.translatable(
						"biome." + key.identifier().getNamespace() + "." + key.identifier().getPath()).getString())
				.orElse("");
	}

	/**
	 * 切换提示：地表／洞穴、白天／夜晚变化时，在小地图左下角闪一下对应图标。
	 *
	 * <p>图标与全屏地图那些按钮<b>是同一批 GUI 图集 sprite</b>（纯白 + 右下 1px 灰阴影），颜色绘制时乘上去。
	 */
	private static final Identifier UI_SURFACE_TEXTURE = guiIcon("surface");
	private static final Identifier UI_CAVE_TEXTURE = guiIcon("cave");
	private static final Identifier UI_DAY_TEXTURE = guiIcon("day");
	private static final Identifier UI_NIGHT_TEXTURE = guiIcon("night");

	/** 地表 / 洞穴这类提示图标自身的像素边长（16 格本体 + 右下 1px 阴影）。 */
	private static final int UI_ICON_SIZE = 17;

	private static Identifier guiIcon(String name) {
		return Identifier.fromNamespaceAndPath(SinanConstants.MOD_ID, name);
	}

	/** 提示图标画出来的边长：源图是 {@value #UI_ICON_SIZE}，画小一点，别盖住小地图。 */
	private static final int SWITCH_ICON_SIZE = 10;
	private static final int SWITCH_ICON_MARGIN = 2;
	private static final long SWITCH_FADE_IN_NANOS = 180_000_000L;
	private static final long SWITCH_HOLD_NANOS = 600_000_000L;
	private static final long SWITCH_FADE_OUT_NANOS = 420_000_000L;

	/** 天空光衰减到几就算夜里。与原版 {@code Level#isDay} 的阈值一致。 */
	private static final int NIGHT_SKY_DARKEN = 4;

	/**
	 * 天气切换图标：GUI 图集里的 sprite。
	 *
	 * <p>判定口径——雷 > 雨 > 雪 > 晴，与画面里看到的天气一致。
	 */
	private static final Identifier WEATHER_RAIN_TEXTURE = weatherTexture("weather_rain");
	private static final Identifier WEATHER_THUNDER_TEXTURE = weatherTexture("weather_thunder");
	private static final Identifier WEATHER_SNOW_TEXTURE = weatherTexture("weather_snow");
	/** 天气 sprite 自身的像素边长（这几张图都按 1:1 画成 10×10，正好等于 {@value #SWITCH_ICON_SIZE}）。 */
	private static final int WEATHER_TEXTURE_SIZE = 10;
	/** 天气档位。 */
	private static final int WEATHER_CLEAR = 0;
	private static final int WEATHER_RAIN = 1;
	private static final int WEATHER_THUNDER = 2;
	private static final int WEATHER_SNOW = 3;

	private static Identifier weatherTexture(String name) {
		return Identifier.fromNamespaceAndPath(SinanConstants.MOD_ID, name);
	}

	/** 当前要闪的那张图标；{@code null} 表示没有要闪的。 */
	private Identifier switchTexture;
	/** 这次要画的图标在<b>源贴图</b>里的边长（地表/洞穴那一批是 {@value #UI_ICON_SIZE}，天气图是 16）。 */
	private int switchSourceSize = UI_ICON_SIZE;
	private long switchStartNanos;
	/** 上次的"在洞穴里"/"是夜晚"/天气档位，-1 表示还没记过——避免第一帧就闪一次。 */
	private int lastUnderground = -1;
	private int lastNight = -1;
	private int lastWeather = -1;

	/** 记下这次要闪的图标（地表/洞穴/白天/夜晚那一批独立贴图）；{@code now} 是 {@link System#nanoTime()}。 */
	private void showSwitchIcon(Identifier texture, long now) {
		switchTexture = texture;
		switchSourceSize = UI_ICON_SIZE;
		switchStartNanos = now;
	}

	/** 记下这次要闪的<b>天气</b>图标（下雨/雷暴/下雪各自一张图；<b>晴朗不提示</b>）。 */
	private void showWeatherIcon(int weather, long now) {
		Identifier texture = switch (weather) {
			case WEATHER_THUNDER -> WEATHER_THUNDER_TEXTURE;
			case WEATHER_RAIN -> WEATHER_RAIN_TEXTURE;
			case WEATHER_SNOW -> WEATHER_SNOW_TEXTURE;
			default -> null; // 晴朗：没有图标，也不闪（无天气无需切换动画）
		};
		if (texture == null) {
			return;
		}
		switchTexture = texture;
		switchSourceSize = WEATHER_TEXTURE_SIZE;
		switchStartNanos = now;
	}

	/**
	 * 当前天气档位：雷 → 雨 → 雪 → 晴。
	 *
	 * <p>雷/雨看原版的降雨与雷暴强度（0~1，客户端每帧推进），所以雨刚下起来/刚停的那几帧也能跟着动。
	 * 雪是"在下雨、但玩家所在地的群系降的是雪"，用 {@link Biome#getPrecipitationAt} 判。
	 */
	private int weatherState() {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		LocalPlayer player = minecraft.player;
		if (level == null || player == null) {
			return WEATHER_CLEAR;
		}
		if (level.getThunderLevel(1.0F) > RAIN_LEVEL_THRESHOLD) {
			return WEATHER_THUNDER;
		}
		if (level.getRainLevel(1.0F) > RAIN_LEVEL_THRESHOLD) {
			return isSnowingAt(level, player) ? WEATHER_SNOW : WEATHER_RAIN;
		}
		return WEATHER_CLEAR;
	}

	/** 降雨/雷暴强度超过它才算"正在下"。取 0.35：足够小，雨刚开始下就会切图标，又不会来回抖。 */
	private static final float RAIN_LEVEL_THRESHOLD = 0.35F;

	/** 玩家脚下这个群系此刻降的是不是雪。 */
	private static boolean isSnowingAt(ClientLevel level, LocalPlayer player) {
		try {
			BlockPos pos = player.blockPosition();
			return level.getBiome(pos).value().getPrecipitationAt(pos, level.getSeaLevel())
					== Biome.Precipitation.SNOW;
		} catch (RuntimeException e) {
			return false;
		}
	}

	/**
	 * 在小地图左下角画切换图标：淡入、停留、淡出。
	 *
	 * <p>整段不过一秒出头，只折成一个 alpha 乘上去，没有状态机，不重传任何东西。
	 */
	private void drawSwitchIcon(GuiGraphicsExtractor graphics, ScreenRect rect, long now) {
		if (switchTexture == null) {
			return;
		}
		long elapsed = now - switchStartNanos;
		long holdEnd = SWITCH_FADE_IN_NANOS + SWITCH_HOLD_NANOS;
		if (elapsed < 0L || elapsed >= holdEnd + SWITCH_FADE_OUT_NANOS) {
			switchTexture = null;
			return;
		}
		float alpha;
		if (elapsed < SWITCH_FADE_IN_NANOS) {
			alpha = elapsed / (float) SWITCH_FADE_IN_NANOS;
		} else if (elapsed < holdEnd) {
			alpha = 1.0F;
		} else {
			alpha = 1.0F - (elapsed - holdEnd) / (float) SWITCH_FADE_OUT_NANOS;
		}
		int color = ((int) (Math.clamp(alpha, 0.0F, 1.0F) * 255.0F) << 24) | 0xFFFFFF;
		int x = rect.x() + SWITCH_ICON_MARGIN;
		int y = rect.bottom() - SWITCH_ICON_SIZE - SWITCH_ICON_MARGIN;
		// 源 sprite 是 switchSourceSize 见方，这里按 SWITCH_ICON_SIZE 缩放：缩放交给变换矩阵，
		// blitSprite 只负责把整张 sprite 画到 switchSourceSize 见方（它会按图集里记录的精灵尺寸绘制）
		float scale = SWITCH_ICON_SIZE / (float) switchSourceSize;
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(scale, scale);
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, switchTexture, 0, 0,
				switchSourceSize, switchSourceSize, color);
		pose.popMatrix();
	}

	/**
	 * 现在是不是夜晚。
	 *
	 * <p>依据是天空光衰减而不是世界时间：地图着色用的就是它，图标与画面同进同退。
	 * 下界、末地这类维度 {@code getSkyDarken()} 恒为 0，永远算白天。
	 */
	private boolean isNight() {
		ClientLevel level = Minecraft.getInstance().level;
		return level != null && level.getSkyDarken() >= NIGHT_SKY_DARKEN;
	}

	/** 小地图出现时的放大倍率（由大缩回 1.0），见 {@link #appeared}。 */
	private static final double APPEAR_SCALE = 1.45;

	/** 只有一行有益效果时，小地图要往下让开的高度。 */
	private static final int EFFECT_ROW_SHIFT = 27;

	/** 出现了有害效果（第二行）时，小地图要往下让开的高度。 */
	private static final int EFFECT_TWO_ROW_SHIFT = 53;

	/**
	 * 右上角要让开的高度（GUI 像素）：原版药水效果图标与成就/进度弹窗都画在右上角，HUD 也在右上角，
	 * 不让开就会互相盖住。
	 *
	 * <p>取两者的<b>较大值</b>而不是相加：两块都贴着画面顶边往下排、在画面里本来就可能互相重叠，
	 * 让到较高的那块下沿就够了。
	 */
	private static double topRightAvoidance(LocalPlayer player) {
		return Math.max(potionEffectsShift(player), toastShift());
	}

	/**
	 * 右上角弹窗占了多高：按<b>最低那个被占用的槽位</b>算。
	 *
	 * <p>不能用"占了几条"：原版弹窗消失后剩下的不会上移，上面那条没了、下面那条还在时，
	 * 按个数算会正好压到它。{@code BitSet#length()} 就是最低占用槽位的下沿。
	 */
	private static double toastShift() {
		ToastManager toasts = Minecraft.getInstance().getToastManager();
		int slots = ((ToastManagerAccessor) toasts).sinanmap$occupiedSlots().length();
		return (double) slots * Toast.SLOT_HEIGHT;
	}

	/**
	 * 右上角被原版的药水效果图标占了多高，小地图要往下让开这么多（一行 27、两行 53）。
	 *
	 * <p>成就/进度弹窗的让位见 {@link #toastShift()}，两者在 {@link #topRightAvoidance()} 里按较大值合并。
	 */
	private static int potionEffectsShift(LocalPlayer player) {
		int positive = 0;
		int other = 0;
		for (MobEffectInstance instance : player.getActiveEffects()) {
			if (!instance.showIcon()) {
				continue;
			}
			if (instance.getEffect().value().isBeneficial()) {
				positive++;
			} else {
				other++;
			}
		}
		if (positive == 0 && other == 0) {
			return 0;
		}
		return other > 0 ? EFFECT_TWO_ROW_SHIFT : EFFECT_ROW_SHIFT;
	}

	/** 缩放后画一行小字。 */
	private static void drawSmallText(GuiGraphicsExtractor graphics, Font font, String text, double x, double y) {
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate((float) x, (float) y);
		pose.scale(TEXT_SCALE, TEXT_SCALE);
		graphics.text(font, text, 0, 0, 0xFFFFFFFF);
		pose.popMatrix();
	}

	/**
	 * 画一个方位字：先垫一层只包住字的背景，再按缩放画亮色字。
	 *
	 * <p>背景色由设置给出（见 {@link MinimapSettings#compassBackgroundColor()}）；按“字实际占多大”用屏幕
	 * 像素算好再填（不跟着矩阵缩放），所以底始终贴合字形，不会因为缩放糊出一圈锯齿。
	 */
	private static void drawCompassLabel(GuiGraphicsExtractor graphics, Font font, String label,
			double x, double y, float scale, int color, int backgroundColor) {
		double width = font.width(label) * scale;
		double height = font.lineHeight * scale;
		graphics.fill((int) Math.floor(x) - COMPASS_BACKGROUND_PAD,
				(int) Math.floor(y) - COMPASS_BACKGROUND_PAD,
				(int) Math.ceil(x + width) + COMPASS_BACKGROUND_PAD,
				(int) Math.ceil(y + height) + COMPASS_BACKGROUND_PAD,
				backgroundColor);
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate((float) x, (float) y);
		pose.scale(scale, scale);
		graphics.text(font, label, 0, 0, color, false);
		pose.popMatrix();
	}

	/**
	 * 算出这一帧小地图覆盖的区块范围与页范围。
	 *
	 * <p>把矩形四个角按<b>逆变换</b>换算成世界坐标再取包围盒：小地图可以转，轴对齐矩形转过来后
	 * 包围盒会大一圈（最多多一页），但页本来就常驻，漏掉一个角就是"屏幕角上缺一块"。
	 *
	 * <p>正变换（世界 → 屏幕）是 {@code gui = 中心 + R(rotation) * ((世界 - 玩家) / blocksPerGui)}，
	 * 这里用的是它的逆：{@code 世界 = 玩家 + R(-rotation) * (gui - 中心) * blocksPerGui}。
	 */
	private void updateViewRange(ScreenRect rect, double playerX, double playerZ,
			float rotation, double blocksPerGui) {
		double radians = Math.toRadians(rotation);
		double cos = Math.cos(radians);
		double sin = Math.sin(radians);
		double centerX = rect.x() + rect.width() / 2.0;
		double centerY = rect.y() + rect.height() / 2.0;
		double minX = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE;
		double minZ = Double.MAX_VALUE;
		double maxZ = -Double.MAX_VALUE;
		for (int corner = 0; corner < 4; corner++) {
			double guiX = (corner & 1) == 0 ? rect.x() : rect.right();
			double guiY = (corner & 2) == 0 ? rect.y() : rect.bottom();
			double u = guiX - centerX;
			double v = guiY - centerY;
			double worldX = playerX + (u * cos + v * sin) * blocksPerGui;
			double worldZ = playerZ + (-u * sin + v * cos) * blocksPerGui;
			minX = Math.min(minX, worldX);
			maxX = Math.max(maxX, worldX);
			minZ = Math.min(minZ, worldZ);
			maxZ = Math.max(maxZ, worldZ);
		}
		viewMinChunkX = CoordinateSpaces.blockToChunk((int) Math.floor(minX));
		viewMaxChunkX = CoordinateSpaces.blockToChunk((int) Math.floor(maxX));
		viewMinChunkZ = CoordinateSpaces.blockToChunk((int) Math.floor(minZ));
		viewMaxChunkZ = CoordinateSpaces.blockToChunk((int) Math.floor(maxZ));
		viewMinPageX = Math.floorDiv(viewMinChunkX, WorldTilePages.PAGE_CHUNKS);
		viewMaxPageX = Math.floorDiv(viewMaxChunkX, WorldTilePages.PAGE_CHUNKS);
		viewMinPageZ = Math.floorDiv(viewMinChunkZ, WorldTilePages.PAGE_CHUNKS);
		viewMaxPageZ = Math.floorDiv(viewMaxChunkZ, WorldTilePages.PAGE_CHUNKS);
	}

	/**
	 * 把这一屏看得见的区块备好并写进共享瓦片页，由内向外、每帧只用一点预算。
	 *
	 * <p>与全屏地图不同：全屏地图按页序推进（一页 1024 块），小地图一屏只看得到几十块，所以这里
	 * 直接按"真正看得见的那一片区块"点名要（{@link WorldTilePages#fillChunkNow}），不推进页内游标。
	 *
	 * <p>常驻 HUD，预算必须很小：没备完的下一帧接着来，备好的块立刻跳过。
	 * <b>调用方要先 {@code colorProvider.beginWarm()}</b>（重试轮转与补数据共用一笔预算）。
	 */
	private void warmVisible(int layer, double playerX, double playerZ) {
		int columns = viewMaxChunkX - viewMinChunkX + 1;
		int rows = viewMaxChunkZ - viewMinChunkZ + 1;
		int centerChunkX = CoordinateSpaces.blockToChunk((int) Math.floor(playerX));
		int centerChunkZ = CoordinateSpaces.blockToChunk((int) Math.floor(playerZ));
		// 换了一层、或者可见范围变了就重排：换层之后新层那批块一块都还没画，得从中心重来
		if (fillSweep == null || columns != fillSweepColumns || rows != fillSweepRows
				|| layer != fillSweepLayer) {
			fillSweep = new CenterOutSweep(columns, rows,
					Math.clamp(centerChunkX - viewMinChunkX, 0, Math.max(0, columns - 1)),
					Math.clamp(centerChunkZ - viewMinChunkZ, 0, Math.max(0, rows - 1)));
			fillSweepColumns = columns;
			fillSweepRows = rows;
			fillSweepLayer = layer;
		}

		tiles.setMinimapProtectedRange(layer, viewMinPageX, viewMinPageZ, viewMaxPageX, viewMaxPageZ);
		// 只传当前要画的那张（白天 alpha 为 0 时不必传夜晚那张），见 WorldTilePages#upload
		int nightAlpha = (int) Math.round(colorProvider.skyDarkenFraction() * 255.0);
		boolean wantDay = nightAlpha < 255;
		boolean wantNight = nightAlpha > 0;

		// 这一轮碰到的页：一张页只传一次，别为同一页反复调 upload（每次都是 1 MB）
		int touched = 0;
		TilePage[] pages = touchedPages;
		int uploads = 0;
		while (fillSweep.hasNext() && !colorProvider.isWarmSpent()) {
			if (!fillSweep.inRange()) {
				// 扫描是一个正方形环，转角的格子会落到矩形外面
				fillSweep.advance();
				continue;
			}
			TilePage page = tiles.fillChunkNow(layer,
					viewMinChunkX + fillSweep.x(), viewMinChunkZ + fillSweep.y());
			if (page == null) {
				// 这一轮的建数据预算用尽：剩下的下一帧再来
				break;
			}
			fillSweep.advance();
			boolean seen = false;
			for (int i = 0; i < touched; i++) {
				if (pages[i] == page) {
					seen = true;
					break;
				}
			}
			if (seen) {
				continue;
			}
			if (touched < pages.length) {
				pages[touched++] = page;
			}
			if (tiles.upload(page, wantDay, wantNight) && ++uploads >= MAX_UPLOADS_PER_FRAME) {
				break;
			}
		}
		if (!fillSweep.hasNext()) {
			// 这一屏走完了：下一帧从头再来（已经画过的块会被立刻跳过，几乎不花时间）
			fillSweep = null;
		}
	}

	/** {@link #warmVisible} 的临时表：一屏最多几个页，够用。 */
	private final TilePage[] touchedPages = new TilePage[8];

	/**
	 * 把小地图覆盖到的页贴到屏幕上。
	 *
	 * <p>变换只设一次：平移到矩形中心 → 按视角旋转 → 缩放到"方块坐标系"（{@code 1 / blocksPerGui}）
	 * → 平移到玩家所在的世界坐标。每页只要用<b>世界方块坐标</b>画一个整页矩形即可，页本身不用重算。
	 *
	 * <p>未填充的像素透明，裁剪到小地图范围用裁剪框（转过的矩形没法靠"只画可见页"裁）。
	 *
	 * @param sharp 是否用最近邻采样：放大到"一个方块超过一个设备像素"时必须关掉线性过滤，
	 *              否则每个方块都被插值糊成一片
	 */
	private void drawTerrain(GuiGraphicsExtractor graphics, ScreenRect rect, int layer,
			double playerX, double playerZ, float rotation, double blocksPerGui, boolean sharp) {
		// 底色：未探明的地方透出这一层暗色，见 UNEXPLORED_BACKGROUND
		graphics.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), UNEXPLORED_BACKGROUND);

		int size = WorldTilePages.PAGE_BLOCKS;
		int nightAlpha = (int) Math.round(colorProvider.skyDarkenFraction() * 255.0);
		double scale = layerTransition.current() / Math.max(1.0e-6, blocksPerGui);

		Matrix3x2fStack pose = graphics.pose();
		graphics.enableScissor(rect.x(), rect.y(), rect.right(), rect.bottom());
		pose.pushMatrix();
		pose.translate((float) (rect.x() + rect.width() / 2.0), (float) (rect.y() + rect.height() / 2.0));
		pose.rotate((float) Math.toRadians(rotation));
		pose.scale((float) scale, (float) scale);
		pose.translate((float) -playerX, (float) -playerZ);
		for (int pageZ = viewMinPageZ; pageZ <= viewMaxPageZ; pageZ++) {
			for (int pageX = viewMinPageX; pageX <= viewMaxPageX; pageX++) {
				TilePage page = tiles.find(layer, pageX, pageZ);
				if (page == null || !page.loaded()) {
					continue;
				}
				// 白天那张打底，夜晚那张按天色压 alpha 叠一层：结果是 白天×(1-t) + 夜晚×t。
				// 两张贴图都不随时间变，所以换天色一个像素都不用重算
				TileVariants textures = page.textures();
				int x0 = pageX * size;
				int z0 = pageZ * size;
				if (nightAlpha < 255) {
					WorldTilePages.drawPage(graphics, sharp ? textures.daySharpId() : textures.dayId(),
							x0, z0, size, 0xFFFFFFFF);
				}
				if (nightAlpha > 0) {
					WorldTilePages.drawPage(graphics, sharp ? textures.nightSharpId() : textures.nightId(),
							x0, z0, size, nightAlpha << 24 | 0xFFFFFF);
				}
			}
		}
		// 叠加层挂在同一套变换里：与地形一起转、一起缩放，也就不会出现"网格在原地抖"
		drawOverlays(graphics, rect, scale, playerX, playerZ);
		pose.popMatrix();
		graphics.disableScissor();
	}

	/**
	 * 方位字贴边时的<b>基准</b>边距（GUI 像素），实际边距再乘当前字号倍率（见 {@link #drawCompass}）。
	 *
	 * <p>不直接用固定值：字号调小之后固定边距会让字显得离边缘很远。
	 * 乘上字号倍率后，字大字小都是"贴着边缘"的观感。
	 */
	private static final double COMPASS_EDGE_MARGIN = 4.0;

	/** 四个方位的世界方向 {@code (dx, dz)}——北 = -Z、东 = +X、南 = +Z、西 = -X。 */
	private static final double[][] COMPASS_DIRECTIONS = {
			{0.0, -1.0}, {1.0, 0.0}, {0.0, 1.0}, {-1.0, 0.0},
	};

	/** 与 {@link #COMPASS_DIRECTIONS} 一一对应的字母（非中文语言用）。 */
	private static final String[] COMPASS_LABELS = {"N", "E", "S", "W"};

	/** 中文语言用的方位字，与 {@link #COMPASS_DIRECTIONS} 一一对应（北 / 东 / 南 / 西）。 */
	private static final String[] COMPASS_LABELS_ZH = {"北", "东", "南", "西"};

	/**
	 * 方位字的基准缩放：实际字号再乘设置里的 {@link MinimapSettings#compassScale()}。
	 *
	 * <p>取 0.5 而不是信息行那种 0.7：方位字贴着边缘、还要垫背景，太大反而会糊住边缘地形。
	 */
	private static final float COMPASS_BASE_SCALE = 0.5F;

	/** 方位字背景相对字外扩的像素：只包住字本身，不铺成一大块。 */
	private static final int COMPASS_BACKGROUND_PAD = 1;

	/**
	 * 四个方位该写的字：当前语言以 {@code zh} 开头（{@code zh_cn} / {@code zh_tw} 等）就用"东南西北"，
	 * 其它语言用 N / E / S / W。
	 *
	 * <p>按语言代码判断而不是走翻译键：汉字的宽高与单个字母不同，落点与背景都要按实际文字尺寸算。
	 */
	private static String[] compassLabels() {
		String language = Minecraft.getInstance().getLanguageManager().getSelected();
		return language != null && language.startsWith("zh") ? COMPASS_LABELS_ZH : COMPASS_LABELS;
	}

	/**
	 * 在小地图边缘内侧画四个方位字（中文"东南西北"，其它语言 N / E / S / W）。
	 *
	 * <p>位置用的是与地形、路径点<b>同一个变换</b>：世界方向向量按地图旋转角投到屏幕
	 * （{@code screen = (dx·cos - dz·sin, dx·sin + dz·cos)}，即 {@link Heading#screenDirection} 的那一套）。
	 * 于是锁定正北（旋转角 0）时"北"恒在上方；跟随视角时地图转、方位字也跟着转到对应方向。
	 *
	 * <p>字本身<b>始终正着写</b>（只有落点参与旋转，字形不转），否则转过之后会倒过来认不出。
	 * 落点贴到"按实际文字尺寸内缩后的边界"上：取方向射线与内缩矩形的交点，
	 * 背景只包住字本身，所以字再宽也不会越出小地图。
	 */
	private void drawCompass(GuiGraphicsExtractor graphics, ScreenRect rect, float rotation) {
		if (!settings.compass()) {
			return;
		}
		Font font = Minecraft.getInstance().font;
		String[] labels = compassLabels();
		double radians = Math.toRadians(rotation);
		double cos = Math.cos(radians);
		double sin = Math.sin(radians);
		double centerX = rect.x() + rect.width() / 2.0;
		double centerY = rect.y() + rect.height() / 2.0;
		// 字号 = 基准 × 设置倍率；字色与背景色都取自设置
		float scale = COMPASS_BASE_SCALE * (float) settings.compassScale();
		int color = settings.compassColor();
		int background = settings.compassBackgroundColor();
		// 画在最上层，但仍裁在小地图边界内：字号调到最大时也不会有半个字露到小地图外面
		graphics.enableScissor(rect.x(), rect.y(), rect.right(), rect.bottom());
		for (int i = 0; i < labels.length; i++) {
			String label = labels[i];
			double width = font.width(label) * scale;
			double height = font.lineHeight * scale;
			// 落点要按"这个字的实际尺寸"内缩：字以中心对准落点、背景再外扩 1px，所以边界至少要
			// 让出 半个字宽 / 半个行高 + 1px；边距随字号缩放（见 COMPASS_EDGE_MARGIN），确保贴边
			double margin = COMPASS_EDGE_MARGIN * scale;
			double halfExtentX = Math.max(margin, width / 2.0 + COMPASS_BACKGROUND_PAD);
			double halfExtentY = Math.max(margin, height / 2.0 + COMPASS_BACKGROUND_PAD);
			double dx = COMPASS_DIRECTIONS[i][0];
			double dz = COMPASS_DIRECTIONS[i][1];
			double dirX = dx * cos - dz * sin;
			double dirY = dx * sin + dz * cos;
			// 射线与内缩矩形的交点：两个轴各自算"到达边界所需的倍率"，取小的那个；
			// 某轴分量为 0 时它不构成限制（用极小值兜底，避免除零）
			double halfWidth = Math.max(0.0, rect.width() / 2.0 - halfExtentX);
			double halfHeight = Math.max(0.0, rect.height() / 2.0 - halfExtentY);
			double t = Math.min(halfWidth / Math.max(1.0e-6, Math.abs(dirX)),
					halfHeight / Math.max(1.0e-6, Math.abs(dirY)));
			double px = centerX + dirX * t;
			double py = centerY + dirY * t;
			// 以字中心对准落点：文本从左上角起画，所以往回挪半个字宽 / 半个行高
			drawCompassLabel(graphics, font, label, px - width / 2.0, py - height / 2.0,
					scale, color, background);
		}
		graphics.disableScissor();
	}

	/**
	 * 叠加层：区块网格与史莱姆区块。
	 *
	 * <p>挂在 {@link #drawTerrain} 的那套变换里（调用点就在矩阵内部），所以跟着小地图一起转、一起缩放；
	 * 判定与阈值来自 {@link ChunkOverlays}，与世界地图共用。
	 *
	 * <p>可见范围按矩形<b>半对角线</b>估：转着的矩形用外接圆最省事，多扫几个区块只是多几次查表。
	 */
	private void drawOverlays(GuiGraphicsExtractor graphics, ScreenRect rect, double guiPerBlock,
			double playerX, double playerZ) {
		if (!settings.chunkGrid() && !settings.slimeChunks()) {
			return;
		}
		// 防御：下面要拿它当除数算可见范围，真为 0 的话会算出无穷大的范围
		if (guiPerBlock <= 1.0e-6) {
			return;
		}
		double chunkPixels = ChunkOverlays.CHUNK_BLOCKS * guiPerBlock;
		boolean drawGrid = settings.chunkGrid()
				&& chunkPixels >= ChunkOverlays.MIN_GRID_CHUNK_PIXELS
				&& chunkPixels <= ChunkOverlays.MAX_GRID_CHUNK_PIXELS;
		boolean drawSlime = settings.slimeChunks()
				&& chunkPixels >= ChunkOverlays.MIN_SLIME_CHUNK_PIXELS
				&& ChunkOverlays.canMarkSlimeChunks();
		if (!drawGrid && !drawSlime) {
			return;
		}

		double half = Math.hypot(rect.width(), rect.height()) / 2.0 / guiPerBlock;
		int blockMinX = (int) Math.floor(playerX - half);
		int blockMaxX = (int) Math.ceil(playerX + half);
		int blockMinZ = (int) Math.floor(playerZ - half);
		int blockMaxZ = (int) Math.ceil(playerZ + half);

		int size = ChunkOverlays.CHUNK_BLOCKS;
		int lineBlocks = ChunkOverlays.lineBlocks(1.0 / guiPerBlock);
		// 画网格时色块向里缩一条线宽，免得两处半透明叠出一条深带（见下面对网格线的分段说明）
		int inset = drawGrid ? lineBlocks : 0;
		// 史莱姆区块先铺底、网格线后画
		if (drawSlime) {
			int lastChunkX = ChunkOverlays.chunkAt(blockMaxX);
			for (int chunkZ = ChunkOverlays.chunkAt(blockMinZ);
					chunkZ <= ChunkOverlays.chunkAt(blockMaxZ); chunkZ++) {
				int z = ChunkOverlays.chunkOrigin(chunkZ);
				for (int chunkX = ChunkOverlays.chunkAt(blockMinX); chunkX <= lastChunkX; chunkX++) {
					if (!ChunkOverlays.isSlimeChunk(chunkX, chunkZ)) {
						continue;
					}
					int x = ChunkOverlays.chunkOrigin(chunkX);
					graphics.fill(x + inset, z + inset, x + size, z + size,
							ChunkOverlays.SLIME_COLOR);
				}
			}
		}
		if (drawGrid) {
			int color = settings.chunkGridColor();
			int firstChunkZ = ChunkOverlays.chunkAt(blockMinZ);
			int lastChunkZ = ChunkOverlays.chunkAt(blockMaxZ) + 1;
			// 横线整条铺满
			for (int chunkZ = firstChunkZ; chunkZ <= lastChunkZ; chunkZ++) {
				int z = ChunkOverlays.chunkOrigin(chunkZ);
				graphics.fill(blockMinX, z, blockMaxX, z + lineBlocks, color);
			}
			// 竖线只在相邻两条横线之间分段画：交点叠两次会更亮，整张网格上会浮出一片小方块
			for (int chunkX = ChunkOverlays.chunkAt(blockMinX);
					chunkX <= ChunkOverlays.chunkAt(blockMaxX) + 1; chunkX++) {
				int x = ChunkOverlays.chunkOrigin(chunkX);
				int cursor = blockMinZ;
				for (int chunkZ = firstChunkZ; chunkZ <= lastChunkZ; chunkZ++) {
					int z = ChunkOverlays.chunkOrigin(chunkZ);
					if (z > cursor) {
						graphics.fill(x, cursor, x + lineBlocks, z, color);
					}
					cursor = z + lineBlocks;
				}
				if (cursor < blockMaxZ) {
					graphics.fill(x, cursor, x + lineBlocks, blockMaxZ, color);
				}
			}
		}
	}

	/**
	 * 画路径点。
	 *
	 * <p>位置与地形用<b>同一套换算</b>：先算它与玩家的世界位移、按旋转角转、再除以每设备像素的方块数，
	 * 于是与地形严丝合缝地一起转、一起缩放。
	 *
	 * <p>样式与世界地图<b>共用同一套绘制</b>（{@link WaypointRenderer}），但小地图上的标记
	 * <b>没有尖端朝下的倒三角</b>：只画一个路径点原色底板的小标签，未展开时是名字首字（死亡点是骷髅图标）。
	 *
	 * <p><b>永远按"收起"画、也不推进展开动画</b>：小地图是常驻 HUD，没有"光标停在哪条上"这一说；
	 * 它每帧都会被抽取（开着世界地图时也一样），若在这里推进同一份按 id 记的进度，会把世界地图的展开动画推回去。
	 *
	 * <p><b>跑到小地图外的路径点也画</b>：位置夹在小地图边上，"它在左后方多远"的信息还在。
	 */
	private void drawWaypoints(GuiGraphicsExtractor graphics, ScreenRect rect,
			double playerX, double playerZ, float rotation, int guiScale, double blocksPerDevice) {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		if (level == null || !settings.showMapWaypoints()) {
			// 设置里关掉了"地图上路径点"：小地图这边也一起不画
			return;
		}
		String dimension = level.dimension().identifier().toString();
		double radians = Math.toRadians(rotation);
		double cos = Math.cos(radians);
		double sin = Math.sin(radians);
		double scale = layerTransition.current() / (guiScale * blocksPerDevice);
		double centerX = rect.x() + rect.width() / 2.0;
		double centerY = rect.y() + rect.height() / 2.0;
		// 贴边夹住时留出的边距：小标签的一半（还有一点描边），保证夹住之后仍然完整看得见。
		// 两头都与中心取 min/max：小地图被调得比两倍边距还小时，钳位的上下界也不会反过来
		double minX = Math.min(rect.x() + WAYPOINT_EDGE_MARGIN, centerX);
		double maxX = Math.max(rect.right() - WAYPOINT_EDGE_MARGIN, centerX);
		double minY = Math.min(rect.y() + WAYPOINT_EDGE_MARGIN, centerY);
		double maxY = Math.max(rect.bottom() - WAYPOINT_EDGE_MARGIN, centerY);
		graphics.enableScissor(rect.x(), rect.y(), rect.right(), rect.bottom());
		// 每帧都会走这一遍，所以用不复制列表的 view()：见 WaypointStore#view
		for (Waypoint waypoint : WaypointManager.instance().store(minecraft).view()) {
			if (!waypoint.visible() || !waypoint.pos().dimension().equals(dimension)) {
				continue;
			}
			double dx = waypoint.pos().x() - playerX;
			double dz = waypoint.pos().z() - playerZ;
			double x = centerX + (dx * cos - dz * sin) * scale;
			double y = centerY + (dx * sin + dz * cos) * scale;
			// 从小地图中心朝它看的那条方向不变：夹住之后方向仍然是准的
			if (x < rect.x() || x > rect.right() || y < rect.y() || y > rect.bottom()) {
				x = Math.clamp(x, minX, maxX);
				y = Math.clamp(y, minY, maxY);
			}
			WaypointRenderer.drawOnMinimap(graphics, minecraft.font, x, y, waypoint, settings);
		}
		graphics.disableScissor();
	}

	/**
	 * 贴边夹住时，标记离小地图边缘留多少（GUI 像素）。
	 *
	 * <p>取"首字方块的一半 + 描边"：夹住的标记以坐标点为中心画，留这么多才不会被裁剪框切掉一半。
	 * 取 {@value} 而不是更大——贴边本来就该贴着走，留太多会明显离边框一条缝。
	 */
	private static final double WAYPOINT_EDGE_MARGIN = 3.0;

	/**
	 * 画服务端同步过来的其他玩家。
	 *
	 * <p>位置换算与路径点<b>一模一样</b>（先算与玩家的世界位移，再按视角转，再除每设备像素的方块数），
	 * 所以他们会和地形一起转、一起缩放。和小地图上的路径点不同的是：<b>不贴边夹住</b>——
	 * 贴边是为了"远处的东西也知道在哪"，而玩家是活动的，夹在边上一圈头像反而更乱。
	 */
	private void drawTrackedPlayers(GuiGraphicsExtractor graphics, ScreenRect rect,
			double playerX, double playerZ, float rotation, int guiScale, double blocksPerDevice) {
		if (TrackedPlayers.isEmpty()) {
			return;
		}
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			return;
		}
		String dimension = level.dimension().identifier().toString();
		double radians = Math.toRadians(rotation);
		double cos = Math.cos(radians);
		double sin = Math.sin(radians);
		double scale = layerTransition.current() / (guiScale * blocksPerDevice);
		double centerX = rect.x() + rect.width() / 2.0;
		double centerY = rect.y() + rect.height() / 2.0;
		float opacity = (float) settings.mapWaypointOpacity();
		graphics.enableScissor(rect.x(), rect.y(), rect.right(), rect.bottom());
		for (TrackedPlayersS2C.Player entry : TrackedPlayers.view()) {
			if (!entry.dimension().equals(dimension)) {
				continue;
			}
			double dx = entry.x() - playerX;
			double dz = entry.z() - playerZ;
			double x = centerX + (dx * cos - dz * sin) * scale;
			double y = centerY + (dx * sin + dz * cos) * scale;
			if (x < rect.x() || x > rect.right() || y < rect.y() || y > rect.bottom()) {
				continue;
			}
			TrackedPlayers.draw(graphics, Minecraft.getInstance().font, x, y, entry, opacity);
		}
		graphics.disableScissor();
	}

}
