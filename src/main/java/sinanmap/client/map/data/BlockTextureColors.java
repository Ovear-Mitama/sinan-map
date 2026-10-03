package sinanmap.client.map.data;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sinanmap.common.SinanConstants;
import sinanmap.common.map.TextureAverage;

/**
 * 方块"看上去是什么颜色"。
 *
 * <p>取方块<b>朝上那一面</b>的贴图求平均，得到该方块在地图上的本色；贴图平均色 + 生物群系染色
 * 与原生渲染是同一套算法。{@code BlockState.getMapColor()} 是原版地图的卡通色板，又亮又饱和，
 * 画出来和游戏里对不上。
 *
 * <p>读贴图走资源管理器，按贴图名缓存：每种方块只读一次 PNG。
 *
 * <p><b>这几张缓存必须线程安全，所以用 {@link ConcurrentHashMap}。</b>后台采样器会经
 * {@link #colorOf}/{@link #lavaColor} 并发写入，普通 {@code HashMap} 并发 {@code put} 会写坏桶、
 * 极端时查表<b>死循环</b>；表里存的都是"由方块状态 / 贴图内容唯一决定"的常量，改成并发表既安全、
 * 又不必加锁（理由同 {@code WorldSaveChunks.REGION_PRESENCE} 的锁）。
 */
final class BlockTextureColors {

	private static final Logger LOGGER = LoggerFactory.getLogger(SinanConstants.MOD_ID);

	/** 取不到贴图时的兜底色。 */
	private static final int FALLBACK = TextureAverage.FALLBACK;

	/**
	 * 原版<b>流体与火</b>的贴图名：它们没有"朝上那一面"的方块模型可查（流体不走方块模型；火是几片
	 * 竖直的十字面，{@code collectParts} 取不到 {@code Direction.UP} 的那一面），只能点名。
	 * 其它模组方块取不到名字时由调用方退回模型/兜底色，不会崩。
	 */
	private static final Identifier WATER_SPRITE = Identifier.withDefaultNamespace("block/water_still");
	private static final Identifier LAVA_SPRITE = Identifier.withDefaultNamespace("block/lava_still");
	private static final Identifier FIRE_SPRITE = Identifier.withDefaultNamespace("block/fire_0");
	private static final Identifier SOUL_FIRE_SPRITE = Identifier.withDefaultNamespace("block/soul_fire_0");

	/**
	 * 岩浆过渡用的降采样边长：把单帧 16×16 盒式平均成 {@value #LAVA_TILE_SIZE}×{@value #LAVA_TILE_SIZE}。
	 *
	 * <p>岩浆贴图有高频噪点，直接双线性采样会让相邻方块的颜色跟着噪点跳（实测总亮度跳变可达 50），
	 * 画出来是"斑点"；先平均成小块、抹掉高频，过渡才平滑。
	 */
	private static final int LAVA_TILE_SIZE = 8;

	/**
	 * 岩浆采样步长（纹素/格）：取 {@value #LAVA_SAMPLE_STEP}，小图边长 {@value #LAVA_TILE_SIZE}，
	 * 整片岩浆走完一个循环约 53 格。
	 *
	 * <p>取 {@value #LAVA_SAMPLE_STEP}（原 0.10）：0.10 时一片小岩浆池只覆盖不到一个纹素，颜色几乎不变；
	 * 调到 0.15 后同样的池子能走过好几个纹素，明暗过渡才看得出来，同时仍远小于 1 纹素/格——不会跳色。
	 */
	private static final float LAVA_SAMPLE_STEP = 0.15F;

	/**
	 * 采样相位的"平滑扰动"幅度（纹素）与频率。
	 *
	 * <p>按 16 格一挡加偏移会在格边界跳变（实测总亮度跳变 119），画出来是一道道硬缝；
	 * 改成随位置连续变化的正弦扰动，既能打散重复图案又处处可导、不产生缝。
	 */
	private static final float LAVA_WARP = 1.0F;
	private static final float LAVA_WARP_FREQ = 0.055F;

	/**
	 * 发光下限（三通道之和），<b>必须与 {@code ChunkRecord} 里的拉亮规则一致</b>。
	 *
	 * <p>着色时自发光方块会被等比拉到这个下限，会抹平贴图明暗；所以这里先在本色上把亮度抬过这条线，
	 * 见 {@link #liftAboveGlowFloor}。
	 */
	private static final int GLOW_FLOOR_TOTAL = 480;

	/** 抬亮后的总亮度上限，避免整片岩浆过曝、糊成一片纯亮色。 */
	private static final int GLOW_CEIL_TOTAL = 600;

	/** 贴图名 → 平均色。多线程共用，见类注释的线程模型。 */
	private final Map<Identifier, Integer> bySprite = new ConcurrentHashMap<>();

	/** 贴图名 → 发光方块的平均色。岩浆这类方块走的是另一套取色规则，不能混用。 */
	private final Map<Identifier, Integer> byEmissiveSprite = new ConcurrentHashMap<>();

	/** 方块状态 → 本色。方块状态是单例，直接拿它当键最省事。 */
	private final Map<BlockState, Integer> byState = new ConcurrentHashMap<>();

	/**
	 * 方块状态 → <b>被水盖住</b>时用的本色（自发光方块不按光源取色）。
	 *
	 * <p>只有自发光方块会往这张表里写，见 {@link #coveredColorOf}。
	 */
	private final Map<BlockState, Integer> byCoveredState = new ConcurrentHashMap<>();

	/** 方块本色，0xRRGGBB。同一种方块状态只会解析一次。 */
	int colorOf(Minecraft minecraft, BlockState state) {
		Integer cached = byState.get(state);
		if (cached != null) {
			return cached;
		}
		int color = resolve(minecraft, state, state.getLightEmission() > 0);
		byState.put(state, color);
		return color;
	}

	/**
	 * 被水盖住时的本色：自发光方块不再按"光源"取色，改用普通方块的柔和平均色。
	 *
	 * <p>地图上盖了水的那一列画的是"水 + 水底"，水底即便本来是光源，沉在水下也看不出发光；
	 * 若仍按发光档取色（只取贴图最亮的两成再整体提亮，见 {@link TextureAverage#averageBrightest}），
	 * 水底就会透出一块偏亮的色斑。水面之上不叠水，仍按光源显示（见 {@code ChunkRecord#FLAG_EMISSIVE}）。
	 *
	 * <p>只有自发光方块会用到这张缓存，普通方块复用 {@link #colorOf}；"有没有被水盖住"只有调用方
	 * 知道，所以判定放在这里。
	 */
	int coveredColorOf(Minecraft minecraft, BlockState state) {
		if (state.getLightEmission() <= 0) {
			return colorOf(minecraft, state);
		}
		Integer cached = byCoveredState.get(state);
		if (cached != null) {
			return cached;
		}
		int color = resolve(minecraft, state, false);
		byCoveredState.put(state, color);
		return color;
	}

	private int resolve(Minecraft minecraft, BlockState state, boolean emissive) {
		Identifier sprite = namedSprite(state);
		if (sprite == null) {
			sprite = topSprite(minecraft, state);
		}
		return sprite == null ? FALLBACK
				: averageColor(minecraft.getResourceManager(), sprite, emissive);
	}

	/** 原版点名取色的方块：流体（水、岩浆）与火（普通火、灵魂火）。见各 {@code *_SPRITE} 常量。 */
	private static Identifier namedSprite(BlockState state) {
		if (state.is(Blocks.WATER)) {
			return WATER_SPRITE;
		}
		if (state.is(Blocks.LAVA)) {
			return LAVA_SPRITE;
		}
		if (state.is(Blocks.FIRE)) {
			return FIRE_SPRITE;
		}
		if (state.is(Blocks.SOUL_FIRE)) {
			return SOUL_FIRE_SPRITE;
		}
		return null;
	}

	/** 方块朝上那一面的贴图；没有朝上的面就退回模型自带的粒子贴图。 */
	private static Identifier topSprite(Minecraft minecraft, BlockState state) {
		BlockStateModel model;
		try {
			model = minecraft.getModelManager().getBlockStateModelSet().get(state);
		} catch (RuntimeException e) {
			return null;
		}
		if (model == null) {
			return null;
		}
		List<BlockStateModelPart> parts = new ArrayList<>(2);
		model.collectParts(RandomSource.create(0L), parts);
		for (BlockStateModelPart part : parts) {
			for (BakedQuad quad : part.getQuads(Direction.UP)) {
				TextureAtlasSprite sprite = quad.materialInfo().sprite();
				if (sprite != null) {
					return sprite.contents().name();
				}
			}
		}
		for (BlockStateModelPart part : parts) {
			if (part.particleMaterial() != null && part.particleMaterial().sprite() != null) {
				return part.particleMaterial().sprite().contents().name();
			}
		}
		return null;
	}

	/** 贴图名 → 供 {@link #lavaColor} 采样的降采样小图。只给岩浆用，多线程共用。 */
	private final Map<Identifier, LavaTile> byPixels = new ConcurrentHashMap<>();

	/**
	 * 一张岩浆贴图的"单帧降采样小图"：边长 {@code size}，逐行存放 ARGB 的 RGB 部分；
	 * {@code minTotal}/{@code maxTotal} 是小图里三通道之和的最小/最大值，供
	 * {@link #liftAboveGlowFloor} 把整张小图的明暗<b>撑满</b>可用亮度带（见那里的说明）。
	 */
	private record LavaTile(int[] rgb, int size, int minTotal, int maxTotal) {
	}

	/**
	 * 岩浆的颜色：从"降采样后的单帧贴图"里按方块坐标<b>平滑采样</b>，而不是取整张的平均色。
	 *
	 * <p>岩浆是<b>一整片</b>，取平均色会变成死板的纯色。贴图本身带纹理，但直接逐纹素采样的高频噪点
	 * 会让相邻方块跳色，所以先盒式平均成 {@link #LAVA_TILE_SIZE}×{@link #LAVA_TILE_SIZE} 小图，
	 * 再用很小的 {@link #LAVA_SAMPLE_STEP} 在它上面推进并做双线性插值：相邻方块取到的是相近又微微
	 * 推进的位置，明暗与色相于是连续缓变。再加一个随位置连续变化的正弦扰动（{@link #LAVA_WARP}）
	 * 打散重复图案。
	 *
	 * <p>这里要先把本色抬到发光下限<b>之上</b>：着色那一步按总亮度等比缩放，会抹平贴图的明暗差异；
	 * 先抬过下限，等比缩放不再改动它，过渡得以保留（见 {@link #liftAboveGlowFloor}）。
	 */
	int lavaColor(Minecraft minecraft, int worldX, int worldZ) {
		LavaTile lava = lavaTile(minecraft.getResourceManager(), LAVA_SPRITE);
		if (lava == null || lava.size() <= 0) {
			return colorOf(minecraft, Blocks.LAVA.defaultBlockState());
		}
		float u = worldX * LAVA_SAMPLE_STEP + LAVA_WARP * (float) Math.sin(worldZ * LAVA_WARP_FREQ);
		float v = worldZ * LAVA_SAMPLE_STEP + LAVA_WARP * (float) Math.cos(worldX * LAVA_WARP_FREQ);
		return liftAboveGlowFloor(bilinear(lava, u, v), lava);
	}

	/** 在小图上的双线性采样；横竖都按小图边长回绕（小图首尾相接）。 */
	private static int bilinear(LavaTile lava, float u, float v) {
		int size = lava.size();
		u -= (float) Math.floor(u / size) * size;
		v -= (float) Math.floor(v / size) * size;
		int x0 = (int) u;
		int y0 = (int) v;
		if (x0 >= size) {
			x0 = size - 1;
		}
		if (y0 >= size) {
			y0 = size - 1;
		}
		int x1 = x0 + 1 == size ? 0 : x0 + 1;
		int y1 = y0 + 1 == size ? 0 : y0 + 1;
		int[] rgb = lava.rgb();
		int top = mix(rgb[y0 * size + x0], rgb[y0 * size + x1], u - x0);
		int bottom = mix(rgb[y1 * size + x0], rgb[y1 * size + x1], u - x0);
		return mix(top, bottom, v - y0) & 0xFFFFFF;
	}

	/** 两个颜色按 t（0..1）线性混合三个通道。 */
	private static int mix(int rgbA, int rgbB, float t) {
		int red = (int) (((rgbA >> 16) & 0xFF) * (1.0F - t) + ((rgbB >> 16) & 0xFF) * t);
		int green = (int) (((rgbA >> 8) & 0xFF) * (1.0F - t) + ((rgbB >> 8) & 0xFF) * t);
		int blue = (int) ((rgbA & 0xFF) * (1.0F - t) + (rgbB & 0xFF) * t);
		return red << 16 | green << 8 | blue;
	}

	/**
	 * 把岩浆本色抬到发光下限之上，并让总亮度随贴图本来的明暗浮动。
	 *
	 * <p>着色那一步只保证"不低于下限"；只要这里已超过下限就不会再动它，贴图的明暗过渡因此保留。
	 * 目标总亮度把这张小图的明暗按<b>它自己的 {@code minTotal}~{@code maxTotal}</b> 线性映射到
	 * [{@link #GLOW_FLOOR_TOTAL}, {@link #GLOW_CEIL_TOTAL}]：最暗的一格落到下限、最亮的一格落到上限，
	 * 中间的灰度逐个铺开。
	 *
	 * <p><b>为什么要按小图自己的范围归一化</b>（以前是拿一个写死的"典型亮度"当基准）：
	 * 旧写法 {@code 480 + 0.5 × (total - 380)} 里那条 {@code 380} 是贴图的<b>典型</b>亮度，于是
	 * <b>凡是不亮于典型的像素全被夹到同一个 480</b>——半张贴图糊成一块纯色，整片岩浆看着很单调。
	 * 归一化之后同样的亮度带里铺开的是完整明暗，观感立刻有层次；而且换资源包也会自适应。
	 */
	private static int liftAboveGlowFloor(int rgb, LavaTile lava) {
		int red = (rgb >> 16) & 0xFF;
		int green = (rgb >> 8) & 0xFF;
		int blue = rgb & 0xFF;
		int total = red + green + blue;
		if (total <= 0) {
			return rgb;
		}
		int span = lava.maxTotal() - lava.minTotal();
		double t = span > 0 ? (total - lava.minTotal()) / (double) span : 0.0;
		t = Math.clamp(t, 0.0, 1.0);
		int target = (int) Math.round(GLOW_FLOOR_TOTAL + t * (GLOW_CEIL_TOTAL - GLOW_FLOOR_TOTAL));
		double factor = target / (double) total;
		return Math.clamp((int) Math.round(red * factor), 0, 255) << 16
				| Math.clamp((int) Math.round(green * factor), 0, 255) << 8
				| Math.clamp((int) Math.round(blue * factor), 0, 255);
	}

	/**
	 * 读岩浆贴图并做一次"取单帧 + 盒式平均降采样"。
	 *
	 * <p>贴图是逐帧竖排的动画贴图，静态地图只需最上面那一帧；把 {@code width×width} 的帧平均成
	 * {@link #LAVA_TILE_SIZE}×{@link #LAVA_TILE_SIZE} 小图。帧边长不能被小图边长整除时就不降采样
	 * （小图 = 整帧），照样能跑，只是噪点大一点。
	 */
	private LavaTile lavaTile(ResourceManager resources, Identifier spriteId) {
		LavaTile cached = byPixels.get(spriteId);
		if (cached != null) {
			return cached;
		}
		Identifier file = spriteId.withPrefix("textures/").withSuffix(".png");
		Optional<Resource> resource = resources.getResource(file);
		if (resource.isEmpty()) {
			return null;
		}
		try (InputStream stream = resource.get().open(); NativeImage image = NativeImage.read(stream)) {
			int frame = Math.min(image.getWidth(), image.getHeight());
			if (frame <= 0) {
				return null;
			}
			int size = frame % LAVA_TILE_SIZE == 0 ? LAVA_TILE_SIZE : frame;
			int block = frame / size;
			int[] rgb = new int[size * size];
			int minTotal = Integer.MAX_VALUE;
			int maxTotal = 0;
			for (int y = 0; y < size; y++) {
				for (int x = 0; x < size; x++) {
					long red = 0;
					long green = 0;
					long blue = 0;
					for (int dy = 0; dy < block; dy++) {
						for (int dx = 0; dx < block; dx++) {
							int pixel = image.getPixel(x * block + dx, y * block + dy);
							red += (pixel >> 16) & 0xFF;
							green += (pixel >> 8) & 0xFF;
							blue += (pixel & 0xFF);
						}
					}
					int count = block * block;
					int r = (int) (red / count);
					int g = (int) (green / count);
					int b = (int) (blue / count);
					rgb[y * size + x] = r << 16 | g << 8 | b;
					// 记下这张小图自己的明暗范围，供 liftAboveGlowFloor 归一化（见那里的说明）
					int total = r + g + b;
					minTotal = Math.min(minTotal, total);
					maxTotal = Math.max(maxTotal, total);
				}
			}
			LavaTile loaded = new LavaTile(rgb, size, minTotal, maxTotal);
			byPixels.put(spriteId, loaded);
			return loaded;
		} catch (IOException | RuntimeException e) {
			LOGGER.debug("读取方块贴图 {} 失败", file, e);
			return null;
		}
	}

	private int averageColor(ResourceManager resources, Identifier spriteId, boolean emissive) {
		Map<Identifier, Integer> cache = emissive ? byEmissiveSprite : bySprite;
		Integer cached = cache.get(spriteId);
		if (cached != null) {
			return cached;
		}
		int color = readAverage(resources, spriteId, emissive);
		cache.put(spriteId, color);
		return color;
	}

	private int readAverage(ResourceManager resources, Identifier spriteId, boolean emissive) {
		Identifier file = spriteId.withPrefix("textures/").withSuffix(".png");
		Optional<Resource> resource = resources.getResource(file);
		if (resource.isEmpty()) {
			LOGGER.debug("找不到方块贴图 {}，该方块在地图上用兜底色", file);
			return FALLBACK;
		}
		try (InputStream stream = resource.get().open(); NativeImage image = NativeImage.read(stream)) {
			int[] pixels = toArray(image);
			return emissive ? TextureAverage.averageBrightest(pixels) : TextureAverage.average(pixels);
		} catch (IOException | RuntimeException e) {
			LOGGER.debug("读取方块贴图 {} 失败，该方块在地图上用兜底色", file, e);
			return FALLBACK;
		}
	}

	/** 把贴图像素读成一维 ARGB 数组，交给共享层的取色规则。 */
	private static int[] toArray(NativeImage image) {
		int[] pixels = new int[image.getWidth() * image.getHeight()];
		int index = 0;
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				pixels[index++] = image.getPixel(x, y);
			}
		}
		return pixels;
	}
}
