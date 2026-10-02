package sinanmap.client.map;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

import sinanmap.common.SinanConstants;

/**
 * 区块叠加层的共用规则：区块网格与史莱姆区块的"画不画"阈值、以及史莱姆区块的判定。
 *
 * <p><b>为什么单独放一处</b>：世界地图与小地图都要画这两层，而"史莱姆区块怎么算"是原版那一套
 * 带魔数的哈希——两处各写一份迟早会有一处写错，而写错的表现只是"标的位置不对"，很难看出来。
 * 绘制本身（挂在各自的变换里）留在各自的界面里，这里只放两边必须一致的判定。
 */
public final class ChunkOverlays {

	/** 一区块的边长（方块）。 */
	public static final int CHUNK_BLOCKS = SinanConstants.CHUNK_SIZE;

	/**
	 * 网格线画出来的最小间隔（像素）：一区块的屏幕宽度小于它就整块不画。
	 *
	 * <p>缩得很远时一个区块可能只有一两像素，线比格子还密——那不是"参考线"，是把地图糊成一块灰。
	 */
	public static final int MIN_GRID_CHUNK_PIXELS = 6;

	/**
	 * 网格线画出来的最大间隔（像素）：放得太大时线会跟着变粗，那就不画了。
	 *
	 * <p>线宽只能用<b>整方块</b>表示（绘制矩阵里 1 单位 = 1 方块），所以放得越大线越粗。
	 * 这里限到两像素：再粗就不是"参考线"而是把地形划开了。
	 */
	public static final int MAX_GRID_CHUNK_PIXELS = 32;

	/**
	 * 史莱姆区块画出来的最小间隔（像素）：比网格更严一些，因为它还要逐区块查一次表。
	 *
	 * <p>再小就只剩一片糊绿，而这个值下最坏每帧也就几万次查表（见 {@link #isSlimeChunk} 的缓存）。
	 */
	public static final int MIN_SLIME_CHUNK_PIXELS = 3;

	/** 史莱姆区块的底色：半透明绿，压在地形上看得出、又不挡地形。 */
	public static final int SLIME_COLOR = 0x5533FF55;

	/** 取不到种子时用的哨兵（多人服不把种子给客户端）。 */
	private static final long NO_SEED = Long.MIN_VALUE;

	/** 判定结果缓存：缩得远时每帧要问几千次，缓存是"几千次哈希"变成"几千次查表"的关键。 */
	private static final Map<Long, Boolean> CACHE = new HashMap<>();

	private static long cacheSeed = NO_SEED;

	private ChunkOverlays() {
	}

	/**
	 * 这一局能不能标史莱姆区块。两个前提：
	 *
	 * <p><b>① 人在主世界。</b>这套"种子 + 区块坐标"的哈希只决定<b>主世界</b>的史莱姆区块，下界与末地
	 * 根本没有这回事。不加这个判断的话，末地会被照着同一套哈希标出一堆"史莱姆区块"——那是假信息。
	 *
	 * <p><b>② 拿得到种子。</b>史莱姆区块完全由世界种子决定，而客户端默认拿不到种子——只有单人
	 * （集成服务端）才有。多人服上取不到就整层不画，而不是按错误的种子瞎标。
	 */
	public static boolean canMarkSlimeChunks() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || minecraft.level.dimension() != Level.OVERWORLD) {
			return false;
		}
		return seed() != NO_SEED;
	}

	/** 这一格是不是史莱姆区块（原版那套"种子 + 区块坐标"的哈希，保证与游戏内一致）。 */
	public static boolean isSlimeChunk(int chunkX, int chunkZ) {
		long currentSeed = seed();
		if (currentSeed == NO_SEED) {
			return false;
		}
		if (currentSeed != cacheSeed) {
			cacheSeed = currentSeed;
			CACHE.clear();
		}
		long key = ((long) chunkX << 32) | chunkZ & 0xFFFFFFFFL;
		Boolean cached = CACHE.get(key);
		if (cached != null) {
			return cached;
		}
		// 走到没缓存过的地方时整块清掉重建，不给它无限长
		if (CACHE.size() >= 16384) {
			CACHE.clear();
		}
		Random random = new Random(currentSeed
				+ (long) (chunkX * chunkX * 0x4c1906)
				+ (long) (chunkX * 0x5ac0db)
				+ (long) (chunkZ * chunkZ) * 0x4307a7L
				+ (long) (chunkZ * 0x5f24f) ^ 0x3ad8025fL);
		boolean result = random.nextInt(10) == 0;
		CACHE.put(key, result);
		return result;
	}

	/** 区块坐标 → 该区块的最小方块坐标。 */
	public static int chunkOrigin(int chunk) {
		return chunk * CHUNK_BLOCKS;
	}

	/** 方块坐标 → 所在区块坐标。 */
	public static int chunkAt(double worldCoordinate) {
		return Math.floorDiv((int) Math.floor(worldCoordinate), CHUNK_BLOCKS);
	}

	/** 线宽（方块）：1 个 GUI 像素对应多少方块，取整；至少 1 块。 */
	public static int lineBlocks(double blocksPerGui) {
		return Math.max(1, (int) Math.round(blocksPerGui));
	}

	private static long seed() {
		MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
		return server == null ? NO_SEED : server.overworld().getSeed();
	}
}
