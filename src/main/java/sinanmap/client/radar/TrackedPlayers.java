package sinanmap.client.radar;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import org.jetbrains.annotations.Nullable;

import sinanmap.common.net.TrackedPlayersS2C;

/**
 * 服务端同步过来的<b>其他玩家</b>。
 *
 * <p>和 {@link EntityRadar} 的分工：那个画的是"已加载区块里的实体"，玩家走出视野（或去了别的维度）
 * 就看不见了；这里画的是服务端特地发来的玩家坐标——他们可能远在天边、根本不在客户端的加载范围内。
 * 因此两条路都要留着，同一个人同时出现在两边时也不会重（雷达按实体画、这里按坐标画，位置一致）。
 *
 * <p>整批替换而不是增量维护：服务端每次发的就是"现在应该看到谁"，直接换掉最简单，
 * 也不会因为丢包留下一个不存在的头像（见 {@link TrackedPlayersS2C}）。
 */
public final class TrackedPlayers {

	/** 头像边长（GUI 像素）。 */
	public static final int ICON_SIZE = 8;

	/** 取不到皮肤（客户端还没有这个玩家的资料）时的兜底底色。 */
	private static final int FALLBACK_COLOR = 0xFF3B3B46;

	/** 当前位置列表；只在渲染线程访问。 */
	private static final List<TrackedPlayersS2C.Player> ENTRIES = new ArrayList<>();

	private TrackedPlayers() {
	}

	/** 用服务端刚发来的那一批整体替换。 */
	public static void replace(List<TrackedPlayersS2C.Player> players) {
		ENTRIES.clear();
		ENTRIES.addAll(players);
	}

	/** 断开连接：服务端给的坐标立刻作废（那是刚才那个服务器的实时数据）。 */
	public static void clear() {
		ENTRIES.clear();
	}

	/**
	 * 每帧遍历用的<b>只读视图</b>：不复制（世界地图与小地图每帧各问一次）。
	 * 改这份清单只能走 {@link #replace} 与 {@link #clear}。
	 */
	public static List<TrackedPlayersS2C.Player> view() {
		return ENTRIES;
	}

	public static boolean isEmpty() {
		return ENTRIES.isEmpty();
	}

	/**
	 * 画一个玩家：头像 + 名字。
	 *
	 * <p>头像是从<b>皮肤贴图</b>上直接抠正脸那一块（64×64 皮肤里脸在 {@code (8,8)}、大小 {@code 8×8}），
	 * 而不是另做一套离屏渲染——这里只要一个 8 像素的小方块，抠贴图最省事也最清晰。
	 * 帽子层（{@code (40,8)}）刻意不叠：它是半透明的，叠上去只会把五官糊掉。
	 *
	 * @param screenX 屏幕横坐标（GUI 像素，头像以它为中心）
	 * @param opacity 整体不透明度
	 */
	public static void draw(GuiGraphicsExtractor graphics, Font font,
			double screenX, double screenY, TrackedPlayersS2C.Player entry, float opacity) {
		int left = (int) Math.round(screenX) - ICON_SIZE / 2;
		int top = (int) Math.round(screenY) - ICON_SIZE / 2;
		Identifier skin = skinTexture(entry.id());
		if (skin != null) {
			graphics.blit(RenderPipelines.GUI_TEXTURED, skin, left, top, 8.0F, 8.0F,
					ICON_SIZE, ICON_SIZE, 8, 8, 64, 64, withAlpha(0xFFFFFFFF, opacity));
		} else {
			graphics.fill(left, top, left + ICON_SIZE, top + ICON_SIZE,
					withAlpha(FALLBACK_COLOR, opacity));
		}
		// 名字压在头像下面：头像才 8 像素，不写名字根本认不出是谁
		graphics.centeredText(font, Component.literal(entry.name()),
				(int) Math.round(screenX), top + ICON_SIZE + 2, withAlpha(0xFFFFFFFF, opacity));
	}

	/** 从玩家列表里取皮肤贴图；拿不到（离线资料还没到）就返回 null，交给调用方画兜底色块。 */
	@Nullable
	private static Identifier skinTexture(UUID id) {
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		if (connection == null) {
			return null;
		}
		PlayerInfo info = connection.getPlayerInfo(id);
		// 皮肤的"身体"那张贴图就是整张 64×64 皮肤，正脸那一格从它上面抠（见 draw）
		return info == null ? null : info.getSkin().body().texturePath();
	}

	private static int withAlpha(int color, float opacity) {
		int alpha = Math.round(((color >>> 24) & 0xFF) * Math.clamp(opacity, 0.0F, 1.0F));
		return alpha << 24 | color & 0xFFFFFF;
	}
}
