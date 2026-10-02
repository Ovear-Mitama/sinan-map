package sinanmap.common.map;

/**
 * 平滑缩放。
 *
 * <p>滚轮每滚一格直接跳变会"滚一下卡一下"，大范围重采样本身也耗时；这里让缩放值按时间常数
 * 指数逼近目标值，配合"动画期间降采样"，滚轮可以连续快滚而不掉帧。
 *
 * <p>纯逻辑，与渲染无关，便于单元测试。
 */
public final class ZoomAnimator {

	/**
	 * 收敛时间常数（毫秒）：越小越快，约 3 倍时间常数后基本到位。
	 *
	 * <p>直接决定"缩放跟手不跟手"。取得大（原来是 80）时每滚一格都要几百毫秒才落定、
	 * 指数曲线尾部长，手感发飘。
	 */
	private static final double TIME_CONSTANT_MILLIS = 45.0;

	/**
	 * 过场用的"慢速"时间常数（毫秒）。
	 *
	 * <p>开场"由放大到缩小"这类整屏过场用 {@link #TIME_CONSTANT_MILLIS} 会一眨眼走完、像"啪"地冒出来；
	 * 过场期间改用这个值，动画才有展开的感觉。停下来（{@link #setSlow} 关掉）之后手感的缩放仍是快那个。
	 *
	 * <p>取 {@value}：配合 {@link #SNAP_TOLERANCE} 约 1.2 秒到位（前 0.5 秒走完九成），
	 * 比原来那个 340 快近一倍。
	 */
	private static final double SLOW_TIME_CONSTANT_MILLIS = 190.0;

	/**
	 * 相对容差，小于该比例即认为已到位并吸附到目标值。
	 *
	 * <p>同时决定两件事：<b>尾巴有多长</b>（指数逼近永远不会真正到位，容差越小尾巴越长），
	 * 以及<b>最后那一下跳多大</b>——吸附是一次真正的跳变，幅度就是这个比例。
	 *
	 * <p>原来取 0.8%（约 4.5 倍时间常数收尾），跳变是缩放值的 0.8%：屏幕边缘离中心越远跳得越明显
	 * （500 像素处约 4 像素），看着像"动画结束时突然归位"。取 {@value} 后跳变只有 0.15%
	 * （同处约 0.7 像素，看不出），代价是尾巴长一点：快速那个时间常数下约 0.3 秒收尾，仍在"跟手"范围。
	 */
	private static final double SNAP_TOLERANCE = 0.0015;

	/**
	 * 单帧最多推进的毫秒数。
	 *
	 * <p>重合成整张全屏贴图会有几十甚至几百毫秒的长帧。若不封顶，那帧的逼近系数
	 * {@code 1 - e^(-dt/τ)} 会逼近 1，整个动画一步走完——表现为"突然放大/缩小"，
	 * 紧接着又是下一次长帧，于是越卡越跳。封顶后掉帧只让动画慢一点，不会跳。
	 */
	public static final double MAX_STEP_MILLIS = 40.0;

	private double current;
	private double target;
	private double timeConstantMillis = TIME_CONSTANT_MILLIS;

	public ZoomAnimator(double initial) {
		this.current = initial;
		this.target = initial;
	}

	/**
	 * 切换"过场模式"：开着的时候动画按 {@link #SLOW_TIME_CONSTANT_MILLIS} 走。
	 *
	 * <p>界面在开场过场开始时打开、过场结束（{@link #update} 返回 false）时关掉。
	 */
	public void setSlow(boolean slow) {
		this.timeConstantMillis = slow ? SLOW_TIME_CONSTANT_MILLIS : TIME_CONSTANT_MILLIS;
	}

	/** 当前值。 */
	public double current() {
		return current;
	}

	/** 目标值。 */
	public double target() {
		return target;
	}

	/** 设定新的目标值，动画会在随后的 update 中逐步逼近。 */
	public void setTarget(double value) {
		this.target = value;
	}

	/** 立即到位（例如首次打开界面，不需要动画）。 */
	public void snapTo(double value) {
		this.current = value;
		this.target = value;
	}

	/**
	 * 只把当前值跳到某处，<b>目标值保持不变</b>。
	 *
	 * <p>用于"先拉远再推回"这类过场：当前值先跳到拉远的位置，随后动画自然把它推回原目标。
	 * 若错误地用了 {@link #snapTo}，目标值也会被改掉，动画就没有终点可以走了。
	 */
	public void snapCurrent(double value) {
		this.current = value;
	}

	/**
	 * 推进动画。
	 *
	 * @param deltaMillis 距上一帧的毫秒数
	 * @return 是否仍在变化（调用方据此决定是否需要重新合成）
	 */
	public boolean update(double deltaMillis) {
		double difference = target - current;
		double tolerance = Math.max(1.0e-6, Math.abs(target) * SNAP_TOLERANCE);
		if (Math.abs(difference) <= tolerance) {
			current = target;
			return false;
		}
		double factor = 1.0 - Math.exp(-Math.clamp(deltaMillis, 0.0, MAX_STEP_MILLIS) / timeConstantMillis);
		current += difference * factor;
		return true;
	}

	/** 是否正在动画中。 */
	public boolean isAnimating() {
		return current != target;
	}
}
