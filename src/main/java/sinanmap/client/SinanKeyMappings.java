package sinanmap.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sinanmap.common.SinanConstants;
import sinanmap.common.coord.WorldPos;
import sinanmap.common.hud.MinimapSettings;
import sinanmap.common.waypoint.Waypoint;
import sinanmap.client.map.data.ClientChunkColorProvider;
import sinanmap.client.map.MinimapHud;
import sinanmap.client.map.WorldMapScreen;
import sinanmap.client.map.settings.MapSettingsScreen;
import sinanmap.client.waypoint.WaypointEditScreen;
import sinanmap.client.waypoint.WaypointListScreen;
import sinanmap.client.waypoint.WaypointManager;

/**
 * 按键绑定：打开世界地图（默认 M）、小地图放大 / 缩小（默认 = 与 -）、
 * 新建路径点（默认 B）、路径点管理器（默认 U）、小地图设置（默认 O）。
 *
 * <p>按键事件按帧轮询：只有当前没有打开任何界面时才处理，避免在聊天框或背包里误触。
 */
public final class SinanKeyMappings {

	private static final Logger LOGGER = LoggerFactory.getLogger(SinanConstants.MOD_ID);

	/**
	 * 本模组在"按键绑定"里单独占一个分类，而不是混进原版的<b>杂项</b>（杂项里条目一大堆，要翻很久才能找到）；
	 * 分类名走 {@code key.category.<命名空间>.<路径>}（见 {@link KeyMapping.Category#label()}）。
	 */
	private static final KeyMapping.Category CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath(SinanConstants.MOD_ID, "controls"));

	/**
	 * 按住缩放键时，两次步进之间隔多少个客户端 tick。
	 *
	 * <p>20 tick/秒，取 {@value} → 每 0.25 秒一步：按住是"匀速拉远/拉近"，不是系统按键重复那种
	 * "先静半秒、随后狂飙"（见 {@code register} 里缩放那一段的说明）。
	 */
	private static final int ZOOM_REPEAT_TICKS = 5;

	/** 按住缩放键时已经过去几个 tick（松手清零），见 {@link #ZOOM_REPEAT_TICKS}。 */
	private static int zoomRepeatTicks;

	private static final KeyMapping OPEN_WORLD_MAP = new KeyMapping(
			"key." + SinanConstants.MOD_ID + ".world_map",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_M,
			CATEGORY);

	private static final KeyMapping MINIMAP_ZOOM_IN = new KeyMapping(
			"key." + SinanConstants.MOD_ID + ".minimap_zoom_in",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_EQUAL,
			CATEGORY);

	private static final KeyMapping MINIMAP_ZOOM_OUT = new KeyMapping(
			"key." + SinanConstants.MOD_ID + ".minimap_zoom_out",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_MINUS,
			CATEGORY);

	private static final KeyMapping ADD_WAYPOINT = new KeyMapping(
			"key." + SinanConstants.MOD_ID + ".add_waypoint",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_B,
			CATEGORY);

	private static final KeyMapping WAYPOINT_MANAGER = new KeyMapping(
			"key." + SinanConstants.MOD_ID + ".waypoint_manager",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_U,
			CATEGORY);

	/**
	 * 打开地图设置：默认 <b>]</b>（键盘上 } 那个键；原来"小地图设置"用的就是它，那个界面删掉之后这个键就空出来了）。
	 *
	 * <p>刻意<b>不留空</b>：{@code GLFW_KEY_UNKNOWN} 在原版按键界面里会显示成一个空的按键框，而且按键列表
	 * 里那一行也没法一眼看出能不能用。给个默认键，要改的人自己改。
	 *
	 * <p>从这里打开的设置界面没有上一层，所以背景是游戏画面（不是世界地图）。
	 */
	private static final KeyMapping OPEN_SETTINGS = new KeyMapping(
			"key." + SinanConstants.MOD_ID + ".open_settings",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_RIGHT_BRACKET,
			CATEGORY);

	private SinanKeyMappings() {
	}

	public static void register(ClientChunkColorProvider colorProvider, MinimapHud minimap) {
		KeyMappingHelper.registerKeyMapping(OPEN_WORLD_MAP);
		KeyMappingHelper.registerKeyMapping(MINIMAP_ZOOM_IN);
		KeyMappingHelper.registerKeyMapping(MINIMAP_ZOOM_OUT);
		KeyMappingHelper.registerKeyMapping(ADD_WAYPOINT);
		KeyMappingHelper.registerKeyMapping(WAYPOINT_MANAGER);
		KeyMappingHelper.registerKeyMapping(OPEN_SETTINGS);
		// 注意：此处不要调用 getTranslatedKeyMessage() 之类的按键名解析——那会在 glfwInit() 之前调用 GLFW，
		// 留下的错误状态被 MC 的 checkGlfwError() 读到，导致启动崩溃（GLFW error before init）。
		LOGGER.info("按键已注册 | 世界地图 M、小地图放大 = 、小地图缩小 - 、新建路径点 B 、路径点管理器 U");
		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
			while (OPEN_WORLD_MAP.consumeClick()) {
				if (minecraft.screen == null) {
					// 全屏地图与 HUD 共用同一份小地图设置，并顺带把 minimap 交给它（"编辑 HUD 位置"要用它画实时小地图）
					minecraft.setScreen(new WorldMapScreen(colorProvider, minimap));
				}
			}
			// 缩放：按住时按<b>固定节拍</b>步进，而不是用 consumeClick()。
			//
			// 为什么：consumeClick() 把系统的"按键重复"也算作一次点击——按住后先静 ~0.5 秒，
			// 之后每秒三十来次。而缩放值是<b>指数叠加</b>的（每步 ×ZOOM_STEP），目标值越跑越远、
			// 平滑动画越追越快（动画是按"剩余距离的比例"逼近的），手感就是"一开始匀速、后面突然变快"。
			// 固定节拍后按住的步进速率恒定，缓动只负责把每步抹平。
			boolean zoomIn = MINIMAP_ZOOM_IN.isDown();
			boolean zoomOut = MINIMAP_ZOOM_OUT.isDown();
			if ((!zoomIn && !zoomOut) || minecraft.screen != null) {
				zoomRepeatTicks = 0;
			} else if (zoomRepeatTicks++ % ZOOM_REPEAT_TICKS == 0) {
				minimap.zoomBy(zoomIn ? 1.0 / MinimapHud.ZOOM_STEP : MinimapHud.ZOOM_STEP);
			}
			while (ADD_WAYPOINT.consumeClick()) {
				if (minecraft.screen == null) {
					openWaypointEditor(minecraft, null);
				}
			}
			while (WAYPOINT_MANAGER.consumeClick()) {
				if (minecraft.screen == null) {
					minecraft.setScreen(new WaypointListScreen(null));
				}
			}
			while (OPEN_SETTINGS.consumeClick()) {
				if (minecraft.screen == null) {
					// 没有上一层：关掉就回游戏，背景也因此是游戏画面而不是世界地图
					minecraft.setScreen(new MapSettingsScreen(null, minimap, minimap.settings()));
				}
			}
			trackDeath(minecraft, minimap.settings());
		});
	}

	/**
	 * 死亡检测：抓住"活着 → 死亡"的那一次跳变，把死亡地点记成一条"上次死亡地点"路径点。
	 *
	 * <p>挂在每帧的按键轮询里（{@code END_CLIENT_TICK}，仓库已有用法，不必再加 mixin 或事件）。
	 * <b>只在跳变那一帧记一次</b>（{@link #wasDead}）：玩家死后会一直处于死亡状态，每帧都记会反复写盘、
	 * 还会把"上次死亡地点"一次次改名；复活时清掉标记，下次死亡才算新的一次。
	 *
	 * <p>位置取<b>玩家死亡那一刻所在的方块</b>、维度取当前维度（同一个 (x, z) 在别的维度是另一个地方）。
	 */
	private static void trackDeath(Minecraft minecraft, MinimapSettings settings) {
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null) {
			wasDead = false;
			return;
		}
		if (!player.isDeadOrDying()) {
			wasDead = false;
			return;
		}
		// 这一次死亡已经记过了（同一帧、以及之后停在死亡界面的每一帧都走这里）
		if (wasDead) {
			return;
		}
		wasDead = true;
		// 设置里关掉了"死亡地点"：不记新的（已有的那些也不去动）
		if (!settings.showDeathPoints()) {
			return;
		}
		WorldPos pos = new WorldPos(level.dimension().identifier().toString(),
				player.getX(), player.getY() + Waypoint.MARKER_Y_OFFSET, player.getZ());
		Waypoint recorded = WaypointManager.instance()
				.recordDeath(minecraft, pos, settings.keepOldDeathPoints());
		if (recorded != null) {
			// 与死亡点本身的颜色一致（#F9867D）：一眼能把这条提示和地图上那个骷髅对上
			notifyActionBar(minecraft, Component.translatable("message.sinanmap.waypoint.death_recorded",
					recorded.pos().blockX(), recorded.pos().blockY(), recorded.pos().blockZ())
					.withStyle(style -> style.withColor(0xF9867D)));
		}
	}

	/** 上一帧玩家是不是"已经死了"；用来抓"活着 → 死亡"的那一次跳变。 */
	private static boolean wasDead;

	/**
	 * 打开"新建路径点"界面，落点在玩家脚下。不走"按一下就直接建一条"：名字得让玩家起。
	 */
	private static void openWaypointEditor(Minecraft minecraft, Screen parent) {
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null) {
			notifyActionBar(minecraft, Component.translatable("message.sinanmap.waypoint.failed"));
			return;
		}
		minecraft.setScreen(new WaypointEditScreen(parent, null,
				new WorldPos(level.dimension().identifier().toString(),
						player.getX(), player.getY() + Waypoint.MARKER_Y_OFFSET, player.getZ())));
	}

	/**
	 * 在动作栏上回一句：用动作栏而不是聊天栏——加路径点是个"顺手一下"的操作，不该往聊天里灌东西；
	 * 但也不能没有反馈，否则按下去毫无动静会让人以为没生效。
	 */
	private static void notifyActionBar(Minecraft minecraft, Component message) {
		LocalPlayer player = minecraft.player;
		if (player != null) {
			WaypointManager.notify(player, message);
		}
	}
}
