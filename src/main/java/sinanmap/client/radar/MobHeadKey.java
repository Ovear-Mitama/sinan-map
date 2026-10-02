package sinanmap.client.radar;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.entity.animal.equine.Llama;
import net.minecraft.world.entity.animal.feline.Cat;
import net.minecraft.world.entity.animal.fish.TropicalFish;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.animal.parrot.Parrot;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.trim.ArmorTrim;

/**
 * 头像的<b>变种键</b>：同一个 {@code EntityType} 下的不同"长相"必须各占一份缓存。
 *
 * <p>键 = 变种判别值（{@link #variant()}）+ 装备判别值（{@link #equipment()}）+ 类型 + 是否幼体；
 * 只按类型做键会让村民职业/等级、羊毛色、马的花纹、装备差异<b>全部共用第一张图</b>。
 * 贴图<u>不进键</u>：它只能由渲染状态算（{@code createRenderState} + {@code getTextureLocation}），
 * 而键每帧每生物都要算，造渲染状态代价远大于收益；上述字段对原版生物已决定贴图，
 * 故"查表命中"与"贴图命中"等价（模组生物会共用一张图，有界、可接受）。
 *
 * <p><b>每帧零分配</b>：查表用一只复用的可变实例 {@link #LOOKUP}，只在首次遇到该变种时
 * {@link #copy()} 一份快照入表（record 不可变、每帧查表会分配，所以没做成 record）。
 * 线程：只在渲染线程（抽取期）使用。
 */
final class MobHeadKey {

	/** 查表专用的<b>复用实例</b>：{@link #of} 把结果写进它再查表；<b>绝不允许</b>放进任何集合
	 * （入表的是它的 {@link #copy()} 快照）。 */
	static final MobHeadKey LOOKUP = new MobHeadKey();

	/**
	 * 变种判别值（0 = 该类型没有变种信息）。
	 *
	 * <p><b>键里必须带 {@code EntityType}。</b>没有变种字段的类型（牛、鸡、犰狳、骆驼、驴、兔……）
	 * 全落回初始值 {@code 17}、装备为 {@code 0}，会算出<b>同一个键</b>；而离屏渲染的 {@code JOBS}
	 * 是<b>全局</b>按键存图标（不分类型），于是不同类型的图标会互相顶替。
	 */
	private int variant;

	/** 装备判别值（0 = 没有装备）。 */
	private int equipment;

	/** 实体类型的判别值；见 {@link #variant} 的说明。 */
	private int type;

	/** 是否幼体（1 = 幼体）。幼体有自己的模型与贴图，必须与成年各占一份缓存。 */
	private int baby;

	private MobHeadKey() {
	}

	/**
	 * 把这只生物的变种与装备写进 {@link #LOOKUP} 并返回它。
	 *
	 * <p>调用方拿到后<b>只允许立刻查表</b>；要留存必须 {@link #copy()}。
	 */
	static MobHeadKey of(LivingEntity entity) {
		MobHeadKey key = LOOKUP;
		// 用注册名的哈希而不是 identityHashCode：类型之间绝不碰撞，且跨会话稳定
		key.type = EntityType.getKey(entity.getType()).hashCode();
		key.baby = entity.isBaby() ? 1 : 0;
		key.variant = variantOf(entity);
		key.equipment = equipmentOf(entity);
		return key;
	}

	/** 变种判别值。命中路径上只读字段、只做 instanceof，无分配、无装箱。 */
	private static int variantOf(LivingEntity entity) {
		int hash = 17;
		if (entity instanceof Villager villager) {
			// 村民：类型（平原/沙漠…）+ 职业 + 等级（决定徽章与多数贴图差异）
			VillagerData data = villager.getVillagerData();
			hash = mix(hash, System.identityHashCode(data.type().value()));
			hash = mix(hash, System.identityHashCode(data.profession().value()));
			hash = mix(hash, data.level());
		} else if (entity instanceof Horse horse) {
			// 马：毛色 + 花纹（驴/骡/骷髅马/僵尸马没有这两个字段，类型本身就区分了）
			hash = mix(hash, System.identityHashCode(horse.getVariant()));
			hash = mix(hash, System.identityHashCode(horse.getMarkings()));
		} else if (entity instanceof Llama llama) {
			// 羊驼：毛色（地毯色由装备槽进键）
			hash = mix(hash, System.identityHashCode(llama.getVariant()));
		} else if (entity instanceof Sheep sheep) {
			hash = mix(hash, System.identityHashCode(sheep.getColor()));
		} else if (entity instanceof Cat cat) {
			hash = mix(hash, System.identityHashCode(cat.getVariant().value()));
			hash = mix(hash, cat.isTame() ? 1 : 0);
			hash = mix(hash, System.identityHashCode(cat.getCollarColor()));
		} else if (entity instanceof Wolf wolf) {
			// 狼的毛色变种没有公开访问器，只能按"是否驯服 + 项圈色"区分（见类注释的取舍）
			hash = mix(hash, wolf.isTame() ? 1 : 0);
			hash = mix(hash, System.identityHashCode(wolf.getCollarColor()));
		} else if (entity instanceof IronGolem golem) {
			hash = mix(hash, System.identityHashCode(golem.getCrackiness()));
		} else if (entity instanceof TropicalFish fish) {
			hash = mix(hash, System.identityHashCode(fish.getPattern()));
			hash = mix(hash, System.identityHashCode(fish.getBaseColor()));
			hash = mix(hash, System.identityHashCode(fish.getPatternColor()));
		} else if (entity instanceof Pig pig) {
			hash = mix(hash, System.identityHashCode(pig.getVariant().value()));
		} else if (entity instanceof Chicken chicken) {
			// 鸡：温带/暖/寒三种变种各有贴图
			hash = mix(hash, System.identityHashCode(chicken.getVariant().value()));
		} else if (entity instanceof Frog frog) {
			hash = mix(hash, System.identityHashCode(frog.getVariant().value()));
		} else if (entity instanceof Parrot parrot) {
			// 鹦鹉：红/蓝/绿/青/灰五种体色各是一张贴图
			hash = mix(hash, System.identityHashCode(parrot.getVariant()));
		} else if (entity instanceof Axolotl axolotl) {
			// 美西螈：露西/野生/金/青/蓝五种体色各是一张贴图
			hash = mix(hash, System.identityHashCode(axolotl.getVariant()));
		} else if (entity instanceof EnderMan enderman) {
			hash = mix(hash, enderman.isCreepy() ? 1 : 0);
		} else if (entity instanceof Slime slime) {
			// 史莱姆/岩浆怪：大小决定模型尺寸
			hash = mix(hash, slime.getSize());
		} else if (entity instanceof Vex vex) {
			// 恼鬼：充能时换成"持剑发红"的另一张皮，必须分开缓存，否则所有恼鬼共用第一张图
			hash = mix(hash, vex.isCharging() ? 1 : 0);
		}
		return hash;
	}

	/**
	 * 装备判别值：<b>玩家不取装备</b>；其余活体取头部槽（马/骆驼这类取身体槽与鞍槽，
	 * 猪/炽足兽取鞍槽——它们的"有没有鞍"就画在模型上）。
	 */
	private static int equipmentOf(LivingEntity entity) {
		if (entity instanceof Player) {
			return 0;
		}
		int hash = 0;
		hash = mix(hash, slotOf(entity, EquipmentSlot.HEAD));
		if (entity instanceof AbstractHorse) {
			hash = mix(hash, slotOf(entity, EquipmentSlot.BODY));
			hash = mix(hash, slotOf(entity, EquipmentSlot.SADDLE));
		} else if (entity instanceof Pig || entity instanceof Strider) {
			hash = mix(hash, slotOf(entity, EquipmentSlot.SADDLE));
		}
		return hash;
	}

	/** 一个装备槽的判别值：物品身份 + 盔甲纹饰（材质/图案）。空槽返回 0。 */
	private static int slotOf(LivingEntity entity, EquipmentSlot slot) {
		ItemStack stack = entity.getItemBySlot(slot);
		if (stack.isEmpty()) {
			return 0;
		}
		int hash = System.identityHashCode(stack.getItem());
		ArmorTrim trim = stack.get(DataComponents.TRIM);
		if (trim != null) {
			hash = mix(hash, System.identityHashCode(trim.material().value()));
			hash = mix(hash, System.identityHashCode(trim.pattern().value()));
		}
		return hash;
	}

	private static int mix(int hash, int value) {
		return hash * 31 + value;
	}

	/**
	 * 不可变快照：只有"第一次遇到这个变种、需要写入缓存"时才调用。
	 * 写进 {@code Map} 的必须是它，不能是 {@link #LOOKUP}（那只实例每帧都在变）。
	 */
	MobHeadKey copy() {
		MobHeadKey snapshot = new MobHeadKey();
		snapshot.type = this.type;
		snapshot.baby = this.baby;
		snapshot.variant = this.variant;
		snapshot.equipment = this.equipment;
		return snapshot;
	}

	@Override
	public int hashCode() {
		return ((this.type * 31 + this.baby) * 31 + this.variant) * 31 + this.equipment;
	}

	@Override
	public boolean equals(Object other) {
		if (other == this) {
			return true;
		}
		if (!(other instanceof MobHeadKey key)) {
			return false;
		}
		return key.type == this.type && key.baby == this.baby
				&& key.variant == this.variant && key.equipment == this.equipment;
	}
}
