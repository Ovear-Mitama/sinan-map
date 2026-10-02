package sinanmap.client.waypoint;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import org.jetbrains.annotations.Nullable;

import sinanmap.common.coord.Dimensions;
import sinanmap.common.coord.WorldPos;
import sinanmap.common.waypoint.Waypoint;

/**
 * 把路径点分享到聊天里，并让<b>装了本模组的人</b>点一下就收下。
 *
 * <p><b>为什么不需要服务端模组。</b>分享分两半：发出去的是<b>普通聊天消息</b>（原版 {@code sendChat}，受服务器规则约束），
 * 消息里带一段固定格式的坐标；接住的那一半全在客户端——收到时认出格式、<b>把它拦下来</b>（原版那条不再显示），
 * 改排成一条<b>系统消息</b>（灰杠与悬停提示都是原版系统消息自带，走 {@code addServerSystemMessage}），
 * 里面带一个可点的 {@code [添加]}——点它执行一条<b>客户端命令</b>，打开"已填好名字与坐标"的新建界面。
 * 客户端命令被 Fabric 在 {@code ClientPacketListener.sendCommand} 处拦下本地执行，聊天里点击正是走这条路，所以全程不碰服务端。
 *
 * <p><b>没装模组的人</b>看到的只是那条消息本身（坐标写得清清楚楚），聊天不会变乱。
 *
 * <p>文本格式是<b>固定字符串、不走语言文件</b>：它是一份"协议"，中文客户端发出去的英文客户端也要能认出；只有展示给人的那段才翻译。
 */
public final class WaypointShare {

	/** 分享消息的固定前缀。用 ASCII，免得在别的编码环境里变样。 */
	private static final String MARKER = "[Sinan/WP]";

	/** 分享这一行里 {@code [添加]} 的颜色（其余文字走原版系统消息那套灰）。 */
	private static final int SHARE_COLOR = 0xB1EAC2;

	/**
	 * 从一条聊天消息里认出分享。用"在整条消息里找"而非"整条消息必须等于它"：原版发给对方的聊天是 {@code <玩家名> 正文}。
	 *
	 * <p>第一个分组是<b>类型</b>：{@code W} = 分享路径点、{@code L} = 分享位置，两者排版不一样，收件人光看名字分不出，所以写进协议。
	 */
	private static final Pattern PATTERN = Pattern.compile(
			Pattern.quote(MARKER)
					+ "([WL])\\|([^|]*)\\|(-?\\d+)\\|(-?\\d+)\\|(-?\\d+)\\|([A-Za-z0-9_.:\\-/]+)");

	/** 收下一条分享用的客户端命令：只在本地执行，见类注释。 */
	private static final String ADD_COMMAND = "sinanmap-wp-add";

	private WaypointShare() {
	}

	/** 注册"接住分享"的两件事：拦下收到的聊天/系统消息、加一条客户端命令。 */
	public static void register() {
		// 玩家聊天：分享走的就是这条路（sendChat → 服务器再广播回来，原文带 <玩家名> 前缀）。
		// 认出来就返回 false 把它拦掉，改由 absorb 用系统消息重排一条
		ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signed, sender, bound, time) ->
				!absorb(message, sender.name()));
		// 有的服务器会把带前缀的文本当"系统消息"发出来：这里也接一份免得漏；overlay 是动作栏，不动它
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) ->
				overlay || !absorb(message, null));

		// 收下分享用的命令：/sinanmap-wp-add <x> <y> <z> "<维度>" <名字>。
		//
		// <b>维度必须用 string 并加引号，不能用 word</b>：`word()` 的字符集里没有冒号，读 `minecraft:overworld`
		// 会在 `minecraft` 后面停下，Brigadier 报"参数后应有空格分隔，但发现了尾随数据"——点 [添加] 时就会报这个错。
		// 从最里层往外拼，比一层层 .then() 好读、也不会数错括号
		RequiredArgumentBuilder<FabricClientCommandSource, String> name =
				ClientCommands.argument("name", StringArgumentType.greedyString())
						.executes(context -> openCreateScreen(
								IntegerArgumentType.getInteger(context, "x"),
								IntegerArgumentType.getInteger(context, "y"),
								IntegerArgumentType.getInteger(context, "z"),
								StringArgumentType.getString(context, "dimension"),
								StringArgumentType.getString(context, "name")));
		RequiredArgumentBuilder<FabricClientCommandSource, String> dimension =
				ClientCommands.argument("dimension", StringArgumentType.string()).then(name);
		RequiredArgumentBuilder<FabricClientCommandSource, Integer> z =
				ClientCommands.argument("z", IntegerArgumentType.integer()).then(dimension);
		RequiredArgumentBuilder<FabricClientCommandSource, Integer> y =
				ClientCommands.argument("y", IntegerArgumentType.integer()).then(z);
		RequiredArgumentBuilder<FabricClientCommandSource, Integer> x =
				ClientCommands.argument("x", IntegerArgumentType.integer()).then(y);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
				dispatcher.register(ClientCommands.literal(ADD_COMMAND).then(x)));
	}

	/**
	 * 把一条路径点分享到聊天里。名字里的分隔符会被换掉（见 {@link #writeName}）：换的只是发出去的文本，本地路径点不受影响。
	 */
	static void share(Waypoint waypoint) {
		if (waypoint == null) {
			return;
		}
		share(new WorldPos(waypoint.pos().dimension(), waypoint.pos().blockX(),
				waypoint.pos().blockY(), waypoint.pos().blockZ()), waypoint.name(), false);
	}

	/**
	 * 分享一个坐标（世界地图上"在聊天中分享位置"走这一条）。排版与路径点那条不同（"分享了一个位置在 主世界"），
	 * 所以协议里带类型标记（见 {@link #PATTERN}）；收件人点 {@code [添加]} 打开的仍是新建界面。
	 */
	static void shareLocation(WorldPos pos) {
		if (pos == null) {
			return;
		}
		share(pos, Component.translatable("message.sinanmap.share.location_name").getString(), true);
	}

	private static void share(WorldPos pos, String name, boolean location) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return;
		}
		player.connection.sendChat(MARKER + (location ? "L|" : "W|") + writeName(name) + "|"
				+ pos.blockX() + "|" + pos.blockY() + "|" + pos.blockZ() + "|" + pos.dimension());
	}

	/**
	 * 认出分享就<b>吃掉</b>这条消息，改用系统消息重排一条。<b>只有 {@code [添加]} 是彩色的</b>，正文走原版系统消息的灰
	 * （整行染成薄荷绿反而看不出哪个字能点）；{@code [添加]} 也不加下划线。
	 *
	 * @param senderName 发送者名字（玩家聊天那条路能拿到）；系统消息那条路没有，传 {@code null}
	 * @return 这条消息是不是分享（是的话调用方不再让原版显示它）
	 */
	private static boolean absorb(Component message, @Nullable String senderName) {
		Matcher matcher = PATTERN.matcher(message.getString());
		if (!matcher.find()) {
			return false;
		}
		boolean location = "L".equals(matcher.group(1));
		String name = matcher.group(2);
		String dimension = matcher.group(6);
		// 名字空着时也要塞一个占位：命令最后那个参数是"必须给"的，缺了会报参数不足
		String commandName = name.isBlank()
				? Component.translatable("chat.sinanmap.share.unnamed").getString()
				: writeName(name);
		String command = "/" + ADD_COMMAND + " "
				+ matcher.group(3) + " " + matcher.group(4) + " " + matcher.group(5)
				// 维度要带引号：命令里那个参数是 string（见 #register 的说明）
				+ " \"" + dimension + "\" " + commandName;

		Component sender = senderName == null
				? Component.translatable("chat.sinanmap.share.unknown_sender")
				: Component.literal(senderName);
		// 位置那条不写名字（"分享了一个位置在 主世界"），路径点那条才带名字
		MutableComponent line = location
				? Component.translatable("chat.sinanmap.share.line.location",
						sender, dimensionName(dimension))
				: Component.translatable("chat.sinanmap.share.line.waypoint",
						sender,
						name.isBlank()
								? Component.translatable("chat.sinanmap.share.unnamed")
								: Component.literal(name),
						dimensionName(dimension));
		line = line.withStyle(style -> style.withColor(ChatFormatting.GRAY));
		line.append(Component.translatable("chat.sinanmap.share.add").withStyle(style -> style
				.withColor(SHARE_COLOR)
				.withClickEvent(new ClickEvent.RunCommand(command))
				.withHoverEvent(new HoverEvent.ShowText(
						Component.translatable("chat.sinanmap.share.add_hover")))));
		// 走"服务器系统消息"这一条：左边那条灰杠与悬停提示都跟 /tp 的回显一模一样
		Minecraft.getInstance().gui.getChat().addServerSystemMessage(line);
		return true;
	}

	/** 维度在聊天里的名字：原版三维度翻成中文名，别的维度直接写 id。 */
	private static Component dimensionName(String dimension) {
		String key = Dimensions.translationKey(dimension);
		return key == null ? Component.literal(dimension) : Component.translatable(key);
	}

	/**
	 * 点 {@code [添加]} 之后：打开新建界面，名字与坐标都已填好。{@code parent} 传 null（关掉就回游戏）。
	 */
	private static int openCreateScreen(int x, int y, int z, String dimension, String name) {
		Minecraft.getInstance().setScreen(new WaypointEditScreen(null, null,
				new WorldPos(dimension, x, y, z), null, name));
		return 1;
	}

	/**
	 * 名字在协议里的写法：分隔符换成 {@code /}、新行换成空格（否则会破坏格式）。其它字符照旧
	 * （中文、空格都没问题，名字是命令行最后一个"贪婪"参数，空格不会切开它）。
	 */
	private static String writeName(String name) {
		if (name == null || name.isBlank()) {
			return "";
		}
		return name.replace('|', '/').replace('\n', ' ').replace('\r', ' ').strip();
	}
}
