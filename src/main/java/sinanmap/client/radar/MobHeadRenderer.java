package sinanmap.client.radar;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sinanmap.common.SinanConstants;

/**
 * 生物头像的<b>唯一渲染路</b>：让游戏自己的渲染器把这一帧要画的<b>每一个渲染层</b>提交出来
 * （主模型 + 羊毛/项圈/发光眼/斑纹/马铠/护甲/雪层…），把这些提交<b>记录</b>下来，再把每一层里
 * "该模型/该部位的头像区域"用那一层自己的 {@link RenderType} 画进一张离屏贴图，回读成像素、
 * 超采样降采样、外扩描边，最后当成 {@link MobHeads.Head} 交给上层。
 *
 * <p><b>画"头像区域"而不是"头部位"</b>：区域由 {@link MobHeads#iconRegion} 给出——head 部位所在
 * 那个"头组"的整棵子树。马/驴/骡的层定义把鬃与吻/上颚与 head <b>平级</b>挂在 {@code head_parts} 下，
 * 只画 {@code head} 会把鬃和吻丢掉（看起来就不像那匹马）；整组一起画就都回来了。
 * 而"头就是身体/头太小"的那些生物（鱼、蜜蜂、史莱姆、鸡、蝙蝠…）由上层判成"整只"，这里画整个模型。
 *
 * <p><b>为什么必须"画齐所有层"</b>：只画"主模型 + 主贴图"时，羊毛、项圈、发光眼、马的花纹、护甲、
 * 雪层全都不在，同一个模型的不同状态看起来一模一样（"头像长期不对版"的主因之一）。
 *
 * <p><b>怎么拿到这些层</b>：<b>自己实现一个 {@link SubmitNodeCollector}</b>，拦截
 * {@code submitModel/submitModelPart} 把 <b>(模型或部位, {@link RenderType}, 颜色)</b> 记进一张小表；
 * 其余提交（名牌、影子、着火、拴绳、物品、方块、自定义几何…）一律忽略。<b>不注入、不反射</b>：
 * {@code RenderType} 就是原版递过来的那一个，贴图/半透明/剔除/深度/着色器全都对，只是"换个地方画"，
 * 同一套代码在 Fabric 与 NeoForge 上都能跑。
 *
 * <p><b>姿势：静态、成年、正面</b>：渲染状态由上层按"动画归零、{@code isBaby=false}"改过
 * （见 {@link MobHeads#neutralize}），这里再按 {@code resetPose() → setupAnim(state)} 摆一次：
 * {@code resetPose()} 清掉上一次世界渲染的残留姿势，{@code setupAnim(state)} 用归零过的状态摆出
 * 设计正面站姿。画完（含中途抛异常）<b>一定</b>再 {@code resetPose()} 一次——模型是共享对象，
 * 复位是"世界渲染每帧都会先 setupAnim"的安全方向，<b>不会污染游戏内渲染</b>。注意原版只在
 * "该生物有渲染层"时才调 {@code setupAnim}，所以"没有层的生物"必须自己补，否则姿势取决于上一次
 * 世界渲染（旧实现画歪耳朵的原因之一）。
 *
 * <p><b>坐标系（全部正确性的根据，改动前请先读）</b>：
 * <ul>
 *   <li>{@code RenderSystem.getModelViewStack()} 在绘制期间是<b>单位阵</b>，所有摆放都写在 PoseStack 里。</li>
 *   <li>摆放写在"<b>头像区域的局部坐标系</b>"里：先把该区域这一帧的姿态取逆（{@code base}），
 *       画出来的就是模型<b>设计上的正面姿态</b>（不改共享模型，只把逆矩阵乘在外面）。
 *       三段：平移到画布中心 → {@code scale(s, -s, s)} → 平移到锚点。</li>
 *   <li><b>Y 镜像：整条链上有且只有一次，且放在 scale 里</b>。画面朝向与绕序由"投影 Y 符号 × 缩放
 *       Y 符号"决定，放哪一侧画面/绕序都一样；但<b>只有缩放的镜像作用到法线</b>，而离屏光照是按
 *       "法线被 Y 镜像过"调出来的，所以镜像必须放 scale、投影那边保持 {@code invertY=false}。
 *       绕序为什么必须是"奇数次镜像"见下面"面剔除"。</li>
 *   <li><b>深度</b>：{@code Projection#setupOrtho(zNear, zFar, w, h, invertY)} 用 JOML 口径——
 *       <b>近平面对应 {@code z = -zNear}</b>。传 {@code (-R, +R)} 会把相机放到 +Z，深度比较（固定
 *       {@code LESS_OR_EQUAL}、清成 1.0 最远）把<b>模型背面</b>判得更近，于是每个头只画出"后脑勺"
 *       （一片平坦、没有五官的贴图区）。必须传 <b>{@code (+R, -R)}</b>：近平面落在 {@code z = -R}，
 *       模型 -Z 那一面（原版正脸法线就是 (0,0,-1)）最小 z、正面胜出。</li>
 *   <li><b>面剔除</b>：不自己造 RenderType、直接用层给的那一个，所以<b>必须按最严的那条管线来写</b>：
 *       {@code ENTITY_CUTOUT} 关着剔除，但 {@code EYES} 开着。绕序与画面同源，"奇数次 Y 镜像"这条
 *       既是画面要求也是剔除要求；当初只核实了 {@code ENTITY_CUTOUT}，才让发光眼整整一层被剔空。</li>
 * </ul>
 *
 * <p><b>缩放与居中</b>：量<b>头自己最大的那个立方体</b>（头盒）：锚点 X 取头的 pivot、Y/Z 取盒中心，
 * 跨度取盒的 X/Y 长边；耳朵/犄角/鬃再大也不会把脸推出中心或压小（见 {@link #measureIconBox}）。
 * 超出画布的部分被边界裁掉，<b>不为了塞进去而缩小</b>；画布<b>固定正方形、不裁透明边</b>。
 *
 * <p><b>超采样</b>：画布边长 = 图标长边 × {@value #SUPERSAMPLE}（{@value #CANVAS}），回读后按 alpha
 * 加权做 {@value #SUPERSAMPLE}×{@value #SUPERSAMPLE} 盒式平均降采样到 {@value #SIZE}×{@value #SIZE}。
 *
 * <p><b>全局状态：存了什么、为什么必须还</b>（漏还任一项都会把这一帧的 HUD/3D 画面弄花）：
 * <ol>
 *   <li>{@code outputColorTextureOverride}/{@code outputDepthTextureOverride}：不还回去，后面所有经
 *       RenderType 画的 GUI 都会画到这张离屏贴图上。</li>
 *   <li><b>投影矩阵</b>：不还的话 GUI 会继续用这里的正交矩阵。<b>不用</b> {@code backupProjectionMatrix()}/
 *       {@code restoreProjectionMatrix()}——那一对是<b>单槽</b>备份，外面若也有人用就会互相踩。</li>
 *   <li>{@code getModelViewStack()}：原样进出。</li>
 *   <li><b>剪刀</b>：HUD 抽取期常处在地图矩形的裁剪里，不关会把离屏画面裁掉；关掉后按原值恢复。</li>
 *   <li><b>光照</b>：换成自己那支"正对相机"的光（见 {@link #buildHeadLights}），不还的话后面 GUI 会按它着色。</li>
 *   <li><b>雾</b>：<b>不动</b>，HUD/GUI 阶段拿到的本来就是"无雾"缓冲。</li>
 * </ol>
 *
 * <p><b>回读是异步的</b>：{@code CommandEncoder#copyTextureToBuffer} 的回调经
 * {@code RenderSystem.queueFencedTask} 排队、在 {@code runTick} 的 {@code executePendingTasks()} 里兑现，
 * 所以从"排队"到"拿到像素"会隔一两帧；{@link MobHeads.Resolution#PENDING} 就是这个中间态，调用方<b>不要缓存</b>。
 *
 * <p><b>取舍</b>：<b>全场一个离屏目标、缓冲全部复用</b>（{@link #target} 只建一次，带深度，不按类型建，
 * 显存有界）；<b>带时间预算的懒预渲染队列</b>（每帧最多 {@value #MAX_JOBS_PER_FRAME} 个变种，单次抽取不超过
 * {@link #FRAME_BUDGET_NANOS}，两次至少隔 {@link #MIN_PUMP_INTERVAL_NANOS}，只有真正出现在雷达里的变种才进队）；
 * <b>"未就绪"不缓存</b>（否则会"先把退化结果缓存下来、之后永远画退化结果"）；<b>画不出来就交给退化链</b>
 * （失败判成 FAILED，由上层退化成刷怪蛋 → 菱形标记）；<b>不用 Mixin、不用反射</b>。
 *
 * <p><b>线程</b>：全部只在渲染线程跑（抽取期由 {@link EntityRadar#refresh} 推队列，回调也在渲染线程）。
 */
final class MobHeadRenderer {

	private static final Logger LOGGER = LoggerFactory.getLogger(SinanConstants.MOD_ID);

	/**
	 * 超采样倍率：离屏画布边长 = 图标长边 × 这个数。取 {@value #SUPERSAMPLE}（画布 64、再按 2×2
	 * 按 alpha 加权降到 32）。折中的旋钮，试过两端：<b>4</b>（画布 128）整张脸发糊、边缘掺入相邻部件
	 * 颜色；<b>2</b> 更糟（放大一倍再 2×2 降采样，边缘把相邻部件掺得更多），所以"缩小采样会掺邻居"
	 * 的猜测是错的、杂色不是 mipmap 来的。
	 */
	private static final int SUPERSAMPLE = 1;

	/** 离屏画布边长（超采样后）。 */
	private static final int CANVAS = MobHeads.ICON_LONG_SIDE * SUPERSAMPLE;

	/** 最终图标的边长（与 {@link MobHeads} 的预算同一个数）。 */
	private static final int SIZE = MobHeads.ICON_LONG_SIDE;

	/**
	 * 鱼类侧视（横截面）：整只画时把模型绕 Y 转 90°，让身体侧面面对相机。鱼类模型是"一片"，
	 * 正面看只是一条竖缝加一堆鳍，转 90° 后才是"鱼侧面剪影 + 尾巴"。
	 */
	private static final Set<EntityType<?>> SIDE_VIEW_TYPES = Set.of(
			EntityType.COD, EntityType.SALMON, EntityType.PUFFERFISH, EntityType.TROPICAL_FISH,
			// 鹦鹉螺：一片"壳 + 触手"，正面只是一条窄边，绕 Y 转 90° 才是能认出来的横截面；僵尸鹦鹉螺一致。
			EntityType.NAUTILUS, EntityType.ZOMBIE_NAUTILUS);

	/**
	 * 俯视（顶部截面）：整只画时绕 X 轴转 -90°，让模型上表面面对相机。幻翼是"一对大翅膀"，
	 * 正面看只有一条缝，所以取顶部截面。
	 */
	private static final Set<EntityType<?>> TOP_VIEW_TYPES = Set.of(EntityType.PHANTOM);

	/** 绕模型空间 X 轴 -90°：把上表面摆到镜头前，见 {@link #TOP_VIEW_TYPES}。 */
	private static final Matrix4f TOP_VIEW_ROTATION = new Matrix4f().rotationX((float) (-Math.PI / 2.0));

	/** 绕模型空间 Y 轴 +90°：把沿 Z 长的鱼身摆成沿 X 长的侧视。只读，绝不改它本身。 */
	private static final Matrix4f SIDE_VIEW_ROTATION = new Matrix4f().rotationY((float) (Math.PI / 2.0));

	/**
	 * <b>不抵消这一帧姿态</b>的类型：直接按模型<b>设计好的姿势</b>画（其余类型都把头部姿态取逆、得到"正面站姿"）。
	 *
	 * <p>疣猪兽的 {@code head} 是<b>一个 14×6×19 的长吻盒</b>（野猪式，没有单独的"脸"），层定义里这个头
	 * <b>本来就低着</b>（{@code PartPose} 带 50° 俯角）。抵消姿态后变成"正对着长吻的端面看"，投影是一条
	 * 14×6 的横条、只剩两个鼻孔。保留自己的俯角才看到本来面目（耳朵在两侧、角在吻侧面、长吻从上伸到下）。
	 * 它的眼睛画在长吻的<b>两个侧面</b>上，任何"正面"视角都看不到，这是模型结构决定的。
	 * 僵尸疣猪兽与它同一个模型（{@code HoglinModel}），一起处理。
	 */
	private static final Set<EntityType<?>> KEEP_MODEL_POSE_TYPES =
			Set.of(EntityType.HOGLIN, EntityType.ZOGLIN);

	/** 见 {@link #KEEP_MODEL_POSE_TYPES}：取景与绘制都<b>不</b>乘"姿态取逆"。 */
	private static boolean keepModelPose(EntityType<?> type) {
		return KEEP_MODEL_POSE_TYPES.contains(type);
	}

	/**
	 * 头盒在画布长边上占的比例。取 {@value #FILL}：留几% 的边给"贴着头形外扩 1 像素"的描边
	 * （见 {@link MobHeads#outline}），又不浪费像素预算。
	 */
	private static final float FILL = 0.86F;

	/**
	 * 脸盒之外的余量（见 {@link #fitSpan}）：取 {@value #HEAD_FIT_MARGIN} 是为了让紧贴脸的<b>耳朵</b>
	 * 留在画面里（耳朵一般只比脸高出一两格），同时不给鬃/颈这种长部件留位置。
	 */
	private static final float HEAD_FIT_MARGIN = 1.2F;

	/**
	 * "画整只、取景对头"（见 {@code MobHeads#frameOnHead}）时，取景范围相对"头盒"放大多少。
	 *
	 * <p>取 {@value}：头盒再放大八成，画面就带上头后面的前半身——海豚因此是"头 + 前半身"而不是
	 * "一个方块头 + 小吻"。
	 */
	private static final float HEAD_FRAME_EXPAND = 2.6F;

	/**
	 * 见 {@code MobHeads#frameOnHead}：马类单独用这一档。
	 *
	 * <p>马的整身比海豚高得多，用 2.6 / 2.2 都会在画面下方带出肩膀/前腿，收到 {@value} 才干净。
	 */
	private static final float HORSE_FRAME_EXPAND = 2.05F;

	/**
	 * 马类取景再往下放开的倍数（见 {@code frameOnHead} 那一支）。
	 *
	 * <p>按"头盒 × {@link #HORSE_FRAME_EXPAND}"框出来时上沿压在耳朵尖、下沿切掉胸口，中间一大片全是
	 * 平脖子（驴尤其明显）。这里把跨度放到 {@value} 倍并把取景中心**同步下移一半的增量**：上沿原地
	 * 不动（耳朵完整），多出来的全部落在下方，画面变成"头 + 上身（胸口/前腿根）"。
	 */
	private static final float HORSE_FRAME_EXTEND = 1.25F;

	/** 见 {@code MobHeads#preferFaceCube}：框到吻之后再放大的倍数。 */
	private static final float CAMEL_FRAME_EXPAND = 1.7F;

	/** 见 {@code MobHeads#trimNeck}：框整个头区域时再收一点，把过长的脖子压短。 */
	private static final float WHOLE_REGION_TRIM = 0.68F;

	/**
	 * 见 {@code draw}：监守者耳朵的收框倍数。
	 *
	 * <p>取 {@value}：不收框时耳朵细成碎点、会被当噪声摘掉，收 0.8 后耳朵粗且与头相接、完整保留；
	 * 屏幕上最终多大由图标档位（{@code EntityRadar.WARDEN_ICON_SCALE}）决定。
	 */
	private static final float WARDEN_FRAME_TRIM = 0.8F;

	/**
	 * 见 {@code draw}：女巫帽子的收框倍数。
	 *
	 * <p><b>1.0 = 不收框</b>：0.6 / 0.75 两版都因"帽檐被切到画布外、沿切口留孤立像素"出现杂色，
	 * 所以女巫与监守者相反——不收框保证干净，要头大就抬图标档位。
	 */
	private static final float WITCH_FRAME_TRIM = 1.0F;

	/** "画整只、取景对头"时用的放大倍数，按类型分档（见 {@link #HORSE_FRAME_EXPAND}）。 */
	private static float frameExpand(EntityType<?> type) {
		return horseFamily(type) ? HORSE_FRAME_EXPAND : HEAD_FRAME_EXPAND;
	}

	/** 马/驴/骡/骷髅马/僵尸马：这一族"画整只、取景对头"，取景规则共用（见 {@link #frameExpand}）。 */
	private static boolean horseFamily(EntityType<?> type) {
		return type == EntityType.HORSE || type == EntityType.DONKEY || type == EntityType.MULE
				|| type == EntityType.SKELETON_HORSE || type == EntityType.ZOMBIE_HORSE;
	}

	/**
	 * 后续渲染层相对主层的深度偏置（方块，往相机那一侧）。
	 *
	 * <p>主层与附加层经常是<b>同一份几何</b>（末影人的发光眼、铁傀儡的裂纹、热带鱼的花纹都是"同模型
	 * 换贴图"），深度本该相等、管线用 {@code LESS_OR_EQUAL} 应当放行；但两层各自用 {@code inverseOf(part)}
	 * 重算矩阵，浮点误差会让附加层偶尔差一个深度单位、时通时不通（在侧缘/头顶斜角这类深度梯度大的地方
	 * 表现为零星斑点）。推一点点即可稳定通过，量只有模型厚度的千分之一。<b>必须加在
	 * {@code mulPose(layerBase)} 之前</b>（视图空间），否则会被 base 的旋转带走。
	 */
	private static final float LAYER_DEPTH_BIAS = 1.0E-3F;

	/**
	 * 见 {@code draw}：守卫者/远古守卫者取景留的余量。
	 *
	 * <p>取 {@value}（<b>不留余量</b>）：棘刺只有<b>顶出轮廓</b>才有观感；实测留 18% 时棘刺整根缩进壳里、
	 * 看起来"没有触角"，留 5% 也看不见，按包围盒原样取景才看得见。
	 */
	private static final float GUARDIAN_FRAME_MARGIN = 1.0F;

	/**
	 * 守卫者族：画之前把 12 根棘刺（"触角"）沿<b>它自己的轴</b>放大这一档。
	 *
	 * <p>棘刺的枢轴落在壳表面、立方体以枢轴为中心，所以每根刺只有<b>一半</b>露在壳外，尖端只比壳宽约
	 * 3 个模型单位；缩到五六像素的图标上只有<b>半个像素</b>，所以不管怎么调取景都"看不到触角"——这是几何
	 * 比例问题。把刺拉长，尖端才真的离开壳。用"沿轴放大"而不是"把枢轴往外挪"：后者会让内端也离开壳、
	 * 贴角的刺变成浮空小条；沿轴放大则内端仍扎在壳里。取 {@value #GUARDIAN_SPIKE_SCALE} 是"尖端明显出轮廓、
	 * 又没长成海胆"的一档。
	 */
	private static final float GUARDIAN_SPIKE_SCALE = 1.8F;

	/** 见 {@link #GUARDIAN_SPIKE_SCALE}：原版 {@code GuardianModel} 里棘刺是 head 的子部位，名叫 {@code spike0..spike11}。 */
	private static final int GUARDIAN_SPIKE_COUNT = 12;

	/** 见 {@link #GUARDIAN_SPIKE_COUNT}。 */
	private static final String GUARDIAN_SPIKE_PREFIX = "spike";

	/** 正交投影的深度范围：{@code zNear = +DEPTH_RANGE}、{@code zFar = -DEPTH_RANGE}。取 {@value #DEPTH_RANGE}
	 * （1000）是"任何模型都塞得进"的量级，同时保证正脸（-Z 面）落在近侧（见类注释"深度"那一节）。*/
	private static final float DEPTH_RANGE = 1000.0F;

	/**
	 * 全亮打包光照：blockLight=15、skyLight=15（原版 {@code LightTexture.FULL_BRIGHT} 就是这个值）。
	 * 取全亮是为了让图标不受"实体站在多黑的地方"影响。
	 */
	private static final int FULL_BRIGHT = 0xF000F0;

	/** 顶点缓冲的初始容量：一个头几十个立方体、再加几层，256 KB 够用（不够会自动扩容，不会溢出）。 */
	private static final int VERTEX_BUFFER_BYTES = 1 << 18;

	/**
	 * 每帧最多渲染几个变种。
	 *
	 * <p>取 {@value #MAX_JOBS_PER_FRAME}：一次回读会让驱动同步一次管线，是这一整套里唯一有"停顿感"的操作；
	 * 一个变种晚一两帧到无所谓，一帧塞几次就可能被玩家看见。
	 */
	private static final int MAX_JOBS_PER_FRAME = 1;

	/**
	 * 单次抽取的时间预算（纳秒）：{@value #FRAME_BUDGET_NANOS}（2 ms）。
	 *
	 * <p>正常远用不满，作用是<b>兜住异常</b>（驱动抽风、贴图巨大、GPU 忙）：超预算立刻收手、剩下的留给
	 * 下一帧，绝不为了多补两个头像卡帧。
	 */
	private static final long FRAME_BUDGET_NANOS = 2_000_000L;

	/**
	 * 两次抽取之间的最小间隔：{@value #MIN_PUMP_INTERVAL_NANOS}（4 ms，约一帧）。
	 *
	 * <p>小地图与全屏地图都会调 {@link EntityRadar#refresh}（同一 HUD 帧可能各调一次），用它把
	 * "每帧最多一次回读"钉死，不依赖调用方的次数。
	 */
	private static final long MIN_PUMP_INTERVAL_NANOS = 4_000_000L;

	/**
	 * 回读的超时期限（{@value #READBACK_TIMEOUT_NANOS} 纳秒 = 2 秒）：发起回读后这么久还没兑现就判失败、
	 * 交回退化链——宁可晚一点用刷怪蛋/菱形标记，也不能永远停在"正在渲染中"（那就是"永远不画"）。
	 */
	private static final long READBACK_TIMEOUT_NANOS = 2_000_000_000L;

	/** 任务状态。{@link #JOBS} 里有记录 = "这个变种已经处理过了"。 */
	private enum State {
		/** 已排队，或已经发起渲染、等回读。 */
		PENDING,
		/** 拿到像素并注册好贴图了。 */
		DONE,
		/** 确定做不出来：这个变种以后都走退化链（刷怪蛋/菱形标记），不再重试离屏。 */
		FAILED
	}

	/**
	 * 捕获到的一个渲染层：要么是"整模型提交"（{@link #model} 非空），要么是"单部位提交"
	 * （{@link #part} 非空）。{@link #renderType} 是原版这一层自己用的那一个，原样复用。
	 */
	private static final class Layer {

		private final Model<?> model;
		private final ModelPart part;
		private final RenderType renderType;
		private final int color;

		private Layer(Model<?> model, ModelPart part, RenderType renderType, int color) {
			this.model = model;
			this.part = part;
			this.renderType = renderType;
			this.color = color;
		}
	}

	/**
	 * 本类的 {@link SubmitNodeCollector}：只负责"记下来"。原版 {@code submitModel/submitModelPart} 本身就是
	 * "记进提交队列、稍后再落笔"的语义，所以"拦截 = 记录"是它本来的用法，不需要注入、不需要反射；
	 * 只有这两类提交被动用，其余（名牌/影子/着火/拴绳/物品/方块/自定义几何/粒子）一律丢掉。
	 * 实例是<b>复用</b>的（每帧一个变种），{@link #reset} 在每次捕获前清表，不产生每帧分配。
	 */
	private static final class Capture implements SubmitNodeCollector {

		private final List<Layer> layers = new ArrayList<>();

		private void reset() {
			this.layers.clear();
		}

		@Override
		public OrderedSubmitNodeCollector order(int order) {
			// 按"提交顺序"画（原版就是这个顺序），所以排序请求可以原样返回自己
			return this;
		}

		@Override
		public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType,
				int light, int overlay, int color, TextureAtlasSprite sprite, int outlineColor,
				ModelFeatureRenderer.CrumblingOverlay crumbling) {
			this.layers.add(new Layer(model, null, renderType, color));
		}

		@Override
		public void submitModelPart(ModelPart part, PoseStack poseStack, RenderType renderType, int light,
				int overlay, TextureAtlasSprite sprite, boolean visible, boolean skipDraw, int color,
				ModelFeatureRenderer.CrumblingOverlay crumbling, int outlineColor) {
			// 只认"部位 + RenderType"：颜色参数的位置不是可依赖的契约，
			// 而少数用得到"按颜色染"的层（羊毛、雪层…）走的是整模型提交那条路，颜色在那条路上拿得到。
			this.layers.add(new Layer(null, part, renderType, -1));
		}

		@Override
		public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
			// 头像里不要影子
		}

		@Override
		public void submitNameTag(PoseStack poseStack, Vec3 pos, int light, Component name, boolean discrete,
				int backgroundColor, double distanceSq, CameraRenderState camera) {
			// 头像里不要名牌
		}

		@Override
		public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence text, boolean shadow,
				Font.DisplayMode mode, int light, int color, int backgroundColor, int outlineColor) {
			// 头像里不要文字
		}

		@Override
		public void submitFlame(PoseStack poseStack, EntityRenderState state, Quaternionf rotation) {
			// 头像里不要着火特效（否则在火里的生物图标会顶着一团火）
		}

		@Override
		public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leash) {
			// 头像里不要拴绳
		}

		@Override
		public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState state) {
			// 方块类提交不是"头"，丢掉
		}

		@Override
		public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> parts,
				int[] tints, int light, int overlay, int color) {
			// 同上
		}

		@Override
		public void submitBreakingBlockModel(PoseStack poseStack, BlockStateModel model, long seed, int progress) {
			// 同上
		}

		@Override
		public void submitItem(PoseStack poseStack, ItemDisplayContext context, int light, int overlay, int color,
				int[] tints, List<BakedQuad> quads, ItemStackRenderState.FoilType foil) {
			// 手上的东西、头顶的方块（雪傀儡/末影人/铁傀儡的花）都不画：这里是"头像"
		}

		@Override
		public void submitCustomGeometry(PoseStack poseStack, RenderType renderType,
				SubmitNodeCollector.CustomGeometryRenderer renderer) {
			// 自定义几何（光束之类）不是头的一部分，丢掉
		}

		@Override
		public void submitParticleGroup(SubmitNodeCollector.ParticleGroupRenderer renderer) {
			// 粒子不画
		}
	}

	/**
	 * 一个待渲染的变种。持有的是<b>渲染状态</b>（本类新建）、<b>模型</b>与<b>渲染器</b>（长生命周期的共享
	 * 对象），<b>绝不持有实体</b>——否则实体会被它及其所在区块拖住。渲染状态已按同一口径归零过。
	 */
	private static final class Job {

		private final EntityType<?> type;
		private final MobHeadKey key;
		/**
		 * 渲染器与渲染状态都取<b>通用</b>类型，不写死成 {@code LivingEntityRenderer}：末影龙直接继承
		 * {@code EntityRenderer}（状态也继承 {@code EntityRenderState}），写死活体那一支就拿不到它的图标。
		 */
		private final EntityRenderer<?, ?> renderer;
		private final Model<?> model;
		private final EntityRenderState state;
		private final boolean wholeEntity;

		private State state$;
		private MobHeads.Resolution result;
		/** 已经发起回读的时刻（没发起过就是 0），用来兜住"回读迟迟不回来"。 */
		private long readbackIssuedNanos;

		private Job(EntityType<?> type, MobHeadKey key, EntityRenderer<?, ?> renderer, Model<?> model,
				EntityRenderState state, boolean wholeEntity) {
			this.type = type;
			this.key = key;
			this.renderer = renderer;
			this.model = model;
			this.state = state;
			this.wholeEntity = wholeEntity;
			this.state$ = State.PENDING;
		}
	}

	/** {@code 变种键 → 任务}。只在该变种第一次出现时插入（每个变种一次），之后只读。 */
	private static final Map<MobHeadKey, Job> JOBS = new HashMap<>();

	/** 待渲染队列（FIFO，用索引而非 poll，避免每次都搬数组）。排空后复位，容量留着复用。 */
	private static final List<Job> QUEUE = new ArrayList<>();
	private static int queueHead;
	private static long lastPumpNanos;

	/** 出过事就永久关掉这条路（上层走刷怪蛋 / 菱形标记的退化链），避免反复踩同一个坑、反复刷日志。 */
	private static boolean disabled;
	private static boolean warnedFailure;

	/**
	 * 离屏目标：<b>两张轮换</b>（懒建，尺寸固定，不随变种数增长）。必须两张：画布<b>既是这一帧要画的
	 * 画布、又是上一次回读正在拷贝的源</b>，而一次回读可能跨一两帧才落地，画进同一张就会让拷贝读到被改写
	 * 的内容（图标混进两次的内容）。轮换后这一帧画 A、上一帧的拷贝在读 B，互不影响。
	 */
	private static TextureTarget targetA;
	private static TextureTarget targetB;

	/** 这一帧画进哪一张（{@link #draw} 开头轮换）。 */
	private static TextureTarget target;
	private static Projection projection;
	private static ProjectionMatrixBuffer projectionBuffer;

	/** 复用的顶点缓冲与批次、摆姿势用的栈、捕获器、量尺寸用的栈，以及"空相机"（名牌/影子都被丢掉，它只用来占位）。 */
	private static ByteBufferBuilder vertexBuffers;
	private static MultiBufferSource.BufferSource batches;
	private static PoseStack poseStack;

	/** 见 {@link #buildHeadLights}：离屏专用光照缓冲，以及交给 {@code setShaderLights} 的切片。 */
	private static GpuBuffer headLights;
	private static GpuBufferSlice HEAD_LIGHTS;
	private static final Capture CAPTURE = new Capture();
	private static final PoseStack scratchStack = new PoseStack();
	private static final PoseStack measureStack = new PoseStack();
	private static final CameraRenderState camera = new CameraRenderState();

	/**
	 * 模型单位 → 方块的换算系数。层定义里 {@code ModelPart.Cube} 的 {@code min/max} 是<b>模型单位</b>
	 * （16 个 = 1 方块），而几何渲染、{@code getExtentsForGui} 以及锚点/缩放都以<b>方块</b>为单位；
	 * 混用会让跨度差 16 倍、头像被画成一个小点（见 {@link #storeAnchor}）。
	 */
	private static final float MODEL_UNITS_PER_BLOCK = 16.0F;

	/** 复用的回读缓冲，以及"它正被一次在飞的回读占着"的标记。 */
	private static GpuBuffer readbackBuffer;
	private static boolean readbackBusy;

	/** 见 {@link #pump}：{@link #readbackBusy} 是什么时候置上的，用来兜住"回读迟迟不回来"。 */
	private static long readbackBusySince;

	/** 锚点与跨度（在"该头像区域的局部坐标系"里，见类注释与 {@link #measureIconBox}）；每类型一次，无分配。 */
	private static float anchorX;
	private static float anchorY;
	private static float anchorZ;
	private static float span;

	/** 量"头盒"时的中间量：两条候选（头自己的立方体 / 区域自己的立方体）各存一份，最后取优先的那条。 */
	private static final Matrix4f measureMatrix = new Matrix4f();
	private static final Vector3f measurePoint = new Vector3f();
	private static String measureSelfPath;
	private static float measureHeadVolume;
	/**
	 * "头盒"候选里当前那个立方体的<b>长边 / 短边</b>（越接近 1 越方）。为什么不按体积选：有些模型的
	 * {@code head} 把<b>脖子也并进同一个大立方体</b>（成年骆驼的三个是 7×8×19 头+脖、7×14×7、5×5×6 吻），
	 * 按体积会选中长条、脸只占画布一小条；按"最接近正方体"选就能选中脸那段。绝大多数生物只有一个立方体、不受影响。
	 */
	private static float measureHeadAspect = Float.MAX_VALUE;
	private static float measureHeadAnchorX;
	private static float measureHeadAnchorY;
	private static float measureHeadAnchorZ;
	private static float measureHeadSpan;
	private static float measureSelfVolume;
	/**
	 * S 分支（"区域自己的立方体"）候选的<b>长边 / 短边</b>，与 {@link #measureHeadAspect} 同理。
	 *
	 * <p>骆驼的三个无名立方体是 8×8×19（头+脖）、8×14×8、6×5×6（吻）：按体积会选中 19 长的那根、
	 * 图标里只有脖子没有脸；按"最接近正方体"选就能选中吻。
	 */
	private static float measureSelfAspect = Float.MAX_VALUE;
	/** S 分支的体积下限：小于"区域最大立方体"这个比例的候选直接不参与（跳过耳朵那种小立方体）。 */
	private static final float SELF_VOLUME_FLOOR = 0.1F;

	/** 见 {@link #iconRegion} 的说明。 */
	private static final float MIN_SANE_FACE_SPAN = 0.3F;
	/** 区域自己的立方体里最大的体积，用来给 S 分支定"体积下限"。 */
	private static float measureSelfMaxVolume;
	private static float measureSelfAnchorX;
	private static float measureSelfAnchorY;
	private static float measureSelfAnchorZ;
	private static float measureSelfSpan;

	/** 量"整棵子树包围盒"时用的累加器。 */
	private static boolean boxValid;
	private static float boxMinX;
	private static float boxMaxX;
	private static float boxMinY;
	private static float boxMaxY;
	private static float boxMinZ;
	private static float boxMaxZ;

	private MobHeadRenderer() {
	}

	/**
	 * 这个变种已经拿到头像了吗？拿到就返回那个终态结果（与 {@link MobHeads#resolve} 返回的是
	 * <b>同一个实例</b>，调用方可以放心缓存），否则返回 {@code null}。
	 */
	static MobHeads.Resolution result(MobHeadKey key) {
		Job job = JOBS.get(key);
		return job != null && job.state$ == State.DONE ? job.result : null;
	}

	/**
	 * 这个变种是否有"排队中/回读中"的任务。顺带兜一层超时：已发起回读却迟迟不兑现
	 * （见 {@link #READBACK_TIMEOUT_NANOS}）就改判失败、交回退化链。
	 */
	static boolean pending(MobHeadKey key) {
		Job job = JOBS.get(key);
		if (job == null || job.state$ != State.PENDING) {
			return false;
		}
		if (job.readbackIssuedNanos != 0L
				&& System.nanoTime() - job.readbackIssuedNanos > READBACK_TIMEOUT_NANOS) {
			job.state$ = State.FAILED;
			return false;
		}
		return true;
	}

	/** 是否已经为这个变种建过任务（不论成败）：用来区分"还没试过"和"试过但确定失败"。 */
	static boolean started(MobHeadKey key) {
		return JOBS.containsKey(key);
	}

	/**
	 * 给一个变种排队（懒：只有真正出现在雷达里的变种才会走到这里）。
	 *
	 * <p>只入队、不在这里画——真正的渲染在 {@link #pump()} 里按预算推进。
	 *
	 * @return {@link MobHeads.Resolution#PENDING}（已入队；调用方<b>不要缓存</b>，下一帧再来问）；
	 *         离屏这条路不可用（关掉了 / 不在渲染线程 / 建资源失败）时返回 {@code null}，由调用方判成 FAILED
	 */
	static MobHeads.Resolution request(EntityType<?> type, MobHeadKey key, EntityRenderer<?, ?> renderer,
			Model<?> model, EntityRenderState state, boolean wholeEntity) {
		if (disabled || !RenderSystem.isOnRenderThread()) {
			return null;
		}
		try {
			ensureResources();
			// 存进表的必须是不可变快照：查表用的那只实例每帧都在变
			Job job = new Job(type, key.copy(), renderer, model, state, wholeEntity);
			JOBS.put(job.key, job);
			QUEUE.add(job);
			return MobHeads.Resolution.PENDING;
		} catch (RuntimeException e) {
			disable("初始化失败", e);
			return null;
		}
	}

	/**
	 * 推进预渲染队列：每帧最多 {@value #MAX_JOBS_PER_FRAME} 个变种，且不超过
	 * {@link #FRAME_BUDGET_NANOS} 的时间预算；两次抽取之间至少隔 {@link #MIN_PUMP_INTERVAL_NANOS}。
	 *
	 * 由 {@link EntityRadar#refresh} 在收集完这一帧的生物之后调用。
	 */
	static void pump() {
		if (disabled || queueHead >= QUEUE.size() || !RenderSystem.isOnRenderThread()) {
			return;
		}
		// 上一张画布的回读还在飞时，一个字都不能往画布上画：画布既是这一帧要画的画布、又是上一次回读
		// 正在拷贝的源，回读没落地就往上画会让拷贝读到被改写的内容（图标混进两次内容，不止当次类型）。
		// 留一两帧没代价：头像本来就是异步出来的（{@code readbackBusy} 在回读完成时清掉）。
		if (readbackBusy) {
			if (System.nanoTime() - readbackBusySince <= READBACK_TIMEOUT_NANOS) {
				return; // 正常情况：等下一帧
			}
			// 兜底：回读迟迟不落地就放弃这只缓冲，否则会永远卡在"等回读"上；丢掉引用而不 close，
			// 回调可能还会碰它。
			readbackBusy = false;
			readbackBuffer = null;
		}
		long now = System.nanoTime();
		if (now - lastPumpNanos < MIN_PUMP_INTERVAL_NANOS) {
			return;
		}
		lastPumpNanos = now;
		int drawn = 0;
		while (drawn < MAX_JOBS_PER_FRAME && queueHead < QUEUE.size()) {
			render(QUEUE.get(queueHead++));
			drawn++;
			if (System.nanoTime() - now >= FRAME_BUDGET_NANOS) {
				break;
			}
		}
		if (queueHead >= QUEUE.size()) {
			// 排空：清空但不缩容，容量留着下一批复用（不产生每帧分配）
			QUEUE.clear();
			queueHead = 0;
		}
	}

	/** 渲染一个任务；任何异常都把它判成"确定失败"（终态），由上层的退化链接手。 */
	private static void render(Job job) {
		try {
			draw(job);
		} catch (RuntimeException e) {
			job.state$ = State.FAILED;
			if (!warnedFailure) {
				warnedFailure = true;
				LOGGER.info("离屏头像渲染失败，退化为刷怪蛋/色点 | {}", e.toString());
			}
		} finally {
			// 无论成败都把共享模型复位（正常路径下面已经配平，这里兜住异常路径）
			job.model.resetPose();
		}
	}

	/**
	 * 真的画一次：摆姿势 → 捕获全部渲染层 → 量锚点 → 存全局状态 → 设投影/光照/剪刀 → 逐层画头像区域
	 * → 发起回读 → 还原状态。回读是异步的，像素到手在 {@link #complete} 里处理。
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void draw(Job job) {
		Model<?> model = job.model;

		// 0) 先轮换离屏目标：上一帧发起的那次回读正在读另一张，这一帧绝不碰它（见 targetA/targetB）。
		target = target == targetA ? targetB : targetA;

		// 1) 姿势：先回设计默认值（清掉上一次世界渲染的残留），再按归零过的状态摆一次。
		//    原版只在"该生物有渲染层"时才 setupAnim，所以这里必须自己补上。
		model.resetPose();
		((Model) model).setupAnim(job.state);

		// 2) 捕获这一帧全部渲染层：原版渲染器把它要画的东西"提交"出来，这里只做记录。
		CAPTURE.reset();
		scratchStack.pushPose();
		try {
			// 通用提交入口（见 Job#renderer）：活体走 LivingEntityRenderer，末影龙走 EntityRenderer，
			// 两者的 submit 都是"把要画的东西交给 collector"，正是要拦的东西。
			((EntityRenderer) job.renderer).submit(job.state, scratchStack, CAPTURE, camera);
		} finally {
			scratchStack.popPose();
		}

		// 3) 选"主层"（原版总是先提交主模型）并量锚点/缩放。
		Layer main = null;
		for (Layer layer : CAPTURE.layers) {
			if (layer.model != null) {
				main = layer;
				break;
			}
		}
		if (main == null || CAPTURE.layers.isEmpty()) {
			// 捕获不到任何"模型层"：这个变种交给退化链（刷怪蛋/菱形标记），绝不硬画
			job.state$ = State.FAILED;
			return;
		}
		// 画哪一块：整只那档是整个模型，否则是"头像区域"（head 所在头组的整棵子树，见 MobHeads#iconRegion）。
		// 用捕获到的那一份模型（layer.model）——那是渲染器这一帧真正提交的实例。
		ModelPart region = job.wholeEntity ? main.model.root() : MobHeads.iconRegion(job.type, main.model);
		if (region == null) {
			job.state$ = State.FAILED;
			return;
		}
		// 见 GUARDIAN_SPIKE_SCALE：守卫者的棘刺要在取景与绘制**都**生效（量不到尖端就会被裁掉）。
		// 改的是共享模型，但不用自己配平——外层 resolve 的 finally 一定会 resetPose（连缩放一起还原）。
		if (job.type == EntityType.GUARDIAN || job.type == EntityType.ELDER_GUARDIAN) {
			stretchGuardianSpikes(model);
		}

		// base 把该区域这一帧的姿态抵消掉（整只那档不抵消：根的姿态通常是单位阵）。鱼类例外：整只那档
		// 也要绕 Y 转 90°，以<b>横截面（侧视）</b>面对相机（鱼身是"一片"，正面只是一条竖缝 + 一堆乱翅）。
		Matrix4f wholeBase = null;
		if (job.wholeEntity && SIDE_VIEW_TYPES.contains(job.type)) {
			wholeBase = SIDE_VIEW_ROTATION;
		} else if (job.wholeEntity && TOP_VIEW_TYPES.contains(job.type)) {
			wholeBase = TOP_VIEW_ROTATION;
		}
		// 见 KEEP_MODEL_POSE_TYPES：这几类不抵消姿态，直接用模型设计好的姿势（疣猪兽是低着头的）。
		boolean keepPose = job.wholeEntity || keepModelPose(job.type);
		Matrix4f base = keepPose ? wholeBase : inverseOf(region);
		// "画整只、取景对头"（海豚）：画的是整棵模型，但按"头盒"来定中心与跨度，再放大取景范围
		boolean frameOnHead = job.wholeEntity && MobHeads.frameOnHead(job.type);
		boolean measured;
		boolean preferFaceCube = MobHeads.preferFaceCube(job.type);
		if (frameOnHead) {
			measured = measureIconBox(region, base == null ? new Matrix4f() : base, preferFaceCube);
			if (measured) {
				span *= frameExpand(job.type);
				// 马类：只框"头盒"时上沿卡在耳朵尖、下沿切掉胸口，只剩一大片平脖子。把跨度放到
				// {@value #HORSE_FRAME_EXTEND} 倍并把中心同步下移一半增量：上沿不动（耳朵不裁），
				// 多出来的全部落在下方，画面变成"头 + 上身（胸口/前腿根）"。
				boolean extendTarget = horseFamily(job.type);
				if (extendTarget) {
					float grown = span * HORSE_FRAME_EXTEND;
					anchorY += (grown - span) * 0.5F;
					span = grown;
				}
			} else {
				// 模型里找不到"头"立方体：退回"整只"取景，绝不能判失败（否则整条鱼降级成刷怪蛋）
				measured = measureSubtree(region, base);
			}
		} else if (job.wholeEntity || MobHeads.frameWholeRegion(job.type)) {
			// 整只那一档、以及"要框整个头区域"的类型（骆驼：头部件里堆着吻+头+脖）都走整棵子树
			measured = measureSubtree(region, base);
			if (measured && MobHeads.trimNeck(job.type)) {
				// "脖子有些长了"：整棵子树里脖子最长，收一点取景就能压短。关键必须"只从脖子那一头收"：
				// 模型 +Y 朝下、头在 boxMinY 一侧，早期绕中心等比收会把头顶的角一起裁掉。现在跨度收一点、
				// 同时把取景框整体上移，让上沿停在头顶上方（余量试到 14% 才容得下头顶最高那撮）。
				span *= WHOLE_REGION_TRIM;
				anchorY = boxMinY + span * 0.36F;
			}
			if (measured && (job.type == EntityType.WARDEN || job.type == EntityType.WITCH)) {
				// 两者的"头之外"部件都特别长（监守者的耳朵、女巫的帽子），按整棵子树取景时头只占一小块。
				// <b>监守者必须收框</b>：不收框时耳朵细成碎点、会被 {@code MobHeads.dropDetachedSlivers}
				// 当噪声摘掉，收 0.8 后耳朵粗且与头相接；<b>女巫不能收框</b>：帽檐被切到画布外会沿切口
				// 留孤立像素，用图标档位放大。
				span *= job.type == EntityType.WARDEN ? WARDEN_FRAME_TRIM : WITCH_FRAME_TRIM;
				anchorY = job.type == EntityType.WARDEN
						? boxMinY + span * 0.5F // 耳朵朝下垂（模型 +Y 向下），头在上侧
						: boxMaxY - span * 0.5F; // 帽子上翘，头在下侧
			}
		} else {
			measured = measureIconBox(region, base, preferFaceCube);
		}
		// 守卫者族：棘刺又细又长、正好顶在包围盒边界上，按原样取景时尖端贴到画布边缘、像"没伸出来"，
		// 给整框放一点余量让棘刺完整落在画面里。
		if (measured && (job.type == EntityType.GUARDIAN || job.type == EntityType.ELDER_GUARDIAN)) {
			span *= GUARDIAN_FRAME_MARGIN;
		}
		if (!measured) {
			job.state$ = State.FAILED;
			return;
		}
		if (!(span > 1.0E-4F)) {
			// 投影到画布上退化成一条线/一个点：这变种交给退化链（它有退化的兜底）
			job.state$ = State.FAILED;
			return;
		}
		float scale = FILL * CANVAS / span;

		// 4) 哪些部位属于"头像区域"：单部位提交的层要拿它对一下（不属于就不画，免得把身体画进来）。
		//    注意要把捕获到的每个模型各自算一遍：护甲这类层用的是它自己的模型。
		Set<ModelPart> headParts = null;
		if (!job.wholeEntity) {
			headParts = new HashSet<>();
			for (Layer layer : CAPTURE.layers) {
				if (layer.model == null) {
					continue;
				}
				ModelPart part = MobHeads.iconRegion(job.type, layer.model);
				if (part != null) {
					headParts.addAll(part.getAllParts());
				}
			}
		}

		// 5) 存下所有会被改到的全局状态（清单与理由见类注释）
		GpuTextureView oldColor = RenderSystem.outputColorTextureOverride;
		GpuTextureView oldDepth = RenderSystem.outputDepthTextureOverride;
		GpuBufferSlice oldProjection = RenderSystem.getProjectionMatrixBuffer();
		ProjectionType oldProjectionType = RenderSystem.getProjectionType();
		GpuBufferSlice oldLights = RenderSystem.getShaderLights();
		ScissorState scissor = RenderSystem.getScissorStateForRenderTypeDraws();
		boolean scissorWasOn = scissor.enabled();
		int scissorX = scissor.x();
		int scissorY = scissor.y();
		int scissorWidth = scissor.width();
		int scissorHeight = scissor.height();
		Matrix4fStack modelView = RenderSystem.getModelViewStack();
		boolean modelViewPushed = false;
		GpuBuffer readback = null;
		boolean readbackIssued = false;
		try {
			// 6) 绑到离屏目标、清屏、换正交投影
			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			RenderSystem.outputColorTextureOverride = target.getColorTextureView();
			RenderSystem.outputDepthTextureOverride = target.getDepthTextureView();
			// 颜色清成透明黑（外扩描边依赖透明）；深度清成 1.0（最远），于是只有真正画到的像素才可能通过
			// 深度比较——配合下面正确的 zNear/zFar 才是"画正面"的关键。
			encoder.clearColorAndDepthTextures(target.getColorTexture(), 0, target.getDepthTexture(), 1.0);
			// zNear 取正、zFar 取负：近平面落在模型 -Z 那一侧（见类注释"深度"）。invertY 取 false：那一次
			// Y 镜像放在 scale 里，投影这边不再镜像——两处都镜像等于没镜像，绕序又会翻回去。
			projection.setupOrtho(DEPTH_RANGE, -DEPTH_RANGE, CANVAS, CANVAS, false);
			RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(projection), ProjectionType.ORTHOGRAPHIC);
			// 关掉剪刀：当前多半正处在地图矩形的裁剪里，不关会把离屏画面一起裁掉
			RenderSystem.disableScissorForRenderTypeDraws();
			// 光照：见 HEAD_LIGHTS——自己造一支"正对相机"的光，可见面直接拿到 1.0。
			RenderSystem.setShaderLights(HEAD_LIGHTS);

			// 7) 画：模型视图矩阵置单位阵，摆放全部写在 PoseStack 里
			modelView.pushMatrix();
			modelViewPushed = true;
			modelView.identity();
			try {
				for (Layer layer : CAPTURE.layers) {
					ModelPart part;
					Matrix4f layerBase;
					if (layer.model != null) {
						// 所有"模型层"都画：村民的职业衣服、群系皮肤、羊毛、项圈都在附加层里，只画主层会
						// 全丢。每一层都用"它自己那个头像区域"，护甲这类层有自己的头组、各归各位。
						part = job.wholeEntity ? layer.model.root() : MobHeads.iconRegion(job.type, layer.model);
						// 与上面的 base 同一口径：量出来什么姿态，画的时候就什么姿态。
						layerBase = keepPose ? wholeBase : (part == null ? null : inverseOf(part));
					} else {
						if (headParts == null || !headParts.contains(layer.part)) {
							continue; // 不属于任何头像区域：不画（绝不把身体/腿带进来）
						}
						// 单部位提交没有"它是哪个组的孩子"这条信息，用主模型那个组去抵消姿态：
						// 对"组的直接子部位"（头自己）正好等价，对更深的部位也与旧实现同口径。
						part = layer.part;
						layerBase = base;
					}
					if (part == null || layer.renderType.isOutline()) {
						continue; // 发光描边那种"额外的圈"不要：改为画剪影外扩的描边
					}
					poseStack.pushPose();
					try {
						// 见 LAYER_DEPTH_BIAS：主层不动，后续层往相机推一丁点，且必须放在 mulPose(layerBase)
						// 之前——那时还在视图空间，Z 就是"离相机多远"；之后会被 layerBase 转走而失效。
						poseStack.translate(CANVAS / 2.0F, CANVAS / 2.0F, layer == main ? 0.0F : -LAYER_DEPTH_BIAS);
						// Y 上那个负号必须且只能出现一次（见类注释"Y 镜像"与"面剔除"）：整条链上镜像次数为
						// 奇数时绕序才和原版一致，开着背面剔除的管线（发光眼用的 eyes）才画得出来；偶数次会让
						// 那些层被整片剔掉（"铜傀儡眼部没有光"的根因）。镜像必须放在 scale 而不是投影里：
						// 只有 scale 的镜像作用于法线，而离屏光照按"法线被 Y 镜像过"调系数，挪到投影会让整批头像变暗。
						poseStack.scale(scale, -scale, scale);
						poseStack.translate(-anchorX, -anchorY, -anchorZ);
						if (layerBase != null) {
							poseStack.mulPose(layerBase);
						}
						// 见 MobHeads#hideExcluded：把要排除的子部件（快乐恶魂的触肢）临时藏起来，
						// 画完立刻还原——ModelPart#render 是整树提交，只能借原版的可见性开关来排除。
						List<ModelPart> hidden = MobHeads.hideExcluded(job.type, part);
						try {
							renderLayer(job.type, part, poseStack, layer);
							// 每画完一层就立刻提交：批处理按 RenderType 分桶，若留到最后一起 endBatch，
							// 层序就交给分桶顺序而非原版提交顺序；逐层提交能保留"先主模型、后附加层"。
							batches.endBatch();
						} finally {
							MobHeads.restoreHidden(hidden);
						}
					} finally {
						poseStack.popPose();
					}
				}
			} finally {
				// 姿势栈与批次都是复用的静态对象：不管画成没画成都要收干净
				batches.endBatch();
			}
			modelView.popMatrix();
			modelViewPushed = false;

			// 8) 发起回读（异步：像素到手在 complete 里）
			GpuBuffer issued = acquireReadbackBuffer();
			readback = issued;
			encoder.copyTextureToBuffer(target.getColorTexture(), issued, 0L,
					() -> complete(job, issued, encoder), 0);
			readbackIssued = true;
			job.readbackIssuedNanos = System.nanoTime();
		} finally {
			// 9) 还原：把第 5 步存下的东西原样放回去
			if (modelViewPushed) {
				modelView.popMatrix();
			}
			RenderSystem.outputColorTextureOverride = oldColor;
			RenderSystem.outputDepthTextureOverride = oldDepth;
			RenderSystem.setProjectionMatrix(oldProjection, oldProjectionType);
			RenderSystem.setShaderLights(oldLights);
			if (scissorWasOn) {
				RenderSystem.enableScissorForRenderTypeDraws(scissorX, scissorY, scissorWidth, scissorHeight);
			} else {
				RenderSystem.disableScissorForRenderTypeDraws();
			}
			if (!readbackIssued && readback != null) {
				releaseReadbackBuffer(readback);
			}
		}
	}

	/**
	 * 提交一层的几何。默认直接用 {@link ModelPart#render}（它把 {@code visible}/{@code skipDraw} 算进去）。
	 * 只有"部位里混着多余立方体"的类型（见 {@link MobHeads#trimFlatCube}）才退到<b>立方体</b>这一级：
	 * {@code render} 一次提交整棵子树、没法只丢几个立方体，而 {@code ModelPart.Cube#compile} 是公开的，
	 * 配 {@link ModelPart#visit} 正好逐个筛。注意 {@code visit} 不看 {@code visible}，所以这条分支只给
	 * "头像区域本身是个无子节点的叶部位"的类型用。
	 */
	private static void renderLayer(EntityType<?> type, ModelPart part, PoseStack poseStack, Layer layer) {
		VertexConsumer buffer = batches.getBuffer(layer.renderType);
		if (!MobHeads.hasCubeTrim(type)) {
			part.render(poseStack, buffer, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, layer.color);
			return;
		}
		part.visit(poseStack, (pose, name, index, cube) -> {
			if (MobHeads.trimFlatCube(type, cube)) {
				return;
			}
			cube.compile(pose, buffer, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, layer.color);
		});
	}

	/**
	 * 回读完成：把 {@value #CANVAS}×{@value #CANVAS} 的像素搬成 {@link NativeImage}、超采样降采样到
	 * {@value #SIZE}×{@value #SIZE}，交给 {@link MobHeads#adopt} 去外扩描边、注册贴图。
	 *
	 * <p><b>要翻一次行序</b>：那一次 Y 镜像放在 {@code scale} 里、投影没镜像，所以读回来的画面是倒的。
	 * 用翻转而不是"渲染里再镜像一次"，是因为多一次镜像会翻转绕序、把开着背面剔除的层（发光眼）剔空。
	 */
	private static void complete(Job job, GpuBuffer buffer, CommandEncoder encoder) {
		try (GpuBuffer.MappedView view = encoder.mapBuffer(buffer, true, false)) {
			ByteBuffer pixels = view.data();
			NativeImage canvas = new NativeImage(CANVAS, CANVAS, false);
			for (int y = 0; y < CANVAS; y++) {
				int sourceY = CANVAS - 1 - y;
				for (int x = 0; x < CANVAS; x++) {
					int index = (sourceY * CANVAS + x) * 4;
					int red = pixels.get(index) & 0xFF;
					int green = pixels.get(index + 1) & 0xFF;
					int blue = pixels.get(index + 2) & 0xFF;
					int alpha = pixels.get(index + 3) & 0xFF;
					// NativeImage 的 setPixel 收的是 ARGB；上面四个分量就是纹理 RGBA8 的内存顺序
					canvas.setPixel(x, y, alpha << 24 | red << 16 | green << 8 | blue);
				}
			}
			NativeImage icon = downsample(canvas);
			canvas.close();
			MobHeads.Head head = MobHeads.adopt(job.type, job.key, icon);
			if (head == null) {
				// 画布上一个不透明像素都没有：这个变种认了，交给退化链
				job.state$ = State.FAILED;
			} else {
				job.result = new MobHeads.Resolution(head, false);
				job.state$ = State.DONE;
			}
		} catch (RuntimeException e) {
			job.state$ = State.FAILED;
			if (!warnedFailure) {
				warnedFailure = true;
				LOGGER.info("离屏头像回读失败，退化为刷怪蛋/色点 | {}", e.toString());
			}
		} finally {
			releaseReadbackBuffer(buffer);
		}
	}

	/**
	 * 超采样降采样：每一格取 {@value #SUPERSAMPLE}×{@value #SUPERSAMPLE} 个像素做<b>按 alpha 加权</b>的
	 * 盒式平均。
	 *
	 * <p>直接平均 RGB 会让透明像素（黑）把边界掺出黑边；按 alpha 加权得到的正是"该格真正有内容的颜色的
	 * 平均"，边缘只剩柔和的半透明（贴图采样器是线性 + CLAMP_TO_EDGE，见 {@link MobHeads}）。
	 */
	private static NativeImage downsample(NativeImage source) {
		NativeImage icon = new NativeImage(SIZE, SIZE, false);
		int samples = SUPERSAMPLE * SUPERSAMPLE;
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				long red = 0L;
				long green = 0L;
				long blue = 0L;
				long alpha = 0L;
				for (int sy = 0; sy < SUPERSAMPLE; sy++) {
					for (int sx = 0; sx < SUPERSAMPLE; sx++) {
						int argb = source.getPixel(x * SUPERSAMPLE + sx, y * SUPERSAMPLE + sy);
						int a = argb >>> 24;
						red += (long) ((argb >>> 16) & 0xFF) * a;
						green += (long) ((argb >>> 8) & 0xFF) * a;
						blue += (long) (argb & 0xFF) * a;
						alpha += a;
					}
				}
				if (alpha == 0L) {
					continue; // 整格透明：保持透明
				}
				int outAlpha = (int) (alpha / samples);
				icon.setPixel(x, y, outAlpha << 24
						| (int) (red / alpha) << 16
						| (int) (green / alpha) << 8
						| (int) (blue / alpha));
			}
		}
		return icon;
	}

	/**
	 * 量"头像区域"里该对焦的那个立方体（<b>头盒</b>）：锚点与跨度都从它来，且表达在<b>区域局部坐标系</b>里
	 * ——与绘制用的 {@code base = inverseOf(region)} 同一套坐标，所以"量出来的脸中心"与"画上去的脸"必然对得上。
	 *
	 * <p>优先取"头自己"的立方体（路径最后一段是 {@code head}）：头组里 head 与鬃/吻平级，只有 head 自己的
	 * 立方体才是"脸该对上的地方"，鬃/吻再大也不参与对焦；头自己一个立方体都没有时才退回"区域里最大的那个"。
	 * 变换用 {@code base × pose}（{@code visit} 给的 pose 是从区域到该部位的矩阵），乘上 base 后区域姿态被抵消。
	 * 区域里一个立方体都没有时返回 {@code false}，由调用方退回整棵子树的包围盒（见 {@link #measureSubtree}）。
	 */
	private static boolean measureIconBox(ModelPart region, Matrix4f base, boolean preferFaceCube) {
		measureSelfPath = null;
		measureHeadVolume = -1.0F;
		measureHeadAspect = Float.MAX_VALUE;
		measureSelfVolume = -1.0F;
		measureSelfAspect = Float.MAX_VALUE;
		measureSelfMaxVolume = 0.0F;
		region.visit(measureStack, (pose, name, index, cube) -> {
			if (measureSelfPath == null) {
				measureSelfPath = name; // 前序：第一条路径就是区域自己
			}
			// 区域**自己的**立方体（不是子部件的）体积上限——第二遍给 S 分支做"体积下限"用
			if (measureSelfPath.equals(name)) {
				float volume = (cube.maxX - cube.minX) * (cube.maxY - cube.minY) * (cube.maxZ - cube.minZ);
				measureSelfMaxVolume = Math.max(measureSelfMaxVolume, volume);
			}
		});
		region.visit(measureStack, (pose, name, index, cube) -> {
			if (measureSelfPath == null) {
				measureSelfPath = name; // 前序：第一条路径就是区域自己
			}
			boolean head = isHeadLeaf(name);
			if (!head && !measureSelfPath.equals(name)) {
				return;
			}
			float volume = (cube.maxX - cube.minX) * (cube.maxY - cube.minY) * (cube.maxZ - cube.minZ);
			if (head) {
				float aspect = cubeAspect(cube);
				// 先比"方不方"，一样方再比体积（见 measureHeadAspect 的说明）
				if (aspect < measureHeadAspect
						|| (aspect == measureHeadAspect && volume > measureHeadVolume)) {
					measureHeadAspect = aspect;
					measureHeadVolume = volume;
					storeAnchor(base, pose, cube, true);
				}
			} else if (preferFaceCube
					? (cubeAspect(cube) < measureSelfAspect
							|| (cubeAspect(cube) == measureSelfAspect && volume > measureSelfVolume))
					: (volume > measureSelfVolume && volume >= measureSelfMaxVolume * SELF_VOLUME_FLOOR)) {
				// 见 MobHeads#preferFaceCube：骆驼这类"头部件里堆着头+脖+吻"的模型按"最方"选，
				// 其余一律按体积最大选（全局改成"最方"会弄坏羊驼成年与幼年骆驼）
				measureSelfAspect = cubeAspect(cube);
				measureSelfVolume = volume;
				storeAnchor(base, pose, cube, false);
			}
		});
		if (measureHeadVolume >= 0.0F) {
			anchorX = measureHeadAnchorX;
			anchorY = measureHeadAnchorY;
			anchorZ = measureHeadAnchorZ;
			span = fitSpan(measureHeadSpan);
			return span >= MIN_SANE_FACE_SPAN || measureSubtree(region, base);
		}
		if (measureSelfVolume >= 0.0F) {
			anchorX = measureSelfAnchorX;
			anchorY = measureSelfAnchorY;
			anchorZ = measureSelfAnchorZ;
			span = fitSpan(measureSelfSpan);
			if (span >= MIN_SANE_FACE_SPAN) {
				return true;
			}
			if (!measureSubtree(region, base)) {
				return false;
			}
			// 再留一点余量：山羊的两只角长在头组的最上沿、正好卡着包围盒边界，不留余量会被画布
			// 裁掉一只（"山羊左侧角没了"）
			span *= MIN_SANE_SUBTREE_MARGIN;
			return true;
		}
		return false;
	}

	/** 见 {@link #measureIconBox}：退回"整个区域包围盒"取景时的额外余量。 */
	private static final float MIN_SANE_SUBTREE_MARGIN = 1.15F;

	/** 立方体的"长边 / 短边"：越接近 1 越方，见 {@link #measureHeadAspect}。 */
	private static float cubeAspect(ModelPart.Cube cube) {
		float x = cube.maxX - cube.minX;
		float y = cube.maxY - cube.minY;
		float z = cube.maxZ - cube.minZ;
		float max = Math.max(x, Math.max(y, z));
		float min = Math.min(x, Math.min(y, z));
		// 退化立方体（某一边为 0，例如"山羊胡"）直接判成最不方，免得被选中
		return min <= 0.0F ? Float.MAX_VALUE : max / min;
	}

	/**
	 * 由"脸盒"的跨度算出最终跨度：<b>以脸为准，只多留一点余量给耳朵</b>。
	 *
	 * <p>曾试"把头组整体框进来"，但马/驴/骡的 {@code head_parts} 里挂着很长的<b>鬃</b>（实测跨度 0.825，
	 * 而脸盒只有 0.375），按整组取跨度会让脸只占画布两成多、其余被鬃填满。现在用"脸盒 ×
	 * {@value #HEAD_FIT_MARGIN}"：脸占主体，耳朵（离脸很近）仍在画面内，鬃/颈被天然裁掉。
	 */
	private static float fitSpan(float faceSpan) {
		return faceSpan * HEAD_FIT_MARGIN;
	}

	/*
	 * 试过并回退的一条（别再加）：头盒高/宽 > 1.5 就当作"头+脖合并的大盒"、只取靠脸那一端。它修好了
	 * 成年骆驼，却把幼体骆驼、马、驴、骡全弄坏——这些头盒本身就不方，阈值一碰就误伤。要可靠只能按类型
	 * 指定部位（见 {@link MobHeads} 的 REGION_PART_OVERRIDES）。
	 */

	/**
	 * 把一个立方体的锚点/跨度算到区域局部坐标系里并暂存起来。
	 *
	 * <p>锚点：X 取该部位局部坐标系的原点（枢轴）——头左右本来就对称，不该被某个偏心的大块拉走；
	 * Y/Z 取盒中心——枢轴在脖子那一端，只有盒中心才是"脸该对上的地方"。
	 */
	private static void storeAnchor(Matrix4f base, PoseStack.Pose pose, ModelPart.Cube cube, boolean head) {
		measureMatrix.set(base).mul(pose.pose());
		// 单位换算（"头像只有几个像素"的根因）：cube.min/max 是模型单位（1/16 方块，马的头盒量出来是 6.0），
		// 而 pose/base 与几何渲染用的都是方块。不换算等于把跨度算大 16 倍：缩放小 16 倍、头被画成几个像素的点。
		measurePoint.set(0.0F, (cube.minY + cube.maxY) * 0.5F / MODEL_UNITS_PER_BLOCK,
				(cube.minZ + cube.maxZ) * 0.5F / MODEL_UNITS_PER_BLOCK);
		measureMatrix.transformPosition(measurePoint);
		float boxSpan = Math.max(cube.maxX - cube.minX, cube.maxY - cube.minY) / MODEL_UNITS_PER_BLOCK;
		if (head) {
			measureHeadAnchorX = measurePoint.x;
			measureHeadAnchorY = measurePoint.y;
			measureHeadAnchorZ = measurePoint.z;
			measureHeadSpan = boxSpan;
		} else {
			measureSelfAnchorX = measurePoint.x;
			measureSelfAnchorY = measurePoint.y;
			measureSelfAnchorZ = measurePoint.z;
			measureSelfSpan = boxSpan;
		}
	}

	/** 这个立方体所属部位的叶名是不是 {@code head}（{@code visit} 给的路径如 {@code /head_parts/head}）。 */
	private static boolean isHeadLeaf(String name) {
		int slash = name.lastIndexOf('/');
		String leaf = slash < 0 ? name : name.substring(slash + 1);
		return leaf.equals("head");
	}

	/**
	 * 量整棵子树的包围盒（该部位自己没有立方体、或"整只图标"那一档用）：
	 * 锚点取包围盒中心，跨度取 X/Y 的长边。
	 */
	private static boolean measureSubtree(ModelPart part, Matrix4f base) {
		if (!measureSubtreeBounds(part, base)) {
			return false;
		}
		span = Math.max(boxMaxX - boxMinX, boxMaxY - boxMinY);
		anchorX = (boxMinX + boxMaxX) * 0.5F;
		anchorY = (boxMinY + boxMaxY) * 0.5F;
		anchorZ = (boxMinZ + boxMaxZ) * 0.5F;
		return true;
	}

	/**
	 * 只量整棵子树的包围盒（填好 {@link #boxMinX} 等），返回是否有内容；<b>不动</b>锚点与跨度。
	 * {@link #measureIconBox} 拿它量"头组整体"有多大（见 {@link #HEAD_FIT_MARGIN}）。
	 */
	private static boolean measureSubtreeBounds(ModelPart part, Matrix4f base) {
		boxValid = false;
		measureStack.pushPose();
		try {
			if (base != null) {
				measureStack.mulPose(base);
			}
			part.getExtentsForGui(measureStack, MobHeadRenderer::accumulateBox);
		} finally {
			measureStack.popPose();
		}
		return boxValid;
	}

	/** 累加一个顶点到包围盒（{@link ModelPart#getExtentsForGui} 会吐出这个部位子树的所有顶点）。 */
	private static void accumulateBox(Vector3fc point) {
		float x = point.x();
		float y = point.y();
		float z = point.z();
		if (!boxValid) {
			boxValid = true;
			boxMinX = boxMaxX = x;
			boxMinY = boxMaxY = y;
			boxMinZ = boxMaxZ = z;
			return;
		}
		boxMinX = Math.min(boxMinX, x);
		boxMaxX = Math.max(boxMaxX, x);
		boxMinY = Math.min(boxMinY, y);
		boxMaxY = Math.max(boxMaxY, y);
		boxMinZ = Math.min(boxMinZ, z);
		boxMaxZ = Math.max(boxMaxZ, z);
	}

	/**
	 * 取这个部位"自身姿态"的逆矩阵：乘在外面，子树顶点就回到层定义里的原始几何，与实体此刻的朝向/缩放无关。
	 * 手法：{@code ModelPart#visit} 内部对每个部位就是 {@code pushPose → translateAndRotate}，所以在一只空
	 * PoseStack 上单独调 {@code translateAndRotate}，拿到的正是该部位立方体被变换时看到的矩阵。
	 */
	private static Matrix4f inverseOf(ModelPart part) {
		PoseStack stack = new PoseStack();
		part.translateAndRotate(stack);
		return new Matrix4f(stack.last().pose()).invert();
	}

	/**
	 * 见 {@link #GUARDIAN_SPIKE_SCALE}：把 {@code head} 下 12 根棘刺的缩放设成放大档。不用自己还原——
	 * {@code resetPose()} 会把位移/旋转/缩放一起恢复（每次 {@code draw} 之前、外层 finally 里都会跑）。
	 */
	private static void stretchGuardianSpikes(Model<?> model) {
		ModelPart head = childPart(model.root(), "head");
		if (head == null) {
			return;
		}
		for (int i = 0; i < GUARDIAN_SPIKE_COUNT; i++) {
			ModelPart spike = childPart(head, GUARDIAN_SPIKE_PREFIX + i);
			if (spike == null) {
				continue;
			}
			spike.xScale = GUARDIAN_SPIKE_SCALE;
			spike.yScale = GUARDIAN_SPIKE_SCALE;
			spike.zScale = GUARDIAN_SPIKE_SCALE;
		}
	}

	/** 取直接子部位；没有就返回 {@code null}（原版 {@code getChild} 找不到会抛异常，不能拿它探测）。 */
	private static ModelPart childPart(ModelPart parent, String name) {
		return parent != null && parent.hasChild(name) ? parent.getChild(name) : null;
	}

	/** 懒建所有一次性资源（只在渲染线程；{@code TextureTarget} 的构造本身会断言渲染线程）。 */
	private static void ensureResources() {
		if (target != null) {
			return;
		}
		// 两张轮换，见 targetA/targetB 的说明
		targetA = new TextureTarget("sinanmap mob head A", CANVAS, CANVAS, true);
		targetB = new TextureTarget("sinanmap mob head B", CANVAS, CANVAS, true);
		target = targetA;
		projection = new Projection();
		projectionBuffer = new ProjectionMatrixBuffer("sinanmap mob heads");
		vertexBuffers = new ByteBufferBuilder(VERTEX_BUFFER_BYTES);
		batches = MultiBufferSource.immediate(vertexBuffers);
		poseStack = new PoseStack();
		buildHeadLights();
	}

	/**
	 * 离屏专用的一支"正对相机"的光（std140：两个 {@code vec3}，各占 16 字节）。
	 *
	 * <p><b>不用原版的 {@code Lighting.Entry.ENTITY_IN_UI}</b>：它那两盏光的 Z 分量一个 +1 一个 0，而本投影
	 * 的可见面是<b>正对相机的 -Z 面</b>（法线 {@code (0,0,-1)}），两盏都照不到，光照系数只剩环境项 {@code 0.4}、
	 * 整批头像被均匀压暗。靠事后补偿（反除系数）虽能修好受光层，却会把<b>本就不吃光照</b>的层一起放大
	 * （发光眼已是贴图本色，再乘补偿就被裁成白色）。
	 *
	 * <p>所以把光换成"相机方向"：第一盏正对相机（{@code (0,0,-1)}），可见面 {@code dot=1}、系数恰好 {@code 1.0}；
	 * 第二盏沿用原版那一盏，给上下倾斜的面留一点层次。
	 */
	private static void buildHeadLights() {
		// 两盏光都写成 std140 对齐后的 4 个 float
		float[] lights = {
				0.0F, 0.0F, -1.0F, 0.0F,
				-0.19612F, -0.98058F, 0.0F, 0.0F,
		};
		ByteBuffer data = ByteBuffer.allocateDirect(lights.length * Float.BYTES).order(ByteOrder.nativeOrder());
		for (float value : lights) {
			data.putFloat(value);
		}
		data.flip();
		headLights = RenderSystem.getDevice().createBuffer(() -> "sinanmap mob head lights",
				GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, data);
		HEAD_LIGHTS = headLights.slice();
	}

	/**
	 * 取一只回读缓冲：优先复用 {@link #readbackBuffer}，它正被一次在飞的回读占着时临时开一只（回调里关掉）。
	 * 否则"同一只缓冲被两次回读先后写、回调却按相反先后读"会读到别人的像素。
	 */
	private static GpuBuffer acquireReadbackBuffer() {
		if (readbackBuffer == null) {
			readbackBuffer = createReadbackBuffer();
		}
		if (!readbackBusy) {
			readbackBusy = true;
			readbackBusySince = System.nanoTime();
			return readbackBuffer;
		}
		return createReadbackBuffer();
	}

	/** 回读缓冲：要能当拷贝目标（{@code USAGE_COPY_DST}）并且能被 CPU 映射读（{@code USAGE_MAP_READ}）。 */
	private static GpuBuffer createReadbackBuffer() {
		return RenderSystem.getDevice().createBuffer(
				() -> "sinanmap mob head readback",
				GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
				(long) CANVAS * CANVAS * 4);
	}

	/** 归还回读缓冲：复用的那只把标记清掉，临时开的那只直接关掉。 */
	private static void releaseReadbackBuffer(GpuBuffer buffer) {
		if (buffer == readbackBuffer) {
			readbackBusy = false;
		} else {
			buffer.close();
		}
	}

	/** 永久关掉离屏这条路（上层退化成刷怪蛋/菱形标记），失败只记一次日志。 */
	private static void disable(String what, RuntimeException e) {
		disabled = true;
		if (!warnedFailure) {
			warnedFailure = true;
			LOGGER.info("离屏头像{}，退化为刷怪蛋/色点 | {}", what, e.toString());
		}
	}
}
