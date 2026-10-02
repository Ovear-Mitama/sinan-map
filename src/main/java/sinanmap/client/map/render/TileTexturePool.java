package sinanmap.client.map.render;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import sinanmap.common.SinanConstants;

/**
 * 瓦片贴图的<b>申请与释放</b>：叶页（512）与粗页（2 档 512、4 档 256）共用这一套，只是尺寸不同。
 *
 * <p>从 {@code WorldTilePages} 拆出来：那一边管"页什么时候建、填、传"，这里只管"贴图从哪来、什么时候
 * 能丢"，两边互不知道对方的细节。顺带把"每张图四个名字"的注册细节收在一处——两个采样器（线性给
 * 小地图、最近邻给全屏地图）指向同一份显存，见 {@link ResampledTexture}。
 */
final class TileTexturePool {

	/**
	 * 等着关掉的贴图：<b>推迟到下一帧再关</b>。
	 *
	 * <p>不能当场关：一帧里的"填充"与"绘制"是两步，贴图可能在第一步被淘汰、下一步仍被画——按名字画
	 * 时名字指向的显存一旦销毁，画出来就是别人的纹理（"在地图上看见别的 MC 纹理"）。约定"这一帧结束后"
	 * 就是最后一个引用消失的时刻，所以统一推迟一帧关闭。
	 */
	private final List<TileVariants> pendingClose = new ArrayList<>();

	/** 给贴图起名用的序号：名字只是渲染后端的调试标注，每对不重名便于排查。 */
	private int serial;

	/** 取一对空白贴图，边长 {@code size} 像素。 */
	TileVariants obtain(int size) {
		// smooth=true（放大也用线性）：这是小地图要的那一套——它是连续滑动的，落点必然不在整数设备
		// 像素上，放大用最近邻就会一格一格地跳。全屏地图要的是最近邻，它拿的是下面注册的"锐"名字。
		int id = serial++;
		SmoothedDynamicTexture day = new SmoothedDynamicTexture(
				"sinanmap_world_tile_" + id + "_day", size, size, true, true);
		SmoothedDynamicTexture night = new SmoothedDynamicTexture(
				"sinanmap_world_tile_" + id + "_night", size, size, true, true);
		GpuSampler sharp = RenderSystem.getSamplerCache().getSampler(
				AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
				FilterMode.LINEAR, FilterMode.NEAREST, false);
		ResampledTexture daySharp = new ResampledTexture("sinanmap_world_tile_" + id + "_day_sharp", day, sharp);
		ResampledTexture nightSharp = new ResampledTexture("sinanmap_world_tile_" + id + "_night_sharp", night, sharp);
		return new TileVariants(day, register(day, "day_" + id), night, register(night, "night_" + id),
				daySharp, register(daySharp, "day_sharp_" + id),
				nightSharp, register(nightSharp, "night_sharp_" + id));
	}

	/** 记下"这一帧之后可以关"的贴图，见 {@link #pendingClose}。 */
	void deferClose(TileVariants textures) {
		pendingClose.add(textures);
	}

	/** 关掉上一帧攒下的贴图（每帧由 {@code WorldTilePages#beginFrame} 调用）。 */
	void flushPendingClose() {
		if (pendingClose.isEmpty()) {
			return;
		}
		for (TileVariants textures : pendingClose) {
			textures.close();
		}
		pendingClose.clear();
	}

	/** 连还没关掉的一起收尾（换世界 / 关掉仓库时用）。 */
	void close() {
		flushPendingClose();
	}

	/**
	 * 把页贴图挂到贴图管理器上，返回它的名字。
	 *
	 * <p>挂上去只是为了能用"带颜色"的那条 {@code blit} 画它——接受动态贴图的那条没有颜色参数，而按天色
	 * 混合夜晚那张必须能给 alpha。
	 *
	 * <p>名字一对贴图一个、<b>不复用</b>：贴图被回收再用时还是同一个对象，名字继续有效；仓库关掉时贴图
	 * 跟着关闭，之后也不会有人拿旧名字去画。管理器里会留下几条指向已关闭贴图的记录，但再次 {@code close()}
	 * 是幂等的，关服时统一关闭不会出问题。
	 */
	private static Identifier register(DynamicTexture texture, String suffix) {
		Identifier id = Identifier.fromNamespaceAndPath(SinanConstants.MOD_ID, "dynamic/world_tile_" + suffix);
		Minecraft.getInstance().getTextureManager().register(id, texture);
		return id;
	}
}
