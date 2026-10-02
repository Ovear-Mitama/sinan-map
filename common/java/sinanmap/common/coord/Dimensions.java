package sinanmap.common.coord;

/**
 * 维度的公共信息：显示名与坐标换算比例。
 *
 * <p>只做与加载器无关的纯计算：聊天里那句"在 XX"与世界地图的维度切换按钮都要用同一套名字，
 * 两处各写一份迟早会不一样。
 *
 * <p><b>刻意不在这里列"有哪些维度"</b>：那要从客户端连接里取（含其它模组注册的维度），
 * 写死三个原版维度会让玩家切到一个服务器根本没有的维度。
 */
public final class Dimensions {

	public static final String OVERWORLD = "minecraft:overworld";

	public static final String NETHER = "minecraft:the_nether";

	public static final String END = "minecraft:the_end";

	private Dimensions() {
	}

	/** 维度 id 对应的名字语言键；不是原版三维度时返回 {@code null}。 */
	public static String translationKey(String dimension) {
		if (dimension == null) {
			return null;
		}
		return switch (dimension) {
			case OVERWORLD -> "dimension.sinanmap.overworld";
			case NETHER -> "dimension.sinanmap.the_nether";
			case END -> "dimension.sinanmap.the_end";
			default -> null;
		};
	}

	/**
	 * 该维度里 1 方块相当于主世界的多少方块。
	 *
	 * <p>主世界 1、下界 8（下界走 1 格等于主世界 8 格）、其余 1（末地与模组维度按 1:1）。切换维度看地图时
	 * 用它把视图中心换算过去，好让两边的"同一个地点"落在同一处。
	 */
	public static double overworldScale(String dimension) {
		return NETHER.equals(dimension) ? 8.0 : 1.0;
	}
}
