package sinanmap.common.map;

/**
 * 依据玩家所在高度决定地图该显示哪一层（地表 or 洞穴）。
 *
 * <p>纯函数，便于单元测试：自动切换的判断条件集中在这里，平台侧只负责取地表高度和玩家高度。
 */
public final class LayerSelector {

	/** 表示"地表"的哨兵值。洞穴层用真实 Y 值表示。 */
	public static final int SURFACE = Integer.MIN_VALUE;

	/**
	 * 洞穴<b>归档</b>层的量化步长（格）。
	 *
	 * <p><b>只用于"归档键"</b>：换层的去抖判定与"相邻层才改挂瓦片"的范围判断，每 16 格一档，
	 * 免得玩家在层边界上上下微动时把切换判得太碎。它<b>不</b>是显示层步长（显示层逐格跟随，
	 * 见 {@link #caveStart}），也<b>不</b>是落盘目录步长（见 {@link #STORAGE_STEP}）。
	 */
	public static final int CAVE_STEP = 16;

	/**
	 * 落盘 / 后台预读 / 颜色缓存用的层键步长（格）。
	 *
	 * <p><b>固定 {@value} 格一档，不随显示层逐格变化。</b>否则往下挖几百格就是几百个
	 * {@code cave_<层高>} 目录，后台预读还会把同一个区块按每一个高度各读一遍世界存档（每块 100~200µs）。
	 *
	 * <p><b>为什么是 16 而不是更细的 4。</b>每一档都是一整套区域文件（一个区域 2 MB 上下），
	 * 4 格一档时 y=−64…320 能积出近百个 {@code cave_XX} 目录、同一个区域被存近百遍——实测一份
	 * 只玩了几天的存档，洞穴数据就涨到 1.6 GB，占地表那部分的十倍。16 格一档把目录数压到四分之一，
	 * 而"站在洞里时看到的切面"本来就是实时逐格采样的（见 {@link #caveStart}），只有<b>重看远处</b>的
	 * 洞穴才吃这 16 格的粒度，实际观感几乎不变。
	 *
	 * <p>改这个值会让不是它整数倍的旧 {@code cave_XX} 目录再也读不回来，因此
	 * {@code MapStorage#purgeStaleCaveLayers} 会在打开存储时把那些目录删掉。
	 *
	 * <p>{@link #CAVE_TOLERANCE} 是逐列扫描窗口的半宽，与这里无关，以后改任何一个都不该牵动另一个。
	 */
	public static final int STORAGE_STEP = 16;

	/**
	 * 落盘 / 后台预读 / 颜色缓存用的层键：把显示层按 {@link #STORAGE_STEP} 归档（地表原样返回）。
	 *
	 * <p>与显示层分开之后，切面可以逐格跟着玩家变，而落盘与预读仍共用同一档数据——
	 * 同一档内不会重复读盘、也不会多出目录。
	 */
	public static int storageLayer(int layer) {
		return isCave(layer) ? Math.floorDiv(layer, STORAGE_STEP) * STORAGE_STEP : SURFACE;
	}

	/**
	 * 切面取样点相对玩家抬高多少格。
	 *
	 * <p>取在玩家上方 4 格，是为了让切面落在玩家周围能看见的那一带：顶着脚底下切，
	 * 矿道的地板与两侧的墙都会被切掉一半。
	 */
	public static final int CAVE_LOOK_UP = 4;

	/** 玩家低于该列地表多少格时判定为"已进入地下"。 */
	public static final int ACTIVATION_DEPTH = 3;

	/**
	 * 洞穴切面取样窗口：在层高度上下各多少格内找空气。
	 *
	 * <p><b>只当"扫描窗口半宽"用</b>（逐列扫描的上下沿、以及"同一结果的高度区间"复用），
	 * <b>不是</b>显示层的量化步长——显示层逐格跟随玩家高度（见 {@link #resolve}）。
	 *
	 * <p>有<b>两个</b>取样器必须用同一套规则：已加载区块走实时采样、未加载区块走世界存档，
	 * 窗口差一点点，加载范围的内外两侧就会画出两种洞穴（边界上一条明显的缝）。
	 */
	public static final int CAVE_TOLERANCE = 4;

	/** 洞穴切面从找到的空气往下最多扫多少格找地板；扫不到就当作这一列不显示。 */
	public static final int CAVE_SCAN_DEPTH = 24;

	private LayerSelector() {
	}

	/**
	 * 洞穴层的"起点高度"：<b>只看玩家自己的高度</b>，即 {@code 玩家 Y + }{@value #CAVE_LOOK_UP}。
	 *
	 * <p><b>刻意不看头顶的天花板。</b>按"从玩家脚下往上找第一块实心方块"实现过一版，
	 * 沿矿道平着走时天花板一鼓一凹、层高跟着抖，层一变整套瓦片换键、界面重切，
	 * 看着像"站在同一层却一直在变"。玩家自己的高度只在真的上下移动时才变。
	 *
	 * <p>不看天花板仍然画得对：切面在 {@code [层高 - 容差, 层高 + 容差]} 窗口里自上而下找空气再找地板，
	 * 玩家上方那几格永远落在窗口里，脚下那层地板总是画得出来。天花板也未丢弃——它仍在
	 * {@link #resolve} 里以外一层的地表高度参与"到底在不在洞里"的判断。
	 *
	 * @param playerBlockY 玩家所在的方块高度
	 */
	public static int caveStart(int playerBlockY) {
		return playerBlockY + CAVE_LOOK_UP;
	}

	/**
	 * 解析当前应该显示的层（<b>精确候选值，不含去抖</b>）。
	 *
	 * <p>洞穴层给的是<b>精确高度</b>（{@link #caveStart}），<b>不按任何步长量化</b>：切面在
	 * {@code [层高 ± }{@link #CAVE_TOLERANCE}{@code ]} 的窗口里按列取样，每列都记着结果在哪些高度
	 * 上仍然成立，所以高度动一两格绝大多数列的结论根本不变。
	 *
	 * <p>返回的是"本该显示的候选层"，不是"这一帧真的显示那一层"。玩家原地微动会让候选层每格都在变，
	 * 据此逐帧换层会闪屏，栈顶用 {@link CaveDisplayFilter} 把这段抖动挡掉。落盘 / 预读另按固定档位
	 * 归档，见 {@link #storageLayer}（那些必须量化，否则目录与读盘会爆）。
	 *
	 * @param mode          层模式
	 * @param playerY       玩家高度
	 * @param surfaceHeight 玩家所在列的地表高度
	 * @param caveTop       洞穴层起点高度，见 {@link #caveStart}
	 * @return {@link #SURFACE} 或精确的洞穴层候选高度
	 */
	public static int resolve(LayerMode mode, double playerY, int surfaceHeight, int caveTop) {
		return switch (mode) {
			case SURFACE -> SURFACE;
			case CAVE -> caveTop;
			case AUTO -> playerY < surfaceHeight - ACTIVATION_DEPTH ? caveTop : SURFACE;
		};
	}

	/**
	 * 洞穴<b>显示层</b>的去抖死区（格）。
	 *
	 * <p>候选层与当前显示层相差没到这么多格时一律不换。取 1 表示<b>只有完全相等（差 0）才忽略</b>，
	 * 差 1 格也算一次真正的变化，会进入下面的保持时间判定。
	 *
	 * <p>取 2 时玩家下潜一格不构成候选，缓慢下挖要攒到差 2 格图面才动。改成差 1 就参与判定后，
	 * Y 变一格地图就跟一格；"原地微动不闪"交给{@link #DISPLAY_HOLD_NANOS 保持时间}——
	 * ±1 来回交替时方向每帧翻转、计时不断重来，照样不换层。
	 */
	public static final int DISPLAY_DEADBAND = 1;

	/**
	 * 洞穴显示层的候选值要<b>连续保持</b>这么久才真的生效。
	 *
	 * <p>候选层连续 {@value #DISPLAY_HOLD_NANOS} 纳秒（200 毫秒）都跟当前显示层不同、且方向不变，
	 * 才换过去；半路回到当前值、或方向翻转就作废、重新计时。
	 *
	 * <p>取 200 毫秒比原来那版 350ms 更跟手，又足以让原地 ±1 拉锯攒不满计时。一次跳跃把玩家抬高一格
	 * 约 0.3 秒、比它长，所以跳跃期间显示层可能跟着换 1 格（落地再换回）——这是"1 格也要跟"换来的
	 * 灵敏度，嫌吵就把这个值调到越过 0.3 秒。
	 *
	 * <p>保持时间一到就换到<b>当时的最新高度</b>（不是发起时那个），持续下潜不会被越落越远：
	 * 行走速度（约 4.3 格/秒）下每次换层只落后 1 格左右。
	 */
	public static final long DISPLAY_HOLD_NANOS = 200_000_000L;

	/**
	 * 洞穴显示层的"死区 + 保持时间"去抖（<b>纯逻辑</b>，时刻由调用方传入，便于单测）。
	 *
	 * <p>把显示层从"逐格瞬时跟随"改成"变化要持续一段时间才跟随"，是为了消除玩家原地上下微动时的
	 * 闪烁。判定规则：
	 * <ol>
	 *   <li>{@code |candidate - current| < }{@link #DISPLAY_DEADBAND}（现在就是"完全相等"）：
	 *       不换，并把计时清掉；</li>
	 *   <li>差 1 格及以上：记下方向与时刻，这一帧先不换；</li>
	 *   <li>方向不变地连续保持满 {@link #DISPLAY_HOLD_NANOS}：才换到<b>当前</b>候选值；
	 *       中途回到当前值、或方向翻转，计时重来。</li>
	 * </ol>
	 *
	 * <p><b>方向不变时不重置计时</b>——这一点很关键：逐格下落时候选值每格都在变，若"一变就重置"，
	 * 计时永远跑不满、层就永远不换（图冻在开始下落那一刻）。只认<b>方向</b>就能既跟上持续下落、
	 * 又挡住来回抖动。
	 *
	 * <p>实例<b>不是线程安全</b>的：只在渲染线程里每帧调用一次。
	 */
	public static final class CaveDisplayFilter {

		/** 是否已经记下一次"有变化"（正在等保持时间跑满）。 */
		private boolean pending;
		/** 记下变化时的方向：-1 向下、+1 向上。 */
		private int pendingSign;
		/** 记下变化、且方向未变的起始时刻（纳秒）。 */
		private long pendingSinceNanos;

		/**
		 * 推进一次判定，返回这一帧该显示的洞穴层。
		 *
		 * @param current   上一帧显示的洞穴层（精确高度）
		 * @param candidate 这一帧本该显示的精确高度（{@link LayerSelector#caveStart}）
		 * @param nowNanos  当前时刻；同一次判定内必须保持一致（回退会被当成"没到时间"）
		 * @return 这一帧应显示的洞穴层：多半是 {@code current}，攒够保持时间才是 {@code candidate}
		 */
		public int settle(int current, int candidate, long nowNanos) {
			int diff = candidate - current;
			if (Math.abs(diff) < DISPLAY_DEADBAND) {
				// 跟当前显示层还是同一格：不换，并把计时清掉
				clear();
				return current;
			}
			int sign = diff > 0 ? 1 : -1;
			if (!pending || sign != pendingSign) {
				// 刚开始变化、或方向翻转：重新计时，这一帧先不换
				pending = true;
				pendingSign = sign;
				pendingSinceNanos = nowNanos;
				return current;
			}
			if (nowNanos - pendingSinceNanos < DISPLAY_HOLD_NANOS) {
				return current;
			}
			// 保持够了：换到"当前"候选值（不是发起时的值），持续升降才不会被越落越远
			clear();
			return candidate;
		}

		/** 归零。换世界 / 换维度、或地表↔洞穴类型切换时调用，免得把上一段的状态带进来。 */
		public void reset() {
			clear();
		}

		private void clear() {
			pending = false;
			pendingSign = 0;
			pendingSinceNanos = 0L;
		}
	}

	/**
	 * 缓存与归档用的层键。
	 *
	 * <p>把洞穴层对齐到 {@link #CAVE_STEP} 的整数倍（向下取整）。<b>不是</b>显示层（显示层是精确高度，
	 * 见 {@link #resolve} 与 {@link CaveDisplayFilter}），这里给的是"换层类型判定 / 改挂范围"用的粗档；
	 * 落盘目录用的是更细的 {@link #STORAGE_STEP}。
	 */
	public static int cacheLayer(int layer) {
		return isCave(layer) ? quantize(layer) : SURFACE;
	}

	/** 把层高度对齐到 {@link #CAVE_STEP} 的整数倍（向下取整，负坐标向负无穷）。 */
	public static int quantize(int layerY) {
		return Math.floorDiv(layerY, CAVE_STEP) * CAVE_STEP;
	}

	/** 是否处于洞穴层。 */
	public static boolean isCave(int layer) {
		return layer != SURFACE;
	}

	/**
	 * 两个层是不是同一类（都在地表，或都在洞穴）。
	 *
	 * <p>用来决定切换时要不要做缩放过场：洞穴模式下显示层是<b>逐格</b>跟着玩家高度走的，
	 * 若每次换层都放过场动画，在洞里走动就会不停放大缩小，很烦。只有"地表 ↔ 洞穴"这种
	 * 类型变化才值得一次过场。
	 */
	public static boolean isSameKind(int first, int second) {
		return isCave(first) == isCave(second);
	}

	/**
	 * 从 {@code from} 换到 {@code to} 是不是在<b>往深处</b>走。
	 *
	 * <p>界面据此决定过场动画的方向：往下走是"推进"（地形放大），往上走是"退出"（缩小）。
	 * 地表视为在任何洞穴层之上，因此地表↔洞穴的方向判断天然正确。
	 */
	public static boolean isDeeper(int from, int to) {
		if (from == to) {
			return false;
		}
		return depthOf(to) < depthOf(from);
	}

	/** 层的深度：越小越深。地表在最上面。 */
	private static long depthOf(int layer) {
		return layer == SURFACE ? Long.MAX_VALUE : layer;
	}
}
