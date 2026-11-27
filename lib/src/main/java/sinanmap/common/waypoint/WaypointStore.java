package sinanmap.common.waypoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import sinanmap.common.coord.WorldPos;

/**
 * 路径点集合：增删查，以及一份"人也能读"的文本格式。
 *
 * <p>纯逻辑、不碰文件也不碰渲染，因此可以单独测：最容易出问题的是格式转换（名字里有分隔符、
 * 颜色是负数、坐标带小数），这些都能在这里测掉。
 *
 * <p><b>用每行一条的文本</b>而不是 NBT/JSON：一整份通常只有几十条、几十 KB，读一次就全在内存里，
 * 不值得为它引一套二进制格式；文本出问题时还能直接打开看、手工改回来。
 *
 * <p>格式（UTF-8，每行一条，{@code #} 开头与空行忽略）：
 * <pre>
 * id|名字|维度|x|y|z|分类|颜色|是否可见|创建时间|是否是死亡点|简称
 * </pre>
 * 名字里的 {@code |}、{@code \} 与换行会被转义成 {@code \p}、{@code \\}、{@code \n}，
 * 这样任何名字都存得下，也不会把行切坏。
 *
 * <p><b>尾部字段只增不改</b>：后加的都排在最后，旧文件少这几个字段时按默认值补上（见 {@link #parseLine}），
 * 于是老存档升上来不会丢数据、也不会读不出来。
 */
public final class WaypointStore {

	/** 版本号，写在第一行；以后改格式时据此分流。 */
	private static final String FORMAT_LINE = "sinanmap-waypoints/1";

	private static final char SEPARATOR = '|';
	private static final char ESCAPE = '\\';

	private final List<Waypoint> waypoints = new ArrayList<>();

	/**
	 * {@link #waypoints} 的只读视图（只建一次）。
	 *
	 * <p>绘制路径点那条路每帧都要遍历一两次，而 {@code all()} 或 {@code inDimension()} 每帧都会
	 * 复制一份列表；这个视图不复制、也不建对象，专门给"每帧看一眼"的地方用。
	 */
	private final List<Waypoint> readOnlyView = Collections.unmodifiableList(waypoints);

	/**
	 * 变更序号。
	 *
	 * <p>界面可以缓存"已经画出来的那一份"，靠它判断要不要重排：路径点每帧都重排一屏几十个标签是白花的
	 * 时间，而用对象比较去判断变没变反而更容易漏。
	 */
	private long revision;

	/** 全部路径点（快照）。 */
	public List<Waypoint> all() {
		return List.copyOf(waypoints);
	}

	/**
	 * 全部路径点的<b>只读视图</b>：不复制。
	 *
	 * <p>给每帧都要遍历一次的绘制路径用（小地图、世界地图、游戏内的标记）。改这份清单仍然只能走
	 * {@link #add} / {@link #remove}，界面别拿它当可变列表。
	 */
	public List<Waypoint> view() {
		return readOnlyView;
	}

	/** 某个维度里的路径点。地图只画当前维度的：同一个 (x, z) 在三个维度是完全不同的地方。 */
	public List<Waypoint> inDimension(String dimension) {
		List<Waypoint> result = new ArrayList<>();
		for (Waypoint waypoint : waypoints) {
			if (waypoint.pos().dimension().equals(dimension)) {
				result.add(waypoint);
			}
		}
		return result;
	}

	public int size() {
		return waypoints.size();
	}

	public boolean isEmpty() {
		return waypoints.isEmpty();
	}

	/** 变更序号，见 {@link #revision}。 */
	public long revision() {
		return revision;
	}

	/** 加一条；同一 id 已存在时替换掉它。 */
	public void add(Waypoint waypoint) {
		for (int i = 0; i < waypoints.size(); i++) {
			if (waypoints.get(i).id().equals(waypoint.id())) {
				waypoints.set(i, waypoint);
				revision++;
				return;
			}
		}
		waypoints.add(waypoint);
		revision++;
	}

	/** 按 id 删一条。 */
	public boolean remove(UUID id) {
		for (int i = 0; i < waypoints.size(); i++) {
			if (waypoints.get(i).id().equals(id)) {
				waypoints.remove(i);
				revision++;
				return true;
			}
		}
		return false;
	}

	/** 整体替换（读文件时用）。 */
	public void replaceAll(List<Waypoint> loaded) {
		waypoints.clear();
		waypoints.addAll(loaded);
		revision++;
	}

	/**
	 * 离给定水平位置最近的一条路径点。
	 *
	 * @param maxDistance 超过这个距离（方块）就当作没有，返回 null
	 */
	public Waypoint nearest(String dimension, double x, double z, double maxDistance) {
		Waypoint best = null;
		double bestDistance = maxDistance * maxDistance;
		for (Waypoint waypoint : waypoints) {
			if (!waypoint.pos().dimension().equals(dimension)) {
				continue;
			}
			double dx = waypoint.pos().x() - x;
			double dz = waypoint.pos().z() - z;
			double distance = dx * dx + dz * dz;
			if (distance <= bestDistance) {
				bestDistance = distance;
				best = waypoint;
			}
		}
		return best;
	}

	/** 序列化成文本，末尾带换行。 */
	public static String serialize(List<Waypoint> source) {
		StringBuilder builder = new StringBuilder();
		builder.append(FORMAT_LINE).append('\n');
		for (Waypoint waypoint : source) {
			builder.append(waypoint.id()).append(SEPARATOR)
					.append(escape(waypoint.name())).append(SEPARATOR)
					.append(waypoint.pos().dimension()).append(SEPARATOR)
					.append(waypoint.pos().x()).append(SEPARATOR)
					.append(waypoint.pos().y()).append(SEPARATOR)
					.append(waypoint.pos().z()).append(SEPARATOR)
					.append(escape(waypoint.group())).append(SEPARATOR)
					.append(waypoint.color()).append(SEPARATOR)
					.append(waypoint.visible() ? '1' : '0').append(SEPARATOR)
					.append(waypoint.createdAt()).append(SEPARATOR)
					.append(waypoint.death() ? '1' : '0').append(SEPARATOR)
					.append(escape(waypoint.shortName()))
					.append('\n');
		}
		return builder.toString();
	}

	/**
	 * 从文本还原。
	 *
	 * <p><b>坏行只跳过，不整份放弃</b>：这是纯本地文件，可能手工改过、也可能写到一半断电。跳过坏行
	 * 至少还能画出剩余的路径点；整份放弃等于丢掉全部已保存的路径点。版本行不认识时按当前格式尽力解析
	 * （多出来的尾部字段忽略、少了的给默认值）。
	 */
	public static List<Waypoint> parse(String text) {
		List<Waypoint> result = new ArrayList<>();
		if (text == null || text.isEmpty()) {
			return result;
		}
		for (String rawLine : text.split("\n")) {
			String line = rawLine.strip();
			if (line.isEmpty() || line.startsWith("#") || line.startsWith("sinanmap-waypoints/")) {
				continue;
			}
			Waypoint parsed = parseLine(line);
			if (parsed != null) {
				result.add(parsed);
			}
		}
		return result;
	}

	private static Waypoint parseLine(String line) {
		List<String> fields = split(line);
		if (fields.size() < 6) {
			return null;
		}
		try {
			UUID id = UUID.fromString(fields.get(0));
			String name = unescape(fields.get(1));
			String dimension = fields.get(2);
			double x = Double.parseDouble(fields.get(3));
			double y = Double.parseDouble(fields.get(4));
			double z = Double.parseDouble(fields.get(5));
			String group = fields.size() > 6 ? groupOf(unescape(fields.get(6))) : Waypoint.DEFAULT_GROUP;
			int color = fields.size() > 7 ? (int) Long.parseLong(fields.get(7)) : Waypoint.DEFAULT_COLOR;
			boolean visible = fields.size() <= 8 || !"0".equals(fields.get(8));
			long createdAt = fields.size() > 9 ? Long.parseLong(fields.get(9)) : 0L;
			// 老文件里没有这个字段（那时还没有死亡地点）：默认"不是死亡点"
			boolean death = fields.size() > 10 && "1".equals(fields.get(10));
			// 简称同样是后加的字段：老文件没有，就是空（空 = 用名字首字）
			String shortName = fields.size() > 11 ? unescape(fields.get(11)) : "";
			if (name.isBlank()) {
				return null;
			}
			return new Waypoint(id, name, new WorldPos(dimension, x, y, z), group, color, "",
					visible, createdAt, death, shortName);
		} catch (RuntimeException e) {
			// 坏行：跳过，理由见 parse
			return null;
		}
	}

	/**
	 * 第 6 个字段 → 分组名。
	 *
	 * <p>老版本这里存的是<b>分类枚举名</b>（{@code HOME} / {@code VILLAGE} / … / {@code OTHER}），
	 * 后来改成自定义的<b>分组名</b>。老名字按当年的中文名搬过来，于是升上来之后分组还在，
	 * 看起来只是"分类"改叫"分组"了。
	 */
	private static String groupOf(String raw) {
		return switch (raw) {
			case "HOME" -> "家";
			case "VILLAGE" -> "村庄";
			case "MINING" -> "矿点";
			case "PORTAL" -> "传送门";
			case "DEATH" -> "死亡点";
			case "OTHER", "DEFAULT" -> Waypoint.DEFAULT_GROUP;
			default -> raw;
		};
	}

	/** 按分隔符切；被转义的分隔符不算。 */
	private static List<String> split(String line) {
		List<String> fields = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean escaped = false;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (escaped) {
				current.append(ESCAPE).append(c);
				escaped = false;
			} else if (c == ESCAPE) {
				escaped = true;
			} else if (c == SEPARATOR) {
				fields.add(current.toString());
				current.setLength(0);
			} else {
				current.append(c);
			}
		}
		// 行尾一个孤立的反斜杠：当作普通字符，不丢
		if (escaped) {
			current.append(ESCAPE);
		}
		fields.add(current.toString());
		return fields;
	}

	/** 名字里会破坏格式的字符：分隔符、反斜杠、换行。 */
	private static String escape(String name) {
		StringBuilder builder = new StringBuilder(name.length() + 8);
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			switch (c) {
				case SEPARATOR -> builder.append(ESCAPE).append('p');
				case ESCAPE -> builder.append(ESCAPE).append(ESCAPE);
				case '\n' -> builder.append(ESCAPE).append('n');
				case '\r' -> builder.append(ESCAPE).append('r');
				default -> builder.append(c);
			}
		}
		return builder.toString();
	}

	private static String unescape(String value) {
		StringBuilder builder = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c != ESCAPE || i + 1 >= value.length()) {
				builder.append(c);
				continue;
			}
			char next = value.charAt(++i);
			switch (next) {
				case 'p' -> builder.append(SEPARATOR);
				case ESCAPE -> builder.append(ESCAPE);
				case 'n' -> builder.append('\n');
				case 'r' -> builder.append('\r');
				default -> builder.append(ESCAPE).append(next);
			}
		}
		return builder.toString();
	}
}
