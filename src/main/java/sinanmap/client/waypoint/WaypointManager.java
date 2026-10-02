package sinanmap.client.waypoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sinanmap.common.SinanConstants;
import sinanmap.common.coord.WorldPos;
import sinanmap.common.waypoint.Waypoint;
import sinanmap.common.waypoint.WaypointStore;
import sinanmap.client.map.data.MapStorage;
import sinanmap.client.map.data.WorldSaveChunks;

/**
 * 路径点的客户端装配：一份内存清单 + 落盘，文件在地图数据根目录
 * （{@code <游戏目录>/sinanmap/<世界>/waypoints.txt}，与 {@link MapStorage} 同根）。
 *
 * <p><b>刻意不放存档里</b>：路径点带名字与坐标，属于个人痕迹；跟着存档走的话，把存档发给别人
 * 等于把自己的标记一起发出去（与地图数据同一个理由，见 {@code MapStorage#currentRoot}）。
 *
 * <p>不按维度分文件：路径点很少，读进内存一份、界面按维度筛即可；分开存还要处理维度目录名
 * （模组维度的 {@code :} 与 {@code /} 不合法）。
 */
public final class WaypointManager {

	private static final Logger LOGGER = LoggerFactory.getLogger(SinanConstants.MOD_ID);

	private static final String FILE_NAME = "waypoints.txt";

	/**
	 * 全局唯一的一份：小地图、全屏地图与按键三处必须看到同一份清单，否则会出现"小地图上加了、打开地图却没有"。
	 */
	private static final WaypointManager INSTANCE = new WaypointManager();

	public static WaypointManager instance() {
		return INSTANCE;
	}

	private final WaypointStore store = new WaypointStore();

	/** 已经装载过的那个世界的数据目录；换了世界（或换了服务器）就重新读。 */
	private Path loadedRoot;

	/**
	 * 装载时那个集成服务端的实例。
	 *
	 * <p>只比数据目录不够：换存档的瞬间 {@code getSingleplayerServer()} 可能仍是上一个世界，会让"新世界"短暂读到旧清单；
	 * 比实例可挡住这个窗口。
	 */
	private Object loadedServer;

	/** 有没有改动没落盘。写一次只有几十 KB，但没必要每加一条就写一次。 */
	private boolean dirty;

	/** 当前世界的地图数据根目录；拿不到（连远程服且没有目录）时为 null，此时不落盘。 */
	private static Path root(Minecraft minecraft) {
		return MapStorage.currentRoot(minecraft);
	}

	/**
	 * 按需读盘：每帧都会调用（地图像素与界面都要用），故只在数据目录变了或首次时真正读文件；
	 * 退出世界时 {@code currentRoot} 变 null，顺便清空内存，免得下个世界短暂画着上个世界的路径点。
	 */
	public WaypointStore store(Minecraft minecraft) {
		Path current = root(minecraft);
		Object server = minecraft.getSingleplayerServer();
		if (current == null) {
			if (loadedRoot != null || loadedServer != null) {
				loadedRoot = null;
				loadedServer = null;
				store.replaceAll(List.of());
			}
			return store;
		}
		if (current.equals(loadedRoot) && server == loadedServer) {
			return store;
		}
		loadedRoot = current;
		loadedServer = server;
		store.replaceAll(read(current));
		dirty = false;
		LOGGER.info("路径点已载入 | {} 条 | {}", store.size(), current.resolve(FILE_NAME));
		return store;
	}

	/** 按给定的名字、位置、分组与颜色建一条路径点，并立刻落盘；新建的那条自动成为选中项。 */
	Waypoint create(Minecraft minecraft, String name, WorldPos pos, String group, int color,
			String shortName) {
		Waypoint waypoint = Waypoint.create(name, pos, group, color).withShortName(shortName);
		store(minecraft).add(waypoint);
		selected = waypoint.id();
		dirty = true;
		save(minecraft);
		return waypoint;
	}

	/**
	 * 记录一次死亡：把死亡地点变成一条路径点。
	 *
	 * <p>永远只有一条叫"上次死亡地点"（老的先改名"此前死亡地点"）；死在<b>同一格</b>时复用那一条，
	 * 否则反复摔死会堆出一串同坐标的点。
	 *
	 * <p>名字取自语言文件但被持久化，之后改语言不会重命名已记录的点。
	 *
	 * @param keepOld 是否保留旧死亡地点（设置里可关）；关掉时旧的直接删掉。
	 */
	public Waypoint recordDeath(Minecraft minecraft, WorldPos pos, boolean keepOld) {
		if (pos == null) {
			return null;
		}
		WaypointStore current = store(minecraft);
		String last = Component.translatable("waypoint.sinanmap.death_last").getString();
		String previous = Component.translatable("waypoint.sinanmap.death_previous").getString();

		Waypoint sameSpot = null;
		// 先收下来再改：一边遍历一边改这份清单是自找麻烦（add 会替换同 id 的那条）
		List<Waypoint> renames = new ArrayList<>();
		List<Waypoint> removals = new ArrayList<>();
		for (Waypoint waypoint : current.all()) {
			if (!waypoint.death()) {
				continue;
			}
			if (waypoint.pos().dimension().equals(pos.dimension())
					&& waypoint.pos().blockX() == pos.blockX()
					&& waypoint.pos().blockZ() == pos.blockZ()) {
				sameSpot = waypoint;
			} else if (keepOld) {
				renames.add(waypoint.withName(previous).withGroup(Waypoint.DEATH_GROUP));
			} else {
				removals.add(waypoint);
			}
		}
		for (Waypoint renamed : renames) {
			current.add(renamed);
		}
		for (Waypoint removed : removals) {
			current.remove(removed.id());
		}

		Waypoint death = sameSpot != null
				? sameSpot.withName(last).withPos(pos).withGroup(Waypoint.DEATH_GROUP)
				: Waypoint.createDeath(last, pos, Waypoint.DEATH_GROUP);
		current.add(death);
		dirty = true;
		select(death.id());
		save(minecraft);
		return death;
	}

	/** 某个维度里出现过的分组（按名排序，但<b>默认组永远第一</b>）；界面只列真的有分组。 */
	List<String> groupsIn(Minecraft minecraft, String dimension) {
		List<String> groups = new ArrayList<>();
		groups.add(Waypoint.DEFAULT_GROUP);
		for (Waypoint waypoint : store(minecraft).all()) {
			if (waypoint.pos().dimension().equals(dimension) && !groups.contains(waypoint.group())) {
				groups.add(waypoint.group());
			}
		}
		groups.subList(1, groups.size()).sort(String::compareTo);
		return groups;
	}

	/**
	 * 把某个维度里的一整组改名：改的是组里每条的 group 字段（分组只是路径点上的一个名字，无独立"组对象"），
	 * 因此不会出现"组还在、点却没了"。
	 *
	 * @return 改了几条
	 */
	int renameGroup(Minecraft minecraft, String dimension, String oldGroup, String newGroup) {
		String target = newGroup == null || newGroup.isBlank() ? Waypoint.DEFAULT_GROUP : newGroup.strip();
		if (target.equals(oldGroup)) {
			return 0;
		}
		int changed = 0;
		// 先收集再写回（边遍历边改存储不安全）
		List<Waypoint> updated = new ArrayList<>();
		for (Waypoint waypoint : store(minecraft).all()) {
			if (!waypoint.pos().dimension().equals(dimension) || !waypoint.group().equals(oldGroup)) {
				continue;
			}
			updated.add(waypoint.withGroup(target));
		}
		for (Waypoint waypoint : updated) {
			store(minecraft).add(waypoint);
			changed++;
		}
		if (changed > 0) {
			dirty = true;
			save(minecraft);
		}
		return changed;
	}

	/** 覆盖同 id 的那一条（改名、改色、改分类、改坐标都走这里）。 */
	void update(Minecraft minecraft, Waypoint waypoint) {
		store(minecraft).add(waypoint);
		dirty = true;
		save(minecraft);
	}

	/** 按 id 删一条。 */
	public boolean remove(Minecraft minecraft, UUID id) {
		if (!store(minecraft).remove(id)) {
			return false;
		}
		if (id.equals(selected)) {
			selected = null;
		}
		dirty = true;
		save(minecraft);
		return true;
	}

	/**
	 * 把某个分组整体同步成 {@code desired} 那一份（外部数据源专用，例如传送石碑的自动同步）。
	 *
	 * <p>按 <b>id</b> 覆盖，再清掉该分组里不在 {@code desired} 的：一次调用即可表达增/改/删，调用方不必算差集；
	 * 只动这个分组，玩家自己的点不受影响。
	 *
	 * @return 有没有变化（没变就不落盘）
	 */
	public boolean syncExternalGroup(Minecraft minecraft, String group, List<Waypoint> desired) {
		WaypointStore target = store(minecraft);
		// 按 id 索引现存项以判断"变没变"：每帧同步一次，否则每帧都当改动、都写盘
		Map<UUID, Waypoint> existing = new HashMap<>();
		for (Waypoint waypoint : target.all()) {
			existing.put(waypoint.id(), waypoint);
		}

		Set<UUID> keep = new HashSet<>();
		boolean changed = false;
		for (Waypoint waypoint : desired) {
			keep.add(waypoint.id());
			if (!waypoint.equals(existing.get(waypoint.id()))) {
				target.add(waypoint);
				changed = true;
			}
		}
		// 先收集要删的再删（边遍历边改存储不安全）
		List<UUID> stale = new ArrayList<>();
		for (Waypoint known : existing.values()) {
			if (known.group().equals(group) && !keep.contains(known.id())) {
				stale.add(known.id());
			}
		}
		for (UUID id : stale) {
			if (target.remove(id)) {
				changed = true;
				if (id.equals(selected)) {
					selected = null;
				}
			}
		}
		if (changed) {
			dirty = true;
			save(minecraft);
		}
		return changed;
	}

	/** 切换"在地图上显示"。 */
	Waypoint toggleVisible(Minecraft minecraft, Waypoint waypoint) {
		Waypoint updated = waypoint.withVisible(!waypoint.visible());
		update(minecraft, updated);
		return updated;
	}

	/** 当前选中的那一条。跨界面共用，放在此处而非各界面里，才能从地图延续到管理器。 */
	private UUID selected;

	UUID selected() {
		return selected;
	}

	public void select(UUID id) {
		if (!Objects.equals(selected, id)) {
			selected = id;
		}
	}

	public boolean isSelected(Waypoint waypoint) {
		return waypoint.id().equals(selected);
	}

	/** 有新改动就落盘。 */
	void save(Minecraft minecraft) {
		if (!dirty) {
			return;
		}
		Path base = loadedRoot;
		if (base == null) {
			return;
		}
		Path file = base.resolve(FILE_NAME);
		try {
			Files.createDirectories(base);
			// 先写临时文件再改名：写一半崩掉时不会把原来那份好数据覆盖成半截
			Path temp = base.resolve(FILE_NAME + ".tmp");
			Files.writeString(temp, WaypointStore.serialize(store.all()), StandardCharsets.UTF_8);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
			dirty = false;
		} catch (IOException e) {
			// 落盘失败不影响本局使用（内存里那份还在），但要说清楚，否则会被当成界面的问题
			LOGGER.warn("路径点无法写入 {}", file, e);
		}
	}

	/** 退出世界时把没写完的写掉。 */
	public void flush(Minecraft minecraft) {
		save(minecraft);
	}

	/**
	 * 传送到一条路径点（世界地图的右键菜单与管理器共用）：走原版 {@code /tp}——无权限时它会失败并在聊天栏说明，
	 * 比自猜权限可靠；跨维度不传（坐标形式只在当前维度有效）。
	 */
	public static void teleport(Minecraft minecraft, Waypoint waypoint) {
		if (waypoint != null) {
			teleportTo(minecraft, waypoint.pos());
		}
	}

	/** 传送到某个坐标；做不到时在动作栏说明原因。 */
	public static void teleportTo(Minecraft minecraft, WorldPos pos) {
		teleportTo(minecraft, pos, false);
	}

	/**
	 * 传送到某个坐标。
	 *
	 * @param yUnknown 高度未知（算不出地表、路径点那份也是猜的）：命令改用 {@code ~} 保持当前高度，而不是猜一个
	 *                 （猜 64 会落到地底）
	 */
	public static void teleportTo(Minecraft minecraft, WorldPos pos, boolean yUnknown) {
		LocalPlayer player = minecraft.player;
		if (pos == null || player == null || minecraft.level == null) {
			return;
		}
		String dimension = minecraft.level.dimension().identifier().toString();
		if (!pos.dimension().equals(dimension)) {
			notify(player, Component.translatable("message.sinanmap.waypoint.other_dimension_teleport"));
			return;
		}
		// 单人存档没开作弊时 /tp 一定失败，先说清楚，别让玩家以为模组坏了
		MinecraftServer server = minecraft.getSingleplayerServer();
		if (server != null && !server.getWorldData().isAllowCommands()) {
			notify(player, Component.translatable("message.sinanmap.waypoint.need_cheats"));
			return;
		}
		// 高度未知（调用方说不知、或者问下来也定不下来）→ 用 `~` 保持玩家当前高度
		Integer y = yUnknown ? null : teleportY(minecraft, pos);
		player.connection.sendCommand("tp @s %d %s %d".formatted(
				pos.blockX(), y == null ? "~" : y.toString(), pos.blockZ()));
	}

	/**
	 * 在聊天里分享一串坐标（收件人点 {@code [添加]} 就能直接建出来）。实现见 {@link WaypointShare}。
	 */
	public static void shareLocation(WorldPos pos) {
		WaypointShare.shareLocation(pos);
	}

	/** 在聊天里分享一条路径点（带名字），见 {@link #shareLocation}。 */
	public static void shareWaypoint(Waypoint waypoint) {
		WaypointShare.share(waypoint);
	}

	/**
	 * 传送用哪个 Y；<b>定不下来时返回 null</b>（调用方改用 {@code ~}）。
	 *
	 * <p><b>不能照路径点里存的那个直接传</b>：那个 Y 可能是"当时地图还不知道高度"时留下的猜测值
	 * （旧版本固定写 64、或在没加载的远处随手新建），照它传送就是<b>落到地底、卡在石头里</b>。
	 *
	 * <p>规则：<b>站得住</b>（脚与头都不是挡路方块）就用它（洞穴里的点因此不会被硬拽到地面）；
	 * 站不住就改用这一列的地表高度——已加载的问客户端高度图，没加载的问存档里原版写的高度图
	 * （见 {@code WorldSaveChunks#surfaceHeightAt}）；两处都问不到就返回 {@code null}。
	 */
	private static Integer teleportY(Minecraft minecraft, WorldPos pos) {
		ClientLevel level = minecraft.level;
		if (level == null) {
			return pos.blockY();
		}
		int x = pos.blockX();
		int z = pos.blockZ();
		if (Boolean.TRUE.equals(WorldSaveChunks.standableAt(minecraft, x, pos.blockY(), z))) {
			return pos.blockY();
		}
		if (level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) != null) {
			return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
		}
		int fromSave = WorldSaveChunks.surfaceHeightAt(minecraft, x, z);
		return fromSave == WorldSaveChunks.NO_HEIGHT ? null : fromSave;
	}

	/** 在动作栏上回一句。 */
	public static void notify(LocalPlayer player, Component message) {
		if (player != null) {
			player.sendOverlayMessage(message);
		}
	}

	private static List<Waypoint> read(Path base) {
		Path file = base.resolve(FILE_NAME);
		if (!Files.isRegularFile(file)) {
			return List.of();
		}
		try {
			return WaypointStore.parse(Files.readString(file, StandardCharsets.UTF_8));
		} catch (IOException e) {
			LOGGER.warn("路径点读不出 {}", file, e);
			return List.of();
		}
	}

	/**
	 * 自动命名："分组名 序号"（按已有多少条同组推序号）。名字重复无所谓（id 才是身份），只是别让新建的每一条都同名。
	 *
	 * <p><b>只在没给名字时兜底</b>：新建界面的名字框不预填，玩家想直接用空名字保存才落到这里。
	 */
	String suggestName(WaypointStore current, String group) {
		int count = 1;
		for (Waypoint waypoint : current.all()) {
			if (waypoint.group().equals(group)) {
				count++;
			}
		}
		return group + " " + count;
	}
}
