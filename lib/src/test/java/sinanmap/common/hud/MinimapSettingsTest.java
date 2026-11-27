package sinanmap.common.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sinanmap.common.map.LayerMode;

class MinimapSettingsTest {

	@Test
	@DisplayName("默认值：矩形、靠右上、自动缩放、锁定正北、自动切层")
	void defaults() {
		MinimapSettings settings = new MinimapSettings();

		assertTrue(settings.enabled());
		assertEquals(96, settings.width());
		assertEquals(64, settings.height());
		assertTrue(settings.width() > settings.height(), "默认是横向矩形而不是正方形");
		assertTrue(settings.autoZoom(), "默认自动缩放，一个方块一个设备像素");
		assertFalse(settings.rotateWithPlayer(), "默认锁定正北，只有玩家标记转动");
		assertEquals(LayerMode.AUTO, settings.layerMode(), "默认自动切换地表/洞穴层");
		assertEquals(ScreenAnchor.TOP_RIGHT, settings.anchor());
		assertTrue(settings.entityRadarEnabled(), "默认开启生物雷达");
		assertEquals(64.0, settings.entityRadarRadius());
		assertFalse(settings.entityRadarHostileOnly(), "默认敌对与被动都显示");
	}

	@Test
	@DisplayName("宽高超界会被钳位")
	void sizesAreClamped() {
		MinimapSettings settings = new MinimapSettings();

		settings.setWidth(1);
		settings.setHeight(100000);
		assertEquals(MinimapSettings.MIN_SIDE, settings.width());
		assertEquals(MinimapSettings.MAX_SIDE, settings.height());
	}

	@Test
	@DisplayName("缩放超界会被钳位")
	void zoomIsClamped() {
		MinimapSettings settings = new MinimapSettings();

		settings.setBlocksPerPixel(-5.0);
		assertEquals(MinimapSettings.MIN_BLOCKS_PER_PIXEL, settings.blocksPerPixel());

		settings.setBlocksPerPixel(1.0e9);
		assertEquals(MinimapSettings.MAX_BLOCKS_PER_PIXEL, settings.blocksPerPixel());
	}

	@Test
	@DisplayName("缩放必须是有限值")
	void zoomMustBeFinite() {
		MinimapSettings settings = new MinimapSettings();

		assertThrows(IllegalArgumentException.class, () -> settings.setBlocksPerPixel(Double.NaN));
		assertThrows(IllegalArgumentException.class, () -> settings.setBlocksPerPixel(Double.POSITIVE_INFINITY));
	}

	@Test
	@DisplayName("雷达半径超界会被钳位，非有限值抛异常")
	void radarRadiusIsClamped() {
		MinimapSettings settings = new MinimapSettings();

		settings.setEntityRadarRadius(0.0);
		assertEquals(MinimapSettings.MIN_RADAR_RADIUS, settings.entityRadarRadius());

		settings.setEntityRadarRadius(1.0e9);
		assertEquals(MinimapSettings.MAX_RADAR_RADIUS, settings.entityRadarRadius());

		assertThrows(IllegalArgumentException.class,
				() -> settings.setEntityRadarRadius(Double.POSITIVE_INFINITY));
	}

	@Test
	@DisplayName("空枚举直接抛异常")
	void nullEnumsAreRejected() {
		MinimapSettings settings = new MinimapSettings();

		assertThrows(IllegalArgumentException.class, () -> settings.setAnchor(null));
		assertThrows(IllegalArgumentException.class, () -> settings.setLayerMode(null));
	}

	@Test
	@DisplayName("copy 得到互不影响的两份")
	void copyIsIndependent() {
		MinimapSettings original = new MinimapSettings();
		original.setWidth(200);
		original.setHeight(150);
		original.setAutoZoom(false);
		original.setOffsetY(12);
		original.setEntityRadarRadius(96.0);
		original.setEntityRadarHostileOnly(true);

		MinimapSettings copy = original.copy();
		copy.setWidth(320);
		copy.setHeight(64);
		copy.setEntityRadarEnabled(false);
		copy.setEntityRadarRadius(32.0);
		copy.setEntityRadarHostileOnly(false);

		assertEquals(200, original.width(), "修改副本不应影响原件");
		assertEquals(150, original.height());
		assertEquals(320, copy.width());
		assertEquals(64, copy.height());
		assertFalse(copy.autoZoom());
		assertEquals(12, copy.offsetY());
		assertEquals(96.0, original.entityRadarRadius(), "副本改半径不应影响原件");
		assertTrue(original.entityRadarEnabled(), "副本改开关不应影响原件");
		assertTrue(original.entityRadarHostileOnly(), "副本改仅敌对不应影响原件");
	}
}
