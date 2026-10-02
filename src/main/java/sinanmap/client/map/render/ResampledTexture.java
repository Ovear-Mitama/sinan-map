package sinanmap.client.map.render;

import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;

import net.minecraft.client.renderer.texture.DynamicTexture;

/**
 * 同一份显存贴图、<b>换一个采样器</b>的"第二个名字"。
 *
 * <p>瓦片页要被两个界面画，两边对采样器的要求正好相反：
 *
 * <ul>
 *   <li><b>小地图</b>一个方块一个设备像素、连续滑动，放大用最近邻会整块跳一格（"走动时明显抖动"），
 *       必须用线性。</li>
 *   <li><b>全屏地图</b>按倍率整页贴，放大用线性会把方块边缘插成渐变、整张糊死，必须用最近邻。</li>
 * </ul>
 *
 * <p>采样器挂在贴图对象上，而两边的像素完全一样，于是让同一份显存贴图有两个名字：
 * {@link #getTextureView()} 借源贴图的视图，{@link #getSampler()} 返回另一个采样器。这样
 * <b>不额外占显存</b>（只多一个 1×1 占位贴图）。占位贴图随对象释放；{@code close()} 只关自己那份，
 * 不会动源贴图。
 */
final class ResampledTexture extends DynamicTexture {

	private final DynamicTexture source;
	private final GpuSampler sampler;

	ResampledTexture(String name, DynamicTexture source, GpuSampler sampler) {
		// 父类需要一个尺寸来建自己的显存贴图，这里给 1×1：它只是占位，永远不会被画
		super(name, 1, 1, false);
		this.source = source;
		this.sampler = sampler;
	}

	@Override
	public GpuTextureView getTextureView() {
		return source.getTextureView();
	}

	@Override
	public GpuSampler getSampler() {
		return sampler;
	}
}
