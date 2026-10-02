package sinanmap.client.radar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HeadedModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartNames;
import net.minecraft.client.model.monster.dragon.EnderDragonModel;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.EnderDragonRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sinanmap.common.SinanConstants;

/**
 * 生物头像的来源：把生物交给<b>游戏自己的渲染管线</b>离屏画一遍，回读成一张小图标，
 * 再交给上层用普通 GUI 贴图画。每个 (类型, 变种) 只解析一次、结果缓存，每帧路径上零计算、零分配。
 *
 * <p><b>只有一条渲染路：离屏渲染，不带软件光栅化</b>：
 * <ol>
 *   <li>实现一个 {@link net.minecraft.client.renderer.SubmitNodeCollector}，让原版渲染器把这一帧要画的
 *       <b>所有渲染层</b>（主模型 + 羊毛/项圈/发光眼/斑纹/马铠/护甲/雪层…）逐层提交出来，
 *       记下每一层 <b>(模型, RenderType, 颜色)</b>，再用那一层自己的 {@code RenderType} 把"头像区域"
 *       画进一张离屏画布，回读成像素、超采样降采样、外扩描边，注册成贴图（见 {@link MobHeadRenderer}）。</li>
 *   <li><b>头像区域分两档</b>：模型里有"头"的（Head/HeadedModel/部分模型改名，见 {@link #headPartOf}）
 *       画<b>整个"头组"子树</b>（见 {@link #iconRegion}）——耳朵、犄角、喙、吻、鳃、冠都在里面，
 *       身体/腿/尾一个都不收；模型里没有"头"或类型在 {@link #WHOLE_ENTITY} 白名单里的（蜜蜂、鱿鱼、
 *       史莱姆、岩浆怪、蝙蝠、鸡、鱼、鹦鹉…）整只画，"头就是身体"或头小得认不出。</li>
 *   <li><b>画不出来的退化链</b>：离屏不可用/无可用渲染器/贴图读不到 → {@link Resolution#FAILED}，
 *       由调用方退化成<b>刷怪蛋物品图标 → 黄/红菱形标记</b>（见 {@code EntityRadar}）；任何一级失败
 *       都不会变成"整只不画"。那条 CPU 投影 + z-buffer 的软件几何光栅化路质量不稳（常把面部压扁、
 *       特征丢失），已整体删除。</li>
 * </ol>
 *
 * <p><b>姿势：静态、成年、正面</b>：{@code createRenderState} 出来的渲染状态立刻按
 * {@link #neutralize} 归零（动画归零、{@code isBaby=false}、不渲染名牌/轮廓），
 * {@link MobHeadRenderer} 再按 {@code resetPose() → setupAnim(state)} 摆一次姿势，
 * 于是所有生物的图标都是同一种"成年、正面、静止"的站姿，幼年个体与成年同图。
 *
 * <p><b>坐标系与朝向</b>：先把头自身这一帧的姿态取逆（{@code translateAndRotate} 的逆），
 * 于是画出来的是<b>设计上的正面姿态</b>；整条链上有且只有一次 Y 镜像、且放在缩放里
 * （投影那边不镜像），画面、绕序、法线三者同时正确，上下朝向再由回读时翻一次行序补回来，
 * 所以头顶在上沿、正脸朝观察者。改动渲染数学前先读 {@link MobHeadRenderer} 类注释。
 *
 * <p><b>缩放与居中</b>：度量用"该部位自己最大的那个立方体"（头盒）：锚点 X 取头的枢轴、
 * Y/Z 取头盒中心，跨度取头盒的 X/Y 长边；耳朵/犄角/鬃再大也不会把脸推出画面中心或压小。
 * 画布是固定正方形、不裁透明边（屏幕上的头像因此始终是同样大小的方框，头在框里占的比例一致）；
 * 整只那一档以整棵子树的包围盒为准。
 *
 * <p><b>超采样降采样</b>：画布边长 = 图标长边 × 超采样倍率（{@code MobHeadRenderer} 里两者相乘），
 * 回读时按 alpha 加权做盒式平均缩回 {@value #ICON_LONG_SIDE}，缩小时的锯齿基本消失。回读是异步的
 * （隔一两帧才拿到像素），{@link Resolution#PENDING} 表达的就是这个中间态，调用方<b>不要缓存</b>它。
 *
 * <p><b>导出 PNG 调试开关</b>：默认关闭的系统属性 {@code -Dsinanmap.mobhead.dump=true} 打开后，
 * 每生成一个变种就把 {@link NativeImage} 原图写到
 * {@code <游戏目录>/mobhead_dump/<实体类型>_<变种>.png}（开发环境即 {@code run/mobhead_dump/}）。
 * 关闭时只有一次静态布尔判断；写盘只在"该变种第一次解析"时发生、不在每帧路径上，
 * 且整段被 try/catch 包住，失败只记一次日志。
 *
 * <p><b>贴图与生命周期</b>：每变种一张 {@link DynamicTexture}，采样器是<b>两向线性 + CLAMP_TO_EDGE</b>
 * （{@link HeadTexture}），雷达里每个生物只做<b>一次 blit</b>。贴图<b>绝不允许在一帧内被关掉</b>
 * （本仓库有明确约定），而头像缓存"按<b>变种</b>只解析一次、跨世界不失效"、没有合适的释放时机，
 * 所以这些贴图<b>保留到进程结束</b>：每变种一张、边长 {@value #ICON_LONG_SIDE} 像素，
 * 最多 32×32×4 = 4 KB；原版实体约 170 种、每类型最多 32 个变种，上界仍是几 MB 量级
 * （真实游戏里远少于上限），有界且很小。
 *
 * <p>整个流程只在渲染线程、每个变种跑一次，每帧路径上<b>零计算、零分配</b>。
 */
final class MobHeads {

	private static final Logger LOGGER = LoggerFactory.getLogger(SinanConstants.MOD_ID);

	/** 解析失败只在日志里说一次，避免刷屏。 */
	private static boolean warnedFailure;

	/**
	 * 是否把生成的头像原图导到磁盘（调试用）。
	 *
	 * <p>由系统属性 {@code -Dsinanmap.mobhead.dump=true} 打开，<b>默认关闭</b>。关闭时它只是一个
	 * 静态 final 布尔，用到它的地方都只是"一次布尔判断"，不建目录、不拼路径、不碰 IO，<b>零开销</b>。
	 */
	static final boolean DUMP_ENABLED =
			Boolean.parseBoolean(System.getProperty("sinanmap.mobhead.dump", "false"));

	/** 导出失败只在日志里说一次（同理，别刷屏）。 */
	private static boolean warnedDump;

	/**
	 * 图标长边的像素预算。
	 *
	 * <p>图标最终在小地图上只有 7 像素上下、世界地图上 4~9 像素，{@value #ICON_LONG_SIDE} 已明显
	 * 超过屏幕实际分辨率，再大只是浪费（每类型一张、又永不释放）。包级可见：离屏渲染那条路
	 * （{@link MobHeadRenderer}）复用同一个预算做画布，图标尺寸才能一致；画布是<b>固定正方形</b>。
	 */
	static final int ICON_LONG_SIDE = 32;

	/**
	 * 导出目录（<b>相对游戏运行目录</b>）。开发环境里游戏目录就是 {@code run/}，所以只写
	 * {@code mobhead_dump}，最终落在 {@code run/mobhead_dump/}（写 {@code run/mobhead_dump}
	 * 会拼成 {@code run/run/mobhead_dump}）。
	 */
	private static final String DUMP_DIRECTORY = "mobhead_dump";

	/**
	 * 一个头像：图标贴图 + 它的像素尺寸。
	 *
	 * <p>{@code width}/{@code height} 是离屏画布（固定正方形 {@value #ICON_LONG_SIDE}×
	 * {@value #ICON_LONG_SIDE}）。上层按像素数 1:1 贴，所以屏幕上所有头像都是同样的方框大小。
	 */
	record Head(Identifier texture, int width, int height) {
	}

	/**
	 * {@link #resolve} 的结论：三态，缺一不可。
	 *
	 * <ul>
	 *   <li>{@code head != null}：<b>拿到了</b>。终态，调用方可以缓存。</li>
	 *   <li>{@link #PENDING}：<b>正在渲染中</b>（已排队或已发起离屏渲染、等回读）。这<b>不是</b>终态，
	 *       调用方<b>不要缓存</b>它，下一帧再来问一次；否则就会把"暂时没有"钉成"永远没有"。</li>
	 *   <li>{@link #FAILED}：<b>确定做不出来</b>。也是终态，调用方可以缓存退化结果（刷怪蛋/菱形）。</li>
	 * </ul>
	 *
	 * <p>{@link #PENDING} 与 {@link #FAILED} 是共享单例：每帧重复询问只返回同一个对象，
	 * <b>不产生任何分配</b>（每帧零分配这条约定在抽取期不能破）。
	 */
	record Resolution(Head head, boolean pending) {

		/** 正在离屏渲染中：还没有终态（见 {@link MobHeadRenderer}）。 */
		static final Resolution PENDING = new Resolution(null, true);

		/** 确定做不出来：离屏这条路走不通，由上层画刷怪蛋/菱形。 */
		static final Resolution FAILED = new Resolution(null, false);
	}

	/**
	 * "整只图标"白名单：这些生物只画一个头认不出来——头太小（蝙蝠）、头与身体本来就是一团
	 * （史莱姆/岩浆怪）、或者头就是整只（鱼/鱿鱼/蜜蜂/鹦鹉），所以整只画。名字取自原版类型常量本身。
	 *
	 * <p><b>鹦鹉与蜜蜂不在这一档</b>：整只画会把<b>脚和翅膀</b>一起带进来，而两者模型里都有
	 * {@code head}（鹦鹉带喙、蜜蜂带触须与复眼），按"画头"走就没有脚和翅膀。
	 *
	 * <p>模型里根本<b>找不到"头"部位</b>的类型也会在 {@link #resolve} 里自动转成"整只"，同样覆盖。
	 */
	private static final Set<EntityType<?>> WHOLE_ENTITY = Set.of(
			EntityType.COD, EntityType.DOLPHIN, EntityType.NAUTILUS, EntityType.ZOMBIE_NAUTILUS,
			// 守卫者/远古守卫者：它的"头"只是一颗方块，触肢（棘刺）是同级部件。
			// 只画头会导致触肢全丢，因此整只画。
			EntityType.GUARDIAN, EntityType.ELDER_GUARDIAN,
			// 马类：整只画 + 取景对头（见 frameOnHead），得到"头 + 下身身体"的正视图
			EntityType.HORSE, EntityType.DONKEY, EntityType.MULE,
			EntityType.SKELETON_HORSE, EntityType.ZOMBIE_HORSE,
			EntityType.MAGMA_CUBE, EntityType.PARROT, EntityType.PHANTOM, EntityType.PUFFERFISH,
			EntityType.SALMON, EntityType.SLIME, EntityType.TADPOLE, EntityType.TROPICAL_FISH);

	/**
	 * "画整只、但取景对着头"的类型：它们的头只是一个方块、单看不像（海豚没有脖子，{@code head}
	 * 就是身体最前面那一段），所以整只画出全貌，但<b>把画布对焦在头那一小块上、再放大取景范围</b>。
	 * 取景范围见 {@code MobHeadRenderer#HEAD_FRAME_EXPAND}。
	 */
	static boolean frameOnHead(EntityType<?> type) {
		// 鳕鱼试过、已回退：它的 {@code head} 只有 2×4×3 格，按"对头取景"会把画面缩到那 2 格上。
		// 马类（马/驴/骡/骷髅马/僵尸马）：画成"头 + 下身身体（正视）"；只画"头组"时鬃与吻会糊在图标上，
		// 整只画、取景对头后画面正好是"头 + 下方前半身"，四肢被画布边界裁掉。
		return type == EntityType.DOLPHIN
				|| type == EntityType.HORSE || type == EntityType.DONKEY || type == EntityType.MULE
				|| type == EntityType.SKELETON_HORSE || type == EntityType.ZOMBIE_HORSE;
	}

	/**
	 * 取景用<b>整个"头区域"的包围盒</b>（而不是从里面挑一个立方体）的类型。
	 *
	 * <p>默认那条路（{@code measureIconBox}）之所以要"挑一个立方体"，是为了对马/驴/骡这种
	 * {@code head_parts} 里混着长鬃的模型只框住脸、不把鬃带进来；下面这些类型不适合——它们的关键部件
	 * （骆驼的脖子、各类耳朵/角/头饰）是 head 的子部件或与 head 同盒，挑单个立方体会丢件。
	 *
	 * <p>骆驼：{@code head} 是"吻 6×5×6 + 头 8×14×7 + 脖 8×8×19"三个立方体堆在一起，挑任何一个都
	 * "有头没脖 / 有脖没头"，框整棵子树才都在，且成年/幼体自动一致。
	 */
	static boolean frameWholeRegion(EntityType<?> type) {
		// 骆驼尸壳与骆驼同模型同造型，与骆驼保持一致
		if (type == EntityType.CAMEL || type == EntityType.CAMEL_HUSK) {
			return true;
		}
		// 猪灵 / 猪灵蛮兵 / 僵尸猪灵 / 监守者：耳朵是 head 的<b>子部件</b>、向两侧伸出，
		// 按 head 立方体取景不含耳朵、会被画布裁掉，框整棵子树才算进跨度。
		return type == EntityType.PIGLIN || type == EntityType.PIGLIN_BRUTE
				|| type == EntityType.ZOMBIFIED_PIGLIN || type == EntityType.WARDEN
				// 铜傀儡：区域是"头(8×5×10) + 三个更小的立方体"，按头立方体取景会裁掉头顶部件。
				|| type == EntityType.COPPER_GOLEM
				// 疣猪兽 / 僵尸疣猪兽：耳朵、角都伸出头盒，且 head 是"脸+长吻合一"的长盒，
				// 只框那个立方体会得到一条扁横条。
				|| type == EntityType.HOGLIN || type == EntityType.ZOGLIN
				// 山羊：角（{@code left_horn}/{@code right_horn}）是 head 的子部件、伸出头盒不少。
				|| type == EntityType.GOAT
				// 女巫（帽子）与沼骸（头顶的蘑菇）：头饰同样伸出头盒。
				|| type == EntityType.WITCH || type == EntityType.BOGGED;
	}

	/**
	 * 框整个区域之后是否还要"把脖子压短"（只对骆驼这一类长脖子模型）。
	 * 与 {@link #frameWholeRegion} 分开，是因为它带"取景框整体上移"的副作用——
	 * 猪灵/监守者只要"把耳朵算进跨度"，不需要也不能跟着上移。
	 */
	static boolean trimNeck(EntityType<?> type) {
		return type == EntityType.CAMEL || type == EntityType.CAMEL_HUSK;
	}

	/**
	 * 渲染时要从"区域子树"里<b>藏起来</b>的子部件（按部件名子串匹配）。
	 *
	 * <p>快乐恶魂：九根触肢是 {@code body} 的<b>子部件</b>（普通恶魂的触肢是兄弟节点，选 {@code body}
	 * 就排除了；快乐恶魂不行），而 {@code ModelPart#render} 按整棵子树提交，换部件名解决不了。
	 * 做法：渲染前把命中的子部件 {@code visible} 置 false，画完立刻还原——只借用原版自己的可见性开关。
	 */
	private static final Map<EntityType<?>, String[]> HIDDEN_PARTS = Map.of(
			EntityType.HAPPY_GHAST, new String[]{
					"tentacle0", "tentacle1", "tentacle2", "tentacle3", "tentacle4",
					"tentacle5", "tentacle6", "tentacle7", "tentacle8"},
			// 河豚：没鼓起时用的 {@code PufferfishSmallModel} 把眼睛做成两个独立的 1×1×1 方块，
			// 位置压在身体上沿、贴图上是<b>纯黑</b>，侧视图标里就是"鱼身上方多出一个黑方块"。
			// 眼睛在 6 像素图标上本来也读不出，直接藏掉（鼓起的"中/大"模型没有这两个部件）。
			EntityType.PUFFERFISH, new String[]{"left_eye", "right_eye"});

	/**
	 * 见 {@link #HIDDEN_PARTS}：把区域内名字命中的子部件藏起来。
	 *
	 * <p>用 {@code ModelPart#getChild(名字)} 逐个取（{@code children} 字段私有、遍历不了）。名字
	 * <b>写死成实测到的那 9 个</b>，不要用"前缀+序号"去探：取出不存在的名字会抛异常，异常冒到
	 * 渲染循环里会把这个变种判成渲染失败（表现是"头像变成了刷怪蛋"）。
	 *
	 * @return 被改动的部件列表，调用方<b>必须</b>在画完后交给 {@link #restoreHidden} 还原
	 */
	static List<ModelPart> hideExcluded(EntityType<?> type, ModelPart region) {
		String[] names = HIDDEN_PARTS.get(type);
		if (names == null) {
			return List.of();
		}
		List<ModelPart> hidden = new ArrayList<>();
		for (String name : names) {
			// 先问 hasChild：names 是按"某一个状态下的模型"写死的（河豚的眼睛只存在于未鼓起那一档），
			// 而 getChild 取不到会抛异常、冒到渲染循环里把这个变种判成失败。
			ModelPart child = region.hasChild(name) ? region.getChild(name) : null;
			if (child != null && child.visible) {
				child.visible = false;
				hidden.add(child);
			}
		}
		return hidden;
	}

	/** 见 {@link #hideExcluded}：把隐藏过的部件还原成可见。 */
	static void restoreHidden(List<ModelPart> hidden) {
		for (ModelPart part : hidden) {
			part.visible = true;
		}
	}

	/**
	 * 要按<b>立方体</b>（而不是部件）筛掉多余面片的类型。<b>目前是空的</b>——机制留着备用。
	 *
	 * <p>曾把嘎吱怪物放进来，按"有一轴厚度为 0"筛掉它 {@code head} 里的手臂平板，<b>那是误判</b>：
	 * 分支是它的特征、要留，要修的是杂色。所以恢复成空集（见 {@link #hasCubeTrim} 的调用点）。
	 */
	private static final Set<EntityType<?>> FLAT_CUBE_TRIM = Set.of();

	/** 见 {@link #FLAT_CUBE_TRIM}：这个类型需不需要走"逐立方体"提交（而不是整棵子树一次提交）。 */
	static boolean hasCubeTrim(EntityType<?> type) {
		return FLAT_CUBE_TRIM.contains(type);
	}

	/** 见 {@link #FLAT_CUBE_TRIM}：这个立方体是不是要按"零厚度"筛掉。 */
	static boolean trimFlatCube(EntityType<?> type, ModelPart.Cube cube) {
		if (!FLAT_CUBE_TRIM.contains(type)) {
			return false;
		}
		return cube.minX == cube.maxX || cube.minY == cube.maxY || cube.minZ == cube.maxZ;
	}

	/**
	 * 取景是否按"最接近正方体"的立方体（而不是体积最大）来选。目前<b>全关</b>：
	 * 骆驼已改走 {@link #frameWholeRegion}，而这条规则试过全局会弄坏羊驼成年与幼年骆驼。
	 */
	static boolean preferFaceCube(EntityType<?> type) {
		return false;
	}

	/**
	 * "找头"的第 3 档：少数模型把自己的"头"命名成别的名字（史莱姆的 {@code cube}、
	 * 岩浆怪的 {@code inside_cube}、凋灵的 {@code center_head}…）。按顺序试，取第一个"确实有几何"的。
	 *
	 * <p><b>刻意不收 {@code body}</b>：把"身体"当成"头"会把图标画成只有躯干，反而不像；
	 * 模型里真的找不到头时 {@link #resolve} 会按"整只"处理——那才是正确的退化方向。
	 */
	private static final String[] FALLBACK_HEAD_NAMES = {"cube", "inside_cube", "center_head"};

	/**
	 * 解析一个生物的头像，返回三态结论（见 {@link Resolution}）。<b>只有一条来源：离屏渲染</b>
	 * （{@link MobHeadRenderer}）。
	 *
	 * <p>顺序是"<b>先查缓存/在飞结果，再排队</b>"：先问 {@link MobHeadRenderer} 有没有该<b>变种</b>
	 * （见 {@link MobHeadKey}）的终态/在飞结果，有就当场回答——这一档只有一次哈希查表，<b>既不必解析
	 * 渲染器、也不必读贴图</b>，所以"正在渲染中"的变种每帧被问一次也不产生分配；没试过才去解析
	 * 渲染器、渲染状态、模型、贴图，决定"画头还是画整只"（{@link #iconRegion} / {@link #WHOLE_ENTITY}，
	 * 模型里没有头也按整只算），排进离屏渲染队列并返回 {@link Resolution#PENDING}（异步）。
	 *
	 * <p>{@link Resolution#FAILED} 只有两种：模型/贴图拿不到（玩家皮肤这种非资源贴图、既不是活体
	 * 渲染器、又不在 {@link #modelOf} 表里的实体），以及离屏这条路不可用（不在渲染线程／初始化失败）；
	 * 之后由调用方的退化链（刷怪蛋 → 菱形）接手。
	 *
	 * <p>用实体实例去取渲染器与渲染状态：贴图（尤其是带变种的颜色/花纹）只有
	 * {@code createRenderState(entity, 0)} + {@code getTextureLocation(state)} 才算得准。
	 * 任何 {@link RuntimeException} 都在这里被吃掉并返回 {@link Resolution#FAILED}——"解析失败"
	 * 绝不允许变成"整只不画"。
	 */
	static Resolution resolve(LivingEntity entity, MobHeadKey key) {
		EntityType<?> type = entity.getType();
		try {
			Resolution offscreen = MobHeadRenderer.result(key);
			if (offscreen != null) {
				return offscreen;
			}
			if (MobHeadRenderer.pending(key)) {
				return Resolution.PENDING;
			}

			EntityRenderer<?, ?> renderer =
					Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
			Model<?> model = modelOf(type, renderer);
			if (model == null) {
				return Resolution.FAILED;
			}
			// createRenderState 之后立刻归零：贴图与姿势都按"静态、成年、正面"来
			EntityRenderState state = renderStateOf(renderer, entity);
			Identifier texture = textureOf(type, renderer, state);
			if (texture == null) {
				return Resolution.FAILED;
			}

			// 白名单里的、或模型里根本找不到"头"的，一律整只；其余画"头像区域"
			// （头 + 平级"头组"部位，见 {@link #iconRegion}）。
			boolean whole = WHOLE_ENTITY.contains(type) || iconRegion(type, model) == null;

			// 唯一来源：交给游戏自己的渲染管线画"这一帧所有渲染层"（异步，见 MobHeadRenderer）。
			// 贴图必须能从资源包读到才能离屏渲染（玩家皮肤这类动态贴图会被原版画成"缺失贴图"格子），
			// 读不到就 FAILED、由退化链画刷怪蛋/菱形。玩家例外：皮肤动态注册、走不了资源包检查，
			// 但离屏用的是那一层自己的 RenderType，动态贴图照样能画，所以类型是玩家就放行；
			// started() 挡住同一变种的重复请求。
			if (!MobHeadRenderer.started(key)
					&& (loadable(texture) || entity.getType() == EntityType.PLAYER)) {
				Resolution queued = MobHeadRenderer.request(type, key, renderer, model, state, whole);
				if (queued != null) {
					return queued;
				}
			}
			return Resolution.FAILED;
		} catch (RuntimeException e) {
			if (!warnedFailure) {
				warnedFailure = true;
				LOGGER.info("生物头像解析失败，退化为刷怪蛋/菱形 | {}", e.toString());
			}
			return Resolution.FAILED;
		}
	}

	/**
	 * 姿势归零（静态、成年、正面）：{@code createRenderState} 之后直接改渲染状态的 public 字段。
	 *
	 * <p>这一帧的"转头/走路/幼年"都会经渲染状态进到模型里，图标就会歪脖子、耳朵乱摆、幼年和成年
	 * 不一样大。归零之后 {@link MobHeadRenderer} 再按 {@code resetPose() → setupAnim(state)} 摆一遍，
	 * 得到的就是<b>设计上的正面站姿</b>；且只作用在<b>本类新建的</b>渲染状态上（每次
	 * {@code createRenderState} 都是新对象），不改实体、不影响世界里的渲染。
	 */
	private static void neutralize(EntityRenderState state) {
		// 通用字段：所有渲染状态都有，先都归零
		state.ageInTicks = 0.0F;
		state.displayFireAnimation = false;
		state.nameTag = null;
		state.scoreText = null;
		state.outlineColor = EntityRenderState.NO_OUTLINE;
		if (state instanceof LivingEntityRenderState living) {
			neutralizeLiving(living);
		}
		// 末影龙的状态继承 {@code EntityRenderState}（非活体），上面那一支走不到，
		// 但这三项同样决定"这张脸是不是静态的"：扇翅相位、死亡计时、受伤红屏。
		if (state instanceof EnderDragonRenderState dragon) {
			dragon.flapTime = 0.0F;
			dragon.deathTime = 0.0F;
			dragon.hasRedOverlay = false;
		}
	}

	/** 见 {@link #neutralize}：活体特有的那些字段。 */
	private static void neutralizeLiving(LivingEntityRenderState state) {
		state.bodyRot = 0.0F;
		state.yRot = 0.0F;
		state.xRot = 0.0F;
		state.walkAnimationPos = 0.0F;
		state.walkAnimationSpeed = 0.0F;
		state.ticksSinceKineticHitFeedback = 0.0F;
		// isBaby <b>不归零</b>：幼体有自己的模型与贴图（小鸡、小牛、小村民…），
		// 归零就等于把幼体画成成年。幼年与否进变种键（见 MobHeadKey），成年与幼体各存一张图。
		state.scale = 1.0F;
		state.ageScale = 1.0F;
		state.isUpsideDown = false;
		state.isFullyFrozen = false;
		state.isAutoSpinAttack = false;
		state.hasRedOverlay = false;
		state.deathTime = 0.0F;
		state.isInWater = false;
		state.pose = Pose.STANDING;
		state.bedOrientation = null;
		state.wornHeadAnimationPos = 0.0F;
	}

	/**
	 * 建一个渲染状态并立刻归零。<b>不写死成活体</b>：末影龙的状态继承 {@code EntityRenderState}，
	 * 泛型参数会被擦除，所以这里走原始类型，归零交给 {@link #neutralize} 按实际类型分支。
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static EntityRenderState renderStateOf(EntityRenderer<?, ?> renderer, LivingEntity entity) {
		EntityRenderState state = ((EntityRenderer) renderer).createRenderState(entity, 0.0F);
		neutralize(state);
		return state;
	}

	/**
	 * 找"头"部位（<b>数据驱动优先</b>，不猜几何）：
	 * <ol>
	 *   <li>{@link HeadedModel#getHead()}：模型自己知道它的头是哪个部位；</li>
	 *   <li>名字路径：{@code head} → {@code head_parts} → {@code body/head} → {@code body/head_parts}
	 *       （常量取原版 {@link PartNames}）；</li>
	 *   <li>{@link #FALLBACK_HEAD_NAMES}：少数模型把头命名为别的名字；</li>
	 *   <li>都找不到就返回 {@code null}，由 {@link #resolve} 决定"整只画"。
	 *       <b>刻意不做"猜哪一段像头"的启发式</b>：猜出来往往四不像，"无头生物要正确图标"由整只渲染满足。</li>
	 * </ol>
	 */
	static ModelPart headPartOf(Model<?> model) {
		ModelPart root = model.root();
		if (model instanceof HeadedModel headed) {
			ModelPart head = headed.getHead();
			if (head != null && !head.isEmpty()) {
				return head;
			}
		}
		ModelPart head = child(root, PartNames.HEAD);
		if (head != null) {
			return head;
		}
		ModelPart headParts = child(root, PartNames.HEAD + "_parts");
		if (headParts != null) {
			return headParts;
		}
		ModelPart body = child(root, PartNames.BODY);
		if (body != null) {
			ModelPart bodyHead = child(body, PartNames.HEAD);
			if (bodyHead != null) {
				return bodyHead;
			}
			ModelPart bodyHeadParts = child(body, PartNames.HEAD + "_parts");
			if (bodyHeadParts != null) {
				return bodyHeadParts;
			}
		}
		for (String name : FALLBACK_HEAD_NAMES) {
			ModelPart fallback = child(root, name);
			if (fallback != null && !fallback.isEmpty()) {
				return fallback;
			}
		}
		return null;
	}

	/**
	 * 取一个子部位：先看直接子节点，再看"穿透层级"的查找（有些模型把部位挂在中间组下面）。
	 * 找不到一律返回 {@code null}（不同版本可能返回 null 也可能抛异常，这里都吃掉）。
	 */
	private static ModelPart child(ModelPart parent, String name) {
		if (parent == null) {
			return null;
		}
		if (parent.hasChild(name)) {
			return parent.getChild(name);
		}
		try {
			return parent.createPartLookup().apply(name);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * 推进离屏渲染的懒预渲染队列（每帧最多一个类型、带时间预算，见 {@link MobHeadRenderer}）。
	 *
	 * 由 {@link EntityRadar#refresh} 在"收集完这一帧的生物之后"调用一次，这样这帧新遇到的类型
	 * 能同帧排上队，不必白等一帧。
	 */
	static void pump() {
		MobHeadRenderer.pump();
	}

	/**
	 * 这张贴图能不能从<b>资源包</b>里读出来（离屏渲染要靠 {@code TextureManager} 去取它）。
	 *
	 * <p>离屏渲染取不到贴图时原版会退回"缺失贴图"（一格黑白格子）。先判一下，就能让"贴图根本不在
	 * 资源包里"（如皮肤管理器动态注册的玩家皮肤）照旧走退化链，而不是画出一张假的。
	 */
	private static boolean loadable(Identifier texture) {
		return Minecraft.getInstance().getResourceManager().getResource(texture).isPresent();
	}

	/**
	 * 少数生物"头就是身体"，或"头部件名不固定"：这里按<b>候选链</b>指定该拿哪个部位当头像区域，
	 * 依次尝试、取第一个存在的；一个都找不到才退回 {@link #headPartOf}。
	 *
	 * <ul>
	 *   <li><b>鱿鱼</b>：模型里没有 {@code head}（只有 {@code body} 那团套膜 + 8 条 {@code tentacles}），
	 *       按"找不到头就整只"会拖着一堆鱿鱼须。</li>
	 *   <li><b>鸡</b>：成年是 {@code head}（只画头、不带腿）；{@code BabyChickenModel} 只有
	 *       {@code body / 腿 / 翅}，<b>小鸡根本没有 {@code head}</b>，它的头就是 {@code body}——
	 *       取 {@code body} 正好只拿头身、把腿留在外面（否则缩到 32 像素糊成一团）。</li>
	 * </ul>
	 */
	private static final Map<EntityType<?>, String[]> REGION_PART_OVERRIDES = Map.of(
			EntityType.SQUID, new String[]{"body"},
			EntityType.GLOW_SQUID, new String[]{"body"},
			EntityType.CHICKEN, new String[]{"head", "body"},
			// 蜜蜂同理：成年取 {@code head}（不带翅膀/脚），幼体没有头部件时退到 {@code body}
			EntityType.BEE, new String[]{"head", "body"},
			// 恶魂 / 快乐恶魂：没有"头"，整只是一颗大方块 + 九根触尾；只取 {@code body} 就只剩那张脸。
			EntityType.GHAST, new String[]{"body"},
			EntityType.HAPPY_GHAST, new String[]{"body"},
			// 炽足兽：身体下面挂着腿，不要脚，只取 {@code body}
			EntityType.STRIDER, new String[]{"body"});
	// 注：驴/骡曾在此单独取 {@code head} 以排除马的 mane；马类改走"整只画 + 取景对头"后那条例外撤掉了。

	/**
	 * 头像的<b>渲染区域</b>：head 部位所在的那一层"head 组"；没有"head 组"就是 head 自己。
	 *
	 * <p><b>为什么不是只画 head</b>：马/驴/骡的 {@code mane}（鬃）和 {@code upper_mouth}（吻/上颚）
	 * 与 {@code head} <b>平级</b>挂在 {@code head_parts} 下面，只画 {@code head} 子树会把鬃和吻丢掉。
	 * 把整个"头组"收进来，吻/鬃/冠这些长在脸周围的部位就和 head 一起画；其余模型（牛/羊/狼/猫…）
	 * 没有这样的组，区域就是 head 自己，行为不变。
	 *
	 * <p>返回值给 {@link MobHeadRenderer} 当"这一层画哪些部位"的边界；模型里没有 head 时返回 {@code null}。
	 */
	static ModelPart iconRegion(EntityType<?> type, Model<?> model) {
		String[] override = REGION_PART_OVERRIDES.get(type);
		if (override != null) {
			for (String name : override) {
				ModelPart part = child(model.root(), name);
				if (part != null) {
					return part;
				}
			}
		}
		ModelPart head = headPartOf(model);
		if (head == null) {
			return null;
		}
		return headGroup(model.root(), head);
	}

	/**
	 * 找 head 部位所在的那一层"head 组"（叶名里含 head 的祖先组，比如马/驴/骡的 {@code head_parts}）；
	 * 没有就返回 head 自己。
	 *
	 * <p>判据只看<b>祖先组名里是否含 head</b>：挂在 {@code body} 下的骆驼头、直接挂在根下的牛/羊/狼等
	 * 都不受影响（取 head 自己）。路径来自一次 {@code visit} 的 {@code name} 参数（从根算起的路径，
	 * 如 {@code /head_parts/head}）；拿不到路径（head 一个立方体都没有）就退回 head。
	 */
	private static ModelPart headGroup(ModelPart root, ModelPart head) {
		String[] holder = new String[1];
		root.visit(new PoseStack(), (pose, name, index, cube) -> {
			if (holder[0] != null) {
				return;
			}
			int slash = name.lastIndexOf('/');
			String leaf = slash < 0 ? name : name.substring(slash + 1);
			if (leaf.equals("head")) {
				holder[0] = name;
			}
		});
		String path = holder[0];
		if (path == null) {
			return head;
		}
		int slash = path.lastIndexOf('/');
		if (slash <= 0) {
			return head; // 直接挂在根下：没有"head 组"
		}
		int prev = path.lastIndexOf('/', slash - 1);
		String parent = path.substring(prev + 1, slash);
		if (!parent.contains("head")) {
			return head; // 父组不是"head 组"（比如 body）：不动
		}
		ModelPart group = root.createPartLookup().apply(parent);
		return group != null ? group : head;
	}

	/**
	 * 取这个渲染器的模型。
	 *
	 * <p><b>活体</b>渲染器自带 {@code getModel()}，直接拿。
	 *
	 * <p><b>末影龙</b>是唯一的例外：{@code EnderDragonRenderer} 直接继承 {@code EntityRenderer}、
	 * 没有 {@code getModel()}，模型是私有字段。这里按<b>已知的模型层</b>自己烘一份
	 * （{@code ModelLayers.ENDER_DRAGON}，与渲染器用同一个层定义），确定性的、不依赖反射。
	 * 表里没有的非活体渲染器一律返回 {@code null} → 解析失败 → 由退化链画菱形。
	 */
	private static Model<?> modelOf(EntityType<?> type, EntityRenderer<?, ?> renderer) {
		if (renderer instanceof LivingEntityRenderer<?, ?, ?> living) {
			return living.getModel();
		}
		if (type == EntityType.ENDER_DRAGON) {
			return new EnderDragonModel(
					Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.ENDER_DRAGON));
		}
		return null;
	}

	/**
	 * 问这个渲染状态当前用哪张贴图。
	 *
	 * <p>{@code getTextureLocation} 的参数是渲染状态而不是实体，且部分渲染器的贴图由状态字段决定
	 * （马的花纹、猫的毛色、村民的职业…），所以用<u>已经归零过的</u>状态去问——幼年因此拿到成年贴图。
	 *
	 * <p>不写死成活体渲染器：末影龙也走这个入口（泛型擦除，只能走原始类型）；它的贴图不由状态决定、
	 * 渲染器也没暴露，所以按固定路径取（见 {@link #ENDER_DRAGON_TEXTURE}）。
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static Identifier textureOf(EntityType<?> type, EntityRenderer<?, ?> renderer, EntityRenderState state) {
		if (renderer instanceof LivingEntityRenderer && state instanceof LivingEntityRenderState living) {
			return ((LivingEntityRenderer) renderer).getTextureLocation(living);
		}
		if (type == EntityType.ENDER_DRAGON) {
			return ENDER_DRAGON_TEXTURE;
		}
		return null;
	}

	/**
	 * 末影龙的主贴图。{@code EnderDragonRenderer} 把它存成 private 常量、没有公开入口，
	 * 这里按同一条资源路径取（{@code textures/entity/enderdragon/dragon.png}）。
	 */
	private static final Identifier ENDER_DRAGON_TEXTURE =
			Identifier.withDefaultNamespace("textures/entity/enderdragon/dragon.png");

	/**
	 * 收尾（唯一那条路）：把 {@link MobHeadRenderer} 回读回来的图标导图（调试）、外扩描边、注册。
	 *
	 * <p><b>不裁透明边</b>：离屏画布是固定正方形、头盒按固定比例摆在正中，画布本身就是"头像该在的
	 * 位置"，屏幕上的头像因此始终同样大小。（裁边会按"真实画到的范围"重算宽高比，耳朵竖得高的
	 * 生物会被裁窄、落到屏幕上反而变小。）
	 *
	 * @return 注册好的 {@link Head}；整张图一个不透明像素都没有时返回 {@code null}
	 *         （调用方把该变种判成"这条路做不出来"，交给退化链画刷怪蛋/菱形）
	 */
	static Head adopt(EntityType<?> type, MobHeadKey key, NativeImage image) {
		// 先摘掉"与主体不相连的碎块"，再导出（这样导出图看到的就是最终结果，便于验收）
		dropDetachedSlivers(image);
		if (DUMP_ENABLED) {
			dump(type, key, image);
		}
		outline(image);
		if (!hasContent(image)) {
			// 一个不透明像素都没有：画布上什么都没画出来 → 退化
			image.close();
			return null;
		}
		return register(type, key, image);
	}

	/**
	 * 摘掉"与主体不相连的碎块"：只保留最大的那一块不透明区域，其余一律清成透明。
	 *
	 * <p><b>不能改成"裁掉最外几行"</b>：取景框按<b>头盒</b>量、画的却是<b>整棵头子树</b>，
	 * 耳朵/角/帽檐本来就伸到框外，"最外几行一定是空的"并不成立，裁边会切到真内容。
	 *
	 * <p><b>判据是"离主体多远"，不是"连不连通"</b>：耳朵/帽檐投影出来可能与头之间隔着一两像素的
	 * 透明缝（坚守者的耳朵就是这样），按"只留最大一块"会把它们一并摘掉；所以只清掉<b>整块都离主体
	 * 超过 {@value #SILVER_MAX_GAP} 像素</b>的碎块（画布边缘那一两条远离主体的碎条是纯噪声）。
	 */
	private static void dropDetachedSlivers(NativeImage icon) {
		int width = icon.getWidth();
		int height = icon.getHeight();
		int[] source = new int[width * height];
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				source[y * width + x] = icon.getPixel(x, y);
			}
		}
		int[] label = new int[source.length];
		int[] stack = new int[source.length];
		int bestLabel = 0;
		int bestSize = 0;
		int nextLabel = 1;
		for (int start = 0; start < source.length; start++) {
			if (label[start] != 0 || (source[start] >>> 24) < OUTLINE_ALPHA) {
				continue;
			}
			int size = 0;
			int top = 0;
			stack[top++] = start;
			label[start] = nextLabel;
			while (top > 0) {
				int current = stack[--top];
				size++;
				int x = current % width;
				int y = current / width;
				if (x > 0) {
					top = push(label, stack, top, source, current - 1, nextLabel);
				}
				if (x < width - 1) {
					top = push(label, stack, top, source, current + 1, nextLabel);
				}
				if (y > 0) {
					top = push(label, stack, top, source, current - width, nextLabel);
				}
				if (y < height - 1) {
					top = push(label, stack, top, source, current + width, nextLabel);
				}
			}
			if (size > bestSize) {
				bestSize = size;
				bestLabel = nextLabel;
			}
			nextLabel++;
		}
		if (bestLabel == 0) {
			return;
		}
		// "离主体够近"的一圈：真实部件即便与主体之间有一两像素的缝，也落在这一圈里
		boolean[] nearMain = new boolean[source.length];
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				if (label[y * width + x] != bestLabel) {
					continue;
				}
				for (int dy = -SILVER_MAX_GAP; dy <= SILVER_MAX_GAP; dy++) {
					for (int dx = -SILVER_MAX_GAP; dx <= SILVER_MAX_GAP; dx++) {
						int nx = x + dx;
						int ny = y + dy;
						if (nx >= 0 && ny >= 0 && nx < width && ny < height) {
							nearMain[ny * width + nx] = true;
						}
					}
				}
			}
		}
		// 只有"整块都远离主体"的碎块才清掉
		boolean[] drop = new boolean[nextLabel];
		for (int i = 0; i < source.length; i++) {
			int value = label[i];
			if (value != 0 && value != bestLabel && !nearMain[i]) {
				drop[value] = true;
			}
		}
		for (int i = 0; i < source.length; i++) {
			if (drop[label[i]]) {
				icon.setPixel(i % width, i / width, 0);
			}
		}
	}

	/**
	 * 见 {@link #dropDetachedSlivers}：碎块"整块都离主体超过这么多像素"才当作噪声清掉。
	 * 取 {@value}：耳朵/帽檐与脸之间的透明缝一般只有一两像素，留到 2 能保住它们；
	 * 而画布边缘的残留离主体通常有四五像素以上，照样会被清掉。
	 */
	private static final int SILVER_MAX_GAP = 3;

	/** 见 {@link #dropDetachedSlivers}：把一个还没标记过的不透明邻居压进栈。 */
	private static int push(int[] label, int[] stack, int top, int[] source, int index, int value) {
		if (label[index] != 0 || (source[index] >>> 24) < OUTLINE_ALPHA) {
			return top;
		}
		label[index] = value;
		stack[top] = index;
		return top + 1;
	}

	/**
	 * 剪影外扩的描边色，<b>不透明</b>（{@code 0xFF…}）：32 像素下圆头（兔子/美西螈/羊驼/牛）的
	 * 剪影边缘是锯齿状，半透明描边会和边缘那些抗锯齿的半透明像素<b>叠色</b>、看起来是"边缘一圈
	 * 杂色"；不透明描边则是干净的一圈实色。
	 */
	private static final int OUTLINE_COLOR = 0xFF101014;

	/**
	 * 描边判"这里确实有内容"的 alpha 门槛。取偏高值 {@value}：超采样/抗锯齿会在形状边缘留下
	 * 半透明像素，拿它们当"内容"去外扩，描边会被撑到淡边上、剪影外侧多出零星深色像素。
	 */
	private static final int OUTLINE_ALPHA = 150;

	/**
	 * <b>贴着头形外扩 1 像素的剪影描边</b>：把每个"自己没有内容、但 8 邻域里有内容"的像素涂成描边色。
	 *
	 * <p>为什么不是矩形框：矩形框会把耳朵/犄角/下颌这些"伸出去"的地方框到外面；按剪影外扩得到的
	 * 是一圈<b>紧贴形状</b>的 1 像素黑边。做法放在最终的 {@link NativeImage} 上，不需要任何 GL 状态、
	 * 不占绘制期的开销。
	 *
	 * <p>先在数组里取好快照再写回，保证外扩只有一圈。
	 */
	private static void outline(NativeImage icon) {
		int width = icon.getWidth();
		int height = icon.getHeight();
		int[] source = new int[width * height];
		for (int py = 0; py < height; py++) {
			for (int px = 0; px < width; px++) {
				source[py * width + px] = icon.getPixel(px, py);
			}
		}
		for (int py = 0; py < height; py++) {
			for (int px = 0; px < width; px++) {
				if ((source[py * width + px] >>> 24) >= OUTLINE_ALPHA) {
					continue; // 这一格自己有内容：保持原样
				}
				boolean nearContent = false;
				for (int dy = -1; dy <= 1 && !nearContent; dy++) {
					for (int dx = -1; dx <= 1; dx++) {
						if (dx == 0 && dy == 0) {
							continue;
						}
						int nx = px + dx;
						int ny = py + dy;
						if (nx < 0 || ny < 0 || nx >= width || ny >= height) {
							continue;
						}
						if ((source[ny * width + nx] >>> 24) >= OUTLINE_ALPHA) {
							nearContent = true;
							break;
						}
					}
				}
				if (nearContent) {
					icon.setPixel(px, py, OUTLINE_COLOR);
				}
			}
		}
	}

	/** 这张图里有没有至少一个不透明像素（"到底画没画出东西"的判据）。 */
	private static boolean hasContent(NativeImage icon) {
		for (int py = 0; py < icon.getHeight(); py++) {
			for (int px = 0; px < icon.getWidth(); px++) {
				if ((icon.getPixel(px, py) >>> 24) != 0) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * 挂成动态贴图并返回上层的 {@link Head}（唯一那条路的出口）。
	 * <p><b>传进来的图会被贴图持有（永不 close 它，见类注释的"生命周期"）</b>。
	 */
	private static Head register(EntityType<?> type, MobHeadKey key, NativeImage icon) {
		Identifier id = iconId(type, key);
		Minecraft.getInstance().getTextureManager().register(id, new HeadTexture(id.toString(), icon));
		return new Head(id, icon.getWidth(), icon.getHeight());
	}

	/**
	 * 调试用：把刚做出来的头像原图写到 {@code <游戏目录>/mobhead_dump/<实体类型>_<变种>.png}。
	 *
	 * <p><b>路径是"相对游戏目录"的</b>：开发环境里游戏目录本身就是 {@code run/}，所以这里<b>只写
	 * {@code mobhead_dump}</b>——写 {@code run/mobhead_dump} 会变成 {@code run\run\mobhead_dump}。
	 *
	 * <p>只在 {@link #DUMP_ENABLED} 打开时被调用；调用点在"解析路径"上，而本类按<b>变种</b>只解析
	 * 一次，所以每个变种最多写一个文件，<b>不在每帧路径上</b>。文件名带变种判别值，"同类型不同变种"
	 * 不会互相覆盖。
	 *
	 * <p>写盘用 {@link NativeImage#writeToFile(Path)}（已核实它经 {@code STBImageWrite} 直接写 PNG，
	 * 行序与 {@link NativeImage#getPixel} 一致、<b>不做上下翻转</b>，导出图与屏幕同朝向）。目录不存在
	 * 就建；任何失败（只读盘、路径被占、格式不支持）都只在日志里说一次，<b>绝不影响渲染</b>。
	 */
	private static void dump(EntityType<?> type, MobHeadKey key, NativeImage icon) {
		try {
			Path directory = Path.of(DUMP_DIRECTORY);
			Files.createDirectories(directory);
			Path file = directory.resolve(dumpFileName(type, key));
			icon.writeToFile(file);
		} catch (RuntimeException | IOException e) {
			if (!warnedDump) {
				warnedDump = true;
				LOGGER.info("生物头像导出失败（调试开关），忽略 | {}", e.toString());
			}
		}
	}

	/** 导出文件名：取 {@link #iconId} 的最后一段（已在那边把非法字符换掉）加 {@code .png}。 */
	private static String dumpFileName(EntityType<?> type, MobHeadKey key) {
		String path = iconId(type, key).getPath();
		int slash = path.lastIndexOf('/');
		return (slash < 0 ? path : path.substring(slash + 1)) + ".png";
	}

	/**
	 * 头像图标贴图的名字：<b>按"类型 + 变种"</b>生成，一个变种一张。
	 *
	 * <p>名字里带类型路径（不合法字符换成 {@code _}）与变种判别值（十六进制），出问题时一眼能对上
	 * 是谁；也保证"同类型不同变种"不共用贴图名（共用会让后注册的覆盖前一张，两种变种显示成同一张图）。
	 */
	private static Identifier iconId(EntityType<?> type, MobHeadKey key) {
		Identifier typeKey = EntityType.getKey(type);
		String raw = typeKey == null ? "unknown" : typeKey.toString();
		StringBuilder path = new StringBuilder("dynamic/mob_head/");
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
					|| c == '_' || c == '.' || c == '-' || c == '/';
			path.append(ok ? c : '_');
		}
		path.append('_').append(Integer.toHexString(key.hashCode()));
		return Identifier.fromNamespaceAndPath(SinanConstants.MOD_ID, path.toString());
	}

	/**
	 * 头像图标自己的显存贴图：采样器换成<b>两向最近邻 + CLAMP_TO_EDGE</b>。
	 *
	 * <p>{@link DynamicTexture} 默认 REPEAT + NEAREST——边缘用 REPEAT 会把对侧像素卷过来，所以边缘
	 * 改成 CLAMP_TO_EDGE；过滤<b>保持最近邻</b>：头像本质是"像素画"，用线性过滤缩到小地图那十来个
	 * 像素会糊成一团。代价是连续滑动时有轻微抖动（像素画通病），换来"一眼能认出是什么生物"。
	 */
	private static final class HeadTexture extends DynamicTexture {

		private HeadTexture(String label, NativeImage pixels) {
			super(() -> label, pixels);
			this.sampler = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE,
					AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.NEAREST, false);
		}
	}
}
