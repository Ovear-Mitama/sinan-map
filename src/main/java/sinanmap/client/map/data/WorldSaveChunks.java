package sinanmap.client.map.data;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import com.mojang.serialization.Codec;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sinanmap.common.SinanConstants;
import sinanmap.common.map.LayerSelector;
import sinanmap.common.storage.ChunkRecord;

/**
 * 直接从世界存档的区域文件里读地形。
 *
 * <p>这是第三个、也是优先级最低的数据来源，但它是唯一与"玩家有没有到过这里"无关的来源：前两个
 * （已加载区块、自己写下的存档）都要求区块被加载过，远处因此永远是未探明；世界存档里本来就有全部
 * 已生成区块的数据，直接读它就能把远处补上。
 *
 * <p>做法与同类实现相同：直接读区域文件里的区块 NBT，逐节解出方块调色板、光照与生物群系，再收成
 * 逐列记录。三点关键差异：
 *
 * <ul>
 *   <li>调色板只有一项且非空气时<section 里<b>没有</b> {@code data}，整节都是那一种方块；</li>
 *   <li>光照是每节两张半字节数组（{@code SkyLight}/{@code BlockLight}），索引 {@code (y << 8) | (z << 4) | x}；</li>
 *   <li>未生成到位的区块必须跳过，而<b>门槛是 {@code LIGHT} 而不是 {@code FEATURES}</b>：
 *       {@code features} 阶段的区块地形已写好、但光照还没写，读出来每一列天空光都是 0——画到地图上
 *       就是一片深浅不一的黑块，等它长到 {@code full} 再读又会由黑变亮。</li>
 * </ul>
 *
 * <p><b>能力边界</b>：读的是本机磁盘上的存档，因此只在单人（集成服务端）与局域网主机上可用；
 * 连别人的服务器时客户端拿不到存档，那里只能维持"未探明"。
 */
public final class WorldSaveChunks {

	/** 区域文件的一个扇区。 */
	private static final int SECTOR_BYTES = 4096;

	/** 单个区块解压后的上限，防止读到损坏的长度字段时分配巨大数组。 */
	private static final int MAX_CHUNK_BYTES = 16 * 1024 * 1024;

	/** 窗口外、或解不出方块时一律当作空气。 */
	private static final BlockState AIR = Blocks.AIR.defaultBlockState();

	/**
	 * "选列"只用到的那一半判定：<b>只看方块状态，不碰客户端关卡</b>。
	 *
	 * <p>从 {@link ColumnColor} 里拆出来是为了让"选列"与"着色"分家：选列只需知道"这一格是不是水/冰、
	 * 是不是要看穿的细碎方块"，这些由方块状态唯一决定，因此<b>可以放到后台线程上跑</b>
	 * （见 {@link #scanCaveColumns}）；着色则要问关卡取贴图配色与生物群系染色，只能在渲染线程上做。
	 *
	 * <p>这三个判定实时采样那条路也一直在用，两边问的是同一份规则，不会画出两种样子。
	 */
	interface ColumnShape {

		/** 这个方块是不是"盖在地形之上、底下仍要看一眼"的一层（水、冰）。 */
		boolean isLayered(BlockState state);

		/** 这一层是不是平铺的流体（水）：它和冰的明暗处理不同，见 {@code ChunkRecord#FLAG_FLAT_SHEET}。 */
		boolean isWater(BlockState state);

		/**
		 * 这个方块是不是"不是地面、要看穿它"的东西（火把、花草、玻璃……）。
		 *
		 * <p>默认不忽略；它与实时采样必须用<b>同一份名单</b>，两边差一点就会在"已加载范围"的边界上
		 * 画出一条缝——里侧是草地、外侧是火把色。
		 */
		default boolean shouldIgnore(BlockState state) {
			return false;
		}
	}

	/**
	 * 逐列取色的回调。
	 *
	 * <p>方块状态的解释权在调用方：只有它知道贴图配色、本模组的染色规则与半透明层怎么合成。
	 * 它继承 {@link ColumnShape} 的三个判定（后台扫描也要用）；多出来的两个方法要问客户端关卡，
	 * <b>只能在渲染线程上调用</b>。
	 */
	interface ColumnColor extends ColumnShape {

		/**
		 * 方块本色，0xRRGGBB。
		 *
		 * <p>群系来源见 {@code ClientChunkColorProvider} 的取色入口：已加载区块问客户端关卡，未加载
		 * （远处）的区块读世界存档，因此与实时采样那条路问的是同一个东西。
		 *
		 * @param worldY 这一格自己的高度。群系是<b>三维</b>的（洞穴群系与地表不同），必须按这一格问，
		 *               不能用一个固定的 y
		 */
		int rgb(BlockState state, int worldX, int worldY, int worldZ);

		/**
		 * 把这一层盖到底色上。
		 *
		 * <p>深度只压暗<b>水色自己</b>，不压底色（底那部分要一直透出来）；其余明暗由着色时统一算，
		 * 调整它不需要递增数据格式版本。
		 *
		 * @param bedRgb     底下那层的本色
		 * @param layerState 层那一格的状态（水或冰）
		 * @param worldY     层自己的高度（水色的群系按它问）
		 * @param depth      层顶面到底面的格数
		 */
		int layered(int bedRgb, BlockState layerState, int worldX, int worldY, int worldZ, int depth);

		/**
		 * 被水盖住的那一列的底色，0xRRGGBB。
		 *
		 * <p>与 {@link #rgb} 只差一处：把"底色是不是自发光方块（岩浆块）"强制当成否，不再按"光源"档
		 * 取色。水面合成只压覆盖层、不压底（见 {@link #layered}），底的亮度全靠这里定——仍按光源
		 * 取色就会透出一块偏亮的色斑。只在覆盖层是水、且底色自发光时才有区别；实时采样必须用同一条
		 * 规则，否则加载范围的边界上会出现一道"近处水底偏暗、远处偏亮"的缝。
		 *
		 * @param worldY 底色自己那一格的高度（群系按它问，见 {@link #rgb}）
		 */
		int coveredRgb(BlockState bedState, int worldX, int worldY, int worldZ);
	}

	/** 前几次尝试都记一行日志：这条路到底成不成立，看日志比猜快。 */
	private static final Logger LOGGER = LoggerFactory.getLogger(SinanConstants.MOD_ID);

	/** 头几次尝试各记一行原因，最多这么多行。 */
	private static final int DIAGNOSTIC_LIMIT = 12;
	private static int diagnostics;

	private static void diagnose(int chunkX, int chunkZ, String message) {
		if (diagnostics >= DIAGNOSTIC_LIMIT) {
			return;
		}
		diagnostics++;
		LOGGER.info("世界存档读取 | 区块({}, {}) {}", chunkX, chunkZ, message);
	}

	/** 尝试读多少个区块之后汇报一次命中情况。 */
	private static final int SUMMARY_ATTEMPTS = 200;

	private static int attempts;
	private static int hits;
	private static boolean pathReported;
	/** 读到的天空光总和与列数：用来在日志里一眼看出"光照取错格子"这类问题。 */
	private static long skyLightSum;
	private static long skyLightColumns;

	private WorldSaveChunks() {
	}

	/**
	 * 世界存档里这一块有没有可能读到数据（只看区域文件在不在）。
	 *
	 * <p>给调用方的<b>廉价预判</b>：区域文件不存在就绝无可能有数据，立刻按未探明处理。它也是
	 * "自己的存档里没有"之后唯一还能救回远处地形的判据，因此调用方<b>不能</b>用"自己没写过"把它
	 * 一并挡掉——挡掉就等于已加载范围之外永远画不出来。
	 */
	public static boolean hasRegion(Minecraft minecraft, int chunkX, int chunkZ) {
		Path region = regionFile(minecraft, chunkX, chunkZ);
		return region != null && regionExists(region);
	}

	/**
	 * 指定维度的区域文件在不在。
	 *
	 * <p>世界地图切到别的维度时也要能读那片地形：存档里每个维度都有自己的 region 目录，
	 * 按"正在查看的维度"问就有数据（联机时整个存档都拿不到，仍然是 null）。
	 */
	public static boolean hasRegion(Minecraft minecraft, String dimension, int chunkX, int chunkZ) {
		Path region = regionFile(minecraft, dimension, chunkX, chunkZ);
		return region != null && regionExists(region);
	}

	/** 这个维度在存档里有没有 region 目录（世界地图列出可切换维度时用）。 */
	public static boolean hasRegionDirectory(Minecraft minecraft, String dimension) {
		return regionDirectory(minecraft, dimension) != null;
	}

	/**
	 * 后台读一次区块的结果。
	 *
	 * @param tag        读到的原始 NBT；读不到时为 null
	 * @param retryLater 这个"没有"是不是<b>临时的</b>：区块还没生成到位，等它生成出来再读就有了。
	 *                   <b>必须与"区域文件里确实没有"分开</b>，混为一谈会让这一块被永久当成"没有数据"
	 *                   ——"加载了一点就不动了"
	 * @param lightMissing 地形写好了、但<b>光照还没写到</b>。这时存档里的 {@code SkyLight} 往往整片是 0，
	 *                     直接读出来整块会画成黑的；收记录时按"满天空光"兜底（见 {@link #toRecord}），
	 *                     而不是拒收这一块。
	 */
	record ChunkRead(CompoundTag tag, boolean retryLater, boolean lightMissing) {
	}

	/**
	 * 从区域文件里读出一个区块的原始 NBT（读不到时返回 {@link ChunkRead#tag()} 为 null）。
	 *
	 * <p><b>只在后台线程上跑</b>（{@code sinanmap-prefetch}）：做的是磁盘 I/O 与 NBT 解析，不碰游戏状态。
	 * 为什么停在这里：把 NBT 收成逐列记录要用方块本色、要问生物群系，那些得从 {@code ClientLevel} 取，
	 * 离开渲染线程去读客户端区块是危险的。分工：后台读文件，渲染线程收记录（见 {@link #makeRecord}）。
	 *
	 * <p>它仍会碰区域文件存在性缓存（已加锁）与诊断日志计数（普通 int，最坏多打或少打一行）。
	 *
	 * @param region 区域文件路径；由渲染线程算好传进来，后台线程不必再问存档根目录
	 */
	static ChunkRead readTag(Path region, int chunkX, int chunkZ) {
		CompoundTag chunk = readChunkNbt(region, chunkX, chunkZ);
		if (chunk == null) {
			diagnose(chunkX, chunkZ, "区域文件里没有这个区块，或解析失败");
			return new ChunkRead(null, false, false);
		}
		if (!isGenerated(chunk)) {
			diagnose(chunkX, chunkZ, "区块还没生成完 | Status=" + chunk.getStringOr("Status", "?"));
			// 临时没有：地图先把它当未探明，但不许记成"永远没有"，留给重试轮转去补
			return new ChunkRead(null, true, false);
		}
		return new ChunkRead(chunk, false, !reachesLight(chunk));
	}

	/** 这一块是不是已经生成到"光照也写好了"。低于这一步的块，天空光要按满的兜底。 */
	private static boolean reachesLight(CompoundTag chunk) {
		ChunkStatus resolved = resolveStatus(chunk.getStringOr("Status", ""));
		return resolved != null && resolved.getIndex() >= ChunkStatus.LIGHT.getIndex();
	}

	/**
	 * 渲染线程：把读到的原始 NBT 收成记录。
	 *
	 * <p>地表给"每列最上面那一格"，洞穴给"层高度那一带某个高度上的切面"——后者要在每列层高度附近
	 * 找空气、再往下找地板，规则与实时采样一致（见 {@link LayerSelector#CAVE_TOLERANCE}），只是数据
	 * 来源换成了存档里的节。
	 *
	 * <p>洞穴那条在这里只是把"扫描 + 着色"串起来的便捷入口；热路径上扫描单独在后台做
	 * （见 {@link #scanCaveColumns} / {@link #makeCaveRecord}）。
	 *
	 * @param layer 地表，或<b>归档后</b>的洞穴层高度（{@link LayerSelector#storageLayer}；预读/落盘都按它）
	 * @param chunk 后台线程读到的原始 NBT；为 null 时直接返回 null
	 * @param ceiling 这个维度有没有天花板（地狱这类）：地表要跳过最上面那段实心，
	 *                见 {@code ClientChunkColorProvider#terrainTopBelowCeiling}
	 * @return 整块一个非空气方块都没有时返回 null
	 */
	static ChunkRecord makeRecord(int layer, CompoundTag chunk, int chunkX, int chunkZ, ColumnColor colors,
			boolean lightMissing, boolean ceiling) {
		if (chunk == null) {
			return null;
		}
		if (LayerSelector.isCave(layer)) {
			// 洞穴：扫描与着色已经拆开（见 scanCaveColumns / colorCaveColumns），这里替调用方串起来
			return makeCaveRecord(scanCaveColumns(chunk, chunkX, chunkZ, layer, colors), chunkX, chunkZ, colors);
		}
		noteAttempt();
		ChunkRecord record = toRecord(chunk, chunkX, chunkZ, colors, lightMissing, ceiling);
		if (record == null) {
			diagnose(chunkX, chunkZ, "整块没有非空气方块");
			return null;
		}
		hits++;
		// 平均天空光只统计地表：洞穴层的地板本来就照不到天，混进来只会把这条诊断弄糊
		for (int index = 0; index < ChunkRecord.COLUMNS; index++) {
			skyLightSum += record.skyLight(index);
		}
		skyLightColumns += ChunkRecord.COLUMNS;
		if (hits <= 3) {
			diagnose(chunkX, chunkZ, "读到一份");
		}
		return record;
	}

	/**
	 * 渲染线程：把后台扫好的洞穴逐列结果着色成记录，并做与地表同一套的诊断计数。
	 *
	 * <p>与 {@link #makeRecord} 的区别只在入口：那边拿到原始 NBT（要在这里扫描），这边拿到已经扫好的
	 * {@link CaveColumn}（扫描已在前面的并行阶段做掉）。
	 */
	static ChunkRecord makeCaveRecord(CaveColumn[] columns, int chunkX, int chunkZ, ColumnColor colors) {
		noteAttempt();
		ChunkRecord record = colorCaveColumns(columns, colors);
		hits++;
		if (hits <= 3) {
			diagnose(chunkX, chunkZ, "读到一份");
		}
		return record;
	}

	/**
	 * 世界存档里某一格所在的生物群系 id（如 {@code minecraft:plains}）；读不到时返回 null。
	 *
	 * <p>{@code level.getBiome(pos)} 只对<b>客户端已加载</b>的区块有效，而地图连远处也画出来了——
	 * 鼠标停在那里时群系那一行会空着。存档里的区块 NBT 本来就带群系（每节一份 4×4×4 的调色板数据），
	 * 直接读它，显示范围就与地图一致。
	 *
	 * <p>解析交给原版编解码器（{@link PalettedContainerFactory#biomeContainerCodec()}），不自己解按位
	 * 打包：那条规则有个"调色板很小时磁盘位数与内存不一致"的分支，自己写最容易出错，且错了不报错、
	 * 只安静显示成别的群系。
	 *
	 * <p>按"区块 + 节"缓存：鼠标移动时每帧都会被问，一次区域文件读取（几十上百微秒）挂在渲染线程上
	 * 就是看得见的顿。
	 */
	public static String biomeIdAt(Minecraft minecraft, int blockX, int blockY, int blockZ) {
		ClientLevel level = minecraft.level;
		if (level == null) {
			return null;
		}
		return biomeIdAt(minecraft, level.dimension().identifier().toString(), blockX, blockY, blockZ);
	}

	/** 指定维度某一格的群系 id；读不到时返回 null。世界地图看别的维度时用这一条。 */
	public static String biomeIdAt(Minecraft minecraft, String dimension, int blockX, int blockY, int blockZ) {
		Holder<Biome> biome = biomeAt(minecraft, minecraft.level, dimension, blockX, blockY, blockZ);
		return biome == null ? null
				: biome.unwrapKey().map(holder -> holder.identifier().toString()).orElse(null);
	}

	/**
	 * 存档里某一格的群系；这一格所在的区块/节没有群系数据时返回 null。
	 *
	 * <p>给地图取色用（{@code WorldBiomeTints}）：未加载的区块没有客户端群系，但又必须按真实群系染色，
	 * 才能与走近之后看到的颜色一致。缓存与 {@link #biomeIdAt} 共用。
	 *
	 * <p>{@code dimension} 是"去哪个维度读"：玩家所在维度与"世界地图正在看的那个维度"都可能不是同一个。
	 *
	 * <p><b>只在渲染线程调用</b>：缓存本身不是并发容器（{@link #biomeIdAt} 也是渲染线程上的悬停提示）。
	 */
	static Holder<Biome> biomeAt(Minecraft minecraft, ClientLevel level, String dimension,
			int blockX, int blockY, int blockZ) {
		ensureBiomeDimension(dimension);
		PalettedContainerRO<Holder<Biome>> section = biomeSection(minecraft, level, dimension,
				blockX >> 4, blockZ >> 4, blockY >> 4);
		if (section == null) {
			return null;
		}
		// 群系是每 4×4×4 格一个值（quart 坐标），不是每格一个：这一节的容器尺寸是 4×4×4，
		// 传 0~15 进去会越界。调色板不止一个值时它是安静地读到隔壁那一格的群系，
		// 而整节同一个群系时原版用的是零位打包、会当场校验并抛异常
		// （"The value 108 is not in the specified inclusive range of 0 to 63"）——整局崩溃。
		// 所以三个分量都要先右移 2 位。
		return section.get((blockX & 15) >> 2, (blockY & 15) >> 2, (blockZ & 15) >> 2);
	}

	/** 群系节缓存只按"区块 + 节"作键：换了维度（或换世界）先整体作废，否则会读到另一个维度的群系。 */
	private static void ensureBiomeDimension(String dimension) {
		if (dimension.equals(biomeDimension)) {
			return;
		}
		biomeDimension = dimension;
		BIOME_SECTIONS.clear();
		lastBiomeKey = Long.MIN_VALUE;
		lastBiomeSection = null;
	}

	/**
	 * 取某一节的群系容器，按"区块 + 节"缓存。
	 *
	 * <p>多一层"上一节"的记录：取色时同一列/相邻几格会连续问同一节（一次邻域混合就是 25 次），
	 * 有它就不必每次都进 LRU 表。读不到（还没有/还没生成）时不记，留着下次再问。
	 */
	private static PalettedContainerRO<Holder<Biome>> biomeSection(Minecraft minecraft, ClientLevel level,
			String dimension, int chunkX, int chunkZ, int sectionY) {
		long key = biomeSectionKey(chunkX, chunkZ, sectionY);
		if (key == lastBiomeKey && lastBiomeSection != null) {
			return lastBiomeSection;
		}
		PalettedContainerRO<Holder<Biome>> section = BIOME_SECTIONS.get(key);
		if (section == null) {
			section = readBiomeSection(minecraft, level, dimension, chunkX, chunkZ, sectionY);
			if (section == null) {
				return null;
			}
			BIOME_SECTIONS.put(key, section);
		}
		lastBiomeKey = key;
		lastBiomeSection = section;
		return section;
	}

	/** "区块 + 节"的缓存键，见 {@link #biomeSection}。 */
	private static long biomeSectionKey(int chunkX, int chunkZ, int sectionY) {
		return (long) (chunkX & 0xFFFFF) << 40 | (long) (chunkZ & 0xFFFFF) << 20 | sectionY & 0xFFFFF;
	}

	/**
	 * 把这次<b>已经解析好</b>的区块 NBT 里的群系节记进缓存。
	 *
	 * <p>着色（{@link #biomeAt}）要按列问群系，而这时同一块的 NBT 刚刚由后台线程读进来、已经在手上——
	 * 不先记下来，着色就会为同一块再读一遍区域文件（渲染线程上的重复磁盘 I/O，一页上千块就是上千次）。
	 *
	 * <p>只在渲染线程调用（{@link #BIOME_SECTIONS} 不是并发容器）。
	 */
	static void rememberBiomeSections(CompoundTag chunk, int chunkX, int chunkZ, ClientLevel level, String dimension) {
		if (level == null) {
			return;
		}
		ensureBiomeDimension(dimension);
		Codec<PalettedContainerRO<Holder<Biome>>> codec = biomeCodec(level);
		if (codec == null) {
			return;
		}
		ListTag sections = chunk.getListOrEmpty("sections");
		for (int i = 0; i < sections.size(); i++) {
			CompoundTag section = sections.getCompoundOrEmpty(i);
			int sectionY = section.getByteOr("Y", (byte) 0);
			section.getCompound("biomes")
					.flatMap(tag -> codec.parse(NbtOps.INSTANCE, tag).result())
					.ifPresent(container -> BIOME_SECTIONS.put(
							biomeSectionKey(chunkX, chunkZ, sectionY), container));
		}
	}

	private static long lastBiomeKey = Long.MIN_VALUE;
	private static PalettedContainerRO<Holder<Biome>> lastBiomeSection;
	/** 当前群系节缓存是按哪个维度读出来的，见 {@link #ensureBiomeDimension}。 */
	private static String biomeDimension;

	/** 解析某一节里的群系调色板数据；拿不到就返回 null。 */
	private static PalettedContainerRO<Holder<Biome>> readBiomeSection(
			Minecraft minecraft, ClientLevel level, String dimension, int chunkX, int chunkZ, int sectionY) {
		Path region = regionFile(minecraft, dimension, chunkX, chunkZ);
		if (region == null) {
			return null;
		}
		CompoundTag chunk = readChunkNbt(region, chunkX, chunkZ);
		if (chunk == null) {
			return null;
		}
		Codec<PalettedContainerRO<Holder<Biome>>> codec = biomeCodec(level);
		if (codec == null) {
			return null;
		}
		ListTag sections = chunk.getListOrEmpty("sections");
		for (int i = 0; i < sections.size(); i++) {
			CompoundTag section = sections.getCompoundOrEmpty(i);
			if (section.getByteOr("Y", (byte) 0) != sectionY) {
				continue;
			}
			// 没有 biomes 这一节（很旧的存档或刚生成的区块）：当作没有
			return section.getCompound("biomes")
					.flatMap(tag -> codec.parse(NbtOps.INSTANCE, tag).result())
					.orElse(null);
		}
		return null;
	}

	/** 读不到地表高度时的返回值。 */
	public static final int NO_HEIGHT = Integer.MIN_VALUE;
	/**
	 * 已解析的高度图，按区块缓存：{@code int[256]}，索引 {@code x + z * 16}，值是绝对 Y
	 * （{@link #NO_HEIGHT} 表示这一列没有数据）。
	 *
	 * <p>鼠标移动时这一列的 Y 每帧都要问，而一次区域文件读取是几十上百微秒——挂在渲染线程上就是
	 * 看得见的顿。缓存后同一区块内移动只是数组查表。
	 */
	private static final int HEIGHT_CACHE_LIMIT = 12;
	private static final Map<Long, int[]> HEIGHT_CACHE = new LinkedHashMap<>(16, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, int[]> eldest) {
			return size() > HEIGHT_CACHE_LIMIT;
		}
	};

	/**
	 * 世界存档里这一列的地表高度（<b>第一格空气</b>的 Y），读不到时返回 {@link #NO_HEIGHT}。
	 *
	 * <p>地图上画出来的远处地形大多没加载，客户端高度图问不到值，于是"传送到这一格"会落到猜出来的 Y
	 * （以前固定 64），光标那一行也只好不显示。存档里的区块本来就带原版高度图：每列按
	 * {@code x + z * 16} 索引、位数 {@code ceillog2(高度 + 1)}、存的是相对最低建筑高度的值。
	 *
	 * <p>取 {@code MOTION_BLOCKING} 而不是 {@code WORLD_SURFACE}：后者把草、花也算作地表，传送到那里
	 * 会卡在一丛草里；前者是"真正挡路的那一格"，加一正好站上去。
	 */
	public static int surfaceHeightAt(Minecraft minecraft, int blockX, int blockZ) {
		ClientLevel level = minecraft.level;
		if (level == null) {
			return NO_HEIGHT;
		}
		return surfaceHeightAt(minecraft, level.dimension().identifier().toString(), blockX, blockZ);
	}

	/**
	 * 指定维度的地表高度。
	 *
	 * <p>世界地图看别的维度时用它：那一维度的高度图在它自己的 region 文件里，按玩家所在维度问会读到
	 * 另一个世界的高度（"光标处的 Y 串了"）。
	 */
	public static int surfaceHeightAt(Minecraft minecraft, String dimension, int blockX, int blockZ) {
		int[] heights = heightsOf(minecraft, dimension, blockX >> 4, blockZ >> 4);
		if (heights == null) {
			return NO_HEIGHT;
		}
		return heights[(blockX & 15) + (blockZ & 15) * 16];
	}

	/** 取（并缓存）某个区块整块的高度图；读不到时返回 null。 */
	private static int[] heightsOf(Minecraft minecraft, String dimension, int chunkX, int chunkZ) {
		ClientLevel level = minecraft.level;
		if (level == null) {
			return null;
		}
		ensureHeightDimension(dimension);
		long key = (long) chunkX << 32 | chunkZ & 0xFFFFFFFFL;
		int[] cached = HEIGHT_CACHE.get(key);
		if (cached != null) {
			return cached;
		}
		Path region = regionFile(minecraft, dimension, chunkX, chunkZ);
		CompoundTag chunk = region == null ? null : readChunkNbt(region, chunkX, chunkZ);
		long[] raw = chunk == null ? null
				// 键名是 Heightmaps（大写 H），原版就是这么写的；写成小写会一直取不到，
				// 于是"光标处的 Y"在未加载区域永远少一个来源
				: chunk.getCompoundOrEmpty("Heightmaps").getLongArray("MOTION_BLOCKING").orElse(null);
		int[] heights = new int[256];
		boolean read = false;
		if (raw != null) {
			try {
				// 位数必须与原版建这张表时一致，否则解出来的是别的东西
				SimpleBitStorage storage = new SimpleBitStorage(Mth.ceillog2(level.getHeight() + 1), 256, raw);
				int minY = level.getMinY();
				for (int i = 0; i < heights.length; i++) {
					heights[i] = storage.get(i) + minY;
				}
				read = true;
			} catch (RuntimeException e) {
				// 位数或长度对不上（很旧的存档、或被别的工具改过）：当作读不到
				read = false;
			}
		}
		if (!read) {
			// 也要缓存："读不到"同样是个结论，不记下来的话，鼠标停在那一片上
			// 每帧都会去重读一次区域文件
			Arrays.fill(heights, NO_HEIGHT);
		}
		HEIGHT_CACHE.put(key, heights);
		return heights;
	}

	/**
	 * 高度图缓存只按"区块坐标"作键、不带维度：换了维度先整体作废，
	 * 否则看主世界时会把下界那边同坐标的高度直接报出来（光标处的 Y 串了）。
	 */
	private static void ensureHeightDimension(String dimension) {
		if (dimension.equals(heightDimension)) {
			return;
		}
		heightDimension = dimension;
		HEIGHT_CACHE.clear();
	}

	/** 当前高度图缓存是按哪个维度读出来的，见 {@link #ensureHeightDimension}。 */
	private static String heightDimension;

	/**
	 * 这一列给定高度上能不能站人（脚与头两格都不是挡路的方块）。
	 *
	 * <p>传送前用它判断路径点里存的 Y 还能不能用：洞穴里的路径点 Y 明显低于地表，一律按地表高度传送
	 * 就会把人从洞里拽到地面上——<b>站得住就用它，站不住才改用世界高度</b>。
	 *
	 * @return 拿不准（区块没加载、存档里读不到、或脚正好踩在节边界上）时返回 null
	 */
	// blocksMotion() 在原版被标了过时，但这里要的就是它：原版 MOTION_BLOCKING 高度图
	//（上面 surfaceHeightAt 读的那张）用的正是同一个判定，换一个口径就会出现
	//"存档说有地面、客户端说站不上去"这类对不上的情况
	@SuppressWarnings("deprecation")
	public static Boolean standableAt(Minecraft minecraft, int blockX, int blockY, int blockZ) {
		ClientLevel level = minecraft.level;
		if (level == null) {
			return null;
		}
		if (level.getChunkSource().getChunk(blockX >> 4, blockZ >> 4, ChunkStatus.FULL, false) != null) {
			// 已加载：直接问客户端，最准也最快
			BlockPos pos = new BlockPos(blockX, blockY, blockZ);
			return !level.getBlockState(pos).blocksMotion()
					&& !level.getBlockState(pos.above()).blocksMotion();
		}
		// 存档这条路是按"节"取的，头那一格跨到下一节时读数会错位——交给调用方按"拿不准"处理
		if ((blockY & 15) == 15) {
			return null;
		}
		Path region = regionFile(minecraft, blockX >> 4, blockZ >> 4);
		CompoundTag chunk = region == null ? null : readChunkNbt(region, blockX >> 4, blockZ >> 4);
		if (chunk == null) {
			return null;
		}
		ListTag sections = chunk.getListOrEmpty("sections");
		int sectionY = blockY >> 4;
		for (int i = 0; i < sections.size(); i++) {
			CompoundTag section = sections.getCompoundOrEmpty(i);
			if (section.getByteOr("Y", (byte) 0) != sectionY) {
				continue;
			}
			SectionBlocks blocks = SectionBlocks.parse(section);
			return !blocks.state(blockX & 15, blockY & 15, blockZ & 15).blocksMotion()
					&& !blocks.state(blockX & 15, (blockY & 15) + 1, blockZ & 15).blocksMotion();
		}
		return null;
	}

	/** 原版的群系调色板编解码器；第一次用到时按当前注册表建一份，之后一直复用。 */
	private static Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec(ClientLevel level) {
		RegistryAccess registries = level.registryAccess();
		if (registries == null) {
			return null;
		}
		if (biomeCodec == null || biomeCodecRegistries != registries) {
			biomeCodecRegistries = registries;
			biomeCodec = PalettedContainerFactory.create(registries).biomeContainerCodec();
		}
		return biomeCodec;
	}

	private static Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec;
	private static RegistryAccess biomeCodecRegistries;

	/**
	 * 已解析的群系节，见 {@link #biomeIdAt}。
	 *
	 * <p>取色会一次把整块（约 20~30 节）都记进来（见 {@link #rememberBiomeSections}），而洞穴的着色
	 * 要等后台扫描完成、隔一两帧才做——上限太小会把刚记下的节挤掉、逼着色再读一次文件。
	 * 每节只是一份很小的调色板容器，{@value #BIOME_SECTION_LIMIT} 节的内存仍然只有几 MB。
	 */
	private static final int BIOME_SECTION_LIMIT = 2048;
	private static final Map<Long, PalettedContainerRO<Holder<Biome>>> BIOME_SECTIONS =
			new LinkedHashMap<>(128, 0.75F, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<Long, PalettedContainerRO<Holder<Biome>>> eldest) {
					return size() > BIOME_SECTION_LIMIT;
				}
			};

	/** 第一次询问时把"这条路通不通"记一行日志：查这个问题看日志比猜快。 */
	static void reportPath(Path region) {
		if (pathReported) {
			return;
		}
		pathReported = true;
		LOGGER.info(region == null
				? "世界存档读取 | 拿不到存档路径（连接远程服务器时读不到，远处只能维持未探明）"
				: "世界存档读取 | 存档目录 " + region.getParent());
	}

	/** 前 {@value #SUMMARY_ATTEMPTS} 次尝试里汇报一次命中情况，确认这条路真的读到了东西。 */
	private static void noteAttempt() {
		if (++attempts == SUMMARY_ATTEMPTS) {
			// 平均天空光顺带报一下：地表在露天，这个值应当在 12~15。
			// 明显偏小就说明光照读错了格子（固体方块自身的光照恒为 0），那会让整片地形发暗
			LOGGER.info("世界存档读取 | 前 {} 次尝试命中 {} 个区块 | 平均天空光 {}",
					attempts, hits,
					skyLightColumns == 0 ? "-" : "%.1f".formatted(skyLightSum / (double) skyLightColumns));
		}
	}

	/**
	 * 这一列数据所在维度的 region 目录。
	 *
	 * <p><b>26.1 起所有维度（含主世界）都摆在 {@code dimensions/<命名空间>/<路径>} 下</b>：主世界不再
	 * 直接放在存档根目录，下界与末地也不再叫 {@code DIM-1} / {@code DIM1}。按旧布局拼只会得到一个不
	 * 存在的目录，整条路一个区块都读不出。这也顺带解决了模组维度。
	 *
	 * <p>只在单人/局域网主机上拿得到：世界路径在集成服务端手里，纯客户端连远程服时这里是 null。
	 *
	 * <p><b>按维度缓存</b>：地图可以在世界地图界面里"看"另一个维度（玩家还站在原地），那时读的是
	 * <b>正在查看的那个维度</b>的 region 目录，不能按玩家所在维度拼。结果按维度 id 各缓存一份。
	 *
	 * <p><b>"没有"这个结论只信一小会儿。</b>刚进世界时这条路径可能还问不出来（集成服务端还没挂上、
	 * region 目录还没建），那不是"永远没有"——缓存成 null 的结果到 {@link #REGION_DIR_RETRY_NANOS}
	 * 之后会重问一次。
	 */
	static Path regionDirectory(Minecraft minecraft) {
		ClientLevel level = minecraft.level;
		if (level == null) {
			return null;
		}
		return regionDirectory(minecraft, level.dimension().identifier().toString());
	}

	/** 指定维度的 region 目录；拿不到（联机、还没建）时为 null。 */
	static Path regionDirectory(Minecraft minecraft, String dimension) {
		ClientLevel level = minecraft.level;
		long now = System.nanoTime();
		if (level != null && level != regionDirLevel) {
			// 换了关卡实例（换维度也是换实例）：群系节缓存与目录缓存都只按区块坐标/维度 id 作键，
			// 留着会让下界/末地读到主世界的群系（取色时整片染错）。只清群系，高度缓存另有它的用途。
			BIOME_SECTIONS.clear();
			lastBiomeKey = Long.MIN_VALUE;
			lastBiomeSection = null;
			biomeDimension = null;
			REGION_DIRS.clear();
			REGION_DIR_CHECKED.clear();
		}
		regionDirLevel = level;
		Path cached = REGION_DIRS.get(dimension);
		if (cached != null) {
			return cached;
		}
		Long checkedAt = REGION_DIR_CHECKED.get(dimension);
		if (checkedAt != null && now - checkedAt < REGION_DIR_RETRY_NANOS) {
			return null;
		}
		Path root = worldRoot(minecraft);
		Path region = null;
		if (root != null) {
			Path candidate = root.resolve("dimensions").resolve(dimension.replace(':', '/')).resolve("region");
			region = Files.isDirectory(candidate) ? candidate : null;
		}
		REGION_DIRS.put(dimension, region);
		REGION_DIR_CHECKED.put(dimension, now);
		return region;
	}

	/** 各维度的 region 目录（值为 null 表示"这个维度没有"，按 {@link #REGION_DIR_RETRY_NANOS} 重问）。 */
	private static final Map<String, Path> REGION_DIRS = new HashMap<>(4);
	private static final Map<String, Long> REGION_DIR_CHECKED = new HashMap<>(4);

	private static ClientLevel regionDirLevel;

	/** "这个维度没有 region 目录"这个结论多久重问一次。 */
	private static final long REGION_DIR_RETRY_NANOS = 2_000_000_000L;

	/** 某个区块落在给定 region 目录下的哪个区域文件上。纯拼路径，不碰磁盘。 */
	static Path regionFileIn(Path regionDir, int chunkX, int chunkZ) {
		return regionDir.resolve("r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca");
	}

	/** 某个区块落在哪个区域文件上；拿不到存档路径时为 null。 */
	private static Path regionFile(Minecraft minecraft, int chunkX, int chunkZ) {
		Path regionDir = regionDirectory(minecraft);
		return regionDir == null ? null : regionFileIn(regionDir, chunkX, chunkZ);
	}

	/** 指定维度的区域文件；维度没有 region 目录时为 null。 */
	static Path regionFile(Minecraft minecraft, String dimension, int chunkX, int chunkZ) {
		Path regionDir = regionDirectory(minecraft, dimension);
		return regionDir == null ? null : regionFileIn(regionDir, chunkX, chunkZ);
	}

	/**
	 * 存档根目录。
	 *
	 * <p>每个区块都要用它拼路径，而 {@code getWorldPath} 会新建几个对象、也可能被原版加进额外检查，
	 * 因此按服务端实例缓存一次；换了世界（服务端实例变了）就重来。
	 */
	private static MinecraftServer cachedServer;
	private static Path cachedRoot;

	private static Path worldRoot(Minecraft minecraft) {
		MinecraftServer server = minecraft.getSingleplayerServer();
		if (server != cachedServer) {
			cachedServer = server;
			cachedRoot = null;
			// region 目录是跟着根目录算出来的，根目录重来了它也得重算
			regionDirLevel = null;
			REGION_DIRS.clear();
			REGION_DIR_CHECKED.clear();
			pathReported = false;
			attempts = 0;
			hits = 0;
			diagnostics = 0;
			skyLightSum = 0L;
			skyLightColumns = 0L;
			// 换了世界：上一个存档解析出来的群系节不能再用了（同一区块坐标会是别的地形）
			BIOME_SECTIONS.clear();
			lastBiomeKey = Long.MIN_VALUE;
			lastBiomeSection = null;
			biomeDimension = null;
			// 高度图同理：它也按区块坐标作键、不带世界标识
			HEIGHT_CACHE.clear();
			heightDimension = null;
			if (server != null) {
				try {
					cachedRoot = server.getWorldPath(LevelResource.ROOT);
				} catch (RuntimeException e) {
					cachedRoot = null;
				}
			}
		}
		return cachedRoot;
	}

	private static final int PRESENCE_LIMIT = 4096;

	/**
	 * 区域文件存在性的缓存有效期。
	 *
	 * <p><b>负结果必须会过期</b>：区域文件是边玩边长出来的，缓存"不存在"不放就等于把
	 * 玩家往前探索出来的新区域永久判成没有数据。有效期与未探明区块的重试节奏一致
	 * （见 {@code WorldTilePages#retryUnexplored}），因此不会漏掉新长出来的区域。
	 */
	private static final long PRESENCE_TTL_NANOS = 8_000_000_000L;

	/** 表示"存在"，永不过期（文件不会自己消失）。 */
	private static final long PRESENT = Long.MAX_VALUE;

	/** 区域文件存在性：值为"到什么时候仍然可信"。 */
	private static final Map<Path, Long> REGION_PRESENCE = new LinkedHashMap<>(64, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Path, Long> eldest) {
			return size() > PRESENCE_LIMIT;
		}
	};

	/**
	 * 区域文件在不在。
	 *
	 * <p><b>渲染线程与后台预读都会调它</b>（后者在 {@link #readTag} 里），所以整段加了锁：这张表是按
	 * 访问顺序淘汰的 {@code LinkedHashMap}，两个线程同时动会把自己转晕。锁粒度是"一个区块一次"，
	 * 开销可以忽略。
	 */
	static boolean regionExists(Path region) {
		synchronized (REGION_PRESENCE) {
			long now = System.nanoTime();
			Long cached = REGION_PRESENCE.get(region);
			if (cached != null && (cached == PRESENT || now < cached)) {
				return cached == PRESENT;
			}
			boolean exists = Files.isRegularFile(region);
			REGION_PRESENCE.put(region, exists ? PRESENT : now + PRESENCE_TTL_NANOS);
			return exists;
		}
	}


	/** 区块是否已经生成到地形完整。 */
	private static boolean isGenerated(CompoundTag chunk) {
		String status = chunk.getStringOr("Status", "");
		if (status.isEmpty()) {
			return false;
		}
		ChunkStatus resolved = resolveStatus(status);
		// 门槛回到 FEATURES：提到 LIGHT 会把"地形已写好、光照还没写"的那一大批区块全判成
		// "还没生成完"（实测：整片新区域因此读不出来）。缺光照的问题在读记录那一侧用
		// "按满天空光兜底"解决，而不是靠拒绝这些区块。
		return resolved != null && resolved.getIndex() >= ChunkStatus.FEATURES.getIndex();
	}

	/**
	 * 解析存档里的 {@code Status}。
	 *
	 * <p><b>存档里存的是带命名空间的完整名字</b>（{@code minecraft:full}），而
	 * {@code ChunkStatus.byName} 只认不带命名空间的那个。直接丢过去会拿到 {@code null}，紧接着取下标
	 * 抛异常、被兜住返回 false，于是每个区块都被判成"还没生成完"，整条路一个区块都读不出来
	 * （日志里就是 {@code 命中 0 个区块}）。
	 *
	 * @return 解析不出来时返回 null
	 */
	private static ChunkStatus resolveStatus(String status) {
		ChunkStatus resolved = byName(status);
		if (resolved != null) {
			return resolved;
		}
		int colon = status.indexOf(':');
		return colon < 0 ? null : byName(status.substring(colon + 1));
	}

	private static ChunkStatus byName(String name) {
		try {
			return ChunkStatus.byName(name);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * 已经打开的区域文件通道（按访问顺序 LRU）。
	 *
	 * <p><b>这是取数吞吐的关键。</b>原来每读一块都 {@code Files.newByteChannel} 一次——一页 1024 块
	 * 就是 1024 次开文件 + 两千多次读；而同一页的块全在这一个区域文件里，开一次就够。
	 *
	 * <p><b>这里不能按"只有后台线程用"省并发控制。</b>{@link #readChunkNbt} 也会被渲染线程上的
	 * {@link #biomeIdAt} / {@link #surfaceHeightAt} / {@link #standableAt} 直接调用，于是同一份区域
	 * 文件被两条线程共用。共用本身没问题（{@link FileChannel} 允许多线程并发访问），但前提是
	 * <b>读的时候不改通道自己的 position</b>：老的"先 {@code position(p)} 再 {@code read(buf)}"会被
	 * 另一条线程插进来改掉 position，这次读落到错误的扇区、解析失败被吞成 {@code null}——偶发的
	 * "光标处 Y/群系为空"或"区块被误判成没有数据"。因此一律改用
	 * {@link FileChannel#read(ByteBuffer, long)}（带显式位置、<b>不改通道 position</b>）。
	 */
	private static final Map<Path, FileChannel> OPEN_CHANNELS =
			new LinkedHashMap<>(32, 0.75F, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<Path, FileChannel> eldest) {
					if (size() > MAX_OPEN_CHANNELS) {
						closeQuietly(eldest.getValue());
						return true;
					}
					return false;
				}
			};

	/** 同时最多开着几个区域文件。一屏就十几页，32 足够覆盖。 */
	private static final int MAX_OPEN_CHANNELS = 32;

	private static FileChannel channelFor(Path file) throws IOException {
		synchronized (OPEN_CHANNELS) {
			FileChannel cached = OPEN_CHANNELS.get(file);
			if (cached != null && cached.isOpen()) {
				return cached;
			}
			// 用 FileChannel.open 而不是 Files.newByteChannel：后者只保证返回 SeekableByteChannel，
			// 而需要的是它带显式位置的 read 重载（见 OPEN_CHANNELS 的说明）
			FileChannel opened = FileChannel.open(file, StandardOpenOption.READ);
			OPEN_CHANNELS.put(file, opened);
			return opened;
		}
	}

	private static void closeQuietly(FileChannel channel) {
		try {
			channel.close();
		} catch (IOException ignored) {
			// 关不上就算了：进程退出时操作系统会收
		}
	}

	/**
	 * 从区域文件里取出一个区块的 NBT。
	 *
	 * <p>区域文件的布局：头 4096 字节是 1024 个"位置"（3 字节扇区号 + 1 字节扇区数），头之后按扇区
	 * 存放区块，每个区块是 {@code 长度 + 压缩方式 + 数据}。这里手写而不用原版 {@code RegionFile}：
	 * 那个类的可见性与构造签名在版本间变过，而这段解析只有几十行，稳定得多。
	 *
	 * <p><b>全部读都用带显式位置的重载</b>（{@link FileChannel#read(ByteBuffer, long)}）：本方法会同时
	 * 被后台预读线程与渲染线程调用（见 {@link #OPEN_CHANNELS}），通道可能被两者共用，只有"不改通道
	 * position"的读才是并发安全的。</p>
	 */
	private static CompoundTag readChunkNbt(Path file, int chunkX, int chunkZ) {
		if (!regionExists(file)) {
			return null;
		}
		int index = (chunkX & 31) + (chunkZ & 31) * 32;
		try {
			// 通道是复用的（见 OPEN_CHANNELS）：不要在这里关它
			FileChannel channel = channelFor(file);
			ByteBuffer location = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
			if (readFully(channel, location, index * 4L)) {
				return null;
			}
			location.flip();
			int packed = location.getInt();
			int offset = (packed >>> 8) * SECTOR_BYTES;
			if (offset <= 0) {
				return null;
			}
			ByteBuffer entry = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN);
			if (readFully(channel, entry, offset)) {
				return null;
			}
			entry.flip();
			int length = entry.getInt();
			int compression = entry.get() & 0xFF;
			if (length <= 1 || length > MAX_CHUNK_BYTES) {
				return null;
			}
			ByteBuffer payload = ByteBuffer.allocate(length - 1);
			// 载荷紧跟在 5 字节的"长度 + 压缩方式"之后
			if (readFully(channel, payload, offset + 5L)) {
				return null;
			}
			byte[] raw = payload.array();
			byte[] data = switch (compression) {
				case 1 -> inflate(raw, true);
				case 2 -> inflate(raw, false);
				case 3 -> raw;
				default -> null;
			};
			if (data == null) {
				return null;
			}
			return NbtIo.read(new DataInputStream(new ByteArrayInputStream(data)));
		} catch (IOException | RuntimeException e) {
			// 读不到就是"没有"，交给上层当作未探明；不要让一次坏文件影响整张地图
			return null;
		}
	}

	/**
	 * 从指定位置读满 {@code buffer}；读不满返回 {@code true}。
	 *
	 * <p>用 {@link FileChannel#read(ByteBuffer, long)} 而不是 {@code position(...)} + {@code read(...)}：
	 * 前者带显式位置、<b>不改通道自己的 position</b>，因此同一通道被多条线程共用也不会彼此错位
	 * （见 {@link #OPEN_CHANNELS}）。
	 */
	private static boolean readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
		while (buffer.hasRemaining()) {
			int count = channel.read(buffer, position);
			if (count < 0) {
				return true;
			}
			position += count;
		}
		return false;
	}

	private static byte[] inflate(byte[] raw, boolean gzip) throws IOException {
		try (InputStream in = gzip
				? new GZIPInputStream(new ByteArrayInputStream(raw))
				: new InflaterInputStream(new ByteArrayInputStream(raw))) {
			return in.readAllBytes();
		}
	}

	/**
	 * 洞穴切面里"选列"这一步的<b>中间结果</b>：这一列画哪一格方块、底下有没有底、光照多少。
	 *
	 * <p>它<b>刻意不含颜色</b>：颜色要问客户端关卡取贴图配色与生物群系染色，只能在渲染线程上算。
	 * 扫描（本记录）与着色（{@link #colorCaveColumns}）因此可以分家——扫描能并行，着色留在渲染线程。
	 *
	 * <p>字段：{@code worldX}/{@code worldZ} 供着色时问群系；{@code floorY}/{@code floorState} 是地板
	 * 那一格，{@code skyLight}/{@code blockLight} 取它<b>上方</b>的空气；{@code bedY}/{@code bedState}/
	 * {@code baseFound} 是地板为水/冰时往下找到的底（{@code baseFound} 为假表示到底都没找到，本色即
	 * 层自己）。
	 *
	 * @param noData 这一列在层高度附近是实心岩层（或下方几十格都是空气）：洞穴图上不显示它
	 */
	record CaveColumn(int worldX, int worldZ, int floorY, BlockState floorState,
			int bedY, BlockState bedState, int skyLight, int blockLight,
			boolean noData, boolean layered, boolean flatSheet, boolean baseFound) {
	}

	/**
	 * 洞穴切面"选列"这一半：读区块 NBT，逐列定出要画哪一格方块、底下有没有底、光照多少。
	 *
	 * <p><b>纯计算，不碰客户端关卡</b>：只用 {@link ColumnShape} 那三个"只看方块状态"的判定，因此
	 * <b>可以放到后台线程上跑</b>（{@code SamplePool}）——这正是把它从着色里拆出来的原因。
	 *
	 * <p>规则与实时采样（{@code ClientChunkColorProvider#scanCaveColumn}）<b>逐条对应</b>：先在层高度
	 * {@code ± }{@link LayerSelector#CAVE_TOLERANCE} 的窗口里自上而下找第一格空气，再从那里往下最多
	 * {@link LayerSelector#CAVE_SCAN_DEPTH} 格找地板；找不到就当作这一列不显示。两边必须一致，任何偏差
	 * 都会在"已加载范围"的边界上留下一条缝。
	 *
	 * <p>没有实时采样那份"这一列在哪些高度上仍然成立"的区间记录：这边一次性读一个区块，不需要跨层复用。
	 *
	 * @param shape 方块状态判定（水、冰、要看穿的细碎方块）；必须是不碰关卡的那一半
	 * @return 逐列中间结果，长度 {@link ChunkRecord#COLUMNS}，索引与 {@code ChunkRecord} 一致
	 */
	static CaveColumn[] scanCaveColumns(CompoundTag chunk, int chunkX, int chunkZ, int layerY,
			ColumnShape shape) {
		LayerWindow window = new LayerWindow(chunk, layerY);
		CaveColumn[] columns = new CaveColumn[ChunkRecord.COLUMNS];
		int top = layerY + LayerSelector.CAVE_TOLERANCE;
		int bottom = layerY - LayerSelector.CAVE_TOLERANCE;

		for (int index = 0; index < ChunkRecord.COLUMNS; index++) {
			int x = index % 16;
			int z = index / 16;
			int airY = Integer.MIN_VALUE;
			int floor = Integer.MIN_VALUE;
			// 自上而下找第一格空气。按"节"推进：整节都是同一格方块时，这一节在窗口里的那几格
			// 结果一样，只看第一格就够——是空气就是命中，非空气就是 16 个 continue，
			// 不必逐格问 {@code state()}（洞穴里绝大多数节正是这种单一调色板的节）。
			//
			// 等价性：找空气本来就是"自上而下第一格命中就停"，这里既没有换掉窗口上下沿，
			// 也没有换掉节序与节内自上而下的格序，因此第一个命中的 y 与逐格扫完全相同；
			// 不命中的节整节跳过，与逐格 continue 到底也是同一结果。
			for (int y = top; y >= bottom; ) {
				BlockState uniform = window.uniformAt(y);
				int sectionTop = Math.min(y, ((y >> 4) << 4) + 15);
				int sectionBottom = Math.max(bottom, (y >> 4) << 4);
				if (uniform == null) {
					// 这一节的方块不单一：逐格查，与改造前完全一样
					for (int cell = sectionTop; cell >= sectionBottom; cell--) {
						if (window.state(x, cell, z).isAir()) {
							airY = cell;
							break;
						}
					}
				} else if (uniform.isAir()) {
					// 整节空气：窗口里的第一格就是空气
					airY = sectionTop;
				}
				if (airY != Integer.MIN_VALUE) {
					break;
				}
				y = sectionBottom - 1;
			}
			if (airY != Integer.MIN_VALUE) {
				// 从空气那一格往下最多 CAVE_SCAN_DEPTH 格找地板。同样按节跳过：
				//   整节空气           → 这 16 格全是 continue，与逐格扫一样，整节跳过；
				//   整节同一种非空气且不在地板忽略名单里的方块 → 第一格（也就是本节最高那格）就是地板；
				//   整节同一种但被忽略（例如整节火把）→ 这 16 格全是 continue，整节跳过。
				// 其余情况逐格查。下沿仍然是 {@code airY - CAVE_SCAN_DEPTH}，没有动边界。
				int scanBottom = airY - LayerSelector.CAVE_SCAN_DEPTH;
				for (int y = airY - 1; y >= scanBottom; ) {
					BlockState uniform = window.uniformAt(y);
					int sectionBottom = Math.max(scanBottom, (y >> 4) << 4);
					if (uniform == null) {
						for (int cell = y; cell >= sectionBottom; cell--) {
							BlockState belowState = window.state(x, cell, z);
							// 火把、花草这些不算地板，接着往下找（同地表那条路）
							if (!belowState.isAir() && !shape.shouldIgnore(belowState)) {
								floor = cell;
								break;
							}
						}
					} else if (!uniform.isAir() && !shape.shouldIgnore(uniform)) {
						floor = y;
					}
					if (floor != Integer.MIN_VALUE) {
						break;
					}
					y = sectionBottom - 1;
				}
			}
			if (airY == Integer.MIN_VALUE || floor == Integer.MIN_VALUE) {
				// 这一列在层高度附近是实心岩层（或下方几十格都是空气）：洞穴图上不显示它。
				// 其余的坐标与方块都是占位值，着色时会走 noData 那条分支，用不上它们
				columns[index] = new CaveColumn(0, 0, 0, AIR, 0, AIR, 0, 0, true, false, false, false);
				continue;
			}

			int worldX = chunkX * 16 + x;
			int worldZ = chunkZ * 16 + z;
			BlockState state = window.state(x, floor, z);
			boolean layered = shape.isLayered(state);
			boolean flatSheet = layered && shape.isWater(state);
			// 地板是水/冰时往下找真正的底（跳过空气与层方块，与拆分前那段找底的扫描一致）。
			// 只记高度与状态，本色与合成留到渲染线程——那一步要问关卡
			int bedY = 0;
			BlockState bedState = AIR;
			boolean baseFound = false;
			if (layered) {
				// 往下找底这趟要穿过空气/水/冰，可能连着走几十格，于是按 16 格一节推进：
				// 整节空气就一次跨过去。这里的判定与实时那条路（{@code ClientChunkColorProvider}
				// 里用 {@code LevelChunkSection#hasOnlyAir()}）是同一条——{@link LayerWindow#uniformAt}
				// 给出"整节同一种方块"的那个状态，整节空气时它就是空气；存档里单一调色板的节本来
				// 就不写 data（见 {@link SectionBlocks#parse}），所以洞穴里绝大多数节都命中。
				// 下沿仍是 {@code window.bottom()}，判定条件一个字没改。
				for (int y = floor - 1; y >= window.bottom(); ) {
					int sectionBottom = Math.max(window.bottom(), (y >> 4) << 4);
					BlockState uniform = window.uniformAt(y);
					if (uniform != null && uniform.isAir()) {
						// 整节空气：这一节在窗口里的每一格都会走下面的"接着往下找"，逐格扫与整节跳过相同
						y = sectionBottom - 1;
						continue;
					}
					for (int cell = y; cell >= sectionBottom; cell--) {
						BlockState under = window.state(x, cell, z);
						if (under.isAir() || shape.isLayered(under)) {
							continue;
						}
						bedY = cell;
						bedState = under;
						baseFound = true;
						break;
					}
					if (baseFound) {
						break;
					}
					y = sectionBottom - 1;
				}
			}
			// 光照取地板那一格上方的空气：固体方块自身的光照恒为 0，取它自己那一格会整片发暗
			columns[index] = new CaveColumn(worldX, worldZ, floor, state, bedY, bedState,
					window.skyLight(x, floor + 1, z), window.blockLight(x, floor + 1, z),
					false, layered, flatSheet, baseFound);
		}
		return columns;
	}

	/**
	 * 洞穴切面"着色"这一半：把 {@link #scanCaveColumns} 的结果收成 {@link ChunkRecord}。
	 *
	 * <p><b>只能在渲染线程上调用</b>：{@link ColumnColor#rgb} / {@link ColumnColor#layered} 要问客户端
	 * 关卡（贴图配色与生物群系染色），离开了关卡算不出颜色。
	 *
	 * <p>与拆分前逐列等价：水/冰找到底时按同一档不透明度盖上去、深度取 {@code floorY - bedY}；
	 * 没找到底就是纯层色；非层方块直接取本色；水（{@code flatSheet}）的高度按底算，冰仍按地板算。
	 */
	static ChunkRecord colorCaveColumns(CaveColumn[] columns, ColumnColor colors) {
		ChunkRecord record = new ChunkRecord();
		for (int index = 0; index < ChunkRecord.COLUMNS; index++) {
			CaveColumn column = columns[index];
			if (column.noData()) {
				record.setNoData(index);
				record.setFlatSheet(index, false);
				record.setEmissive(index, false);
				continue;
			}
			int rgb;
			int displayY = column.floorY();
			if (column.layered() && column.baseFound()) {
				// 洞穴里的水面/冰面同样要盖一层、并把底下露出来，见 toRecord 里地表那一套。
				// 水面（flatSheet）的底色走 coveredRgb：被水盖住时它不再当光源，见 ColumnColor#coveredRgb
				int bedRgb = column.flatSheet()
						? colors.coveredRgb(column.bedState(), column.worldX(), column.bedY(), column.worldZ())
						: colors.rgb(column.bedState(), column.worldX(), column.bedY(), column.worldZ());
				rgb = colors.layered(bedRgb, column.floorState(), column.worldX(), column.floorY(),
						column.worldZ(), column.floorY() - column.bedY());
				// 水的明暗按底算（深浅要看水深）；冰反过来——它是跟着地形走的固体薄壳
				if (column.flatSheet()) {
					displayY = column.bedY();
				}
			} else {
				// 非层方块，或一直扫到窗口底部都没找到底（深渊）：本色就是这一格本身
				rgb = colors.rgb(column.floorState(), column.worldX(), column.floorY(), column.worldZ());
			}
			record.setColumn(index, rgb, displayY, column.skyLight(), column.blockLight());
			record.setFlatSheet(index, column.flatSheet());
			record.setEmissive(index, !column.layered() && column.floorState().getLightEmission() > 0);
		}
		return record;
	}

	/**
	 * 区块 NBT 里那几节的<b>随机访问视图</b>，只覆盖洞穴切面要看的那一段高度。
	 *
	 * <p>地表那条路自上而下一次扫完、不回头，因此不需要随机访问；洞穴这条路要按列在任意高度找空气与
	 * 地板，范围通常只跨两三节，所以这里只把与窗口相交的节解出来。
	 */
	private static final class LayerWindow {

		private final int minY;
		private final int maxY;

		/**
		 * 与窗口相交的那些节，按节下标（{@code y >> 4}）连续摆放。
		 *
		 * <p>用数组而不是按键查表：每列扫描要问几十次方块、一屏几千区块就是几千万次查找，键是装箱的
		 * {@code Integer}，临时对象光分配就够喝一壶。
		 */
		private final SectionBlocks[] sections;
		private final int firstSection;

		LayerWindow(CompoundTag chunk, int layerY) {
			minY = layerY - LayerSelector.CAVE_TOLERANCE - LayerSelector.CAVE_SCAN_DEPTH;
			maxY = layerY + LayerSelector.CAVE_TOLERANCE;
			// 右移 4 位就是它所在的节下标（负数也是向下取整，与 floorDiv 一致）
			firstSection = minY >> 4;
			int lastSection = maxY >> 4;
			SectionBlocks[] found = new SectionBlocks[lastSection - firstSection + 1];
			ListTag list = chunk.getListOrEmpty("sections");
			for (int i = 0; i < list.size(); i++) {
				CompoundTag section = list.getCompoundOrEmpty(i);
				int index = section.getByte("Y").orElse((byte) 0);
				if (index < firstSection || index > lastSection) {
					continue;
				}
				found[index - firstSection] = SectionBlocks.parse(section);
			}
			sections = found;
		}

		/** 窗口下沿：往下找地板/找底的扫描到此为止。 */
		int bottom() {
			return minY;
		}

		/** 窗口外的方块一律当作空气（那儿没有解出来的节，也就无从取值）。 */
		BlockState state(int x, int y, int z) {
			if (y < minY || y > maxY) {
				return AIR;
			}
			SectionBlocks section = sectionAt(y);
			return section == null ? AIR : section.state(x, y, z);
		}

		int skyLight(int x, int y, int z) {
			SectionBlocks section = sectionAt(y);
			// 窗口里没有这一节（存档里省掉了整节、或采样点刚好落在窗口上一格）：
			// 这是<b>洞穴</b>那条路，取 0（地下本来就照不到天）。绝不能按 15 兜底——
			// 那会把整片洞穴画成地表那种亮度（见下方 SectionBlocks#skyLight 的说明）。
			return section == null ? 0 : section.skyLight(x, y, z);
		}

		int blockLight(int x, int y, int z) {
			SectionBlocks section = sectionAt(y);
			return section == null ? 0 : section.blockLight(x, y, z);
		}

		/**
		 * y 所在节"整节同一种方块"的那个状态；必须逐格取时返回 {@code null}。
		 *
		 * <p>窗口里根本没有这一节（存档里缺这一节）时按空气返回：{@link #state} 对这种情况也返回
		 * 空气，所以把它当成"整节空气"与逐格取完全等价——只是少问 16 次。
		 */
		BlockState uniformAt(int y) {
			SectionBlocks section = sectionAt(y);
			return section == null ? AIR : section.uniform();
		}

		private SectionBlocks sectionAt(int y) {
			int index = (y >> 4) - firstSection;
			return index < 0 || index >= sections.length ? null : sections[index];
		}
	}

	/**
	 * 一节方块：解好的调色板、打包索引、两张光照数组，以及"整节是不是同一种方块"的预判。
	 *
	 * @param uniform 整节 4096 格都是同一种方块时是那个状态（<b>可能是空气</b>）；必须逐格取时是
	 *                {@code null}。判定见 {@link #parse}
	 */
	private record SectionBlocks(BlockState[] palette, SimpleBitStorage data,
			byte[] skyLight, byte[] blockLight, BlockState uniform) {

		static SectionBlocks parse(CompoundTag section) {
			CompoundTag states = section.getCompoundOrEmpty("block_states");
			ListTag paletteTag = states.getListOrEmpty("palette");
			BlockState[] palette = new BlockState[paletteTag.size()];
			for (int i = 0; i < palette.length; i++) {
				palette[i] = NbtUtils.readBlockState(BuiltInRegistries.BLOCK, paletteTag.getCompoundOrEmpty(i));
			}
			long[] packed = states.getLongArray("data").orElse(null);
			SimpleBitStorage data = packed == null ? null
					: new SimpleBitStorage(Math.max(4, Mth.ceillog2(palette.length)), 4096, packed);
			// 整节是不是同一格方块：调色板只有一项、且没有打包索引时就是。vanilla 对单一调色板的节
			// 本来就不写 data，所以这正是"整节空气"最常见的形态（洞穴扫描据此整节跳过）。
			//
			// data 在时一律不算：那种节里索引可能越界，而 state() 对越界索引返回空气——此时"整节都是
			// palette[0]"并不成立，硬跳过会把空气当成实心的一整节。
			BlockState uniform = palette.length == 1 && data == null
					? (palette[0] == null ? AIR : palette[0])
					: null;
			return new SectionBlocks(palette, data,
					section.getByteArray("SkyLight").orElse(null),
					section.getByteArray("BlockLight").orElse(null), uniform);
		}

		BlockState state(int x, int y, int z) {
			int paletteIndex = data == null ? 0 : data.get((y & 15) << 8 | (z << 4) | x);
			if (paletteIndex < 0 || paletteIndex >= palette.length) {
				return AIR;
			}
			BlockState state = palette[paletteIndex];
			return state == null ? AIR : state;
		}

		/**
		 * 这一节的天空光。
		 *
		 * <p><b>没有数组时取 0，不是 15。</b>存档里 {@code SkyLight} 按节可选，原版只在"这一节的天空光
		 * 不全为 0"时才写它——<b>数组缺失 ⇔ 这一节天空光全是 0</b>，地下每一节都是这个形态。
		 *
		 * <p>以前这里按 15 兜底（对"还没点亮过的区块"是对的），但洞穴切面复用了同一个读取器，于是
		 * "身边（实时采样，真 0，是黑的）"与"远处（存档，兜底成 15）"画出了两种亮度，整张洞穴图除了
		 * 身边一圈全是亮的。
		 */
		int skyLight(int x, int y, int z) {
			return skyLight == null ? 0 : nibble(skyLight, (y & 15) << 8 | (z << 4) | x);
		}

		int blockLight(int x, int y, int z) {
			return nibble(blockLight, (y & 15) << 8 | (z << 4) | x);
		}
	}

	/**
	 * 区块 NBT → 逐列记录。
	 *
	 * <p>每列自上而下取两样东西：<b>顶面</b>（用来取光照与覆盖层）与<b>底</b>（真正要画的那一层）。
	 * 水与冰不算底——它们只是盖在地形上的一层，下面还有水、还有海底或地面；只看"第一个非空气方块"
	 * 会得到一整片纯水色或纯冰，与实时采样对不上，地图上会出现一条分界线（正落在加载范围的边缘）。
	 *
	 * <p>调色板逐节解析一次（一节 4096 格共用一张调色板），打包索引用 {@link SimpleBitStorage} 解。
	 *
	 * @return 整块一个非空气方块都没有时返回 null（那不是地形，不该画成一块黑）
	 */
	private static ChunkRecord toRecord(CompoundTag chunk, int chunkX, int chunkZ, ColumnColor colors,
			boolean lightMissing, boolean ceiling) {
		ChunkRecord record = new ChunkRecord();
		ListTag sections = chunk.getListOrEmpty("sections");
		// 每列顶面（最上面那个非空气方块）的高度
		int[] topY = new int[ChunkRecord.COLUMNS];
		java.util.Arrays.fill(topY, Integer.MIN_VALUE);
		int[] topSky = new int[ChunkRecord.COLUMNS];
		int[] topBlock = new int[ChunkRecord.COLUMNS];
		// 天花板维度：每列"已经穿过天花板、见到空气"了没有。天花板（基岩顶）不算地形——
		// 不跳过它，整张地狱地图就是一片平坦的基岩（与实时采样那条路的 terrainTopBelowCeiling 对应）
		boolean[] ceilingPassed = ceiling ? new boolean[ChunkRecord.COLUMNS] : null;
		// 每列的覆盖层（水或冰），没有就是 null；与之配套的高度（群系按层自己那一格问）
		BlockState[] overlay = new BlockState[ChunkRecord.COLUMNS];
		int[] overlayY = new int[ChunkRecord.COLUMNS];
		// 每列顶面是不是自发光方块（岩浆这类），见 ChunkRecord#FLAG_EMISSIVE
		boolean[] emissive = new boolean[ChunkRecord.COLUMNS];
		// 每列真正要画的那一层：高度与本色
		int[] bedY = new int[ChunkRecord.COLUMNS];
		java.util.Arrays.fill(bedY, Integer.MIN_VALUE);
		int[] bedRgb = new int[ChunkRecord.COLUMNS];

		int remaining = ChunkRecord.COLUMNS;
		// 上面一节的光照数组：光照要取地表那一格上方的空气，而地表正好落在某一节的最顶上时，
		// 那格空气在上一节里，得从这里取
		byte[] upperSkyLight = null;
		byte[] upperBlockLight = null;
		// 自上而下逐节扫，每列一直往下找到"底"为止
		sections:
		for (int i = sections.size() - 1; i >= 0; i--) {
			CompoundTag section = sections.getCompoundOrEmpty(i);
			int sectionY = section.getByte("Y").orElse((byte) 0) * 16;
			// 先留下"上一节"的快照，再把本节记成上一节——顺序反了本节就会用它自己
			byte[] skyAbove = upperSkyLight;
			byte[] blockAbove = upperBlockLight;
			// 光照还没写到的块：当作"没有光照数据"，让取光处按满天空光兜底。直接读会拿到一整片 0，
			// 整块画成黑的——那正是"偶尔出现的暗色区块"。
			byte[] skyLight = lightMissing ? null : section.getByteArray("SkyLight").orElse(null);
			byte[] blockLight = lightMissing ? null : section.getByteArray("BlockLight").orElse(null);
			upperSkyLight = skyLight;
			upperBlockLight = blockLight;
			CompoundTag states = section.getCompoundOrEmpty("block_states");
			if (states.isEmpty()) {
				continue;
			}
			ListTag palette = states.getListOrEmpty("palette");
			if (palette.isEmpty()) {
				continue;
			}
			long[] packed = states.getLongArray("data").orElse(null);
			SimpleBitStorage storage = packed == null ? null
					: new SimpleBitStorage(Math.max(4, Mth.ceillog2(palette.size())), 4096, packed);
			BlockState[] resolved = new BlockState[palette.size()];

			for (int y = 15; y >= 0; y--) {
				int worldY = sectionY + y;
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						int index = z * 16 + x;
						if (bedY[index] != Integer.MIN_VALUE) {
							// 这一列已经找到底了
							continue;
						}
						int paletteIndex = storage == null ? 0 : storage.get((y << 8) | (z << 4) | x);
						if (paletteIndex >= palette.size()) {
							continue;
						}
						BlockState state = resolved[paletteIndex];
						if (state == null) {
							state = NbtUtils.readBlockState(BuiltInRegistries.BLOCK, palette.getCompoundOrEmpty(paletteIndex));
							resolved[paletteIndex] = state;
						}
						if (state.isAir()) {
							if (ceilingPassed != null) {
								// 见到空气 = 已经穿过天花板，下面第一块实心才是地形顶
								ceilingPassed[index] = true;
							}
							continue;
						}
						if (colors.shouldIgnore(state)) {
							// 火把、花草这类"不是地面"的东西：跳过，接着往下找地面。名单必须与实时采样
							// 一致，否则加载范围边界上会出现一圈色差
							continue;
						}
						if (ceilingPassed != null && !ceilingPassed[index]) {
							// 还在天花板那一段实心里：跳过（不算顶面、也不当地形）
							continue;
						}
						if (topY[index] == Integer.MIN_VALUE) {
							topY[index] = worldY;
							// 自发光方块在地图上直接给本色，不叠明暗（见 ChunkRecord#FLAG_EMISSIVE）。
							// 覆盖层（水、冰）不发光，标记在下面的合成里按覆盖层判定
							emissive[index] = state.getLightEmission() > 0;
							// 光照取地表那一格上方的空气：固体方块自身光照恒为 0，取自己那格会得到 0、
							// 整块被压到 30% 亮度（实时采样取的是上方一格，两边差三倍多）。
							// 下标 {@code (y + 1) << 8 | z << 4 | x} 里的 +1 不能漏；
							// y == 15 时上方那格在上一节的第一层，下标变 {@code z << 4 | x}、数组也换上一节。
							int above = ((y + 1) << 8) | (z << 4) | x;
							byte[] sky = skyLight;
							byte[] block = blockLight;
							if (y == 15) {
								above = (z << 4) | x;
								sky = skyAbove;
								block = blockAbove;
							}
							// 没有光照数据时按"满天空光"处理：存档里光照是可选内容，还没点亮过的区块
							// 根本没有这两张数组，按 0 处理会让整片地形变黑。
							topSky[index] = sky == null ? 15 : nibble(sky, above);
							topBlock[index] = nibble(block, above);
						}
						if (colors.isLayered(state)) {
							// 水、冰接着往下看：它们是盖在上面的一层，不是底
							if (overlay[index] == null) {
								overlay[index] = state;
								overlayY[index] = worldY;
							}
							continue;
						}
						bedY[index] = worldY;
						// 被水盖住的底色不算光源（见 ColumnColor#coveredRgb）：覆盖层自上而下扫到时
						// 已经记下，这里能直接判断这一格是否压在水下。
						bedRgb[index] = overlay[index] != null && colors.isWater(overlay[index])
								? colors.coveredRgb(state, chunkX * 16 + x, worldY, chunkZ * 16 + z)
								: colors.rgb(state, chunkX * 16 + x, worldY, chunkZ * 16 + z);
						if (--remaining == 0) {
							// 每一列都找到底了，剩下的节不必再看
							break sections;
						}
					}
				}
			}
		}

		boolean any = false;
		// 天空光兜底：与实时采样同一条规则。存档里 SkyLight 是可选内容，区块光照还没跑到时那张数组
		// 可能整片是 0，直接读出来整块地形会被压到最低亮度（远处发暗、走近才自己更正）。地表取样取的是
		// 高度图那一格、上面就是露天，天空光本该是 15，整块 0 只可能是没写。洞穴那条路不能这么做。
		boolean skyAllZero = true;
		for (int index = 0; index < ChunkRecord.COLUMNS; index++) {
			if (topY[index] != Integer.MIN_VALUE && topSky[index] > 0) {
				skyAllZero = false;
			}
		}
		for (int index = 0; index < ChunkRecord.COLUMNS; index++) {
			if (topY[index] == Integer.MIN_VALUE) {
				// 整列空气（虚空、或区块里只有空气）：标成"没有数据"，输出全透明。
				// 不能用本色 0——着色出来是不透明纯黑，在世界地图上就是一块比底色更黑的方块
				record.setNoData(index);
				continue;
			}
			any = true;
			int rgb;
			int columnHeight;
			if (overlay[index] == null) {
				rgb = bedRgb[index];
				columnHeight = bedY[index];
			} else if (bedY[index] != Integer.MIN_VALUE) {
				rgb = colors.layered(bedRgb[index], overlay[index],
						chunkX * 16 + index % 16, overlayY[index], chunkZ * 16 + index / 16,
						topY[index] - bedY[index]);
				// 水的明暗按底算（深浅要看水深）；冰反过来——它是跟着地形走的固体薄壳
				columnHeight = colors.isWater(overlay[index]) ? bedY[index] : topY[index];
			} else {
				// 一直扫到底都没找到底面（深渊）：就是纯层色
				rgb = colors.rgb(overlay[index],
						chunkX * 16 + index % 16, topY[index], chunkZ * 16 + index / 16);
				columnHeight = topY[index];
			}
			record.setColumn(index, rgb, columnHeight, skyAllZero ? 15 : topSky[index], topBlock[index]);
			// 标记必须与实时采样那条路一致，否则近处的水面是平的、远处却是起伏的
			record.setFlatSheet(index, overlay[index] != null && colors.isWater(overlay[index]));
			// 同样要与实时采样一致：盖了水或冰的那一列画的不是顶面，也就谈不上自发光
			record.setEmissive(index, overlay[index] == null && emissive[index]);
		}
		return any ? record : null;
	}

	/** 半字节数组取值。 */
	private static int nibble(byte[] data, int index) {
		if (data == null || index / 2 >= data.length) {
			return 0;
		}
		int value = data[index / 2] & 0xFF;
		return (index & 1) == 0 ? value & 0x0F : value >> 4;
	}
}
