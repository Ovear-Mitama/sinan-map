package sinanmap.client.map.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import org.joml.Matrix3x2fStack;

import sinanmap.common.SinanConstants;

/**
 * 玩家标记的绘制器。
 *
 * <p>标记是一张 GUI 图集 sprite（{@code sinanmap:player}，源图见 {@code textures/gui/sprites/player.png}，
 * 一个指向上方的导航箭头），每帧按朝向旋转后贴到对应位置。贴图由美术资源提供，不在代码里画形状——
 * "用代码画箭头"难画好看（上一版尖端超出贴图被切掉一截），也难保证左右对称。
 *
 * <p>不把标记烘焙进地形贴图：它随视角转动，一旦烘焙，每转一度就要重合成整张地图，实测每帧多约 2ms。
 */
public final class MarkerRenderer {

	/** 箭头 sprite：朝上（正北）画，绘制时按玩家朝向旋转。 */
	private static final Identifier TEXTURE =
			Identifier.fromNamespaceAndPath(SinanConstants.MOD_ID, "player");

	/** 绘制标记。坐标与尺寸都是 GUI 像素，方向为屏幕坐标系下的单位向量。 */
	public void draw(GuiGraphicsExtractor graphics, double centerX, double centerY, double size, double dirX, double dirY) {
		// atan2(dx, -dy) 是以"正上方"为 0、顺时针为正的屏幕角度
		float angle = (float) Math.atan2(dirX, -dirY);
		int side = Math.max(1, (int) Math.round(size));
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate((float) centerX, (float) centerY);
		pose.rotate(angle);
		pose.translate((float) (-side / 2.0), (float) (-side / 2.0));
		// 画的是整张 sprite：交给 blitSprite 按图集里记录的精灵尺寸绘制（高分辨率资源包自动缩放）
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, TEXTURE, 0, 0, side, side, 0xFFFFFFFF);
		pose.popMatrix();
	}
}
