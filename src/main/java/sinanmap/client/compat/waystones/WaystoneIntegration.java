package sinanmap.client.compat.waystones;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import net.blay09.mods.waystones.api.Waystone;
import net.blay09.mods.waystones.api.WaystoneKinds;
import net.blay09.mods.waystones.api.WaystoneVisibility;
import net.blay09.mods.waystones.api.WaystonesAPI;
import net.blay09.mods.waystones.api.event.WaystoneRemoveReceivedEvent;
import net.blay09.mods.waystones.api.event.WaystoneUpdatedEvent;
import net.blay09.mods.waystones.api.event.WaystonesListReceivedEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sinanmap.client.waypoint.WaypointManager;
import sinanmap.common.SinanConstants;
import sinanmap.common.coord.WorldPos;
import sinanmap.common.hud.MinimapSettings;
import sinanmap.common.waypoint.Waypoint;

/**
 * 「传送石碑」兼容：把服务器给的石碑清单自动同步成一组路径点。
 *
 * <p><b>数据从哪来</b>：Waystones 在客户端侧通过事件广播整份清单、单条更新、单条移除；这里都订阅上，
 * 维护一份"按种类分的当前清单"，任何一次变化都重算目标路径点，再交给 {@link WaypointManager#syncExternalGroup} 落盘。
 *
 * <p><b>只同步玩家真的能用的那些</b>：已激活的普通石碑、全局石碑、分享石（未激活的普通石碑只对"走过它的人"有意义，
 * 全列出来会多出一堆点了没用的标记）。<b>不碰玩家自己的路径点</b>：同步的全都落在 {@link #GROUP} 这一组里，只增删改这一组。
 *
 * <p>这个类只有在 Waystones 已安装时才会被加载，见 {@link WaystoneCompat}。
 */
final class WaystoneIntegration {

	private static final Logger LOGGER = LoggerFactory.getLogger(SinanConstants.MOD_ID);

	/** 自动同步过来的路径点落在这一组里：一眼能认出，关掉开关也能整组清掉。 */
	private static final String GROUP = "传送石碑";

	/** 同步过来的路径点用这个颜色（浅青蓝），和玩家自己设的颜色分开。 */
	private static final int COLOR = 0xFF9AD0F5;

	/** 石碑方块与分享石方块的两条原版标签：用来就地确认"这条石碑在客户端的世界里还立着"。 */
	private static final TagKey<Block> WAYSTONE_BLOCKS = blockTag("waystones");
	private static final TagKey<Block> SHARESTONE_BLOCKS = blockTag("sharestones");

	/** 按<b>石碑种类</b>分的当前清单；种类由 Waystones 自己定义（普通石碑、各种分享石…）。 */
	private static final Map<Identifier, List<Waystone>> BY_KIND = new LinkedHashMap<>();

	private static MinimapSettings settings;

	/** 上一次同步时"开关是开还是关"：用来发现刚改了设置，见 {@link #tick()}。 */
	private static Boolean lastEnabled;

	private WaystoneIntegration() {
	}

	/** 订阅 Waystones 的三类事件。只在 Waystones 已安装时调用。 */
	static void register(MinimapSettings settings) {
		WaystoneIntegration.settings = settings;
		WaystonesListReceivedEvent.EVENT.register(WaystoneIntegration::onListReceived);
		WaystoneUpdatedEvent.EVENT.register(event -> onUpdated(event.waystone()));
		WaystoneRemoveReceivedEvent.EVENT.register(event -> onRemoved(event.waystoneId()));
	}

	/**
	 * 每帧一次：只比较"开关变没变"。事件驱动那条路管不到"玩家在设置里把开关关掉"——那时没有任何石碑事件会来，
	 * 已同步出来的路径点会一直留在图上；用一次布尔比较兜住，开销可忽略。
	 */
	static void tick() {
		if (lastEnabled != null && lastEnabled == settings.waystoneIntegration()) {
			return;
		}
		sync(Minecraft.getInstance());
	}

	/** 断开连接：服务器给的清单作废，但已落盘的路径点留着。 */
	static void clearCache() {
		BY_KIND.clear();
		lastEnabled = null;
	}

	private static void onListReceived(WaystonesListReceivedEvent event) {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.execute(() -> {
			// 整份清单按种类给：直接覆盖那一种，被挖掉的石碑不会残留
			BY_KIND.put(event.waystoneType(), List.copyOf(event.waystones()));
			sync(minecraft);
		});
	}

	private static void onUpdated(Waystone waystone) {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.execute(() -> {
			for (Map.Entry<Identifier, List<Waystone>> entry : BY_KIND.entrySet()) {
				List<Waystone> updated = new ArrayList<>(entry.getValue());
				for (int i = 0; i < updated.size(); i++) {
					if (updated.get(i).getWaystoneUid().equals(waystone.getWaystoneUid())) {
						updated.set(i, waystone);
						entry.setValue(List.copyOf(updated));
						break;
					}
				}
			}
			sync(minecraft);
		});
	}

	private static void onRemoved(UUID waystoneId) {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.execute(() -> {
			boolean dropped = false;
			for (Map.Entry<Identifier, List<Waystone>> entry : BY_KIND.entrySet()) {
				List<Waystone> updated = new ArrayList<>(entry.getValue());
				if (updated.removeIf(waystone -> waystone.getWaystoneUid().equals(waystoneId))) {
					entry.setValue(List.copyOf(updated));
					dropped = true;
				}
			}
			LOGGER.info("传送石碑移除通知 | {} | {}", waystoneId, dropped ? "已在清单里" : "清单里没有");
			sync(minecraft);
		});
	}

	/** 把"目标路径点"整体推给路径点存储；关掉开关时目标就是空清单，等于整组清掉。 */
	private static void sync(Minecraft minecraft) {
		boolean enabled = settings.waystoneIntegration();
		lastEnabled = enabled;
		List<Waypoint> desired = enabled ? collectDesired(minecraft) : List.of();
		boolean changed = WaypointManager.instance().syncExternalGroup(minecraft, GROUP, desired);
		if (changed) {
			LOGGER.info("传送石碑已同步 | {} 条（{}）", desired.size(), GROUP);
		}
	}

	/** 算出"现在应该有"的那一份路径点；已存在的那几条沿用原来的创建时间，免得每次都算成新改动。 */
	private static List<Waypoint> collectDesired(Minecraft minecraft) {
		Map<UUID, Waypoint> existing = new HashMap<>();
		for (Waypoint waypoint : WaypointManager.instance().store(minecraft).all()) {
			if (waypoint.group().equals(GROUP)) {
				existing.put(waypoint.id(), waypoint);
			}
		}

		List<Waypoint> result = new ArrayList<>();
		for (List<Waystone> list : BY_KIND.values()) {
			for (Waystone waystone : list) {
				if (!isUsable(minecraft, waystone)) {
					continue;
				}
				result.add(toWaypoint(minecraft, waystone, existing.get(waystone.getWaystoneUid())));
			}
		}
		return result;
	}

	/**
	 * 这条石碑值不值得画到地图上：分享石是所有人共用的，直接算数；普通石碑要么被设成"全局"，要么玩家已激活过它。
	 */
	private static boolean isUsable(Minecraft minecraft, Waystone waystone) {
		if (!waystone.isValid() || waystone.isTransient()) {
			return false;
		}
		// 客户端清单可能比世界里的事实旧一拍：就地看一眼方块还在不在，把幽灵条目挡掉
		if (!stillStanding(minecraft, waystone)) {
			return false;
		}
		if (WaystoneKinds.isSharestone(waystone.getWaystoneKind())) {
			return true;
		}
		WaystoneVisibility visibility = waystone.getVisibility();
		if (visibility == WaystoneVisibility.GLOBAL) {
			return true;
		}
		return visibility == WaystoneVisibility.ACTIVATION && minecraft.player != null
				&& WaystonesAPI.isWaystoneActivated(minecraft.player, waystone);
	}

	/**
	 * 这条石碑在客户端的世界里还立着吗。<b>只在"同一维度 + 区块已加载"时能判断</b>，其余一律当作"还在"：
	 * 区块没加载时 {@code getBlockState} 返回空气，凭它删路径点会把远处好好的标记误删。
	 */
	private static boolean stillStanding(Minecraft minecraft, Waystone waystone) {
		ClientLevel level = minecraft.level;
		if (level == null || !level.dimension().equals(waystone.getDimension())) {
			return true;
		}
		BlockPos pos = waystone.getPos();
		if (!level.hasChunkAt(pos)) {
			return true;
		}
		return isWaystoneBlock(level.getBlockState(pos));
	}

	/**
	 * 路径点该用哪个高度。
	 *
	 * <p><b>不能直接用 {@code getPos()}</b>：石碑两格高的上下两半共用同一个 {@code Waystone} 对象，谁的方块实体先加载谁就把 pos
	 * 写成<b>自己那一格</b>，于是 pos 会在上下两格间跳——直接拿它加偏移，标记会自己窜上去一格。
	 *
	 * <p>这里按<b>方块的实际位置</b>归一到最下面那格，再抬 {@link Waypoint#MARKER_Y_OFFSET} 格；
	 * 判断不了（不同维度 / 区块没加载）时退回按 pos 算。
	 */
	private static double markerY(Minecraft minecraft, Waystone waystone) {
		BlockPos pos = waystone.getPos();
		ClientLevel level = minecraft.level;
		int baseY = pos.getY();
		if (level != null && level.dimension().equals(waystone.getDimension())) {
			BlockPos below = pos.below();
			// 只看下面一格就够：石碑就两格高，再往下不会是石碑
			if (level.hasChunkAt(below) && isWaystoneBlock(level.getBlockState(below))) {
				baseY = below.getY();
			}
		}
		return baseY + Waypoint.MARKER_Y_OFFSET;
	}

	private static boolean isWaystoneBlock(BlockState state) {
		return state.is(WAYSTONE_BLOCKS) || state.is(SHARESTONE_BLOCKS);
	}

	/** 石碑 → 路径点：id 用石碑自己的 UUID，于是"同一条"永远覆盖同一条，不会越堆越多。 */
	private static Waypoint toWaypoint(Minecraft minecraft, Waystone waystone, Waypoint existing) {
		BlockPos pos = waystone.getPos();
		String dimension = waystone.getDimension().identifier().toString();
		long createdAt = existing == null ? System.currentTimeMillis() : existing.createdAt();
		return new Waypoint(waystone.getWaystoneUid(), safeName(waystone),
				new WorldPos(dimension, pos.getX() + 0.5, markerY(minecraft, waystone), pos.getZ() + 0.5),
				GROUP, COLOR, "", true, createdAt, false, "");
	}

	/** 名字取 Waystones 给的那个（没命名过时它会自己生成一个），并按路径点的长度上限截断。 */
	private static String safeName(Waystone waystone) {
		String name = waystone.getEffectiveName().getString().strip();
		if (name.isEmpty()) {
			name = "传送石碑";
		}
		return name.length() > Waypoint.MAX_NAME_LENGTH
				? name.substring(0, Waypoint.MAX_NAME_LENGTH)
				: name;
	}

	private static TagKey<Block> blockTag(String path) {
		return TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("waystones", path));
	}
}
