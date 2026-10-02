package sinanmap.common.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorldKeysTest {

	@Test
	@DisplayName("非法字符被替换")
	void illegalCharacters() {
		assertEquals("mp_a_b_c_d", WorldKeys.multiplayer("a:b*c?d"));
	}

	@Test
	@DisplayName("空或只有标点的名字退化为 unknown，而不是空目录名")
	void emptyFallsBack() {
		assertEquals("mp_unknown", WorldKeys.multiplayer(""));
		assertEquals("mp_unknown", WorldKeys.multiplayer("   "));
		assertEquals("mp_unknown", WorldKeys.multiplayer(null));
	}

	@Test
	@DisplayName("超长名字被截断")
	void longNamesAreTruncated() {
		String key = WorldKeys.multiplayer("a".repeat(200));

		assertEquals(3 + 64, key.length());
	}

	@Test
	@DisplayName("不同服务器得到不同标识")
	void differentServersDiffer() {
		assertNotEquals(WorldKeys.multiplayer("play.example.com:25565"), WorldKeys.multiplayer("localhost:25565"));
	}

	@Test
	@DisplayName("多人标识以 mp_ 开头")
	void prefixesAreDistinct() {
		assertTrue(WorldKeys.multiplayer("host").startsWith("mp_"));
	}
}
