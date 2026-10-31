package sinanmap.common.storage;

import java.util.Arrays;

import sinanmap.common.map.SurfaceShading;

/**
 * 一个区块的原始逐列数据，是持久化的最小单位。
 *
 * <p>存的是<b>未着色</b>的方块本色与光照等级，而不是最终颜色：最终颜色把采集当时的光照
 * （昼夜、天气）烙了进去，晚上打开地图会依旧是白天那么亮。这里存原始的方块本色与
 * 天空光/方块光，读取时再按当前光照合成，因此同一份数据在任何时刻都正确。
 *
 * <p>每列 8 字节：本色 3 字节、高度 2 字节、天空光 1 字节、方块光 1 字节、标志 1 字节，
 * 整块 {@value #BYTES} 字节，定长便于定点读写。内存里这五项也拼在同一个 8 字节字上
 * （见 {@link #columns}）。
 */
public final class ChunkRecord {

	/** 每区块的列数（16×16）。 */
	public static final int COLUMNS = 256;

	/** 方块边长。 */
	public static final int CHUNK_SIZE = 16;

	/** 序列化后的字节数。 */
	public static final int BYTES = COLUMNS * 8;

	/**
	 * 标志位：这一列是<b>平铺在地形之上的流体层</b>（水）。
	 *
	 * <p>流体表面是平的，底下的地形是起伏的；拿底下地形的邻居高差算坡面明暗，等于把海底的起伏画到
	 * 水面上，水就被画成一块起伏的布。这一位让着色时能单独对待这种列（只去掉坡面项，高度项照常，
	 * 见 {@link sinanmap.common.map.SurfaceShading#totalFactor(int, int, int, int, int, boolean)}）。
	 *
	 * <p><b>冰不置这一位</b>：冰是"跟着地形走的固体薄壳"，明暗该和周围的雪、浮冰连成一片；
	 * 冰的半透明只体现在它存的本色里（采样时已和底下的颜色混过）。
	 */
	public static final int FLAG_FLAT_SHEET = 1;

	/**
	 * 标志位：这一列的地表方块<b>自己发光</b>（岩浆、萤石、海晶灯、营火…）。
	 *
	 * <p>这类方块在地图上直接显示本色、不叠任何明暗（见 {@link #toColors}）。
	 *
	 * <p>必须单独标出来：三项明暗都会把岩浆压暗，而它们都不是"错"，只是不适用于光源——高度项会压暗
	 * 低处的岩浆湖、坡面项会压暗朝东南的那一半、光照平滑会把一格宽的岩浆流与周围黑石平均下去。
	 * 压过之后夜里看就像一摊暗红，而玩家认得的是游戏里那种亮橙色。
	 */
	public static final int FLAG_EMISSIVE = 2;

	/**
	 * 标志位：这一列<b>没有数据</b>（整列都是空气，或者区块高度图还没建起来）。
	 *
	 * <p>不能靠"本色是 0"来判断：着色函数 {@code SurfaceShading#scale} 返回的<b>永远是不透明色</b>，
	 * 本色 0 出来是纯黑；世界地图底色是 {@code #05050A}，一列"没有数据"会变成比底色更黑的方块——
	 * 这正是"加载区域的边界一圈暗块"。有了这一位，{@link #toColors} 会把这列输出成<b>全透明</b>，
	 * 与"根本没有这一页"看起来一致。
	 */
	public static final int FLAG_NO_DATA = 4;

	/**
	 * 昼夜衰减的放大倍率：&gt;1 表示比原版衰减得<b>更</b>暗。
	 *
	 * <p>夜间地表亮度定在白天的大约三成：太亮整张图像蒙了层灰、光源也跳不出来；太暗又读不出地形。
	 * 取 1.4 让午夜（原版衰减约 11）直接把天空光压到底，地表亮度只剩
	 * {@link sinanmap.common.map.SurfaceShading#MIN_LIGHT_FACTOR} 那一档，而有方块光的地方仍接近满亮。
	 */
	public static final double NIGHT_DARKENING = 1.4;

	// ---- 每列打包进一个 long 的位布局（低位在右） ----
	//   [23..0]  本色（0xRRGGBB，24 位）
	//   [39..24] 高度（有符号 short 的位模式，16 位）
	//   [43..40] 天空光（0..15，4 位）
	//   [47..44] 方块光（0..15，4 位）
	//   [55..48] 标志位（8 位，目前只用到低 3 位）
	//   [63..56] 保留，恒为 0
	private static final long RGB_MASK = 0xFFFFFFL;
	private static final int HEIGHT_SHIFT = 24;
	private static final long HEIGHT_MASK = 0xFFFFL;
	private static final int SKY_SHIFT = 40;
	private static final int BLOCK_SHIFT = 44;
	private static final long NIBBLE_MASK = 0xFL;
	private static final int FLAGS_SHIFT = 48;
	private static final long FLAGS_MASK = 0xFFL;

	/**
	 * 逐列数据：每列一个 long，把本色、高度、天空光、方块光与标志位按上面的位布局拼在一起。
	 *
	 * <p><b>为什么打包。</b>这五项以前是五个独立数组，一条记录占 2304 字节，着色扫描时还要来回跳五个
	 * 数组。打包后每列落在同一个 8 字节字里（整块 2048 字节，比原来更省），数组对象从五个缩到一个——
	 * 缓存里动辄上万条记录，省下的是实打实的常驻内存与分配次数。
	 *
	 * <p><b>落盘格式没变。</b>{@link #encode} / {@link #decode} 仍按老顺序、每列 8 字节写出/读入完全
	 * 相同的字节，因此 {@code RegionStore.VERSION} 不必递增，旧存档天然可读、不会触发重新采样。
	 */
	private final long[] columns = new long[COLUMNS];

	/**
	 * 着色时逐列的明暗系数暂存，避免每次合成都新建数组。
	 *
	 * <p><b>刻意做成 static。</b>它们是纯中间量（{@link #sameContent} 明确不比它们），每个实例各留一份
	 * 就是给每条缓存记录白搭 4 KB——而缓存里动辄上万条记录，那份浪费比数据本身还大。做小记录直接等于
	 * 同内存下能多留几倍的已探索区域。
	 *
	 * <p>代价是 {@link #toColors} 不可重入、也不能跨线程并发调用。地图这条流水线全部跑在客户端主线程上
	 * （见 {@code ChunkColorProvider} 的说明），因此这一点是有保证的。
	 */
	private static final double[] factors = new double[COLUMNS];

	/**
	 * 自发光方块（岩浆）用的"地形起伏"系数暂存（只含高度×坡度、不含光照），见
	 * {@link SurfaceShading#reliefFactor}。与 {@link #factors} 同性质：static、不可重入。
	 */
	private static final double[] reliefs = new double[COLUMNS];

	/** 逐列的实际光照等级，以及平滑后的结果。两者都不是最终颜色，只是着色过程的中间量，见 {@link #factors}。 */
	private static final int[] lightRaw = new int[COLUMNS];
	private static final int[] lightSmooth = new int[COLUMNS];

	/** 写入一列。索引按 {@code z * 16 + x} 排列，与渲染缓冲一致。 */
	public void setColumn(int index, int columnRgb, int columnHeight, int columnSkyLight, int columnBlockLight) {
		// 写进了真正的一列数据，"没有数据"这一位就要清掉（见 FLAG_NO_DATA），其余标志位原样保留
		pack(index, columnRgb, columnHeight,
				Math.clamp(columnSkyLight, 0, 15), Math.clamp(columnBlockLight, 0, 15),
				flags(index) & ~FLAG_NO_DATA);
	}

	/** 把一列的各字段按位布局拼进 {@link #columns}；传进来的值都会被各自的掩码裁到额定宽度。 */
	private void pack(int index, int rgb, int height, int skyLight, int blockLight, int flags) {
		columns[index] = (rgb & RGB_MASK)
				| (((long) (height & HEIGHT_MASK)) << HEIGHT_SHIFT)
				| (((long) (skyLight & NIBBLE_MASK)) << SKY_SHIFT)
				| (((long) (blockLight & NIBBLE_MASK)) << BLOCK_SHIFT)
				| (((long) (flags & FLAGS_MASK)) << FLAGS_SHIFT);
	}

	/** 只改标志位区里的某一位，不动别的位。 */
	private void setFlag(int index, int flag, boolean set) {
		long bits = ((long) flag) << FLAGS_SHIFT;
		columns[index] = set ? (columns[index] | bits) : (columns[index] & ~bits);
	}

	/** 读一列的全部标志位。 */
	private int flags(int index) {
		return (int) ((columns[index] >>> FLAGS_SHIFT) & FLAGS_MASK);
	}

	/** 标记这一列是不是"平铺在地形之上的流体层"（水），见 {@link #FLAG_FLAT_SHEET}。 */
	public void setFlatSheet(int index, boolean flatSheet) {
		setFlag(index, FLAG_FLAT_SHEET, flatSheet);
	}

	public boolean isFlatSheet(int index) {
		return (flags(index) & FLAG_FLAT_SHEET) != 0;
	}

	/** 标记这一列的地表方块是不是自发光，见 {@link #FLAG_EMISSIVE}。 */
	public void setEmissive(int index, boolean emissive) {
		setFlag(index, FLAG_EMISSIVE, emissive);
	}

	public boolean isEmissive(int index) {
		return (flags(index) & FLAG_EMISSIVE) != 0;
	}

	/** 标记这一列没有数据，见 {@link #FLAG_NO_DATA}。 */
	public void setNoData(int index) {
		// 置位的同时把这一列的数据清空；其余标志位（水、自发光）保持原样，与改动前一致
		pack(index, 0, 0, 0, 0, flags(index) | FLAG_NO_DATA);
	}

	/** 这一列是不是没有数据，见 {@link #FLAG_NO_DATA}。 */
	public boolean isNoData(int index) {
		return (flags(index) & FLAG_NO_DATA) != 0;
	}

	public int rgb(int index) {
		return (int) (columns[index] & RGB_MASK);
	}

	public int height(int index) {
		// 高度以有符号 short 存储：先取 16 位位模式，再按 short 解释符号（世界底 -64 也能往返）
		return (short) ((columns[index] >>> HEIGHT_SHIFT) & HEIGHT_MASK);
	}

	public int skyLight(int index) {
		return (int) ((columns[index] >>> SKY_SHIFT) & NIBBLE_MASK);
	}

	public int blockLight(int index) {
		return (int) ((columns[index] >>> BLOCK_SHIFT) & NIBBLE_MASK);
	}

	/**
	 * 按当前光照合成为可直接上传的 ARGB 颜色。
	 *
	 * <p>亮度取 {@code max(方块光, 天空光 - 昼夜衰减)}，与原版 {@code getRawBrightness} 一致。
	 *
	 * <p><b>区块边界要特殊处理</b>：最外一圈列缺少一个方向的邻居，只能按"那边没有落差"来算，坡面明暗
	 * 与紧邻的内部列差一档，一个区块四条边连起来就是铺满地图的网格状明暗缝。这里先算出所有列的系数，
	 * 再用相邻内部列的值覆盖最外一圈。
	 *
	 * @param referenceY 高度阴影的参考高度
	 * @param skyDarken  当前昼夜/天气造成的天空光衰减
	 */
	public int[] toColors(int referenceY, int skyDarken) {
		return toColors(referenceY, skyDarken, new int[COLUMNS]);
	}

	/**
	 * 同 {@link #toColors(int, int)}，但把结果写进调用方给的数组。
	 *
	 * <p>洞穴切片每次换层都要重新着色，若每次都新建一个 256 长的数组，一帧就能造出几百个垃圾。
	 */
	public int[] toColors(int referenceY, int skyDarken, int[] out) {
		int dimmed = (int) Math.round(skyDarken * NIGHT_DARKENING);
		int size = CHUNK_SIZE;
		for (int i = 0; i < COLUMNS; i++) {
			long column = columns[i];
			int sky = (int) ((column >>> SKY_SHIFT) & NIBBLE_MASK);
			int block = (int) ((column >>> BLOCK_SHIFT) & NIBBLE_MASK);
			// 方块光与"天空光减去昼夜衰减"取大，与原版 getRawBrightness 一致
			lightRaw[i] = Math.max(0, Math.max(block, sky - dimmed));
		}
		// 光照是 0~15 的整数，不经平滑的话每一级的边界都是一条看得见的硬边
		SurfaceShading.smoothLight(lightRaw, lightSmooth, size);

		for (int z = 0; z < size; z++) {
			for (int x = 0; x < size; x++) {
				int index = z * size + x;
				int columnHeight = height(index);
				int west = x == 0 ? columnHeight : height(index - 1);
				int north = z == 0 ? columnHeight : height(index - size);
				factors[index] = SurfaceShading.totalFactor(columnHeight, referenceY,
						lightSmooth[index], west, north, isFlatSheet(index));
				// 自发光方块只取"地形起伏"（不含光照），让它也有高低层次，见 SurfaceShading#reliefFactor
				reliefs[index] = SurfaceShading.reliefFactor(columnHeight, referenceY, west, north);
			}
		}
		// 最外一圈沿用相邻内部列的明暗。先补 x==0 那一列，再补 z==0 那一行，
		// 这样两次读取到的都是已算好的值，不会互相引入新的落差。
		for (int z = 1; z < size; z++) {
			factors[z * size] = factors[z * size + 1];
			reliefs[z * size] = reliefs[z * size + 1];
		}
		for (int x = 0; x < size; x++) {
			factors[x] = factors[x + size];
			reliefs[x] = reliefs[x + size];
		}

		for (int i = 0; i < COLUMNS; i++) {
			// 没有数据的一列输出全透明：它要看起来和"这里根本没有一页"一样。
			// 之前是走下面的普通着色、拿本色 0 去乘——那出来是纯黑，在世界地图上就是一圈暗块
			if (isNoData(i)) {
				out[i] = 0;
				continue;
			}
			// 自发光方块（岩浆这类）：先抬亮本色（见 brighten），再乘一个"只含地形起伏、不含光照"的
			// 系数（见 SurfaceShading#reliefFactor）——光照项会把它压暗故不用，但高度/坡度必须留，
			// 否则整片岩浆是平涂、看不出熔岩湖的高低与边缘。
			out[i] = isEmissive(i)
					? SurfaceShading.shade(brighten(rgb(i)), reliefs[i])
					: SurfaceShading.shade(rgb(i), factors[i]);
		}
		return out;
	}

	/**
	 * 自发光方块的颜色下限（三通道之和）。满值是 765。
	 *
	 * <p>取"总亮度不足就整体乘一个系数拉上来"而不是"统一刷成某个亮色"：这样萤石、海晶灯、岩浆各自
	 * 仍保留自己的色相。岩浆纹理平均色只有 {@code 212+91+18 ≈ 320}，直接铺到地图上就是一片暗橙，
	 * 和它"在发光"完全对不上。数值是自行选定的。
	 */
	private static final int GLOW_MIN_TOTAL = 480;

	/** 把颜色整体拉亮到总亮度不低于 {@link #GLOW_MIN_TOTAL}；本来就够亮就原样返回。 */
	private static int brighten(int rgb) {
		int red = (rgb >> 16) & 0xFF;
		int green = (rgb >> 8) & 0xFF;
		int blue = rgb & 0xFF;
		int total = red + green + blue;
		if (total >= GLOW_MIN_TOTAL || total <= 0) {
			return rgb;
		}
		double factor = GLOW_MIN_TOTAL / (double) total;
		return Math.clamp((int) (red * factor), 0, 255) << 16
				| Math.clamp((int) (green * factor), 0, 255) << 8
				| Math.clamp((int) (blue * factor), 0, 255);
	}

	/**
	 * 这一份的<b>原始数据</b>与另一份是否逐列一致（不含着色用的临时量）。
	 *
	 * <p>改动检测必须比原始数据而不是比最终颜色：颜色里含昼夜衰减，白天存下的颜色和晚上算出来的
	 * 必然不同，拿颜色去比会把"天黑了"整片报成"地形被改过"。
	 */
	public boolean sameContent(ChunkRecord other) {
		// 所有逐列原始数据都打包在同一个 long 里，逐元素比较即等价于逐字段比较。
		// 第 56..63 位恒为 0（pack 从不写它们），因此不会出现"内容相同、位模式不同"的误判。
		return Arrays.equals(columns, other.columns);
	}

	/**
	 * 序列化到指定缓冲的指定偏移处：每列仍按原来的顺序写出 <b>8 个字节</b>
	 * （本色 R/G/B、高度高/低、天空光、方块光、标志），与打包前的格式逐字节一致。因此换用打包内存表示
	 * <b>不会</b>动到存档格式，{@code RegionStore.VERSION} 也不必递增。
	 */
	public void encode(byte[] out, int offset) {
		for (int i = 0; i < COLUMNS; i++) {
			long column = columns[i];
			int at = offset + i * 8;
			out[at] = (byte) (column >> 16);
			out[at + 1] = (byte) (column >> 8);
			out[at + 2] = (byte) column;
			out[at + 3] = (byte) (column >> 32);
			out[at + 4] = (byte) (column >> 24);
			// 光照各占一个半字节，要掩掉相邻字段的位
			out[at + 5] = (byte) ((column >>> SKY_SHIFT) & NIBBLE_MASK);
			out[at + 6] = (byte) ((column >>> BLOCK_SHIFT) & NIBBLE_MASK);
			out[at + 7] = (byte) ((column >>> FLAGS_SHIFT) & FLAGS_MASK);
		}
	}

	/** 从缓冲反序列化，按 {@link #encode} 的字节顺序还原。 */
	public static ChunkRecord decode(byte[] in, int offset) {
		ChunkRecord record = new ChunkRecord();
		for (int i = 0; i < COLUMNS; i++) {
			int at = offset + i * 8;
			// 天空光/方块光各占 4 位，读入时必须掩到半字节，否则高位会串进相邻字段
			record.columns[i] = ((long) (in[at] & 0xFF) << 16)
					| ((long) (in[at + 1] & 0xFF) << 8)
					| (in[at + 2] & 0xFFL)
					| ((long) (in[at + 3] & 0xFF) << 32)
					| ((long) (in[at + 4] & 0xFF) << 24)
					| ((long) (in[at + 5] & 0x0F) << SKY_SHIFT)
					| ((long) (in[at + 6] & 0x0F) << BLOCK_SHIFT)
					| ((long) (in[at + 7] & 0xFF) << FLAGS_SHIFT);
		}
		return record;
	}
}
