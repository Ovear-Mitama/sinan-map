package sinanmap.common.coord;

/**
 * 朝向与地图旋转之间的换算。
 *
 * <p>Minecraft 的 yaw 以正南（+Z）为 0、顺时针增大（90 为正西）：
 * 前向向量为 {@code (-sin(yaw), cos(yaw))}。
 */
public final class Heading {

	private Heading() {
	}

	/**
	 * 玩家前向向量在 X 轴上的分量。
	 *
	 * @param playerYawDegrees 玩家 yaw（度）
	 */
	public static double forwardX(float playerYawDegrees) {
		return -Math.sin(Math.toRadians(playerYawDegrees));
	}

	/**
	 * 玩家前向向量在 Z 轴上的分量。
	 *
	 * @param playerYawDegrees 玩家 yaw（度）
	 */
	public static double forwardZ(float playerYawDegrees) {
		return Math.cos(Math.toRadians(playerYawDegrees));
	}

	/**
	 * 计算"玩家朝向始终朝上"所需的地图旋转角。
	 *
	 * <p>把前向向量代入地图的正变换（{@code 屏幕 = 中心 + R(rotation) × (世界 - 玩家) / 每像素方块数}）
	 * 可推出 {@code rotation = 180 - yaw}：此时玩家正前方在屏幕上恰好指向正上方（屏幕 Y 轴负方向）。
	 *
	 * @param playerYawDegrees 玩家 yaw（度）
	 * @return 地图的旋转角
	 */
	public static float followRotation(float playerYawDegrees) {
		if (!Float.isFinite(playerYawDegrees)) {
			throw new IllegalArgumentException("yaw 必须是有限值");
		}
		return 180.0F - playerYawDegrees;
	}

	/**
	 * 玩家箭头在屏幕上的方向（单位向量）。
	 *
	 * <p>用的就是地图那一个变换：世界位移先按地图旋转角转一下、再投到屏幕坐标（屏幕 X 对世界 X、
	 * 屏幕 Y 对世界 Z）。于是<b>跟随模式</b>下恒为正上方，<b>正北朝上</b>模式下（旋转角为 0）
	 * 箭头随视角转动——与地形、路径点严丝合缝。
	 *
	 * @param rotation         地图旋转角（度），见 {@link #followRotation}
	 * @param playerYawDegrees 玩家 yaw（度）
	 */
	public static ScreenPoint screenDirection(float rotation, float playerYawDegrees) {
		double radians = Math.toRadians(rotation);
		double cos = Math.cos(radians);
		double sin = Math.sin(radians);
		double forwardX = forwardX(playerYawDegrees);
		double forwardZ = forwardZ(playerYawDegrees);
		// 前向向量本身是单位长度，旋转不改变长度，所以结果仍然是单位向量
		return new ScreenPoint(forwardX * cos - forwardZ * sin, forwardX * sin + forwardZ * cos);
	}
}
