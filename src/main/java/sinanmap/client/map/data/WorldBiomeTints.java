package sinanmap.client.map.data;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import org.jetbrains.annotations.Nullable;

/**
 * 地图取色用的生物群系染色入口。
 *
 * <p><b>它解决的是"远处没有群系"</b>：客户端只为已加载（也就是在视距内）的区块保存群系，
 * 落在未加载区块里的查询会由 {@code ClientLevel#getUncachedNoiseBiome} 一律返回<b>平原</b>
 * （26.1.2 源码确认）。地图却会画到视距之外（那些区块的数据来自世界存档），于是远处整片按平原上色，
 * 等玩家走近、区块加载、原版染色缓存被清空之后才更正——这就是"远处的群系不对、过去后才变"。
 *
 * <p><b>做法</b>：这一层包住客户端关卡，只改"群系从哪儿取"：
 * <ul>
 *   <li><b>邻域全部落在已加载区块里</b>：直接委托原版 {@code ClientLevel#getBlockTint}，含原版的
 *       {@code BlockTintCache} 与邻域混合，结果与游戏内逐像素相同；</li>
 *   <li><b>只要邻域碰到未加载区块</b>（视距之外、或加载范围的边缘）：按原版公式自己混合，每个采样
 *       点的群系<b>优先读世界存档的区块 NBT</b>（{@link WorldSaveChunks#biomeAt}，与
 *       {@link WorldSaveChunks#biomeIdAt} 同一份缓存），读不到再问关卡；</li>
 *   <li>存档里也没有（还没生成、或连的是别人的服务器）时，关卡对未加载区块给的是平原——也就是
 *       改动之前的行为，只在"确实没有任何真实群系可用"时出现。</li>
 * </ul>
 *
 * <p><b>混合必须与原版一致</b>：原版 {@code ClientLevel#calculateBlockTint} 会把
 * {@code biomeBlendRadius} 范围内（默认 2，即 5×5）的群系色取整数平均。这里逐字复刻同一套
 * （含 {@code radius == 0} 的单格分支与 {@code ARGB} 的三通道平均），否则近处平滑、远处硬边，
 * 两边一接就是一条看得见的色差。当这一格的邻域<b>全部</b>落在已加载区块里时，直接委托给原版
 * {@code getBlockTint}（含它自己的 {@code BlockTintCache}），近处因此与以前逐像素相同；只有碰到了
 * 未加载区块（加载范围的边缘）才走这一层的混合——边缘不会再混进"平原"的绿色。
 *
 * <p><b>线程与缓存</b>：只在渲染线程使用。每个 {@link ColorResolver} 一份 {@code BlockTintCache}，
 * 换世界时按关卡实例整体作废；区块加载/卸载时由 {@code ClientChunkEvents} 调
 * {@link #invalidate}（与 {@code ClientLevel#onChunkLoaded} 对原版缓存的作废一致）。
 */
final class WorldBiomeTints implements BlockAndTintGetter {

	/** 关卡为空的兜底：调用方只在有世界时取色，这里只是不让委托方法抛异常。 */
	private static final int NO_TINT = 0xFFFFFF;

	private final Minecraft minecraft;

	/** 每个 {@link ColorResolver} 一份缓存，语义与 {@code ClientLevel} 自己那三份完全相同。 */
	private final Map<ColorResolver, BlockTintCache> caches = new HashMap<>(4);

	/** 算邻域时反复用的临时坐标；只在渲染线程用，不跨线程。 */
	private final BlockPos.MutableBlockPos samplePos = new BlockPos.MutableBlockPos();

	@Nullable
	private ClientLevel level;

	WorldBiomeTints(Minecraft minecraft) {
		this.minecraft = minecraft;
	}

	/**
	 * 见类注释：已加载区块走原版，碰到未加载区块就用"关卡 + 世界存档"拼出来的群系按原版公式混合。
	 */
	@Override
	public int getBlockTint(BlockPos pos, ColorResolver resolver) {
		ClientLevel level = this.minecraft.level;
		if (level == null) {
			return NO_TINT;
		}
		bind(level);
		int radius = this.minecraft.options.biomeBlendRadius().get();
		if (allSamplesLoaded(level, pos, radius)) {
			// 邻域全在已加载区块里：结果与原版逐像素相同，直接用原版缓存
			return level.getBlockTint(pos, resolver);
		}
		return this.caches.computeIfAbsent(resolver,
				r -> new BlockTintCache(sample -> computeTint(level, r, sample)))
				.getColor(pos);
	}

	/** 区块加载/卸载后丢掉这块及其邻块的缓存（与 {@code BlockTintCache#invalidateForChunk} 的范围一致）。 */
	void invalidate(ClientLevel level, int chunkX, int chunkZ) {
		if (this.level != level) {
			return;
		}
		for (BlockTintCache cache : this.caches.values()) {
			cache.invalidateForChunk(chunkX, chunkZ);
		}
	}

	/** 换世界（关卡实例变了）就整体作废：缓存键只有方块坐标，不带世界身份。 */
	private void bind(ClientLevel level) {
		if (this.level == level) {
			return;
		}
		this.level = level;
		this.caches.clear();
	}

	/** 原版 {@code ClientLevel#calculateBlockTint} 的复刻，只是采样点的群系改为 {@link #biomeAt}。 */
	private int computeTint(ClientLevel level, ColorResolver resolver, BlockPos pos) {
		int radius = this.minecraft.options.biomeBlendRadius().get();
		if (radius == 0) {
			return resolver.getColor(biomeAt(level, pos).value(), pos.getX(), pos.getZ());
		}
		// 与原版同序：先 x 后 z，同一 y；平均也是逐通道整数平均（不是按权重）
		int count = (radius * 2 + 1) * (radius * 2 + 1);
		int red = 0;
		int green = 0;
		int blue = 0;
		for (int dz = -radius; dz <= radius; dz++) {
			for (int dx = -radius; dx <= radius; dx++) {
				this.samplePos.set(pos.getX() + dx, pos.getY(), pos.getZ() + dz);
				int color = resolver.getColor(biomeAt(level, this.samplePos).value(),
						this.samplePos.getX(), this.samplePos.getZ());
				red += ARGB.red(color);
				green += ARGB.green(color);
				blue += ARGB.blue(color);
			}
		}
		return ARGB.color(red / count, green / count, blue / count);
	}

	/**
	 * 混合用的群系：<b>优先世界存档</b>，读不到再问客户端关卡。
	 *
	 * <p>混合只在"有样本落在未加载区块"时才会走到这里（见 {@link #getBlockTint}），所以不会影响
	 * 已加载区域内部的正常取色。优先存档是为了让加载范围<b>两侧</b>用同一份群系：已加载那一侧的
	 * {@code BiomeManager} 在边缘也可能把相邻的 quart 选进未加载区块、拿回平原色——那样边缘仍会
	 * 留一圈淡淡的绿。存档读不到（联机、或区块还没写盘）时退回关卡，也就是原来的行为。
	 */
	private Holder<Biome> biomeAt(ClientLevel level, BlockPos pos) {
		if (this.minecraft.level != level) {
			// 关卡正在切换（换维度/退世界）：存档目录是按"当前关卡"的维度拼的，先别读
			return level.getBiome(pos);
		}
		Holder<Biome> saved = WorldSaveChunks.biomeAt(this.minecraft, level, pos.getX(), pos.getY(), pos.getZ());
		return saved != null ? saved : level.getBiome(pos);
	}

	/**
	 * 邻域（与混合半径一样宽）是不是"安全地"全部落在已加载区块里。
	 *
	 * <p>除了混合半径，还要留 {@value #QUART_MARGIN} 格余量：原版 {@code BiomeManager} 取群系时会在
	 * 相邻的 quart（4 格）里挑一个，所以离块边界还有几格的方块也可能问到隔壁区块。余量不够的话，
	 * 加载范围边缘仍会有一圈颜色按平原算。
	 */
	private static boolean allSamplesLoaded(ClientLevel level, BlockPos pos, int radius) {
		int margin = radius + QUART_MARGIN;
		int minChunkX = (pos.getX() - margin) >> 4;
		int maxChunkX = (pos.getX() + margin) >> 4;
		int minChunkZ = (pos.getZ() - margin) >> 4;
		int maxChunkZ = (pos.getZ() + margin) >> 4;
		for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
			for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
				if (level.getChunk(chunkX, chunkZ, ChunkStatus.BIOMES, false) == null) {
					return false;
				}
			}
		}
		return true;
	}

	/** {@code BiomeManager} 会在相邻 quart 里挑群系，见 {@link #allSamplesLoaded}。 */
	private static final int QUART_MARGIN = 4;

	/** 只在取色时用到；取色一定发生在有世界的时候。 */
	@Nullable
	private ClientLevel bound() {
		ClientLevel level = this.level;
		if (level == null) {
			level = this.minecraft.level;
			this.level = level;
		}
		return level;
	}

	// 下面是 BlockAndTintGetter 的委托：群系/光照/方块查询全部按客户端关卡原来的行为走，
	// 这一层只替换 getBlockTint。关卡为空时给与原版 BlockAndTintGetter.EMPTY 相同的兜底值。

	@Override
	public CardinalLighting cardinalLighting() {
		ClientLevel level = bound();
		return level == null ? CardinalLighting.DEFAULT : level.cardinalLighting();
	}

	@Override
	public LevelLightEngine getLightEngine() {
		ClientLevel level = bound();
		return level == null ? LevelLightEngine.EMPTY : level.getLightEngine();
	}

	@Override
	@Nullable
	public BlockEntity getBlockEntity(BlockPos pos) {
		ClientLevel level = bound();
		return level == null ? null : level.getBlockEntity(pos);
	}

	@Override
	public BlockState getBlockState(BlockPos pos) {
		ClientLevel level = bound();
		return level == null ? Blocks.AIR.defaultBlockState() : level.getBlockState(pos);
	}

	@Override
	public FluidState getFluidState(BlockPos pos) {
		ClientLevel level = bound();
		return level == null ? Fluids.EMPTY.defaultFluidState() : level.getFluidState(pos);
	}

	@Override
	public int getHeight() {
		ClientLevel level = bound();
		return level == null ? 0 : level.getHeight();
	}

	@Override
	public int getMinY() {
		ClientLevel level = bound();
		return level == null ? 0 : level.getMinY();
	}
}
