package sinanmap.common.storage;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * 一个区域文件，存放 {@value #REGION_CHUNKS}×{@value #REGION_CHUNKS} 个区块的原始数据。
 *
 * <p><b>布局：头部 + 索引表 + 压缩数据区。</b>头部是魔数与版本；索引表每个区块一项
 * （{@value #ENTRY_BYTES} 字节：数据在文件里的偏移 + 压缩后的字节数，长度为 0 表示没有这一块）；
 * 后面是各区块<b>各自压缩</b>过的记录。
 *
 * <p><b>为什么要压缩。</b>原来的布局是"每区块固定 {@code ChunkRecord.BYTES} 字节"，一整条记录原样落盘。
 * 实测这些逐列数据高度冗余（相邻列的本色、高度、光照大量相同），deflate 之后地表块中位数只有
 * 一两百字节、洞穴块常常不到一百字节——一份实际存档里区域文件从约 2 MB 降到几十到几百 KB。
 *
 * <p><b>为什么是"逐块独立压缩 + 索引"而不是"整个文件压成一个流"。</b>整文件压缩能再小一点，但每改一个
 * 区块就要重写并重压整个区域（几万块的数据），而且读一块得先解整份——放在渲染线程上是卡顿来源。
 * 逐块独立压缩保住了"改哪块只写哪块"：新数据比原来长就追加到文件末尾、把索引指过去，比原来短就原地覆盖。
 *
 * <p>文件只在首次写入时创建：只读的场景（看以前探索过的地图）不会凭空生成一堆空文件。
 */
public final class RegionStore implements Closeable {

	/** 每个区域文件覆盖的区块边长。 */
	public static final int REGION_CHUNKS = 32;

	/** 索引表里一项的字节数：4 字节偏移 + 4 字节长度。 */
	private static final int ENTRY_BYTES = 8;

	/** 索引表覆盖的区块数。 */
	private static final int SLOTS = REGION_CHUNKS * REGION_CHUNKS;

	/** 未压缩的一条记录有多少字节（估算内存与压缩前尺寸时用）。 */
	public static final int SLOT_SIZE = ChunkRecord.BYTES;

	private static final int MAGIC = 0x534E414E;

	/**
	 * 数据格式版本。
	 *
	 * <p>改过颜色/采样的算法后必须递增：区块数据里存的是算好的方块本色，公式一变旧数据就过时，
	 * 读出来是老配色；版本不匹配时直接当作没有数据。
	 *
	 * <p>2–29 的历次换代总体是：本色从 MapColor 起步→改贴图平均色→水按深度叠水色并反复调盖度、
	 * 发光方块取较亮像素、地表改取"固体地面"那一格；存储路径开始带 {@code v<版本>/} 目录；洞穴数据
	 * 改为按 16 格量化、后来按<b>显示层档案</b>落盘；逐版给每列加"平铺流体层""自发光""没有数据"标记。
	 *
	 * <p><b>什么时候该递增：</b>只有"存档里存的原始数据本身变了"才需要——换了取样哪个方块、
	 * 改了水色怎么混、改了洞穴切哪一层。仅调整 {@code toColors} 里的明暗/配色公式<b>不需要</b>递增，
	 * 因为那些是读取时才计算的。递增一次的代价是：以前探索过、但没再走到的区域会重新变成未探明。
	 *
	 * <p><b>32 → 33</b>：落盘从"每区块固定 2048 字节"改成"逐块 deflate 压缩 + 索引表"。字节布局整个换了，
	 * 旧文件必须当作没有数据。
	 */
	private static final int VERSION = 33;

	/** 索引表在文件里的起点（前面是魔数与版本）。 */
	private static final int TABLE_OFFSET = 16;

	/** 头部总长：魔数 + 版本 + 整张索引表。 */
	private static final int HEADER_SIZE = TABLE_OFFSET + SLOTS * ENTRY_BYTES;

	/** 进内存的区域文件大小上限，防止读到异常大的文件把内存撑爆。 */
	private static final long MAX_MEMORY_BYTES = 8L * 1024 * 1024;

	/**
	 * 一条记录压完之后的最大可能长度。
	 *
	 * <p>deflate 在最坏情况下会比输入略长（存不下的块退化成原样存储），但不会超过输入
	 * {@code + 一小段块头}。读侧按这个上限留缓冲，超过就说明文件坏了。
	 */
	private static final int MAX_PAYLOAD = ChunkRecord.BYTES + 64;

	private final Path path;
	private RandomAccessFile file;

	/**
	 * 头部缓存（魔数、版本、索引表）。
	 *
	 * <p>以前每读一个区块都要 stat 一次文件、再 seek 回开头重读头部。全屏地图一帧要问上万个区块，
	 * 光这两下就是上万次系统调用，实测首次合成要 400 毫秒以上。索引表只有 {@value #HEADER_SIZE} 字节，
	 * 常驻内存的代价可忽略，因此头部只读一次。
	 */
	private byte[] header;

	/** 头部是否已经尝试读过（含"文件不存在"这种情况，此时 {@link #header} 为 null）。 */
	private boolean headerRead;

	/** 这一次读里，内存快照是否被判定为"比文件旧"（见 {@link #readFromSnapshot}）。 */
	private boolean snapshotStale;

	/**
	 * 整个区域文件的内容，仅 {@link #memoryMode} 下使用。
	 *
	 * <p>否则每读一个区块就是一次 seek + read 系统调用；地图平移时会把新进入视野的区块成批读出来，
	 * 几千次系统调用堆在一起会有明显卡顿，同一片区域来回看还会反复读同一份数据。载入内存后区块读取
	 * 变成一次数组切片 + 一次解压。
	 */
	private byte[] content;

	/**
	 * 是否把整个区域文件载入内存。
	 *
	 * <p>只给"读取侧"用：写入侧要的是立刻落盘，不需要也不会用到这份副本。
	 */
	private final boolean memoryMode;

	/** 一条未压缩的记录（编码与解码共用）。读写分属不同实例，实例内又是顺序使用，因此可以共用。 */
	private final byte[] raw = new byte[ChunkRecord.BYTES];

	/** 压缩后的字节（写侧留缓冲，不够长再长）。 */
	private byte[] compressed = new byte[512];

	/** 读侧读一条压缩数据的缓冲。 */
	private final byte[] payload = new byte[MAX_PAYLOAD];

	// 压缩器/解压器按需创建：只有真正读写过的实例才占这份原生内存
	private Deflater deflater;
	private Inflater inflater;

	public RegionStore(Path path) {
		this(path, false);
	}

	/**
	 * @param memoryMode 是否把整个区域文件载入内存（读取侧建议开启）
	 */
	public RegionStore(Path path, boolean memoryMode) {
		this.path = path;
		this.memoryMode = memoryMode;
	}

	/** 区域文件路径：{@code r.<区域X>.<区域Z>.sinanmap}。 */
	public static Path pathFor(Path directory, int chunkX, int chunkZ) {
		int regionX = Math.floorDiv(chunkX, REGION_CHUNKS);
		int regionZ = Math.floorDiv(chunkZ, REGION_CHUNKS);
		return directory.resolve("r." + regionX + "." + regionZ + ".sinanmap");
	}

	/**
	 * 当前数据格式版本。
	 *
	 * <p>存储路径里会带上它（{@code v<版本>/...}）：换了配方就换一个目录，旧数据<b>天然读不到</b>，
	 * 不必依赖"读到之后再校验版本"那条路。以前没有分版本目录时，一旦某处漏检，改了配方的地方还会
	 * 照旧显示成老样子，很难查。
	 */
	public static int formatVersion() {
		return VERSION;
	}

	/** 索引表能覆盖多少个区块（供测试确认"每个区块都有自己的槽位"）。 */
	static int slotCount() {
		return SLOTS;
	}

	/** 区域内的列序号（先 Z 后 X）。 */
	private static int indexOf(int chunkX, int chunkZ) {
		int localX = Math.floorMod(chunkX, REGION_CHUNKS);
		int localZ = Math.floorMod(chunkZ, REGION_CHUNKS);
		return localZ * REGION_CHUNKS + localX;
	}

	/** 第 {@code index} 个槽位在索引表里的字节位置。 */
	private static int tableAt(int index) {
		return TABLE_OFFSET + index * ENTRY_BYTES;
	}

	/**
	 * 读取区块数据。
	 *
	 * <p><b>没有这一块时返回 null</b>（这是"确实没有"，调用方可以据此记住"这一块没数据"）；
	 * <b>文件坏了、或者只读到半截时抛 {@link IOException}</b>——那是"这次读不动"，绝不能当成
	 * "没有数据"，否则一次偶发失败就会在地图上留下一格永远补不上的黑。
	 *
	 * <p>整个读路径加锁（{@code synchronized}）：写侧是另一个实例、读侧可能被后台预读线程和渲染
	 * 线程同时问（见 {@code MapStorage#load}）。
	 */
	public synchronized ChunkRecord read(int chunkX, int chunkZ) throws IOException {
		int index = indexOf(chunkX, chunkZ);
		if (loadContent()) {
			snapshotStale = false;
			ChunkRecord fromSnapshot = readFromSnapshot(index);
			if (!snapshotStale) {
				return fromSnapshot;
			}
			// 快照比文件旧（写入方刚追加过一块）：上面已把 content 作废，改从文件里读最新那份。
			// 这一步是关键：不重读的话这里会返回 null，调用方会把它记成"这一块没有数据"，
			// 那一格就永远是黑的（表现为地图上零星几格不刷新）。
		}
		return readFromFile(index);
	}

	/** 从内存快照里解一块；快照覆盖不到时作废快照并置 {@link #snapshotStale}，交给调用方改走文件。 */
	private ChunkRecord readFromSnapshot(int index) throws IOException {
		int at = tableAt(index);
		int offset = readInt(content, at);
		int length = readInt(content, at + 4);
		if (length <= 0) {
			// 索引明确说"没有这一块"，这是可信的结论
			return null;
		}
		if (offset < HEADER_SIZE || (long) offset + length > content.length) {
			content = null;
			snapshotStale = true;
			return null;
		}
		return inflate(content, offset, length);
	}

	/** 从文件里读一块（快照不可用、或没有内存副本时走这条）。 */
	private ChunkRecord readFromFile(int index) throws IOException {
		if (!hasRecord(index)) {
			return null;
		}
		int at = tableAt(index);
		int offset = readInt(header, at);
		int length = readInt(header, at + 4);
		if (length <= 0 || length > payload.length || offset < HEADER_SIZE) {
			throw new IOException("地图区域文件的索引表坏了: " + path);
		}
		RandomAccessFile access = open(true);
		access.seek(offset);
		access.readFully(payload, 0, length);
		return inflate(payload, 0, length);
	}

	/**
	 * 该槽位是否有记录。
	 *
	 * <p>只看内存里的索引表，<b>不读数据</b>，因此一次磁盘访问都不需要。地图一帧要问上万个
	 * 区块，其中绝大多数从没写过；让"没写过"的判定走这条路，预算就只需花在真正有数据、真要读文件的
	 * 少数区块上。
	 */
	public synchronized boolean hasRecord(int chunkX, int chunkZ) throws IOException {
		return hasRecord(indexOf(chunkX, chunkZ));
	}

	private boolean hasRecord(int index) throws IOException {
		if (content != null) {
			return readInt(content, tableAt(index) + 4) > 0;
		}
		return loadHeader() && readInt(header, tableAt(index) + 4) > 0;
	}

	/**
	 * 把整份区域文件读进内存（只在内存模式下有效）。
	 *
	 * <p>给"后台先把页读好"用：渲染线程第一次问这一页时就不必再等一次磁盘读了；它调
	 * {@link #hasRecord} / {@link #read} 时走的是内存副本，一次 IO 都没有。
	 */
	public synchronized void preload() throws IOException {
		loadContent();
	}

	/**
	 * 把整个区域文件读进内存，只做一次。
	 *
	 * @return 是否已经可以走内存这条路
	 */
	private synchronized boolean loadContent() throws IOException {
		if (content != null) {
			return true;
		}
		if (!memoryMode) {
			return false;
		}
		if (file == null && !Files.isRegularFile(path)) {
			return false;
		}
		RandomAccessFile access = open(true);
		long length = access.length();
		if (length < HEADER_SIZE || length > MAX_MEMORY_BYTES) {
			// 空文件或异常大的文件就不进内存，退回逐区块读
			return false;
		}
		byte[] buffer = new byte[(int) length];
		access.seek(0);
		access.readFully(buffer);
		if (readInt(buffer, 0) != MAGIC || readInt(buffer, 4) != VERSION) {
			// 版本不符：旧数据必须当作不存在，否则会显示改配方前的老颜色
			return false;
		}
		content = buffer;
		return true;
	}

	/**
	 * 读入并校验头部，只做一次。
	 *
	 * <p><b>只在读成功时才记下"读过了"</b>：区域文件是写入线程按需新建的，若把"文件还不存在"
	 * 也缓存下来，同一局里紧接着写进去的数据就会被本实例一直当成没有。
	 *
	 * <p>反过来，头部一旦缓存就<b>不会自动感知后续的写入</b>——索引表是写之前读的快照。写入方必须在
	 * 落盘后调用 {@link #invalidateCache()}，否则曾经走过、已经写进磁盘的区域会被判成"没有数据"，
	 * 地图上就是擦不掉的黑块。
	 *
	 * @return 头部是否可用（魔数与版本都正确）
	 */
	private synchronized boolean loadHeader() throws IOException {
		if (headerRead) {
			return header != null;
		}
		if (file == null && !Files.isRegularFile(path)) {
			return false;
		}
		RandomAccessFile access = open(true);
		if (access.length() < HEADER_SIZE) {
			return false;
		}
		byte[] buffer = new byte[HEADER_SIZE];
		access.seek(0);
		access.readFully(buffer);
		if (readInt(buffer, 0) != MAGIC || readInt(buffer, 4) != VERSION) {
			// 版本不符：旧数据必须当作不存在，否则会显示改配方前的老颜色
			return false;
		}
		header = buffer;
		headerRead = true;
		return true;
	}

	/**
	 * 丢弃所有缓存的文件状态，下次读取时重新从磁盘加载。
	 *
	 * <p>有新的区块写进这个区域文件后必须调用，否则索引表与内存副本都还停留在写入前的快照。
	 */
	public synchronized void invalidateCache() {
		header = null;
		headerRead = false;
		content = null;
	}

	/**
	 * 写入区块数据（逐块压缩）。
	 *
	 * <p>新数据比这一块原来那份<b>短</b>就原地覆盖（省一次文件增长），否则追加到文件末尾再把索引指过去
	 * ——旧的那一段留在文件里成了空洞，但一条记录压完只有一两百字节，这点浪费远比"整份重写、重排"
	 * 划算，也不必维护空闲链。
	 */
	public synchronized void write(int chunkX, int chunkZ, ChunkRecord record) throws IOException {
		int index = indexOf(chunkX, chunkZ);
		RandomAccessFile access = open(false);

		if (header == null) {
			// 先看一眼磁盘：文件已存在且版本正确时不能重建，否则会把已有数据截断
			loadHeader();
		}
		if (header == null) {
			// 新建，或旧版本文件：清空重来，避免把过时的数据当成有效记录
			byte[] fresh = new byte[HEADER_SIZE];
			writeInt(fresh, 0, MAGIC);
			writeInt(fresh, 4, VERSION);
			access.setLength(0);
			access.seek(0);
			access.write(fresh);
			header = fresh;
			headerRead = true;
		}

		record.encode(raw, 0);
		int length = deflate(raw);

		int at = tableAt(index);
		int oldLength = readInt(header, at + 4);
		long offset = oldLength >= length ? readInt(header, at) : access.length();
		access.seek(offset);
		access.write(compressed, 0, length);

		// 索引表要同时改内存副本与文件：只改文件的话，本实例后续的 read 会被缓存的索引否掉
		writeInt(header, at, (int) offset);
		writeInt(header, at + 4, length);
		access.seek(at);
		access.write(header, at, ENTRY_BYTES);
		// 这里不做 fsync：原来每写一个区块就 force 一次，一屏几百上千个区块就是几百上千次同步，
		// 写盘线程会长时间占着磁盘。数据写进的是操作系统页缓存，同一文件的其他句柄立刻就能读到
		// （读侧就是这么读的），因此不 force 也不影响正确性；掉电最多丢掉最近几秒的地图数据。
	}

	/**
	 * 把 {@link #raw} 压进 {@link #compressed}，返回压缩后的字节数。
	 *
	 * <p>用默认档：实测真实存档里地表块中位 {@code 330 B/块}（原 2048，压到 16.1%），最快速档要多花两成
	 * 空间（{@code 395 B}）才换到几倍速度，而写盘线程每秒只处理几百块、<b>从来不是瓶颈</b>。
	 */
	private int deflate(byte[] source) {
		if (deflater == null) {
			deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
		}
		deflater.reset();
		deflater.setInput(source, 0, source.length);
		deflater.finish();
		int total = 0;
		while (!deflater.finished()) {
			if (total == compressed.length) {
				compressed = Arrays.copyOf(compressed, compressed.length * 2);
			}
			int written = deflater.deflate(compressed, total, compressed.length - total);
			if (written == 0) {
				// 既没写完、也推不动了：不可能是正常数据，别在这里死循环
				break;
			}
			total += written;
		}
		return total;
	}

	/**
	 * 解一条压缩记录。
	 *
	 * <p><b>解不出完整的一条就抛异常，而不是返回 null。</b>null 的语义是"这一块确实没有数据"，
	 * 调用方会据此把它记进"没有"的表、那一格就再也不会重问；而"解不开/只读到半截"是这次读不动，
	 * 必须让调用方按读失败处理、下一轮再试。
	 */
	private ChunkRecord inflate(byte[] source, int offset, int length) throws IOException {
		if (inflater == null) {
			inflater = new Inflater();
		}
		inflater.reset();
		inflater.setInput(source, offset, length);
		int total = 0;
		try {
			while (total < raw.length) {
				int written = inflater.inflate(raw, total, raw.length - total);
				if (written == 0) {
					break;
				}
				total += written;
			}
		} catch (DataFormatException e) {
			throw new IOException("地图记录解压失败: " + path, e);
		}
		if (total != raw.length) {
			throw new IOException("地图记录解压不完整（得到 " + total + " / " + raw.length
					+ " 字节）: " + path);
		}
		return ChunkRecord.decode(raw, 0);
	}

	private static void writeInt(byte[] target, int offset, int value) {
		target[offset] = (byte) (value >>> 24);
		target[offset + 1] = (byte) (value >>> 16);
		target[offset + 2] = (byte) (value >>> 8);
		target[offset + 3] = (byte) value;
	}

	private static int readInt(byte[] source, int offset) {
		return ((source[offset] & 0xFF) << 24) | ((source[offset + 1] & 0xFF) << 16)
				| ((source[offset + 2] & 0xFF) << 8) | (source[offset + 3] & 0xFF);
	}

	private synchronized RandomAccessFile open(boolean readOnly) throws IOException {
		if (file != null) {
			return file;
		}
		if (readOnly && !Files.isRegularFile(path)) {
			throw new IOException("区域文件不存在: " + path);
		}
		Path parent = path.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		file = new RandomAccessFile(path.toFile(), readOnly ? "r" : "rw");
		return file;
	}

	@Override
	public synchronized void close() {
		if (file != null) {
			try {
				file.close();
			} catch (IOException ignored) {
				// 关闭失败没有补救手段，忽略
			}
			file = null;
		}
		// 原生压缩流的缓冲要显式还回去
		if (deflater != null) {
			deflater.end();
			deflater = null;
		}
		if (inflater != null) {
			inflater.end();
			inflater = null;
		}
	}
}
