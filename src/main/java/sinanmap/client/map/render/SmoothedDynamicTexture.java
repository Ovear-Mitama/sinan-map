package sinanmap.client.map.render;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;

import net.minecraft.client.renderer.texture.DynamicTexture;

/**
 * 地图用的动态贴图：<b>缩小用线性、放大用最近邻</b>（min=LINEAR、mag=NEAREST）。
 *
 * <ul>
 *   <li><b>缩小</b>时必须线性：否则地图一动、取样点每帧落在不同像素上，会出现"麻麻的"闪烁。</li>
 *   <li><b>放大</b>时用最近邻：线性会把每个方块的边缘插值成一段渐变，看起来就是发糊。</li>
 * </ul>
 *
 * <p>不要"在缩放动画期间把放大也切成线性"：那样整段缩放过程都是糊的，比动画中的台阶感更难受。
 *
 * <p>唯一例外是
 * {@link #SmoothedDynamicTexture(String, int, int, boolean, boolean)} 的 {@code smooth}
 * ——<b>画瓦片页的那两张</b>用它：页贴图一个像素一个方块，而小地图连续滑动、落点必然不在整数设备
 * 像素上，放大用最近邻就会一格一格地跳。全屏地图要的正好相反（线性会把方块边缘糊成渐变），
 * 它拿的是这份显存贴图的另一个名字，采样器为最近邻，见 {@link ResampledTexture}。
 */
final class SmoothedDynamicTexture extends DynamicTexture {

	/** 建显存贴图时用的这个名字，回收之后再用同一名字把显存要回来。 */
	private final String label;

	SmoothedDynamicTexture(String name, int width, int height, boolean clear) {
		this(name, width, height, clear, false);
	}

	/**
	 * @param smooth 放大时是否也用线性过滤，见类注释
	 */
	SmoothedDynamicTexture(String name, int width, int height, boolean clear, boolean smooth) {
		super(name, width, height, clear);
		this.label = name;
		this.sampler = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE,
				AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR,
				smooth ? FilterMode.LINEAR : FilterMode.NEAREST, false);
	}

	/**
	 * 只回收<b>显存</b>那一份，堆里的像素原样留着。
	 *
	 * <p>页被淘汰时用这个而不是 {@link #close()}：{@code close()} 会连像素一起释放，回来就只能重新
	 * 采样、重新着色（"移出去再回来看又加载一遍"）。留着像素回来只差一次上传，见 {@link #reacquireGpu()}。
	 *
	 * <p>释放之后<b>不能再画它</b>：{@code getTextureView()} 会抛异常；调用方必须保证被淘汰的页已不在
	 * 绘制范围内（{@code WorldTilePages} 里只有常驻页会被画）。
	 */
	void releaseGpu() {
		if (texture != null) {
			texture.close();
			texture = null;
		}
		if (textureView != null) {
			textureView.close();
			textureView = null;
		}
	}

	/**
	 * 把显存要回来，并把堆里现有的像素传上去（<b>一次</b>上传，不重新采样、不重新着色）。
	 *
	 * <p>建贴图的 {@code usage} 必须与 {@code DynamicTexture} 自己建的时候一致
	 * （可作拷贝目标 + 可绑定为纹理），否则上传或绘制会失败；采样器不受影响。
	 */
	void reacquireGpu() {
		if (texture != null) {
			return;
		}
		GpuDevice device = RenderSystem.getDevice();
		texture = device.createTexture(label,
				GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
				TextureFormat.RGBA8, getPixels().getWidth(), getPixels().getHeight(), 1, 1);
		textureView = device.createTextureView(texture);
		upload();
	}
}
