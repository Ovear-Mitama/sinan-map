package sinanmap.common.waypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sinanmap.common.coord.WorldPos;

class WaypointTest {

	private static WorldPos overworld(int x, int y, int z) {
		return WorldPos.of("minecraft:overworld", x, y, z);
	}

	@Test
	@DisplayName("用默认分组创建时用默认颜色且默认可见")
	void createUsesDefaults() {
		Waypoint waypoint = Waypoint.create("主基地", overworld(10, 64, -20));

		assertEquals(Waypoint.DEFAULT_GROUP, waypoint.group());
		assertEquals(Waypoint.DEFAULT_COLOR, waypoint.color());
		assertTrue(waypoint.visible());
		assertEquals("", waypoint.iconId());
		assertTrue(waypoint.createdAt() > 0L);
	}

	@Test
	@DisplayName("分组名留空时落到默认分组")
	void blankGroupFallsBackToDefault() {
		Waypoint waypoint = Waypoint.create("主基地", overworld(10, 64, -20), "  ", Waypoint.DEFAULT_COLOR);

		assertEquals(Waypoint.DEFAULT_GROUP, waypoint.group());
	}

	@Test
	@DisplayName("with* 方法派生新实例且保留唯一标识与创建时间")
	void withMethodsKeepIdentity() {
		Waypoint origin = Waypoint.create("矿洞", overworld(5, 30, 5), "挖矿", Waypoint.DEFAULT_COLOR);

		Waypoint renamed = origin.withName("深层矿洞").withVisible(false);

		assertEquals(origin.id(), renamed.id());
		assertEquals(origin.createdAt(), renamed.createdAt());
		assertEquals("深层矿洞", renamed.name());
		assertTrue(!renamed.visible());
		assertNotEquals(origin, renamed);
		assertEquals("矿洞", origin.name(), "原实例不应被修改");
		assertEquals("挖矿", origin.group(), "withGroup 也不该改到原实例");
		assertEquals("别的组", origin.withGroup("别的组").group());
	}

	@Test
	@DisplayName("不同路径点拥有不同的唯一标识")
	void idsAreUnique() {
		Waypoint first = Waypoint.create("A", overworld(0, 0, 0));
		Waypoint second = Waypoint.create("A", overworld(0, 0, 0));

		assertNotEquals(first.id(), second.id());
	}

	@Test
	@DisplayName("空名称与超长名称被拒绝")
	void nameIsValidated() {
		WorldPos pos = overworld(0, 0, 0);

		assertThrows(IllegalArgumentException.class, () -> Waypoint.create("  ", pos));
		assertThrows(IllegalArgumentException.class, () -> Waypoint.create(null, pos));

		String tooLong = "x".repeat(Waypoint.MAX_NAME_LENGTH + 1);
		assertThrows(IllegalArgumentException.class, () -> Waypoint.create(tooLong, pos));
	}

	@Test
	@DisplayName("坐标文本按方块取整显示")
	void describeRoundsToBlock() {
		WorldPos pos = WorldPos.of("minecraft:overworld", 12, 64, -8);

		assertEquals("X=12 Y=64 Z=-8", pos.describe());
		assertEquals(0, pos.chunkX());
		assertEquals(-1, pos.chunkZ());
	}

	@Test
	@DisplayName("维度 ID 不能为空")
	void dimensionIsRequired() {
		assertThrows(IllegalArgumentException.class, () -> WorldPos.of("", 0, 0, 0));
	}
}
