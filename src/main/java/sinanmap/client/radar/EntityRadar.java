package sinanmap.client.radar;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.joml.Matrix3x2fStack;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import sinanmap.client.map.data.ClientChunkColorProvider;
import sinanmap.common.SinanConstants;
import sinanmap.common.hud.MinimapSettings;
import sinanmap.common.hud.ScreenRect;

/**
 * 生物/实体雷达：把玩家附近<b>已加载区块里</b>的生物画成小头像，叠在小地图或全屏世界地图之上。
 *
 * <p><b>头像从哪来</b>：见 {@link MobHeads}——把生物交给<b>游戏自己的渲染管线离屏渲染</b>一遍
 * （{@link MobHeadRenderer}）：有"头"的画头像区域（head 所在那个头组，含鬃/吻/冠），头就是身体或
 * 头太小的（鱼/蜜蜂/史莱姆/鸡/蝙蝠…）画整只。两者产出同一张"正视图"小图标，之后每个生物在屏幕上
 * 只做一次普通 GUI blit。
 *
 * <p><b>退化链</b>：头像做不出来（非活体渲染器、贴图读不到、离屏不可用、该变种已判失败）→
 * <b>刷怪蛋物品图标</b> → <b>菱形标记</b>（黄=友好、红=敌对，未知实体用它们表示）。完整优先级：
 * <b>小体型判定 → 真头像 → 刷怪蛋 → 菱形</b>，见 {@link #buildAvatar}。小体型（碰撞箱宽 × 高小于
 * {@value #SMALL_ENTITY_MIN_METRIC}，另有小型鸟兽/农场动物/马等几档，见 {@link #iconScaleFor}）
 * 先按尺寸把整个图标缩小。
 * <b>投射物与点燃的 TNT</b>（{@link #isMarkerEntity}）不是活体、也没有头像来源，直接画<b>紫色菱形</b>。
 *
 * <p><b>缓存策略</b>：{@code EntityType → 变种键 → 头像}（见 {@link MobHeadKey}）只在该<b>变种</b>
 * 第一次出现时解析一次，结果跨世界不失效、无清理逻辑（贴图生命周期见 {@link MobHeads}）；掉落物另有
 * 一张 {@code Item → 图标}的表（见 {@link #itemAvatars}）。按变种而不是按类型做键是必须的：村民职业/
 * 等级、羊毛色、马的花纹、狼/猫的驯服与项圈色、装备……只按类型做键会让它们<b>全部共用第一张图</b>。
 * 键的构造<b>不分配</b>（见 {@link MobHeadKey#of}），所以"每帧查表"仍然零分配。三条退化路里最贵的是
 * 刷怪蛋（{@link SpawnEggItem#byId} 内部线性扫物品注册表），头像解析也不便宜，两者都<b>绝不能每帧、
 * 更不能每个实体各来一次</b>；每类型变种条数有上界（见 {@link #MAX_VARIANTS_PER_TYPE}），超了统一用兜底头像。
 *
 * <p><b>只缓存"终态"</b>：离屏渲染是异步的（{@link MobHeads.Resolution#PENDING}），"正在渲染中"的
 * 中间态<b>不写进缓存</b>——写进去就等于把"还没有"钉成"永远没有"（"先画个刷怪蛋、之后永远画蛋"）。
 *
 * <p><b>画哪些实体</b>：{@link LivingEntity}（生物、其他玩家、盔甲架等）与<b>掉落物</b>
 * （{@link ItemEntity}，用物品自身的图标画）；玩家自己由箭头标记负责，这里跳过。<b>不含</b>箭矢、船、
 * 矿车这类"不是物品"的东西——它们的图标得走 3D 模型，没有现成的 2D 物品图标，画上去只会把地图糊住。
 * 可选"只显示敌对生物"（{@code MobCategory.MONSTER}）：打开时掉落物也一并隐藏（语义一致）。
 *
 * <p><b>每帧开销与两条路</b>：遍历一次 {@code level.entitiesForRendering()}（只含已加载区块），先在
 * <b>垂直方向</b>筛掉玩家上下太远的（见 {@link #MAX_VERTICAL_DISTANCE}），再把半径内、活着的
 * {@link LivingEntity} 按水平距离取<b>最近的 {@value #MAX_ENTITIES} 个</b>。收集用固定的复用数组 +
 * 就地插入排序，<b>不新建任何集合、不产生每帧分配</b>，位置每帧现读。活体那套（垂直筛选、小体型按
 * 碰撞箱缩放、上透下暗）只作用在活体上；掉落物<b>不套用</b>体型判据（其碰撞箱与图标大小无关）。垂直
 * 区分按 ΔY 给正脸/菱形加透明度或亮度，<b>描边不参与渐变</b>，始终是一圈固定深色细边（见
 * {@link MinimapSettings#entityRadarHeightIndicator()}）。
 */
public final class EntityRadar {

	/**
	 * 每帧最多画几个（超出按水平距离取最近的）。
	 *
	 * <p>取 {@value #MAX_ENTITIES}：生物密集时（刷怪塔、村庄/牧场成群的被动生物、下界成群的猪灵）
	 * 上限太小会把"第 N 名开外"的整只漏掉（"部分生物根本没出来"）。加了一道垂直筛选后候选本就会少
	 * 一批，这里给足余量；数组是预分配的复用数组，放大它只是一次性内存，不产生每帧分配。
	 */
	private static final int MAX_ENTITIES = 256;

	/**
	 * 只在玩家上下这个垂直距离（方块）内的生物才画。取 {@value}：约为"一个区块高度出头"的量级，
	 * 保证洞壁上下、地表与浅层洞穴之间的生物不被漏掉；更深的洞穴层按同一口径过滤（不画）。
	 */
	private static final double MAX_VERTICAL_DISTANCE = 24.0;

	/** |ΔY| 不超过这个值视作"同一层"：正常不透明、原色。 */
	private static final double HEIGHT_SAME_LEVEL = 4.0;

	/** 上方最透时的 alpha 下限（0~1）。取 0.65：够透能看出"在上方"，又不会把图标看没了。 */
	private static final float MIN_HEIGHT_ALPHA = 0.65F;

	/** 下方最暗时的亮度下限（0~1）。取 0.75：够暗能看出"在下方"，又绝不把脸压成黑块。 */
	private static final float MIN_HEIGHT_BRIGHTNESS = 0.75F;

	/**
	 * 本帧是否按高度差做视觉区分（上=半透、下=变暗、同层=原样），由设置项"高度指示"驱动。
	 *
	 * <p>在 {@link #refresh} 里从设置读一次，绘制时直接用——绘制路径上不该再去碰 settings。
	 */
	private boolean heightIndicator = true;

	/**
	 * 当前雷达图标的显示档（全部 / 无图标（菱形） / 仅玩家），由 {@link #refresh} 从设置里读一次。
	 *
	 * <p>在绘制路径上不再碰 settings，只认这里存的值。
	 */
	private MinimapSettings.RadarIconMode iconMode = MinimapSettings.RadarIconMode.ALL;

	/** 物品图标在原版模型里的边长（GUI 像素），用来把头像缩放到想要的大小。 */
	private static final double ITEM_ICON_SIZE = 16.0;

	/** {@link #ITEM_ICON_SIZE} 的一半：把图标/方点从"左上角"挪到"以中心为原点"。 */
	private static final int ICON_HALF = 8;

	/**
	 * 头像内容的边长（16×16 的图标方框里正好占满，也就是头像的"尺寸预算"）。不再给描边留位——头像
	 * 贴图里已烘焙好剪影描边，留位只会把头变小。非正方形头像按宽高比塞进这个 {@code INNER}×
	 * {@code INNER} 的正方形：<b>长边</b>用满预算、短边按比例缩小。
	 */
	private static final int INNER = ICON_HALF * 2;

	/**
	 * 图标的深色描边色（菱形标记的深色边）。<b>只画最外一圈</b>，不参与高度渐变——若把它当"整块
	 * 底色"铺满并跟着亮度/透明度衰减，远离同层时整块图标会变成黑/半透的底。
	 */
	private static final int DOT_OUTLINE = 0xE014141A;

	/**
	 * 生物图标的边长（<b>GUI 像素</b>，以 {@value #ICON_REFERENCE_GUI_SCALE} 倍 GUI 缩放为基准）——
	 * <b>两个地图共用这一条规则</b>，所以天生一致。
	 *
	 * <p><b>为什么不是简单的固定值</b>：完全固定 GUI 像素时，最大化窗口（GUI 缩放变大）图标会跟着
	 * 明显变大；完全固定设备像素时，最大化后它相对小地图又显得变小。两头都不合适，所以取
	 * <b>部分跟随</b>：GUI 尺寸按 {@code guiScale^-0.3} 变化（设备尺寸按 {@code guiScale^0.7} 增长，
	 * 介于"完全跟随"和"完全不变"之间）——窗口化时图标够大，最大化时不至于突兀变大。
	 *
	 * <p><b>实测校准</b>：窗口化（guiScale 2）用 {@value} GUI；最大化（guiScale 3~4）经指数折算后
	 * 约 4.9~5.4 GUI，正是用户认可的"最大化下正常"的那一档。
	 */
	private static double radarIconSize() {
		double guiScale = Math.max(1.0, Minecraft.getInstance().getWindow().getGuiScale());
		return ICON_GUI_PIXELS * Math.pow(ICON_REFERENCE_GUI_SCALE / guiScale, ICON_FOLLOW_EXPONENT);
	}

	/**
	 * 见 {@link #radarIconSize}：<b>guiScale 2 时</b>的图标边长（GUI 像素）。
	 *
	 * <p><b>5.0 → 5.5 → 6.0 → {@value}</b>：一路按"整体小一点 / 太大 / 最大化下正常但窗口化偏小"
	 * 来回试出来的当前落点（窗口化基准）。要整体再大一档/小一档<b>只改这一个数</b>；
	 * 注意同时要动 {@link #ICON_FOLLOW_EXPONENT} 才能把"最大化那一端"保持在原来的大小（见下）。
	 *
	 * <p><b>注意</b>：这个数是 <b>GUI 像素</b>（会跟随界面部分放大），不是设备像素——guiScale 2 下
	 * {@value} GUI 等于 13.6 个设备像素，和"设备像素口径的 5.5"完全不同，别混。
	 *
	 * <p><b>再往下的代价</b>：最小体型档（{@link #SMALL_ICON_MIN_SCALE}）乘完就只剩三四个像素，
	 * 兔那种叠了两档的（见 {@link #RABBIT_ICON_SCALE}）会掉到 3 像素以下、糊成一团，
	 * 所以"整体变小"要和那些额外档位一起看，不要单独把这里压到底。
	 */
	private static final double ICON_GUI_PIXELS = 6.8;

	/**
	 * {@link #ICON_GUI_PIXELS} 对应的基准 GUI 缩放：窗口化实测为 2（日志里的一帧 96×64 GUI × 缩放 2）。
	 */
	private static final double ICON_REFERENCE_GUI_SCALE = 2.0;

	/**
	 * 部分跟随指数（越小越"跟随界面"，越大越"固定物理大小"）：
	 * {@code 0} = 完全跟随（纯 GUI 像素）、{@code 1} = 完全固定（纯设备像素）。
	 *
	 * <p><b>0.3 → {@value}</b>：用户要"窗口化再大一点"，但又认可最大化下的大小，所以不能只抬基准
	 * （那样两头一起变大）。调大指数后，窗口化（guiScale 2）按 {@link #ICON_GUI_PIXELS} 取更大值，
	 * 而 guiScale 4 处的折算结果仍 ≈5.0 GUI（用户认可的"最大化正常"档）：
	 * {@code 6.8 × (2/4)^0.44 ≈ 5.0}。改动前先按这个式子验算两端。
	 */
	private static final double ICON_FOLLOW_EXPONENT = 0.44;

	/** 小体型判据：碰撞箱<b>宽 × 高</b>小于这个值就算小生物（见 {@link #iconScaleFor}）。 */
	private static final float SMALL_ENTITY_MIN_METRIC = 0.75F;

	/** 小体型判据的另一头：地面积缩到这个值（兔/蠹虫/末影螨/蝌蚪）时按 {@link #SMALL_ICON_MIN_SCALE} 画。 */
	private static final float SMALL_ENTITY_TINY_METRIC = 0.2F;

	/**
	 * 小体型里最极端那个的图标缩放：越小画得越小。取 {@value} 而非更小：基础边长
	 * {@value #ICON_GUI_PIXELS} GUI 像素再乘更小的档就只剩三四个像素、屏幕上一团糊。
	 */
	private static final float SMALL_ICON_MIN_SCALE = 0.70F;

	/**
	 * 每个类型最多缓存几个变种。取 {@value #MAX_VARIANTS_PER_TYPE}：原版变种最多的就是村民
	 * （类型×职业×等级）与热带鱼，足够覆盖真实观感。超出后<b>不再往表里加</b>，统一用"该类型已建过的
	 * 那张"当兜底（见 {@link TypeAvatars#overflow}）——即使某模组生物有几百种皮肤，内存仍是
	 * "类型数 × 32 张小贴图"这个有界量，也不会退化成"每帧重算"。
	 */
	private static final int MAX_VARIANTS_PER_TYPE = 32;

	/**
	 * 一个类型下的头像表：{@code 变种键 → 头像}，外加一个"变种太多时统一用的兜底"。
	 * 做成一个盒子是为了超上限时还能记住兜底头像：直接拒绝入表的话，超出上限的变种<b>每帧都会被
	 * 重新解析一遍</b>，比"图标近似"糟得多。
	 */
	private static final class TypeAvatars {

		private final Map<MobHeadKey, Avatar> byKey = new HashMap<>();
		private Avatar overflow;

		/** 命中就返回（命中路径上零分配）；没命中返回 {@code null}。 */
		private Avatar get(MobHeadKey key) {
			Avatar cached = this.byKey.get(key);
			return cached != null ? cached : this.overflow;
		}

		/** 入表：表没满就按变种存，满了就更新兜底头像。 */
		private void put(MobHeadKey snapshot, Avatar avatar) {
			if (this.byKey.size() < MAX_VARIANTS_PER_TYPE) {
				this.byKey.put(snapshot, avatar);
			} else {
				this.overflow = avatar;
			}
		}
	}

	/** {@code EntityType → 该类型各变种的头像}。只在第一次遇到该变种时插入，之后只读，故不是每帧分配。 */
	private final Map<EntityType<?>, TypeAvatars> avatars = new HashMap<>();

	/**
	 * {@code Item → 掉落物图标}。
	 *
	 * <p>按<b>物品</b>（而不是 {@code ItemStack} 或每个实体）做键：查表只算一次身份比较，<b>每帧
	 * 零分配、零组件哈希</b>；按 {@code ItemStack} 做键要对组件做哈希与相等判断，掉落物一多就是每帧
	 * 额外开销。代价是"同一物品的不同组件变体"共用同一张图标（药水/附魔书/旗帜只画成<b>第一次看到的
	 * 那种变体</b>）——掉落物在小地图上只有几个像素，这个取舍划算。
	 */
	private final Map<Item, Avatar> itemAvatars = new HashMap<>();

	/** {@code EntityType → 菱形标记}（非活体：投射物、点燃的 TNT，见 {@link #isMarkerEntity}）。 */
	private final Map<EntityType<?>, Avatar> markerAvatars = new HashMap<>();

	/** 每帧复用的"最近的实体"表（按距离升序），避免每帧新建集合。 */
	private final Entity[] nearest = new Entity[MAX_ENTITIES];
	private final double[] nearestDistSqr = new double[MAX_ENTITIES];
	private int count;

	/**
	 * 一个实体类型的头像：三者按优先级取其一——<b>真头像</b>（{@code head} 非空，含合成好的正视图
	 * 贴图与像素尺寸，见 {@link MobHeads.Head}）、<b>物品图标</b>（{@code itemIcon} 非空，刷怪蛋或掉落物
	 * 本身那件物品）、<b>菱形标记</b>（都没有，用 {@code color} 画一颗小菱形）。
	 *
	 * <p>{@code iconScale} 是这个类型整体画多大（1.0 = 正常，小体型见 {@link #iconScaleFor}），和头像
	 * 一起按类型缓存；{@code itemIcon} 是"实际掉的那份栈的拷贝"（有些物品图标由组件决定，空栈可能解析
	 * 不出），交给原版 GUI 物品渲染画（见 {@link #drawItem}）；{@code color} 只在没有头像也没有物品
	 * 图标时有意义（紫色给投射物/点燃的 TNT，黄/红给未知实体）。
	 */
	private record Avatar(MobHeads.Head head, ItemStack itemIcon, int color, float iconScale) {

		boolean hasHead() {
			return head != null;
		}

		boolean hasItemIcon() {
			return itemIcon != null;
		}
	}

	/**
	 * 收集这一帧要画的生物。只在渲染线程调用；不为了雷达去加载任何区块
	 * （{@code entitiesForRendering()} 本身就只含<b>已加载区块</b>里的实体）。
	 *
	 * @param settings 当前设置：关掉开关时直接清空，不遍历
	 */
	public void refresh(ClientLevel level, LocalPlayer player, MinimapSettings settings) {
		count = 0;
		if (!settings.entityRadarEnabled()) {
			return;
		}
		heightIndicator = settings.entityRadarHeightIndicator();
		iconMode = settings.radarIconMode();
		double radius = settings.entityRadarRadius();
		double radiusSqr = radius * radius;
		double playerX = player.getX();
		double playerY = player.getY();
		double playerZ = player.getZ();
		boolean hostileOnly = settings.entityRadarHostileOnly();
		for (Entity entity : level.entitiesForRendering()) {
			// 玩家自己由箭头标记负责，别叠一个头像上去
			if (entity == player || !entity.isAlive()) {
				continue;
			}
			// "仅玩家"档：只画其他玩家，生物/掉落物/投射物一律不画
			if (iconMode == MinimapSettings.RadarIconMode.PLAYERS && !(entity instanceof Player)) {
				continue;
			}
			// 活体、掉落物，以及两类"标记"（投射物与已点燃的 TNT）；其余（船/矿车/展示框…）不纳入。
			boolean item = entity instanceof ItemEntity;
			if (!item && !(entity instanceof LivingEntity) && !isMarkerEntity(entity)) {
				continue;
			}
			if (hostileOnly && iconMode != MinimapSettings.RadarIconMode.PLAYERS) {
				// "仅敌对"时只留敌对生物：掉落物与投射物也不是敌对生物，语义保持一致。
				// "仅玩家"档不看这一条——玩家既不是敌对生物，两个开关叠在一起会得到空雷达。
				if (entity.getType().getCategory() != MobCategory.MONSTER) {
					continue;
				}
			}
			// 先按垂直差筛：放在半径判定之前，一是最便宜，二是别让"上下很远但水平很近"的实体占掉
			// "最近的 N 个"的名额（否则生物密集时会把该显示的挤掉）。
			if (Math.abs(entity.getY() - playerY) > MAX_VERTICAL_DISTANCE) {
				continue;
			}
			double dx = entity.getX() - playerX;
			double dz = entity.getZ() - playerZ;
			double distSqr = dx * dx + dz * dz;
			if (distSqr > radiusSqr) {
				continue;
			}
			insert(entity, distSqr);
		}
		sortByHeightForDrawing();
		// 离屏头像的推进不在这里（这里是小地图 HUD 中途，见 registerPumpHook）；排队仍在本方法里，
		// 所以这一帧新遇到的类型当帧就排上了队。
	}

	/**
	 * 注册"离屏头像"的推进时机：<b>世界画完、GUI 还没开始</b>这一刻（见 {@link MobHeads#pump}）。
	 *
	 * <p><b>不能在 HUD 里顺手推进</b>：{@link #refresh} 在小地图 HUD 里调，而 HUD 阶段此时还有没画完的
	 * 几何（本模组的游戏内路径点标记就是），推进会在 HUD 中途切走渲染目标与投影，那些几何之后落笔就画进
	 * 了那张 32×32 的离屏画布（每个头像多出几条杂色，而且波及当帧所有类型）。
	 *
	 * <p>所以放到 <b>关卡"抽取"阶段的末尾</b>（{@code LevelRenderEvents.END_EXTRACTION}）：此时这一帧
	 * 还没有任何东西落笔（世界还没画、HUD 更没开始），画布干净；同时也不在任何渲染通道里（离屏渲染会新建
	 * 通道，处在通道里会直接抛异常）。
	 */
	public static void registerPumpHook() {
		LevelRenderEvents.END_EXTRACTION.register(context -> MobHeads.pump());
	}

	/**
	 * 把实体插进"最近表"（保持按距离升序）。
	 *
	 * <p>表没满直接插，满了只有比<b>当前最远</b>的更近才替换，于是整表始终是"最近的
	 * {@value #MAX_ENTITIES} 个"，一趟遍历、每步至多 O(N) 冒泡，不必先收集全部候选再排序。
	 */
	private void insert(Entity entity, double distSqr) {
		int limit = count < MAX_ENTITIES ? count : MAX_ENTITIES - 1;
		if (count == MAX_ENTITIES && distSqr >= nearestDistSqr[limit]) {
			return;
		}
		int i = limit;
		while (i > 0 && nearestDistSqr[i - 1] > distSqr) {
			nearestDistSqr[i] = nearestDistSqr[i - 1];
			nearest[i] = nearest[i - 1];
			i--;
		}
		nearestDistSqr[i] = distSqr;
		nearest[i] = entity;
		if (count < MAX_ENTITIES) {
			count++;
		}
	}

	/**
	 * 画之前按<b>高度</b>重排：高的排在后面，于是它压在低的之上。
	 *
	 * <p>收集阶段（见 {@link #insert}）按水平距离排序是为了"取最近的 N 个"；绘制先后若也按距离，
	 * 玩家上方的生物就会被脚下的盖住，所以收集完再按 {@code getY()} 升序就地插入排序一次。
	 * 就地排、复用同一对数组，不产生每帧分配。
	 */
	private void sortByHeightForDrawing() {
		for (int i = 1; i < count; i++) {
			Entity entity = nearest[i];
			double y = entity.getY();
			int j = i - 1;
			while (j >= 0 && nearest[j].getY() > y) {
				nearest[j + 1] = nearest[j];
				j--;
			}
			nearest[j + 1] = entity;
		}
	}

	/**
	 * 把小地图范围内的生物画到屏幕上。
	 *
	 * <p>位置与地形、路径点<b>用同一套换算</b>（与玩家的世界位移 → 同一旋转角 → 除以每 GUI 像素的
	 * 方块数），所以头像与地形严丝合缝地一起转、一起缩放；头像本身<b>不跟着地图转</b>（在屏幕坐标系里
	 * 正着画），所以锁定正北时随地图转、跟随视角时也不歪，始终正立。
	 *
	 * <p><b>按住 TAB 时的贴边</b>：跑出小地图范围的实体改成<b>贴到最近的边框内侧</b>（按图标半径内缩、
	 * 不会被裁一半），于是"地图外还有谁、在哪个方向"一眼可见；松开 TAB 就恢复原样。
	 *
	 * @param scale   与 {@code MinimapHud#drawWaypoints} 的 {@code scale} 完全一致
	 */
	public void drawOnMinimap(GuiGraphicsExtractor graphics, ScreenRect rect, float rotation,
			double playerX, double playerY, double playerZ, double scale) {
		if (count == 0) {
			return;
		}
		double iconSize = radarIconSize();
		double radians = Math.toRadians(rotation);
		double cos = Math.cos(radians);
		double sin = Math.sin(radians);
		double centerX = rect.x() + rect.width() / 2.0;
		double centerY = rect.y() + rect.height() / 2.0;
		double margin = iconSize / 2.0 + 1.0;
		// 见上面"按住 TAB 时的贴边"：按住玩家列表键就把框外的实体贴到边框内侧
		boolean clampToEdge = Minecraft.getInstance().options.keyPlayerList.isDown();
		graphics.enableScissor(rect.x(), rect.y(), rect.right(), rect.bottom());
		for (int i = 0; i < count; i++) {
			Entity entity = nearest[i];
			double dx = entity.getX() - playerX;
			double dz = entity.getZ() - playerZ;
			double x = centerX + (dx * cos - dz * sin) * scale;
			double y = centerY + (dx * sin + dz * cos) * scale;
			if (x < rect.x() - margin || x > rect.right() + margin
					|| y < rect.y() - margin || y > rect.bottom() + margin) {
				if (!clampToEdge) {
					continue;
				}
				x = Math.clamp(x, rect.x() + margin, rect.right() - margin);
				y = Math.clamp(y, rect.y() + margin, rect.bottom() - margin);
			}
			drawIcon(graphics, entity, x, y, iconSize, playerY);
		}
		graphics.disableScissor();
	}

	/**
	 * 把全屏地图范围内的生物画到屏幕上。
	 *
	 * <p>全屏地图固定正北朝上（旋转为 0），位置换算就是"原点 GUI 坐标 + 世界位移 / 每 GUI 像素方块数"，
	 * 与地形、路径点、玩家标记同一条口径。
	 */
	public void drawOnWorldMap(GuiGraphicsExtractor graphics, int screenWidth, int screenHeight,
			double originGuiX, double originGuiZ, double originWorldX, double originWorldZ,
			double perGui, double playerY) {
		if (count == 0) {
			return;
		}
		double iconSize = radarIconSize();
		double margin = iconSize / 2.0 + 1.0;
		for (int i = 0; i < count; i++) {
			Entity entity = nearest[i];
			double x = originGuiX + (entity.getX() - originWorldX) / perGui;
			double y = originGuiZ + (entity.getZ() - originWorldZ) / perGui;
			if (x < -margin || x > screenWidth + margin || y < -margin || y > screenHeight + margin) {
				continue;
			}
			drawIcon(graphics, entity, x, y, iconSize, playerY);
		}
	}

	/**
	 * 画一个头像：把坐标平移到实体所在屏幕位置，再把 16×16 的方块缩放到 {@code size * iconScale}。
	 *
	 * <p>有真头像就按<b>头像自己的宽高比</b>贴整张正视图；否则贴物品图标（刷怪蛋，或掉落物本身）；
	 * 再不然画一颗菱形标记（{@link #drawDiamond}）。变换矩阵先压栈再还原，不影响后面画的地形/路径点。
	 *
	 * <p><b>"正在渲染中"这一帧什么都不画</b>：{@link #avatarFor} 返回 {@code null} 表示首张真实头像还在
	 * 离屏渲染/回读（见 {@link MobHeads}），先跳过、下一帧大概率就有。跳过而不是画替代品，是为了避免
	 * "先画个退化结果、之后永远画它"。
	 *
	 * <p><b>非正方形</b>：把 {@code INNER}×{@code INNER} 的<b>内框</b>当作尺寸预算，按图标宽高比塞进去
	 * （长边用满、短边按比例缩小），竖长的图标因此不会把旁边挡死。
	 *
	 * <p><b>高度视觉区分</b>：按 {@code entity.getY() - playerY} 算 alpha（在上方越小越透）与
	 * brightness（在下方越小越暗），只乘到<b>正脸/菱形本身</b>上；描边固定不透明、绝不参与渐变。
	 * {@code |ΔY| ≤ HEIGHT_SAME_LEVEL} 的"同层"两个系数都保持 1.0。
	 */
	private void drawIcon(GuiGraphicsExtractor graphics, Entity entity, double x, double y,
			double size, double playerY) {
		float alpha = 1.0F;
		float brightness = 1.0F;
		if (heightIndicator) {
			double dy = entity.getY() - playerY;
			// (MAX_VERTICAL_DISTANCE - HEIGHT_SAME_LEVEL) 是能画出来的最大 ΔY：到那里正好压到下限
			double span = MAX_VERTICAL_DISTANCE - HEIGHT_SAME_LEVEL;
			if (dy > HEIGHT_SAME_LEVEL) {
				// 在上方：越上越透
				float t = (float) Math.min(1.0, (dy - HEIGHT_SAME_LEVEL) / span);
				alpha = 1.0F - (1.0F - MIN_HEIGHT_ALPHA) * t;
			} else if (dy < -HEIGHT_SAME_LEVEL) {
				// 在下方：越深越暗
				float t = (float) Math.min(1.0, (-dy - HEIGHT_SAME_LEVEL) / span);
				brightness = 1.0F - (1.0F - MIN_HEIGHT_BRIGHTNESS) * t;
			}
		}

		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		// 贴到整数<b>设备</b>像素再画：按小数位置贴图会把烘焙的那圈深色描边重采样成"一段有一段没有"，
		// 移动起来就是黑边抖动；对齐后每步都落在整像素上。
		double deviceScale = Minecraft.getInstance().getWindow().getGuiScale();
		pose.translate((float) (Math.round(x * deviceScale) / deviceScale),
				(float) (Math.round(y * deviceScale) / deviceScale));

		if (iconMode == MinimapSettings.RadarIconMode.DIAMOND) {
			// 无图标档：一律画菱形，固定正常大小（不再随生物体型缩，否则小生物就是一个看不清的点）。
			// 颜色口径与平时的一致：投射物/点燃的 TNT 紫、敌对红、其余友好黄。
			// 菱形画成接近头像的体积（见 {@link #DIAMOND_MODE_ICON_SIZE}），否则整片雷达就是一堆糊点。
			pose.scale((float) (size / ITEM_ICON_SIZE), (float) (size / ITEM_ICON_SIZE));
			int color = isMarkerEntity(entity) ? MARKER_PURPLE : unknownMarkerColor(entity.getType());
			drawDiamond(graphics, fadeColor(color, brightness, alpha), DIAMOND_MODE_ICON_SIZE);
		} else if (iconMode == MinimapSettings.RadarIconMode.PLAYERS) {
			// 仅玩家档：refresh 已筛成只有其他玩家，画皮肤正脸
			pose.scale((float) (size / ITEM_ICON_SIZE), (float) (size / ITEM_ICON_SIZE));
			drawPlayerFace(graphics, (Player) entity, brightness, alpha);
		} else {
			// 全部档：掉落物走物品图标那条路；活体/标记走头像那条路
			Avatar avatar = entity instanceof ItemEntity item ? avatarForItem(item) : avatarFor(entity);
			if (avatar == null) {
				// 这个类型的头像还在离屏渲染中：这一帧不画（也绝不把这个状态缓存成退化结果）
				pose.popMatrix();
				return;
			}
			// 小体型（iconScale < 1）整体画小一点：这是"按碰撞箱尺寸适配"的落点，见 iconScaleFor
			pose.scale((float) (size * avatar.iconScale() / ITEM_ICON_SIZE),
					(float) (size * avatar.iconScale() / ITEM_ICON_SIZE));
			if (avatar.hasHead()) {
				drawHead(graphics, avatar.head(), brightness, alpha);
			} else if (avatar.hasItemIcon()) {
				// 掉落物图标再缩一档：原版物品渲染是 16×16 画满内框的，在小地图上比生物头像还抢眼
				// 只影响物品，不动头像与菱形标记。
				pose.pushMatrix();
				pose.scale(ITEM_ICON_SCALE, ITEM_ICON_SCALE);
				drawItem(graphics, avatar.itemIcon());
				pose.popMatrix();
			} else {
				// 菱形标记：贴图（自绘）+ 按标记类型着色；只有菱形本体参与高度渐变，描边在贴图里
				drawDiamond(graphics, fadeColor(avatar.color(), brightness, alpha), MARKER_DIAMOND_SIZE);
			}
		}
		if (heightIndicator) {
			drawHeightIndicator(graphics, entity.getY() - playerY, backdropColorOf(entity));
		}
		pose.popMatrix();
	}

	/**
	 * 画一个玩家的<b>皮肤正脸</b>（复制自 {@link TrackedPlayers} 的取皮方案，对齐参考的 Damage-Engine）：
	 * 从 64×64 皮肤上把正脸那一格 {@code (8,8)}、{@code 8×8} 抠出来贴成方形头像。
	 *
	 * <p>关节与活体头像一样随整体缩放与高度渐变（描边不参与渐变）。帽子层（{@code (40,8)}）刻意不叠，
	 * 它是半透明的，叠上去只会把五官糊掉。
	 */
	private static void drawPlayerFace(GuiGraphicsExtractor graphics, Player player,
			float brightness, float alpha) {
		int color = fadeColor(0xFFFFFFFF, brightness, alpha);
		Identifier skin = skinTextureOf(player);
		if (skin != null) {
			graphics.blit(RenderPipelines.GUI_TEXTURED, skin, -ICON_HALF, -ICON_HALF,
					8.0F, 8.0F, INNER, INNER, 8, 8, 64, 64, color);
		} else {
			// 皮肤资料还没到：画一块兜底色（与 TrackedPlayers 同色），不至于整个空格
			graphics.fill(-ICON_HALF, -ICON_HALF, ICON_HALF, ICON_HALF,
					fadeColor(0xFF3B3B46, brightness, alpha));
		}
	}

	/** 见 {@link #drawPlayerFace}：从玩家列表里拿皮肤贴图；拿不到（离线资料还没到）就返回 null。 */
	private static Identifier skinTextureOf(Player player) {
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		if (connection == null) {
			return null;
		}
		PlayerInfo info = connection.getPlayerInfo(player.getUUID());
		return info == null ? null : info.getSkin().body().texturePath();
	}

	/**
	 * 画物品/刷怪蛋图标：<b>直接走原版的 GUI 物品渲染</b>（{@code GuiGraphicsExtractor#item}）。
	 *
	 * <p>画出来和物品栏里完全一样（方块是立体的小方块，而不是某个面的贴图）。不自己取粒子贴图再 blit，
	 * 是因为那样方块类物品只剩一个平面、与物品栏对不上。
	 *
	 * <p><b>代价</b>：{@code item()} 没有颜色参数，物品图标不再随高度做明暗/透明变化，上下高度只由右下角
	 * 那个箭头表示（见 {@link #drawHeightIndicator}）。物品图标以"格"为单位画在 (0,0)..(16,16)，
	 * 这里把它挪到以中心为原点。
	 */
	private static void drawItem(GuiGraphicsExtractor graphics, ItemStack stack) {
		graphics.item(stack, -ICON_HALF, -ICON_HALF);
	}

	/**
	 * 图标右下角的<b>高度指示</b>：目标在玩家上方画"上箭头"、在下方画"下箭头"、同层不画。
	 *
	 * <p>高度区分（上方变透、下方变暗）在小图标上很含蓄，尤其物品图标颜色本来就杂，一个明确的箭头让
	 * "在头顶还是脚底"一眼可读；画在右下角、压住图标一角，不遮挡主体。只在"确实差出一个身位"
	 * （{@code |ΔY| > }{@value #HEIGHT_SAME_LEVEL}，与渐变同口径）时才画。
	 *
	 * <p><b>深浅两套都是图片</b>：亮地形取 {@code height_up_dark} / {@code height_down_dark}，其余
	 * （暗地形、夜间、下雨等）取 {@code height_up} / {@code height_down}。判据见 {@link #backdropColorOf}
	 * ——它返回的是<b>按当前光照压暗之后</b>的地图色，所以"地图整体变暗就换白箭头"自动成立。颜色完全由
	 * PNG 决定，代码里没有颜色常量（不要硬编码，方便资源包替换）。
	 */
	private static void drawHeightIndicator(GuiGraphicsExtractor graphics, double dy, int backdrop) {
		boolean dark = isBrightColor(backdrop);
		Identifier texture;
		if (dy > HEIGHT_SAME_LEVEL) {
			texture = dark ? HEIGHT_UP_DARK_TEXTURE : HEIGHT_UP_TEXTURE;
		} else if (dy < -HEIGHT_SAME_LEVEL) {
			texture = dark ? HEIGHT_DOWN_DARK_TEXTURE : HEIGHT_DOWN_TEXTURE;
		} else {
			return;
		}
		// 图标以中心为原点画（跨度 -8..+8），GUI 里 +y 朝下，所以"右下角"起点取 {@code ICON_HALF - size}；
		// 再按 {@link #HEIGHT_INDICATOR_OFFSET} 往外让一点，箭头外角微微探出图标框。
		int size = HEIGHT_INDICATOR_SIZE;
		int x = ICON_HALF - size + HEIGHT_INDICATOR_OFFSET;
		int y = ICON_HALF - size + HEIGHT_INDICATOR_OFFSET;
		// 原样贴：深浅由选中的那张 PNG 决定，不再乘色（tint 固定不透明白 = 不改色）。
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, texture, x, y, size, size, 0xFFFFFFFF);
	}

	/** 见 {@link #isBrightColor}：整数 Rec.601 亮度超过这个值算"亮地形"。 */
	private static final int BRIGHT_LUMA_THRESHOLD = 140;

	/** 见 {@link #backdropColorOf}：天空光衰减的满值（原版 {@code getSkyDarken()} 的上限）。 */
	private static final float SKY_DARKEN_MAX = 11.0F;

	/** 见 {@link #backdropColorOf}：下雨/雷雨时地图再压一档。 */
	private static final float RAIN_DIM = 0.55F;

	/** 见 {@link #backdropColorOf}：往下找"地图上看得见的那一格"时最多探这么多格。 */
	private static final int SURFACE_SCAN_DEPTH = 3;

	/** 见 {@link #backdropColorOf}：复用的可变坐标，避免每个图标每帧新建一个 {@code BlockPos}。 */
	private static final BlockPos.MutableBlockPos backdropPos = new BlockPos.MutableBlockPos();

	/**
	 * 见 {@link #drawHeightIndicator}：该生物脚下那格<b>地图色</b>，<b>并按当前光照压暗</b>。
	 *
	 * <p><b>为什么不是简单的"脚下一格"</b>：地图取色用的是 {@code WORLD_SURFACE}——最上面那个非空气
	 * 方块，而 {@code blockPosition().below()} 会漏掉"生物自己站进去的那半格"。雪原正是这种情形：地面是
	 * <b>雪层</b>（只有几像素高）加底下的草/土，生物站在雪层里，它所在的那一格是雪，再往下数一格却是土。
	 * 取到土就判成暗地形，于是"雪地一片白"却仍然画白箭头。所以这里与地图同口径：<b>从生物自己那一格开始
	 * 往下找第一格非空气、也非"看穿的装饰"的方块</b>（矮草、花、火把…），详见方法内的循环。
	 *
	 * <p><b>为什么用地图色</b>：它正是"这块地方在地图上看起来是什么颜色"的官方口径。地图本身按当前光照
	 * 合成，夜里/下雨会把整张图压下去（天空光衰减越强越暗，天气再叠一档），把同一份衰减作用到取到的颜色上，
	 * "地图整体变暗"时判据就自然落到"该用白箭头"那一侧，不必逐个情形枚举。每帧每个图标一次方块读取加几次
	 * 整数运算，开销可忽略。
	 */
	private static int backdropColorOf(Entity entity) {
		var level = entity.level();
		// 取"这一列在地图上看得见的那一格"。地图按 WORLD_SURFACE（最上面那个非空气方块）取色，
		// 箭头必须跟同一个口径：生物钻到地表之下时（洞穴、山坡背面），它脚下是石头、而地图上那一格
		// 是雪/草，按生物自己那一格取色就会"白地图上画白箭头"。
		BlockPos feet = entity.blockPosition();
		BlockPos top = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, feet);
		// 高度图在部分列会返回<b>未填充的世界底部值</b>（如 -64），远低于实体这一格的 Y：
		// 再往下扫只能扫到空气、取到全 0，于是站在草地的生物被误判成"暗地形"→ 画白箭头，
		// 与旁边判成亮地形的同类交错就是"深度指示一闪一闪（一会黑一会白）"。这种"地表在实体脚下很深处"
		// 的高度图是数据没写好，直接改用实体自己那一列往下扫。
		if (top.getY() < feet.getY() - SURFACE_SCAN_DEPTH) {
			top = feet;
		}
		int base = 0;
		// 与地图取样同一口径往下找"真正的地面"：跳过空气与矮草/高草/火把这类"看穿的装饰"。
		// <b>不跳装饰会踩 MapColor 的坑</b>：草方块是 {@code GRASS}（{@code 0x7FB238}，亮度 148），
		// 而矮草/高草是 {@code PLANT}（{@code 0x007C00}，亮度只有 73）——草原上生物大多站在矮草里，
		// 于是同一片绿色草原上会一半画深色箭头、一半画白色箭头。
		// 花也一并跳过：地图取样把它们按本色画（是地表），但它们的 MapColor 同样是 PLANT 深绿、
		// 并不代表脚下那片草地，拿来判明暗会让"站在花上的生物"在一片绿草原上画白箭头。
		for (int i = 0; i <= SURFACE_SCAN_DEPTH; i++) {
			backdropPos.set(top.getX(), top.getY() - i, top.getZ());
			BlockState state = level.getBlockState(backdropPos);
			if (!state.isAir() && !ClientChunkColorProvider.isSurfaceDecoration(state)
					&& !state.is(BlockTags.FLOWERS)) {
				base = state.getMapColor(level, backdropPos).col;
				break;
			}
		}
		float dim = 1.0F - Math.clamp(level.getSkyDarken() / SKY_DARKEN_MAX, 0.0F, 0.9F);
		if (level.isRaining() || level.isThundering()) {
			dim *= RAIN_DIM;
		}
		int r = (int) (((base >> 16) & 0xFF) * dim);
		int g = (int) (((base >> 8) & 0xFF) * dim);
		int b = (int) ((base & 0xFF) * dim);
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	/** 见 {@link #drawHeightIndicator}：按亮度判"亮地形"（整数运算，无浮点）。 */
	private static boolean isBrightColor(int argb) {
		int r = (argb >> 16) & 0xFF;
		int g = (argb >> 8) & 0xFF;
		int b = argb & 0xFF;
		return (r * 299 + g * 587 + b * 114) / 1000 > BRIGHT_LUMA_THRESHOLD;
	}

	/**
	 * 高度指示的上/下箭头（GUI 图集里的 sprite {@code sinanmap:height_up} / {@code sinanmap:height_down}，
	 * 源图见 {@code assets/sinanmap/textures/gui/sprites/}）。
	 */
	private static final Identifier HEIGHT_UP_TEXTURE =
			Identifier.fromNamespaceAndPath(SinanConstants.MOD_ID, "height_up");
	private static final Identifier HEIGHT_DOWN_TEXTURE =
			Identifier.fromNamespaceAndPath(SinanConstants.MOD_ID, "height_down");
	/**
	 * 见 {@link #drawHeightIndicator}：亮地形上的<b>深色</b>那一套。
	 *
	 * <p>与上面两张浅色图成对，四张都是普通 sprite，颜色写在 PNG 里（默认把浅色版按 12% 压暗得到），
	 * 资源包替换一张即可改样式。
	 */
	private static final Identifier HEIGHT_UP_DARK_TEXTURE =
			Identifier.fromNamespaceAndPath(SinanConstants.MOD_ID, "height_up_dark");
	private static final Identifier HEIGHT_DOWN_DARK_TEXTURE =
			Identifier.fromNamespaceAndPath(SinanConstants.MOD_ID, "height_down_dark");
	/** 箭头在图标里的绘制边长（GUI 像素），留 1 像素边距贴住右下角。 */
	private static final int HEIGHT_INDICATOR_SIZE = 7;

	/**
	 * 箭头额外往右下角让出的像素数：取 {@value #HEIGHT_INDICATOR_OFFSET}，让箭头外角微微探出图标框。
	 * 取 1 是因为再大就会明显压到相邻图标。
	 */
	private static final int HEIGHT_INDICATOR_OFFSET = 1;

	/**
	 * 把一个真头像按它自己的宽高比画到内框里。头像贴图本身就是"正视图"（见 {@link MobHeads}），所以
	 * <b>整张贴图只 blit 一次</b>。
	 *
	 * <p>尺寸预算的落点是"<b>长边</b>"：把头像塞进 {@code INNER}×{@code INNER} 的正方形，长边用满、
	 * 短边按比例缩，所以竖长（兔子/鹦鹉）的图标只纵向变高、不会挡住左右邻居。
	 *
	 * <p><b>不再套矩形描边</b>：头像贴图里已烘焙了一圈"贴着头形外扩 1 像素"的剪影描边（见 {@link MobHeads}），
	 * 再套方框就多出两条边。贴图用带颜色的 blit（{@code RenderPipelines.GUI_TEXTURED}）把 alpha/brightness
	 * 乘进颜色里，从而能整体变透/变暗。参数序（已用 javap 核对）：
	 * <pre>blit(管线, 贴图, x, y, u, v, 目标宽, 目标高, 源宽, 源高, 贴图宽, 贴图高, 颜色)</pre>
	 * u/v 从 0 取到整张贴图。
	 */
	private static void drawHead(GuiGraphicsExtractor graphics, MobHeads.Head head, float brightness, float alpha) {
		int texWidth = head.width();
		int texHeight = head.height();
		float aspect = (float) texWidth / texHeight;
		int width;
		int height;
		if (aspect >= 1.0F) {
			width = INNER;
			height = Math.max(1, Math.round(INNER / aspect));
		} else {
			height = INNER;
			width = Math.max(1, Math.round(INNER * aspect));
		}
		int left = -width / 2;
		int top = -height / 2;
		graphics.blit(RenderPipelines.GUI_TEXTURED, head.texture(), left, top, 0.0F, 0.0F,
				width, height, texWidth, texHeight, texWidth, texHeight,
				fadeColor(0xFFFFFFFF, brightness, alpha));
	}

	/**
	 * 按实体类型的碰撞箱尺寸给一个整体缩放：正常体型 1.0，越小越缩。
	 *
	 * <p><b>为什么按尺寸</b>：小体型生物（蠹虫、末影螨、蝙蝠、鸡、兔、鱼、蝌蚪……）在
	 * {@value #ITEM_ICON_SIZE} 像素、又缩到五六像素的图标上本来就小，按正常大小画会跟牛羊一样占地方。
	 * 给每个模型硬编码缩放值要逐个打补丁、还会漏掉幼年个体；这里改成<b>用碰撞箱尺寸驱动</b>的连续缩放。
	 *
	 * <p>头像画到屏幕上是一个<b>正视图</b>，占多大由横向宽窄决定，所以判据取宽×高（地面积）而不是最小的
	 * 一条边：海龟碰撞箱 1.2×0.4、蝾螈 0.75×0.42 横向一点都不小，按最小边（高度）判会被压到 0.5 / 0.7、
	 * 在地图上认不出来；而真正窄的蝙蝠 0.5、鹦鹉 0.5、蠹虫/末影螨 0.4 仍然照旧缩。
	 *
	 * <p><b>阈值</b>：≥{@value #SMALL_ENTITY_MIN_METRIC}（宽×高）算正常——原版人形/四足的标准宽度
	 * 就是 0.6，僵尸/骷髅/牛羊都在这一头，<b>完全不受这条判据影响</b>；
	 * {@value #SMALL_ENTITY_TINY_METRIC}~{@value #SMALL_ENTITY_MIN_METRIC} 之间线性地从
	 * {@value #SMALL_ICON_MIN_SCALE} 涨到 1.0；再小就取 {@value #SMALL_ICON_MIN_SCALE}。
	 *
	 * <p><b>狼/猫</b>虽然过得了这条判据（地面积 0.42~0.51），但它们还有
	 * {@link #SMALL_BIRD_BEAST_ICON_SCALE} 那一档，所以并不是"不受影响"。
	 *
	 * <p><b>每类型算一次</b>：用 {@code EntityType#getWidth} 等类型级常量，结果随头像一起缓存，每帧零成本。
	 */
	private static float iconScaleFor(EntityType<?> type, boolean baby) {
		// 判据用宽×高（地面积）而不是只看宽：同样 0.6 宽，村民/僵尸高 1.95、猫/狐狸只有 0.7，
		// 只看宽会把它们判成同一档。鱼类横截面细长，按地面积判会被压到最小档，直接给接近满尺寸的值。
		if (isFish(type)) {
			return baby ? FISH_ICON_SCALE * BABY_ICON_SCALE : FISH_ICON_SCALE;
		}
		float metric = type.getWidth() * type.getHeight();
		float scale = 1.0F;
		if (metric < SMALL_ENTITY_MIN_METRIC) {
			float t = (metric - SMALL_ENTITY_TINY_METRIC) / (SMALL_ENTITY_MIN_METRIC - SMALL_ENTITY_TINY_METRIC);
			t = Math.clamp(t, 0.0F, 1.0F);
			scale = SMALL_ICON_MIN_SCALE + (1.0F - SMALL_ICON_MIN_SCALE) * t;
		}
		// 鹦鹉、蝙蝠、蜜蜂的地面积和猫接近（0.42~0.45），但视觉上该明显更小，再额外缩一档。
		scale *= extraSmallFactor(type);
		// 鸡/猫这一族（小型鸟兽）在体型判据之上再压一档：它们画的是"整只/整个头"、把画布填满
		// （鸡、鹦鹉、蝙蝠都是整只画），按地面积判出来的档位看着仍比同档的猪牛羊抢眼。
		if (isSmallBirdOrBeast(type)) {
			scale *= SMALL_BIRD_BEAST_ICON_SCALE;
		}
		// 马/驴/骡那一族整身比"人那么高"的生物长得多，按 1.0 画出来明显大一圈，整族压一档。
		if (isHorseFamily(type)) {
			scale *= HORSE_ICON_SCALE;
		}
		// 牛/蘑菇牛：和猪/羊同一档，不再额外压
		if (isFarmAnimal(type)) {
			scale *= FARM_ANIMAL_ICON_SCALE;
		}
		// 羊：羊毛层是"比身体大 0.5 的壳"，会把头像画布填满（实测 784/1024 不透明），看起来比同档的
		// 猪/牛大一圈，在上一档之外再压一档。
		if (type == EntityType.SHEEP) {
			scale *= SHEEP_ICON_SCALE;
		}
		// 幻翼：地面积 0.45 会被小体型判据压到 0.71，但它是"一对大翅膀"的整只图标，拉到体大档。
		if (type == EntityType.PHANTOM) {
			scale = PHANTOM_ICON_SCALE;
		}
		// 守卫者：碰撞箱 0.85×0.85 落在正常档，但视觉上是带放射状棘刺的大方块，给到体大档。
		if (type == EntityType.GUARDIAN || type == EntityType.ELDER_GUARDIAN) {
			scale = GUARDIAN_ICON_SCALE;
		}
		// 监守者：Boss 级大型生物，按正常档偏小，给到体大档。
		if (type == EntityType.WARDEN) {
			scale = WARDEN_ICON_SCALE;
		}
		// 女巫：取景框被帽子撑大（帽子不裁，见 MobHeadRenderer），头在框里只占一小块，给一档放大补大小。
		if (type == EntityType.WITCH) {
			scale = WITCH_ICON_SCALE;
		}
		// 兔：地面积 0.20 落在最底档（0.70），比同样"巴掌大"的鸡/鱼更显眼，再压一档。
		if (type == EntityType.RABBIT) {
			scale *= RABBIT_ICON_SCALE;
		}
		// 幼体碰撞箱与成年同宽，不单独缩的话小牛小羊会和成年一样大，再小一档。
		return baby ? scale * BABY_ICON_SCALE : scale;
	}

	/** 见 {@link #iconScaleFor}：属于"体大"那一档的类型，直接给这个放大值（不再走小体型判据）。 */
	private static final float GUARDIAN_ICON_SCALE = 0.95F;

	/** 见 {@link #iconScaleFor}。 */
	private static final float PHANTOM_ICON_SCALE = 1.2F;

	/**
	 * 见 {@link #iconScaleFor}：监守者那对长耳朵撑大了取景框、头只占框里一小块，所以连图标一起放大，
	 * 头部实际大小才对得上"巨型生物"。试过 2.6 偏大，回到 {@value}。耳朵的完整性由取景保证
	 * （见 MobHeadRenderer"不裁监守者"），不靠档位。
	 */
	private static final float WARDEN_ICON_SCALE = 2.1F;

	/**
	 * 见 {@link #iconScaleFor}：女巫的取景框里有整顶帽子、头只占一小块，不收框（避免切口杂色）的前提下
	 * 只能靠这里把头画大。取 {@value}；要调大小改这一个数即可。
	 */
	private static final float WITCH_ICON_SCALE = 1.7F;

	/** 见 {@link #iconScaleFor}：兔在最小体型档之上再压一档。 */
	private static final float RABBIT_ICON_SCALE = 0.7F;

	/** 见 {@link #iconScaleFor}：马/驴/骡那一族整身太长，比起"人那么高"的生物要再压一档。 */
	private static final float HORSE_ICON_SCALE = 0.8F;

	/** 见 {@link #iconScaleFor}：马/驴/骡/骷髅马/僵尸马。 */
	private static boolean isHorseFamily(EntityType<?> type) {
		return type == EntityType.HORSE || type == EntityType.DONKEY || type == EntityType.MULE
				|| type == EntityType.SKELETON_HORSE || type == EntityType.ZOMBIE_HORSE;
	}

	/** 见 {@link #iconScaleFor}：猪/牛/羊/蘑菇牛整体再压一档（它们的头把画布填得太满）。 */
	private static final float FARM_ANIMAL_ICON_SCALE = 0.85F;

	/** 见 {@link #iconScaleFor}：猪/牛/羊/蘑菇牛。 */
	private static boolean isFarmAnimal(EntityType<?> type) {
		return type == EntityType.COW || type == EntityType.PIG
				|| type == EntityType.SHEEP || type == EntityType.MOOSHROOM;
	}

	/** 见 {@link #iconScaleFor}：羊的羊毛壳把画布填满，压一档让它和猪/牛看起来一样大。 */
	private static final float SHEEP_ICON_SCALE = 0.85F;

	/** 鱼类的固定缩放，见 {@link #iconScaleFor}。 */
	private static final float FISH_ICON_SCALE = 0.95F;

	/** 这四个是"整只横截面"画的鱼，见 {@link #iconScaleFor}。 */
	private static boolean isFish(EntityType<?> type) {
		return type == EntityType.COD || type == EntityType.SALMON
				|| type == EntityType.PUFFERFISH || type == EntityType.TROPICAL_FISH;
	}

	/** 见 {@link #iconScaleFor}：这几个体型在"地面积"上分不开，单独再压一档。 */
	private static float extraSmallFactor(EntityType<?> type) {
		// 鹦鹉 / 蝙蝠再压得比蜜蜂更狠一档
		if (type == EntityType.PARROT || type == EntityType.BAT) {
			return 0.75F;
		}
		if (type == EntityType.BEE) {
			return 0.88F;
		}
		return 1.0F;
	}

	/**
	 * 见 {@link #iconScaleFor}：<b>小型鸟兽</b>（鸡、猫、豹猫、狐狸、狼、鹦鹉、蝙蝠）在体型判据之上
	 * 再压一档，取 {@value}。要统一调这一族的观感只改这一个数。
	 *
	 * <p><b>为什么它们要单独一档</b>：这几个在取景框里就是"整个头/整只"、把画布填满
	 * （见 {@link MobHeads}：鸡、鹦鹉、蝙蝠都按整只画），地面积判据只看到 0.28~0.45，
	 * 算出来的档位与猪牛羊相近，画面上却明显更抢眼。
	 *
	 * <p><b>不含兔</b>：兔已有自己的 {@link #RABBIT_ICON_SCALE}，两者叠乘会掉到 2 像素多、糊成一团。
	 * <b>不含蜜蜂</b>：它已有 {@link #extraSmallFactor} 那一档，且"鸡猫类"说的是鸟兽。
	 *
	 * <p>这一档只会让<b>本来就 &lt; 1.0</b> 的类型更小，所以不会把谁从"正常体型"推进
	 * {@link #buildAvatar} 的"小体型"分支（那条分支决定退化时画菱形还是刷怪蛋）。
	 */
	private static final float SMALL_BIRD_BEAST_ICON_SCALE = 0.85F;

	/** 见 {@link #SMALL_BIRD_BEAST_ICON_SCALE}：鸡、猫、豹猫、狐狸、狼、鹦鹉、蝙蝠。 */
	private static boolean isSmallBirdOrBeast(EntityType<?> type) {
		return type == EntityType.CHICKEN || type == EntityType.CAT || type == EntityType.OCELOT
				|| type == EntityType.FOX || type == EntityType.WOLF
				|| type == EntityType.PARROT || type == EntityType.BAT;
	}

	/** 幼体图标的额外缩放，见 {@link #iconScaleFor}。 */
	private static final float BABY_ICON_SCALE = 0.82F;

	/** 掉落物图标的额外缩放，见 {@code drawAvatar} 里物品那一支。 */
	private static final float ITEM_ICON_SCALE = 0.75F;

	/**
	 * 要画成<b>菱形标记</b>的非活体：投射物（箭、火球、雪球…）与已点燃的 TNT。它们没有头像来源
	 * （头像只能从活体渲染器的模型来），但"有东西飞过来 / 这里马上要炸"值得在地图上看见。
	 */
	private static boolean isMarkerEntity(Entity entity) {
		return entity instanceof Projectile || entity instanceof PrimedTnt;
	}

	/** 见 {@link #isMarkerEntity}：投射物 / 点燃的 TNT 的菱形颜色（紫）。 */
	private static final int MARKER_PURPLE = 0xFFC060FF;

	/** 未知实体（其他模组加的生物等）没有头像时的菱形颜色：友好 / 中立 = 黄。 */
	private static final int MARKER_FRIENDLY = 0xFFFFD23F;

	/** 见 {@link #MARKER_FRIENDLY}：敌对（{@code MONSTER}）= 红。 */
	private static final int MARKER_HOSTILE = 0xFFFF5A5A;

	/** 见 {@link #MARKER_FRIENDLY}：按"是不是敌对生物"分黄/红。 */
	private static int unknownMarkerColor(EntityType<?> type) {
		return type.getCategory() == MobCategory.MONSTER ? MARKER_HOSTILE : MARKER_FRIENDLY;
	}

	/**
	 * 见 {@link #isMarkerEntity}：非活体标记按<b>类型</b>缓存一颗紫菱形。
	 *
	 * <p>它们没有变种，所以不进 {@code avatars} 那套，否则每帧都会重新造一颗。
	 */
	private Avatar markerAvatarFor(EntityType<?> type) {
		Avatar cached = markerAvatars.get(type);
		if (cached == null) {
			cached = new Avatar(null, null, MARKER_PURPLE, 1.0F);
			markerAvatars.put(type, cached);
		}
		return cached;
	}

	/** 把亮度乘到 RGB、把 alpha 乘到 A 上（用于高度视觉区分）。命中路径上无分配。 */
	private static int fadeColor(int argb, float brightness, float alpha) {
		int a = (int) (((argb >>> 24) & 0xFF) * alpha);
		int r = (int) (((argb >>> 16) & 0xFF) * brightness);
		int g = (int) (((argb >>> 8) & 0xFF) * brightness);
		int b = (int) ((argb & 0xFF) * brightness);
		return a << 24 | r << 16 | g << 8 | b;
	}

	/**
	 * 取头像，没有就现算一次并缓存。<b>按 (类型, 变种键, 装备) 查表</b>（见 {@link MobHeadKey}），
	 * 命中缓存时不含任何装箱/分配——键是复用的一只可变实例，只在"确实要入表"时才拷贝一份快照。
	 *
	 * <p>返回 {@code null} 表示"这个变种的首张真实头像正在离屏渲染中"（见 {@link MobHeads}）：这时
	 * <b>不写缓存</b>，下一帧再来问——写入就等于把"暂时没有"钉成"永远没有"。只有<b>终态</b>
	 * （拿到头像 / 确定做不出来）才落进 {@code avatars}。
	 */
	private Avatar avatarFor(Entity entity) {
		EntityType<?> type = entity.getType();
		// 非活体（投射物 / 点燃的 TNT）：没有变种、也不会有头像，直接一颗紫菱形，按类型缓存
		if (!(entity instanceof LivingEntity living)) {
			return markerAvatarFor(type);
		}
		MobHeadKey key = MobHeadKey.of(living);
		TypeAvatars holder = avatars.get(type);
		if (holder != null) {
			Avatar cached = holder.get(key);
			if (cached != null) {
				return cached;
			}
		}
		Avatar created;
		try {
			created = buildAvatar(type, living, key);
		} catch (RuntimeException e) {
			// 兜底：解析头像万一炸了也绝不能"整只不画"。退化成黄/红菱形并缓存，否则每帧都会对同一变种
			// 反复抛异常、连累后面还没画到的实体（"部分生物根本没出来"的一种成因）。
			created = new Avatar(null, null, unknownMarkerColor(type), 1.0F);
		}
		if (created == null) {
			return null;
		}
		if (holder == null) {
			holder = new TypeAvatars();
			avatars.put(type, holder);
		}
		// 入表的必须是不可变快照：查表用的那只实例每帧都在变
		holder.put(key.copy(), created);
		return created;
	}

	/**
	 * 取掉落物的图标：按<b>物品</b>缓存，第一次遇到这件物品时拷贝一份它当时的 {@code ItemStack} 常驻
	 * （原因见 {@link #itemIcon}），之后每帧只做一次身份查表。
	 */
	private Avatar avatarForItem(ItemEntity entity) {
		Item item = entity.getItem().getItem();
		Avatar cached = itemAvatars.get(item);
		if (cached != null) {
			return cached;
		}
		Avatar created = new Avatar(null, itemIcon(entity.getItem()), 0, 1.0F);
		itemAvatars.put(item, created);
		return created;
	}

	/**
	 * 建一个物品图标：拷贝这份栈。组件决定图标的物品（药水颜色、附魔书、旗帜图案……）必须用<b>真实掉的
	 * 那一份</b>，凭空造一个空组件的栈可能根本解析不出图标。只在"这件物品第一次出现"时调用，不在每帧路径上。
	 */
	private static ItemStack itemIcon(ItemStack stack) {
		return stack.copy();
	}

	/**
	 * 第一次遇到某<b>变种</b>时解析头像：<b>小体型判定 → 真头像 → 刷怪蛋 → 菱形</b>，逐级退化。
	 * 要一个实体实例（而不是只有类型）是因为贴图、模型、变种都要从实体/渲染状态拿（见
	 * {@link MobHeads#resolve}）。{@link MobHeads#resolve} 返回 {@code FAILED} 时<b>不会返回"什么都没有"</b>，
	 * 而是继续退化到刷怪蛋、再到菱形，保证每只候选都至少画出一个标记；未知实体（其他模组加的生物等）
	 * 落在这里，黄=友好、红=敌对（见 {@link #unknownMarkerColor}）。
	 *
	 * <p><b>也可能返回 {@code null}</b>：表示"首张真实头像正在离屏渲染中"
	 * （{@link MobHeads.Resolution#PENDING}）。这是<b>中间态</b>、不是退化结果，调用方（见 {@link #avatarFor}）
	 * <b>不缓存</b>它、这一帧先不画、下一帧再来问。
	 *
	 * <p><b>小体型怎么衔接</b>：抠到脸 → 画缩小版；抠不到脸 → 直接<b>菱形</b>、<b>跳过刷怪蛋</b>
	 * （模型里一个可用面都没有的极端情况，蛋图标缩到三四像素同样认不出，菱形更能表达"这里有东西、是友是敌"）；
	 * 正常体型 → 真头像 → 刷怪蛋 → 菱形。体型是<b>最高优先级</b>分支（类型的固有属性，一次算好随头像缓存），
	 * 只改写"画什么 / 画多大"，不改三级退化内部的相对次序。
	 *
	 * @param key 变种键（{@link MobHeadKey#LOOKUP} 那只复用实例）
	 * @return 该变种的头像（终态）；{@code null} 表示"正在渲染中，这一帧先别画、也别缓存"
	 */
	private static Avatar buildAvatar(EntityType<?> type, LivingEntity entity, MobHeadKey key) {
		float iconScale = iconScaleFor(type, entity.isBaby());
		boolean small = iconScale < 1.0F;
		MobHeads.Resolution resolution = MobHeads.resolve(entity, key);
		if (resolution.head() != null) {
			return new Avatar(resolution.head(), null, 0, iconScale);
		}
		if (resolution.pending()) {
			// 离屏渲染还没出结果：不缓存、不退化成刷怪蛋，下一帧再问一次
			return null;
		}
		if (small) {
			return new Avatar(null, null, unknownMarkerColor(type), iconScale);
		}
		Optional<Holder<Item>> egg = SpawnEggItem.byId(type);
		if (egg.isPresent()) {
			return new Avatar(null, itemIcon(new ItemStack(egg.get())), 0, 1.0F);
		}
		return new Avatar(null, null, unknownMarkerColor(type), 1.0F);
	}

	/**
	 * 画一颗<b>菱形标记</b>：既没有真头像、也没有物品图标时的最后一档。
	 *
	 * <p>用菱形而不是方形色块，是因为方色块和真头像一样都是"方块"、看起来像"头像没画出来"，菱形一眼
	 * 就知道是<b>标记</b>；尺寸也小一档（见 {@link #MARKER_DIAMOND_SIZE}），不跟真头像抢地方。
	 *
	 * <p><b>用行填充拼，不用贴图</b>：曾经用 {@link #MARKER_DIAMOND_TEXTURE}（白芯+深边的 11×11 贴图）
	 * + {@code blitSprite} 着色。贴图在小尺寸上会被线性过滤把四个斜角淡化成圆角，缩到三四个像素时
	 * 直接糊成一块<b>方形</b>。这里改成按行 {@code fill}：每一行横跨几格
	 * 由离中心的距离决定，画出的是像素级的锐利菱形，任何尺寸都不糊、字形也不随分辨率变。
	 *
	 * <p>颜色用在<b>本体</b>上（配合高度渐变的 {@code fadeColor}），描边固定 {@link #DOT_OUTLINE}
	 * 一圈、不参与渐变（与旧贴图行为一致）。
	 *
	 * @param size 绘制边长（图标坐标，满框是 {@value #ICON_HALF} 的两倍）；见 {@link #MARKER_DIAMOND_SIZE}
	 *             与 {@link #DIAMOND_MODE_ICON_SIZE}——两处用不同大小，菱形档画得更大、单独标记画得小
	 */
	private static void drawDiamond(GuiGraphicsExtractor graphics, int color, int size) {
		// 描边先画（在下层）：整颗大一圈的菱形用描边色铺，本体再缩一圈盖上去。
		// 本体缩 2 像素（每边 1），描边恰好露出 1 像素一圈。
		drawDiamondRows(graphics, size, DOT_OUTLINE);
		drawDiamondRows(graphics, Math.max(1, size - 2), color);
	}

	/**
	 * 按行画一颗实心菱形（以原点为中心）。
	 *
	 * <p>第 {@code row} 行的宽度 = {@code size} - 离中心行距离的两倍（约）：中间行最宽、上下两端收成一点，
	 * 于是整颗是 45° 直边的真菱形。逐行用 1 像素高的矩形拼，落点全在整像素上，缩放多少都保持笔直。
	 */
	private static void drawDiamondRows(GuiGraphicsExtractor graphics, int size, int color) {
		if (size <= 0) {
			return;
		}
		double center = (size - 1) / 2.0;
		for (int row = 0; row < size; row++) {
			double distance = Math.abs(row - center);
			int width = Math.max(1, size - (int) Math.round(2.0 * distance));
			int left = -width / 2;
			int y = row - (int) center;
			graphics.fill(left, y, left + width, y + 1, color);
		}
	}

	/** 见 {@link #drawDiamond}：曾经的菱形贴图。改为几何绘制后不再使用，保留常量避免误删资源。 */
	@SuppressWarnings("unused")
	private static final Identifier MARKER_DIAMOND_TEXTURE =
			Identifier.fromNamespaceAndPath(SinanConstants.MOD_ID, "marker_diamond");

	/**
	 * 单独标记（投射物 / 点燃的 TNT / 未知实体）那颗菱形的绘制边长（图标坐标，满框是
	 * {@value #ICON_HALF} 的两倍）。
	 *
	 * <p><b>10 → 8 → {@value}</b>：几何绘制后不再受贴图降采样限制，可以再收一档。
	 * 落到屏幕上（图标满框为 {@value #ICON_GUI_PIXELS} GUI 像素）约 3 个 GUI 像素，仍一眼可辨。
	 */
	private static final int MARKER_DIAMOND_SIZE = 6;

	/**
	 * "无图标（菱形）"档下每只生物那颗菱形的绘制边长。
	 *
	 * <p>10 → 8 → {@value}：收到 8 之后仍偏小，于是定为 {@value}——它要在整片雷达上
	 * 一眼看出"哪里有东西"，太小就与噪点无异。几何绘制（不是贴图）所以放大也不会糊。
	 */
	private static final int DIAMOND_MODE_ICON_SIZE = 10;
}
