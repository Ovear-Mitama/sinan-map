package sinanmap.common.waypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sinanmap.common.coord.WorldPos;

class WaypointStoreTest {

	private static final String OVERWORLD = "minecraft:overworld";

	private static Waypoint waypoint(String name, String dimension, double x, double z) {
		return new Waypoint(UUID.randomUUID(), name, new WorldPos(dimension, x, 64.0, z),
				"家", 0xFFFF6B4A, "", true, 1234567890L);
	}

	@Test
	@DisplayName("往返一圈：字段一个不少")
	void roundTripKeepsEveryField() {
		Waypoint source = waypoint("矿洞入口", OVERWORLD, 128.5, -64.25).withShortName("矿");
		List<Waypoint> parsed = WaypointStore.parse(WaypointStore.serialize(List.of(source)));

		assertEquals(1, parsed.size());
		Waypoint restored = parsed.get(0);
		assertEquals(source.id(), restored.id());
		assertEquals(source.name(), restored.name());
		assertEquals(source.pos(), restored.pos());
		assertEquals(source.group(), restored.group());
		assertEquals(source.color(), restored.color(), "颜色是负数（不透明色）也必须原样回来");
		assertEquals(source.visible(), restored.visible());
		assertEquals(source.createdAt(), restored.createdAt());
		assertEquals(source.shortName(), restored.shortName(), "简称也要能往返");
	}

	@Test
	@DisplayName("名字里的分隔符、反斜杠、换行都不会把一行切坏")
	void namesWithSeparatorsSurvive() {
		String tricky = "a|b\\c\nd e";
		List<Waypoint> parsed = WaypointStore.parse(WaypointStore.serialize(List.of(waypoint(tricky, OVERWORLD, 0, 0))));

		assertEquals(1, parsed.size(), "转义没做对的话这一行会被切成两半，或者字段错位");
		assertEquals(tricky, parsed.get(0).name());
	}

	@Test
	@DisplayName("坏行只跳过，好行照常读出来")
	void badLinesAreSkipped() {
		String text = """
				sinanmap-waypoints/1
				这不是一行合法数据
				%s
				12345678-1234-1234-1234-123456789012|名字|minecraft:overworld|not-a-number|1|2|HOME|1|1|0
				""".formatted(WaypointStore.serialize(List.of(waypoint("好的", OVERWORLD, 1, 2)))
				.lines().skip(1).findFirst().orElse(""));

		List<Waypoint> parsed = WaypointStore.parse(text);

		assertEquals(1, parsed.size());
		assertEquals("好的", parsed.get(0).name());
	}

	@Test
	@DisplayName("版本行不认识时也尽力解析（尾部字段缺了给默认值）")
	void unknownVersionStillParses() {
		String text = "sinanmap-waypoints/99\n"
				+ "11111111-2222-3333-4444-555555555555|老数据|minecraft:overworld|10.0|20.0|30.0\n";

		List<Waypoint> parsed = WaypointStore.parse(text);

		assertEquals(1, parsed.size());
		assertEquals(Waypoint.DEFAULT_GROUP, parsed.get(0).group(), "缺字段时给默认分组");
		assertTrue(parsed.get(0).visible(), "缺字段时默认可见");
	}

	@Test
	@DisplayName("旧版本存档里的分类枚举名搬成对应的分组名")
	void legacyCategoryNamesBecomeGroups() {
		// 以前第 6 个字段存分类枚举名（HOME/OTHER…），现在存分组名；老存档升上来分组不能丢。
		String text = "sinanmap-waypoints/2\n"
				+ "11111111-2222-3333-4444-555555555555|老数据|minecraft:overworld|10.0|20.0|30.0"
				+ "|HOME|2527416|1|0\n"
				+ "22222222-3333-4444-5555-666666666666|更老|minecraft:overworld|1.0|2.0|3.0"
				+ "|OTHER|2527416|1|0\n";

		List<Waypoint> parsed = WaypointStore.parse(text);

		assertEquals(2, parsed.size(), "改了字段含义之后老文件仍然要读得出来");
		assertEquals("家", parsed.get(0).group());
		assertEquals(Waypoint.DEFAULT_GROUP, parsed.get(1).group());
	}

	@Test
	@DisplayName("空文本与 null 都不是错误")
	void emptyInputIsEmptyList() {
		assertTrue(WaypointStore.parse(null).isEmpty());
		assertTrue(WaypointStore.parse("").isEmpty());
		assertTrue(WaypointStore.parse("sinanmap-waypoints/1\n").isEmpty());
	}

	@Test
	@DisplayName("按维度取：同一个坐标在别的维度不是同一个地方")
	void filtersByDimension() {
		WaypointStore store = new WaypointStore();
		store.add(waypoint("主世界", OVERWORLD, 0, 0));
		store.add(waypoint("下界", "minecraft:the_nether", 0, 0));

		assertEquals(1, store.inDimension(OVERWORLD).size());
		assertEquals("主世界", store.inDimension(OVERWORLD).get(0).name());
	}

	@Test
	@DisplayName("最近的路径点：只在同维度里找，且受距离上限约束")
	void nearestRespectsDimensionAndRange() {
		WaypointStore store = new WaypointStore();
		store.add(waypoint("近的", OVERWORLD, 10, 0));
		store.add(waypoint("远的", OVERWORLD, 100, 0));
		store.add(waypoint("下界的", "minecraft:the_nether", 1, 0));

		assertEquals("近的", store.nearest(OVERWORLD, 0, 0, 50).name());
		assertNull(store.nearest(OVERWORLD, 0, 0, 5), "超出半径就不该返回");
		assertEquals("下界的", store.nearest("minecraft:the_nether", 0, 0, 50).name());
		assertNull(store.nearest("minecraft:the_end", 0, 0, 50));
	}

	@Test
	@DisplayName("增删会推进变更序号，界面据此判断要不要重排")
	void revisionChangesOnMutation() {
		WaypointStore store = new WaypointStore();
		long initial = store.revision();
		Waypoint first = waypoint("一", OVERWORLD, 0, 0);
		store.add(first);
		long afterAdd = store.revision();
		assertTrue(afterAdd > initial, "加一条要推进序号");

		assertFalse(store.remove(UUID.randomUUID()), "删不存在的 id 应当失败");
		assertEquals(afterAdd, store.revision(), "失败的删除不该推进序号");

		assertTrue(store.remove(first.id()));
		assertTrue(store.revision() > afterAdd);
		assertTrue(store.isEmpty());
	}

	@Test
	@DisplayName("同一 id 再 add 是替换而不是重复一条")
	void addWithSameIdReplaces() {
		WaypointStore store = new WaypointStore();
		Waypoint original = waypoint("旧名字", OVERWORLD, 0, 0);
		store.add(original);
		store.add(original.withName("新名字"));

		assertEquals(1, store.size());
		assertEquals("新名字", store.all().get(0).name());
	}

	@Test
	@DisplayName("死亡地点标记存得住；老文件里没有这个字段时当作普通路径点")
	void deathFlagSurvivesAndDefaultsToFalse() {
		Waypoint death = new Waypoint(UUID.randomUUID(), "上次死亡地点",
				new WorldPos(OVERWORLD, 1.0, 2.0, 3.0), Waypoint.DEFAULT_GROUP,
				Waypoint.DEATH_COLOR, "", true, 1234567890L, true, "");
		List<Waypoint> parsed = WaypointStore.parse(WaypointStore.serialize(List.of(death)));

		assertEquals(1, parsed.size());
		assertTrue(parsed.get(0).death(), "死亡标记要能往返");
		assertEquals(Waypoint.DEATH_COLOR, parsed.get(0).color());

		// 老文件（第 11 个字段还不存在）读出来必须还是普通路径点，其余字段一个都不能坏
		List<Waypoint> legacy = WaypointStore.parse("sinanmap-waypoints/1\n"
				+ "11111111-2222-3333-4444-555555555555|老数据|minecraft:overworld|10.0|20.0|30.0"
				+ "|家|2527416|1|123\n");

		assertEquals(1, legacy.size());
		assertFalse(legacy.get(0).death(), "缺字段时默认不是死亡地点");
		assertEquals("家", legacy.get(0).group());
		assertEquals(2527416, legacy.get(0).color());
		assertEquals(123L, legacy.get(0).createdAt());
	}
}
