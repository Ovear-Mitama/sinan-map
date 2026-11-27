package sinanmap.common.waypoint;

import java.util.Objects;
import java.util.UUID;

import sinanmap.common.coord.WorldPos;

/**
 * 一个路径点。
 *
 * <p>不可变对象，修改通过 {@code with*} 方法派生出新实例，这样跨线程传递时不需要额外加锁。
 * 持久化格式与字段一一对应，序列化层不额外引入状态。
 *
 * @param id        唯一标识
 * @param name      显示名称
 * @param pos       所在维度与坐标
 * @param group     分组名（自定义，见 {@link #DEFAULT_GROUP}）
 * @param color     显示颜色，ARGB 格式
 * @param iconId    图标 ID，空字符串表示用默认图标（目前没有图标，留着占位）
 * @param visible   是否在地图上显示
 * @param createdAt 创建时间（自纪元起的毫秒数）
 * @param death     是不是"死亡地点"：由玩家死亡自动记录，地图上画骷髅图标
 * @param shortName 简称：标记<b>收起时</b>显示的那几个字。空字符串 = 用名字的首字（见渲染那边）
 */
public record Waypoint(
		UUID id,
		String name,
		WorldPos pos,
		String group,
		int color,
		String iconId,
		boolean visible,
		long createdAt,
		boolean death,
		String shortName) {

	/** 名称长度上限，同时适用于界面输入与反序列化校验。 */
	public static final int MAX_NAME_LENGTH = 48;

	/** 简称长度上限：标记收起时那点宽度只放得下几个字。 */
	public static final int MAX_SHORT_NAME_LENGTH = 4;

	/** 分组名长度上限。 */
	public static final int MAX_GROUP_LENGTH = 24;

	/**
	 * 没指定分组时用的那个。
	 *
	 * <p>不用分组也能用：新建的路径点默认落在这一组里，管理器里它就是列表最上面那一组。
	 */
	public static final String DEFAULT_GROUP = "默认";

	/**
	 * 死亡地点自动归入的分组。
	 *
	 * <p>玩家死亡自动记下的路径点直接落在这一组里，不用和手动建的混在「默认」组里找；老版本记在
	 * 「默认」组、名叫"上次死亡地点/此前死亡地点"的那些，会在下一次记死亡时一并挪过来（见
	 * {@code WaypointManager#recordDeath}）。
	 */
	public static final String DEATH_GROUP = "死亡";

	/**
	 * 新建路径点的默认颜色。
	 *
	 * <p>{@code #B1EAC2}：一块浅浅的薄荷绿，压在草地上、洞穴里、下界的岩壁上都能看清，
	 * 也不像纯绿那样和地形里的草方块混成一片。
	 */
	public static final int DEFAULT_COLOR = 0xFFB1EAC2;

	/**
	 * 死亡地点的默认颜色。
	 *
	 * <p>用浅暖红（{@code #F9867D}）：死亡点的标记底板就是它，骷髅图标按底板亮度自动取深色或白色的
	 * "对色"来画（见 {@code WaypointRenderer#drawMarker}），所以底板浅、骷髅深，一眼就看出是死亡点。
	 */
	public static final int DEATH_COLOR = 0xFFF9867D;

	/**
	 * 标记相对"脚下那一格"该抬高多少（格）。
	 *
	 * <p>所有"自动记下来的"路径点都用它：新建路径点、死亡地点、以及兼容模组推过来的点。
	 * 取 {@value} 是<b>一格半</b>——正好落在"站在这一格上的人/石碑"的腰腹高度。
	 *
	 * <p>为什么不贴地：标记是挂在坐标点上方的（名字画在点上面），点落在脚底时整个人看着是踩在
	 * 标签上的；抬到腰腹之后，位置才读得出"就是这儿"。
	 */
	public static final double MARKER_Y_OFFSET = 1.5;

	public Waypoint {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(pos, "pos");
		Objects.requireNonNull(iconId, "iconId");

		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("路径点名称不能为空");
		}
		if (name.length() > MAX_NAME_LENGTH) {
			throw new IllegalArgumentException("路径点名称不能超过 " + MAX_NAME_LENGTH + " 个字符");
		}
		// 空分组名等于"没分组"：统一落到默认组，界面与文件里都不会出现空名字的组
		if (group == null || group.isBlank()) {
			group = DEFAULT_GROUP;
		}
		if (group.length() > MAX_GROUP_LENGTH) {
			throw new IllegalArgumentException("分组名不能超过 " + MAX_GROUP_LENGTH + " 个字符");
		}
		// 简称是纯显示字段：超长就截断，不抛异常——手工改过的存档不该因为一个装饰性字段整行读不出来
		shortName = shortName == null ? "" : shortName.strip();
		if (shortName.length() > MAX_SHORT_NAME_LENGTH) {
			shortName = shortName.substring(0, MAX_SHORT_NAME_LENGTH);
		}
	}

	/** 用默认分组与颜色创建路径点。 */
	public static Waypoint create(String name, WorldPos pos) {
		return create(name, pos, DEFAULT_GROUP, DEFAULT_COLOR);
	}

	/** 指定分组与颜色创建路径点。 */
	public static Waypoint create(String name, WorldPos pos, String group, int color) {
		return new Waypoint(UUID.randomUUID(), name, pos, group, color, "", true,
				System.currentTimeMillis(), false, "");
	}

	/**
	 * 兼容旧写法的构造：不写 {@code death} 时当作普通路径点。
	 *
	 * <p>加这条是为了"多一个字段"只影响序列化与死亡逻辑本身，别的调用方（含测试）仍可按原来那
	 * 8 个参数建一条普通路径点。
	 */
	public Waypoint(UUID id, String name, WorldPos pos, String group, int color, String iconId,
			boolean visible, long createdAt) {
		this(id, name, pos, group, color, iconId, visible, createdAt, false, "");
	}

	/** 创建一条"死亡地点"路径点（玩家死亡时自动记录，地图上用骷髅图标画）。 */
	public static Waypoint createDeath(String name, WorldPos pos, String group) {
		return new Waypoint(UUID.randomUUID(), name, pos, group, DEATH_COLOR, "", true,
				System.currentTimeMillis(), true, "");
	}

	public Waypoint withName(String newName) {
		return new Waypoint(id, newName, pos, group, color, iconId, visible, createdAt, death, shortName);
	}

	public Waypoint withPos(WorldPos newPos) {
		return new Waypoint(id, name, newPos, group, color, iconId, visible, createdAt, death, shortName);
	}

	public Waypoint withGroup(String newGroup) {
		return new Waypoint(id, name, pos, newGroup, color, iconId, visible, createdAt, death, shortName);
	}

	public Waypoint withColor(int newColor) {
		return new Waypoint(id, name, pos, group, newColor, iconId, visible, createdAt, death, shortName);
	}

	public Waypoint withIcon(String newIconId) {
		return new Waypoint(id, name, pos, group, color, Objects.requireNonNull(newIconId, "iconId"),
				visible, createdAt, death, shortName);
	}

	public Waypoint withVisible(boolean newVisible) {
		return new Waypoint(id, name, pos, group, color, iconId, newVisible, createdAt, death, shortName);
	}

	/** 换一个简称（空字符串 = 回到"用名字首字"）。 */
	public Waypoint withShortName(String newShortName) {
		return new Waypoint(id, name, pos, group, color, iconId, visible, createdAt, death, newShortName);
	}
}
