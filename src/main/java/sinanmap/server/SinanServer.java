package sinanmap.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sinanmap.common.SinanConstants;
import sinanmap.common.net.HelloC2S;
import sinanmap.common.net.SinanNet;
import sinanmap.common.net.TrackedPlayersS2C;
import sinanmap.common.net.WorldInfoS2C;

/**
 * 司南的服务端侧。
 *
 * <p><b>只做两件事</b>：给这个存档一个稳定的世界标识，以及把"同意共享位置的玩家坐标"发给彼此。
 * 全部是可选增强——没装司南的客户端不受任何影响，装了但服务端没装的客户端就退回纯本地模式
 * （{@code fabric.mod.json} 里 {@code environment} 是 {@code *}，所以两边都会加载这个类，
 * 但这里<b>不引用任何客户端类</b>）。
 *
 * <p><b>状态都存在内存里</b>（按玩家 UUID）：这些数据"这次连接有效"，玩家一退就该忘掉。
 * 硬塞进存档反而要处理升级、清理这些琐事，收益为零。
 */
public final class SinanServer {

	private static final Logger LOGGER = LoggerFactory.getLogger(SinanConstants.MOD_ID);

	/** 存档里存"这个世界的标识"的文件名（与地图数据同一个目录）。 */
	private static final String WORLD_ID_FILE = "world_id.txt";

	/** 位置同步的下发间隔（tick）：一秒一次。走得太勤只是白占带宽，地图上也看不出差别。 */
	private static final int SYNC_INTERVAL_TICKS = 20;

	/** 一次最多同步多少个玩家：取最近的这么多。防止大服上一次塞几百个坐标。 */
	private static final int MAX_TRACKED_PLAYERS = 64;

	/** 每个在线玩家的状态：协议版本 + 是否同意共享位置。 */
	private record ClientState(int protocol, boolean sharePosition) {
	}

	/** 按玩家 UUID 记的状态；退出即删。 */
	private static final Map<UUID, ClientState> CLIENTS = new HashMap<>();

	/** 世界标识的缓存（同一个服务端实例只算一次）。 */
	private static MinecraftServer cachedServer;
	private static String cachedWorldId;

	private static int tickCounter;

	private SinanServer() {
	}

	/** 收到客户端的报到：记下它的版本与共享意愿，然后把世界身份回过去。 */
	public static void onHello(ServerPlayer player, HelloC2S payload) {
		CLIENTS.put(player.getUUID(), new ClientState(payload.protocol(), payload.sharePosition()));
		if (payload.protocol() != SinanNet.PROTOCOL_VERSION) {
			// 版本对不上就什么都不发：对方解不了我们的包，发了只会断连
			LOGGER.info("司南客户端协议不匹配，已降级为纯本地模式 | 玩家={} 客户端={} 服务端={}",
					player.getGameProfile().name(), payload.protocol(), SinanNet.PROTOCOL_VERSION);
			return;
		}
		sendWorldInfo(player);
	}

	/** 玩家退出：忘掉它的状态，免得下次进来还留着上次的共享设置。 */
	public static void onDisconnect(ServerPlayer player) {
		CLIENTS.remove(player.getUUID());
	}

	/** 每秒把"同意共享位置的玩家坐标"下发给装了司南的客户端。 */
	public static void tick(MinecraftServer server) {
		if (++tickCounter < SYNC_INTERVAL_TICKS) {
			return;
		}
		tickCounter = 0;
		if (CLIENTS.isEmpty()) {
			return;
		}
		for (ServerPlayer receiver : server.getPlayerList().getPlayers()) {
			if (!isSinanClient(receiver)) {
				continue;
			}
			ServerPlayNetworking.send(receiver, new TrackedPlayersS2C(collectTracked(server, receiver)));
		}
	}

	/**
	 * 这个接收者现在应该看到哪些玩家。
	 *
	 * <p>只取<b>同一维度</b>的：跨维度的坐标画在地图上没有意义（同一个 (x,z) 在三个维度是三处地方）。
	 * 不限距离但按距离取最近的 {@value #MAX_TRACKED_PLAYERS} 个，大服上也不会一次塞爆。
	 */
	private static List<TrackedPlayersS2C.Player> collectTracked(MinecraftServer server, ServerPlayer receiver) {
		List<ServerPlayer> candidates = new ArrayList<>();
		for (ServerPlayer other : server.getPlayerList().getPlayers()) {
			if (other == receiver) {
				continue;
			}
			ClientState state = CLIENTS.get(other.getUUID());
			// 对方必须装了司南、协议对得上、并且同意共享位置——三样缺一不可
			if (state == null || state.protocol() != SinanNet.PROTOCOL_VERSION || !state.sharePosition()) {
				continue;
			}
			if (!other.level().dimension().equals(receiver.level().dimension())) {
				continue;
			}
			candidates.add(other);
		}
		candidates.sort(Comparator.comparingDouble(player -> player.distanceToSqr(receiver)));
		List<TrackedPlayersS2C.Player> result = new ArrayList<>();
		for (ServerPlayer player : candidates) {
			if (result.size() >= MAX_TRACKED_PLAYERS) {
				break;
			}
			result.add(new TrackedPlayersS2C.Player(player.getUUID(), player.getGameProfile().name(),
					player.level().dimension().identifier().toString(),
					player.getX(), player.getY(), player.getZ()));
		}
		return result;
	}

	private static boolean isSinanClient(ServerPlayer player) {
		ClientState state = CLIENTS.get(player.getUUID());
		if (state == null || state.protocol() != SinanNet.PROTOCOL_VERSION) {
			return false;
		}
		// 客户端没注册这个通道就别发：fabric 会记一条警告，玩家则会断连
		return ServerPlayNetworking.canSend(player, WorldInfoS2C.TYPE);
	}

	private static void sendWorldInfo(ServerPlayer player) {
		if (ServerPlayNetworking.canSend(player, WorldInfoS2C.TYPE)) {
			ServerPlayNetworking.send(player,
					new WorldInfoS2C(SinanNet.PROTOCOL_VERSION, worldId(player.level().getServer())));
		}
	}

	/**
	 * 这个存档的世界标识。
	 *
	 * <p>第一次问的时候生成一个随机 UUID 写进 {@code <存档>/sinanmap/world_id.txt}，之后一直用它。
	 * 客户端靠它认出"这还是同一个世界"——地址会变（换域名、加端口、走内网），文件不会。
	 */
	private static String worldId(MinecraftServer server) {
		if (server == cachedServer && cachedWorldId != null) {
			return cachedWorldId;
		}
		cachedServer = server;
		Path file = dataDir(server).resolve(WORLD_ID_FILE);
		String id = readString(file);
		if (id == null || id.isBlank()) {
			id = UUID.randomUUID().toString();
			writeString(file, id);
			LOGGER.info("已为这个世界生成标识 | {} | {}", id, file);
		}
		cachedWorldId = id.strip();
		return cachedWorldId;
	}

	private static Path dataDir(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve(SinanConstants.MOD_ID);
	}

	private static String readString(Path file) {
		try {
			return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
		} catch (IOException e) {
			LOGGER.warn("读不出来 | {}", file, e);
			return null;
		}
	}

	private static void writeString(Path file, String text) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, text, StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOGGER.warn("写不出去 | {}", file, e);
		}
	}
}
