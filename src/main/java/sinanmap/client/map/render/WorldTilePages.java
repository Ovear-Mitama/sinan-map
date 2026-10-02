package sinanmap.client.map.render;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sinanmap.common.SinanConstants;
import sinanmap.common.map.LayerSelector;
import sinanmap.common.storage.RegionStore;
import sinanmap.client.map.data.ClientChunkColorProvider;

/**
 * 世界地图的瓦片页仓库。
 *
 * <p>全屏地图以前是"每次视图变化就重画一整张屏幕贴图"（拖动顿挫、缩放发糊、已加载区域被反复重扫）。
 * 现在改成按<b>世界坐标</b>切成一页页固定大小的瓦片：
 *
 * <ul>
 *   <li>每页覆盖 {@value #PAGE_CHUNKS}×{@value #PAGE_CHUNKS} 个区块，页贴图<b>一个像素一个方块</b>，
 *       因此内容与缩放无关——缩放只是按不同倍率贴出去，不重新采样。</li>
 *   <li>页按<b>归档层</b>分开（见 {@link #keyLayer}）：地表一套，洞穴是"每 {@code CAVE_STEP} 格一档"的
 *       一套。<b>精确显示高度只当页的内容、不当页的身份</b>——洞穴里上下 1 格只是同一归档档的高度变化，
 *       页对象不换、页键不变，就地按 {@link #refreshCaveHeight} 作废"结果真的变了的区块"。</li>
 *   <li>页一旦填好就常驻，平移、缩放都不再碰它；只有新进入视野的页需要填。未填充的像素全透明，
 *       未取到数据的区域自然露出界面底色。</li>
 * </ul>
 */
public final class WorldTilePages implements Closeable {

	private static final Logger LOGGER = LoggerFactory.getLogger(SinanConstants.MOD_ID);

	/**
	 * 每页覆盖的区块边长。
	 *
	 * <p><b>刻意取 {@link RegionStore#REGION_CHUNKS}</b>：一页正好等于一个区域文件，填一页就是读一个
	 * 区域，页与落盘分区一一对应。
	 */
	public static final int PAGE_CHUNKS = RegionStore.REGION_CHUNKS;

	/** 每页覆盖的方块边长，也是页贴图的边长。 */
	public static final int PAGE_BLOCKS = PAGE_CHUNKS * SinanConstants.CHUNK_SIZE;

	private static final int CHUNK_SIZE = SinanConstants.CHUNK_SIZE;
	private static final int PAGE_CHUNK_COUNT = PAGE_CHUNKS * PAGE_CHUNKS;

	/**
	 * 常驻页数下限。
	 *
	 * <p>视野很窄时也留这么多，免得来回小幅平移就把页挤出去（每次挤出去都要重采样）。
	 */
	static final int MIN_PAGES = 32;

	/**
	 * 常驻页数上限。
	 *
	 * <p><b>容量不能是固定值。</b>视野拉宽时"一屏本身"就要几十页，固定值小于一屏需要的页数、屏幕边缘
	 * 的页根本不会被建出来；手一移就整批换人，看起来就是"离开又回来又加载一遍"。所以容量跟视野走
	 * （见 {@link #setCapacity}），再受显存与配置上限约束。
	 *
	 * <p>上限压在这里是因为每页两张贴图各 1 MB：{@value #MAX_PAGES} 页约 192 MB 显存。
	 *
	 * <p><b>从 48 提到 96：</b>缩得较小时一屏要铺的页数会超过上限，屏幕外圈那些页不会被建出来（有数据
	 * 却空白）。提高上限只把临界点往外推，<b>真正的解法是 LOD</b>：缩小时改画<b>同一批页</b>的更低像素
	 * 密度档（见 {@link #coarse}），让"一屏的页数"不再随缩放线性增长。
	 */
	public static final int MAX_PAGES = 96;

	/** 当前允许常驻的页数；由界面按"这一屏要铺多少页"设定。 */
	private int capacity = MIN_PAGES;

	/**
	 * 设定常驻页数容量。
	 *
	 * @param pages 当前视野（含预取余量）覆盖的页数
	 */
	public void setCapacity(int pages) {
		capacity = Math.clamp(pages, MIN_PAGES, MAX_PAGES);
	}

	/** 当前容量；填充顺序也按它裁剪，保证"一屏要的页"都能被建出来。 */
	public int capacity() {
		return capacity;
	}

	/**
	 * 已经被淘汰、但<b>像素还留在堆里</b>的那些页，容量跟着 {@link #capacity} 走。
	 *
	 * <p>淘汰一页只回收显存贴图、像素留在堆里（见 {@link SmoothedDynamicTexture#releaseGpu()}）；"移出去
	 * 再回来"只需把显存要回来上传一次，不必重新采样着色。按访问顺序 LRU，超容量的连像素一起释放。
	 */
	private final Map<Long, TilePage> cold = new LinkedHashMap<>(64, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, TilePage> eldest) {
			if (size() <= capacity) {
				return false;
			}
			eldest.getValue().textures().close();
			return true;
		}
	};

	/**
	 * 未探明（数据源明确说"没有这一块的数据"）的像素：<b>全透明</b>，直接透出界面底色。
	 *
	 * <p>必须是全透明，不能是半透明黑：以前用 {@code 0x80000000}，压到底色 {@code #05050A} 上会更暗，
	 * 于是"有页但没数据"的区域比"根本没有页"的区域暗一档，一平移就是一块深一块浅的补丁。
	 */
	private static final int UNEXPLORED = 0;

	private final ClientChunkColorProvider colors;

	/** 常驻页：显存里有贴图，随时可画。 */
	private final Map<Long, TilePage> resident = new LinkedHashMap<>(64, 0.75F, true);

	/**
	 * 不允许被淘汰的页范围（当前视野加预取余量，以及小地图那一屏）——淘汰任何一页前都要先问它，
	 * 见 {@link TileProtection}。
	 */
	private final TileProtection protection = new TileProtection();

	/** 一个区块 256 个像素的暂存，两张贴图各一份，免得每块都新建数组。 */
	private final int[] dayPixels = new int[CHUNK_SIZE * CHUNK_SIZE];
	private final int[] nightPixels = new int[CHUNK_SIZE * CHUNK_SIZE];

	/**
	 * 上一次见到的世界。
	 *
	 * <p>记在这里而不是界面里：瓦片跨界面复用，界面每次打开都是新实例，记在界面上就等于每次打开都判定
	 * 成换了世界、把所有页清空重填。
	 */
	private ClientLevel level;

	private static WorldTilePages shared;

	/**
	 * 粗页（LOD）仓库：怎么填、怎么淘汰、什么时候作废都归它，见 {@link CoarseTileCache}。
	 *
	 * <p>构造放在构造函数里而不是字段初始化：它要拿到 {@code this} 当宿主。
	 */
	private final CoarseTileCache coarseCache;

	/**
	 * 粗页仓库要问宿主的三件事——<b>更细那一档的页</b>（降采样来源）、<b>键怎么算</b>、<b>归档层怎么折算</b>。
	 * 除此之外它对叶页那一套一无所知。
	 */
	private final CoarseTileCache.Host coarseHost = new CoarseTileCache.Host() {
		@Override
		public TilePage finerPage(int layer, int pageX, int pageZ) {
			return finePage(layer, pageX, pageZ);
		}

		@Override
		public long coarseKey(int layer, int blocksPerPixel, int pageX, int pageZ) {
			return key(layer, gradeCode(blocksPerPixel), pageX, pageZ);
		}

		@Override
		public int keyedLayer(int layer) {
			return keyLayer(layer);
		}
	};

	WorldTilePages(ClientChunkColorProvider colors) {
		this.colors = colors;
		this.coarseCache = new CoarseTileCache(colors, texturePool, protection, coarseHost,
				dayPixels, nightPixels);
	}

	/**
	 * 取全屏地图共用的那一份瓦片页。
	 *
	 * <p>瓦片<b>不随界面开关销毁</b>：以前每次打开都新建、关掉时释放，于是每开一次都要整屏重新采样、
	 * 重新传显存。页按世界坐标摆、与界面无关，没有理由跟着界面走。
	 */
	public static WorldTilePages shared(ClientChunkColorProvider colors) {
		if (shared == null || shared.colors != colors) {
			if (shared != null) {
				shared.close();
			}
			shared = new WorldTilePages(colors);
		}
		return shared;
	}

	/** 一页的贴图从哪来、什么时候能丢，见 {@link TileTexturePool}（含"每张图四个名字"的细节）。 */
	private final TileTexturePool texturePool = new TileTexturePool();

	/**
	 * 取一页；没有就新建一张空白页。
	 *
	 * <p>新建的页只是占位，像素等 {@link #fillSome} 去填；若刚从淘汰集合回来（像素都还在），顺手把显存
	 * 要回来，它<b>立刻就是画好的</b>。拿到页后还要按<b>精确层高</b>校准一次（见 {@link #refreshCaveHeight}）：
	 * 页键是归档层，同档内上下移动时页键不变、像素却可能过时。
	 */
	public TilePage page(int layer, int pageX, int pageZ) {
		long key = key(layer, 0, pageX, pageZ);
		TilePage found = resident.get(key);
		if (found != null) {
			refreshCaveHeight(found, layer);
			return found;
		}
		TilePage recycled = cold.remove(key);
		if (recycled != null) {
			recycled.reacquire();
			refreshCaveHeight(recycled, layer);
			resident.put(key, recycled);
			evictOverflow();
			return recycled;
		}
		// 换归档档：把上一档的同一页整页拿过来继续用（连像素一起），只作废结果真的变了的区块
		TilePage adopted = adoptPreviousBandPage(layer, pageX, pageZ);
		TilePage created = adopted != null ? adopted
				: new TilePage(texturePool.obtain(PAGE_BLOCKS), keyLayer(layer), layer, pageX, pageZ);
		if (adopted != null) {
			// 改挂过来的像素还是按"上一格那个高度"画的，紧接着按当前高度校准一次（见 refreshCaveHeight）
			refreshCaveHeight(created, layer);
		}
		resident.put(key, created);
		evictOverflow();
		return created;
	}

	/**
	 * 上一次<b>显示</b>的是哪一层（<b>原始层号</b>，含地表）。换到新的归档档时从它那一档改挂像素，
	 * 见 {@link #adoptPreviousBandPage}。登记点是 {@link #setProtectedRange}。
	 */
	private int previousLayer = LayerSelector.SURFACE;

	/** 当前显示的是哪一层（原始层号，含地表）；只用来判断"换档了没有"，见 {@link #setProtectedRange}。 */
	private int shownLayer = LayerSelector.SURFACE;

	/** 当前显示的是哪一<b>档</b>（{@link #keyLayer} 的结果）；变档才更新 {@link #previousLayer}。 */
	private int shownBand = Short.MIN_VALUE;

	/**
	 * 换<b>归档档</b>时，把上一档的同一页<b>整页</b>拿过来（连像素一起），只作废结果真的变了的区块。
	 *
	 * <p>相邻两档之间绝大多数区块的切面<b>完全一样</b>（每列都记着"结果在哪些高度上仍然成立"，见
	 * {@code ClientChunkColorProvider#caveSliceCovers}），所以换档不该整套重采。做法是把上一档那一页
	 * <b>整个对象</b>拿过来、只把不成立的区块标回待填：不花拷贝贴图的钱，只花每块两次比较。
	 *
	 * <p><b>只改挂"相邻"的档。</b>换档每 {@link LayerSelector#CAVE_STEP} 格才发生一次；若两层差得远
	 * （典型是地图关着下潜了很长一段才换档），整页几乎全部失效，改挂只会把旧像素与新像素混在一页里
	 * 越抹越花（"撕裂、中间动不动黑几列"）；让新档从头铺反而干净。
	 *
	 * @return 没有可挂的上一档页时返回 null（首次进洞、从地表下来、或上一档那一页没建过）
	 */
	private TilePage adoptPreviousBandPage(int layer, int pageX, int pageZ) {
		// 只在"洞穴档 → 洞穴档"之间改挂。
		//  · 上一档是地表（或还没定过）：洞穴要从未探明（黑）开始，绝不能沿用地面那套像素
		//    ——否则下到洞里看到的还是刚才那张地表图。
		//  · 上一档就是本档：没有可挂的（本档的页已经命中，走不到这里）。
		if (!LayerSelector.isCave(layer) || !LayerSelector.isCave(previousLayer)
				|| keyLayer(previousLayer) == keyLayer(layer)) {
			return null;
		}
		// 判据用 {@link LayerSelector#CAVE_STEP}（16 格）：这就是档长，即"离得够近才改挂"的范围上限
		if (Math.abs(previousLayer - layer) > LayerSelector.CAVE_STEP) {
			return null;
		}
		long previousKey = key(previousLayer, 0, pageX, pageZ);
		TilePage page = resident.remove(previousKey);
		boolean fromCold = false;
		if (page == null) {
			page = cold.remove(previousKey);
			if (page == null) {
				return null;
			}
			fromCold = true;
		}
		// 只换键层；pixelHeight 留着当"旧高度"，给紧接着的 refreshCaveHeight 用
		page.retarget(keyLayer(layer));
		if (fromCold) {
			// 从淘汰集合来的：显存已经还回去了，重新申请时会把现有像素一起传上去
			page.reacquire();
		}
		// 常驻的那一份不必标脏：改挂只换了层键，没碰任何像素（失效块只是记进 pending，
		// 等补画段真的写像素时 writeChunk 自己会标脏）。标脏的话，换一档就要把视野内
		// 每一页的两张 1MB 原样重传一遍——纯浪费的显存带宽。
		return page;
	}

	/** 档内换高度的沿用情况上次记日志的时刻，用来限制日志频率。 */
	private long lastRefreshLogNanos;

	/**
	 * 页的键是<b>归档层</b>，像素却是按<b>精确层高</b>画的；两者对不上时就在原页里校准一次。
	 *
	 * <p>同一归档档内相邻两格之间绝大多数区块的切面<b>完全一样</b>（见
	 * {@code ClientChunkColorProvider#caveSliceCovers}），所以高度变化不该整套重采，只把不成立的区块
	 * 标回待填即可。
	 *
	 * <p><b>就地做：不换页键、不挪页对象、不动游标。</b>页键一变就要走一遍建页 / 改挂 / 旧页被搬走，
	 * 看起来就是"下潜一格整屏重新加载一遍"。现在页还在原处，只改一个记号、作废少数几块像素。
	 *
	 * <p><b>不在这里涂未探明、也不把 {@code fillCursor} 拨回 0</b>，改成与 {@code markStale#requeue} 同一条
	 * 路：把块记进待补，由 {@code fillSome} 的补画段<b>在原位</b>重画，旧像素先留着。当场涂未探明会让一页
	 * 里几十上百块同时闪黑；拨回游标更糟——游标从 0 重走会一路跳过已填块、每帧推进 {@code ASK_LIMIT} 步，
	 * 只有散落的失效块在"乱点"，就是撕裂。
	 */
	private void refreshCaveHeight(TilePage page, int layer) {
		// 只有洞穴层有"档内高度变化"这件事；地表页的 pixelHeight 恒等于它自己的键层
		if (!LayerSelector.isCave(layer) || page.pixelHeight() == layer) {
			return;
		}
		int pixelHeight = page.pixelHeight();
		page.setPixelHeight(layer);
		int stale = 0;
		for (int index = 0; index < PAGE_CHUNK_COUNT; index++) {
			if (!page.filled[index]) {
				continue;
			}
			int chunkX = page.pageX * PAGE_CHUNKS + index % PAGE_CHUNKS;
			int chunkZ = page.pageZ * PAGE_CHUNKS + index / PAGE_CHUNKS;
			if (colors.caveSliceCovers(chunkX, chunkZ, pixelHeight, layer)) {
				continue;
			}
			page.filled[index] = false;
			// 像素<b>先留着</b>（不涂黑），只把"这一格的数据还算不算数"归零：重试轮转与补画都用
			// {@code hasTerrain} 判断"要不要重问"。{@code painted} 仍是 true，于是重画时若新高度那边
			// 没洞可画，就继续留着旧像素、不会闪黑（见 fillOne）。
			page.hasTerrain[index] = false;
			page.markPending(index);
			stale++;
		}
		// 洞穴里每上下 1 格就会走到这里一次，日志按秒限流，免得一走动就刷屏
		long now = System.nanoTime();
		if (now - lastRefreshLogNanos >= 1_000_000_000L) {
			lastRefreshLogNanos = now;
			LOGGER.info("洞穴档内换高度 | {} → {} | 本页 {} 块中 {} 块需要重填（其余像素逐点相同）",
					pixelHeight, layer, PAGE_CHUNK_COUNT, stale);
		}
	}

	/** 取一页，没有就返回 null（不新建）。绘制时用，免得为了画一张空页建出一堆贴图。 */
	public TilePage find(int layer, int pageX, int pageZ) {
		TilePage page = resident.get(key(layer, 0, pageX, pageZ));
		if (page != null) {
			// 绘制走这条：视野里若有一页没被 fillView 的 page() 校准过（不在填充顺序里），
			// 也要在这里按当前精确高度校准一次，否则会照上一格的高度画（见 refreshCaveHeight）。
			refreshCaveHeight(page, layer);
		}
		return page;
	}

	/**
	 * 页键用的层号：洞穴按<b>归档层</b>量化（{@link LayerSelector#CAVE_STEP} 格一档），
	 * 地表用 {@link Short#MIN_VALUE} 这个不会与真实层高相撞的哨兵。
	 *
	 * <p><b>精确显示高度刻意不进位置。</b>它只当页的<b>内容</b>（见 {@link TilePage#pixelHeight}）：洞里上下
	 * 1 格都会变高度，拿它当页身份就会每格换一套页键（建页、改挂、搬走旧页），看起来就是"下潜一格整屏
	 * 重新加载一遍"；量化到归档层后，档内高度变化只是同一页就地校准（见 {@link #refreshCaveHeight}）。
	 *
	 * <p>量化档取 {@link LayerSelector#CAVE_STEP}，<b>不是</b>落盘档 {@link LayerSelector#STORAGE_STEP}
	 * （落盘、预读与颜色缓存用更细的那个），页的身份不必跟它一致。
	 */
	private static int keyLayer(int layer) {
		// 地表是哨兵，不参与量化。两种写法都要认：{@link LayerSelector#SURFACE} 是调用方传进来的，
		// {@link Short#MIN_VALUE} 是页上存的那个（两者掩码后同值，见 {@link #key}）。
		// 必须先判掉：{@code LayerSelector.isCave(Short.MIN_VALUE)} 为真（它只和 SURFACE 比），
		// 直接丢给 cacheLayer 会绕一圈——虽然结果一样，但那是碰巧。
		if (layer == LayerSelector.SURFACE || layer == Short.MIN_VALUE) {
			return Short.MIN_VALUE;
		}
		return LayerSelector.cacheLayer(layer);
	}

	/**
	 * 页键：<b>归档层 + 档位 + 页坐标</b>。
	 *
	 * <p>层必须进键（见 {@link #keyLayer}）：地表与洞穴因此天然分成两套页，从地表下到洞穴时页键不同、
	 * 不会沿用地面那套像素。
	 *
	 * <p>位分配：层号 16 位、档位 2 位、页 X 23 位、页 Z 23 位。<b>档位</b>（见 {@link #gradeCode}）必须
	 * 进键：同一页的 1/2/4 方块每像素三张贴图互不相同，不带档就会互相覆盖。
	 */
	private static long key(int layer, int gradeCode, int pageX, int pageZ) {
		int layerNumber = keyLayer(layer);
		return (long) (layerNumber & 0xFFFF) << 48
				| (long) (gradeCode & 0x3) << 46
				| (long) (pageX & 0x7FFFFF) << 23
				| pageZ & 0x7FFFFFL;
	}

	/**
	 * 档位编码：叶页 = 0（1 方块/像素）、2 方块/像素 = 1、4 方块/像素 = 2。
	 *
	 * <p>进 {@link #key} 的只有这 2 位编码，真正的"方块/像素"数值另行传递，这样键才塞得进 64 位。
	 */
	private static int gradeCode(int blocksPerPixel) {
		return switch (blocksPerPixel) {
			case 2 -> 1;
			case 4 -> 2;
			default -> 0;
		};
	}

	/**
	 * 把这一页里还没填的区块填上，最多处理 {@code limit} 块。
	 *
	 * <p>页内<b>按固定顺序（列优先）推进</b>（见 {@link #SWEEP_ORDER}），不看朝向；"离玩家由近到远"是
	 * <b>页与页之间</b>的事。
	 *
	 * <p>两段：先<b>补画</b>上次问过还没画上的（{@link TilePage#pending}），轮转着找，队首没到也不挡后面；
	 * 再<b>往下问新的</b>，遇到"还没定论"的记进 {@link TilePage#pending} 继续走——<b>不能停在这一块上</b>，
	 * 一停整页就变成"一块块串行等磁盘"，一页要几十秒。
	 *
	 * <p>本页预算（{@code limit}）、全局预算（{@link ClientChunkColorProvider#isWarmSpent()}）与
	 * "一次最多问几块"（{@value #ASK_LIMIT}）都要守：<b>全局预算一用完就停在游标上</b>，剩下的下一帧接着填。
	 *
	 * @return 这一轮有没有<b>进展</b>（画上了几块、或游标前进了）；调用方据此决定"还要不要喂下一页"。
	 */
	public boolean fillSome(TilePage page, int limit) {
		// 【诊断计量】只在本方法这一层取一次时间；写入块数用 framePaints 的增量算（只有本方法里的
		// fillOne 会动它，所以这一段的增量就是本方法写下的块数）。
		long statT0 = ClientChunkColorProvider.STATS_ENABLED ? System.nanoTime() : 0L;
		int statPaintsBefore = framePaints;
		int startCursor = page.fillCursor;
		int processed = 0;
		// 一段：把"数据已经到的"画上
		for (int probe = 0; probe < PAINT_PROBES && processed < limit && page.pendingCount > 0; probe++) {
			frameProbes++;
			// 补画也走同一张固定表（见 SWEEP_ORDER）：以前这里直接在"块索引"上转圈，而索引是
			// z * 32 + x，所以补上的那几块是从上到下一行行长出来的——与问新块的方向不同，
			// 一页里就同时看得到"由内向外"和"一行行"两种走向（用户反馈）。统一到一张表之后，
			// 页内无论问新块、补画、还是重试轮转，都是同一个方向。
			int index = SWEEP_ORDER[page.pendingCursor];
			page.pendingCursor = (page.pendingCursor + 1) % PAGE_CHUNK_COUNT;
			if (!page.pending[index]) {
				continue;
			}
			frameMarked++;
			if (colors.isWarmSpent()) {
				frameBudgetOut++;
				if (ClientChunkColorProvider.STATS_ENABLED) {
					colors.stats().recordFillSome(System.nanoTime() - statT0, framePaints - statPaintsBefore);
				}
				return false;
			}
			if (!fillOne(page, index)) {
				continue;
			}
			page.clearPending(index);
			processed++;
		}
		// 二段：往下问新的
		int asked = 0;
		while (page.fillCursor < PAGE_CHUNK_COUNT && processed < limit && asked < ASK_LIMIT) {
			if (colors.isWarmSpent()) {
				frameBudgetOut++;
				break;
			}
			asked++;
			// 页内固定列优先：一列一列地走，先从上到下走完一列再走右边那一列
			// （见 SWEEP_ORDER）。不看玩家朝向——那样斜着走会扫出斜条带、转头还会跳。
			int index = SWEEP_ORDER[page.fillCursor++];
			if (page.filled[index]) {
				// 被 markStale 挑出来重填的区块，重新走一遍时会遇到已填过的
				continue;
			}
			if (fillOne(page, index)) {
				processed++;
			} else {
				page.markPending(index);
			}
		}
		// 【诊断计量】本次叶页填充的耗时与写下的块数
		if (ClientChunkColorProvider.STATS_ENABLED) {
			colors.stats().recordFillSome(System.nanoTime() - statT0, framePaints - statPaintsBefore);
		}
		return processed > 0 || page.fillCursor != startCursor;
	}

	/**
	 * 一次访问里，最多去找几块"已经问过"的来补画。
	 *
	 * <p>布尔表要扫一遍才知道哪几块在等，所以一次扫一小段、多扫几次转完一圈。取 {@value #PAINT_PROBES}
	 * 是"一页约四次访问转一圈"；扫本身只是读数组，不贵。
	 */
	private static final int PAINT_PROBES = 256;

	/**
	 * 页内推进顺序表（问新块、补画、未探明重试共用同一张）。
	 *
	 * <p>走向与"为什么不跟着朝向走"都写在 {@link TileSweepOrder#pageCenterOut} 那里——
	 * 全工程的页内推进顺序只此一处，别再各写一套。
	 */
	private static final int[] SWEEP_ORDER = TileSweepOrder.pageCenterOut(PAGE_CHUNKS);

	/**
	 * 一次访问里，最多往下问几块新的。
	 *
	 * <p>"数据在路上"的那些问起来很便宜，真正的花费由全局时间预算兜住（见
	 * {@link ClientChunkColorProvider#isWarmSpent()}）。
	 *
	 * <p><b>不能取"整页"</b>：一次问 1024 块等于把这一页全押在后台预读队列上（队列只有
	 * {@code MAX_PREFETCH_QUEUE} 那么深，多出的请求会被拒），画面上就是"先什么都不出来、然后一块块断续
	 * 冒出来"。取 {@value #ASK_LIMIT} 让块分批在路上，数据一到就画，看起来是连续地在长。
	 */
	private static final int ASK_LIMIT = 128;

	/**
	 * 这一页是不是<b>正在加载</b>：页内游标还没走完。
	 *
	 * <p>加载指示由页自己说了算。判据用"游标还没走完"而不是"还有在等数据的块"：后者在数据到货时会反复
	 * 归零又出现、圈一闪一闪；游标一路向前，圈便<b>稳定地转到这一页问完</b>再跳下一页。
	 *
	 * <p><b>这只是"进度"那一半</b>；另一半是"这一片到底有没有数据"（见 {@link #hasRealTerrain} 与
	 * {@code WorldMapScreen#couldStillHaveData}）。只看进度会让外周<b>根本没有存档数据</b>的页也一直转圈。
	 */
	public boolean awaitingData(TilePage page) {
		// "还没轮到的页"不算。只有"已经开过工、这一遍还没走完"的页才转圈——
		// 排队等开工的页（同时填的页数有上限）游标还是 0，把它们也算进来圈就永远转个不停。
		return started(page) && page.fillCursor < PAGE_CHUNK_COUNT;
	}

	/**
	 * 这一页有没有<b>真的画出过一块地形</b>（见 {@link TilePage#everTerrain}）。
	 *
	 * <p>给加载指示用：它和 {@link #awaitingData} 是同一判据的两半。一页只要出过地形，就说明"这一片
	 * 确实有数据"，之后即使还有块在等也值得继续转圈。
	 */
	public boolean hasRealTerrain(TilePage page) {
		return page.everTerrain;
	}

	/**
	 * 问一块并画上。
	 *
	 * <p><b>只有"确定没有数据"才画空洞</b>（见 {@link ClientChunkColorProvider#isKnownEmpty}）。预算用尽、
	 * 请求在路上、队列满了没排上都只是"还没定论"；当成"没有数据"画成空洞会<b>同时标成"问过了"</b>
	 * （{@code filled}），那一页看上去就填完了、再也不会来填（"加载了一点就不动了"）。
	 *
	 * @return false = 这一轮还问不出结果（预算用尽、或数据正在来的路上），调用方应留着下次再问
	 */
	private boolean fillOne(TilePage page, int index) {
		if (colors.isWarmSpent()) {
			return false;
		}
		frameAsks++;
		int chunkX = page.pageX * PAGE_CHUNKS + index % PAGE_CHUNKS;
		int chunkZ = page.pageZ * PAGE_CHUNKS + index / PAGE_CHUNKS;
		boolean hasData = colors.shadeChunkPair(chunkX, chunkZ, dayPixels, nightPixels);
		if (!hasData) {
			if (!colors.isKnownEmpty(chunkX, chunkZ)) {
				// 还没定论：数据在路上 / 请求没排上 / 区块还没生成完。留着下次再问
				frameNotDecided++;
				return false;
			}
			if (page.painted[index]) {
				// 这一格像素里现在还有地形（典型是洞穴换高度时留下的上一格那层）：新高度这边没有洞
				// 可画，也<b>不能</b>当场涂成未探明——那会在一页里同时闪出一片黑。
				// 留着旧像素只当"这一轮处理完了"：数据真到了，重试轮转下次就会把它重画成新的。
				page.filled[index] = true;
				return true;
			}
			// 确定没有数据、这里本来也没有像素：这一格就画成未探明
			frameHoles++;
		}
		writeChunk(page, index, hasData);
		page.filled[index] = true;
		framePaints++;
		return true;
	}

	/** 诊断：本帧问了几块（含补画段、页内扫掠、重试轮转、小地图点名）。 */
	private int frameAsks;

	/** 诊断：本帧画上了几块（问出结果的那些）。 */
	private int framePaints;

	/** 诊断：本帧补画段扫了几格（扫的本身也要花时间，是"卡"的一个来源）。 */
	private int frameProbes;

	/** 诊断：本帧补画段扫到的"待补"格子数。 */
	private int frameMarked;

	/** 诊断：本帧因为预算用尽而停下填充的次数。 */
	private int frameBudgetOut;

	/** 诊断："问到了确定没有数据"的块数（会画成未探明）。 */
	private int frameHoles;

	/** 诊断：本帧"还没有定论"的块数（数据在路上 / 请求没排上 / 还没生成完）。 */
	private int frameNotDecided;

	/** 开始新的一帧，由界面调用（见 {@code WorldMapScreen#recordFrameCost}）。 */
	public void beginFrame() {
		texturePool.flushPendingClose();
		frameAsks = 0;
		framePaints = 0;
		frameProbes = 0;
		frameMarked = 0;
		frameBudgetOut = 0;
		frameHoles = 0;
		frameNotDecided = 0;
	}

	public int frameAsks() {
		return frameAsks;
	}

	public int framePaints() {
		return framePaints;
	}

	/**
	 * 当前还占着贴图的页数（叶页 + 粗页，冷热两档都算）。
	 *
	 * <p><b>只给性能面板用</b>：它不开面板就没人调，也就不产生任何开销。
	 */
	public int residentPageCount() {
		return resident.size() + cold.size() + coarseCache.residentCount() + coarseCache.coldCount();
	}

	/**
	 * 这些页的贴图大致占多少字节：叶页是"1 方块 1 像素"、粗页按 {@link #COARSE_PACK} 再缩一次。
	 *
	 * <p>是估算——真正的显存占用只有驱动知道；这里给的是"这些贴图至少这么大"，够用来判断
	 * "是不是页堆太多把显存吃掉了"。
	 */
	public long estimatedTextureBytes() {
		long leafBytes = (long) PAGE_BLOCKS * PAGE_BLOCKS * 4;
		long coarseSide = PAGE_BLOCKS / COARSE_PACK;
		long coarseBytes = coarseSide * coarseSide * 4;
		return (long) (resident.size() + cold.size()) * leafBytes
				+ (long) (coarseCache.residentCount() + coarseCache.coldCount()) * coarseBytes;
	}

	/** 本帧补画段扫了几格。 */
	public int frameProbes() {
		return frameProbes;
	}

	/** 本帧补画段扫到几个"待补"格子。 */
	public int frameMarked() {
		return frameMarked;
	}

	/** 本帧因为预算用尽停下填充的次数。 */
	public int frameBudgetOut() {
		return frameBudgetOut;
	}

	/** 本帧问到"确定没有数据"的块数。 */
	public int frameHoles() {
		return frameHoles;
	}

	/** 本帧"还没有定论"的块数（问而不画的那些）。 */
	public int frameNotDecided() {
		return frameNotDecided;
	}

	/** 每秒记完诊断行后清零，见 {@code WorldMapScreen#logComposition}。 */
	public void resetFrameCounters() {
		frameAsks = 0;
		framePaints = 0;
		frameProbes = 0;
		frameMarked = 0;
		frameBudgetOut = 0;
		frameHoles = 0;
		frameNotDecided = 0;
	}

	/** 这一页是不是已经开过工（问过至少一块）。见 {@code WorldMapScreen} 里"同时在填的页数"封顶。 */
	public boolean started(TilePage page) {
		return page.fillCursor > 0 || page.pendingCount > 0;
	}

	/**
	 * <b>当前这一层</b>里还有"活要干"的页数（正在填的页数）。
	 *
	 * <p>屏幕同时摊开太多半成品页就是"到处都在长、又都长不完"，界面据此给"同时在填的页数"封顶。
	 *
	 * <p><b>必须只数当前层。</b>换层时上一层的页原地留着，但它们里面"问过、数据还没到"的块
	 * （{@link TilePage#pendingCount}）要等切回去才会被消化；填这一层的顺序又用当前层，上一层根本不会被再喂，
	 * 于是它们的"还有活"状态<b>永久</b>占着名额——攒够上限后新一层就再也开不了工（"换层后一直加载上一层
	 * 的瓦片"）。按层过滤后名额只算当前层，换层立刻从当前层重新铺。
	 */
	public int fillingCount(int layer) {
		int keyed = keyLayer(layer);
		int count = 0;
		for (TilePage page : resident.values()) {
			// 只算当前层：上一层的页留在常驻集合里，但不该占这一层的开工名额（见上面的说明）。
			// 比的是归档层——页上存的就是它（见 TilePage#layer）
			if (page.layer != keyed) {
				continue;
			}
			// 只数"已经开过工"的页。把没开工的页也算进来会死锁：名额被一批"等着开工"的页占满，
			// 而它们又因为名额满了开不了工——一页都不动（实测：视野内页=16、还没问=12288、问=0）。
			if (started(page) && hasWork(page)) {
				count++;
			}
		}
		return count;
	}

	/**
	 * 这一页还有没有活要干（整屏填充顺序由调用方按"离玩家由近到远"重排，见 {@link #fillSome}）。
	 *
	 * <p><b>两个条件缺一不可</b>：游标没走完，<b>或者</b>还有在等数据的块（{@link TilePage#pendingCount}）。
	 * 只看游标会<b>死住</b>：游标把剩下的块全标成"待问"后走到底，那几百块没画上，而调用方见
	 * {@code hasWork} 为假就再也不来访问。
	 */
	public boolean hasWork(TilePage page) {
		return page.fillCursor < PAGE_CHUNK_COUNT || page.pendingCount > 0;
	}

	/**
	 * 只把<b>这一个区块</b>按当前数据画进它所在的那一页，<b>不推进</b>页内游标。
	 *
	 * <p>小地图用这条：一页 512 方块、小地图一屏只看得到几十块，按游标推进要填掉半页才轮到玩家脚下。
	 * 与 {@link #fillSome} 共用同一批像素与同一张 {@code filled} 表，世界地图之后补整页时会跳过这些块。
	 *
	 * @return 这一块所在的页；这一轮没画上（预算用尽 / 数据在路上）也返回它，但那一格<b>什么都不写</b>
	 *         （保持透明），并标进 {@link TilePage#pending} 等下次再补
	 */
	public TilePage fillChunkNow(int layer, int chunkX, int chunkZ) {
		int pageX = Math.floorDiv(chunkX, PAGE_CHUNKS);
		int pageZ = Math.floorDiv(chunkZ, PAGE_CHUNKS);
		TilePage page = page(layer, pageX, pageZ);
		int index = Math.floorMod(chunkZ, PAGE_CHUNKS) * PAGE_CHUNKS + Math.floorMod(chunkX, PAGE_CHUNKS);
		if (page.filled[index]) {
			return page;
		}
		if (!fillOne(page, index)) {
			// 还没问出结果（预算用尽 / 数据在路上）：标上，下次访问这一页时补它。
			// 返回页本身（而不是 null）是为了不打断扫掠——剩下的块还能继续问
			page.markPending(index);
		}
		return page;
	}

	/**
	 * 把一页里的某个区块整块涂成"未探明"色。
	 *
	 * <p>夜晚那张涂<b>全透明</b>而不是同一个半透明黑：未探明色本身就带一半透明要把底色透出来，两张各叠
	 * 一次的话天越黑它越暗（午夜只剩四分之一）。涂全透明后那一块由白天那张单独负责，天色不影响它。
	 */
	private static void paintChunkSolid(TilePage page, int index) {
		int baseX = index % PAGE_CHUNKS * CHUNK_SIZE;
		int baseY = index / PAGE_CHUNKS * CHUNK_SIZE;
		page.textures().day().getPixels().fillRect(baseX, baseY, CHUNK_SIZE, CHUNK_SIZE, UNEXPLORED);
		page.textures().night().getPixels().fillRect(baseX, baseY, CHUNK_SIZE, CHUNK_SIZE, 0);
	}

	/**
	 * 把某个区块标成"待重填"：<b>地形被改过</b>（实时采样发现内容变了）。
	 *
	 * <p>区块与页一对一（一页正好 16×16 个区块），因此这里是一次常数级查找。淘汰集合里的页也要一起改：
	 * 它们虽然不在屏幕上，但像素还留着、回来时会直接画出来。
	 *
	 * @param layer 这一块改动是在哪一层看到的；只有那一层的页需要重画
	 * @return 有没有找到这一块所在的页
	 */
	public boolean markStale(int layer, int chunkX, int chunkZ) {
		return requeue(layer, chunkX, chunkZ, false);
	}

	/**
	 * 把某个区块标成"待补画"：<b>后台刚给这一块读到一份数据</b>（见
	 * {@code ClientChunkColorProvider#drainArrivedChunks}）。
	 *
	 * <p>与 {@link #markStale} 的区别：<b>已经画着地形的格子直接跳过</b>——这一份只是"数据到了"，那张
	 * 像素本来就是对的，重画一遍只会白白让整页重传一次显存。
	 *
	 * @return 有没有找到这一块所在的页（已经画着地形的格子直接算找到，不做任何改动）
	 */
	public boolean markArrived(int layer, int chunkX, int chunkZ) {
		return requeue(layer, chunkX, chunkZ, true);
	}

	/**
	 * @param skipIfPainted 已经画着地形的格子要不要跳过，见 {@link #markArrived}
	 */
	private boolean requeue(int layer, int chunkX, int chunkZ, boolean skipIfPainted) {
		int pageX = Math.floorDiv(chunkX, PAGE_CHUNKS);
		int pageZ = Math.floorDiv(chunkZ, PAGE_CHUNKS);
		long key = key(layer, 0, pageX, pageZ);
		TilePage page = resident.get(key);
		if (page == null) {
			page = cold.get(key);
		}
		if (page == null) {
			return false;
		}
		int index = Math.floorMod(chunkZ, PAGE_CHUNKS) * PAGE_CHUNKS + Math.floorMod(chunkX, PAGE_CHUNKS);
		if (skipIfPainted && page.hasTerrain[index]) {
			return true;
		}
		// 不把那一格先涂成未探明：未探明像素全透明，涂一下那一格就闪一下黑，一屏几十处一起发生就是
		// "已经加载好的地方时不时闪黑块"。补画按块轮转、数据一到就画（见 fillSome），旧地形多留一两帧更好。
		//
		// 不要动 fillCursor：归零等于把游标后面那些块整页重走一遍；这一块由 pending 单独记着。
		page.filled[index] = false;
		page.markPending(index);
		return true;
	}

	/**
	 * 把这一页需要的那几张贴图传到显存。
	 *
	 * <p><b>只传要用的那一张。</b>一页是"白天 + 夜晚"两张各 1 MB，任何时刻只画其中一张。以前无条件
	 * 都传，铺图时每秒要往显存灌两百多 MB——纯浪费显存带宽，也是"地图一加载就卡"的一大来源。
	 *
	 * <p><b>页还在"陆续来数据"时先不传。</b>一页贴图是 512×512 = 1 MB，而数据是一块一块到的：
	 * 每来一块就传一次的话，屏幕上一页会以 16×16 方块为单位一格一格显影——"撕裂感"主要就来自这里，
	 * 而且填一页要传几十次 1 MB。改成"这一页静了 {@value #UPLOAD_SETTLE_NANOS} 纳秒、或已经没有
	 * 待办的块"才传：一页以较大的步子出现，传的次数降一个量级，屏幕上的格子感也随之消失。
	 *
	 * @return 有没有真的传过东西
	 */
	public boolean upload(TilePage page, boolean wantDay, boolean wantNight) {
		if (!settled(page)) {
			return false;
		}
		boolean uploaded = false;
		if (wantDay && page.dirtyDay) {
			page.textures().day().upload();
			page.dirtyDay = false;
			uploaded = true;
		}
		if (wantNight && page.dirtyNight) {
			page.textures().night().upload();
			page.dirtyNight = false;
			uploaded = true;
		}
		return uploaded;
	}

	/**
	 * 这一页"静下来了没有"：没有待办的块（该问的都问完了），或者已经安静了
	 * {@value #UPLOAD_SETTLE_NANOS} 纳秒（数据暂时不会再来）。
	 *
	 * <p>后半条是兜底：万一某一块的请求卡住很久，也不能让已经画好的那部分一直不上屏。
	 */
	private static boolean settled(TilePage page) {
		return page.pendingCount == 0
				|| System.nanoTime() - page.paintedAtNanos >= UPLOAD_SETTLE_NANOS;
	}

	/** 一页在"还在变化"时最多憋多久不上传，见 {@link #settled}。 */
	private static final long UPLOAD_SETTLE_NANOS = 250_000_000L;

	/**
	 * 画一页贴图。位置与大小交给外面那层变换矩阵，两个界面共用这一条。
	 *
	 * <p>传进来的名字决定采样器：小地图传 {@code dayId}（线性）、全屏地图传 {@code daySharpId}
	 * （最近邻，方块边缘要锐），见 {@link ResampledTexture}。走<b>带颜色</b>的那条 {@code blit}——只有它
	 * 能把 alpha 乘上去，也是唯一能用动态贴图带颜色的办法（见 {@link #register}）。
	 *
	 * <p><b>取样范围往内缩半个像素。</b>贴图是 {@code CLAMP_TO_EDGE} 的，直接取满 0..512 时第一/最后
	 * 一个像素外侧那半格会被"夹"成边界像素，与邻页拼接的缝上取到的就是老像素而不是邻页内容。改成从 0.5
	 * 取到 511.5 后，每个贴图像素的中心正好落在对方块中心，拼接处严丝合缝。
	 */
	public static void drawPage(GuiGraphicsExtractor graphics, Identifier texture, int x, int y, int size, int color) {
		drawPage(graphics, texture, x, y, size, size, color);
	}

	/**
	 * 画一页贴图，贴图边长与落点边长可以不同。
	 *
	 * <p>粗页把 {@link #COARSE_PACK}² 张叶页格位合成一张贴图：它要铺满的方块范围是叶页格位的
	 * {@link #COARSE_PACK} 倍，所以落点边长（{@code size}，矩阵里 1 单位 = 1 方块）比贴图边长
	 * （{@code textureSize}，像素）大——{@code blit} 的取样与归一化都用贴图边长、落点用方块数，才是
	 * "整张拉满、不裁不重复"。
	 *
	 * @param size        画到屏幕上的边长（矩阵单位 = 方块）
	 * @param textureSize 贴图本身的边长（像素）
	 */
	public static void drawPage(GuiGraphicsExtractor graphics, Identifier texture, int x, int y,
			int size, int textureSize, int color) {
		graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0.5F, 0.5F,
				size, size, textureSize - 1, textureSize - 1, textureSize, textureSize, color);
	}

	/** 换了世界就清空；同一个世界则什么也不做（界面反复打开也不会重填）。 */
	public void ensureLevel(ClientLevel current) {
		if (current == level) {
			return;
		}
		level = current;
		reset();
	}

	/** 换世界就把瓦片全部丢掉（连堆里留的像素一起），免得画出一堆别的存档的地形。 */
	public void reset() {
		for (TilePage page : resident.values()) {
			page.textures().close();
		}
		resident.clear();
		for (TilePage page : cold.values()) {
			page.textures().close();
		}
		cold.clear();
		// 粗页也要丢：它们按世界坐标缓存，不丢的话切存档会画出上一个世界的地形（连"冷"表里
		// 留着的像素与填充进度一起）
		coarseCache.releaseAll();
		// 层的记录一起作废：换了世界之后没有"上一档"可挂（地表也不挂）
		previousLayer = LayerSelector.SURFACE;
		shownLayer = LayerSelector.SURFACE;
		shownBand = Short.MIN_VALUE;
	}

	@Override
	public void close() {
		reset();
		// 推迟关闭的那几张已经不在 resident/cold 里了，得单独收掉
		texturePool.close();
	}

	/**
	 * 把一个区块的像素写进页里。
	 *
	 * <p><b>全屏地图不做逐块淡入。</b>未探明像素现在是全透明的，整页压 alpha 就等于让地形一起变淡、压在
	 * 深色底色上（加载区域四周会出现一圈发暗的块）。只有小地图仍有淡入：它每帧重新合成，顺手加不透明度即可。
	 */
	private void writeChunk(TilePage page, int index, boolean hasData) {
		int baseX = index % PAGE_CHUNKS * CHUNK_SIZE;
		int baseY = index / PAGE_CHUNKS * CHUNK_SIZE;
		page.loaded = true;
		page.dirtyDay = true;
		page.dirtyNight = true;
		page.paintedAtNanos = System.nanoTime();
		// 这一叶变了：覆盖它的粗页要重建（缩小时画的就是粗页）
		coarseCache.markDirty(page.layer(), page.pageX, page.pageZ, index);
		// 记下"这块画的到底是地形还是空洞"，重试轮转与重画都靠它判断
		page.hasTerrain[index] = hasData;
		page.painted[index] = hasData;
		if (!hasData) {
			// 已经确认没有数据（区块没加载、磁盘上也没写过）
			paintChunkSolid(page, index);
			return;
		}
		// 真的画出地形了：记下"这一片确实有数据"，加载指示据此才可能继续转圈
		page.everTerrain = true;
		paint(page.textures().day().getPixels(), baseX, baseY, dayPixels);
		paint(page.textures().night().getPixels(), baseX, baseY, nightPixels);
	}

	/** 把一个区块的 16×16 个像素写进页贴图。 */
	private static void paint(NativeImage image, int baseX, int baseY, int[] chunkColors) {
		for (int z = 0; z < CHUNK_SIZE; z++) {
			int rowStart = z * CHUNK_SIZE;
			for (int x = 0; x < CHUNK_SIZE; x++) {
				image.setPixel(baseX + x, baseY + z, chunkColors[rowStart + x]);
			}
		}
	}

	/**
	 * 淘汰超出上限的页：从最久没用过的开始，但<b>跳过当前视野范围内的页</b>。
	 *
	 * <p>不跳过的后果：视野很宽时页数超过上限，正在显示的那页被自己挤掉、贴图清空再重填，就是"撕裂"。
	 * 宁可暂时超上限（多占几 MB 显存），也不能把正在看的页淘汰掉。淘汰只是<b>回收显存</b>：像素留进
	 * {@link #cold}，回来时只重传一次。
	 */
	private void evictOverflow() {
		Iterator<Map.Entry<Long, TilePage>> iterator = resident.entrySet().iterator();
		while (resident.size() > capacity && iterator.hasNext()) {
			Map.Entry<Long, TilePage> entry = iterator.next();
			TilePage page = entry.getValue();
			if (isProtected(page)) {
				continue;
			}
			iterator.remove();
			if (page.loaded()) {
				page.releaseGpu();
				cold.put(entry.getKey(), page);
			} else {
				// 还没写过像素，留着也没意义
				page.textures().close();
			}
		}
	}

	/**
	 * 设置需要保住、不允许被淘汰的页（当前视野加预取余量）。
	 *
	 * <p>保护范围<b>只限当前显示的那一层</b>：层不进保护判定的话，刚走过的几个归档档各有一整屏页被保住，
	 * 页数会随走过的档数一直涨，而它们正是最该被淘汰的那批。层按<b>归档层</b>记（见 {@link #keyLayer}）。
	 */
	public void setProtectedRange(int layer, int minX, int minZ, int maxX, int maxZ) {
		int keyed = keyLayer(layer);
		if (keyed != shownBand) {
			// 换档了：新档的页可以从上一档那一套页"改挂"过来，见 adoptPreviousBandPage。
			// 档内的高度变化不算换档（页键没变，见 refreshCaveHeight），所以这里刻意不更新 previousLayer。
			previousLayer = shownLayer;
			shownBand = keyed;
		}
		shownLayer = layer;
		protection.set(keyed, minX, minZ, maxX, maxZ);
	}

	/** 当前页仓库属于哪个世界的数据目录；见 {@link #ensureWorld(Path)}。 */
	private Path worldRoot;

	/**
	 * 世界换了就把所有页丢掉。
	 *
	 * <p>页按<b>世界坐标</b>缓存、不带"这是哪个存档"的信息：不丢的话切到另一个存档画出来的还是上一个
	 * 存档的地形（落盘本来就分存档，串的只是内存里这些烘焙好的贴图）。
	 *
	 * <p>判定用<b>数据目录</b>而不是世界名：同名世界会复用同一个存档目录名，光看名字分不出是不是同一个。
	 */
	public void ensureWorld(Path root) {
		if (root == null || root.equals(worldRoot)) {
			return;
		}
		worldRoot = root;
		reset();
	}

	/** 这一页在不在"不许被淘汰"的范围内（叶页判据；粗页那一边由 {@link CoarseTileCache} 自己问）。 */
	private boolean isProtected(TilePage page) {
		return protection.containsLeaf(page.layer(), page.pageX, page.pageZ);
	}

	/**
	 * 设置<b>小地图</b>那一屏要保住的页范围。
	 *
	 * <p>层与全屏地图那一份一样按<b>归档层</b>记——两边看的是同一批页，层号必须一致，否则
	 * {@link #isProtected} 会漏判。
	 */
	public void setMinimapProtectedRange(int layer, int minX, int minZ, int maxX, int maxZ) {
		protection.setMinimap(keyLayer(layer), minX, minZ, maxX, maxZ);
	}

	/** 对"未探明"区块做整轮重试的间隔。 */
	private static final long RETRY_NANOS = 2_000_000_000L;

	/**
	 * 一页每次重试最多重新排多少个区块。
	 *
	 * <p>早期版本是"一页 1024 块里凡是未探明的全部重排"，新区块刚落地时整屏几千块都是未探明，一下就把
	 * 这一帧的预算吃光（地图每隔几秒卡一下）。现在按游标轮转；取 512（半页）——轮转太慢的话，那一块
	 * 黑着的时间就长得看得见（"有的地方一直是暗块，开一次地图才补上"）。
	 */
	private static final int RETRY_SLICE = 512;

	/** 上次重试的时间。 */
	private long retryAtNanos;

	/**
	 * 把"问过但没画上地形"的区块再问一遍（空洞、以及当时数据还没到的那些）。
	 *
	 * <p>填一页时数据还不在的话，这一块会以空洞定格；而页内游标一路向前、走完不回，那片黑就永远留着。
	 * 这里每隔 {@value #RETRY_NANOS} 把这类区块重问一遍，真有了数据就画上；每次只翻
	 * {@value #RETRY_SLICE} 块。
	 *
	 * <p><b>直接在这儿补，不动填充游标</b>：游标一归零那一页就变成"还有活要干"、界面立刻给它转圈。
	 * 判定用 {@link TilePage#hasTerrain} 而不读贴图像素——一整块里只要有一列没数据，角落像素就是透明的，
	 * 按像素判断会把好页反复挑出来重填。
	 */
	public void retryUnexplored(long now) {
		if (now < retryAtNanos) {
			return;
		}
		retryAtNanos = now + RETRY_NANOS;
		boolean spent = false;
		// 按"离玩家由近到远"排页。固定按轮转顺序扫的话，预算会大量花在"玩家根本没去过"的
		// 页上——那些块注定补不上，而玩家脚下这几页（真正有新数据的）反而轮不到。
		// 实测：不排序时每轮扫到上千个候选、只补上 1~3 块。
		int playerPageX = Integer.MIN_VALUE;
		int playerPageZ = Integer.MIN_VALUE;
		ClientLevel level = Minecraft.getInstance().level;
		if (level != null && Minecraft.getInstance().player != null) {
			playerPageX = Math.floorDiv(Minecraft.getInstance().player.getBlockX() >> 4, PAGE_CHUNKS);
			playerPageZ = Math.floorDiv(Minecraft.getInstance().player.getBlockZ() >> 4, PAGE_CHUNKS);
		}
		java.util.List<TilePage> ordered = new java.util.ArrayList<>(resident.values());
		final int centerX = playerPageX;
		final int centerZ = playerPageZ;
		if (centerX != Integer.MIN_VALUE) {
			ordered.sort(java.util.Comparator.comparingInt(
					page -> Math.max(Math.abs(page.pageX - centerX), Math.abs(page.pageZ - centerZ))));
		}
		for (TilePage page : ordered) {
			for (int step = 0; step < RETRY_SLICE; step++) {
				// 补画也走同一张固定表（见 SWEEP_ORDER），
				// 不然补上的那几块会以另一种顺序出现，与填充的走向混在一起
				int index = SWEEP_ORDER[page.retryCursor];
				page.retryCursor = (page.retryCursor + 1) % PAGE_CHUNK_COUNT;
				if (page.hasTerrain[index]) {
					continue;
				}
				// 只挑"问过、但画的是空洞"的：还没问过的那些由填充的 pending 表管，
				// 没必要在这儿再问一遍（问一次可能就要采样一整块）
				if (!page.filled[index]) {
					continue;
				}
				if (colors.isWarmSpent()) {
					spent = true;
					break;
				}
				int chunkX = page.pageX * PAGE_CHUNKS + index % PAGE_CHUNKS;
				int chunkZ = page.pageZ * PAGE_CHUNKS + index / PAGE_CHUNKS;
				boolean hasData = colors.shadeChunkPair(chunkX, chunkZ, dayPixels, nightPixels);
				if (!hasData && !colors.isKnownEmpty(chunkX, chunkZ)) {
					// 还没定论（预算用尽 / 请求在路上）：留着，别急着把它钉成空洞
					continue;
				}
				if (!hasData) {
					// 还是"没有地形"：这一格本来就画成未探明了，重涂一遍没有任何变化，
					// 却会把它标脏、让整页重传一次显存——看着就像"这一页又被扫了一遍"。
					// 边角上那种"探不到就不动"的黑块因此每轮都被白扫一次。直接跳过。
					continue;
				}
				writeChunk(page, index, true);
			}
			if (spent) {
				break;
			}
		}
	}

	/**
	 * 粗档：同一批叶页的<b>更低像素密度</b>版本。
	 *
	 * <p>一页覆盖 1024 方块（= {@value #COARSE_PACK}×{@value #COARSE_PACK} 张叶页格位），贴图边长 =
	 * 叶页边长 ÷ 方块每像素 × {@link #COARSE_PACK}：叶页 1 方块/像素 → 512×512；{@value #COARSE_TIER_2}
	 * 档 2 方块/像素 → 512×512；{@value #COARSE_TIER_4} 档 4 方块/像素 → 256×256。
	 *
	 * <p><b>让粗档落在叶页那套页网格上</b>：页范围、保护范围、填充顺序、坐标换算于是全部复用叶页那一套，
	 * 只是"一格"由 {@link #COARSE_PACK}² 张叶页格位合成。三档是<b>同一份数据的 1× / 2× / 4× 降采样</b>
	 * （见 {@link #fillCoarse}），相邻档只差 2 倍，切换时颜色与形状连续。
	 *
	 * <p>粗页取色分两步（见 {@link #fillCoarse}）：先降采样更细那一档的同一块（纯内存读），没有才回落到
	 * 按粗步长回世界采样——于是"缩小时已探索的区域"立刻可见，远处没有细层数据的地方也照样有内容。
	 * 也因此<b>粗页不靠"把叶页缩下来"建出来</b>：缩得很小时一屏需要的叶页远超 {@link #MAX_PAGES}。
	 */
	static final int COARSE_TIER_2 = 2;

	/** 更粗的那一档，见 {@link #COARSE_TIER_2}。 */
	public static final int COARSE_TIER_4 = 4;

	/** 一档的贴图边长（像素）：一页仍是 {@value #PAGE_BLOCKS} 方块，方块/像素 = 档。 */
	private static int tierSize(int blocksPerPixel) {
		return PAGE_BLOCKS / blocksPerPixel;
	}

	/**
	 * 一张粗页覆盖几张<b>叶页格位</b>（每轴）。{@value} = 2×2 张合成一张，即 1024 方块见方。
	 *
	 * <p><b>为什么要合。</b>缩到最远时一屏有两千多个页格位，每个格位一张独立贴图就是两千多次"贴图绑定 +
	 * 绘制调用"——"缩到最小明显掉帧"里最大的一块。合起来后张数、绘制调用、贴图切换都降到
	 * {@code 1 / COARSE_PACK²}。
	 *
	 * <p><b>像素密度与页坐标语义不变</b>：还是一张贴图管 {@value}×{@value} 个格位，贴图边长与块数都乘
	 * {@value}，总显存不变（见 {@link #MAX_COARSE_PAGES}）。粗页仓库的键用<b>粗页坐标</b>
	 * （{@code floorDiv(叶页坐标, COARSE_PACK)}）。
	 */
	public static final int COARSE_PACK = 2;

	/** 一张粗页的贴图边长（像素）：叶页网格的边长乘上 {@link #COARSE_PACK}。绘制时按它折算取样范围。 */
	public static int coarseSize(int blocksPerPixel) {
		return tierSize(blocksPerPixel) * COARSE_PACK;
	}

	/**
	 * 粗页最多留几张。
	 *
	 * <p>缩到最小时一屏约 32×18 ≈ 576 张（一张覆盖 1024 方块）。<b>显存代价与合并前相同</b>：
	 * 4 档一张昼夜两张共 512 KB，{@value #MAX_COARSE_PAGES} 张约 288 MB——张数除以 4、单张面积乘 4，
	 * 正好抵消。2 档一张 2 MB，但只在中等缩放下用，一屏只有几十张；页走 LRU，离屏的会被淘汰。
	 */
	public static final int MAX_COARSE_PAGES = 2304 / (COARSE_PACK * COARSE_PACK);

	// ------------------------------------------------------------ 粗页（LOD）的对外接口
	// 实现全在 CoarseTileCache（那边的类注释写了"为什么要粗页、每一步怎么取色"），
	// 这里只是转发，外部界面看到的 API 一点没变。

	/** 取一张粗页；没有就建一张空白的。见 {@link CoarseTileCache#coarse}。 */
	public TileVariants coarse(int layer, int blocksPerPixel, int pageX, int pageZ) {
		return coarseCache.coarse(layer, blocksPerPixel, pageX, pageZ);
	}

	/** 取一张<b>已经建出来</b>的粗页；没建过返回 null（不新建）。见 {@link CoarseTileCache#findCoarse}。 */
	public TileVariants findCoarse(int layer, int blocksPerPixel, int pageX, int pageZ) {
		return coarseCache.findCoarse(layer, blocksPerPixel, pageX, pageZ);
	}

	/** 某一档已经建出来的粗页数（诊断用）。 */
	public int coarseCount(int blocksPerPixel) {
		return coarseCache.coarseCount(blocksPerPixel);
	}

	/** 这张粗页现在有没有活要干。见 {@link CoarseTileCache#coarsePending}。 */
	public boolean coarsePending(int layer, int blocksPerPixel, int pageX, int pageZ) {
		return coarseCache.coarsePending(layer, blocksPerPixel, pageX, pageZ);
	}

	/** 这张粗页还没铺完（含"还没建出来"）。见 {@link CoarseTileCache#coarseUnfinished}。 */
	public boolean coarseUnfinished(int layer, int blocksPerPixel, int pageX, int pageZ) {
		return coarseCache.coarseUnfinished(layer, blocksPerPixel, pageX, pageZ);
	}

	/** 这张粗页是不是正在加载（已开工、这一遍还没扫完）。见 {@link CoarseTileCache#coarseAwaitingData}。 */
	public boolean coarseAwaitingData(int layer, int blocksPerPixel, int pageX, int pageZ) {
		return coarseCache.coarseAwaitingData(layer, blocksPerPixel, pageX, pageZ);
	}

	/** 这张粗页有没有真的填进过一块地形。见 {@link CoarseTileCache#coarseHasRealTerrain}。 */
	public boolean coarseHasRealTerrain(int layer, int blocksPerPixel, int pageX, int pageZ) {
		return coarseCache.coarseHasRealTerrain(layer, blocksPerPixel, pageX, pageZ);
	}

	/** 把一张粗页往外填一点（含"优先降采样、没有才回世界采样"那两步）。见 {@link CoarseTileCache#fillCoarse}。 */
	public boolean fillCoarse(int layer, int blocksPerPixel, int pageX, int pageZ, int budget) {
		return coarseCache.fillCoarse(layer, blocksPerPixel, pageX, pageZ, budget);
	}

	/** 把一张粗页改动过的像素传上显存。见 {@link CoarseTileCache#uploadCoarse}。 */
	public boolean uploadCoarse(int layer, int blocksPerPixel, int pageX, int pageZ,
			boolean wantDay, boolean wantNight) {
		return coarseCache.uploadCoarse(layer, blocksPerPixel, pageX, pageZ, wantDay, wantNight);
	}

	/**
	 * 取"更细那一档"的某一页——粗页降采样的来源（见 {@link CoarseTileCache#fillCoarse} 第 1 步）。
	 *
	 * <p>传的是<b>叶页坐标</b>（粗页那一侧先按 {@link #COARSE_PACK} 折算），只是一次<b>同键查找</b>，
	 * 把键里的档位换成叶页的 0（{@link #gradeCode}）。它是粗页仓库问宿主的<b>唯一</b>一件事。
	 *
	 * <p><b>常驻表与冷表都要查</b>：冷表里的页只是回收了显存、像素仍在堆里（见 {@link #cold}）。
	 *
	 * <p><b>只从叶页降采样</b>（2 档取 2×2、4 档取 4×4 个叶像素），不从 2 档那一页再降一层：2 档页只在
	 * 中等缩放下才建，而"叶页一变就作废粗页"这条失效链（见 {@link CoarseTileCache#markDirty}）只覆盖叶页。
	 */
	private TilePage finePage(int layer, int pageX, int pageZ) {
		long key = key(layer, 0, pageX, pageZ);
		TilePage page = resident.get(key);
		if (page == null) {
			page = cold.get(key);
			if (page == null) {
				return null;
			}
		}
		// 先按精确高度校准：降采样读的是它的像素，没校准就会把上一格高度的图缩下来。
		// 同一帧稍后的叶页填充也会走到这条（见 page/find），那时它已经校准过、直接返回。
		refreshCaveHeight(page, layer);
		return page;
	}

}
