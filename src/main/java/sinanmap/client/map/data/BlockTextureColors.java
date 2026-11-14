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

	/**
	 * 岩浆的颜色：<b>整张贴图的平均色</b>，整片岩浆共用一个颜色。
	 *
	 * <p><b>为什么是平均色</b>：以前按方块坐标在贴图上"逐块采样"，想让大片熔岩有过渡，但采样位置天然
	 * 形成以几十格为周期的循环，一整片熔岩湖就出现一大块偏亮、一大块偏暗，收窄幅度也照样花。
	 * 岩浆在游戏里本来就是一整片同温的流体，用平均色最自然；
	 * 起伏交给着色时的地形高度/坡度（{@link sinanmap.common.map.SurfaceShading#reliefFactor}）。
	 *
	 * <p>取平均值时用 {@link TextureAverage#averageBrightest}：岩浆贴图有一半是暗红"结壳"，
	 * 算术平均会偏暗，取偏亮的那一档才接近游戏里看到的亮橙。
	 */
	int lavaColor(Minecraft minecraft) {
		int average = averageColor(minecraft.getResourceManager(), LAVA_SPRITE, true);
		if (average == FALLBACK) {
			return colorOf(minecraft, Blocks.LAVA.defaultBlockState());
		}
		// 按最亮通道撑到满值：贴图平均色本身偏暗（实测铺到地图上是 #E08125，而游戏内约 #FF8D22），
		// 撑满后三通道同比例放大，色相不变、亮度对得上
		int red = (average >> 16) & 0xFF;
		int green = (average >> 8) & 0xFF;
		int blue = average & 0xFF;
		int value = Math.max(red, Math.max(green, blue));
		if (value <= 0) {
			return average;
		}
		double factor = 255.0 / value;
		return Math.clamp((int) Math.round(red * factor), 0, 255) << 16
				| Math.clamp((int) Math.round(green * factor), 0, 255) << 8
				| Math.clamp((int) Math.round(blue * factor), 0, 255);
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
