package sinanmap.client.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import net.fabricmc.loader.api.FabricLoader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sinanmap.common.SinanConstants;
import sinanmap.common.hud.MinimapSettings;
import sinanmap.common.hud.ScreenAnchor;
import sinanmap.common.map.LayerMode;

/**
 * 设置落盘：{@code <游戏目录>/config/sinanmap/sinanmap.json}。
 *
 * <p><b>为什么放 config 而不是存档里</b>：这些是"我怎么看地图"的偏好，跟具体哪个世界无关——
 * 换了存档还希望小地图还是原来那么大、信息行还是那几行。原版和绝大多数模组也都是这个位置。
 *
 * <p><b>为什么自己拼 JSON 而不是直接序列化对象</b>：Gson 直接反射 {@code MinimapSettings} 的话，
 * 字段一改名旧文件就整份读不出来，而这里的字段名是<b>稳定的对外名字</b>（改代码里的字段名不该动文件格式）。
 * 另一个好处是读写都能容错：缺字段用默认值、多字段直接忽略，旧版本的文件永远读得进来。
 *
 * <p><b>写盘时机</b>：每隔一秒比一次"内容变了没"，变了才写。跟"每个 setter 都标脏"相比，
 * 这里不需要在设置类的几十个 setter 里都插一行；序列化本身只有几十个字段，一秒一次的开销可以忽略。
 */
public final class MinimapSettingsIO {

	private static final Logger LOGGER = LoggerFactory.getLogger(SinanConstants.MOD_ID);

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** 写盘检查的间隔（tick）：一秒一次。 */
	private static final int WRITE_INTERVAL_TICKS = 20;

	/** 上一次真正写下去的内容：用它判断"变了没"，避免每秒都重写同一个文件。 */
	private static String lastWritten;

	private static int tickCounter;

	private MinimapSettingsIO() {
	}

	/** 设置文件的位置。 */
	public static Path file() {
		return FabricLoader.getInstance().getConfigDir()
				.resolve(SinanConstants.MOD_ID)
				.resolve(SinanConstants.MOD_ID + ".json");
	}

	/** 读进设置；文件不存在或读坏了就保持默认值（只是"回到默认"，不该挡着进游戏）。 */
	public static void load(MinimapSettings settings) {
		Path path = file();
		if (!Files.exists(path)) {
			return;
		}
		try {
			String text = Files.readString(path, StandardCharsets.UTF_8);
			apply(settings, JsonParser.parseString(text).getAsJsonObject());
			lastWritten = text;
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("设置文件读不出来，本局用默认值 | {}", path, e);
		}
	}

	/** 每帧一次的空转检查：到点了才比一次内容。 */
	public static void tick(MinimapSettings settings) {
		if (++tickCounter < WRITE_INTERVAL_TICKS) {
			return;
		}
		tickCounter = 0;
		flush(settings);
	}

	/** 立刻写一次（内容没变就什么都不做）。 */
	public static void flush(MinimapSettings settings) {
		String text = GSON.toJson(toJson(settings));
		if (text.equals(lastWritten)) {
			return;
		}
		Path path = file();
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, text, StandardCharsets.UTF_8);
			lastWritten = text;
		} catch (IOException e) {
			LOGGER.warn("设置写不出去 | {}", path, e);
		}
	}

	private static JsonObject toJson(MinimapSettings settings) {
		JsonObject json = new JsonObject();
		json.addProperty("enabled", settings.enabled());
		json.addProperty("width", settings.width());
		json.addProperty("height", settings.height());
		json.addProperty("blocksPerPixel", settings.blocksPerPixel());
		json.addProperty("autoZoom", settings.autoZoom());
		json.addProperty("rotateWithPlayer", settings.rotateWithPlayer());
		json.addProperty("layerMode", settings.layerMode().name());
		json.addProperty("anchor", settings.anchor().name());
		json.addProperty("offsetX", settings.offsetX());
		json.addProperty("offsetY", settings.offsetY());

		json.addProperty("entityRadar", settings.entityRadarEnabled());
		json.addProperty("entityRadarRadius", settings.entityRadarRadius());
		json.addProperty("entityRadarHostileOnly", settings.entityRadarHostileOnly());
		json.addProperty("entityRadarHeightIndicator", settings.entityRadarHeightIndicator());
		json.addProperty("radarIconMode", settings.radarIconMode().name());
		json.addProperty("sharePlayerPosition", settings.sharePlayerPosition());

		json.addProperty("waypointHudDistance", settings.waypointHudDistance());
		json.addProperty("waypointHudScale", settings.waypointHudScale());
		json.addProperty("hudScaleAuto", settings.hudScaleAuto());
		json.addProperty("showMapWaypoints", settings.showMapWaypoints());
		json.addProperty("showHudWaypoints", settings.showHudWaypoints());
		json.addProperty("showDeathPoints", settings.showDeathPoints());
		json.addProperty("keepOldDeathPoints", settings.keepOldDeathPoints());
		json.addProperty("waystoneIntegration", settings.waystoneIntegration());
		json.addProperty("hudWaypointOpacity", settings.hudWaypointOpacity());
		json.addProperty("mapWaypointOpacity", settings.mapWaypointOpacity());
		json.addProperty("kilometerThreshold", settings.kilometerThreshold());
		json.addProperty("nearHideDistance", settings.nearHideDistance());
		json.addProperty("nearShowFullName", settings.nearShowFullName());

		json.addProperty("chunkGrid", settings.chunkGrid());
		json.addProperty("chunkGridColor", settings.chunkGridColor());
		json.addProperty("slimeChunks", settings.slimeChunks());
		json.addProperty("compass", settings.compass());
		json.addProperty("compassColor", settings.compassColor());
		json.addProperty("compassBackgroundColor", settings.compassBackgroundColor());
		json.addProperty("compassScale", settings.compassScale());

		json.addProperty("infoAlign", settings.infoAlign().name());
		json.addProperty("clock24", settings.clock24());
		JsonArray infoLines = new JsonArray();
		for (MinimapSettings.InfoLine line : settings.infoLines()) {
			infoLines.add(line.name());
		}
		json.add("infoLines", infoLines);

		json.addProperty("prefetchThreads", settings.prefetchThreads());
		json.addProperty("caveScanThreads", settings.caveScanThreads());
		json.addProperty("appliedPerFrame", settings.appliedPerFrame());
		json.addProperty("applyBudgetMillis", settings.applyBudgetMillis());
		json.addProperty("chunksPerVisit", settings.chunksPerVisit());
		json.addProperty("fillingPages", settings.fillingPages());
		json.addProperty("fillingPagesPerFrame", settings.fillingPagesPerFrame());
		json.addProperty("mapDetailTierV2", settings.mapDetailTier());
		return json;
	}

	/** 把 JSON 摊到设置上。<b>缺什么就用默认值</b>，所以旧文件、手改过的文件都能读。 */
	private static void apply(MinimapSettings settings, JsonObject json) {
		settings.setEnabled(bool(json, "enabled", settings.enabled()));
		settings.setWidth(integer(json, "width", settings.width()));
		settings.setHeight(integer(json, "height", settings.height()));
		settings.setBlocksPerPixel(decimal(json, "blocksPerPixel", settings.blocksPerPixel()));
		settings.setAutoZoom(bool(json, "autoZoom", settings.autoZoom()));
		settings.setRotateWithPlayer(bool(json, "rotateWithPlayer", settings.rotateWithPlayer()));
		settings.setLayerMode(enumOf(LayerMode.class, string(json, "layerMode"), settings.layerMode()));
		settings.setAnchor(enumOf(ScreenAnchor.class, string(json, "anchor"), settings.anchor()));
		settings.setOffsetX(integer(json, "offsetX", settings.offsetX()));
		settings.setOffsetY(integer(json, "offsetY", settings.offsetY()));

		settings.setEntityRadarEnabled(bool(json, "entityRadar", settings.entityRadarEnabled()));
		settings.setEntityRadarRadius(decimal(json, "entityRadarRadius", settings.entityRadarRadius()));
		settings.setEntityRadarHostileOnly(
				bool(json, "entityRadarHostileOnly", settings.entityRadarHostileOnly()));
		settings.setEntityRadarHeightIndicator(
				bool(json, "entityRadarHeightIndicator", settings.entityRadarHeightIndicator()));
		settings.setRadarIconMode(
				enumOf(MinimapSettings.RadarIconMode.class, string(json, "radarIconMode"),
						settings.radarIconMode()));
		settings.setSharePlayerPosition(
				bool(json, "sharePlayerPosition", settings.sharePlayerPosition()));

		settings.setWaypointHudDistance(bool(json, "waypointHudDistance", settings.waypointHudDistance()));
		settings.setWaypointHudScale(decimal(json, "waypointHudScale", settings.waypointHudScale()));
		settings.setHudScaleAuto(bool(json, "hudScaleAuto", settings.hudScaleAuto()));
		settings.setShowMapWaypoints(bool(json, "showMapWaypoints", settings.showMapWaypoints()));
		settings.setShowHudWaypoints(bool(json, "showHudWaypoints", settings.showHudWaypoints()));
		settings.setShowDeathPoints(bool(json, "showDeathPoints", settings.showDeathPoints()));
		settings.setKeepOldDeathPoints(bool(json, "keepOldDeathPoints", settings.keepOldDeathPoints()));
		settings.setWaystoneIntegration(bool(json, "waystoneIntegration", settings.waystoneIntegration()));
		settings.setHudWaypointOpacity(decimal(json, "hudWaypointOpacity", settings.hudWaypointOpacity()));
		settings.setMapWaypointOpacity(decimal(json, "mapWaypointOpacity", settings.mapWaypointOpacity()));
		settings.setKilometerThreshold(integer(json, "kilometerThreshold", settings.kilometerThreshold()));
		settings.setNearHideDistance(integer(json, "nearHideDistance", settings.nearHideDistance()));
		settings.setNearShowFullName(integer(json, "nearShowFullName", settings.nearShowFullName()));

		settings.setChunkGrid(bool(json, "chunkGrid", settings.chunkGrid()));
		settings.setChunkGridColor(integer(json, "chunkGridColor", settings.chunkGridColor()));
		settings.setSlimeChunks(bool(json, "slimeChunks", settings.slimeChunks()));
		settings.setCompass(bool(json, "compass", settings.compass()));
		settings.setCompassColor(integer(json, "compassColor", settings.compassColor()));
		settings.setCompassBackgroundColor(
				integer(json, "compassBackgroundColor", settings.compassBackgroundColor()));
		settings.setCompassScale(decimal(json, "compassScale", settings.compassScale()));

		settings.setInfoAlign(enumOf(MinimapSettings.TextAlign.class,
				string(json, "infoAlign"), settings.infoAlign()));
		settings.setClock24(bool(json, "clock24", settings.clock24()));
		applyInfoLines(settings, json);

		settings.setPrefetchThreads(integer(json, "prefetchThreads", settings.prefetchThreads()));
		settings.setCaveScanThreads(integer(json, "caveScanThreads", settings.caveScanThreads()));
		settings.setAppliedPerFrame(integer(json, "appliedPerFrame", settings.appliedPerFrame()));
		settings.setApplyBudgetMillis(integer(json, "applyBudgetMillis", settings.applyBudgetMillis()));
		settings.setChunksPerVisit(integer(json, "chunksPerVisit", settings.chunksPerVisit()));
		settings.setFillingPages(integer(json, "fillingPages", settings.fillingPages()));
		settings.setFillingPagesPerFrame(
				integer(json, "fillingPagesPerFrame", settings.fillingPagesPerFrame()));
		// 细节档的语义变过：原来三档是 1/2/4、而 4 同时表示"不限制"；现在三档是 1/4/8、不限制是 8。
		// 换个新键来区分：新键原样读；只有旧键时才把那个 4（当时就是"不限制"）换算成 8。
		int detailTier = integer(json, "mapDetailTierV2", Integer.MIN_VALUE);
		if (detailTier == Integer.MIN_VALUE) {
			int legacy = integer(json, "mapDetailTier", MinimapSettings.MAX_DETAIL_TIER);
			detailTier = legacy == 4 ? MinimapSettings.MAX_DETAIL_TIER : legacy;
		}
		settings.setMapDetailTier(detailTier);
	}

	/**
	 * 信息行：先全关掉、再按文件里的顺序一条条开。
	 *
	 * <p>不能直接替换列表——{@code MinimapSettings} 只提供"开关某一行"与"上移 / 下移"，
	 * 这样写既用上了它已有的校验，顺序也天然对（{@code setInfoLineEnabled(true)} 是追加到末尾）。
	 */
	private static void applyInfoLines(MinimapSettings settings, JsonObject json) {
		if (!json.has("infoLines") || !json.get("infoLines").isJsonArray()) {
			return;
		}
		for (MinimapSettings.InfoLine line : MinimapSettings.InfoLine.values()) {
			settings.setInfoLineEnabled(line, false);
		}
		for (JsonElement element : json.getAsJsonArray("infoLines")) {
			MinimapSettings.InfoLine line =
					enumOf(MinimapSettings.InfoLine.class, element.getAsString(), null);
			if (line != null) {
				settings.setInfoLineEnabled(line, true);
			}
		}
	}

	private static boolean bool(JsonObject json, String key, boolean fallback) {
		return json.has(key) ? json.get(key).getAsBoolean() : fallback;
	}

	private static int integer(JsonObject json, String key, int fallback) {
		return json.has(key) ? json.get(key).getAsInt() : fallback;
	}

	private static double decimal(JsonObject json, String key, double fallback) {
		return json.has(key) ? json.get(key).getAsDouble() : fallback;
	}

	private static String string(JsonObject json, String key) {
		return json.has(key) ? json.get(key).getAsString() : null;
	}

	/** 按名字取枚举；名字不认识（文件是别的版本写的）就退回 {@code fallback}。 */
	private static <E extends Enum<E>> E enumOf(Class<E> type, String name, E fallback) {
		if (name == null) {
			return fallback;
		}
		try {
			return Enum.valueOf(type, name);
		} catch (IllegalArgumentException e) {
			return fallback;
		}
	}
}
