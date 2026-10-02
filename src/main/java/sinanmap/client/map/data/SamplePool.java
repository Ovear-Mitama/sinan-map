package sinanmap.client.map.data;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 采样用的固定后台线程池：把"取地形数据"这类<b>纯计算</b>搬到渲染线程之外。
 *
 * <p>它<b>不负责</b>着色：着色要问客户端关卡（{@code ClientLevel}），且 {@code ChunkRecord#toColors}
 * 用的是 static 暂存数组，只能在渲染线程上做。这里只跑"不碰游戏状态"的那一半。
 *
 * <p><b>为什么固定少量几条，而不是 {@code availableProcessors()}：</b>机器同时在跑游戏本身
 * （渲染、区块生成、光照），按核数铺开线程会跟游戏抢核、帧时间反而更不稳。默认三条，可由设置调整，
 * 但不要去问核数。
 *
 * <p><b>为什么是守护 + 最低优先级：</b>它们做的是"有空就补"的活，进程退出不该被卡住，也不该抢 CPU。
 *
 * <p><b>汇合是非阻塞的 drain，而不是 {@code get(超时)} 或自旋轮询：</b>调用方是渲染线程。
 * {@code get} 会把这一帧挂住；早先"轮询 {@code isDone} 到全部完成或到点"<b>同样把渲染线程原地占住</b>
 * ——自旋本身就是阻塞，最坏能烧掉一整个预算。现在每帧只<b>取走已经完成的</b>
 * （{@link #drainCompleted}），没完成的原样留在池里、带着提交时的 {@code generation}，下一帧再看，
 * <b>一次都不为它停留</b>；"晚一帧拿到结果"完全可接受。
 *
 * <p><b>没做完的既不丢弃、也不取消</b>（换世界除外）：让后台线程白跑没有意义，下一帧收即可。
 *
 * <p><b>换世界防护：</b>任务带着提交时的 {@code prefetchGeneration}；{@link #drainCompleted} 发现与
 * 当前计数不一致，说明属于上一个存档，任务取消、结果丢弃、也不回调。
 *
 * <p><b>池里不能无限堆积：</b>正常用不到上限——提交量被 {@code prefetchQueued} 卡着、每帧又 drain
 * 一次。万一某条后台任务卡住不返回，{@link #MAX_PENDING} 兜底：满了 {@link #submit} 返回
 * {@code false}，调用方放回、下一帧再排。
 *
 * <p>{@link #submit} / {@link #drainCompleted} / {@link #hasPending} 都只在渲染线程上调用，
 * 所以 {@link #pending} 不必加锁。线程是守护线程，进程退出即结束，无需显式关闭。
 */
final class SamplePool {

	/** 池里允许同时挂着的未完成任务数上限，见类注释；{@value} 是兜底值，正常路径到不了。 */
	private static final int MAX_PENDING = 256;

	/** 后台工作池。用 {@link ThreadPoolExecutor} 是为了让设置改线程数时能当场调（见 {@link #setThreads}）。 */
	private final ThreadPoolExecutor workers;

	SamplePool(int threads) {
		this.workers = (ThreadPoolExecutor) Executors.newFixedThreadPool(threads, task -> {
			Thread thread = new Thread(task, "sinanmap-sample");
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY);
			return thread;
		});
	}

	/** 改后台线程数（设置里改了洞穴扫描线程时调用）；值没变时立刻返回，可以每帧调。 */
	void setThreads(int threads) {
		if (workers.getMaximumPoolSize() == threads) {
			return;
		}
		// 先调上限再调核心：否则中途会出现"核心 > 上限"的非法状态
		if (threads > workers.getMaximumPoolSize()) {
			workers.setMaximumPoolSize(threads);
			workers.setCorePoolSize(threads);
		} else {
			workers.setCorePoolSize(threads);
			workers.setMaximumPoolSize(threads);
		}
	}

	/** 已经提交出去、还没被取走的任务。只由渲染线程读写。 */
	private final List<Pending> pending = new ArrayList<>();

	/** 一个待取走的任务：{@link Future}、提交时的换世界计数，以及完成时要在渲染线程上跑的回调（收尾交给调用方，池本身不碰游戏状态）。 */
	private record Pending(Future<?> future, int generation, Runnable onCompleted) {
	}

	/**
	 * 提交一个后台任务。
	 *
	 * @param generation  提交时的换世界计数（见 {@code ClientChunkColorProvider#prefetchGeneration}）；
	 *                    {@link #drainCompleted} 时不一致就丢弃，防止把上一个存档的算成这个存档的
	 * @param task        要跑的计算；必须是<b>不碰游戏状态</b>的纯计算（碰关卡/渲染线程缓存的一律不能放进来）
	 * @param onCompleted 任务完成、且没换过世界时，在渲染线程上执行（着色/收记录/落盘这类只能在这里做的收尾）
	 * @return 是否排入成功；池已满（{@link #MAX_PENDING}）时为 {@code false}，调用方应把这批放回、下一帧再排
	 */
	boolean submit(int generation, Runnable task, Runnable onCompleted) {
		if (pending.size() >= MAX_PENDING) {
			return false;
		}
		pending.add(new Pending(workers.submit(task), generation, onCompleted));
		return true;
	}

	/** 池里是否还有没取走的任务（调用方据此决定"没事也定期来 drain 一下"）。 */
	boolean hasPending() {
		return !pending.isEmpty();
	}

	/**
	 * 非阻塞地取走本帧<b>已经完成</b>的任务：完成的在渲染线程上跑它的 {@code onCompleted}，
	 * 换过世界的直接取消丢弃，<b>没完成的原样留在池里</b>，等下一帧再来。
	 *
	 * <p>绝不阻塞、绝不自旋、不为任何任务等待——只做一次遍历。
	 *
	 * @param currentGeneration 当前的换世界计数；与某个任务的计数不一致时，该任务作废
	 * @return 本次取走（回调过或丢弃过）的任务数
	 */
	int drainCompleted(int currentGeneration) {
		int drained = 0;
		Iterator<Pending> iterator = pending.iterator();
		while (iterator.hasNext()) {
			Pending item = iterator.next();
			if (item.generation() != currentGeneration) {
				// 换过世界：这一份属于上一个存档，取消并丢弃。
				// 不回调——回调会把它当成当前存档的写进缓存（串档）。
				item.future().cancel(false);
				iterator.remove();
				drained++;
				continue;
			}
			if (!item.future().isDone()) {
				// 还没算完：留在池里，下一帧再看
				continue;
			}
			// 完成：在渲染线程上收尾。取消用 false 的语义在这里用不上——它已经完成了。
			// future.isDone() 为真保证了任务对"结果槽"的写入对这里可见（FutureTask 的状态是 volatile）
			item.onCompleted().run();
			iterator.remove();
			drained++;
		}
		return drained;
	}
}
