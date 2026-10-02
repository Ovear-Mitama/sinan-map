package sinanmap.client.waypoint;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sinanmap.common.SinanConstants;
import sinanmap.common.hud.MinimapSettings;
import sinanmap.common.waypoint.Waypoint;

/**
 * 游戏内（3D 世界里）的路径点标记。
 *
 * <p>把路径点的世界坐标用<b>相机投影</b>投到屏幕上，在该处画一个屏幕空间标记——画法与世界地图<b>不一样</b>
 * （见 {@link WaypointRenderer#drawOnHud}）：收起时只有<b>名字首字</b>、<b>该路径点自身颜色的半透明底</b>、<b>没有倒三角</b>；
 * 玩家<b>视角正对着它</b>时才展开成全名 + 一条距离。整层画在<b>原版准心之下</b>（见 {@link #register}）。
 *
 * <p><b>怎么投影的</b>：不挂 Mixin、不翻渲染管线矩阵，自己算一次针孔投影——取相机的插值位置与朝向（yaw/pitch）、
 * 竖直 FOV，把"相机 → 路径点"分解到相机的右/上/前三个轴，再除以进深、乘"半屏高 / tan(FOV/2)"。
 *
 * <p><b>怎么看"视角对准"</b>：标记离<b>屏幕中心</b>的像素距离 = 它与视线轴的夹角 × 焦距，所以
 * {@value #LOOK_CONE_DEGREES}° 的锥角就是"离屏幕中心不超过 {@code tan(角度) × 焦距} 像素"的圆形范围。
 * 用像素距离判而非三维夹角，是为了与真正画出来的位置一致（同一个焦距），FOV 一变阈值也跟着变。
 *
 * <p>只画<b>当前维度、可见、且落在画面里</b>的路径点：投影出屏的不画、也不贴边；距离<b>不设上限</b>，
 * 数量由 {@link #MAX_MARKERS} 收口。
 */
public final class WaypointHud {

	private static final Logger LOGGER = LoggerFactory.getLogger(SinanConstants.MOD_ID);

	/**
	 * 与小地图、世界地图共用同一份设置，只读其中"路径点 HUD"那几项（是否显示距离、标记缩放）；
	 * 设置界面改的就是这一份，改完立刻生效。
	 */
	private final MinimapSettings settings;

	public WaypointHud(MinimapSettings settings) {
		this.settings = settings;
	}

	/** 一屏最多画多少个：与地图上一个道理，标记不该糊满屏幕。 */
	private static final int MAX_MARKERS = 16;

	/**
	 * 投影时进深的下限：进深太小会让投影值爆到满屏跑，所以取它当分母的下限，也是"在相机背后"与"贴在脸上"的分界（见 {@link #render}）。
	 */
	private static final double NEAR = 0.5;

	/** 屏幕边缘留出的边距：标记不该贴着边。 */
	private static final int SCREEN_MARGIN = 8;

	/**
	 * "视角对准"的锥角（度）：标记落在屏幕中心这个角度的圆锥里才算对准、才展开。
	 *
	 * <p>取 {@value}（很小）：10° 时准星周围一大圈都算"看向"，收到 3° 后只有<b>几乎正对准星</b>那一条才展开。
	 */
	private static final double LOOK_CONE_DEGREES = 3.0;

	/**
	 * 注册到 Fabric 的 HUD 元素注册表，位置放在<b>原版准心之前</b>。
	 *
	 * <p>用 {@code addLast} 会画在所有原版元素（含准心）之上、盖住准心；{@code attachElementBefore} 把这一层插到
	 * 原生元素<b>之前</b>，即"先画路径点、再画准心"。原生元素名由 {@link VanillaHudElements} 给出
	 * （本版 {@code VanillaHudElements.CROSSHAIR} = {@code minecraft:crosshair}），不用猜 id。
	 */
	public void register() {
		HudElementRegistry.attachElementBefore(VanillaHudElements.CROSSHAIR,
				Identifier.fromNamespaceAndPath(SinanConstants.MOD_ID, "waypoint_markers"), this::render);
		LOGGER.info("游戏内路径点标记已注册 | 画在原版准心之下 | 相机投影 | 不设距离上限、一屏最多 {} 个 | 看向 {}° 内才展开",
				MAX_MARKERS, (int) LOOK_CONE_DEGREES);
	}

	private void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		// F1（隐藏界面）时跟着原版一起藏起来
		if (player == null || level == null || minecraft.options.hideGui) {
			return;
		}
		Camera camera = minecraft.gameRenderer.getMainCamera();
		if (!camera.isInitialized()) {
			return;
		}
		// 设置里关掉了"游戏内路径点"就整层不画
		if (!settings.showHudWaypoints()) {
			return;
		}

		// 相机：位置与朝向（这两个是插值过的，与这一帧画出来的画面一致）
		Vec3 eye = camera.position();
		double yaw = Math.toRadians(camera.yRot());
		double pitch = Math.toRadians(camera.xRot());
		double cosYaw = Math.cos(yaw);
		double sinYaw = Math.sin(yaw);
		double cosPitch = Math.cos(pitch);
		double sinPitch = Math.sin(pitch);
		// 相机的三个轴（原版约定：yaw=0 朝 +Z，向右是 -X）
		double forwardX = -sinYaw * cosPitch;
		double forwardY = -sinPitch;
		double forwardZ = cosYaw * cosPitch;
		double rightX = -cosYaw;
		double rightZ = -sinYaw;
		double upX = -sinYaw * sinPitch;
		double upY = cosPitch;
		double upZ = cosYaw * sinPitch;

		int guiWidth = graphics.guiWidth();
		int guiHeight = graphics.guiHeight();
		// 竖直 FOV 决定"离视线中心同样的角度落在屏幕多远处"：半屏高 / tan(fov/2)
		float fov = camera.getFov();
		if (fov <= 1.0F) {
			// 拿不到这一帧的 FOV 时退回选项里的那个值（例如刚进世界的第一帧）
			fov = minecraft.options.fov().get();
		}
		double focal = guiHeight / 2.0 / Math.tan(Math.toRadians(fov) / 2.0);
		// "视角对准"的像素阈值：离屏幕中心不超过这么多像素 = 落在 LOOK_CONE_DEGREES 的锥角内
		// （横向、纵向同一个焦距，所以这个圆在两边同样成立）
		double lookRadius = Math.tan(Math.toRadians(LOOK_CONE_DEGREES)) * focal;

		String dimension = level.dimension().identifier().toString();
		int drawn = 0;
		// 每帧都会走这一遍，所以用不复制列表的 view()：见 WaypointStore#view
		for (Waypoint waypoint : WaypointManager.instance().store(minecraft).view()) {
			if (!waypoint.visible() || !waypoint.pos().dimension().equals(dimension)) {
				continue;
			}
			double dx = waypoint.pos().x() - eye.x;
			double dy = waypoint.pos().y() - eye.y;
			double dz = waypoint.pos().z() - eye.z;
			// 进深（相机前 +）：投影、以及"在背后要镜像"都用它
			double depth = dx * forwardX + dy * forwardY + dz * forwardZ;
			// 距离用水平距离：与地图、与路径点列表里的"多少米"是同一个口径（也直接当标记上的距离文本）。
			// 不按距离筛：只要还在画面里就画（筛选条件只有下面的"在画面里 / 在相机前方"两条）
			double meters = Math.sqrt(dx * dx + dz * dz);
			// 在相机背后（或贴在脸上）的不画：投影出来会翻到屏幕另一侧
			if (depth <= NEAR) {
				continue;
			}
			// 投影出屏就不画、也不贴边停住：贴边会在准星四周糊一圈与视角无关的标签
			double screenX = guiWidth / 2.0 + (dx * rightX + dz * rightZ) / depth * focal;
			double screenY = guiHeight / 2.0 - (dx * upX + dy * upY + dz * upZ) / depth * focal;
			if (screenX < SCREEN_MARGIN || screenX > guiWidth - SCREEN_MARGIN
					|| screenY < SCREEN_MARGIN || screenY > guiHeight - SCREEN_MARGIN) {
				continue;
			}
			// 看向才展开：标记离屏幕中心的像素距离在锥角阈值内（其余标记保持"首字"）
			double offsetX = screenX - guiWidth / 2.0;
			double offsetY = screenY - guiHeight / 2.0;
			boolean lookedAt = offsetX * offsetX + offsetY * offsetY <= lookRadius * lookRadius;
			WaypointRenderer.drawOnHud(graphics, minecraft.font, screenX, screenY, waypoint,
					lookedAt, meters, settings);
			if (++drawn >= MAX_MARKERS) {
				break;
			}
		}
	}
}
