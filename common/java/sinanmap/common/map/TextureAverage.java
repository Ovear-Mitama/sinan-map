package sinanmap.common.map;

/**
 * 把一张方块贴图的像素收成一个"本色"。
 *
 * <p>放在共享层是为了能被单元测试覆盖：它是纯数学，没有理由不测。平台侧只负责把贴图读成
 * ARGB 数组再交给这里。
 */
public final class TextureAverage {

	/** 全透明像素的判定阈值：贴图边缘常有一圈全透明像素，算进去会把颜色拉灰。 */
	public static final int MIN_ALPHA = 128;

	/** 一个有效像素都没有时的兜底色。 */
	public static final int FALLBACK = 0x7F7F7F;

	/**
	 * 柔和化：保留原色的比例（越小越去饱和）。
	 *
	 * <p>贴图是设计成在光照、环境光遮蔽之下观看的，直接取平均色会偏亮偏饱和，一格格铺进地图就显得
	 * "塑料、霓虹"，像缩略图而不是地图。这里把每个通道往它的明度收一点，所有方块统一变柔和。
	 *
	 * <p><b>不能收得太狠。</b>收过头会把所有方块洗成灰的，整张图发灰、发卡通；红沙、红土这类靠通道差
	 * 拉开辨识度的方块首当其冲。取 {@value #MUTE_BLEND} 只做轻微收敛。
	 */
	public static final double MUTE_BLEND = 0.94;

	/**
	 * 柔和化后整体压暗/提亮的系数。
	 *
	 * <p>只做很轻微的提亮。曾经取到 1.08 想补偿"比同类地图偏暗"，结果沙地这类本身就很亮的方块直接
	 * 顶到纯白；也绝不能大幅压暗，那会整体发闷。实测沙地本色比参考值略暗，因此留一点点提亮。
	 */
	public static final double MUTE_BRIGHTNESS = 1.04;

	/**
	 * 发光方块取色时的亮度分位线（百分比）。
	 *
	 * <p>熔岩贴图是"暗色渣壳 + 明亮熔体"的两极分布：分位线太低会把暗的一半也算进来，等于没提亮
	 * （岩浆会算成暗红棕）。取 80 分位只保留最亮的两成，才是熔体那一档颜色。
	 */
	public static final int BRIGHT_PERCENTILE = 80;

	/**
	 * 发光方块的额外提亮倍率。
	 *
	 * <p>它们在游戏里自发光、恒亮显示，而贴图平均色只是"贴图看起来的颜色"，不含发光带来的观感抬升，
	 * 直接用会明显偏暗。
	 */
	public static final double EMISSIVE_GAIN = 1.15;

	/**
	 * 花卉的额外饱和度倍率。
	 *
	 * <p>花在图上只占一格，而贴图平均色会把花瓣和绿茎混成一片暗哑的橄榄色，铺在草色底子上几乎看不
	 * 出来。这里给花单独把通道差拉开一点（保留明度，只放大"离明度的距离"），让它们跳出来。
	 * 取 {@value #FLOWER_SATURATION}：够显眼，又不至于艳成霓虹。
	 */
	public static final double FLOWER_SATURATION = 1.4;

	private TextureAverage() {
	}

	/**
	 * 提高饱和度：以明度为轴，把各通道往外拉开 {@code factor} 倍（{@link #mute} 的反向操作）。
	 *
	 * <p>只放大"离明度的距离"，整体明度不变，所以不会把花涂成一块高亮的色斑。
	 */
	public static int saturate(int rgb, double factor) {
		int lum = luminance(rgb);
		int r = Math.clamp((int) (lum + (((rgb >> 16) & 0xFF) - lum) * factor), 0, 255);
		int g = Math.clamp((int) (lum + (((rgb >> 8) & 0xFF) - lum) * factor), 0, 255);
		int b = Math.clamp((int) (lum + ((rgb & 0xFF) - lum) * factor), 0, 255);
		return r << 16 | g << 8 | b;
	}

	/**
	 * 普通方块的取色：逐像素求平均，再做柔和化。
	 *
	 * @param argb 贴图像素（ARGB），顺序无关
	 */
	public static int average(int[] argb) {
		long red = 0L;
		long green = 0L;
		long blue = 0L;
		long count = 0L;
		for (int pixel : argb) {
			if ((pixel >>> 24) < MIN_ALPHA) {
				continue;
			}
			red += (pixel >> 16) & 0xFF;
			green += (pixel >> 8) & 0xFF;
			blue += pixel & 0xFF;
			count++;
		}
		if (count == 0L) {
			return FALLBACK;
		}
		return mute((int) (red / count), (int) (green / count), (int) (blue / count));
	}

	/**
	 * 发光方块（岩浆、火）的取色：只平均较亮的那些像素，且不做柔和化。
	 *
	 * <p>这类贴图里混着暗色渣壳与明亮熔体，直接平均会被暗部拉下去（岩浆因此算成暗红棕）；而它们本身
	 * 发光，也不该再压暗去饱和，否则颜色又闷又脏。
	 */
	public static int averageBrightest(int[] argb) {
		int[] histogram = new int[256];
		long total = 0L;
		for (int pixel : argb) {
			if ((pixel >>> 24) < MIN_ALPHA) {
				continue;
			}
			histogram[luminance(pixel)]++;
			total++;
		}
		if (total == 0L) {
			return FALLBACK;
		}

		// 亮度分位线当阈值：只统计不低于它的那些像素。
		// 用向上取整：贴图只有几百个像素，整除会把分位线压回中位数，
		// 于是在"一半暗一半亮"的贴图上正好把暗的那一半也圈进来，等于没提亮
		long target = Math.max(1L, (total * BRIGHT_PERCENTILE + 99L) / 100L);
		long seen = 0L;
		int threshold = 0;
		for (int level = 0; level < histogram.length; level++) {
			seen += histogram[level];
			if (seen >= target) {
				threshold = level;
				break;
			}
		}

		long red = 0L;
		long green = 0L;
		long blue = 0L;
		long count = 0L;
		for (int pixel : argb) {
			if ((pixel >>> 24) < MIN_ALPHA || luminance(pixel) < threshold) {
				continue;
			}
			red += (pixel >> 16) & 0xFF;
			green += (pixel >> 8) & 0xFF;
			blue += pixel & 0xFF;
			count++;
		}
		if (count == 0L) {
			return FALLBACK;
		}
		return scale((int) (red / count), (int) (green / count), (int) (blue / count), EMISSIVE_GAIN);
	}

	/** 按倍率缩放三通道并钳位。 */
	private static int scale(int r, int g, int b, double factor) {
		return Math.clamp((int) (r * factor), 0, 255) << 16
				| Math.clamp((int) (g * factor), 0, 255) << 8
				| Math.clamp((int) (b * factor), 0, 255);
	}

	/** 感知明度 0~255。 */
	public static int luminance(int rgb) {
		return (int) Math.round(0.299 * ((rgb >> 16) & 0xFF)
				+ 0.587 * ((rgb >> 8) & 0xFF)
				+ 0.114 * (rgb & 0xFF));
	}

	/** 把颜色往明度方向拉，再按 {@link #MUTE_BRIGHTNESS} 整体调亮。 */
	static int mute(int r, int g, int b) {
		int lum = luminance(r << 16 | g << 8 | b);
		int mr = (int) ((lum + (r - lum) * MUTE_BLEND) * MUTE_BRIGHTNESS);
		int mg = (int) ((lum + (g - lum) * MUTE_BLEND) * MUTE_BRIGHTNESS);
		int mb = (int) ((lum + (b - lum) * MUTE_BLEND) * MUTE_BRIGHTNESS);
		return Math.clamp(mr, 0, 255) << 16 | Math.clamp(mg, 0, 255) << 8 | Math.clamp(mb, 0, 255);
	}
}
