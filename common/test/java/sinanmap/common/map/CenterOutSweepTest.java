package sinanmap.common.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CenterOutSweepTest {

	/** 把整个遍历收集成坐标列表。 */
	private static List<int[]> collect(int columns, int rows) {
		CenterOutSweep sweep = new CenterOutSweep(columns, rows);
		List<int[]> visited = new ArrayList<>();
		while (sweep.hasNext()) {
			if (sweep.inRange()) {
				visited.add(new int[] {sweep.x(), sweep.y()});
			}
			sweep.advance();
		}
		return visited;
	}

	@Test
	@DisplayName("从中心出发，第一格就是中心格子")
	void startsAtCenter() {
		List<int[]> visited = collect(5, 5);

		assertEquals(2, visited.getFirst()[0]);
		assertEquals(2, visited.getFirst()[1]);
	}

	@Test
	@DisplayName("每个格子恰好访问一次")
	void visitsEveryCellExactlyOnce() {
		List<int[]> visited = collect(7, 4);
		Set<Long> unique = new HashSet<>();
		for (int[] cell : visited) {
			assertTrue(unique.add((long) cell[0] << 32 | cell[1]), "格子 " + cell[0] + "," + cell[1] + " 被访问了两次");
		}

		assertEquals(7 * 4, visited.size());
	}

	@Test
	@DisplayName("距离中心的圈数单调不减：先近后远")
	void expandsOutward() {
		int previous = -1;
		for (int[] cell : collect(9, 6)) {
			int distance = Math.max(Math.abs(cell[0] - 4), Math.abs(cell[1] - 3));
			assertTrue(distance >= previous, "距离不该回退：" + distance + " < " + previous);
			previous = distance;
		}
	}

	@Test
	@DisplayName("单格栅格只访问一次")
	void singleCell() {
		CenterOutSweep sweep = new CenterOutSweep(1, 1);

		assertTrue(sweep.hasNext());
		assertTrue(sweep.inRange());
		assertEquals(0, sweep.x());
		assertEquals(0, sweep.y());

		sweep.advance();
		assertFalse(sweep.hasNext());
	}

	@Test
	@DisplayName("长条栅格也能覆盖全：中心那一行先走")
	void stripGrid() {
		List<int[]> visited = collect(1, 6);

		assertEquals(6, visited.size());
		assertEquals(3, visited.getFirst()[1], "中心格是 6 的一半（向下取整）");
		assertEquals(0, visited.get(0)[0]);
	}

	@Test
	@DisplayName("reset 后从头再来")
	void resetRestarts() {
		CenterOutSweep sweep = new CenterOutSweep(5, 5);
		sweep.advance();
		sweep.advance();

		sweep.reset();

		assertEquals(2, sweep.x());
		assertEquals(2, sweep.y());
	}

	@Test
	@DisplayName("格子数非法直接抛异常")
	void invalidGridThrows() {
		assertThrows(IllegalArgumentException.class, () -> new CenterOutSweep(0, 4));
		assertThrows(IllegalArgumentException.class, () -> new CenterOutSweep(4, -1));
	}
}
