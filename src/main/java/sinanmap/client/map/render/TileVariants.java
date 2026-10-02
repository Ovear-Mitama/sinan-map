package sinanmap.client.map.render;

import net.minecraft.resources.Identifier;

/**
 * 一页瓦片的<b>贴图与它们在贴图管理器里的名字</b>：白天、夜晚各一套。
 *
 * <p>要名字是因为 26.1 里能带颜色的 {@code blit} 只接受 {@link Identifier}，而接受
 * {@code GpuTextureView} 的那条没有颜色参数；要按天色给夜晚那张压 alpha，只能把贴图挂到贴图管理器上
 * 用名字去画（见 {@link TileTexturePool#obtain}）。
 *
 * <p><b>每张图两个名字</b>：{@code dayId}/{@code nightId} 线性（小地图用，连续滑动，最近邻会跳），
 * {@code daySharpId}/{@code nightSharpId} 最近邻（全屏地图用，线性会糊）。两个名字指向同一份显存，
 * 见 {@link ResampledTexture}。
 *
 * <p>从 {@code WorldTilePages} 拆出来：叶页与粗页都要用它，而它本身只是"一对贴图 + 四个名字"，
 * 与页的填充逻辑无关。
 */
public record TileVariants(SmoothedDynamicTexture day, Identifier dayId,
		SmoothedDynamicTexture night, Identifier nightId,
		ResampledTexture daySharp, Identifier daySharpId,
		ResampledTexture nightSharp, Identifier nightSharpId) {

	/** 只回收显存，像素留着，见 {@link SmoothedDynamicTexture#releaseGpu()}。 */
	void releaseGpu() {
		day.releaseGpu();
		night.releaseGpu();
	}

	/** 把显存要回来并把像素传上去，见 {@link SmoothedDynamicTexture#reacquireGpu()}。 */
	void reacquireGpu() {
		day.reacquireGpu();
		night.reacquireGpu();
	}

	/** 连像素一起释放。两个"锐"的名字只是换采样器，各关掉自己那份 1×1 占位即可。 */
	void close() {
		day.close();
		night.close();
		daySharp.close();
		nightSharp.close();
	}
}
