package sinanmap.client.map.data;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sinanmap.common.SinanConstants;
import sinanmap.common.coord.CoordinateSpaces;
import sinanmap.common.coord.Dimensions;
import sinanmap.common.hud.MinimapSettings;
import sinanmap.common.map.CenterOutSweep;
import sinanmap.common.map.ChunkColorCache;
import sinanmap.common.map.ChunkColorProvider;
import sinanmap.common.map.LayerMode;
import sinanmap.common.map.LayerSelector;
import sinanmap.common.map.SurfaceShading;
import sinanmap.common.map.TextureAverage;
import sinanmap.common.storage.ChunkRecord;

/**
 * 从客户端已加载区块采样一层的原始数据（地表/洞穴切面），负责落盘与读回。
 * 采到的是 {@link ChunkRecord}（本色含群系染色、高度、天空光、方块光）而非最终颜色，着色统一交给
 * {@link ChunkRecord#toColors}，故落盘数据与昼夜无关。取数顺序：内存缓存 → 已加载区块实时采样（受预算
 * 限制）→ 磁盘；只用 {@code getChunk(..., false)}，不会变成预加载器。
 *
 * <p><b>线程模型：</b>所有字段与缓存（fastutil 单线程集合，仅为免 {@code Long} 装箱）只由渲染线程写。
 * 后台 {@link #prefetcher} 只把 {@link WorldSaveChunks#readTag} 的结果投进 {@link #prefetchReady}；
 * {@link #samplePool} 只做洞穴纯扫描，都不碰渲染线程缓存。共享的仅 {@link BlockTextureColors} 与
 * {@link #SURFACE_DECORATION}（{@link ConcurrentHashMap}）。界线如此是因为 {@link #rgb}/{@link #layered}
 * 需问 {@code minecraft.level}、{@link ChunkRecord#toColors} 用 static 暂存数组（不可重入），着色只能在
 * 渲染线程做；{@link #prefetchGeneration} 为换世界计数，比对不符即丢弃以防串数据。
 */
public final class ClientChunkColorProvider implements ChunkColorProvider, WorldSaveChunks.ColumnColor {

	private static final Logger LOGGER = LoggerFactory.getLogger(SinanConstants.MOD_ID);

	/** 【诊断计量】总开关：默认<b>开</b>，{@code -Dsinanmap.stats=false} 关闭后累加全跳过，只剩一个布尔判断；
	 * 诊断用而非常驻（只做加减，不做字符串拼接）。 */
	public static final boolean STATS_ENABLED = !"false".equalsIgnoreCase(System.getProperty("sinanmap.stats", "true"));

	/**
	 * 【诊断计量】累加各段耗时/次数，供 {@code WorldMapScreen#logComposition} 每秒输出一行。
	 *
	 * <p><b>线程归属：</b>除标注"池线程写"的计数外，其余字段都只在渲染线程写，用普通 long 即可。
	 * 洞穴后台扫描跑在 {@code SamplePool} 池线程上，是唯一跨线程累加处，故那组用 {@link LongAdder} 与
	 * {@link AtomicLong}。
	 */
	public static final class MapStats {

		/** {@code sampleSurface}：调用次数、总耗时、单次最大耗时（纳秒）。 */
		public long surfaceCount;
		public long surfaceNanosSum;
		public long surfaceNanosMax;

		/** {@code layeredSample}（水/冰找底）：同上。 */
		public long layeredCount;
		public long layeredNanosSum;
		long layeredNanosMax;

		/** 实时洞穴列扫描 {@code scanCaveColumn}：调用次数、总耗时、单次最大耗时。 */
		public long caveRealtimeCount;
		public long caveRealtimeNanosSum;
		long caveRealtimeNanosMax;

		/** 后台洞穴扫描 {@code WorldSaveChunks.scanCaveColumns}：次数与总耗时（池线程写，用 LongAdder）。 */
		public final LongAdder caveBackgroundCount = new LongAdder();
		public final LongAdder caveBackgroundNanos = new LongAdder();
		/** 后台洞穴扫描单次最大耗时；池线程写，用 AtomicLong 无锁求最大值。 */
		public final AtomicLong caveBackgroundNanosMax = new AtomicLong();

		/** {@code fillCoarse}：调用次数、总耗时、单次最大耗时；以及"降采样/回落"两条路各命中多少像素。 */
		public long fillCoarseCount;
		public long fillCoarseNanosSum;
		long fillCoarseNanosMax;
		public long coarseDownsamplePixels;
		public long coarseFallbackPixels;
		/** 第 2 步里真正花在 {@code shadeChunkPair}（回世界采样）上的总耗时。 */
		public long coarseFallbackNanos;

		/** {@code fillSome}（叶页）：调用次数、总耗时、单次最大耗时、写下的区块数。 */
		public long fillSomeCount;
		public long fillSomeNanosSum;
		long fillSomeNanosMax;
		public long fillSomeChunks;

		/** {@code WorldMapScreen#fillView} 整体：调用次数、总耗时、单次最大耗时。 */
		public long fillViewCount;
		public long fillViewNanosSum;
		public long fillViewNanosMax;

		private MapStats() {
		}

		void recordSurfaceSample(long nanos) {
			surfaceCount++;
			surfaceNanosSum += nanos;
			if (nanos > surfaceNanosMax) {
				surfaceNanosMax = nanos;
			}
		}

		void recordLayered(long nanos) {
			layeredCount++;
			layeredNanosSum += nanos;
			if (nanos > layeredNanosMax) {
				layeredNanosMax = nanos;
			}
		}

		void recordCaveRealtime(long nanos) {
			caveRealtimeCount++;
			caveRealtimeNanosSum += nanos;
			if (nanos > caveRealtimeNanosMax) {
				caveRealtimeNanosMax = nanos;
			}
		}

		/** 由 {@code SamplePool} 的池线程调用，因此只碰 LongAdder / AtomicLong。 */
		void recordCaveBackground(long nanos) {
			caveBackgroundCount.increment();
			caveBackgroundNanos.add(nanos);
			caveBackgroundNanosMax.accumulateAndGet(nanos, Math::max);
		}

		public void recordFillCoarse(long nanos, int downsamplePixels, int fallbackPixels, long fallbackNanos) {
			fillCoarseCount++;
			fillCoarseNanosSum += nanos;
			if (nanos > fillCoarseNanosMax) {
				fillCoarseNanosMax = nanos;
			}
			coarseDownsamplePixels += downsamplePixels;
			coarseFallbackPixels += fallbackPixels;
			coarseFallbackNanos += fallbackNanos;
		}

		public void recordFillSome(long nanos, int chunks) {
			fillSomeCount++;
			fillSomeNanosSum += nanos;
			if (nanos > fillSomeNanosMax) {
				fillSomeNanosMax = nanos;
			}
			fillSomeChunks += chunks;
		}

		public void recordFillView(long nanos) {
			fillViewCount++;
			fillViewNanosSum += nanos;
			if (nanos > fillViewNanosMax) {
				fillViewNanosMax = nanos;
			}
		}

		/** 每秒输出之后清零（"本秒"口径，与既有诊断行一致）。 */
		public void reset() {
			surfaceCount = 0;
			surfaceNanosSum = 0;
			surfaceNanosMax = 0;
			layeredCount = 0;
			layeredNanosSum = 0;
			layeredNanosMax = 0;
			caveRealtimeCount = 0;
			caveRealtimeNanosSum = 0;
			caveRealtimeNanosMax = 0;
			caveBackgroundCount.reset();
			caveBackgroundNanos.reset();
			caveBackgroundNanosMax.set(0);
			fillCoarseCount = 0;
			fillCoarseNanosSum = 0;
			fillCoarseNanosMax = 0;
			coarseDownsamplePixels = 0;
			coarseFallbackPixels = 0;
			coarseFallbackNanos = 0;
			fillSomeCount = 0;
			fillSomeNanosSum = 0;
			fillSomeNanosMax = 0;
			fillSomeChunks = 0;
			fillViewCount = 0;
			fillViewNanosSum = 0;
			fillViewNanosMax = 0;
		}
	}

	/** 本探测器的计量累加器；与探测器同生命周期（见 {@link MapStats} 的线程归属说明）。 */
	private final MapStats stats = new MapStats();

	/** 供界面（{@code WorldMapScreen}）与瓦片仓库（{@code WorldTilePages}）写入/读取计量。 */
	public MapStats stats() {
		return stats;
	}

	/** 高度阴影的参考高度，取主世界海平面。 */
	static final int REFERENCE_Y = 63;

	/**
	 * <b>小地图</b>每帧预热（只补数据、不写像素）的时间上限（纳秒）。
	 *
	 * <p>界面按"以玩家为中心、一圈圈向外"的顺序调用，一轮把能建的建完，剩下的下一帧接着补，
	 * 因此补数据期间帧率不受影响。
	 *
	 * <p><b>为什么只有 {@value}，而全屏地图是 2.5~7 ms：</b>小地图是<b>常驻 HUD</b>，必须给游戏本身让路；
	 * 而且建数据预算是<b>每帧全局一份</b>（见 {@link #beginWarm(long)}），给小地图一个大额度会把同一帧里
	 * 全屏地图能用的那份也一起算大。1 ms 一帧只够补几十块，代价只是填充稍慢、下一帧继续。
	 */
	private static final long WARM_BUILD_NANOS = 1_000_000L;

	/** 单轮建数据的条数上限，兜住"每个区块都很快"这种时间检查跑不完的情况。 */
	private static final int BUILD_LIMIT = 512;

	/** 洞穴切面：空气窗口与下探深度，见 {@link LayerSelector#CAVE_TOLERANCE} / {@link LayerSelector#CAVE_SCAN_DEPTH}。 */
	private static final int CAVE_TOLERANCE = LayerSelector.CAVE_TOLERANCE;
	private static final int CAVE_SCAN_DEPTH = LayerSelector.CAVE_SCAN_DEPTH;

	/** 算"往上走几格结果仍然不变"时，最多往口袋上方再扫多少格找天花板。 */
	private static final int CAVE_CEILING_SCAN = 24;

	/** 算实心列能跨多少层复用时，最多往外扫多少格确认实心范围。 */
	private static final int CAVE_SOLID_SCAN = 32;

	/**
	 * 洞穴切片的条数上限。
	 *
	 * <p>与 {@link #MAX_SURFACE_SAMPLES} 同理：它决定"在洞里走过的地方能记住多远"，超出之后最久没用过的
	 * 那块下次要整块重扫。一条切片约 6 KB（原始数据 2.4 KB + 当前颜色 1 KB + 昼夜两档 2 KB + 适用范围 1 KB）。
	 */
	private static final int MAX_CAVE_SLICES = 4096;

	/**
	 * 水色的盖度。
	 *
	 * <p>水底要能看出结构，但不能像没盖水一样清楚：太淡水底纹理会盖过水本身，太浓深水退化成纯色。
	 *
	 * <p>取的是<b>水的半透明度</b>（原版渲染水体的不透明度）：水盖在地形上、地形还剩约四分之一透出来。
	 */
	private static final double WATER_OPACITY = 0.75;

	/**
	 * 冰色的盖度。
	 *
	 * <p>取 {@code 216 / 255}，即原版渲染冰用的不透明度（水 {@code 191}、冰 {@code 216}、其它半透明
	 * {@code 127}）。比水更"实"，底下的水与地形只隐约可见——原版高度图把冰当成地表，只看高度图会得到
	 * 一整片纯冰；冰必须按自己这一档来盖，用水的档位会让它淡得像一层薄水。
	 */
	private static final double ICE_OPACITY = 216.0 / 255.0;

	/**
	 * 深度的表现方式：<b>只压暗覆盖层（水）自己的颜色，不压水底</b>。
	 *
	 * <p>必须在采样的这一层做，不能放到着色时对整个混合结果乘系数：那样水底那约四分之一的贡献会跟着
	 * 一起变暗，深水处就完全看不见底。
	 */
	private static final double DEPTH_DIM_PER_BLOCK = 0.018;

	/** 水深造成的最大压暗比例（再深也不会把水压成黑色）。 */
	private static final double MAX_DEPTH_DIM = 0.38;

	/**
	 * 虚空或洞穴里扫不到方块时的本色，着色后接近纯黑。
	 *
	 * <p>世界存档那条路（{@link WorldSaveChunks}）画洞穴切面时必须给同一个值，否则加载范围的内外两侧
	 * 会差出一圈色阶。
	 */
	static final int NO_BLOCK_RGB = 0x08080C;

	/**
	 * 昼夜衰减清缓存的分档步长。{@code 1} 就是"每一级亮度都算一档"，换档最密、观感最接近连续。
	 *
	 * <p>分档只影响<b>小地图</b>：它每帧重新合成整张贴图，颜色缓存跟着"当前档"走。世界地图不走这里，
	 * 它按下面两个固定档各烤一张贴图。
	 */
	private static final int SKY_DARKEN_BUCKET = 1;

	/**
	 * 世界地图那两张贴图分别按这两档昼夜衰减烘焙。
	 *
	 * <p>{@code 0} 是白天（天空光一点不减），{@link #SKY_DARKEN_NIGHT} 是原版午夜的衰减值。<b>两张都
	 * 不随时间变</b>，所以换天色不需要重算像素：绘制时按当前天色把夜晚那张以 alpha 叠在白天那张上。
	 * 这个线性插值与真实公式几乎一致（亮度对光照等级是线性的），只有跨过交界的少数列会差一点拐点。
	 *
	 * <p>逐像素光照烘焙进贴图，昼夜只是换一个系数。26.1 里带颜色的 {@code blit} 只接受
	 * {@link net.minecraft.resources.Identifier}，动态贴图那条不带颜色，所以用"两张贴图 + alpha 混合"顶替。
	 */
	static final int SKY_DARKEN_DAY = 0;
	static final int SKY_DARKEN_NIGHT = 11;

	/** 地形改动检测：每隔这么久重新采一遍玩家四周已加载的区块。 */
	private static final long REFRESH_INTERVAL_NANOS = 2_000_000_000L;

	/**
	 * 一直没有改动时，检测间隔最多拉到这么长（见 {@link #refreshLoadedChunks} 末尾的退避）。
	 *
	 * <p>静坐看地图时几百个区块里几乎没一个被改过，却要整圈重采才能确认；退避之后开销趋近于零，
	 * 一旦发现改动就立刻回到 {@link #REFRESH_INTERVAL_NANOS}。
	 */
	private static final long REFRESH_IDLE_MAX_NANOS = 30_000_000_000L;

	/** 一轮检测最多花多少时间，免得某一帧把四周几百个区块一次采完。 */
	private static final long REFRESH_BUDGET_NANOS = 1_000_000L;

	/** 方块变化引发的重采每帧最多花多少时间。 */
	private static final long BLOCK_CHANGE_BUDGET_NANOS = 1_000_000L;

	/**
	 * 检测半径的兜底值（区块）。
	 *
	 * <p>正常用<b>渲染距离</b>：地图要盯的是"已加载出来的区块有没有被改动过"，而加载范围就是渲染距离；
	 * 取小了，稍远但仍在视野里的改动就永远发现不了。
	 */
	private static final int REFRESH_RADIUS_FALLBACK = 8;

	private final Minecraft minecraft;

	private final BlockTextureColors textureColors = new BlockTextureColors();

	private int builtThisPass;
	private long sliceDeadline;

	/**
	 * 当前帧号：由界面在每帧开头调一次 {@link #beginFrame()} 推进一步。
	 *
	 * <p>"每帧一份建数据预算"（{@link #beginWarm(long)}）与"每帧只跑一次改动检测"
	 * （{@link #refreshLoadedChunks(long)}）都靠它把同一帧里两个界面的调用认成同一帧。
	 */
	private long frameSerial;

	/** 上一次开建数据预算的帧号，见 {@link #beginWarm(long)}；{@code MIN_VALUE} 表示还没开过。 */
	private long budgetFrameSerial = Long.MIN_VALUE;

	/** 上一次跑改动检测的帧号，见 {@link #refreshLoadedChunks(long)}；{@code MIN_VALUE} 表示还没跑过。 */
	private long refreshFrameSerial = Long.MIN_VALUE;

	private int cachedSkyDarken = -1;
	/** 未分档的昼夜衰减原始值（0~{@link #SKY_DARKEN_NIGHT}），给世界地图算两张贴图的混合比例。 */
	private int rawSkyDarken;
	private int passSkyDarken;
	private int layer = LayerSelector.SURFACE;

	/** 地形被改过、像素需要重画的区块（打包成 {@code chunkX << 32 | chunkZ}）。 */
	private final LongOpenHashSet changedChunks = new LongOpenHashSet();

	/** 后台刚读到数据、地图可能还没画上的区块。与 {@link #changedChunks} 的区别见 {@link #drainArrivedChunks()}。 */
	private final LongOpenHashSet arrivedChunks = new LongOpenHashSet();

	/**
	 * 方块更新钩子（{@code LevelBlockChangeMixin}）登记下来的区块：下一帧立刻重采。
	 *
	 * <p>钩子可能从别的线程进来，所以用并发集合；重复登记同一块会被集合吃掉。
	 */
	private final Set<Long> pendingBlockChanges = ConcurrentHashMap.newKeySet();

	/** 当前这份数据源，供方块更新钩子登记改动。 */
	private static volatile ClientChunkColorProvider active;

	/**
	 * 洞穴切片：按区块缓存的逐列原始数据，外加"每列结果在哪些高度上仍然成立"。
	 *
	 * <p>缓存的必须是<b>原始数据</b>（本色、高度、光照）而不是最终颜色：光照按邻域平滑过，只重扫一列
	 * 整块颜色都要重算；留着原始数据就能低成本地整体重新着色。
	 */
	private static final class CaveSlice {

		private final ChunkRecord record = new ChunkRecord();
		private final int[] colors = new int[ChunkRecord.COLUMNS];
		private final short[] validLow = new short[ChunkRecord.COLUMNS];
		private final short[] validHigh = new short[ChunkRecord.COLUMNS];

		/**
		 * 白天/夜晚两档的固定颜色，跟着原始数据一起缓存：这两档<b>与天色无关</b>，一份数据算一次就够。
		 * 每次合成都重算的话，小地图推进淡入时每秒合成 25 次、着色也乘 25——明显掉帧。
		 */
		private final int[] dayColors = new int[ChunkRecord.COLUMNS];
		private final int[] nightColors = new int[ChunkRecord.COLUMNS];
		/** 上面两份是不是还对得上 {@link #record}；重扫过就作废。 */
		private boolean pairValid;

		/** 白天/夜晚两份颜色是按哪一层算出来的：天花板维度的亮度与层高有关，层变了要重算。 */
		private int pairLayer = Integer.MIN_VALUE;

		/**
		 * 所有列适用范围的<b>交集</b>，即"换到这一层之后整块结果都不变"的高度区间。
		 *
		 * <p>逐列问的话换层要判断"每块 256 列 × 一屏上千块"、几十万次比较挤在同一帧；交集扫完算一次就够，
		 * 之后每次换层只比两个数——"只重填真的变了的区块"全靠它。
		 */
		private int coversLow = Short.MAX_VALUE;
		private int coversHigh = Short.MIN_VALUE;

		/** 原始数据是按哪一层扫出来的。 */
		private int layer = Integer.MIN_VALUE;

		/** 颜色是按哪一次光照分档算出来的。 */
		private int renderedEpoch = Integer.MIN_VALUE;

		private CaveSlice() {
			// 初始时每列都不适用：validLow > validHigh
			Arrays.fill(validLow, Short.MAX_VALUE);
			Arrays.fill(validHigh, Short.MIN_VALUE);
		}

		/** 重算 {@link #coversLow} / {@link #coversHigh}；扫完一块的列之后调一次。 */
		private void refreshCoverage() {
			int low = Short.MAX_VALUE;
			int high = Short.MIN_VALUE;
			for (int index = 0; index < ChunkRecord.COLUMNS; index++) {
				low = Math.max(low, validLow[index]);
				high = Math.min(high, validHigh[index]);
			}
			coversLow = low;
			coversHigh = high;
		}
	}

	/**
	 * 洞穴切片缓存，<b>访问序 LRU</b>。
	 *
	 * <p>用 fastutil 的 {@link Long2ObjectLinkedOpenHashMap} 消掉 {@code long} 键的 {@code Long} 装箱；
	 * 它<b>没有"访问序"模式</b>，所以访问序自己维持：命中 {@code getAndMoveToLast} 挪到队尾，写入
	 * {@code putAndMoveToLast}，淘汰从队首 {@code removeFirst}。这里的命中率就是"在洞里走过的地方能
	 * 记住多远"（见 {@link #MAX_CAVE_SLICES}），<b>不能改</b>。
	 */
	private final Long2ObjectLinkedOpenHashMap<CaveSlice> caveSlices = new Long2ObjectLinkedOpenHashMap<>(64, 0.75F);

	/** 光照分档的代号：昼夜衰减换档时递增，用来让切片按新光照重新着色。 */
	private int shadeEpoch;

	/**
	 * 地表区块的原始数据缓存，以及由它派生的当前颜色。
	 *
	 * <p><b>它是地形的唯一依据</b>：颜色只是派生缓存，原始数据在，颜色随时能重算（昼夜换档就是这么做的）。
	 * 以前另有一份"已着色区块"缓存，它一旦把某条挤掉，{@code colorsForChunk} 就当这一块"没有数据"、
	 * 必须重采（已加载的逐列扫三维方块、没加载的还要读区域文件）——淘汰的是最便宜的副本，代价却落在
	 * 最贵的路上（"离开加载过的地方、再回去又加载一遍"）。现在只留原始数据这一份，比颜色那份还小，
	 * 同样内存能多留几倍的已探索区域。
	 */
	private static final class SurfaceSample {

		private final ChunkRecord record;

		/** 这块数据属于"天花板维度"（地狱）：着色时高度项要覆盖整个维度高度，见 {@link ChunkRecord#toColors}。 */
		private final boolean ceiling;

		private int skyDarken = Integer.MIN_VALUE;

		/** 按 {@link #skyDarken} 这一档算出来的颜色；换档时原地重算。 */
		private int[] colors;

		/** 白天/夜晚两档的固定颜色，见 {@link CaveSlice#dayColors}：一份原始数据只算一次。 */
		private int[] dayColors;
		private int[] nightColors;

		private SurfaceSample(ChunkRecord record, boolean ceiling) {
			this.record = record;
			this.ceiling = ceiling;
		}

		/** 取当前档位的颜色，需要就（重新）着色。 */
		private int[] colors(int skyDarken, int caveBandTop) {
			if (colors == null) {
				colors = new int[ChunkRecord.COLUMNS];
			}
			if (this.skyDarken != skyDarken) {
				this.skyDarken = skyDarken;
				record.toColors(REFERENCE_Y, skyDarken, colors, ceiling, caveBandTop);
			}
			return colors;
		}
	}

	/**
	 * 按<b>访问序 LRU</b>缓存地表原始数据（与 {@link #caveSlices} 同理）。
	 *
	 * <p>命中率直接决定"平移出去一屏再回来命不命中"（见 {@link #MAX_SURFACE_SAMPLES}），<b>语义不能动</b>。
	 */
	private final Long2ObjectLinkedOpenHashMap<SurfaceSample> surfaceSamples = new Long2ObjectLinkedOpenHashMap<>(256, 0.75F);

	/**
	 * 原始数据缓存的上限（条）。
	 *
	 * <p>就是<b>"走过的地方能记住多远"</b>：超出后最久没用过的那条被丢掉，那一块下次要重新采样
	 * （远贵于一次重着色）。一条记录约 2.4 KB 数据 + 1 KB 派生颜色。
	 *
	 * <p>取 {@value}：缩到最小时一屏本身就要铺满 {@code WorldTilePages.MAX_PAGES} 页 = 12288 个区块。
	 * 留到一屏意味着"平移出去一屏再回来"命中的还是原来那份数据。
	 */
	private static final int MAX_SURFACE_SAMPLES = 12288;

	private long lastRefreshNanos;

	/** 当前的地形改动检测间隔：连续几轮什么都没发现就翻倍，见 {@link #REFRESH_IDLE_MAX_NANOS}。 */
	private long refreshIntervalNanos = REFRESH_INTERVAL_NANOS;

	/** 本轮检测有没有发现"真的变了"的区块，见 {@link #refreshLoadedChunks} 末尾的退避。 */
	private boolean refreshSawChange;
	private int refreshBaseChunkX = Integer.MIN_VALUE;
	private int refreshBaseChunkZ = Integer.MIN_VALUE;

	/**
	 * 改动检测的遍历顺序：<b>从玩家所在区块开始、一圈圈向外</b>（见 {@link #refreshLoadedChunks}）。
	 *
	 * <p><b>为什么不按"从窗口最西边一列列往东"扫。</b>一圈有 {@code (2r+1)²} 块（视距 32 时 4225 块），
	 * 每块要整块重采（实测约 0.9 ms），每帧却只给 {@link #REFRESH_BUDGET_NANOS}（1 ms）——一轮要几十秒、
	 * 每帧只推进一两块。按列扫的话玩家<b>脚下与周围</b>那一圈排在两千多块之后，"最该被更新的一圈永远轮
	 * 不到"，正是"身边改了地形也不修正"的直接原因。
	 *
	 * <p>由中心向外之后，一轮<b>开头</b>就是玩家周围。窗口尺寸变了（改视距）就重建；玩家换区块就
	 * {@link CenterOutSweep#reset()} 回到新中心（窗口整体挪了一格，旧进度不再对应同一批区块）。
	 */
	private CenterOutSweep refreshSweep;

	/** 上面那个遍历器是按多大的窗口建的；与当前窗口不一致就重建。 */
	private int refreshSweepSide;

	/** 近圈重采的周期：见 {@link #refreshNear}。 */
	private static final long NEAR_REFRESH_INTERVAL_NANOS = 300_000_000L;

	/** 近圈半径（区块）：脚下那一块加上紧邻的一圈。 */
	private static final int NEAR_RADIUS = 1;

	/** 近圈每帧最多花多少时间。 */
	private static final long NEAR_BUDGET_NANOS = 1_000_000L;

	/** 近圈遍历器（中心 = 玩家所在区块），见 {@link #refreshNear}。 */
	private CenterOutSweep nearSweep;
	private int nearCenterChunkX = Integer.MIN_VALUE;
	private int nearCenterChunkZ = Integer.MIN_VALUE;
	private boolean nearRunning;
	private long nearNextNanos;

	private ClientLevel storageLevel;
	private MapStorage storage;

	/**
	 * 世界地图正在看的维度 id（{@code null} = 跟着玩家所在的维度）。
	 *
	 * <p>切到别的维度看地图时只是<b>读</b>那个维度存过的数据：不采样当前世界、也不落盘，见
	 * {@link #foreignView()}。
	 */
	private String viewDimension;

	/** {@link #storage} 是按哪个维度打开的，用来判断"要不要换一套目录"。 */
	private String storageDimension;

	/**
	 * 取色用的群系入口：已加载区块问关卡，未加载（远处）的区块读世界存档，见 {@link WorldBiomeTints}。
	 *
	 * <p>实时采样与世界存档两条着色路都用它，于是"远处的群系"不再一律是平原，加载范围边缘的
	 * 邻域混合也不会再混进平原色。
	 */
	private final WorldBiomeTints biomeTints;

	public ClientChunkColorProvider(Minecraft minecraft, MinimapSettings settings) {
		this.minecraft = minecraft;
		this.settings = settings;
		this.biomeTints = new WorldBiomeTints(minecraft);
		this.prefetcher = (ThreadPoolExecutor) Executors.newFixedThreadPool(
				settings.effectivePrefetchThreads(), task -> {
					Thread thread = new Thread(task, "sinanmap-prefetch");
					thread.setDaemon(true);
					thread.setPriority(Thread.MIN_PRIORITY);
					return thread;
				});
		this.samplePool = new SamplePool(settings.effectiveCaveScanThreads());
		active = this;
	}

	/**
	 * 把设置里的性能项同步到线程池。
	 *
	 * <p>在取色入口 {@link #colorsForChunk} 每帧调一次：值没变时只是两次 int 比较。线程数<b>当场生效</b>，
	 * 不用重进世界——这也是池子用 {@link ThreadPoolExecutor} 的原因。
	 */
	private void syncPerformanceSettings() {
		int want = settings.effectivePrefetchThreads();
		if (prefetcher.getMaximumPoolSize() != want) {
			// 先调上限再调核心：否则中途会出现"核心 > 上限"的非法状态
			if (want > prefetcher.getMaximumPoolSize()) {
				prefetcher.setMaximumPoolSize(want);
				prefetcher.setCorePoolSize(want);
			} else {
				prefetcher.setCorePoolSize(want);
				prefetcher.setMaximumPoolSize(want);
			}
		}
		samplePool.setThreads(settings.effectiveCaveScanThreads());
	}

	/** 世界地图切换显示维度：传 {@code null} 表示跟回玩家所在的维度。 */
	public void setViewDimension(String dimension) {
		String normalized = dimension == null || dimension.isBlank() ? null : dimension;
		if (normalized == null ? viewDimension == null : normalized.equals(viewDimension)) {
			return;
		}
		viewDimension = normalized;
		// 群系取色也要跟着换：看别的维度时一律按那个维度的世界存档读，不能问玩家所在关卡的群系
		ClientLevel level = minecraft.level;
		String playerDimension = level == null ? null : level.dimension().identifier().toString();
		biomeTints.setDimension(
				normalized == null || normalized.equals(playerDimension) ? null : normalized);
		// 缓存只按区块坐标 + 层作键，不带维度：不在这里作废，切过去看到的还是上一个维度的地形
		dropDimensionCaches();
	}

	/**
	 * 取数据时"算哪个维度"：看别的维度时是正在查看的那个，否则是玩家所在的维度。
	 *
	 * <p>世界存档、群系、区域文件存在性全部按它拼路径——否则切过去读到的还是玩家当前维度的地形。
	 */
	private String dataDimension() {
		if (viewDimension != null) {
			return viewDimension;
		}
		ClientLevel level = minecraft.level;
		return level == null ? null : level.dimension().identifier().toString();
	}

	/**
	 * 现在看的是不是<b>别的</b>维度（不是玩家站着的那个）。
	 *
	 * <p>界面的"这一片有没有数据"判断必须走它：看别的维度时不能用玩家当前维度的世界存档与玩家位置，
	 * 坐标已经按维度比例换算过，两边对不上（见 {@link #ownStorageMayHaveData}）。
	 */
	public boolean viewingForeignDimension() {
		return foreignView();
	}

	/**
	 * 看别的维度时用：这一块在"正在查看的那个维度"的本地存档里有没有可能有数据。
	 *
	 * <p>只查内存里的存在位图，不碰区块文件。地图据此决定"这一页值不值得建出来填"——<b>不能</b>用
	 * 玩家当前维度的世界存档来判（见 {@link #viewingForeignDimension()}），否则坐标换算后对不上，
	 * 有数据的页会被整片误判成"没有数据"，表现就是"切过去一直不加载"。
	 */
	public boolean ownStorageMayHaveData(int chunkX, int chunkZ) {
		return storage != null
				&& !storage.isKnownAbsent(chunkX, chunkZ, LayerSelector.storageLayer(layer));
	}

	/**
	 * 看的是不是<b>别的</b>维度（不是玩家站着的那个）。
	 *
	 * <p>是的话一切都退化成"只读自己存过的那份"：当前世界里的同坐标方块属于另一个维度，
	 * 既不能拿来当这一层的颜色，更不能写进这个维度的存档里。
	 */
	private boolean foreignView() {
		ClientLevel level = minecraft.level;
		return viewDimension != null && level != null
				&& !viewDimension.equals(level.dimension().identifier().toString());
	}

	/** 换维度时把只按区块坐标作键的那几份缓存作废（与换世界的处理一致）。 */
	private void dropDimensionCaches() {
		changedChunks.clear();
		pendingBlockChanges.clear();
		caveSlices.clear();
		surfaceSamples.clear();
		prefetchGeneration++;
		prefetchQueued.clear();
		prefetchMissing.clear();
		prefetchReady.clear();
		lastRefreshNanos = 0L;
		refreshBaseChunkX = Integer.MIN_VALUE;
		refreshBaseChunkZ = Integer.MIN_VALUE;
		// 目录也要跟着换（下一次取色时 ensureStorage 会发现 storageDimension 变了）
		storageDimension = null;
	}

	/**
	 * 设定本次要渲染的层。
	 *
	 * @param layerY {@link LayerSelector#SURFACE} 表示地表，否则为玩家所在的那一格 Y
	 */
	public void setLayer(int layerY) {
		this.layer = layerY;
	}

	/**
	 * 标记"新的一帧开始"——每帧由界面调用一次，见 {@link #frameSerial}。
	 *
	 * <p>调用点在小地图 HUD 渲染的最开头（{@code MinimapHud#render}）：它每帧必然执行一次，且在渲染
	 * 管线上早于全屏地图的抽取，所以同一帧里两个界面看到的 {@link #frameSerial} 是同一个值。
	 *
	 * <p><b>但"每帧必然执行一次"并不成立</b>：打开全屏地图时那一层 HUD 不绘制，帧号会冻住，所以全屏
	 * 地图那边还额外调 {@link #beginFrameIfStale(long)} 兜底。
	 */
	public void beginFrame() {
		frameSerial++;
	}

	/** 当前帧号；给界面判断"从自己上次渲染到现在，帧号有没有动过"，见 {@link #beginFrameIfStale(long)}。 */
	public long frameSerial() {
		return frameSerial;
	}

	/**
	 * 由全屏地图每帧调用：<b>只有当帧号从自己上次渲染起一直没动过时</b>才把它推进一步。
	 *
	 * <p><b>为什么必须有这一条。</b>{@link #frameSerial} 是"每帧一次"那两条闸的唯一依据
	 * （{@link #beginWarm(long)} 的重置、{@link #refreshLoadedChunks(long)} 的检测），而它的推进点
	 * 本来只有小地图 HUD，<b>打开全屏地图时 HUD 不绘制</b>——帧号冻住，两条闸永久失效：
	 * {@code builtThisPass} 不再归零、{@link #isWarmSpent()} 恒为真（地图再也不补数据），
	 * {@link #refreshFrameSerial} 也不再前进（地形改动检测再也不跑）。
	 *
	 * <p>判据用"帧号是否等于自己上次看到的那个"而不是比时间：小地图正常渲染时已经推进过，这里就不做；
	 * 只有帧号确实没动过时才由这里补推，一帧只补一次。
	 */
	public void beginFrameIfStale(long lastSeenSerial) {
		if (frameSerial == lastSeenSerial) {
			frameSerial++;
		}
	}

	/**
	 * 开始一段只补数据、不写像素的预热（小地图用，预算很小）。
	 *
	 * <p>界面按"以玩家为中心、一圈圈向外"的顺序取色，于是地图是由内向外长出来的，
	 * 而不是自上而下刷一遍。
	 */
	public void beginWarm() {
		beginWarm(WARM_BUILD_NANOS);
	}

	/**
	 * 开始预热，并指定这一方<b>想要</b>的建数据预算。
	 *
	 * <p>全屏地图会把预算放大（地图是屏幕上唯一内容，多花帧时间换加载快很划算）；小地图是常驻 HUD，
	 * 只能用很小的预算。
	 *
	 * <p><b>预算是"每帧全局一份"，不是"每个界面各一份"。</b>小地图与全屏地图同一帧都会调到这里，若每次都
	 * 重置 {@link #builtThisPass} 与 {@link #sliceDeadline}，同帧两者就各拿一份完整额度、叠加可达 ~11 ms。
	 * 现在只认 {@link #frameSerial}：同一帧里<b>只有第一次</b>调用会重置，后续沿用剩余额度。
	 *
	 * <p><b>帧号本身要保证每帧前进</b>，否则 {@link #builtThisPass} 再也不归零（地图彻底不再补数据）；
	 * 所以全屏地图那边还调 {@link #beginFrameIfStale(long)} 兜底。
	 *
	 * <p><b>顺序敏感，且这是刻意接受的。</b>先后由渲染管线决定（HUD 先于 Screen），谁先谁就"定下"这一帧的
	 * 额度基准；同一帧总共花掉的就那么多。后续调用只允许把 {@link #sliceDeadline} <b>往后延</b>，绝不往回拨
	 * ——往回拨会把先开那一方还没用掉的额度提前砍掉。
	 *
	 * <p>唯一不做全局化的是 {@link #ensureStorage()} 与 {@link #refreshSkyDarken()}：它们不是"预算"，
	 * 每次都要做。
	 */
	public void beginWarm(long budgetNanos) {
		// 这两件事每次都要做（见上面说明），不受"每帧一份预算"的门影响
		ensureStorage();
		refreshSkyDarken();
		long now = System.nanoTime();
		if (budgetFrameSerial != frameSerial) {
			// 本帧第一次开预算：只有这一次重置计数与截止时刻
			budgetFrameSerial = frameSerial;
			builtThisPass = 0;
			sliceDeadline = now + budgetNanos;
		} else {
			// 同帧后续调用：不重置计数，截止时刻只往后延、不往回拨
			sliceDeadline = Math.max(sliceDeadline, now + budgetNanos);
		}
	}

	/** 本段的预算是否用完（预热方据此提前收工）。 */
	public boolean isWarmSpent() {
		return sliceSpent();
	}

	/** 本轮（合成或预热）的建数据预算是否用完。 */
	private boolean sliceSpent() {
		return builtThisPass >= BUILD_LIMIT || System.nanoTime() >= sliceDeadline;
	}

	/**
	 * 天空光衰减：直接取原版的 {@code getSkyDarken()}。
	 *
	 * <p>它是客户端一直在用的、喂给天空渲染的那个数，绝不会因为"属性查不到→给 0"这类事情
	 * 把白天算成深夜（那会让整张地图盖一层暗色）。
	 */
	private int skyDarkenNow(ClientLevel level) {
		return level.getSkyDarken();
	}

	/**
	 * 把当前天色对齐一次，界面每帧都应该调用。
	 *
	 * <p>以前只有每两秒一次的改动检测才刷新，于是进世界第一帧、或天色刚开始变时会拿上一次的天色去画
	 * ——夜里打开地图先显示成白天、过一下才变黑。
	 */
	public void refreshSky() {
		refreshSkyDarken();
	}

	/**
	 * 刷新昼夜/天气造成的天空光衰减。
	 *
	 * <p>它影响所有列的明暗、会让颜色缓存整体失效，但<b>不能一有变化就清空整张缓存</b>：
	 * {@code getSkyDarken()} 连续变化，太阳挪一点就变一次，每次清空都意味着可见范围内所有区块重新
	 * 解码着色（"地图反复重新加载"）。这里按 {@value #SKY_DARKEN_BUCKET} 分档，一档之内沿用已有颜色。
	 *
	 * <p>着色用的也必须是<b>归档后</b>的值：直接拿连续值着色会让同档内颜色每帧都不同，改动检测就会把
	 * 昼夜变化误判成地形改动。
	 */
	private void refreshSkyDarken() {
		ClientLevel level = minecraft.level;
		if (level == null) {
			return;
		}
		// 天色取原版的值（见 skyDarkenNow）：不自己估曲线，也不等 SetTime 包
		rawSkyDarken = skyDarkenNow(level);
		int bucket = rawSkyDarken / SKY_DARKEN_BUCKET;
		if (bucket != cachedSkyDarken) {
			cachedSkyDarken = bucket;
			// 不在这里清掉颜色缓存：颜色会按需从原始数据重新算（见 surfaceSamples），
			// 清掉等于让每个可见区块重新采样、没加载的还要重读磁盘
			shadeEpoch++;
		}
		passSkyDarken = bucket * SKY_DARKEN_BUCKET;
	}

	/**
	 * 当前昼夜衰减占"正午→午夜"的比例（0~1），地图界面用它混合白天/夜晚两张贴图。
	 *
	 * <p>取<b>未分档</b>的原始值：分档是为了让颜色缓存别一直失效，混合比例在档与档之间也该连续。
	 */
	public double skyDarkenFraction() {
		return Math.clamp(rawSkyDarken / (double) SKY_DARKEN_NIGHT, 0.0, 1.0);
	}

	@Override
	public boolean shadeChunkPair(int chunkX, int chunkZ, int[] dayOut, int[] nightOut) {
		if (colorsForChunk(chunkX, chunkZ) == null) {
			return false;
		}
		// 上一步已经把原始数据备好了（已加载的区块记在 surfaceSamples，洞穴切片记在 caveSlices）。
		// 取回来之后一次算好两档、贴着原始数据缓存起来：这两档与天色无关，一份数据只算一次。
		//
		// 之前是每次调用都重新着色一遍，于是小地图每合成一次就要把可见区块全着色两遍——
		// 而推进淡入时它每秒合成 25 次，掉帧掉得很明显。
		int length = Math.min(ChunkRecord.COLUMNS, Math.min(dayOut.length, nightOut.length));
		if (LayerSelector.isCave(layer)) {
			CaveSlice slice = caveSlices.getAndMoveToLast((long) chunkX << 32 | chunkZ & 0xFFFFFFFFL);
			if (slice != null) {
				if (!slice.pairValid || slice.pairLayer != slice.layer) {
					slice.record.toColors(REFERENCE_Y, SKY_DARKEN_DAY, slice.dayColors,
							viewDimensionHasCeiling(), caveShading());
					slice.record.toColors(REFERENCE_Y, SKY_DARKEN_NIGHT, slice.nightColors,
							viewDimensionHasCeiling(), caveShading());
					slice.pairValid = true;
					slice.pairLayer = slice.layer;
				}
				System.arraycopy(slice.dayColors, 0, dayOut, 0, length);
				System.arraycopy(slice.nightColors, 0, nightOut, 0, length);
				return true;
			}
			// 没有切片 = 这一块不是实时采样的（区块没加载），数据是刚从磁盘读回来的，
			// 原始数据记在 surfaceSamples 里——与地表共用同一份缓存，因此接着往下走。
			// 少了这一步，从世界存档读出来的洞穴切面会被当成"没有数据"：颜色明明算好了，
			// 却因为找不到切片而画成未探明——这正是"洞穴模式下别处已加载过的区块不显示"的另一半。
		}
		// 按归档层查（见 LayerSelector#storageLayer）：这些数据是后台按归档层存进来的
		int storageKey = LayerSelector.storageLayer(layer);
		SurfaceSample sample = surfaceSample(chunkX, chunkZ, storageKey);
		if (sample == null) {
			return false;
		}
		if (sample.dayColors == null) {
			int band = sampleBand(storageKey);
			sample.dayColors = new int[ChunkRecord.COLUMNS];
			sample.nightColors = new int[ChunkRecord.COLUMNS];
			sample.record.toColors(REFERENCE_Y, SKY_DARKEN_DAY, sample.dayColors, sample.ceiling, band);
			sample.record.toColors(REFERENCE_Y, SKY_DARKEN_NIGHT, sample.nightColors, sample.ceiling, band);
		}
		System.arraycopy(sample.dayColors, 0, dayOut, 0, length);
		System.arraycopy(sample.nightColors, 0, nightOut, 0, length);
		return true;
	}

	@Override
	public boolean isPending(int chunkX, int chunkZ) {
		// 已经排给后台（还没读回来）：那份数据下一帧左右就会进缓存。
		// 预读按归档层排队（见 LayerSelector#storageLayer），所以这里也要用归档层去查。
		return prefetchQueued.contains(ChunkColorCache.key(chunkX, chunkZ, LayerSelector.storageLayer(layer)));
	}

	/**
	 * 这一块<b>现在</b>能不能断定"没有地形可画"，画成未探明。
	 *
	 * <p>两种算：世界存档里问过确实没有（或连区域文件都不存在）、以及"区块还没生成到位"
	 * （{@link #prefetchUnready}，之后重试轮转会再问一次）。
	 *
	 * <p><b>必须与"还没定论"分开</b>：预算用尽、请求在路上、队列满了没排上只是"还没问到"，当成"没有
	 * 数据"会画出一片未探明并标记成"问过了"，而没有任何机制会回头再问（"加载了一点就再也不动"）。
	 */
	public boolean isKnownEmpty(int chunkX, int chunkZ) {
		long key = ChunkColorCache.key(chunkX, chunkZ, LayerSelector.storageLayer(layer));
		if (prefetchMissing.contains(key) || prefetchUnready.containsKey(key)) {
			// 世界存档里找过：确实没有，或者还没生成到位（后者过一会儿会再问）
			return true;
		}
		// 自己没写过、连区域文件都不存在（这两步都不碰磁盘的"存在位图"与带缓存的 stat）。
		// 用归档层查：落盘与预读都按归档层存（见 LayerSelector#storageLayer）
		if (storage == null || !storage.isKnownAbsent(chunkX, chunkZ, LayerSelector.storageLayer(layer))) {
			return false;
		}
		// 世界存档按"正在查看的维度"问：切到主世界时读的就是主世界的 region，而不是玩家当前维度的。
		// 联机拿不到存档（dimension 为 null）时仍然只能以"自己这份存档里确实没写过"为准
		String dimension = dataDimension();
		return dimension == null || !WorldSaveChunks.hasRegion(minecraft, dimension, chunkX, chunkZ);
	}

	/** 缓存里没有这一列的高度。 */
	public static final int NO_HEIGHT = Integer.MIN_VALUE;

	/**
	 * 自己缓存里这一列的地表高度（<b>站上去那一格</b>的 Y），没缓存到就返回 {@link #NO_HEIGHT}。
	 *
	 * <p>给"光标处的 Y"当第二来源：区块已不在加载范围、世界存档又还没写盘时，客户端高度图与存档都
	 * 问不到——但那一块采样过、原始数据还在缓存里（见 {@link #MAX_SURFACE_SAMPLES}）。
	 */
	public int cachedSurfaceHeight(int chunkX, int chunkZ, int localX, int localZ) {
		SurfaceSample sample = surfaceSample(chunkX, chunkZ, LayerSelector.SURFACE);
		if (sample == null) {
			return NO_HEIGHT;
		}
		int index = localZ * SinanConstants.CHUNK_SIZE + localX;
		if (sample.record.isNoData(index)) {
			return NO_HEIGHT;
		}
		// 记录里存的是"地面那一格"，加一才是站上去的那一格（与高度图的语义一致）
		return sample.record.height(index) + 1;
	}

	@Override
	public int[] colorsForChunk(int chunkX, int chunkZ) {
		// 性能项（线程数等）当场对齐设置：这里是每帧必到的入口，改完下一帧就生效
		syncPerformanceSettings();
		// 后台读好的成品先搬进来。放在这条入口上而不是预热流程里：界面取色的唯一入口就是这里，
		// 万一哪个界面没走预热，预读的结果也一定会被搬进来。
		//
		// 按时间限流，不按调用次数：界面每帧会成千上百次地问这一块那一块，
		// 若每次调用都搬一批，一帧就能把整条队列搬空（那正是"一顿一顿"的来源）。
		long now = System.nanoTime();
		// "当时没有"的记录到期就在这里作废（不再是只在 pumpPrefetch 里做，理由见
		// expireStalePrefetchMissing）。这里是取色的唯一入口，一定会被调到，因此不会漏。
		expireStalePrefetchMissing(now);
		// 除了"有成品要搬"，只要样例池里还有没扫完的洞穴批，也定期进来 drain 一下：
		// 否则上一帧提交、这一帧才扫完的结果要等下一个成品到货才收得到（见 applyCaveBatch）
		if (now >= nextPumpNanos && (!prefetchReady.isEmpty() || samplePool.hasPending())) {
			nextPumpNanos = now + PUMP_INTERVAL_NANOS;
			pumpPrefetch();
		}
		LevelChunk chunk = loadedChunk(chunkX, chunkZ);
		// 实时采样用精确显示层（逐格；见 LayerSelector#resolve）；
		// 落盘 / 后台预读 / 颜色缓存用归档层（固定 STORAGE_STEP 格一档；见 LayerSelector#storageLayer）。
		// 两者分开之后，图面能逐格更新，而目录数与后台读盘量不随高度线性增长。
		int storageKey = LayerSelector.storageLayer(layer);
		ClientLevel level = minecraft.level;

		if (chunk != null && level != null && LayerSelector.isCave(layer)) {
			// 已加载的洞穴区块：走"按列切片"这条路。换层时只重扫跨过洞穴口袋边界的那几列，
			// 其余列直接沿用——这是逐格跟着高度走却不卡的关键。
			// 这里传精确显示层：图面要逐格跟着玩家高度变。
			CaveSlice slice = caveSlice(chunkX, chunkZ);
			if (slice.layer != layer && sliceSpent()) {
				// 这一轮预算用尽，留到下一帧再扫
				return null;
			}
			builtThisPass++;
			int[] colors = caveColorsForChunk(level, chunkX, chunkZ, layer);
			// 落盘按归档层（固定 STORAGE_STEP 格一档；见 LayerSelector#storageLayer）：
			// 同一档只写一次（MapStorage#save 按归档键去重），读回来时也按归档键，于是本局写过的
			// 东西总能查回来。像素本身仍是按精确显示层画的（上面那次采样），不受这里影响。
			persist(chunkX, chunkZ, storageKey, slice.record);
			return colors;
		}

		// 原始数据是唯一依据，颜色由它派生（见 SurfaceSample 的说明）。
		// 所以先找原始数据：找到就（必要时）重新着色，颜色缓存丢了也不必重采世界。
		SurfaceSample sample = surfaceSample(chunkX, chunkZ, storageKey);
		if (sample != null) {
			return sample.colors(passSkyDarken, sampleBand(storageKey));
		}
		if (level == null) {
			return null;
		}

		if (chunk == null) {
			// 区块没加载。先看磁盘上有没有以前探索过的数据：位图说"没写过"就直接当作未探明，
			// 这种判定不碰磁盘，因此不吃预算——否则一屏上万个没写过的区块会把预算全吃光。
			//
			// 但"自己没写过"不等于"整个世界都没有数据"：世界存档里本来就有全部已生成区块的
			// 地形。以前这里直接 return null，等于把世界存档那条路整片挡在门外——已加载范围之外
			// 永远画不出来，那一圈的形状正好是加载范围的形状（看着就是一圈锯齿），外面则是一片
			// 半透明黑。现在只有"世界存档里连这块的区域文件都没有"才算真的没有。
			//
			// 看的是别的维度时，世界存档问的是<b>正在查看的那个维度</b>（见 #dataDimension），
			// 不是玩家当前维度的区域文件——因此"自己没写过"绝不能直接把这条路挡掉，
			// 否则切过去一页都不建（只有转圈、什么都不画）。
			boolean foreign = foreignView();
			String regionDimension = dataDimension();
			if (storage != null && storage.isKnownAbsent(chunkX, chunkZ, storageKey)
					&& (regionDimension == null
							|| !WorldSaveChunks.hasRegion(minecraft, regionDimension, chunkX, chunkZ))) {
				return null;
			}
			if (sliceSpent()) {
				// 预算用尽：这一帧先当"还没有数据"，下一帧接着补
				return null;
			}
			// 磁盘那份也按归档层存/查（见上面落盘那段的说明），自己写过的东西才能查回来
			if (foreign) {
				// 看的是别的维度：先读自己存过的那份（当场读，切过去要能立刻看到）；
				// 自己没存过也没有关系——单人/局域网主机的世界存档里就有那个维度的地形，
				// 同样交给后台按"正在查看的维度"去读（见 #enqueuePrefetch 与 #dataDimension）。
				int[] own = loadOwnCache(chunkX, chunkZ, storageKey);
				if (own != null) {
					return own;
				}
				enqueuePrefetch(chunkX, chunkZ, storageKey);
				return null;
			}
			long key = ChunkColorCache.key(chunkX, chunkZ, storageKey);
			if (prefetchMissing.contains(key)) {
				// 两边都找过、确实没有这一块：别再排了
				return null;
			}
			if (prefetchQueued.contains(key)) {
				// 已经排给后台了，就安心等它：这一帧先当"还没有数据"
				return null;
			}
			// 自己存档里存过的、与世界存档里有的，都交给后台线程去读：
			// <b>读文件与解压都在那边做完</b>（见 {@link #enqueuePrefetch}），渲染线程这一帧先当
			// "还没有数据"，下一帧成品到了直接收（见 {@link #pumpPrefetch}）。
			// 以前"自己那份"是在渲染线程上当场解的，回到走过的区域时会有一阵明显的掉帧。
			enqueuePrefetch(chunkX, chunkZ, storageKey);
			return null;
		}

		if (sliceSpent()) {
			// 只是预算用尽，不是没有数据：下一帧接着补
			return null;
		}
		builtThisPass++;

		ChunkRecord record = sampleSurface(level, chunkX, chunkZ);
		persist(chunkX, chunkZ, storageKey, record);
		return rememberSurfaceSample(chunkX, chunkZ, storageKey, record)
				.colors(passSkyDarken, sampleBand(storageKey));
	}

	/**
	 * 某一层着色时该用的切面着色参数：洞穴层看 {@link #caveShading}，地表用
	 * {@link Integer#MIN_VALUE}（不按切面处理）。
	 */
	private int sampleBand(int layer) {
		return LayerSelector.isCave(layer) ? caveShading() : Integer.MIN_VALUE;
	}

	/**
	 * 取一份原始数据；拿到的层不是落盘档时退回归档层。
	 *
	 * <p>存档那份是按<b>归档层</b>（{@link LayerSelector#STORAGE_STEP} 格一档）存的，而显示层逐格变，
	 * 所以调用方若直接拿精确显示层来查，会查不到、于是每次都要重读一遍区域文件。这里兜一次底。
	 */
	private SurfaceSample surfaceSample(int chunkX, int chunkZ, int layer) {
		// 命中即把键挪到队尾（访问序 LRU；见 #surfaceSamples 的字段说明）
		SurfaceSample sample = surfaceSamples.getAndMoveToLast(ChunkColorCache.key(chunkX, chunkZ, layer));
		if (sample == null && LayerSelector.isCave(layer)) {
			sample = surfaceSamples.getAndMoveToLast(
					ChunkColorCache.key(chunkX, chunkZ, LayerSelector.storageLayer(layer)));
		}
		return sample;
	}

	/** 记下原始数据，供昼夜换档时只重算颜色；返回刚记下的那一份。 */
	private SurfaceSample rememberSurfaceSample(int chunkX, int chunkZ, int layerY, ChunkRecord record) {
		SurfaceSample sample = new SurfaceSample(record, viewDimensionHasCeiling());
		// 新记下的这份视作"刚用过"，落在队尾（访问序 LRU；见 #surfaceSamples）
		surfaceSamples.putAndMoveToLast(ChunkColorCache.key(chunkX, chunkZ, layerY), sample);
		// 超上限就从队首丢最久没用过的那份（原来的 LinkedHashMap 迭代器删的也是队首）
		while (surfaceSamples.size() > MAX_SURFACE_SAMPLES) {
			surfaceSamples.removeFirst();
		}
		return sample;
	}

	/**
	 * 取走"地形被改过"的区块（打包成 {@code chunkX << 32 | chunkZ}），界面据此把对应瓦片
	 * <b>先涂成未探明再重填</b>。取走即清空：同一处改动只需重画一次，两个界面谁先取到谁作废。
	 */
	public Set<Long> drainChangedChunks() {
		if (changedChunks.isEmpty()) {
			return Set.of();
		}
		Set<Long> drained = new HashSet<>(changedChunks);
		changedChunks.clear();
		return drained;
	}

	/**
	 * 取走"后台刚读到一份、地图还没有"的区块，界面据此把那几格补画上。
	 *
	 * <p>与 {@link #drainChangedChunks()} 分开：那一份是<b>确定过期</b>（得先涂掉），这一份只是"数据到了"，
	 * <b>已经画着地形的那一格不用动</b>。以前两者并成一条，后台每读到一块就把画好的地方涂掉再重画，
	 * 看起来就是"已经加载好的地方又加载了一遍"。
	 */
	public Set<Long> drainArrivedChunks() {
		if (arrivedChunks.isEmpty()) {
			return Set.of();
		}
		Set<Long> drained = new HashSet<>(arrivedChunks);
		arrivedChunks.clear();
		return drained;
	}

	/** 记下一处改动。打包成 {@code chunkX << 32 | chunkZ}，与 {@link #drainChangedChunks()} 一致。 */
	private void markChanged(int chunkX, int chunkZ) {
		changedChunks.add((long) chunkX << 32 | chunkZ & 0xFFFFFFFFL);
		// 只有"改动检测"这条路会调它：既然真有改动，就把检测的退避打回基准
		// （见 {@link #REFRESH_IDLE_MAX_NANOS}）。
		refreshSawChange = true;
	}

	/** 记下"这一块的数据刚到手"。打包方式与 {@link #drainArrivedChunks()} 一致。 */
	private void markArrived(int chunkX, int chunkZ) {
		arrivedChunks.add((long) chunkX << 32 | chunkZ & 0xFFFFFFFFL);
	}

	/** 方块更新钩子（{@code LevelBlockChangeMixin}）的入口：把改动过的区块记下来，等渲染线程去重采。 */
	public static void recordBlockChange(int chunkX, int chunkZ) {
		ClientChunkColorProvider provider = active;
		if (provider != null) {
			provider.pendingBlockChanges.add((long) chunkX << 32 | chunkZ & 0xFFFFFFFFL);
		}
	}

	/**
	 * 定期重新采样玩家四周已经加载的区块，把"地形被改过、地图还画着旧样子"的地方捡回来。
	 *
	 * <p>地图只在区块<b>第一次</b>采样时记录地形，之后被改动（搭的房子、爆炸、别人建的）并不知道，
	 * 一直显示老样子。这里不监听方块更新事件，而是<b>定期重采一遍再逐列比较</b>：不依赖改动来自哪里，
	 * 单人、多人、爆炸、机械都一样管用；只有真的不一样时才更新缓存、重写像素、重排落盘。按时间预算
	 * 分帧，活动范围只在玩家四周。
	 *
	 * <p><b>每帧只跑一次：</b>两个界面同一帧都会各自调一次，扫描进行中两处各能花掉最多
	 * {@link #REFRESH_BUDGET_NANOS}。现在按 {@link #frameSerial} 认帧：本帧已跑过就直接返回，第二个界面
	 * 只去 {@link #drainChangedChunks()} / {@link #drainArrivedChunks()} 取结果。这道闸成立的前提是帧号
	 * 每帧前进，所以全屏地图每帧还调 {@link #beginFrameIfStale(long)} 兜底。
	 *
	 * <p><b>一轮之内由中心（玩家）向外查</b>，见 {@link #refreshSweep}：一轮几十秒、每帧只推进一两块，
	 * 只有"开头就是玩家周围"才能保证最该更新的那一圈每轮都被查到。
	 */
	public void refreshLoadedChunks(long nowNanos) {
		// 先到先得：本帧已经跑过一次，同帧后续调用直接放手（不改 lastRefreshNanos，退避语义照旧）
		if (refreshFrameSerial == frameSerial) {
			return;
		}
		refreshFrameSerial = frameSerial;
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null || foreignView()) {
			// 看的是别的维度时整轮改动检测都不跑：它采样的是当前世界，与看的那一层无关
			return;
		}
		refreshSkyDarken();
		// 方块变化是最准的信号：钩子登记了哪一块就只重采那一块
		refreshBlockChanges();
		// 先查脚下这一圈：主扫描转回来要十几秒，身边改的方块等不起
		refreshNear(nowNanos);
		if (nowNanos - lastRefreshNanos < refreshIntervalNanos) {
			return;
		}

		int radius = Math.max(REFRESH_RADIUS_FALLBACK, minecraft.options.getEffectiveRenderDistance());
		int side = radius * 2 + 1;
		int baseX = CoordinateSpaces.blockToChunk(player.getBlockX()) - radius;
		int baseZ = CoordinateSpaces.blockToChunk(player.getBlockZ()) - radius;
		if (refreshSweep == null || refreshSweepSide != side) {
			// 第一次跑、或视距变了：按新窗口重建遍历器（中心 = 玩家的偏移，见 #refreshSweep）
			refreshSweep = new CenterOutSweep(side, side, radius, radius);
			refreshSweepSide = side;
			refreshBaseChunkX = baseX;
			refreshBaseChunkZ = baseZ;
		} else if (baseX != refreshBaseChunkX || baseZ != refreshBaseChunkZ) {
			// 玩家换了区块：窗口整体挪了一格，旧进度不再对应同一批区块，从新中心重新扫起
			refreshBaseChunkX = baseX;
			refreshBaseChunkZ = baseZ;
			refreshSweep.reset();
		}

		long deadline = System.nanoTime() + REFRESH_BUDGET_NANOS;
		// 由中心（玩家）向外：一轮的开头就是玩家周围那一圈，见 #refreshSweep 的说明。
		// 中心取的就是窗口正中，所以偏移恒在 [0, side) 内，不必再判 inRange。
		while (refreshSweep.hasNext()) {
			refreshChunkIfChanged(baseX + refreshSweep.x(), baseZ + refreshSweep.y());
			refreshSweep.advance();
			if (System.nanoTime() >= deadline) {
				return;
			}
		}
		// 一轮扫完，歇一会儿再扫
		lastRefreshNanos = nowNanos;
		refreshSweep.reset();
		// 退避：一整圈都没发现"真的变了"的区块，就把间隔翻倍；一旦发现改动，立刻回到基准。
		// 站在原地看着地图时，周围几百个区块里绝大多数根本没被改过，却要整圈重采一遍才能确认
		// ——那是纯粹的浪费（每 2 秒一次全圈扫描）。走动时每隔几秒就会踩到新加载的区块，
		// 退避立刻被打回基准，所以不会漏掉"走到新区块"这种真改动。
		if (refreshSawChange) {
			refreshSawChange = false;
			refreshIntervalNanos = REFRESH_INTERVAL_NANOS;
		} else {
			refreshIntervalNanos = Math.min(refreshIntervalNanos * 2, REFRESH_IDLE_MAX_NANOS);
		}
	}

	/**
	 * 重采方块更新钩子登记过的区块，每帧最多花 {@link #BLOCK_CHANGE_BUDGET_NANOS}。
	 *
	 * <p>剩下的留在集合里下一帧继续，不丢；顺序不保证，但一帧只处理得了几块，够用。
	 */
	private void refreshBlockChanges() {
		if (pendingBlockChanges.isEmpty()) {
			return;
		}
		long deadline = System.nanoTime() + BLOCK_CHANGE_BUDGET_NANOS;
		for (Iterator<Long> iterator = pendingBlockChanges.iterator(); iterator.hasNext(); ) {
			long packed = iterator.next();
			iterator.remove();
			refreshChunkIfChanged((int) (packed >> 32), (int) (long) packed);
			if (System.nanoTime() >= deadline) {
				return;
			}
		}
	}

	/**
	 * 玩家附近一圈的快速重采。
	 *
	 * <p>主扫描一圈要覆盖整个视距，轮回到玩家脚下可能要十几秒；而脚下的方块一改就该立刻看到。
	 * 这里每 {@link #NEAR_REFRESH_INTERVAL_NANOS} 把玩家周围 3×3 区块重采一遍，判断与处置仍走
	 * {@link #refreshChunkIfChanged}，真的变了才重画。
	 */
	private void refreshNear(long nowNanos) {
		LocalPlayer player = minecraft.player;
		if (player == null) {
			return;
		}
		int centerX = CoordinateSpaces.blockToChunk(player.getBlockX());
		int centerZ = CoordinateSpaces.blockToChunk(player.getBlockZ());
		int side = NEAR_RADIUS * 2 + 1;
		if (nearSweep == null || centerX != nearCenterChunkX || centerZ != nearCenterChunkZ) {
			nearCenterChunkX = centerX;
			nearCenterChunkZ = centerZ;
			nearSweep = new CenterOutSweep(side, side, NEAR_RADIUS, NEAR_RADIUS);
			nearRunning = true;
		}
		if (!nearRunning) {
			if (nowNanos < nearNextNanos) {
				return;
			}
			nearRunning = true;
			nearSweep.reset();
		}
		long deadline = System.nanoTime() + NEAR_BUDGET_NANOS;
		while (nearSweep.hasNext()) {
			refreshChunkIfChanged(centerX + nearSweep.x(), centerZ + nearSweep.y());
			nearSweep.advance();
			if (System.nanoTime() >= deadline) {
				return;
			}
		}
		nearRunning = false;
		nearNextNanos = nowNanos + NEAR_REFRESH_INTERVAL_NANOS;
	}

	/**
	 * 每秒最多把几个"本局还没采过"的块当成新数据报给界面，见 {@link #refreshChunkIfChanged}。
	 *
	 * <p>走动时每秒新加载的区块只有几十个，正常碰不到；刚进世界时会有成百上千块，靠它摊平。
	 */
	private static final int MAX_NEW_CHUNKS_PER_SECOND = 256;

	private long lastNewChunkNanos;
	private int newChunksThisSecond;

	/**
	 * 重采一个区块；和缓存里那份不一样才更新。
	 *
	 * <p><b>本局还没采过的区块也处理</b>（采样 + 报"新数据到了"），只是有每秒上限——它们是"走到没加载
	 * 过的地方"时唯一能被补上的来源。以前直接跳过，代价就是"走到新区块、地图不更新"。
	 */
	private void refreshChunkIfChanged(int chunkX, int chunkZ) {
		ClientLevel level = minecraft.level;
		if (level == null || loadedChunk(chunkX, chunkZ) == null) {
			return;
		}
		if (LayerSelector.isCave(layer)) {
			refreshCaveChunk(level, chunkX, chunkZ);
			return;
		}
		SurfaceSample previous = surfaceSample(chunkX, chunkZ, layer);
		if (previous == null) {
			// 本局还没采过这一块：把它当作"新到的数据"报给界面（采样 + markChanged），
			// 否则"走到没加载过的区块、地图不更新"——那些块正是"没基准"的。
			//
			// 但必须限流：刚进世界时加载范围内成百上千块都属于这一类，全报一遍就是"进存档卡顿"
			// （小地图每秒重合成 25 次 + 每块各排一次落盘）。限成每秒 新块上限 个，
			// 走动时的量远小于这个上限，进世界时的洪峰则被摊平。
			long nowNanos = System.nanoTime();
			if (nowNanos - lastNewChunkNanos >= 1_000_000_000L) {
				lastNewChunkNanos = nowNanos;
				newChunksThisSecond = 0;
			}
			if (newChunksThisSecond >= MAX_NEW_CHUNKS_PER_SECOND) {
				return;
			}
			newChunksThisSecond++;
			ChunkRecord fresh = sampleSurface(level, chunkX, chunkZ);
			persist(chunkX, chunkZ, layer, fresh);
			rememberSurfaceSample(chunkX, chunkZ, layer, fresh);
			// 这也是"检测到新东西"：把退避打回基准（玩家正走进新区域）
			refreshSawChange = true;
			markArrived(chunkX, chunkZ);
			return;
		}
		ChunkRecord record = sampleSurface(level, chunkX, chunkZ);
		// 比原始数据，不比着色后的颜色。颜色里含昼夜衰减，白天存下的那份和晚上算出来的
		// 必然处处不同；而上面那句"先把昼夜对齐"只更新了本次的衰减值，并不能让缓存里已经
		// 着色好的颜色跟着变，于是每次换档都把玩家周围整片报成"地形被改过"——界面收到之后
		// 会先把这些区块涂成未探明再排队重填，表现出来就是换档、打开地图时周围一圈暗块。
		//
		// 原始数据与昼夜无关，比它才能只挑出真正被改过的区块。
		boolean changed = !previous.record.sameContent(record);
		// 不论变没变，原始数据都换成刚采的这份；颜色会按当前天色按需重算（见 SurfaceSample）
		rememberSurfaceSample(chunkX, chunkZ, layer, record);
		if (!changed) {
			return;
		}
		persistAgain(chunkX, chunkZ, LayerSelector.storageLayer(layer), record);
		markChanged(chunkX, chunkZ);
	}

	/**
	 * 洞穴层的改动检测。
	 *
	 * <p>切片按列复用，只有整列重扫才能看出哪列被改过——所以把所有列的适用范围一并作废、重扫再逐列
	 * 比较。相邻列会因为光照变化跟着变，所以不做逐列增量，直接整体重算最稳妥。
	 */
	private void refreshCaveChunk(ClientLevel level, int chunkX, int chunkZ) {
		CaveSlice slice = caveSlices.getAndMoveToLast((long) chunkX << 32 | chunkZ & 0xFFFFFFFFL);
		if (slice == null || slice.layer == Integer.MIN_VALUE) {
			// 本局还没扫过这一块：与地表那条路同理，没有基准就谈不上"被改过"。
			// 以前这里会就地扫一遍并报成"改了"，刚进世界（或在洞里刚进世界）时加载范围内全是这种，
			// 于是几百个区块一起报改动——那正是进世界那一下的卡顿来源。
			return;
		}
		int[] before = slice.record.toColors(REFERENCE_Y, 0);
		Arrays.fill(slice.validLow, Short.MAX_VALUE);
		Arrays.fill(slice.validHigh, Short.MIN_VALUE);
		slice.layer = Integer.MIN_VALUE;
		caveColorsForChunk(level, chunkX, chunkZ, layer);
		// 两份都按固定光照（衰减 0）着色再比。洞穴里天空光多为 0，两种衰减算出来本是一样的，
		// 但洞口、竖井那一片有天空光，直接比着色结果会把"天黑了"当成地形改动——同
		// {@link #refreshChunkIfChanged}，那会让界面把整片涂成未探明再重填。
		if (Arrays.equals(before, slice.record.toColors(REFERENCE_Y, 0))) {
			return;
		}
		// 落盘按归档层（与实时采样那条落盘路同一个键，见 #colorsForChunk）
		persistAgain(chunkX, chunkZ, LayerSelector.storageLayer(layer), slice.record);
		markChanged(chunkX, chunkZ);
	}

	/**
	 * 现在该显示哪一层：地表，或洞穴切面所在的高度。
	 *
	 * <p>放在这里而不是两个界面各算一遍：地表高度怎么取、洞穴层高怎么定，两处必须一致，否则会出现
	 * "小地图在地表、世界地图在洞穴"。
	 *
	 * <p>对外返回的是<b>去抖后</b>的显示层，它同时被界面拿去当瓦片页键，所以必须<b>稳</b>：候选高度
	 * 每帧都可能逐格变，直接返回就会每格换一次页键、画面一闪一闪。两级去抖：
	 * <ul>
	 *   <li><b>地表 ↔ 洞穴</b>（类型变化，整屏换一套瓦片）：候选<b>归档层</b>（见
	 *       {@link LayerSelector#cacheLayer}）要连续保持 {@link #KIND_SWITCH_DELAY_NANOS}；</li>
	 *   <li><b>洞穴内部</b>（只是精确高度变）：走 {@link LayerSelector.CaveDisplayFilter} 的
	 *       "差 1 格就参与判定 + 保持时间"，原地来回不换、持续升降才跟。</li>
	 * </ul>
	 */
	public int resolveLayer(LayerMode mode) {
		long now = System.nanoTime();
		// 换世界 / 换维度：关卡对象变了就把全部去抖状态清零。否则会拿上一个世界的"当前层"当基点、
		// 或让地表↔洞穴的 900ms 计时带着旧世界的时间继续跑（表现为进新世界后头几帧层不对）。
		ClientLevel level = minecraft.level;
		if (level != layerLevel) {
			layerLevel = level;
			viewLayer = UNSET_LAYER;
			liveCaveTop = LayerSelector.SURFACE;
			pendingLayer = UNSET_LAYER;
			caveDisplayFilter.reset();
		}
		int wanted = wantedLayer(mode);
		// 类型判定用归档层（16 格一档）：拿精确高度判类型会坏掉——洞里每下一格高度都在变，
		// 候选层于是永远"和当前不同类"、计时一直重来，层就永远不更新（图冻在进洞那一刻）。
		// 洞穴内部的精确高度去抖不走这条，见下面 isSameKind 分支。
		int wantedKey = LayerSelector.cacheLayer(wanted);
		if (viewLayer == UNSET_LAYER) {
			viewLayer = wantedKey;
			liveCaveTop = wanted;
			return displayLayer();
		}
		// 站上基岩顶、或天花板维度里"头顶有没有盖"变了，都是明确的位置变化、不会来回抖，
		// 直接切过去，不必等那 900ms 的类型去抖。
		if (atCeilingRoof() || (mode == LayerMode.AUTO && !foreignView() && viewDimensionHasCeiling()
				&& !LayerSelector.isSameKind(viewLayer, wantedKey))) {
			viewLayer = wantedKey;
			liveCaveTop = wanted;
			pendingLayer = UNSET_LAYER;
			caveDisplayFilter.reset();
			return displayLayer();
		}
		// 眼睛在水下时保持原层、整帧不换：见 wantedLayer 里 underwater 的说明。
		// 连"洞穴内部逐格跟随"一起停掉——在水里本来就不该动层；出水后计时重来，该切的层照样会切。
		if (underwater(minecraft.player)) {
			pendingLayer = UNSET_LAYER;
			caveDisplayFilter.reset();
			return displayLayer();
		}
		// 同类（地表↔地表 / 洞穴↔洞穴）：不做 900ms 的类型去抖——那套是给"整屏换一套瓦片 +
		// 缩放过场"用的，同类变化根本不是那种切换。
		if (LayerSelector.isSameKind(viewLayer, wantedKey)) {
			viewLayer = wantedKey;
			pendingLayer = UNSET_LAYER;
			if (LayerSelector.isCave(wantedKey)) {
				// 洞穴内部：候选层是精确高度、逐格都可能变。直接逐帧跟随就会"每格换键 → 一闪一闪"
				// （玩家上下微动时尤甚），所以过一层"保持时间"：差 1 格也算变化，但方向要不变地
				// 连续保持满才换——原地来回不换，持续升降逐格跟。
				liveCaveTop = caveDisplayFilter.settle(liveCaveTop, wanted, now);
			}
			return displayLayer();
		}
		// 类型不同：候选层要连续保持完 {@link #KIND_SWITCH_DELAY_NANOS} 才真的切过去
		if (wantedKey != pendingLayer) {
			pendingLayer = wantedKey;
			pendingLayerSinceNanos = now;
			return displayLayer();
		}
		if (now - pendingLayerSinceNanos < KIND_SWITCH_DELAY_NANOS) {
			return displayLayer();
		}
		viewLayer = wantedKey;
		liveCaveTop = wanted;
		// 进 / 出洞穴：洞穴内部那段去抖状态一并清零，免得带进新的一段
		caveDisplayFilter.reset();
		pendingLayer = UNSET_LAYER;
		return displayLayer();
	}

	/** 对外的"显示层"：地表就是地表；洞穴是去抖后的精确高度（见 {@link #liveCaveTop}）。 */
	private int displayLayer() {
		return LayerSelector.isCave(viewLayer) ? liveCaveTop : LayerSelector.SURFACE;
	}

	/**
	 * 地表 ↔ 洞穴这种<b>类型</b>变化的去抖：候选层要连续保持一段时间才真的切过去。
	 *
	 * <p>差别在<b>来回走</b>的时候：站在洞口或分界线上来回挪一步，瞬时切换就会一步一切、切得眼花，
	 * 每次切换还要重切一遍剖面；加这段延迟后，来回抖动的时间根本不够，层就不动。
	 *
	 * <p>洞穴<b>内部</b>的换层不归它管，另由 {@link LayerSelector.CaveDisplayFilter} 处理（同类型、
	 * 不整屏换瓦片，用不着这么长，但也不能完全不去抖）。
	 */
	private static final long KIND_SWITCH_DELAY_NANOS = 900_000_000L;

	/**
	 * "还没定过"的哨兵。
	 *
	 * <p><b>不能用 {@link LayerSelector#SURFACE}</b>（它是 {@code Integer.MIN_VALUE}，同时是合法的"地表"）：
	 * 拿它当哨兵的话，在地表时判定会一直走"第一次判定"那条路，<b>切换延迟形同虚设</b>。
	 */
	private static final int UNSET_LAYER = Integer.MAX_VALUE;

	/**
	 * 当前视图落在哪个<b>归档层</b>（16 格一档）。它只用来判"地表 ↔ 洞穴"这种类型，
	 * 去抖见 {@link #resolveLayer}；洞穴内部的精确层高去抖则在 {@link #liveCaveTop} 上做。
	 */
	private int viewLayer = UNSET_LAYER;

	/**
	 * 洞穴视图下当前<b>显示</b>的精确层高；地表时无意义。
	 *
	 * <p>它<b>不是</b>候选高度（{@link LayerSelector#resolve}）——候选每帧都可能逐格变，直接显示就会
	 * "每格换键、一闪一闪"；这里放的是经 {@link #caveDisplayFilter} 去抖后的结果。
	 */
	private int liveCaveTop = LayerSelector.SURFACE;

	/** 洞穴显示层的"死区 + 保持时间"去抖；见 {@link LayerSelector.CaveDisplayFilter}。 */
	private final LayerSelector.CaveDisplayFilter caveDisplayFilter = new LayerSelector.CaveDisplayFilter();

	/**
	 * {@link #resolveLayer} 上一次看到的关卡。关卡对象变了时用它把全部去抖状态清零，免得把上一个世界的
	 * "当前层"或跑到一半的保持时间带进新世界。
	 */
	private ClientLevel layerLevel;

	private int pendingLayer = UNSET_LAYER;
	private long pendingLayerSinceNanos;

	/** 按当前地形算出"本该显示哪一层"，不含去抖。 */
	private int wantedLayer(LayerMode mode) {
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null) {
			return LayerSelector.SURFACE;
		}
		int caveTop = LayerSelector.caveStart(player.getBlockY());
		// 天花板维度（地狱）：地表高度给的是基岩顶，玩家永远"低于地表"，拿它判层只会一直停在切面上。
		// 改看头顶有没有盖：周围一小圈还露着天、或者头顶有直通到顶的空柱，就按地表画；被实心方块盖住才算在洞里。
		// 于是在基岩顶上往下挖竖井时，头顶一直通着，图就一直是那一层，不会跟着越挖越深。
		if (mode != LayerMode.SURFACE && viewDimensionHasCeiling() && !foreignView()) {
			// ① 已经进到基岩顶这一层（或站到上面）：地表数据在天花板下面，画出来是"基岩下的地形"；
			//    改用玩家所在高度的切面，扫到的地板就是基岩本身
			if (atCeilingRoof()) {
				return caveTop;
			}
			// ② 还在这层下方、但已经贴得很近：切面窗口会探进基岩里扫成一张平图，改看地表
			boolean nearRoof = player.getBlockY() >= ceilingRoofY() - CEILING_SURFACE_MARGIN;
			if (mode == LayerMode.CAVE) {
				return nearRoof ? LayerSelector.SURFACE : caveTop;
			}
			// ③ 自动：头顶有没有盖决定用切面还是地表，见 underRoof
			return underRoof() && !nearRoof ? caveTop : LayerSelector.SURFACE;
		}
		// 用"不含树叶"的高度图：WORLD_SURFACE 把树冠算进去，站在树下时地表高度会凭空高出一截，
		// 于是被误判成已进入地下，在树下来回走就会频繁切层
		int surfaceHeight = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
				player.getBlockX(), player.getBlockZ()) - 1;
		return LayerSelector.resolve(mode, player.getY(), surfaceHeight, caveTop);
	}

	/** 玩家所在列的基岩顶高度：{@code WORLD_SURFACE} 在这类维度里给的就是天花板。 */
	private int ceilingRoofY() {
		return minecraft.level.getHeight(Heightmap.Types.WORLD_SURFACE,
				minecraft.player.getBlockX(), minecraft.player.getBlockZ()) - 1;
	}

	/** 玩家是不是已经站进基岩顶这一层（或站到它上面），见 {@link #wantedLayer}。 */
	private boolean atCeilingRoof() {
		if (!viewDimensionHasCeiling() || minecraft.player == null || minecraft.level == null) {
			return false;
		}
		return minecraft.player.getBlockY() >= ceilingRoofY() - CEILING_ROOF_DEPTH;
	}

	/** 基岩顶本身有多厚（格）：原版占 123~127 五层，取 4 即"站到最上面那层就算进了基岩层"。 */
	private static final int CEILING_ROOF_DEPTH = 4;

	/** 天花板维度里玩家离顶部多近（还没进基岩层时）就改看地表（格），见 {@link #wantedLayer}。 */
	private static final int CEILING_SURFACE_MARGIN = 10;

	/** 自动分层判"头顶有没有盖"的半径（方块），见 {@link #underRoof()}。 */
	private static final int ROOF_CHECK_RADIUS = 2;

	/** {@link #underRoof()} 的结果缓存多久（纳秒）。 */
	private static final long ROOF_CACHE_NANOS = 150_000_000L;

	private boolean roofKnown;
	private boolean roofCovered;
	private int roofX = Integer.MIN_VALUE;
	private int roofY;
	private int roofZ;
	private long roofCheckedNanos;

	/**
	 * 玩家头顶有没有被实心方块盖住，自动分层用。
	 *
	 * <p>周围一小圈里任意一列：看得见天（天空光满格），或者从玩家这一层往上有一条直通到顶的空柱，
	 * 就算露天。地狱没有天空光，实际起作用的是那条空柱——所以原地上下挖不会来回切层，
	 * 只有头顶那一列真的被挖通、或被盖住时才会变。
	 */
	private boolean underRoof() {
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null) {
			return false;
		}
		long now = System.nanoTime();
		int blockX = player.getBlockX();
		int blockY = player.getBlockY();
		int blockZ = player.getBlockZ();
		if (roofKnown && blockX == roofX && blockY == roofY && blockZ == roofZ
				&& now - roofCheckedNanos < ROOF_CACHE_NANOS) {
			return roofCovered;
		}
		roofKnown = true;
		roofX = blockX;
		roofY = blockY;
		roofZ = blockZ;
		roofCheckedNanos = now;
		roofCovered = roofCovered(level, blockX, blockY, blockZ);
		return roofCovered;
	}

	/** 从玩家头顶那一层开始，一圈圈往外查：有一列通到顶上就返回 false。 */
	private boolean roofCovered(ClientLevel level, int centerX, int playerY, int centerZ) {
		int eyeY = playerY + 1;
		int maxY = level.getMaxY() - 1;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int ring = 0; ring <= ROOF_CHECK_RADIUS; ring++) {
			for (int dx = -ring; dx <= ring; dx++) {
				for (int dz = -ring; dz <= ring; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
						continue;
					}
					int x = centerX + dx;
					int z = centerZ + dz;
					LevelChunk chunk = loadedChunk(x >> 4, z >> 4);
					if (chunk == null) {
						// 没加载：这一列不参与判断
						continue;
					}
					if (level.getBrightness(LightLayer.SKY, pos.set(x, eyeY, z)) >= 15) {
						return false;
					}
					int top = Math.min(level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1, maxY);
					if (!hasCeilingAbove(chunk, pos, x, eyeY, z, top)) {
						return false;
					}
				}
			}
		}
		return true;
	}

	/** 这一列从 {@code eyeY} 到 {@code top} 之间有没有能当"盖"的方块。 */
	private static boolean hasCeilingAbove(LevelChunk chunk, BlockPos.MutableBlockPos pos,
			int x, int eyeY, int z, int top) {
		for (int y = eyeY; y <= top; y++) {
			if (blocksRoof(chunk.getBlockState(pos.set(x, y, z)))) {
				return true;
			}
		}
		return false;
	}

	/** 能不能当"盖"：空气、流体、树叶、火把花草这些都不算。 */
	private static boolean blocksRoof(BlockState state) {
		return !state.isAir() && !state.liquid() && !state.is(BlockTags.LEAVES)
				&& !isSurfaceDecoration(state);
	}

	/**
	 * 天花板维度的切面按方块光着色；别的维度沿用原规则。
	 * 见 {@link ChunkRecord#toColors(int, int, int[], boolean, int)}。
	 *
	 * <p><b>为什么不用天空光</b>：地狱这类维度没有天空光，存档里的天空光只是"没有这张数组"时的
	 * 兜底值，按它算整张图是一片均匀的亮色、完全看不出哪里亮哪里暗。改按方块光后，火把、岩浆、
	 * 荧石附近亮起来、其余保持暗，明暗层次才和游戏里一致。
	 */
	private int caveShading() {
		return viewDimensionHasCeiling() ? ChunkRecord.BLOCK_LIGHT_BAND : Integer.MIN_VALUE;
	}

	/**
	 * 眼睛是不是在水下。
	 *
	 * <p><b>"水下被当成地下"的根因：</b>{@link #wantedLayer} 用的 {@code MOTION_BLOCKING_NO_LEAVES}
	 * 把<b>水面也算成"地面"</b>（水是挡移动的流体），于是在海里往下游几格，玩家 Y 就低于"地表高度" 3 格
	 * 以上，AUTO 模式以为已钻入地下、切成洞穴层。
	 *
	 * <p>它不参与高度计算，只用来"整帧不换层"（见 {@link #resolveLayer}）：水里保持原层，出水后照常切。
	 */
	private static boolean underwater(LocalPlayer player) {
		return player != null && player.isEyeInFluid(FluidTags.WATER);
	}

	/** 取已经加载好的区块；第三个参数为 true 会触发生成/加载，地图不能那么做。 */
	private LevelChunk loadedChunk(int chunkX, int chunkZ) {
		ClientLevel level = minecraft.level;
		if (level == null || foreignView()) {
			// 看的是别的维度：当前世界里的同坐标方块属于另一个维度，不能拿来当这一层的颜色
			return null;
		}
		return level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
	}

	/**
	 * 取某一格所在的那一节；区块为 {@code null}、或这一格落在建筑高度之外时给 {@code null}。
	 *
	 * <p>给 {@code null} 的地方一律退回逐格查询，调用方不必自己判边界。用途只有一个：用
	 * {@link LevelChunkSection#hasOnlyAir()}（O(1) 的缓存计数）把"整节都是空气"的 16 格一次跳过。
	 */
	private static LevelChunkSection sectionOf(LevelChunk chunk, int y) {
		if (chunk == null) {
			return null;
		}
		LevelChunkSection[] sections = chunk.getSections();
		int index = chunk.getSectionIndex(y);
		return index < 0 || index >= sections.length ? null : sections[index];
	}

	// ------------------------------------------------------------------
	// 后台预读
	//
	// 每个"没加载、也不在自己存档里"的区块，都要解世界存档的区域文件才能拿到颜色
	// （NBT + 区块解析，实测一块约 100~200 µs）。以前在渲染线程上做，于是刚进世界那几秒全在解盘、
	// 打开地图只能一格一格往上补。现在挪到后台线程：渲染线程只做"排队 + 把成品搬进缓存"两件便宜事。
	// ------------------------------------------------------------------

	/**
	 * 读世界存档的后台线程<b>池</b>。
	 *
	 * <p>并行条数由设置里的"读盘线程"决定：区域文件随机读、区块之间互不相干，成品只往无锁队列
	 * {@link #prefetchReady} 投递（见类头"线程模型"），多放几条是安全的。SSD 上并行读能把"问 → 数据到"
	 * 的延迟压得很低——这正是新视野铺开速度的下限；HDD 上队列本来就浅，多两条也只是回到串行。
	 * 瓶颈是每次读的寻道与解 NBT 开销，不是磁头本身。
	 *
	 * <p>优先级压低、守护线程：它是"有空就补"的活，不该跟游戏抢 CPU，进程退出也不该被卡住。
	 *
	 * <p>用 {@link ThreadPoolExecutor} 是为了让设置改动当场生效：{@link #syncPerformanceSettings} 直接改
	 * 核心/上限线程数，不必重进世界。
	 */
	private final ThreadPoolExecutor prefetcher;

	/**
	 * 洞穴"选列"用的并行池：读回来的区块 NBT 交给它去扫（{@code WorldSaveChunks#scanCaveColumns}）。
	 *
	 * <p>只做<b>不碰关卡</b>的纯计算，着色仍留在渲染线程（见 {@link WorldSaveChunks#colorCaveColumns}）。
	 * 提交与 drain 都在渲染线程上做（见 {@link #applyCaveBatch}）：提交一小批后立刻返回，扫完的由
	 * {@link SamplePool#drainCompleted} 非阻塞取走，绝不在渲染线程上等待。线程数同样跟设置走。
	 */
	private final SamplePool samplePool;

	/** 设置本体：性能项（线程数、每帧搬运量、时间预算）都从这里读。 */
	private final MinimapSettings settings;

	/** 已经排进后台的区块（键）。只由渲染线程读写，用原始类型 set 就够。 */
	private final LongOpenHashSet prefetchQueued = new LongOpenHashSet();

	/** 后台找过、世界存档里也确实没有的区块：记下来，别每次都再去排一遍。 */
	private final LongOpenHashSet prefetchMissing = new LongOpenHashSet();

	/**
	 * "区块还没生成到位"的块：<b>现在</b>没有数据，但那是临时的——等它生成出来就有了。
	 *
	 * <p>与 {@link #prefetchMissing} 的区别<b>只在还会不会再问</b>：这一类隔 {@value #UNREADY_RETRY_NANOS}
	 * 要再读一次。混进 {@code prefetchMissing} 就永远不问了，那一块会一直是空的（"加载了一点就不动了"）。
	 *
	 * <p>值 = 下次允许再问的时刻；查询一律走 {@code containsKey}：{@code get} 对没有的键返回
	 * {@code defaultReturnValue}（默认 0），而 0 不是可靠的"没有"哨兵。
	 */
	private final Long2LongOpenHashMap prefetchUnready = new Long2LongOpenHashMap();

	/** 未就绪那张表的条数上限，超了整表清掉：它只是"省一次读盘"，丢了不影响对错。 */
	private static final int MAX_UNREADY = 16384;

	/** 未就绪的块隔多久再去读一次；与 {@code WorldTilePages#RETRY_NANOS}（重试轮转）同一个节奏。 */
	private static final long UNREADY_RETRY_NANOS = 8_000_000_000L;

	/** 后台读好的成品，等渲染线程搬进缓存。<b>这是唯一的跨线程交接点</b>（无锁队列）。 */
	private final ConcurrentLinkedQueue<Prefetched> prefetchReady = new ConcurrentLinkedQueue<>();

	/**
	 * 一次最多排多少块。
	 *
	 * <p>排太多危险：交接的是<b>解好的区块 NBT</b>（一块上百 KB），后台读盘比渲染线程搬运快得多，
	 * 排深了就全堆在内存里等搬运。排太浅也不行：队列满时 {@link #enqueuePrefetch} 直接不排，那块要等
	 * 下一轮再问——视口铺开时就表现为"有的块永远在等"。取 {@value #MAX_PREFETCH_QUEUE} 是"多条读盘线程
	 * 同跑也不会立刻见底"的量，约四十兆内存，搬运速度足够把队列消化掉。
	 */
	private static final int MAX_PREFETCH_QUEUE = 256;

	// 一次搬运最多搬几块、最多花多久都是<b>设置项</b>（自动 128 块 / 3 ms），见 MinimapSettings#appliedPerFrame()、
	// #applyBudgetMillis()。搬一块要把后台读到的区块 NBT 收成逐列记录（逐列扫 + 取本色与群系染色）并排队落盘；
	// 磁盘 I/O 已在后台线程，这里只剩解析与取色。
	//
	// 不能写死块数：实测一秒钟只画得出约 1800 块，而"本秒问"常常上千、几乎全是"没定论"——数据读好了却搬不过来，
	// 同时 `预算停` 一直是 0，说明卡的不是填充的时间预算，正是这里。所以改成按时间取，嫌铺得慢就把这两项调大。

	/**
	 * 一轮最多提交多少块洞穴区块去后台"选列"。
	 *
	 * <p>要比 {@link MinimapSettings#appliedPerFrame()} 小得多：洞穴批是<b>异步</b>的
	 * （见 {@link #applyCaveBatch}），提交出去的任务要一直挂着直到后台扫完才被 drain。批开得越大，
	 * 同时挂在 {@link SamplePool} 里的区块 NBT 越多，既占内存、完成也更晚。
	 *
	 * <p><b>它同时是洞穴铺图速度的上限</b>：每 {@link #PUMP_INTERVAL_NANOS} 最多提交这么多块，
	 * 也就是每秒 {@code MAX_CAVE_SCAN_PER_PUMP / 8ms} 块。原先取 16 → 上限约 2000 块/秒，
	 * 实测洞穴里正好压在 1300~1800 块/秒上（一屏两万多块要十几秒）；
	 * 提到 32 把上限抬到约 4000 块/秒，同时挂着的 NBT 也就几兆。
	 */
	private static final int MAX_CAVE_SCAN_PER_PUMP = 32;

	/**
	 * 预读队列还装得下吗（留四分之一余量给"前沿卡住的那一块"的补问）。
	 *
	 * <p>铺图那条路据此决定"还要不要接着往前问"：队列满了再问也是被丢掉（见 {@link #enqueuePrefetch}），
	 * 白白算一遍路径。有这道闸，请求才会刚好铺满流水线而不是灌爆它。
	 */
	public boolean prefetchHasRoom() {
		return prefetchQueued.size() < MAX_PREFETCH_QUEUE * 3 / 4;
	}

	/**
	 * 两批搬运之间至少隔多久。
	 *
	 * <p>按时间而不是按调用次数限：取色入口在一帧里会被问成百上千次，按次数限一帧就能把整条交接队列搬空。
	 *
	 * <p>取 {@value #PUMP_INTERVAL_NANOS} ns（约半帧），一帧可以搬两批。每批开销由
	 * {@link MinimapSettings#applyBudgetMillis()} 兜住，提高频率不会让单次搬运变贵。
	 */
	private static final long PUMP_INTERVAL_NANOS = 8_000_000L;

	/** 下一次允许搬运的时刻，见 {@link #PUMP_INTERVAL_NANOS}。 */
	private long nextPumpNanos;

	/**
	 * 后台读好的一块的交接内容。
	 *
	 * <p>两个数据来源只会有一个非空：{@link #own} 是自己存档里已经存过的那一份（后台线程顺手解压好），
	 * {@link #read} 是世界存档那条路读出来的原始 NBT。两个都为 null 表示哪边都没有这一块。
	 */
	private record Prefetched(long key, int generation, int chunkX, int chunkZ, int layer,
			WorldSaveChunks.ChunkRead read, ChunkRecord own) {
	}

	/**
	 * 换存档的计数。
	 *
	 * <p>后台读一块要花上一小会儿，读到一半换了世界，这一份就是上一个存档的地形，搬进缓存就是"数据串了"。
	 * 所以排队时记下当时的计数，搬的时候不一致就丢掉。
	 */
	private int prefetchGeneration;

	/**
	 * 自己存档里的那份，<b>当场在渲染线程上读</b>。
	 *
	 * <p>只给"世界地图正在看别的维度"那条路用（那条路不走世界存档预读，数据也只可能在自己存档里）。
	 * 玩家所在维度的数据一律交给后台线程读（见 {@link #enqueuePrefetch}），渲染线程不碰文件。
	 *
	 * <p>按<b>归档层</b>查（{@link LayerSelector#storageLayer}）：实时采样与后台预读落盘都按归档层，
	 * 两边必须同一个键，否则本局写过的东西下一局读不回来、只能一遍遍重读世界存档。
	 */
	private int[] loadOwnCache(int chunkX, int chunkZ, int layer) {
		if (storage == null) {
			return null;
		}
		ChunkRecord record = storage.load(chunkX, chunkZ, layer);
		if (record == null) {
			return null;
		}
		SurfaceSample sample = rememberSurfaceSample(chunkX, chunkZ, layer, record);
		return sample.colors(passSkyDarken, sampleBand(layer));
	}

	/**
	 * 把一个区块的数据排进落盘队列。<b>落盘一律走这里</b>。
	 *
	 * <p><b>看的是别的维度时绝不写</b>：{@link #storage} 是按"当前查看的维度"打开的（见
	 * {@link #ensureStorage}），而"预读结果 / 洞穴批次"是更早排出去的任务，读的是<b>玩家所在维度</b>
	 * 的世界存档——两边一旦错位，主世界的地形就会被写进下界 / 末地的目录里。表现出来就是"从没去过
	 * 下界 / 末地，却在那两个维度的目录里有了地图数据"。采样那几条路各自已有
	 * {@link #foreignView()} 判定，这里再兜一道：凡是跨维度的写入，一条都出不去。
	 */
	private void persist(int chunkX, int chunkZ, int layer, ChunkRecord record) {
		if (storage == null) {
			return;
		}
		if (foreignView()) {
			logBlockedForeignWrite();
			return;
		}
		storage.save(chunkX, chunkZ, layer, record);
	}

	/** 同 {@link #persist}，但走"重新排队"那条（地形被改动过，同一局里要再写一次）。 */
	private void persistAgain(int chunkX, int chunkZ, int layer, ChunkRecord record) {
		if (storage == null) {
			return;
		}
		if (foreignView()) {
			logBlockedForeignWrite();
			return;
		}
		storage.resave(chunkX, chunkZ, layer, record);
	}

	/** 跨维度落盘被拦下时记一行（一整局只报一次）：再出现"没去过的维度却有数据"看这一行就知道拦住了。 */
	private boolean foreignWriteLogged;

	private void logBlockedForeignWrite() {
		if (!foreignWriteLogged) {
			foreignWriteLogged = true;
			LOGGER.info("拦下一次跨维度地图落盘 | 数据来源 {} | 正在查看 {}",
					minecraft.level == null ? "-" : minecraft.level.dimension().identifier(),
					viewDimension);
		}
	}

	/**
	 * 把这一块排给后台线程去读世界存档；已经排过、或已经确认没有过的就不再排。
	 *
	 * <p>分工：<b>渲染线程算路径、后台线程读文件</b>。路径要问存档根目录、按维度拼，有缓存（见
	 * {@code WorldSaveChunks#regionDirectory}），放渲染线程只是一次查表；后台线程拿到算好的路径，
	 * 开文件、解 NBT，全程不碰游戏状态。
	 *
	 * <p>解出的原始 NBT 丢进交接队列，由渲染线程收成记录（见 {@link #pumpPrefetch}）——缓存的写入者
	 * 始终只有渲染线程一个。
	 */
	private void enqueuePrefetch(int chunkX, int chunkZ, int layer) {
		long key = ChunkColorCache.key(chunkX, chunkZ, layer);
		if (prefetchMissing.contains(key)) {
			return;
		}
		if (prefetchQueued.size() >= MAX_PREFETCH_QUEUE) {
			// 被队列挡下的块：它们这一轮就当"还没定论"，要等下一轮再问（见 WorldTilePages 的补问）
			return;
		}
		// "还没生成到位"的那些：隔一会儿再问一次就够了。问得太勤的话后台线程会一直在重读
		// 同一批没生成的区块，把真正该读的挤在后面
		// 用 containsKey 而不是拿 get 的返回值去比：见 #prefetchUnready 的说明
		if (prefetchUnready.containsKey(key)) {
			if (System.nanoTime() < prefetchUnready.get(key)) {
				return;
			}
			prefetchUnready.remove(key);
		}
		// 路径按"正在查看的维度"拼：世界地图切到主世界时读的就是主世界的 region，不是玩家所在维度的
		String dimension = dataDimension();
		Path regionDir = dimension == null ? null : WorldSaveChunks.regionDirectory(minecraft, dimension);
		Path region = regionDir == null ? null : WorldSaveChunks.regionFileIn(regionDir, chunkX, chunkZ);
		WorldSaveChunks.reportPath(region);
		// 拿不到世界存档（纯客户端连远程服）不代表没得读：自己存档里可能就存过这一块，
		// 所以这里不再整条路都不排。两个来源都没有时，任务会报"两边都没有"。
		if (!prefetchQueued.add(key)) {
			return;
		}
		int generation = prefetchGeneration;
		// 存储实例可能在任务跑的时候被换掉（切维度/换世界），这里按排队时那一份来读：
		// RegionStore 对"关了再读"是安全的（会重新打开文件），读出来的结果再由渲染线程按计数丢弃
		MapStorage storageAtQueue = storage;
		prefetcher.execute(() -> {
			// 先看自己存档：数据就在本地，解一块只要几十微秒。**解压这一步也在这里做**，
			// 渲染线程因此连解压都不必等（见 pumpPrefetch 里的 own 分支）
			ChunkRecord own = null;
			if (storageAtQueue != null) {
				try {
					own = storageAtQueue.load(chunkX, chunkZ, layer);
				} catch (RuntimeException e) {
					// 自己那份读不动：退回世界存档那条路，别把这块整个丢掉
					own = null;
				}
			}
			WorldSaveChunks.ChunkRead read = own != null || region == null
					? null : WorldSaveChunks.readTag(region, chunkX, chunkZ);
			prefetchReady.add(new Prefetched(key, generation, chunkX, chunkZ, layer, read, own));
		});
	}

	/**
	 * {@link #prefetchMissing} 的"当时没有"能信多久。
	 *
	 * <p>世界存档里"没有这一块"常常是<b>临时的</b>（玩家走过去时服务端正在生成）：记成永久之后，等它
	 * 生成好了也永远不会再问，地图边界上就<b>一格一格缺着补不上</b>。
	 */
	private static final long MISSING_TTL_NANOS = 30_000_000_000L;

	/** 上一次清空"当时没有"记录的时刻，见 {@link #MISSING_TTL_NANOS}。 */
	private long missingClearedAtNanos;

	/**
	 * "当时没有"的记录到期就整体作废，让临时没有的块有机会再被问一次（见 {@link #MISSING_TTL_NANOS}）。
	 *
	 * <p><b>必须挂在"一定会被调到"的地方。</b>原先只在 {@link #pumpPrefetch} 里，而那个方法只有"后台
	 * 还有成品要搬、或样例池还有活"时才被调用；预读队列一空它就再也不跑，{@link #prefetchMissing} 就
	 * 再也不到期清空——一次"当时没有"变成<b>永久</b>：{@link #isKnownEmpty} 一直为真、
	 * {@link #enqueuePrefetch} 也拒绝再排，连 {@link #retryUnexplored} 都跳过。现在改由
	 * {@link #colorsForChunk} 这个唯一入口每帧带一遍。
	 */
	private void expireStalePrefetchMissing(long nowNanos) {
		if (nowNanos - missingClearedAtNanos >= MISSING_TTL_NANOS) {
			missingClearedAtNanos = nowNanos;
			prefetchMissing.clear();
		}
	}

	/** 把后台读好的成品收成记录、塞进缓存并落盘；每帧只做几批，剩下的下一帧接着做。 */
	private void pumpPrefetch() {
		// "当时没有"的记录到期就整体作废（主入口在 colorsForChunk，这里再兜一次，重复调用无害）
		long nowNanos = System.nanoTime();
		expireStalePrefetchMissing(nowNanos);
		// 一次搬运的时间上限：到点就停，剩下的下一批接着搬（按设置里的"搬运时间预算"，自动 3 ms）。
		// 至少搬一块，否则"这一块特别慢"会让搬运永远推不动。
		long applyDeadline = nowNanos + settings.effectiveApplyBudgetNanos();
		// 洞穴的那些不在这里立刻收：先攒起来，整批交给 SamplePool 去"选列"（见 applyCaveBatch）。
		// 地表那些与以前一样，就在这条循环里收
		List<Prefetched> caveBatch = new ArrayList<>();
		for (int applied = 0; applied < settings.effectiveAppliedPerFrame(); applied++) {
			if (applied > 0 && System.nanoTime() >= applyDeadline) {
				break;
			}
			Prefetched done = prefetchReady.poll();
			if (done == null) {
				break;
			}
			if (done.generation() != prefetchGeneration) {
				// 换过世界了：这一份属于上一个存档，直接丢掉（搬进来就是串数据）
				// 键也要一起删：不删的话这个键永远停在"在路上"，问它的地方会一直等下去
				prefetchQueued.remove(done.key());
				continue;
			}
			// 期间实时采样可能已经填上了（玩家走到了那一块），那以实时那份为准。
			// 这条要排在两个数据来源前面：它比磁盘上那份新
			if (surfaceSample(done.chunkX(), done.chunkZ(), done.layer()) != null) {
				prefetchQueued.remove(done.key());
				continue;
			}
			if (done.own() != null) {
				// 自己存档里就有这一块：数据已经在磁盘上了，进缓存并让界面补画即可，不必再落盘一遍
				prefetchQueued.remove(done.key());
				rememberSurfaceSample(done.chunkX(), done.chunkZ(), done.layer(), done.own());
				markArrived(done.chunkX(), done.chunkZ());
				continue;
			}
			if (done.read() == null || done.read().tag() == null) {
				prefetchQueued.remove(done.key());
				if (done.read() != null && done.read().retryLater()) {
					// 只是"区块还没生成到位"：这不是永远没有数据，等它生成出来再读。
					// 不能记进 prefetchMissing——记了就等于永久当成"没有数据"，
					// 玩家之后走到那里（它也就生成出来了），地图上那一块还是空的。
					// 记进"未就绪"那张表：现在先画成未探明，隔一会儿再问一次
					if (prefetchUnready.size() >= MAX_UNREADY) {
						prefetchUnready.clear();
					}
					prefetchUnready.put(done.key(), System.nanoTime() + UNREADY_RETRY_NANOS);
					continue;
				}
				// 区域文件里确实没有这一块（没生成过、或不是单人/局域网主机）：别再排队了
				prefetchMissing.add(done.key());
				continue;
			}
			// 这一块的 NBT 已经在手上：先把群系节记进缓存，着色（makeRecord / makeCaveRecord）
			// 就不必为同一块再把区域文件读一遍。维度按"正在查看的维度"（任务在换维度后已按计数丢弃）
			WorldSaveChunks.rememberBiomeSections(done.read().tag(), done.chunkX(), done.chunkZ(),
					minecraft.level, dataDimension());
			if (LayerSelector.isCave(done.layer())) {
				if (caveBatch.size() < MAX_CAVE_SCAN_PER_PUMP) {
					// 洞穴："选列"要按列扫区块 NBT，是纯计算，交给并行池去做（着色在 applyCaveBatch 里）
					caveBatch.add(done);
				} else {
					// 本轮洞穴批已满：这一块放回队列，下一帧再排（键删掉，取色入口会重新把它排进来）
					prefetchQueued.remove(done.key());
				}
				continue;
			}
			prefetchQueued.remove(done.key());
			// 收记录这一步只能在渲染线程上做：取方块本色、问生物群系都要碰客户端关卡
			applyRecord(done, WorldSaveChunks.makeRecord(done.layer(), done.read().tag(),
					done.chunkX(), done.chunkZ(), this, done.read().lightMissing(),
					viewDimensionHasCeiling()));
		}
		if (!caveBatch.isEmpty()) {
			applyCaveBatch(caveBatch);
		}
		// 洞穴批是异步的：本帧没扫完的留在池里，这里非阻塞地收走已经扫完的（一次都不等）。
		// 放在这里而不是 applyCaveBatch 里：这一步与"本帧有没有新提交的洞穴批"无关，
		// 上一帧提交、这一帧才扫完的同样要收。
		samplePool.drainCompleted(prefetchGeneration);
	}

	/**
	 * 洞穴那一批：把"选列"提交给 {@link SamplePool} 并行扫，<b>不当场等结果</b>；扫完的由
	 * {@link SamplePool#drainCompleted} 在渲染线程上着色、落盘。
	 *
	 * <p><b>为什么扫描能放后台、着色不能：</b>扫描只读区块 NBT、按方块状态判定水/冰/细碎方块
	 * （见 {@link WorldSaveChunks#scanCaveColumns}），不碰客户端关卡；着色要问 {@code minecraft.level}
	 * 取贴图配色与生物群系染色（见 {@link WorldSaveChunks#colorCaveColumns}），只能在渲染线程上做。
	 *
	 * <p><b>非阻塞：</b>不再有"到点就返回"的等法（那种自旋本身就把渲染线程占住）。现在只提交、立刻返回，
	 * 完成的下一帧再收——"晚一帧拿到结果"完全可接受。
	 *
	 * <p><b>换世界计数：</b>任务带着提交时的 {@link #prefetchGeneration}；{@link SamplePool#drainCompleted}
	 * 发现计数已变就取消丢弃，不会把上一个存档的结果写进缓存。
	 *
	 * <p><b>键的归属：</b>提交成功后 {@link #prefetchQueued} 里的键<b>一直留着</b>，等 drain 收完记录才删；
	 * 只有提交失败（池满）时才把键放回，让下一帧重新排。
	 */
	private void applyCaveBatch(List<Prefetched> batch) {
		int generation = prefetchGeneration;
		for (Prefetched done : batch) {
			// 每个任务只写自己那一格（长度 1 的数组当"结果槽"），任务之间互不相干
			WorldSaveChunks.CaveColumn[][] slot = new WorldSaveChunks.CaveColumn[1][];
			boolean queued = samplePool.submit(generation,
					// 扫描不碰关卡，扔给并行池
					() -> {
						// 【诊断计量】后台洞穴扫描的耗时：跑在池线程上，所以累加走
						// MapStats 里那组 LongAdder（见其线程归属说明）。用 finally 保证
						// 扫描抛异常（坏数据）时耗时也被记下——这一层不是内层循环，没有开销顾虑。
						long statT0 = STATS_ENABLED ? System.nanoTime() : 0L;
						try {
							slot[0] = WorldSaveChunks.scanCaveColumns(
									done.read().tag(), done.chunkX(), done.chunkZ(), done.layer(), this);
						} finally {
							if (STATS_ENABLED) {
								stats.recordCaveBackground(System.nanoTime() - statT0);
							}
						}
					},
					// 完成回调：只在渲染线程（drainCompleted）上跑
					() -> {
						prefetchQueued.remove(done.key());
						WorldSaveChunks.CaveColumn[] columns = slot[0];
						// columns 为 null：扫描在后台抛了（坏数据之类）。当成"收不出记录"处理，
						// 免得同一个坏块每帧都被重排一次
						applyRecord(done, columns == null ? null
								: WorldSaveChunks.makeCaveRecord(columns, done.chunkX(), done.chunkZ(), this));
					});
			if (!queued) {
				// 池满：这一块放回"没排过"的状态，下一帧再排
				prefetchQueued.remove(done.key());
			}
		}
	}

	/** 把一份收好的记录落盘、进缓存，并让界面把这一块补画一遍；{@code record} 为 null 表示这一块收不出数据。 */
	private void applyRecord(Prefetched done, ChunkRecord record) {
		if (record == null) {
			// 读到了 NBT 却收不出记录（整块没有非空气方块之类）：这一块也不会有下次了
			prefetchMissing.add(done.key());
			return;
		}
		// 看的是别的维度时，这一份是"正在查看的那个维度"的世界存档解出来的（见 #enqueuePrefetch
		// 里的 dataDimension），因此可以进内存缓存、也可以让界面补画；但**不落盘**（见 #persist：
		// 换维度期间万一还有旧维度的任务回来，写下去就是串数据；切维度是偶尔的动作，重读一次无所谓）
		persist(done.chunkX(), done.chunkZ(), done.layer(), record);
		rememberSurfaceSample(done.chunkX(), done.chunkZ(), done.layer(), record);
		// 这一块以前在瓦片上是"未探明"（当时还没落盘/区块还没加载），现在有数据了，
		// 得让界面把它补画一遍。走 "arrived" 而不是 "changed"：已经画着地形的那一格不用动，
		// 否则后台每读到一块就把画好的地方涂掉再重画（"已经加载的地方又加载了一遍"）
		markArrived(done.chunkX(), done.chunkZ());
	}

	/**
	 * 世界存档里读到的方块本色：与实时采样共用同一套贴图配色与生物群系染色规则。
	 *
	 * <p>群系由 {@link #biomeTints} 取：已加载区块问客户端关卡，未加载的（远处）区块读世界存档，
	 * 两条路问的是同一份群系，走近之后颜色不会再变一次。
	 */
	@Override
	public int rgb(BlockState state, int worldX, int worldY, int worldZ) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(worldX, worldY, worldZ);
		return baseColorOf(biomeTints, minecraft.getBlockColors(), state, pos);
	}

	/** 世界存档里的"是不是盖在上面的一层"判定。必须与实时采样同一套规则，否则近处与远处会画出两种样子。 */
	@Override
	public boolean isLayered(BlockState state) {
		return isLayeredBlock(state);
	}

	/** 世界存档里的"不是地面、要看穿它"判定，与实时采样共用同一份名单（见 {@link #isSurfaceDecoration}）。 */
	@Override
	public boolean shouldIgnore(BlockState state) {
		return isSurfaceDecoration(state);
	}

	/** 世界存档里的"是不是平铺的流体（水）"判定，见 {@link #isWaterBlock}。 */
	@Override
	public boolean isWater(BlockState state) {
		return isWaterBlock(state);
	}

	/**
	 * 洞穴切面往下找地板的深度：天花板维度（地狱）更深，与实时采样那条路用同一个数
	 * （见 {@code scanCaveColumnValue}），否则加载范围两侧会画出不同的洞穴。
	 */
	@Override
	public int caveScanDepth() {
		return viewDimensionHasCeiling() ? SurfaceShading.CEILING_CAVE_BAND : LayerSelector.CAVE_SCAN_DEPTH;
	}

	/** 世界存档里的层合成：同样与实时采样共用一套盖色规则。群系来源见 {@link #rgb}。 */
	@Override
	public int layered(int bedRgb, BlockState layerState, int worldX, int worldY, int worldZ, int depth) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(worldX, worldY, worldZ);
		boolean water = isWaterBlock(layerState);
		int layerRgb = baseColorOf(biomeTints, minecraft.getBlockColors(), layerState, pos);
		// 与实时采样同一条规则：深度只压覆盖层，不压底
		int tint = water ? scaleChannels(layerRgb, 1.0 - depthDim(depth)) : layerRgb;
		double opacity = water ? WATER_OPACITY : ICE_OPACITY;
		return mix(bedRgb, tint, opacity);
	}

	/** 世界存档里的"水下底色"取色：与实时采样同一条规则，见 {@link WorldSaveChunks.ColumnColor#coveredRgb}。 */
	@Override
	public int coveredRgb(BlockState bedState, int worldX, int worldY, int worldZ) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(worldX, worldY, worldZ);
		return baseColorOf(biomeTints, minecraft.getBlockColors(), bedState, pos, false);
	}

	/**
	 * 客户端区块加载/卸载后丢掉这块附近的群系染色缓存（见 {@link WorldBiomeTints}）。
	 *
	 * <p>只在"原地缓存里可能有基于平原的临时值"时需要它：区块一加载，邻域里原先问不到的群系就能从
	 * 关卡拿到，缓存必须跟着原版 {@code ClientLevel#onChunkLoaded} 一起作废。
	 */
	public void invalidateBiomeTints(ClientLevel level, int chunkX, int chunkZ) {
		biomeTints.invalidate(level, chunkX, chunkZ);
	}

	/**
	 * 天花板维度里，这一列的"地形顶"：从高度图给的那一格往下，<b>先穿过最上面的连续实心段（天花板）</b>，
	 * 再找第一格不是空气、也不是装饰的方块。
	 *
	 * <p>地狱的 {@code WORLD_SURFACE} 高度图给的是基岩顶（Y≈127）：照着画就是一张平坦的基岩图，
	 * 光标处 / 传送用的 Y 也会变成同一个值。跳过天花板之后，取到的是天花板下面真正的地形
	 * （地狱岩地面、熔岩湖），既连续、又随坐标变化。
	 *
	 * <p>按节跳过整节空气（{@code hasOnlyAir()} 是 O(1)），因此"从 127 一路找到 40"通常只查几个节。
	 */
	private int terrainTopBelowCeiling(LevelChunk chunk, ClientLevel level,
			int worldX, int worldZ, int topY, int minY) {
		BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
		int y = Math.min(topY, level.getMaxY() - 1);
		// ① 穿过天花板本身：最上面那一段连续实心
		while (y > minY) {
			probe.set(worldX, y, worldZ);
			BlockState state = chunk != null ? chunk.getBlockState(probe) : level.getBlockState(probe);
			if (state.isAir()) {
				break;
			}
			y--;
		}
		// ② 再从天花板下方往下找第一格真正的地形（整节空气一次跨过）
		while (y > minY) {
			LevelChunkSection section = sectionOf(chunk, y);
			if (section != null && section.hasOnlyAir()) {
				y = ((y >> 4) << 4) - 1;
				continue;
			}
			probe.set(worldX, y, worldZ);
			BlockState state = chunk != null ? chunk.getBlockState(probe) : level.getBlockState(probe);
			if (!state.isAir() && !isSurfaceDecoration(state)) {
				return y;
			}
			y--;
		}
		return minY;
	}

	/**
	 * 正在看的这个维度是不是"有天窗"的维度（地狱这类）。
	 *
	 * <p>它们的共同点是：{@code WORLD_SURFACE} 高度图给出的是<b>天花板</b>而不是地形，所以
	 * <ul>
	 *   <li>地表取样要跳过天花板（见 {@link #terrainTopBelowCeiling}）；</li>
	 *   <li>自动分层改看头顶有没有盖（见 {@link #underRoof()}），不能拿"玩家高度 vs 地表高度"来判。</li>
	 * </ul>
	 */
	public boolean viewDimensionHasCeiling() {
		ClientLevel level = minecraft.level;
		if (level == null) {
			return false;
		}
		String dimension = dataDimension();
		if (dimension == null) {
			return false;
		}
		if (dimension.equals(level.dimension().identifier().toString())) {
			return level.dimensionType().hasCeiling();
		}
		// 看别的维度：客户端拿不到那个维度的 DimensionType 时，按原版地狱兜底
		return Dimensions.NETHER.equals(dimension);
	}

	/**
	 * 地表：取地表那一格的颜色。
	 *
	 * <p><b>取颜色与算明暗用两张不同的高度图</b>：
	 *
	 * <ul>
	 *   <li>取颜色用 {@code WORLD_SURFACE}（最上面那个非空气方块）：它把草、花、枯木这些不阻挡移动的
	 *       方块也算进来，沙漠的枯木丛、平原的草才会出现在图上。</li>
	 *   <li>算明暗用 {@code MOTION_BLOCKING}（算树叶、不算草花）：细碎方块不参与高度场，否则平地会因
	 *       "这格有草、隔壁没有"而高度来回跳，图上一片黑麻点。</li>
	 *   <li>水面另用 {@code OCEAN_FLOOR}（忽略流体）取海底高度。</li>
	 * </ul>
	 *
	 * <p>"是否在地下"的判断则用 {@code MOTION_BLOCKING_NO_LEAVES}：站在树下时树冠会把地表高度抬高
	 * 一大截，用带树叶的高度图会被误判成已进入地下、在树下来回走就不停切层。
	 */
	private ChunkRecord sampleSurface(ClientLevel level, int chunkX, int chunkZ) {
		// 【诊断计量】只在本方法这一层取一次时间（不是逐列），关掉计量时连这次 nanoTime 都不做
		long statT0 = STATS_ENABLED ? System.nanoTime() : 0L;
		ChunkRecord record = new ChunkRecord();
		BlockColors blockColors = minecraft.getBlockColors();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		BlockPos.MutableBlockPos lightPos = new BlockPos.MutableBlockPos();

		int baseX = chunkX * SinanConstants.CHUNK_SIZE;
		int baseZ = chunkZ * SinanConstants.CHUNK_SIZE;

		// 直接抓住这一块的 LevelChunk，之后的方块与高度查询都走它。
		// 走 {@code level.getBlockState / level.getHeight} 的话，每一次都要先
		// {@code getChunkSource().getChunk(x>>4, z>>4)} 做一次哈希查找——一列十来次、一块上千次，
		// 一屏上万个区块就是上千万次无谓的哈希。LevelChunk 自己带着节数组与高度图，
		// 用局部坐标直接取，没有这一层。
		// 调用方都先确认过这一块已加载；真拿不到（正好被卸载）就退回关卡查询，行为与以前一致。
		LevelChunk chunk = loadedChunk(chunkX, chunkZ);
		int minY = level.getMinY();

		// 这一块里读到的最大的天空光，见方法末尾的兜底
		int maxSkyLight = 0;

		// 天花板维度：这一块的地表要"跳过天花板"往下找（见 terrainTopBelowCeiling）
		boolean ceiling = viewDimensionHasCeiling();

		for (int localZ = 0; localZ < SinanConstants.CHUNK_SIZE; localZ++) {
			for (int localX = 0; localX < SinanConstants.CHUNK_SIZE; localX++) {
				int worldX = baseX + localX;
				int worldZ = baseZ + localZ;

				// 取颜色的那一格用 WORLD_SURFACE：它连草、花、枯木这些"不阻挡移动"的方块也算进来，
				// 沙漠的枯木丛、平原的草才会出现在图上。
				//
				// 这里只能用 Level 的那个 getHeight，不能用 chunk 的。两者的语义差了 1：
				// {@code ChunkAccess#getHeight} 内部就写着 {@code getFirstAvailable(...) - 1}，
				// 返回的已经是"地表方块本身"；而 {@code Level#getHeight} 返回的是"地表方块上方那一格"。
				// 用 chunk 的那个再减 1，就会取到地表下面一格——草方块被读成底下的泥土，
				// 而且 {@code colorY + 1} 落进实心方块里、光照恒为 0，整片远处发暗。
				// （实测日志：`方块=minecraft:dirt startY=92 colorY=92 本色=#896447 sky=0`。）
				int startY = level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ) - 1;
				// 天花板维度（地狱这类）：高度图给的是基岩顶。先穿过最上面那一段实心，
				// 再从下面找真正的地形顶——否则整张地狱地图就是一片平坦的基岩，Y 也永远不变。
				if (ceiling) {
					startY = terrainTopBelowCeiling(chunk, level, worldX, worldZ, startY, minY);
				}
				int colorY = startY;
				int index = localZ * SinanConstants.CHUNK_SIZE + localX;
				pos.set(worldX, Math.max(colorY, minY), worldZ);
				BlockState state = chunk != null ? chunk.getBlockState(pos) : level.getBlockState(pos);
				// 往下找真正的地面，两种情况都要接着找：
				//   ① 火把、矮草这些"不是地面"的东西——地图上的地表是地面，不是插在上面的一支火把；
				//   ② 高度图给出的是空气（区块高度图还没建起来的边界，或者这一列本来就是虚空）。
				// 只往下走有限几格，避免碰到一串装饰时一直探到地底
				while (colorY > minY && startY - colorY < SURFACE_LOOK_DOWN
						&& (state.isAir() || isSurfaceDecoration(state))) {
					colorY--;
					pos.set(worldX, colorY, worldZ);
					state = chunk != null ? chunk.getBlockState(pos) : level.getBlockState(pos);
				}
				if (state.isAir()) {
					// 一路都是空气：这一列没有地面数据。标成"没有数据"，让它保持全透明——
					// 以前这里给 NO_BLOCK_RGB（接近纯黑的不透明色），在世界地图上就是加载区域
					// 边界上那一圈比底色更黑的暗块
					record.setNoData(index);
					continue;
				}

				// 明暗用的高度另取：MOTION_BLOCKING 算树叶、不算草花。
				// 高度只用它来算，于是这些细碎方块不会把高度场打出一堆毛刺——
				// 之前"平原全是黑麻点"一半是颜色跳、一半就是高度跟着草一起跳。
				//
				// 两张高度图只有在"顶面那一格不挡路"时才不同（草、花、火把、地毯这类：
				// WORLD_SURFACE 算它，MOTION_BLOCKING 不算）。上面已经找到这一列第一格"不是装饰"
				// 的方块，只要它挡路——或者它是流体（流体也算进 MOTION_BLOCKING）——两张图在这一列
				// 就是同一个值，不必再查一次。平地、沙漠、海面几乎全走这条快路。
				int shadingY = state.blocksMotion() || !state.getFluidState().isEmpty()
						? colorY
						: level.getHeight(Heightmap.Types.MOTION_BLOCKING, worldX, worldZ) - 1;

				int rgb;
				boolean layered = isLayeredBlock(state);
				// 冰与水的处理刻意不同：水是平铺的流体，明暗按平铺的一层算；
				// 冰是跟着地形走的固体薄壳，明暗按它自己的表面算，才会和周围的雪、浮冰连成一片
				boolean flatSheet = layered && isWaterBlock(state);
				if (layered) {
					long sample = layeredSample(level, chunk, blockColors, worldX, worldZ, colorY, state, pos);
					rgb = (int) sample;
					if (flatSheet) {
						// 水面高度就是海平面，整片一样高，拿它算明暗的话水下地形完全读不出来。
						// 改用底下那层的高度，水下也就能看出起伏了
						shadingY = (int) (sample >> 32);
					}
				} else {
					rgb = baseColorOf(biomeTints, blockColors, state, pos);
				}

				// 光照取取材那一格上方：固体方块自身的光照恒为 0
				lightPos.set(worldX, colorY + 1, worldZ);
				int skyLight = level.getBrightness(LightLayer.SKY, lightPos);
				record.setColumn(index, rgb, shadingY, skyLight,
						level.getBrightness(LightLayer.BLOCK, lightPos));
				if (skyLight > maxSkyLight) {
					maxSkyLight = skyLight;
				}
				record.setFlatSheet(index, flatSheet);
				// 岩浆这类自发光方块在地图上直接给本色，不叠明暗，见 ChunkRecord#FLAG_EMISSIVE
				record.setEmissive(index, state.getLightEmission() > 0);
				// 岩浆的本色已经按"最亮通道"归一化过（见 BlockTextureColors#lavaColor），着色时不要再拉亮，
				// 否则色相会被推向荧光黄
				record.setGlowNormalized(index, state.is(Blocks.LAVA));
			}
		}
		// 光照还没算好的区块要兜底。刚进视距的区块，光照引擎还没跑过，读出来是整块 0：
		// 照它着色就是一块块暗斑；更要命的是这一份会被缓存、落盘，而之后除非方块真的变了
		// （{@link #refreshChunkIfChanged} 比的是方块数据，光照 0→15 也算变）否则不会重采——
		// 于是"玩家走远之后没再路过"的那几块就成了地图上洗不掉的残留暗块。
		//
		// 判据取"整块天空光都是 0"：地表取样取的是高度图那一格，它上面就是露天，
		// 天空光本来就该是 15；整块全是 0 只可能是"还没算好"。
		// 兜底值与世界存档那条路保持一致（见 {@code WorldSaveChunks#toRecord} 对 {@code lightMissing}
		// 的处理），否则加载范围的内外两侧会差一截亮度、边界上又是一圈暗块。
		if (maxSkyLight == 0) {
			for (int index = 0; index < ChunkRecord.COLUMNS; index++) {
				if (record.isNoData(index)) {
					continue;
				}
				record.setColumn(index, record.rgb(index), record.height(index), 15, record.blockLight(index));
			}
		}
		// 【诊断计量】整块采样的耗时（含着色前的全部查询）
		if (STATS_ENABLED) {
			stats.recordSurfaceSample(System.nanoTime() - statT0);
		}
		return record;
	}

	/**
	 * 半透明层（水、冰）底下的地形本色，与层本色混好后打包在一个 long 里返回。
	 *
	 * <p>高 32 位是底下那层的高度，低 32 位是 ARGB 本色：两者一起算，"往下找底"这趟扫描顺带把高度
	 * 带出来即可，不用再查一次高度图。
	 *
	 * <p><b>冰和水一样只是盖一层，不是地表。</b>原版高度图把冰当成地表（冰挡移动），只看高度图会得到
	 * "一整片纯冰"；这里往下穿过冰与水一直到真正的底，再把层本色按<b>各自那一档</b>的不透明度盖上
	 * （用同一个值会让冰淡得像薄水）。
	 *
	 * <p><b>这里只负责"盖一层"。</b>水面的深浅（越深越暗）与去坡面明暗都在着色时统一算，见
	 * {@code SurfaceShading#totalFactor}——放那边的好处是调整它<b>不需要递增数据格式版本</b>，而这里
	 * 存的颜色一变就必须递增（新旧区域之间会出现色差）。
	 *
	 * <p><b>不能用 {@code Heightmap.Types.OCEAN_FLOOR} 取底的高度</b>：它属于 {@code Usage.LIVE_WORLD}，
	 * 原版只把 {@code Usage.CLIENT} 那几种发给客户端，客户端读到的是一整片没填过的 0。
	 *
	 * <p><b>不做深度判定，也不设"最多看多少格"上限</b>：底一律按本色显示，一直找到底为止；扫描本来就
	 * 在第一个不透明方块处停下，代价只跟真实深度成正比。
	 *
	 * @param chunk        这一列所在的已加载区块（可能为 null，此时退回关卡查询）
	 * @param surfaceY     层的顶面高度
	 * @param surfaceState 顶面那一格的方块状态（水或冰）
	 */
	private long layeredSample(ClientLevel level, LevelChunk chunk, BlockColors blockColors,
			int worldX, int worldZ, int surfaceY, BlockState surfaceState, BlockPos surfacePos) {
		// 【诊断计量】只在本方法这一层取一次时间（不是"往下找底"的每一格）
		long statT0 = STATS_ENABLED ? System.nanoTime() : 0L;
		long value = layeredSampleValue(level, chunk, blockColors, worldX, worldZ, surfaceY, surfaceState, surfacePos);
		if (STATS_ENABLED) {
			stats.recordLayered(System.nanoTime() - statT0);
		}
		return value;
	}

	/** {@link #layeredSample} 的实际计算；拆出来只为在<b>外层</b>计一次耗时，行为与原先逐字相同。 */
	private long layeredSampleValue(ClientLevel level, LevelChunk chunk, BlockColors blockColors,
			int worldX, int worldZ, int surfaceY, BlockState surfaceState, BlockPos surfacePos) {
		int layer = baseColorOf(biomeTints, blockColors, surfaceState, surfacePos);
		boolean water = isWaterBlock(surfaceState);
		double opacity = water ? WATER_OPACITY : ICE_OPACITY;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		// 世界底部高度差在整趟"往下找底"里都不变，提到循环外算一次即可。
		// 方块查询统一走调用方传来的这一块 LevelChunk：用它自己的节数组按世界坐标直接取，
		// 免得往下每探一格都让 level 先做一次 getChunkSource().getChunk(x>>4, z>>4) 的哈希
		// （理由与 sampleSurface 那段相同）。chunk 与 (worldX, worldZ) 必定同属一块；
		// 真拿不到（正好被卸载）就退回 level 查询，取到的方块与以前完全一致。
		int minY = level.getMinY();
		for (int y = surfaceY - 1; y >= minY; y--) {
			pos.set(worldX, y, worldZ);
			BlockState state = chunk != null ? chunk.getBlockState(pos) : level.getBlockState(pos);
			if (state.isAir() || isLayeredBlock(state)) {
				// 冰、水都接着往下看：它们是盖在上面的一层，不是底
				continue;
			}
			// 被水盖住时底色不再算光源：岩浆块在水下不按"发光"那一档取色，否则会透出一块偏亮
			// 的色斑（见 BlockTextureColors#coveredColorOf 与 #coveredRgb）。水面之上不这样——
			// 那一列不叠水、走的是普通的 baseColorOf，仍然按光源显示。
			int bed = water && state.getLightEmission() > 0
					? baseColorOf(biomeTints, blockColors, state, pos, false)
					: baseColorOf(biomeTints, blockColors, state, pos);
			// 深浅只压覆盖层：水底的贡献固定按本色透出来，才能一直看得见底
			int tint = water
					? scaleChannels(layer, 1.0 - depthDim(surfaceY - y))
					: layer;
			return (long) y << 32 | mix(bed, tint, opacity) & 0xFFFFFFFFL;
		}
		// 到世界底部都没有底面（深海沟之类）：就是纯层色，明暗仍按顶面算
		return (long) surfaceY << 32 | layer & 0xFFFFFFFFL;
	}

	/** 水色按深度压暗的比例：越深越暗，但保留对比，也保留一个下限。 */
	private static double depthDim(int depth) {
		return Math.min(MAX_DEPTH_DIM, Math.max(0, depth) * DEPTH_DIM_PER_BLOCK);
	}

	/** 按倍率缩放三通道并钳位。 */
	private static int scaleChannels(int rgb, double factor) {
		int r = Math.clamp((int) (((rgb >> 16) & 0xFF) * factor), 0, 255);
		int g = Math.clamp((int) (((rgb >> 8) & 0xFF) * factor), 0, 255);
		int b = Math.clamp((int) ((rgb & 0xFF) * factor), 0, 255);
		return r << 16 | g << 8 | b;
	}

	/** 盖在地形之上、但底下的东西仍要看一眼的方块：水与冰。 */
	private static boolean isLayeredBlock(BlockState state) {
		return isWaterBlock(state) || state.is(Blocks.ICE);
	}

	/** 平铺在地形之上的流体。只有它按"平的一层"算明暗，见 {@link ChunkRecord#FLAG_FLAT_SHEET}。 */
	private static boolean isWaterBlock(BlockState state) {
		return state.is(Blocks.WATER);
	}

	/**
	 * 从高度图给的那一格往下最多找几格。
	 *
	 * <p>两种情况要看穿：火把、矮草这类"不是地面"的东西（一般就一层，最多再加个雪层），
	 * 以及高度图给出空气（区块高度图还没建起来、或这一列是虚空）。几十格足够覆盖，
	 * 又不会在异常情况下把整列探到地底。
	 */
	private static final int SURFACE_LOOK_DOWN = 48;

	/**
	 * 这些方块<b>不是地面</b>：它们立在地面之上，地图上的地表应该透过它们看到下面的地面。
	 *
	 * <p>最典型的是<b>火把</b>：以前插在草地上一支火把就把整格画成火把的颜色，一整条小路的地面全被
	 * 盖掉；同理还有矮草、高草、树苗、铁轨、玻璃。名单按"完全不可见"的尺度收：显式列 + 渲染形状为
	 * INVISIBLE 的方块，<b>而不是"凡不是整方块就跳过"</b>——后者会把雪层、地毯、台阶、栏杆这些真正
	 * 铺在地面上的东西一起跳过。
	 *
	 * <p><b>花与花簇不在此列</b>：它们自己就是地表那一格，要按本色画出来（见
	 * {@link #computeSurfaceDecoration}）。水与冰也不在此列：它们是<b>覆盖层</b>，走
	 * {@link #isLayeredBlock} 那条路。树叶同样不在——把它看穿会让整片森林变成底下的草地。
	 *
	 * <p><b>结果按方块状态缓存</b>（见 {@link #SURFACE_DECORATION}）：这个判定每列至少问一次（一屏几
	 * 百万次），内部又是一串标签/方块比较；方块状态全世界也就几千个，查表便宜得多。
	 *
	 * <p><b>缓存必须是线程安全的</b>，所以是 {@link ConcurrentHashMap}：洞穴"选列"
	 * （{@code WorldSaveChunks#scanCaveColumns}）已经挪到后台线程，也会调到这里判定"这一格是不是要看
	 * 穿的细碎方块"。两个线程并发 {@code put} 普通 {@code HashMap} 可能写坏桶、极端时查表死循环。
	 *
	 * <p><b>公开</b>是给雷达的高度指示用（{@code EntityRadar#backdropColorOf}）：它要跟地图同一口径
	 * ——跳过矮草/花/火把这些"看穿的装饰"、取真正的地面来判明暗，否则生物站在矮草上会判成暗地形、
	 * 在一片绿色草原上画出白色的高度箭头。
	 */
	private static final Map<BlockState, Boolean> SURFACE_DECORATION = new ConcurrentHashMap<>();

	public static boolean isSurfaceDecoration(BlockState state) {
		Boolean cached = SURFACE_DECORATION.get(state);
		if (cached != null) {
			return cached;
		}
		boolean result = computeSurfaceDecoration(state);
		SURFACE_DECORATION.put(state, result);
		return result;
	}

	/** {@link #isSurfaceDecoration} 的实际判定（缓存未命中时才走这里）。 */
	private static boolean computeSurfaceDecoration(BlockState state) {
		// 流体一律<b>不是</b>要"看穿的装饰"：它们就是地表本身，应当被采样。
		// 水/冰由 {@link #isLayeredBlock} 兜住，但岩浆不是 layered——而岩浆方块（{@code LiquidBlock}）
		// 的 {@code getRenderShape()} 是 {@code INVISIBLE}（流体走流体渲染器、不画方块模型），
		// 于是会掉进下面那条 {@code RenderShape.INVISIBLE} 判定里被当成"看穿的装饰"跳过，
		// 地表取样一路走到岩浆<b>下面</b>的石块——整片岩浆湖在地图上完全看不到。
		// 这里把流体先摘出去（含各流动等级的岩浆与模组流体），岩浆才会按本色画出来。
		if (isLayeredBlock(state) || !state.getFluidState().isEmpty() || state.is(BlockTags.LEAVES)) {
			return false;
		}
		// 花与花簇要看出来，不能当"看穿的装饰"跳过：粉红色花簇（pink_petals）、野花簇
		// （wildflowers）与蒲公英、郁金香这些普通花都落在 {@code BlockTags.FLOWERS} 里，
		// 一并跳过就会改画它们下面的方块——草地上整片花、樱花林地上的花瓣在图上全被抹成草色。
		// 它们自己就是"地表那一格"，按本色画出来。
		//
		// 原版对多数花返回"不染色"（{@code colorInWorld} = -1），画出来就是纹理本色——正是要的效果，
		// 不要为它们加任何兜底染色（第二十四批的教训）。
		return state.is(BlockTags.SAPLINGS)
				|| state.is(BlockTags.RAILS)
				|| state.is(Blocks.SHORT_GRASS)
				|| state.is(Blocks.TALL_GRASS)
				|| state.is(Blocks.GLASS)
				|| state.is(Blocks.GLASS_PANE)
				|| state.is(Blocks.TORCH)
				|| state.is(Blocks.WALL_TORCH)
				|| state.is(Blocks.SOUL_TORCH)
				|| state.is(Blocks.SOUL_WALL_TORCH)
				|| state.is(Blocks.REDSTONE_TORCH)
				|| state.is(Blocks.REDSTONE_WALL_TORCH)
				|| state.getRenderShape() == RenderShape.INVISIBLE;
	}

	/**
	 * 洞穴切面：每列找层高度附近的空气，再往下找它下面的地板。
	 *
	 * <p>切片按<b>列</b>缓存（见 {@link CaveSlice}）：一次扫描把这一列所在洞穴口袋（或那片实心岩层）的
	 * 高度范围都记下来。在口袋里上下移动时这一列的结果没变，于是不必再碰世界——这就是洞穴层能逐格跟着
	 * 玩家高度走、却几乎不花时间的原因。
	 *
	 * @param layerY 要切的那一层高度（显示层逐格跟随玩家，因此是精确值，不是归档值）
	 */
	private int[] caveColorsForChunk(ClientLevel level, int chunkX, int chunkZ, int layerY) {
		CaveSlice slice = caveSlice(chunkX, chunkZ);
		boolean rescanned = false;
		if (slice.layer != layerY) {
			for (int index = 0; index < ChunkRecord.COLUMNS; index++) {
				if (slice.validLow[index] <= layerY && layerY <= slice.validHigh[index]) {
					// 这一列的结果在这个高度上仍然成立，直接沿用
					continue;
				}
				scanCaveColumn(level, chunkX, chunkZ, index, layerY, slice);
				rescanned = true;
			}
			slice.layer = layerY;
			// 列动过了，整块的"适用范围交集"跟着重算一次（256 次比较，相对扫描本身可以忽略）
			slice.refreshCoverage();
		}
		if (rescanned || slice.renderedEpoch != shadeEpoch) {
			// 只有真的动了原始数据、或光照换了档，才需要重新着色。
			// 整块都还在适用范围内时颜色逐像素相同，白算一遍等于每走一格都白烧一次全屏重着色
			slice.record.toColors(REFERENCE_Y, passSkyDarken, slice.colors,
					viewDimensionHasCeiling(), caveShading());
			slice.renderedEpoch = shadeEpoch;
		}
		if (rescanned) {
			// 原始数据动过了，贴着它缓存的那两档固定颜色也要重算
			slice.pairValid = false;
		}
		return slice.colors;
	}

	/**
	 * 扫一列，把结果写进切片，并算出这个结果在哪些高度上仍然成立。
	 *
	 * <p>成立范围是<b>推出来的</b>，不是猜的：
	 * <ul>
	 *   <li>找到空气与地板：窗口上沿是 {@code top = layerY + 容差}。向下扫时已确认 {@code [y+1, top]}
	 *       是实心的，再往上就得到口袋的天花板。只要窗口上沿落在"地板之上、天花板之下"，扫出来的就还是
	 *       同一个地板，范围是 {@code [地板 + 1 - 容差, 天花板 - 容差]}。</li>
	 *   <li>窗口内全是实心：往外扫出这片实心有多厚，只要窗口仍整段落在里面，结果就还是"不显示"。</li>
	 * </ul>
	 *
	 * <p><b>"整节空气"整段跳过</b>：往下找地板与往上摸天花板这两趟要连续穿过很多格空气，于是按 16 格
	 * 一节推进，先用 {@link LevelChunkSection#hasOnlyAir()}（O(1) 的缓存计数）判断"整节都是空气"、是就
	 * 一次跨过，否则回到逐格。判定条件、扫描深度、窗口上下沿一个字都没改，逐列结果与改造前逐位相同。
	 */
	private void scanCaveColumn(ClientLevel level, int chunkX, int chunkZ, int index, int layerY, CaveSlice slice) {
		// 【诊断计量】只在本方法这一层取一次时间（不是扫一列的每一格）；
		// 实时这条路只跑在渲染线程上，累加用普通字段即可。
		long statT0 = STATS_ENABLED ? System.nanoTime() : 0L;
		scanCaveColumnValue(level, chunkX, chunkZ, index, layerY, slice);
		if (STATS_ENABLED) {
			stats.recordCaveRealtime(System.nanoTime() - statT0);
		}
	}

	/** {@link #scanCaveColumn} 的实际扫描；拆出来只为在<b>外层</b>计一次耗时，行为与原先逐字相同。 */
	private void scanCaveColumnValue(ClientLevel level, int chunkX, int chunkZ, int index, int layerY, CaveSlice slice) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		BlockPos.MutableBlockPos lightPos = new BlockPos.MutableBlockPos();
		int worldX = chunkX * SinanConstants.CHUNK_SIZE + index % SinanConstants.CHUNK_SIZE;
		int worldZ = chunkZ * SinanConstants.CHUNK_SIZE + index / SinanConstants.CHUNK_SIZE;
		// 先抓住这一块的 LevelChunk，之后所有方块查询都走它。扫一列要问十几次方块状态
		// （窗口上下、往下找地板、往上量天花板、窗口外探实心），每次都调 {@code level.getBlockState}
		// 的话，每次都要先 {@code getChunkSource().getChunk(x>>4, z>>4)} 做一次哈希。
		// LevelChunk 自带节数组，用世界坐标的 pos 直接取即可，没有这一层（理由同 sampleSurface）。
		// 这条路读的是客户端已加载区块、跑在渲染线程上；真拿不到就退回 level，取到的方块与以前一致。
		LevelChunk chunk = loadedChunk(chunkX, chunkZ);
		int minY = level.getMinY();
		int top = layerY + CAVE_TOLERANCE;
		int bottom = Math.max(minY, layerY - CAVE_TOLERANCE);

		for (int y = top; y >= bottom; y--) {
			pos.set(worldX, y, worldZ);
			if (!(chunk != null ? chunk.getBlockState(pos) : level.getBlockState(pos)).isAir()) {
				continue;
			}
			int floor = Integer.MIN_VALUE;
			// 天花板维度（地狱）的溶洞普遍更空、地板常在 30 格以下：往下翻得更深，否则大片列没有地板
			int scanDepth = viewDimensionHasCeiling() ? SurfaceShading.CEILING_CAVE_BAND : CAVE_SCAN_DEPTH;
			int floorBottom = Math.max(minY, y - scanDepth);
			// 按 16 格一节推进：整节空气就整节跳过，其余情况仍逐格查（见下面的等价性说明）。
			// 扫描下沿仍是 {@code y - CAVE_SCAN_DEPTH}，没有动过。
			for (int below = y - 1; below >= floorBottom; ) {
				int sectionBottom = Math.max(floorBottom, (below >> 4) << 4);
				LevelChunkSection section = sectionOf(chunk, below);
				if (section != null && section.hasOnlyAir()) {
					// 整节都是空气（{@code hasOnlyAir()} 是 O(1) 的缓存计数）：这一节在窗口里的
					// 每一格都会走下面的"接着往下找"，逐格扫与整节跳过得到的 floor 完全相同——
					// 判定条件、扫描深度、节的上下边界一个字没改。
					// 只要这一节里有一格不是空气/装饰，就落到下面的逐格循环，行为与改造前一致。
					below = sectionBottom - 1;
					continue;
				}
				for (int cell = below; cell >= sectionBottom; cell--) {
					pos.set(worldX, cell, worldZ);
					BlockState belowState = chunk != null ? chunk.getBlockState(pos) : level.getBlockState(pos);
					// 火把、花草这些不算地板，要接着往下找（同地表那条路，见 isSurfaceDecoration）
					if (!belowState.isAir() && !isSurfaceDecoration(belowState)) {
						floor = cell;
						break;
					}
				}
				if (floor != Integer.MIN_VALUE) {
					break;
				}
				below = sectionBottom - 1;
			}
			if (floor == Integer.MIN_VALUE) {
				// 下面几十格都是空气：只记下"空气在 y 及以下"这一段仍然成立的范围
				slice.record.setNoData(index);
				slice.record.setFlatSheet(index, false);
				slice.record.setEmissive(index, false);
				setValidity(slice, index, y - CAVE_TOLERANCE, layerY);
				return;
			}
			// 往上把两种情况都摸清：
			//   ceiling   = 从 y+1 起那一段实心能到多高（窗口上沿就落在实心里时靠它）
			//   pocketTop = 之后再往上那一段空气能到多高（口袋比窗口还高时靠它）
			// 只算其中一个会算出过窄的适用范围：口袋比窗口高时 ceiling 就等于 y，
			// 于是"往上走一格"也被判成失效，每走一格都要重扫——就是洞穴里看起来每格都在刷新的原因。
			int ceiling = y;
			int pocketTop = y;
			boolean inSolid = true;
			int ceilingTop = y + CAVE_CEILING_SCAN;
			// 按 16 格一节推进：整节空气就整节跳过，其余情况仍逐格查。扫描上沿仍是
			// {@code y + CAVE_CEILING_SCAN}，没有动过。
			for (int above = y + 1; above <= ceilingTop; ) {
				int sectionTop = Math.min(ceilingTop, (above >> 4) << 4 | 15);
				LevelChunkSection section = sectionOf(chunk, above);
				if (section != null && section.hasOnlyAir()) {
					// 整节都是空气：逐格扫这一节的效果可以一次算出来——
					//   · 进来时 inSolid 还是真：第一格空气就把 inSolid 置假、pocketTop 记成那一格，
					//     之后每一格都是空气，pocketTop 一路抬到本节最高那一格；
					//   · 进来时 inSolid 已是假：pocketTop 同样抬到本节最高那一格。
					// 两种情形合并成下面这三行；ceiling 不会被空气改到，所以保持不变。
					pocketTop = sectionTop;
					inSolid = false;
					above = sectionTop + 1;
					continue;
				}
				boolean stop = false;
				for (int cell = above; cell <= sectionTop; cell++) {
					pos.set(worldX, cell, worldZ);
					boolean air = (chunk != null ? chunk.getBlockState(pos) : level.getBlockState(pos)).isAir();
					if (inSolid) {
						if (air) {
							inSolid = false;
							pocketTop = cell;
						} else {
							ceiling = cell;
						}
					} else if (air) {
						pocketTop = cell;
					} else {
						stop = true;
						break;
					}
				}
				if (stop) {
					break;
				}
				above = sectionTop + 1;
			}

			pos.set(worldX, floor, worldZ);
			BlockState state = chunk != null ? chunk.getBlockState(pos) : level.getBlockState(pos);
			lightPos.set(worldX, floor + 1, worldZ);
			int rgb;
			int displayY = floor;
			boolean layered = isLayeredBlock(state);
			boolean flatSheet = layered && isWaterBlock(state);
			if (layered) {
				// 洞穴模式下的水面/冰面：同样要盖一层、并把底下露出来。
				// 直接取水或冰的本色会得到一整片纯色，下面的东西全看不见
				long sample = layeredSample(level, chunk, minecraft.getBlockColors(), worldX, worldZ, floor, state, pos);
				rgb = (int) sample;
				if (flatSheet) {
					displayY = (int) (sample >> 32);
				}
			} else {
				rgb = baseColorOf(biomeTints, minecraft.getBlockColors(), state, pos);
			}
			slice.record.setColumn(index,
					rgb,
					displayY,
					level.getBrightness(LightLayer.SKY, lightPos),
					level.getBrightness(LightLayer.BLOCK, lightPos));
			slice.record.setFlatSheet(index, flatSheet);
			slice.record.setEmissive(index, !layered && state.getLightEmission() > 0);
			slice.record.setGlowNormalized(index, !layered && state.is(Blocks.LAVA));
			setValidity(slice, index, floor + 1 - CAVE_TOLERANCE,
					Math.max(ceiling, pocketTop) - CAVE_TOLERANCE);
			return;
		}

		// 窗口内没有空气：这一列在层高度附近是实心岩层，洞穴图上不显示它。
		//
		// 这里给的是"没有数据"（→ 全透明），不是 NO_BLOCK_RGB：那个本色被着色后会变成
		// 比界面底色更黑的一小块，于是"已加载的洞穴区域"整片看起来是一片暗块，
		// 而未加载的地方反而是底色——加载范围的边界因此特别显眼。
		// 高度写常数 0 而不是当前层：写当前层的话同一片岩石会随着下潜不断改高度，
		// 紧邻的洞穴地面跟着改明暗，看起来就是"明明没动却一直在变"，而且没法跨层复用
		int solidLow = bottom;
		for (int y = bottom - 1; y >= bottom - CAVE_SOLID_SCAN && y >= minY; y--) {
			pos.set(worldX, y, worldZ);
			if ((chunk != null ? chunk.getBlockState(pos) : level.getBlockState(pos)).isAir()) {
				break;
			}
			solidLow = y;
		}
		int solidHigh = top;
		for (int y = top + 1; y <= top + CAVE_SOLID_SCAN; y++) {
			pos.set(worldX, y, worldZ);
			if ((chunk != null ? chunk.getBlockState(pos) : level.getBlockState(pos)).isAir()) {
				break;
			}
			solidHigh = y;
		}
		slice.record.setNoData(index);
		slice.record.setFlatSheet(index, false);
		slice.record.setEmissive(index, false);
		setValidity(slice, index, solidLow + CAVE_TOLERANCE, solidHigh - CAVE_TOLERANCE);
	}

	/** 记下某一列结果的适用高度区间（闭区间）；给出空区间就是不适用。 */
	private static void setValidity(CaveSlice slice, int index, int low, int high) {
		slice.validLow[index] = (short) Math.clamp(low, Short.MIN_VALUE, Short.MAX_VALUE);
		slice.validHigh[index] = (short) Math.clamp(high, Short.MIN_VALUE, Short.MAX_VALUE);
	}

	/**
	 * 从 {@code fromLayer} 换到 {@code toLayer} 时，这一块的洞穴切面会不会<b>真的不一样</b>。
	 *
	 * <p>切片按列复用，每列都记着"结果在哪些高度上仍然成立"，{@link CaveSlice#coversLow} /
	 * {@link CaveSlice#coversHigh} 是这些区间的交集；只要<b>两个层都落在交集里</b>，重画出来就是逐点相同
	 * 的像素，换层时这一块可以整块沿用。
	 *
	 * <p>要看两个层而不是只看新层：页里像素是按<b>旧层</b>画的，页键里的新层只是"目标高度"（见
	 * {@code WorldTilePages#refreshCaveHeight}），所以"像素仍然有效"要求旧层与新层落在同一个交集里。
	 *
	 * @return true 表示整块的像素都不会变，不必重画
	 */
	public boolean caveSliceCovers(int chunkX, int chunkZ, int fromLayer, int toLayer) {
		CaveSlice slice = caveSlices.getAndMoveToLast((long) chunkX << 32 | chunkZ & 0xFFFFFFFFL);
		if (slice == null) {
			// 这一块不是实时采样的（区块没加载，像素是从世界存档读回来的），没有逐列的适用范围。
			// 它那份数据按归档层存/查，所以只要新旧两层落在同一个归档档里像素就还是对的——沿用，
			// 才不会把整页没加载的区块统统标成失效（显示层逐格变，每格都重填就是"撕裂"）。
			return LayerSelector.storageLayer(fromLayer) == LayerSelector.storageLayer(toLayer);
		}
		int low = Math.min(fromLayer, toLayer);
		int high = Math.max(fromLayer, toLayer);
		return low >= slice.coversLow && high <= slice.coversHigh;
	}

	/** 取一个区块的洞穴切片；没有就新建一份（各列都还没扫过）。 */
	private CaveSlice caveSlice(int chunkX, int chunkZ) {
		long key = (long) chunkX << 32 | chunkZ & 0xFFFFFFFFL;
		// 命中即挪到队尾（访问序 LRU；见 #caveSlices）
		CaveSlice slice = caveSlices.getAndMoveToLast(key);
		if (slice != null) {
			return slice;
		}
		slice = new CaveSlice();
		caveSlices.putAndMoveToLast(key, slice);
		evictCaveSlices();
		return slice;
	}

	/** 超出上限就丢掉最久没用过的那份切片（访问序 LRU，从队首丢；见 #caveSlices）。 */
	private void evictCaveSlices() {
		while (caveSlices.size() > MAX_CAVE_SLICES) {
			caveSlices.removeFirst();
		}
	}

	/**
	 * 方块本色（已含生物群系染色），0xRRGGBB。
	 *
	 * <p>本色取方块朝上那一面的贴图平均色，带染色的方块再乘生物群系染色——与原版渲染"灰度贴图 ×
	 * 染色"是同一套算法。以前用 {@code MapColor}（原版地图的卡通色板），又亮又饱和、和游戏里对不上。
	 *
	 * <p>{@code level} 就是"从哪儿问群系"：实时采样与从世界存档读的那两条路都传 {@link #biomeTints}，
	 * 因此两边问的是同一个东西（见 {@link WorldBiomeTints}）。
	 */
	private int baseColorOf(BlockAndTintGetter level, BlockColors blockColors, BlockState state, BlockPos pos) {
		return baseColorOf(level, blockColors, state, pos, state.getLightEmission() > 0);
	}

	/**
	 * {@link #baseColorOf(BlockAndTintGetter, BlockColors, BlockState, BlockPos)} 的实际实现。
	 *
	 * <p>{@code emissive} 单独传进来，是为了给"被水盖住的底色"留一个口子：那时自发光方块（岩浆块）
	 * 不再当光源，走 {@link BlockTextureColors#coveredColorOf}。"有没有被水盖住"只有调用方知道，所以
	 * 只能由调用方传下来，见 {@link #coveredRgb} 与 {@code layeredSampleValue}。
	 */
	private int baseColorOf(BlockAndTintGetter level, BlockColors blockColors, BlockState state, BlockPos pos,
			boolean emissive) {
		// 岩浆整片用贴图的平均色（见 BlockTextureColors#lavaColor），不再按坐标采样出斑块
		if (state.is(Blocks.LAVA)) {
			return textureColors.lavaColor(minecraft);
		}
		int base = emissive ? textureColors.colorOf(minecraft, state) : textureColors.coveredColorOf(minecraft, state);
		// 花与花簇在图上只占一格，贴图平均色会把花瓣和绿茎混成暗哑的橄榄色，铺在草色底子上
		// 几乎看不出来；这里单独给它们加点饱和度，让它们跳出来（其余方块仍走柔和化）
		if (state.is(BlockTags.FLOWERS)) {
			base = TextureAverage.saturate(base, TextureAverage.FLOWER_SATURATION);
		}
		if (state.is(Blocks.WATER)) {
			// 水的染色来自生物群系的水色，不走 BlockColors（它注册的是方块自身的染色）
			return multiply(base, BiomeColors.getAverageWaterColor(level, pos) & 0xFFFFFF);
		}
		List<BlockTintSource> tintSources = blockColors.getTintSources(state);
		if (tintSources.isEmpty()) {
			return base;
		}
		int tinted = tintSources.getFirst().colorInWorld(state, level, pos);
		if (tinted == -1) {
			return base;
		}
		return multiply(base, tinted & 0xFFFFFF);
	}

	/** 两路颜色按通道相乘（各通道 /255）。 */
	private static int multiply(int first, int second) {
		int r = ((first >> 16) & 0xFF) * ((second >> 16) & 0xFF) / 255;
		int g = ((first >> 8) & 0xFF) * ((second >> 8) & 0xFF) / 255;
		int b = (first & 0xFF) * (second & 0xFF) / 255;
		return r << 16 | g << 8 | b;
	}

	/** 把 {@code overlay} 按 {@code t}（0~1）盖在 {@code base} 上。 */
	private static int mix(int base, int overlay, double t) {
		double keep = 1.0 - Math.clamp(t, 0.0, 1.0);
		int r = (int) (((base >> 16) & 0xFF) * keep + ((overlay >> 16) & 0xFF) * (1.0 - keep));
		int g = (int) (((base >> 8) & 0xFF) * keep + ((overlay >> 8) & 0xFF) * (1.0 - keep));
		int b = (int) ((base & 0xFF) * keep + (overlay & 0xFF) * (1.0 - keep));
		return Math.clamp(r, 0, 255) << 16 | Math.clamp(g, 0, 255) << 8 | Math.clamp(b, 0, 255);
	}

	/**
	 * 关掉当前存档的地图数据，并断开与它的关联。
	 *
	 * <p><b>退出世界时必须调。</b>地图数据放在存档目录里面，而读盘用的是常驻的
	 * {@code RandomAccessFile}；句柄不关，Windows 上整个存档目录就删不掉（"退出世界后删存档报失败"）。
	 *
	 * <p>平时换关卡时会顺带关掉旧的（见 {@link #ensureStorage}），但退出世界之后地图不再渲染、那个方法
	 * 不会被调用，所以只能在断开连接时显式关一次。
	 */
	public void closeStorage() {
		// storageLevel 归零，下一次 ensureStorage 无论拿到什么关卡都会重新打开
		storageLevel = null;
		// 断开世界：后台可能还在读上一个存档的区块，计数一涨它们回来时就会被丢掉
		prefetchGeneration++;
		prefetchQueued.clear();
		prefetchMissing.clear();
		prefetchReady.clear();
		if (storage != null) {
			storage.close();
			storage = null;
		}
	}

	/** 世界变了、或世界地图换了显示维度，就换一套目录，避免把不同存档/维度的地图混在一起。 */
	private void ensureStorage() {
		ClientLevel level = minecraft.level;
		// 地图看哪个维度就以哪个维度为准（没切过时就是玩家所在的维度）
		String dimension = viewDimension != null
				? viewDimension
				: (level == null ? null : level.dimension().identifier().toString());
		if (level == storageLevel && dimension != null && dimension.equals(storageDimension)) {
			return;
		}
		storageLevel = level;
		storageDimension = dimension;
		if (storage != null) {
			storage.close();
			storage = null;
		}
		if (level == null || dimension == null) {
			return;
		}
		Path root = MapStorage.currentRoot(minecraft);
		if (root == null) {
			return;
		}
		// 维度必须进路径：三个维度的区块坐标各自独立，混在一起会互相覆盖（"数据串了"）
		String dimensionKey = dimension.replace(':', '_').replace('/', '_');
		storage = MapStorage.open(root, dimensionKey);
		// 换了世界：改动检测的记录一律作废，否则会把上一个存档的区块当成"改过"。
		// 原始数据也必须一起清掉——它只按区块坐标作键，不带世界标识，
		// 留着就会把上一个存档的地形直接画到新存档里（表现出来就是"两个存档的数据串了"）
		changedChunks.clear();
		pendingBlockChanges.clear();
		caveSlices.clear();
		surfaceSamples.clear();
		// 后台预读的排队/失败记录也一起作废：换了世界，"没有过"的结论不再成立。
		// 队列里还没搬的成品直接丢掉——它们属于上一个世界，搬进来就是串数据。
		// 计数一涨，后台那条还在读的区块回来时也会被认出来、丢掉
		prefetchGeneration++;
		prefetchQueued.clear();
		prefetchMissing.clear();
		prefetchReady.clear();
		lastRefreshNanos = 0L;
		refreshBaseChunkX = Integer.MIN_VALUE;
		refreshBaseChunkZ = Integer.MIN_VALUE;
		LOGGER.info("地图数据目录 | {}", storage.root());
	}
}
