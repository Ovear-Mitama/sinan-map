package sinanmap.common.map;

/**
 * 地表着色：在方块本色之上叠加高度、坡度与光照三种明暗，让平面地图能读出地形。
 *
 * <p>三项都是纯乘法，结果只依赖输入，因此可以在地表颜色进入缓存时一次性算好：高度给出大尺度高低感；
 * 坡度用西侧与北侧邻居估算坡向、模拟西北方向来的光，山脊沟谷因此轮廓清晰；光照直接用游戏内实际亮度。
 *
 * <p><b>区间刻意非对称</b>：平地满光照时乘积恰为 1.0（显示方块本色），亮点一侧只留很小余量
 * （上限 {@value #MAX_TOTAL_FACTOR}），暗的一侧才放开。三项若对称给到 1.4~1.6 倍，最亮处会到 2 倍以上，整张图惨白。
 */
public final class SurfaceShading {

	/**
	 * 每高出参考高度 1 方块增加的亮度比例。
	 *
	 * <p>这个值与 {@link #MIN_HEIGHT_FACTOR} / {@link #MAX_HEIGHT_FACTOR} 是<b>配套</b>的：
	 * 亮度在 {@code [1 - (1 - MIN_HEIGHT_FACTOR) / PER_BLOCK, 1 + (MAX_HEIGHT_FACTOR - 1) / PER_BLOCK]}
	 * 这个高度区间里线性渐变，出了区间就变成一条平的"平台"。
	 *
	 * <p>踩过的坑：原来是 {@code 0.012} 配上限 {@code 1.02}，只要高出参考高度<b>两格</b>就顶到上限，
	 * 丘陵、高原、雪山全都一样亮，整张图看着是平的。现在放大梯度、拉宽区间，几格高的台地也能看出明暗。
	 */
	public static final double PER_BLOCK = 0.030;

	/** 高度项的最暗倍率，避免深谷全黑。 */
	public static final double MIN_HEIGHT_FACTOR = 0.63;

	/**
	 * 高度项的最亮倍率。
	 *
	 * <p>亮的一侧刻意留得很少：方块本色本来就偏亮（沙子、雪接近 #F0E8D0），再抬十几个百分点就顶到 255，
	 * 大片亮色压成同一个白、反而丢层次。暗的一侧可以放开，见 {@link #MIN_HEIGHT_FACTOR}。
	 */
	public static final double MAX_HEIGHT_FACTOR = 1.09;

	/** 最大光照等级。 */
	public static final int MAX_LIGHT = 15;

	/** 全黑处的亮度倍率。 */
	public static final double MIN_LIGHT_FACTOR = 0.30;

	/**
	 * 每方块高差对应的坡面亮度变化。
	 *
	 * <p>取 0.26 而不是让亮度只在 0.9~1.0 小幅浮动：高度项只给出大尺度渐变，真正让山脊、沟谷、
	 * 台地"立起来"的是这一项，太弱地形就像摊平的色块图；也不能做成主导，否则地图会变成灰白的高程渲染图。
	 * 上下限见 {@link #MIN_SLOPE_FACTOR} 与 {@link #MAX_SLOPE_FACTOR}：暗的一侧放开，亮的一侧收紧。
	 */
	public static final double SLOPE_STRENGTH = 0.26;

	/** 坡面项的最暗倍率（朝东南的坡）。 */
	public static final double MIN_SLOPE_FACTOR = 0.52;

	/** 坡面项的最亮倍率（朝西北的坡）。 */
	public static final double MAX_SLOPE_FACTOR = 1.07;

	/**
	 * 水面吸收多少地形明暗，让水底的地形透出来。
	 *
	 * <p>两头都不能取：取 0 水面是一片纯色，水底起伏全抹平；取 1 水面被画成一块起伏的布，黑一块亮一块。
	 * 取 0.45 让地形明暗只按比例作用到水面上——水底是正常地形方块（有自己的高度与坡度明暗），
	 * 水只是盖在上面的一层，于是透过水看到的是带明暗的地形而不是一块平色。
	 */
	public static final double WATER_RELIEF = 0.45;

	/** 三项乘积的下限，保证洞穴里仍能分辨轮廓。 */
	public static final double MIN_TOTAL_FACTOR = 0.10;

	/**
	 * 自发光方块（岩浆）的"地形起伏"因子下限与上限。
	 *
	 * <p>岩浆也要能看出表面的高低：完全平涂会是一块死板的纯色，看不出熔岩湖的起伏与边缘。
	 * 但它<b>不能</b>乘光照项（{@link #lightFactor}）——自发光方块本就恒亮，乘光照会在夜里/洞里被压暗。
	 * 所以只取高度×坡度、且把范围收在 1.0 两侧的窄带里，既出层次又不会让岩浆忽明忽暗。
	 */
	public static final double EMISSIVE_RELIEF_MIN = 0.88;

	public static final double EMISSIVE_RELIEF_MAX = 1.08;

	/**
	 * 三项乘积的上限。
	 *
	 * <p>与两项各自的上限<b>配套</b>：两项都取满时乘积约 {@code 1.09 * 1.07 = 1.166}，所以给到
	 * {@value #MAX_TOTAL_FACTOR}，正常地形几乎不会被钳位。
	 *
	 * <p>踩过坑：曾把两项上限留在 1.12/1.15、总上限却压到 1.05，任何朝西北的坡、任何高处都被钳成
	 * 同一个 1.05——亮面变成没有层次的"平台"，整张图像一块平板。宁可总上限略高，也要让亮面有渐变。
	 */
	public static final double MAX_TOTAL_FACTOR = 1.19;

	private SurfaceShading() {
	}

	/**
	 * 计算一列地表的最终颜色。
	 *
	 * @param rgb         方块本色（已含生物群系染色），0xRRGGBB
	 * @param height      该列地表高度
	 * @param referenceY  高度参考（通常取海平面）
	 * @param light       该列上方的实际光照等级 0..15
	 * @param westHeight  西侧邻居的地表高度
	 * @param northHeight 北侧邻居的地表高度
	 * @return 不透明的 ARGB 颜色
	 */
	public static int shade(int rgb, int height, int referenceY, int light, int westHeight, int northHeight) {
		return scale(rgb, totalFactor(height, referenceY, light, westHeight, northHeight));
	}

	/**
	 * 三项明暗系数的乘积，已按上下限钳位。
	 *
	 * <p>单独暴露出来是给区块边界用的：边界列缺少一个方向的邻居，直接算会得到"没有坡度"、
	 * 与相邻列差一档亮度，于是每个区块边界出现一条明暗缝（连起来就是网格）。调用方可以用内部列的值
	 * 补上边界列，见 {@code ChunkRecord#toColors}。
	 */
	public static double totalFactor(int height, int referenceY, int light, int westHeight, int northHeight) {
		return totalFactor(height, referenceY, light, westHeight, northHeight, false);
	}

	/**
	 * 同 {@link #totalFactor(int, int, int, int, int)}，但可以指定这一列是不是平铺的流体层。
	 *
	 * <p><b>流体层（水）要保留一部分地形明暗</b>，见 {@link #WATER_RELIEF}：水面的高度与坡度都来自水底
	 * （水列存的就是底的高度），按比例吸收之后透过水能看出海底起伏。
	 *
	 * <p><b>深浅不在这里乘。</b>深度只压暗覆盖层、不压水底，必须在采集时就分开处理
	 * （见 {@code ClientChunkColorProvider#layeredSample}）；这里若再乘深度系数，压的是混合后的整列，
	 * 连水底一起压掉，深处就完全看不见底了。
	 *
	 * @param flatSheet 是不是"平铺在地形之上的流体层"（水）；冰传 false
	 */
	public static double totalFactor(int height, int referenceY, int light,
			int westHeight, int northHeight, boolean flatSheet) {
		if (flatSheet) {
			double terrain = heightFactor(height, referenceY) * slopeFactor(height, westHeight, northHeight);
			double relief = 1.0 + (terrain - 1.0) * WATER_RELIEF;
			return Math.clamp(lightFactor(light) * relief, MIN_TOTAL_FACTOR, MAX_TOTAL_FACTOR);
		}
		double factor = heightFactor(height, referenceY) * lightFactor(light)
				* slopeFactor(height, westHeight, northHeight);
		return Math.clamp(factor, MIN_TOTAL_FACTOR, MAX_TOTAL_FACTOR);
	}

	/** 天花板维度的洞穴切面带深度（格）：地狱的地形起伏大，带太浅会扫不到地板、整片发黑。 */
	public static final int CEILING_CAVE_BAND = 30;

	/** 按已算好的系数着色，供需要自行调整系数的调用方使用。 */
	public static int shade(int rgb, double factor) {
		return scale(rgb, Math.clamp(factor, MIN_TOTAL_FACTOR, MAX_TOTAL_FACTOR));
	}

	/**
	 * 天花板维度（地狱）的高度项参与的三项乘积，见 {@link #heightFactorCeiling}。
	 *
	 * <p>光照与坡面两项与主世界完全一致，只把高度项换成"覆盖整个维度高度"的那一版。
	 */
	public static double totalFactorCeiling(int height, int light, int westHeight, int northHeight) {
		double factor = heightFactorCeiling(height) * lightFactor(light)
				* slopeFactor(height, westHeight, northHeight);
		return Math.clamp(factor, MIN_TOTAL_FACTOR, MAX_TOTAL_FACTOR);
	}

	/**
	 * 只算"地形起伏"（高度 × 坡度，<b>不含光照</b>），给自发光方块（岩浆）用。
	 *
	 * <p>见 {@link #EMISSIVE_RELIEF_MIN}：岩浆要能看出表面高低，但又不能用光照项（会把它压暗）。
	 * 结果收在 {@code [EMISSIVE_RELIEF_MIN, EMISSIVE_RELIEF_MAX]} 的窄带里，围绕 1.0 起伏。
	 */
	public static double reliefFactor(int height, int referenceY, int westHeight, int northHeight) {
		double factor = heightFactor(height, referenceY) * slopeFactor(height, westHeight, northHeight);
		return Math.clamp(factor, EMISSIVE_RELIEF_MIN, EMISSIVE_RELIEF_MAX);
	}

	/**
	 * 把网格上的光照等级做一次 3×3 平滑。
	 *
	 * <p>光照是 0~15 的整数，相邻列差一级在地图上就是约 1/15 的亮度差——一条看得见的硬边，
	 * 一片本该渐变的地形会变成一圈圈明显色阶。混一下邻域，色阶边缘就有了过渡。
	 *
	 * <p>越界的邻居按<b>最近的边界列</b>处理（而不是当作 0）：这样相邻两个区块在各自边界列上
	 * 算出来的是同一个值，不会因为"看不到对面"而产生一条新接缝。
	 *
	 * @param light 输入光照等级（0~15），行优先，长度至少 {@code size × size}
	 * @param out   输出，可以与 {@code light} 是同一个数组
	 * @param size  网格边长
	 */
	public static void smoothLight(int[] light, int[] out, int size) {
		int[] source = light == out ? light.clone() : light;
		for (int z = 0; z < size; z++) {
			for (int x = 0; x < size; x++) {
				int total = 0;
				for (int dz = -1; dz <= 1; dz++) {
					int nz = Math.clamp(z + dz, 0, size - 1);
					for (int dx = -1; dx <= 1; dx++) {
						int nx = Math.clamp(x + dx, 0, size - 1);
						// 中心权重 4、上下左右 2、四角 1，总和 16
						int weight = dx == 0 && dz == 0 ? 4 : (dx == 0 || dz == 0 ? 2 : 1);
						total += source[nz * size + nx] * weight;
					}
				}
				out[z * size + x] = (total + 8) / 16;
			}
		}
	}

	/** 高度项：相对参考高度越高越亮。 */
	public static double heightFactor(int height, int referenceY) {
		return Math.clamp(1.0 + (height - referenceY) * PER_BLOCK, MIN_HEIGHT_FACTOR, MAX_HEIGHT_FACTOR);
	}

	/**
	 * 天花板维度（地狱这类）的高度参考高度：取维度高度的中段。
	 *
	 * <p>主世界的高度项以海平面 63 为基准、每格 {@value #PER_BLOCK}，上下各十几格就会被钳到
	 * [{@code MIN_HEIGHT_FACTOR}, {@code MAX_HEIGHT_FACTOR}]——地下几十格全是同一个亮度。
	 * 地狱的可走范围只有约 30~80，而且全程没有天空光，高度是<b>唯一</b>的层次来源，
	 * 用主世界那套就等于整张图没有高低变化。
	 */
	public static final int CEILING_REFERENCE_Y = 64;

	/** 天花板维度的高度项：每格增益与上下限（覆盖整个可走范围，不提前钳位）。 */
	public static final double CEILING_PER_BLOCK = 0.0065;
	public static final double CEILING_MIN_HEIGHT_FACTOR = 0.55;
	public static final double CEILING_MAX_HEIGHT_FACTOR = 1.12;

	/** 天花板维度的高度项，见 {@link #CEILING_REFERENCE_Y}。 */
	public static double heightFactorCeiling(int height) {
		return Math.clamp(1.0 + (height - CEILING_REFERENCE_Y) * CEILING_PER_BLOCK,
				CEILING_MIN_HEIGHT_FACTOR, CEILING_MAX_HEIGHT_FACTOR);
	}

	/** 光照项：光照越暗越黑。 */
	public static double lightFactor(int light) {
		double normalized = Math.clamp(light, 0, MAX_LIGHT) / (double) MAX_LIGHT;
		return MIN_LIGHT_FACTOR + (1.0 - MIN_LIGHT_FACTOR) * normalized;
	}

	/**
	 * 坡面项：模拟来自西北方向的平行光。
	 *
	 * <p>本列比西侧邻居低说明坡面朝东，比北侧邻居低说明朝南，两者都低即朝东南，于是变暗；反之变亮。
	 * 这样山脊与沟谷会有一致的明暗边，比单纯的高度渐变清晰得多。
	 */
	public static double slopeFactor(int height, int westHeight, int northHeight) {
		int facingEast = height - westHeight;
		int facingSouth = height - northHeight;
		return Math.clamp(1.0 + (facingEast + facingSouth) * SLOPE_STRENGTH,
				MIN_SLOPE_FACTOR, MAX_SLOPE_FACTOR);
	}

	/** 按倍率缩放三个通道，结果始终不透明。 */
	static int scale(int rgb, double factor) {
		int r = clampChannel((int) Math.round(((rgb >> 16) & 0xFF) * factor));
		int g = clampChannel((int) Math.round(((rgb >> 8) & 0xFF) * factor));
		int b = clampChannel((int) Math.round((rgb & 0xFF) * factor));
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	private static int clampChannel(int value) {
		return Math.clamp(value, 0, 255);
	}
}
