package sinanmap.client.map.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sinanmap.common.SinanConstants;
import sinanmap.common.storage.WorldKeys;

/**
 * 多人服务器的地图数据目录：<b>先按世界标识找，找不到才按地址分</b>。
 *
 * <p><b>为什么不能只看地址</b>：地址不是身份。同一个服务器换个域名、加个端口、从公网地址换成内网地址，
 * 地址就变了——按地址分的目录会另起一份，表现出来就是"换了个地址连进去，地图和路径点全没了"。
 * 所以服务端（如果装了司南）会给每个存档一个不变的标识（见 {@code SinanServer#worldId}），
 * 客户端把它记在目录里；下次不管用哪个地址连进来，都能顺着标识找回同一份数据。
 *
 * <p>记在<b>目录里的文件</b>而不是另开一张索引：索引文件会与目录不同步（删了目录忘了改索引就永远找不回来），
 * 而文件跟着目录走，删目录等于连记录一起删。
 */
public final class ServerWorldIndex {

	private static final Logger LOGGER = LoggerFactory.getLogger(SinanConstants.MOD_ID);

	/** 记"这个目录属于哪个世界"的文件名。 */
	private static final String ID_FILE = "world_id.txt";

	/** 本次连接拿到的世界标识；null = 这个服务器没装司南。 */
	private static volatile String worldId;

	/** 上一次算出来的结果（按地址缓存）：{@code currentRoot} 每帧都会被问，不能每次都扫目录。 */
	private static String cachedAddress;
	private static Path cachedRoot;

	private ServerWorldIndex() {
	}

	/** 记下服务端给的世界标识（收到 {@code world_info} 时调）；传 null 表示断开连接。 */
	public static void setWorldId(String id) {
		worldId = id;
		// 标识变了，上一次按地址算的结论就不作数了
		cachedAddress = null;
		cachedRoot = null;
	}

	/**
	 * 这个服务器地址对应的数据目录。
	 *
	 * @param base    多人数据的根目录（{@code <游戏目录>/sinanmap}）
	 * @param address 服务器地址，用于"找不到标识"时兜底
	 */
	public static Path root(Path base, String address) {
		if (address.equals(cachedAddress) && cachedRoot != null) {
			return cachedRoot;
		}
		Path byAddress = base.resolve(WorldKeys.multiplayer(address));
		Path resolved = byAddress;
		String id = worldId;
		if (id != null && !id.isBlank()) {
			Path byId = findByWorldId(base, id);
			if (byId != null) {
				resolved = byId;
			} else {
				// 第一次见这个世界：就用地址目录，但把标识记进去，以后换地址也找得回来
				writeId(byAddress, id);
			}
		}
		cachedAddress = address;
		cachedRoot = resolved;
		return resolved;
	}

	/** 在 {@code base} 下面找一个"标识对得上"的目录；没有就返回 null。 */
	private static Path findByWorldId(Path base, String id) {
		if (!Files.isDirectory(base)) {
			return null;
		}
		try (Stream<Path> children = Files.list(base)) {
			for (Path child : children.toList()) {
				if (!Files.isDirectory(child)) {
					continue;
				}
				String recorded = readId(child);
				if (id.equals(recorded)) {
					return child;
				}
			}
		} catch (IOException e) {
			LOGGER.warn("扫不出服务器数据目录，这一局按地址分 | {}", base, e);
		}
		return null;
	}

	private static String readId(Path dir) {
		Path file = dir.resolve(ID_FILE);
		try {
			return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8).strip() : null;
		} catch (IOException e) {
			return null;
		}
	}

	private static void writeId(Path dir, String id) {
		try {
			Files.createDirectories(dir);
			Files.writeString(dir.resolve(ID_FILE), id, StandardCharsets.UTF_8);
		} catch (IOException e) {
			// 写不进去只是"换地址后要找不回来"，不影响这一局使用
			LOGGER.warn("世界标识写不进去，换地址后这份数据可能对不上 | {}", dir, e);
		}
	}
}
