package sinanmap.client.map.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sinanmap.common.SinanConstants;
import sinanmap.common.map.ChunkColorCache;
import sinanmap.common.map.LayerSelector;
import sinanmap.common.storage.ChunkRecord;
import sinanmap.common.storage.RegionStore;
import sinanmap.common.storage.WorldKeys;

/**
 * 地图数据的落盘与读取。
 *
 * <p>按<b>世界 / 服务器</b>分目录：单人存档用存档目录名，多人服务器用服务器地址，因此不同世界
 * 的地图不会互相覆盖。
 *
 * <p>线程模型：<b>读在渲染线程、写在一个后台线程</b>，两者各持独立的 {@link RegionStore} 实例
 * ——共享同一个 {@code RandomAccessFile} 同时读写不安全，分开持有比加锁更简单也更快。
 */
public final class MapStorage implements AutoCloseable {

	private static final Logger LOGGER = LoggerFactory.getLogger(SinanConstants.MOD_ID);

	/**
	 * 未命中记录最多记多少条，超过就整体清空（避免无限增长）。
	 *
	 * <p>必须比"一屏的区块数"大：一屏要看上万条，上限太小会被反复清空、每轮合成又去 stat 文件。
	 */
	private static final int ABSENT_LIMIT = 32768;

	private final Path root;

	/**
	 * 读取侧的区域文件，按访问顺序做 LRU 淘汰。
	 *
	 * <p>每个区域文件整份读进内存（见 {@link RegionStore}），必须限制留几个，否则走得越远内存涨得
	 * 越多。落盘是逐块压缩的，一个区域文件通常只有几百 KB，{@value #MAX_READ_REGIONS} 个也就几十兆，
	 * 够覆盖一屏并留出平移余量。
	 */
	private final Map<Long, RegionStore> readStores = new LinkedHashMap<>(16, 0.75F, true);

	private final Map<Long, RegionStore> writeStores = new HashMap<>();

	/**
	 * 读取侧最多同时留几个区域文件在内存里。
	 *
	 * <p>要盖得住一屏：瓦片页正好等于一个区域文件，一屏最多二十几页；留少了会出现"刚丢掉又要读回来"，
	 * 每次都是整份读盘。
	 */
	private static final int MAX_READ_REGIONS = 24;

	/** 未命中记录。写入线程也会清理它，因此必须是线程安全的。 */
	private final Set<Long> absent = ConcurrentHashMap.newKeySet();

	/**
	 * 本局已经排过写盘队列的区块。
	 *
	 * <p>区块被淘汰后会重新采样，若每次都排写盘，同一区块一局里会反复写几十遍、写队列越积越长；
	 * 同一局内同一层的同一区块写一次就够。
	 */
	private final Set<Long> saved = new HashSet<>();

	/**
	 * 有新区块写进去、读取侧的存在位图因此过时的区域。
	 *
	 * <p>读侧把存在位图缓存在内存里，写入方落盘后必须在这里标记，读线程下次访问该区域时据此丢弃
	 * 旧位图；否则"数据已在磁盘上"的区域会被判成没有数据，地图上留下一块黑。
	 */
	private final Set<Long> dirtyRegions = ConcurrentHashMap.newKeySet();
	private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "sinanmap-map-writer");
		thread.setDaemon(true);
		return thread;
	});

	/**
	 * 预读线程：把区域文件整份读进内存。
	 *
	 * <p><b>单线程就够。</b>它做的是顺序读，多开只会互相抢磁盘；真正需要并行的是"解压 + 解析"，
	 * 那一半在 {@code ClientChunkColorProvider} 的预读池里（见那里的说明）。
	 */
	private final ExecutorService preloader = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "sinanmap-map-preload");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});

	private volatile boolean writeFailed;
	private boolean readFailureLogged;

	private MapStorage(Path root) {
		this.root = root;
	}

	/**
	 * 当前世界的地图数据根目录：<b>游戏目录下的 {@code sinanmap/<世界>}</b>，与存档分开。
	 *
	 * <p><b>为什么不放进存档里。</b>存档是要拿去分享、备份、迁移的；地图数据是"你走到过哪里、那边
	 * 长什么样"，路径点还带着名字与坐标，都属于个人痕迹。放进存档等于"把存档发给别人，也把自己的
	 * 足迹一起发出去了"。放在游戏目录下，各世界仍按名字分目录、互不覆盖。
	 *
	 * <ul>
	 *   <li>单人：按<b>存档目录名</b>分（{@code sp_<目录名>}）。目录名在同一台机器上唯一；世界显示名
	 *       可以改、也不保证唯一，拿它分会把两份数据混到一起。</li>
	 *   <li>多人：按服务器地址分（{@code mp_<地址>}），装了司南的服务端还能靠世界标识把换过地址的
	 *       数据找回来（见 {@link ServerWorldIndex}）。</li>
	 * </ul>
	 *
	 * <p>结果按世界缓存：这个方法每帧都会被问，不能每次都去问服务端路径、拼字符串。
	 *
	 * @return 无法确定时返回 null（此时不落盘，功能降级但不影响游戏）
	 */
	public static Path currentRoot(Minecraft minecraft) {
		try {
			MinecraftServer server = minecraft.getSingleplayerServer();
			Path base = Minecraft.getInstance().gameDirectory.toPath().resolve(SinanConstants.MOD_ID);
			if (server != null) {
				if (server == cachedServer && cachedRoot != null) {
					return cachedRoot;
				}
				Path save = server.getWorldPath(LevelResource.ROOT);
				// getWorldPath 末尾会带一个 "."（存档根就是存档目录本身），先归一化再取目录名
				Path folder = save.normalize().getFileName();
				if (folder == null) {
					return null;
				}
				Path root = base.resolve(WorldKeys.singleplayer(folder.toString()));
				migrateFromSave(save, root);
				cachedServer = server;
				cachedRoot = root;
				return root;
			}
			// 多人服务器客户端拿不到存档，按服务器地址分目录放在游戏目录下。
			// 游戏目录用原版的 Minecraft#gameDirectory（public final），不必再依赖 fabric-loader
			ServerData current = minecraft.getCurrentServer();
			if (current != null && current.ip != null) {
				return ServerWorldIndex.root(base, current.ip);
			}
		} catch (RuntimeException e) {
			LOGGER.warn("无法确定地图数据目录，本局地图数据不会保存", e);
		}
		return null;
	}

	/** {@link #currentRoot} 的缓存（每帧都会被问，见那里的说明）。 */
	private static MinecraftServer cachedServer;
	private static Path cachedRoot;

	/**
	 * 把老布局（数据放在存档里的 {@code <存档>/sinanmap}）搬一次家，并把地图数据那部分删掉。
	 *
	 * <p><b>为什么要搬。</b>路径点是这里唯一"丢了就没了"的东西（地图可以重新走一遍，名字与坐标不行）。
	 * 数据目录换地方之后，老目录里那份 {@code waypoints.txt} 不搬过来就等于用户升级一次丢一次路径点。
	 *
	 * <p><b>为什么只删子目录。</b>老目录下的子目录是按维度分的地图数据（几个 GB），而"存档里不要再留
	 * 这些痕迹"指的正是这部分，所以删掉；而散落在同一层的 {@code world_id.txt} 是<b>服务端</b>记
	 * "这个世界是谁"的文件（见 {@code SinanServer#worldId}），属于存档本身、也不敏感，留着不动。
	 * 它还会在进世界时被服务端按需重写，所以这里绝不能去删它所在的整个目录——那会删到一半报
	 * "目录非空"。
	 */
	private static void migrateFromSave(Path saveDir, Path newRoot) {
		Path legacy = saveDir.resolve(SinanConstants.MOD_ID);
		if (!Files.isDirectory(legacy)) {
			return;
		}
		try {
			Path legacyWaypoints = legacy.resolve("waypoints.txt");
			Path newWaypoints = newRoot.resolve("waypoints.txt");
			if (Files.isRegularFile(legacyWaypoints) && !Files.isRegularFile(newWaypoints)) {
				Files.createDirectories(newRoot);
				Files.move(legacyWaypoints, newWaypoints);
				LOGGER.info("路径点已从存档里搬到游戏目录 | {}", newWaypoints);
			}
			try (Stream<Path> children = Files.list(legacy)) {
				for (Path child : children.toList()) {
					if (Files.isDirectory(child)) {
						deleteRecursively(child);
					}
				}
			}
			LOGGER.info("旧的存档内地图数据已清理（数据现在放在游戏目录下） | {}", legacy);
		} catch (IOException e) {
			// 搬不动就先这样：新数据照样写在游戏目录下，只是路径点要用户自己挪一次
			LOGGER.warn("把旧的地图数据搬出存档失败 | {}", legacy, e);
		}
	}

	/**
	 * 打开一个存储根目录。
	 *
	 * <p>路径分两层：{@code 世界根/模组名（多人还要加一层服务器）} 与最里面的{@code 维度}。
	 * 维度这一层不能省——三个维度的区块坐标各自独立，不分开就会互相覆盖。模组维度同样适用：
	 * 维度 id 里的 {@code :} 与 {@code /} 已在调用方换成下划线。
	 *
	 * <p>存储布局为 {@code <世界>/<维度>/<多世界 id>[/caves/<层>]}，第四层（多世界 id）用格式版本
	 * 目录代替，让不同批次的数据不会互相污染。
	 */
	static MapStorage open(Path root, String dimensionKey) {
		Path full = dimensionKey == null || dimensionKey.isEmpty() ? root : root.resolve(dimensionKey);
		purgeStaleVersions(full);
		purgeStaleCaveLayers(full);
		return new MapStorage(full);
	}

	/**
	 * 删掉"当前分档粒度下再也读不到"的洞穴层目录。
	 *
	 * <p>洞穴按 {@link LayerSelector#STORAGE_STEP} 格一档落盘，每个 {@code cave_<层高>} 目录都是<b>一整套</b>
	 * 区域文件。步长一改大，不是新步长整数倍的旧目录就永远读不回来了——而这一层恰好是占地最大的那部分
	 * （实测比地表大十倍），不清就是白白占着几个 GB。判断依据只有"目录名的层高能不能被新步长整除"，
	 * 对不上就删，因此改步长这件事自带一次清理，不必让用户手动删目录。
	 */
	private static void purgeStaleCaveLayers(Path root) {
		Path versionDir = root.resolve("v" + RegionStore.formatVersion());
		if (!Files.isDirectory(versionDir)) {
			return;
		}
		try (Stream<Path> children = Files.list(versionDir)) {
			for (Path child : children.toList()) {
				if (!Files.isDirectory(child)) {
					continue;
				}
				String name = child.getFileName().toString();
				if (!name.startsWith(CAVE_PREFIX)) {
					continue;
				}
				int layer;
				try {
					layer = Integer.parseInt(name.substring(CAVE_PREFIX.length()));
				} catch (NumberFormatException e) {
					continue;
				}
				if (Math.floorMod(layer, LayerSelector.STORAGE_STEP) != 0) {
					deleteRecursively(child);
				}
			}
		} catch (IOException e) {
			LOGGER.warn("清理旧分档的洞穴数据失败，先放着不管", e);
		}
	}

	/** 洞穴层目录名的前缀，与 {@link #layerDirectory} 里拼出来的一致。 */
	private static final String CAVE_PREFIX = "cave_";

	/**
	 * 清掉旧格式版本留下的目录。
	 *
	 * <p>数据目录按格式版本分目录后，旧目录再也读不到，留着只占地方——洞穴层是每层一个目录，
	 * 改几次配方就会积出几百个目录、成百上千个区域文件。这里只删根目录下"不是当前版本"的目录。
	 */
	private static void purgeStaleVersions(Path root) {
		if (!Files.isDirectory(root)) {
			return;
		}
		String current = "v" + RegionStore.formatVersion();
		try (Stream<Path> children = Files.list(root)) {
			for (Path child : children.toList()) {
				if (!Files.isDirectory(child) || child.getFileName().toString().equals(current)) {
					continue;
				}
				deleteRecursively(child);
			}
		} catch (IOException e) {
			LOGGER.warn("清理旧版本地图数据失败，先放着不管", e);
		}
	}

	/** 递归删除一个目录（深度优先，先删文件再删空目录）。 */
	private static void deleteRecursively(Path directory) throws IOException {
		try (Stream<Path> paths = Files.walk(directory)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	/** 根目录，供日志与调试。 */
	Path root() {
		return root;
	}

	/**
	 * 读取区块数据，没有记录时返回 null。
	 *
	 * <p><b>渲染线程与后台预读线程都会调</b>（后者见 {@code ClientChunkColorProvider#enqueuePrefetch}：
	 * 自己存档里就有的数据由后台线程解好再交给渲染线程，渲染线程连解压都不必等）。未命中会被记住，
	 * 避免每轮合成都去问一次磁盘。
	 *
	 * <p><b>null 的语义是"确实没有这一块"</b>；"这次读不动"（文件坏了、只读到半截）抛的是异常，
	 * 走 {@link #onReadFailure} 那条路、<b>不</b>记进"没有"的表——记了那一格就再也补不上，永远是黑的。
	 */
	ChunkRecord load(int chunkX, int chunkZ, int layer) {
		long key = ChunkColorCache.key(chunkX, chunkZ, layer);
		if (absent.contains(key)) {
			return null;
		}
		try {
			ChunkRecord record = readStore(layer, chunkX, chunkZ).read(chunkX, chunkZ);
			if (record == null) {
				rememberAbsent(key);
			}
			return record;
		} catch (IOException | RuntimeException e) {
			// 读失败只影响这一块：把它当成暂时没有数据，下一轮还会再试。
			// 绝不能因此把整套持久化永久关掉——那会让"探索过的地方"整片变成未探明的黑斑
			onReadFailure(e);
			return null;
		}
	}

	/**
	 * 是否已经能确定这个区块没有数据。
	 *
	 * <p>不走磁盘，只查内存里的未命中记录与区域文件存在位图，可以在渲染线程上随便问。地图据此把
	 * "没数据"的区块直接画成未探明，把建数据预算留给真正有数据的区块——否则一屏上万个没写过的
	 * 区块会把预算吃光。
	 */
	boolean isKnownAbsent(int chunkX, int chunkZ, int layer) {
		long key = ChunkColorCache.key(chunkX, chunkZ, layer);
		if (saved.contains(key)) {
			// 本局已经排过写盘：它迟早会有数据，绝不能判成"没有"——否则写盘还没落地时问过一次，
			// 这个区块在本局里就一直是未探明
			return false;
		}
		if (absent.contains(key)) {
			return true;
		}
		try {
			if (readStore(layer, chunkX, chunkZ).hasRecord(chunkX, chunkZ)) {
				return false;
			}
		} catch (IOException | RuntimeException e) {
			onReadFailure(e);
			return true;
		}
		// 判定结果也要记下来：一屏要问上万次，不记的话每次都要去 stat 一遍文件
		rememberAbsent(key);
		return true;
	}

	/** 记下"这个区块没有数据"，超过上限就整体清空（避免无限增长）。 */
	private void rememberAbsent(long key) {
		if (absent.size() >= ABSENT_LIMIT) {
			absent.clear();
		}
		absent.add(key);
	}

	/**
	 * 取该区块所属的区域文件。
	 *
	 * <p>按"层 + 区域坐标"打包成键查表，而不是每次 {@code resolve} 出一个 {@code Path}：后者每读
	 * 一个区块都要拼一次文件名（字符串拼接 + 对象分配），全屏一帧上万次，光 GC 就够喝一壶。
	 *
	 * <p><b>整张表加锁</b>：渲染线程与后台预读线程都会走这里（见 {@link #load}）。临界区只是几次
	 * 查表与放进内存的动作，而区域文件本身也是加锁的（见 {@code RegionStore#read}），所以两边的
	 * 锁顺序始终是"表 → 文件"，不会互相等成环。
	 */
	private RegionStore readStore(int layer, int chunkX, int chunkZ) {
		int regionX = Math.floorDiv(chunkX, RegionStore.REGION_CHUNKS);
		int regionZ = Math.floorDiv(chunkZ, RegionStore.REGION_CHUNKS);
		long key = regionKey(layer, regionX, regionZ);
		synchronized (readStores) {
			RegionStore store = readStores.get(key);
			if (store == null) {
				// 读取侧整份载入内存：区块读取变成数组拷贝，不再每个区块一次系统调用
				store = new RegionStore(RegionStore.pathFor(layerDirectory(layer), chunkX, chunkZ), true);
				readStores.put(key, store);
				evictReadStores();
				// 后台先把这一页读进内存：渲染线程随后问它时一次 IO 都不用等。
				// 真正并行的解压与解析在预读池里（见 ClientChunkColorProvider#enqueuePrefetch），
				// 这里只负责把页读好，所以一条线程足够、也不必再抢磁盘。
				RegionStore fresh = store;
				try {
					preloader.execute(() -> {
						try {
							fresh.preload();
						} catch (IOException | RuntimeException e) {
							// 预读失败不算错：真要读时还会再试一次，那时才按读失败处理
						}
					});
				} catch (RejectedExecutionException ignored) {
					// 存储已经在关了：这一页就等真正读它的时候再载
				}
			}
			// 写入方落了新数据就必须丢掉旧的内存副本，否则刚写下去的数据会被判成"没有"
			if (!dirtyRegions.isEmpty() && dirtyRegions.remove(key)) {
				store.invalidateCache();
			}
			return store;
		}
	}

	/** 超出上限就关掉最久没用的区域文件，把内存压住。 */
	private void evictReadStores() {
		while (readStores.size() > MAX_READ_REGIONS) {
			var eldest = readStores.entrySet().iterator();
			RegionStore victim = eldest.next().getValue();
			eldest.remove();
			victim.close();
		}
	}

	/** 写入侧的区域文件（只在写入线程上访问，因此不需要热缓存）。 */
	private RegionStore writeStore(int layer, int chunkX, int chunkZ) {
		int regionX = Math.floorDiv(chunkX, RegionStore.REGION_CHUNKS);
		int regionZ = Math.floorDiv(chunkZ, RegionStore.REGION_CHUNKS);
		return writeStores.computeIfAbsent(regionKey(layer, regionX, regionZ),
				ignored -> new RegionStore(RegionStore.pathFor(layerDirectory(layer), chunkX, chunkZ)));
	}

	/**
	 * 把层与区域坐标打包成一个键。
	 *
	 * <p>位分配：层 16 位（地表用 {@code 0xFFFF} 哨兵，洞穴层加偏移后落在 2048..6144）、
	 * 区域 Z 23 位、区域 X 23 位。与 {@link ChunkColorCache#key} 用的是同一套哨兵约定。
	 */
	private static long regionKey(int layer, int regionX, int regionZ) {
		long layerBits = LayerSelector.isCave(layer) ? (layer + 4096) & 0xFFFFL : 0xFFFFL;
		return layerBits << 46 | (regionX & 0x7FFFFFL) << 23 | (regionZ & 0x7FFFFFL);
	}

	/** 排队写入区块数据，实际写盘在后台线程。同一局内同一区块只写一次。 */
	void save(int chunkX, int chunkZ, int layer, ChunkRecord record) {
		if (writeFailed) {
			return;
		}
		long key = ChunkColorCache.key(chunkX, chunkZ, layer);
		// 刚写进去的数据不再是"没有"：这条记录若留着，将来颜色缓存被淘汰时就会读不回它，
		// 地图上那块会一直空着
		absent.remove(key);
		if (!saved.add(key)) {
			// 本局已经写过这一块了，不必重复排队
			return;
		}
		int regionX = Math.floorDiv(chunkX, RegionStore.REGION_CHUNKS);
		int regionZ = Math.floorDiv(chunkZ, RegionStore.REGION_CHUNKS);
		long regionKey = regionKey(layer, regionX, regionZ);
		// 排队时先标脏：写盘期间读线程可能已经把旧位图缓存下来了
		dirtyRegions.add(regionKey);
		writer.execute(() -> {
			try {
				writeStore(layer, chunkX, chunkZ).write(chunkX, chunkZ, record);
				// 落盘后再标一次，让"写盘期间读到的旧快照"也失效
				dirtyRegions.add(regionKey);
				absent.remove(key);
			} catch (IOException | RuntimeException e) {
				onWriteFailure(e);
			}
		});
	}

	/**
	 * 地形被改动过，重新排队写入。
	 *
	 * <p>必须先去掉"本局已经写过"的标记：否则 {@link #save} 会认为早已存过而跳过，改动永远落不了盘，
	 * 下次从磁盘读回来还是老样子。
	 */
	void resave(int chunkX, int chunkZ, int layer, ChunkRecord record) {
		saved.remove(ChunkColorCache.key(chunkX, chunkZ, layer));
		save(chunkX, chunkZ, layer, record);
	}

	/** 等待已排队的写入完成。 */
	void flush() {
		try {
			writer.submit(() -> {
			}).get(10, TimeUnit.SECONDS);
		} catch (Exception e) {
			LOGGER.warn("等待地图写入超时", e);
		}
	}

	@Override
	public void close() {
		// 先把排队的写入跑完（flush 等于等队列排空），再关文件。顺序不能反：shutdown 之后
		// writer.execute 会抛 RejectedExecutionException，而 close 从渲染线程调用，会把游戏崩掉。
		flush();
		// 队列已经空了、也不会再有新任务，此时写入侧的文件由当前线程关闭是安全的
		writeStores.values().forEach(RegionStore::close);
		writeStores.clear();
		writer.shutdown();
		// 预读线程可能正拿着某个区域文件在读；先关掉它（close 会等到那把锁放出来），再关文件
		preloader.shutdown();
		try {
			preloader.awaitTermination(2, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		synchronized (readStores) {
			readStores.values().forEach(RegionStore::close);
			readStores.clear();
		}
		absent.clear();
		saved.clear();
		dirtyRegions.clear();
	}

	/** 层的子目录名：{@code surface} 或 {@code cave_<层高>}，外面再套一层格式版本目录。 */
	private Path layerDirectory(int layer) {
		String name = LayerSelector.isCave(layer) ? CAVE_PREFIX + layer : "surface";
		return root.resolve("v" + RegionStore.formatVersion()).resolve(name);
	}

	/**
	 * 写盘失败：本局不再尝试落盘。
	 *
	 * <p>写失败通常不会自己好（磁盘满、目录不可写），继续排队只会让队列越积越长、磁盘一直转。
	 * 地图本身照常显示，只是这一局不再记录。
	 */
	private void onWriteFailure(Exception cause) {
		if (!writeFailed) {
			writeFailed = true;
			LOGGER.warn("保存地图数据失败，本局不再落盘（地图仍可正常显示）", cause);
		}
	}

	/**
	 * 读盘失败：只当作这一块暂时没有数据。
	 *
	 * <p><b>不能因此停掉整套持久化。</b>一次偶发读失败就让之后所有读取都返回"没有数据"，
	 * 地图上会出现一大片擦不掉的未探明黑斑。
	 */
	private void onReadFailure(Exception cause) {
		if (!readFailureLogged) {
			readFailureLogged = true;
			LOGGER.warn("读取地图数据失败，这一块先当作没有数据（之后还会重试）", cause);
		}
	}
}
