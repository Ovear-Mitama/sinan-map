package sinanmap.common.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LayerSelectorTest {

	@Test
	@DisplayName("地表模式永远返回地表哨兵")
	void surfaceModeAlwaysSurface() {
		assertEquals(LayerSelector.SURFACE, LayerSelector.resolve(LayerMode.SURFACE, 20.0, 70, 55));
		assertEquals(LayerSelector.SURFACE, LayerSelector.resolve(LayerMode.SURFACE, -30.0, 70, 55));
	}

	/** 洞穴层高：只看玩家自己的高度（玩家 + CAVE_LOOK_UP）。 */
	private static int caveTop(int playerBlockY) {
		return LayerSelector.caveStart(playerBlockY);
	}

	@Test
	@DisplayName("洞穴候选层逐格跟随玩家：下潜一格候选就降一格（去抖发生在更上层）")
	void caveCandidateFollowsPlayerEveryBlock() {
		// resolve 给的是"本该显示的候选高度"：切面按 [层高 ± 容差] 取样，每列都记着结果在哪些高度上
		// 仍成立，所以候选是精确高度、不量化。真正显示时再过一层保持时间去抖（见 CaveDisplayFilter）。
		int top = LayerSelector.resolve(LayerMode.CAVE, 95.0, 70, caveTop(95));
		int oneLower = LayerSelector.resolve(LayerMode.CAVE, 94.0, 70, caveTop(94));
		assertEquals(caveTop(95), top, "候选层就是精确的层高（玩家 + CAVE_LOOK_UP）");
		assertEquals(top - 1, oneLower, "往下走一格，候选就跟着降一格");

		// 归档层仍在 16 格格点上（比显示层粗），不会因为显示层变细而多出归档键
		assertEquals(0, LayerSelector.cacheLayer(top) % LayerSelector.CAVE_STEP);
		// 落盘 / 预读层固定 STORAGE_STEP 格一档，与显示层是否逐格无关：同一档共用一个目录/一份缓存
		int base = LayerSelector.storageLayer(top);
		assertEquals(0, base % LayerSelector.STORAGE_STEP, "落盘档一定落在步长的整数倍上");
		assertEquals(base, LayerSelector.storageLayer(base + LayerSelector.STORAGE_STEP - 1),
				"同一档内的显示层共用一个落盘档");
		assertTrue(base != LayerSelector.storageLayer(base + LayerSelector.STORAGE_STEP),
				"跨过一整档才换落盘目录");
	}

	@Test
	@DisplayName("层高只看玩家高度：沿矿道平着走，层高一点都不动")
	void caveTopIgnoresCeilingWhileWalkingFlat() {
		// 层高只由玩家自己的 Y 决定：以前按头顶第一块实心方块定，矿道顶一鼓一凹层高就跟着抖，
		// 层一变整套瓦片换键、界面重切。
		for (int playerY = 41; playerY <= 44; playerY++) {
			assertEquals(playerY + LayerSelector.CAVE_LOOK_UP, LayerSelector.caveStart(playerY),
					"层高只跟玩家高度有关，与天花板无关");
		}
		// 同为 y=42：不管头顶是 2 格还是 30 格，层高都是同一个值
		assertEquals(LayerSelector.caveStart(42), LayerSelector.caveStart(42));
	}

	@Test
	@DisplayName("取样点比玩家高 CAVE_LOOK_UP 格（取头顶那一带）")
	void caveSampleSitsAboveThePlayer() {
		assertEquals(51 + LayerSelector.CAVE_LOOK_UP, caveTop(51));
		assertEquals(caveTop(51),
				LayerSelector.resolve(LayerMode.CAVE, 51.0, 70, caveTop(51)),
				"候选层就是精确层高（不量化）");
		assertTrue(LayerSelector.CAVE_LOOK_UP > 0, "取样点应高于玩家，而不是脚下的位置");
	}

	@Test
	@DisplayName("自动模式：贴地表时画地表，低过地表 3 格才切洞穴")
	void autoModeSwitchesOnlyUnderground() {
		int surface = 70;

		assertEquals(LayerSelector.SURFACE, LayerSelector.resolve(LayerMode.AUTO, 70.0, surface, caveTop(70)));
		assertEquals(LayerSelector.SURFACE, LayerSelector.resolve(LayerMode.AUTO, 67.5, surface, caveTop(67)));

		int underground = LayerSelector.resolve(LayerMode.AUTO, 66.5, surface, caveTop(66));
		assertTrue(LayerSelector.isCave(underground), "低过地表 3 格以上应进入洞穴层");
		assertEquals(0, LayerSelector.cacheLayer(underground) % LayerSelector.CAVE_STEP,
				"归档层落在量化格点上（显示层是精确高度，不参与这条约束）");
	}

	@Test
	@DisplayName("归档层把显示层拉回 16 格格点")
	void cacheLayerIsIdentityForQuantizedLayers() {
		assertEquals(48, LayerSelector.cacheLayer(48));
		assertEquals(48, LayerSelector.cacheLayer(51), "非格点值会被拉回格点");
		assertEquals(32, LayerSelector.cacheLayer(47));
		assertEquals(-16, LayerSelector.cacheLayer(-1));

		assertEquals(LayerSelector.SURFACE, LayerSelector.cacheLayer(LayerSelector.SURFACE));
	}

	@Test
	@DisplayName("量化向下对齐，负坐标也正确")
	void quantizeRoundsDown() {
		assertEquals(48, LayerSelector.quantize(48));
		assertEquals(48, LayerSelector.quantize(51));
		assertEquals(32, LayerSelector.quantize(47));
		assertEquals(-16, LayerSelector.quantize(-1));
		assertEquals(-16, LayerSelector.quantize(-5));
	}

	@Test
	@DisplayName("地表哨兵不会被当成洞穴层")
	void surfaceIsNotCave() {
		assertFalse(LayerSelector.isCave(LayerSelector.SURFACE));
		assertTrue(LayerSelector.isCave(0));
	}

	@Test
	@DisplayName("同类判定：洞穴内换层算同类，地表与洞穴不算")
	void sameKindSeparatesSurfaceFromCave() {
		assertTrue(LayerSelector.isSameKind(48, 44), "洞穴内换层是同类，不该触发过场动画");
		assertTrue(LayerSelector.isSameKind(LayerSelector.SURFACE, LayerSelector.SURFACE));

		assertFalse(LayerSelector.isSameKind(LayerSelector.SURFACE, 48), "进洞穴该有过场");
		assertFalse(LayerSelector.isSameKind(48, LayerSelector.SURFACE), "出洞穴该有过场");
	}

	@Test
	@DisplayName("下潜判定：地表→洞穴、洞穴更深都算往下")
	void deeperDetectsDescending() {
		assertTrue(LayerSelector.isDeeper(LayerSelector.SURFACE, 48), "从地表进入洞穴是往下");
		assertTrue(LayerSelector.isDeeper(48, 44), "洞穴里继续下潜是往下");

		assertFalse(LayerSelector.isDeeper(48, LayerSelector.SURFACE), "回到地表是往上");
		assertFalse(LayerSelector.isDeeper(44, 48), "洞穴里上升是往上");
		assertFalse(LayerSelector.isDeeper(48, 48), "同一层没有方向");
	}

	@Test
	@DisplayName("越深的地表哨兵在任何洞穴层之上")
	void surfaceIsAboveEveryCave() {
		// 这条保证"地表→洞穴"永远被判定为往下，不管洞穴层高度是多少
		assertTrue(LayerSelector.isDeeper(LayerSelector.SURFACE, Integer.MAX_VALUE - 1));
		assertTrue(LayerSelector.isDeeper(LayerSelector.SURFACE, Integer.MIN_VALUE + 1));
	}

	// —— 洞穴显示层的"差 1 格判定 + 保持时间"去抖（见 LayerSelector.CaveDisplayFilter）——
	// 这些用例把"当前时刻"当参数传进去，不依赖真实时间，也不依赖 Minecraft。

	@Test
	@DisplayName("去抖：原地 ±1 格来回拉锯永远不换层（方向每帧翻转，攒不满保持时间）")
	void caveFilterIgnoresOneBlockJitter() {
		var filter = new LayerSelector.CaveDisplayFilter();
		int base = 50;
		long now = 1_000_000_000L;
		// 交替 +1 / -1，每 50ms 一次、持续 10 秒。差 1 算变化，但方向每帧翻转、计时不断重来，
		// 抖得再久也攒不出保持时间。
		for (int i = 0; i < 200; i++) {
			int jitter = (i % 2 == 0) ? base + 1 : base - 1;
			assertEquals(base, filter.settle(base, jitter, now), "±1 拉锯不该换层");
			now += 50_000_000L;
		}
	}

	@Test
	@DisplayName("去抖：差 1 格也要保持满 DISPLAY_HOLD_NANOS 才换（原地不动时终于跟上）")
	void caveFilterFollowsOneBlockAfterHold() {
		var filter = new LayerSelector.CaveDisplayFilter();
		int base = 50;
		int lower = base - 1; // 只差 1 格，也要算一次真正的变化
		long hold = LayerSelector.DISPLAY_HOLD_NANOS;
		long t0 = 1_000_000_000L;

		assertEquals(base, filter.settle(base, lower, t0), "刚变一格先不换");
		assertEquals(base, filter.settle(base, lower, t0 + hold / 2), "保持没够不换");
		assertEquals(base, filter.settle(base, lower, t0 + hold - 1), "差 1 纳秒也不换");
		assertEquals(lower, filter.settle(base, lower, t0 + hold), "保持够了才换到候选值");
	}

	@Test
	@DisplayName("去抖：短于保持时间的 ±1 起伏（如一次很短的跳跃）不换层")
	void caveFilterIgnoresBriefHop() {
		var filter = new LayerSelector.CaveDisplayFilter();
		int base = 50;
		long hold = LayerSelector.DISPLAY_HOLD_NANOS;
		long now = 1_000_000_000L;
		// 抬高一格后立刻落回：起伏时间不足保持时间，攒不满 → 不换层
		assertEquals(base, filter.settle(base, base + 1, now), "抬高一格，先不换");
		now += hold / 2;
		assertEquals(base, filter.settle(base, base + 1, now), "还没攒满保持时间");
		now += hold / 2;
		assertEquals(base, filter.settle(base, base, now), "落回后仍是同一层");
	}

	@Test
	@DisplayName("去抖：变化后又缩回来（来回）不换层，且计时重来")
	void caveFilterIgnoresRoundTrip() {
		var filter = new LayerSelector.CaveDisplayFilter();
		int base = 50;
		int lower = base - 1;
		long hold = LayerSelector.DISPLAY_HOLD_NANOS;
		long t0 = 0L;

		assertEquals(base, filter.settle(base, lower, t0), "开始变化，先不换");
		// 还没攒够就缩回来：退回当前值，计时作废
		assertEquals(base, filter.settle(base, base, t0 + hold / 2), "缩回来后不换");
		// 再变一次：必须从这一刻重新攒满，不能借刚才那段计时
		assertEquals(base, filter.settle(base, lower, t0 + hold), "重新变化，重新计时");
		assertEquals(base, filter.settle(base, lower, t0 + hold + hold - 1), "差 1 纳秒仍不换");
		assertEquals(lower, filter.settle(base, lower, t0 + hold + hold), "重新攒满才换");
	}

	@Test
	@DisplayName("去抖：持续下潜 10 格会跟上（不会冻在起点）")
	void caveFilterKeepsUpWithSustainedDescent() {
		var filter = new LayerSelector.CaveDisplayFilter();
		int current = 0;
		int candidate = 0;
		long now = 0L;
		// 每 100ms 下潜一格，共 10 格（1 秒）；每格推进两帧（50ms 一帧）
		for (int step = 1; step <= 10; step++) {
			candidate = -step;
			for (int frame = 0; frame < 2; frame++) {
				now += 50_000_000L;
				current = filter.settle(current, candidate, now);
			}
		}
		// 停在最深处再推 1 秒，确保保持时间跑满
		for (int frame = 0; frame < 20; frame++) {
			now += 50_000_000L;
			current = filter.settle(current, candidate, now);
		}
		assertEquals(candidate, current, "持续下潜 10 格后要跟到 -10");
	}

	@Test
	@DisplayName("去抖：换世界/换维度（reset）后旧计时作废")
	void caveFilterResetClearsPending() {
		var filter = new LayerSelector.CaveDisplayFilter();
		int base = 50;
		int lower = base - LayerSelector.DISPLAY_DEADBAND;
		long hold = LayerSelector.DISPLAY_HOLD_NANOS;
		long t0 = 1_000_000_000L;

		assertEquals(base, filter.settle(base, lower, t0), "跨出死区，开始计时");
		assertEquals(base, filter.settle(base, lower, t0 + hold - 1), "马上就到时间了");
		// 换世界 / 换维度：状态清零，不能借上一个世界的计时立刻换层
		filter.reset();
		assertEquals(base, filter.settle(base, lower, t0 + hold), "清零后旧计时不再生效");
		assertEquals(base, filter.settle(base, lower, t0 + hold + hold - 1));
		assertEquals(lower, filter.settle(base, lower, t0 + hold + hold), "清零后要重新攒满");
	}
}
