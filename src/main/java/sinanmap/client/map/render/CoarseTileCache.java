package sinanmap.client.map.render;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import com.mojang.blaze3d.platform.NativeImage;

import sinanmap.common.SinanConstants;
import sinanmap.common.map.LayerSelector;
import sinanmap.client.map.data.ClientChunkColorProvider;

/**
 * 粗页（LOD）仓库：把<一份数据>按 2 / 4 方块每像素再降一次采样，缩得很远时改画它。
 *
 * <p>为什么要有它：页的贴图是"一个像素一个方块"，缩小时一屏要铺的页数随缩放线性增长，{@link
 * WorldTilePages#MAX_PAGES} 那种上限只能把临界点往外推；真正的解法是让"一屏的页数"不再增长——粗页把
 * {@link WorldTilePages#COARSE_PACK}² 张叶页格位合成一张贴图，张数与绘制调用都降到四分之一。
 *
 * <p>三档是<b>同一份数据的 1× / 2× / 4× 降采样</b>（见 {@link #fillCoarse}），相邻档只差 2 倍，切换时
 * 颜色与形状连续。粗页取色分两步：先降采样更细那一档的同一块（纯内存读），没有才回落到按粗步长回世界
 * 采样——于是"缩小时已探索的区域"立刻可见，远处没有细层数据的地方也照样有内容。
 *
 * <p><b>它只认粗页。</b>页缓存（叶页的建/填/淘汰）、键的算法、更细那一档的页从哪来，都由宿主通过
 * {@link Host} 提供；这样"粗页怎么填、怎么淘汰"与"叶页怎么管"可以各自改。
 */
final class CoarseTileCache {

	/**
	 * 宿主（就是 {@code WorldTilePages}）要告诉粗页仓库的几件事。
	 *
	 * <p>刻意只留三件：**更细那一档的页**（降采样的来源）、**键怎么算**（粗页与叶页共用同一套页坐标，
	 * 只多一个"档"）、**归档层怎么折算**（页键上的层）。
	 */
	interface Host {

		/** 取"更细那一档"的某一页（叶页坐标）；没有就返回 null。 */
		TilePage finerPage(int layer, int pageX, int pageZ);

		/** 这一档这一页的键（与叶页同一套坐标，档作为一个分量）。 */
		long coarseKey(int layer, int blocksPerPixel, int pageX, int pageZ);

		/** 更细那一档的某一页（叶页坐标）是不是"在本界面要铺的范围里、迟早会被铺出来"。 */
		boolean finerExpected(int layer, int pageX, int pageZ);

		/** 页键上用的层（归档层），见 {@code WorldTilePages#keyLayer}。 */
		int keyedLayer(int layer);
	}

	private final ClientChunkColorProvider colors;
	private final TileTexturePool texturePool;
	private final TileProtection protection;
	private final Host host;

	/**
	 * 叶页那两份"一个区块 256 列颜色"的暂存，与宿主共用一份。
	 *
	 * <p>宿主每帧写叶页要用它，粗页回落采样也用它；两边同一条渲染线程、顺序使用，共用不会打架，
	 * 也省掉一页一个 1 KB 的数组。
	 */
	private final int[] dayPixels;
	private final int[] nightPixels;

	/**
	 * 粗页扫描一整遍之后，隔多久再补一次没填上的像素（与叶页的 {@code RETRY_NANOS} 同一个意思）。
	 *
	 * <p>这一遍<b>填上了东西</b>时就用这个值；一个都没填上（剩下的像素全是"还没定论"）则按
	 * {@link #COARSE_RETRY_MAX_NANOS} 逐次翻倍退避——见 {@link #fillCoarse} 末尾。
	 */
	private static final long COARSE_RETRY_NANOS = 1_500_000_000L;

	/**
	 * "空扫"退避的上限。
	 *
	 * <p>实测：一屏粗页里总有那么些像素永远问不出结论（区块在世界存档里没有、又没被加载），它们每
	 * {@code 1.5s} 被整批重问一次——200 次/秒、每次几千像素，合计<b>每秒 40~65 万像素、370~460 毫秒</b>
	 * 花在"再问一遍、还是没结论"上（占一个核的 40%，而画面早就铺完了）。翻倍退避把它压到可忽略：
	 * 真有新数据时 {@link #markDirty} 会把 {@code retryAtNanos} 清 0，那一页立刻重扫，不会变迟钝。
	 */
	private static final long COARSE_RETRY_MAX_NANOS = 30_000_000_000L;

	/**
	 * 档=2 时，粗页最多连着等多久"更细那一档铺到这儿"，见 {@code fillCoarse} 里的 {@code keepFinerOnly}。
	 *
	 * <p>取 {@value} 秒：叶页一页要几十帧才推完，这段时间够它追上来；追不上（比如更细那一页根本没人在铺）
	 * 就恢复回落，宁可多算一遍也不能让这一页一直空着。
	 */
	private static final long MAX_DEFER_NANOS = 2_000_000_000L;

	/**
	 * 更细那一档的前沿离这一格还有多少步时，才值得"等它铺过来"。
	 *
	 * <p>取两列（{@value WorldTilePages#PAGE_CHUNKS}×2 步）：叶页一帧推进几十步，两列是几帧的事，
	 * 等这一下比回世界重采一遍划算；再远就不值得——那时它的前沿多半卡在别处（甚至这一页已经铺完、
	 * 不会再来），等下去就是每 {@value #MAX_DEFER_NANOS} 纳秒空转一轮，收尾会变成"一步一停"。
	 */
	private static final int DEFER_LOOKAHEAD = WorldTilePages.PAGE_CHUNKS * 2;

	/** 粗页仓库（键 = 层 + 档 + 页坐标，页坐标与叶页同一套）。 */
	private final Map<Long, CoarsePage> coarse = new LinkedHashMap<>(32, 0.75F, true);

	/**
	 * 被淘汰出 {@link #coarse}、但<b>像素与填充进度都还留着</b>的粗页。
	 *
	 * <p>必须留着进度：一张粗页要扫好几帧，若淘汰时把 {@link CoarsePage#filled} 表与游标一起丢掉，取回来
	 * 就得从零重扫——一屏粗页压在 {@link WorldTilePages#MAX_COARSE_PAGES} 上时，手一动就淘汰一张、
	 * 再动回来又从头填，同一块地方反复撕裂、永远填不满。
	 *
	 * <p>容量取 {@link WorldTilePages#MAX_COARSE_PAGES}；超出后最久没用过的那张才连像素一起释放。
	 */
	private final Map<Long, CoarsePage> coarseCold = new LinkedHashMap<>(64, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, CoarsePage> eldest) {
			if (size() <= WorldTilePages.MAX_COARSE_PAGES) {
				return false;
			}
			// 推迟一帧再关：填充与绘制是这一帧的两步，当场 close() 会画出别人的 MC 纹理
			texturePool.deferClose(eldest.getValue().textures);
			return true;
		}
	};

	CoarseTileCache(ClientChunkColorProvider colors, TileTexturePool texturePool,
			TileProtection protection, Host host, int[] dayPixels, int[] nightPixels) {
		this.colors = colors;
		this.texturePool = texturePool;
		this.protection = protection;
		this.host = host;
		this.dayPixels = dayPixels;
		this.nightPixels = nightPixels;
	}

	/** 一张粗页的贴图、逐个像素的填充进度、以及"要不要传显存"。 */
	private static final class CoarsePage {
		private final TileVariants textures;
		/** 页键上的层，即<b>归档层</b>；这张粗页属于哪个档。 */
		private final int layer;
		/** 这些像素是按哪个<b>精确层高</b>采的（地表页就是地表哨兵）：跨档时要不要整页作废重填看它。 */
		private int pixelHeight;
		/** 这一档"多少方块一像素"（2 或 4）。 */
		private final int blocksPerPixel;
		/** 粗页坐标：{@code floorDiv(叶页坐标, COARSE_PACK)}，一页覆盖 1024 方块。 */
		private final int pageX;
		private final int pageZ;
		/** 贴图边长（像素），见 {@link WorldTilePages#coarseSize}。 */
		private final int size;
		/** 贴图总像素数（{@code size}×{@code size}）。 */
		private final int pixels;
		/** 一个区块在这一档里占的像素边长（2 档 8、4 档 4）。 */
		private final int pixelsPerChunk;
		/**
		 * 每个粗像素"填过了没有"：只有真写了颜色才算，数据没到的<b>不标</b>，游标绕回来还会再问
		 * （叶页那套 {@code filled} 的教训）。
		 */
		private final boolean[] filled;
		/** 还没填上的像素数；{@link #coarsePending} 与"填完了没有"都看它。 */
		private int remaining;
		/** 页内推进到第几步（步到像素的映射见 {@link #coarseSweepOrder}）；走到末尾绕回 0。 */
		private int fillCursor;
		/** 下次允许重扫的时刻：扫完一遍还有没填上的就往后推，否则"没数据"的粗页每帧空扫、把预算吃光。 */
		private long retryAtNanos;

		/** 下一次重扫的间隔：连着空扫就翻倍退避，见 {@link #COARSE_RETRY_MAX_NANOS}。 */
		private long retryNanos = COARSE_RETRY_NANOS;
		/** 这一轮扫掠已填上的像素数（扫完一圈清零），只为回答"这一轮有没有进展"。 */
		private int sweepPainted;

		/** 这一轮有多少个像素是"更细那一档还没铺到、先留着"的，见 {@link #fillCoarse}。 */
		private int sweepDeferred;

		/**
		 * 从什么时候起这一页"只在等更细那一档"了（0 = 没在等）。
		 *
		 * <p>等到 {@value #MAX_DEFER_NANOS} 还没等到就恢复回落：万一更细那一页根本没人在铺
		 * （视野比它的填充范围宽、或它被挤出了填充顺序），不能让这一页永远等下去。
		 */
		private long deferredSinceNanos;
		/** 上一轮扫掠<b>一个像素都没填上</b>（剩下的全是"还没定论"）：扫完一圈没进展就不再算"正在加载"，但依旧定期重扫。 */
		private boolean stalled;
		/** 这一张有没有<b>真的填进过一块地形</b>（不是"确认没有数据"的透明格）；单调只置 true 不清。 */
		private boolean everTerrain;

		/** 白天/夜晚两张贴图<b>各自</b>有没有改动没传上显存（只传当前要画的那张）。建页时已整页传过一次，初值 false。 */
		private boolean dirtyDay;
		private boolean dirtyNight;

		/** 上一次上屏的时刻（0 = 建页那次整张上传之后还没传过），见 {@link #uploadCoarse}。 */
		private long lastUploadNanos;

		/**
		 * 游标这一遍已经走到末尾（把"现在能填的"都填过了）。
		 *
		 * <p>上屏的时机就是它：一整遍扫完才把这一页传上去，而不是"填一点传一点"。后者会让同一片
		 * 区域反复只显影几十个像素，看起来是"扫了很多次才扫完"。
		 */
		private boolean sweepDone;

		private CoarsePage(TileVariants textures, int layer, int pixelHeight, int blocksPerPixel,
				int pageX, int pageZ) {
			this.textures = textures;
			this.layer = layer;
			this.pixelHeight = pixelHeight;
			this.blocksPerPixel = blocksPerPixel;
			this.pageX = pageX;
			this.pageZ = pageZ;
			this.size = WorldTilePages.coarseSize(blocksPerPixel);
			this.pixels = size * size;
			this.pixelsPerChunk = tierPixelsPerChunk(blocksPerPixel);
			this.filled = new boolean[pixels];
			this.remaining = pixels;
		}
	}

	/** 一个区块在某一档里占的像素边长：16 方块 / 每像素方块数（2 档 → 8、4 档 → 4）。 */
	private static int tierPixelsPerChunk(int blocksPerPixel) {
		return SinanConstants.CHUNK_SIZE / blocksPerPixel;
	}

	/**
	 * 粗页填充顺序（每档一张）：<b>按区块一列一列地扫</b>，走向与理由写在
	 * {@link TileSweepOrder#coarseColumns} 那里——页内的推进顺序全工程只此一处。
	 */
	private static final int[] COARSE_SWEEP_4 = buildCoarseSweep(WorldTilePages.COARSE_TIER_4);
	private static final int[] COARSE_SWEEP_8 = buildCoarseSweep(WorldTilePages.COARSE_TIER_8);

	/** 取某一档的填充顺序表，见 {@link #COARSE_SWEEP_4}。 */
	private static int[] coarseSweepOrder(int blocksPerPixel) {
		return blocksPerPixel == WorldTilePages.COARSE_TIER_4 ? COARSE_SWEEP_4 : COARSE_SWEEP_8;
	}

	/** 生成某一档的顺序表：把参数折成"贴图边长 / 每区块像素 / 每边区块数"交给 {@link TileSweepOrder}。 */
	private static int[] buildCoarseSweep(int blocksPerPixel) {
		return TileSweepOrder.coarseColumns(
				WorldTilePages.coarseSize(blocksPerPixel),
				tierPixelsPerChunk(blocksPerPixel),
				// 一张粗页管 COARSE_PACK×COARSE_PACK 张叶页格位，区块数也跟着乘上来
				WorldTilePages.PAGE_CHUNKS * WorldTilePages.COARSE_PACK);
	}

	/**
	 * 取一张粗页；没有就建一张空白的（若刚从"冷"表回来，把进度接上）。
	 *
	 * <p>空白页要<b>立刻上传一次</b>：显存贴图构造时只建对象、不写内容，不上传就画会拿到未初始化的显存
	 * （"在地图上看见别的 MC 纹理"的一种）。
	 *
	 * <p>页键是<b>归档层</b>；拿到已存在的页后要按精确高度校准一次（见 {@link #refreshCoarseHeight}）。
	 */
	TileVariants coarse(int layer, int blocksPerPixel, int pageX, int pageZ) {
		long key = host.coarseKey(layer, blocksPerPixel, pageX, pageZ);
		CoarsePage found = coarse.get(key);
		if (found != null) {
			refreshCoarseHeight(found, layer);
			return found.textures;
		}
		// 刚被淘汰出去的那一张：像素与填充进度都还在，把显存要回来就接着填，不必从头再扫一遍
		CoarsePage cached = coarseCold.remove(key);
		if (cached != null) {
			cached.textures.reacquireGpu();
			// 重新申请显存时会把现有像素整张传上去，两张都算传过了
			cached.dirtyDay = false;
			cached.dirtyNight = false;
			coarse.put(key, cached);
			evictCoarseOverflow();
			refreshCoarseHeight(cached, layer);
			return cached.textures;
		}
		evictCoarseOverflow();
		TileVariants created = texturePool.obtain(WorldTilePages.coarseSize(blocksPerPixel));
		created.day().upload();
		created.night().upload();
		coarse.put(key, new CoarsePage(created, host.keyedLayer(layer), layer, blocksPerPixel, pageX, pageZ));
		return created;
	}

	/**
	 * 粗页的精确高度校准：同一落盘档内不动，跨过<b>落盘档</b>就整页作废重填。
	 *
	 * <p>不逐块问 {@code caveSliceCovers}（一屏可能几百张、每张几百个区块，几百万次查表得不偿失），用落盘档
	 * 当判据正好：档内数据本来就不变，已加载区块的变化由叶页重画时经 {@link #markDirty} 作废；跨档则整页
	 * 作废重扫。
	 */
	private void refreshCoarseHeight(CoarsePage page, int layer) {
		if (!LayerSelector.isCave(layer) || page.pixelHeight == layer) {
			return;
		}
		int previous = page.pixelHeight;
		page.pixelHeight = layer;
		if (LayerSelector.storageLayer(previous) == LayerSelector.storageLayer(layer)) {
			return;
		}
		Arrays.fill(page.filled, false);
		page.remaining = page.pixels;
		page.fillCursor = 0;
		page.sweepPainted = 0;
		page.sweepDeferred = 0;
		// 换档了：这是全新的一遍，重新给它"先等更细那一档"的机会
		page.deferredSinceNanos = 0L;
		page.stalled = false;
		page.retryAtNanos = 0L;
	}

	/**
	 * 取一张<b>已经建出来</b>的粗页；没建过就返回 null（<b>不新建</b>）。绘制时用。
	 *
	 * <p>与叶页 {@code find} 同一分工：绘制每帧都跑，若顺手新建，一帧就能把一屏粗页全建出来
	 * （几百张的巨型卡顿与显存尖峰）。只认常驻表——"冷"表里的已回收显存，直接画会画出别人的 MC 纹理，
	 * 返回 null 交给 {@link #coarse} 走 reacquire 那条路。
	 */
	TileVariants findCoarse(int layer, int blocksPerPixel, int pageX, int pageZ) {
		CoarsePage page = coarse.get(host.coarseKey(layer, blocksPerPixel, pageX, pageZ));
		if (page == null) {
			return null;
		}
		// 绘制走的常常是这条（页已经建好就不再调 coarse()），所以校准也要在这里做一次，
		// 否则跨落盘档之后会照着上一档的像素画（见 refreshCoarseHeight）。
		refreshCoarseHeight(page, layer);
		return page.textures;
	}

	/**
	 * 淘汰超出上限的粗页：从最久没用过的开始，但<b>跳过视野内的那一批</b>。正在填的那张一定在视野内，
	 * 绝不能被自己挤掉（否则贴图清空再重填，画面上就是撕裂）。
	 *
	 * <p>被淘汰的那张<b>不销毁</b>：只回收显存，像素与填充进度进"冷"表留着，回视野时接上。
	 */
	private void evictCoarseOverflow() {
		while (coarse.size() >= WorldTilePages.MAX_COARSE_PAGES) {
			Iterator<Map.Entry<Long, CoarsePage>> iterator = coarse.entrySet().iterator();
			boolean removed = false;
			while (iterator.hasNext()) {
				Map.Entry<Long, CoarsePage> entry = iterator.next();
				CoarsePage candidate = entry.getValue();
				if (protection.overlapsCoarse(candidate.layer, candidate.pageX, candidate.pageZ)) {
					// 视野内的粗页不许被自己挤掉
					continue;
				}
				iterator.remove();
				rejectToCoarseCold(entry.getKey(), candidate);
				removed = true;
				break;
			}
			if (!removed) {
				// 视野内全在保护范围里：宁可暂时超上限，也不能把正在看的粗页挤掉
				break;
			}
		}
	}

	/** 把一张粗页从常驻挪进"冷"表：<b>只回收显存，像素与填充进度都留着</b>。 */
	private void rejectToCoarseCold(long key, CoarsePage page) {
		page.textures.releaseGpu();
		// 显存已经还回去了，回视野时 reacquireGpu 会把整张重传，这里不必再标脏
		page.dirtyDay = false;
		page.dirtyNight = false;
		coarseCold.put(key, page);
	}

	/** 某一档已经建出来的粗页数（诊断用）。 */
	int coarseCount(int blocksPerPixel) {
		int count = 0;
		for (CoarsePage page : coarse.values()) {
			if (page.blocksPerPixel == blocksPerPixel) {
				count++;
			}
		}
		return count;
	}

	/**
	 * 这张粗页现在有没有活要干：还有没填上的像素、<b>而且</b>不在"刚扫完一遍的冷却期"里。
	 *
	 * <p>冷却那条很关键：没有它，一张永远取不到数据的粗页会每帧空扫、把预算吃光，"缩出去只有一块有图、
	 * 其余一片空"。
	 */
	boolean coarsePending(int layer, int blocksPerPixel, int pageX, int pageZ) {
		CoarsePage page = coarse.get(host.coarseKey(layer, blocksPerPixel, pageX, pageZ));
		return page != null && page.remaining > 0 && System.nanoTime() >= page.retryAtNanos;
	}

	/**
	 * 这张粗页<b>还没铺完</b>：还没建出来、或还有没填上的像素。只给诊断计量的"填满一屏"用，
	 * 所以"还没建出来"也算没铺完。与 {@link #coarsePending} 的区别：后者还要求"不在冷却期"。
	 */
	boolean coarseUnfinished(int layer, int blocksPerPixel, int pageX, int pageZ) {
		CoarsePage page = coarse.get(host.coarseKey(layer, blocksPerPixel, pageX, pageZ));
		return page == null || page.remaining > 0;
	}

	/**
	 * 这张粗页是不是<b>正在加载</b>：已经开过工、这一遍还没扫完——加载指示该画在它身上。
	 *
	 * <p>判据与叶页不同：粗页游标扫完一圈会绕回起点，所以用"游标不在起点、且还有没填上的"。
	 * <b>还要排除"停住了"的</b>，否则剩下全是"还没定论"的粗页会被定期唤起重扫、圈一直转。
	 */
	boolean coarseAwaitingData(int layer, int blocksPerPixel, int pageX, int pageZ) {
		CoarsePage page = coarse.get(host.coarseKey(layer, blocksPerPixel, pageX, pageZ));
		return page != null && page.fillCursor > 0 && page.remaining > 0 && !page.stalled;
	}

	/** 这张粗页有没有<b>真的填进过一块地形</b>（见 {@link CoarsePage#everTerrain}）。 */
	boolean coarseHasRealTerrain(int layer, int blocksPerPixel, int pageX, int pageZ) {
		CoarsePage page = coarse.get(host.coarseKey(layer, blocksPerPixel, pageX, pageZ));
		return page != null && page.everTerrain;
	}

	/**
	 * 把一张粗页往外填一点：每次最多填 {@code budget} 个粗像素。
	 *
	 * <p><b>一个粗像素覆盖 {@code blocksPerPixel} 个方块</b>，所以一个区块摊到 {@code pixelsPerChunk}² 个
	 * 粗像素（2 档 8×8、4 档 4×4）。
	 *
	 * <p>每个像素按两步取色：
	 * <ol>
	 *   <li><b>优先从更细那一档降采样。</b>粗页与叶页共用同一套页网格，"更细那一档的同一页"是一次
	 *       <b>同键查找</b>；那一页画过这一块时直接取对应的 2×2 / 4×4 个叶像素逐通道求平均——
	 *       <b>一步纯内存读</b>。</li>
	 *   <li>更细那一页没有这一块时才回落到 {@link ClientChunkColorProvider#shadeChunkPair} 按粗步长
	 *       <b>回世界直接采样</b>。粗页于是自动复用缓存 / 后台读盘 / 群系染色修正。</li>
	 * </ol>
	 * 第 2 步保住了"远处没有细层数据也能有内容"。
	 *
	 * <p><b>为什么取平均而不是取中心那一列。</b>只取一列等于"每几个方块挑 1 列当代表"，粗页就成了独立
	 * 采样出来的图，三档切换时明显像"换了一幅画"；取平均是对同一份数据的降采样，三档颜色与形状连续。
	 *
	 * <p>数据还没到就<b>留空、且不标"填过"</b>，游标照常往前、绕一圈回来还会再问；但"世界存档确定没有
	 * 这一块"（{@link ClientChunkColorProvider#isKnownEmpty}）的要记为已填（透明，见
	 * {@link #fillCoarseEmpty}），否则 {@code remaining} 永远清不掉、加载圈也停不下来。
	 *
	 * @return 这一轮有没有<b>进展</b>；调用方据此决定"还要不要接着喂下一张"——没有进展的粗页可能只是在
	 *         等数据，不能挡住后面真有活可干的。
	 */
	boolean fillCoarse(int layer, int blocksPerPixel, int pageX, int pageZ, int budget) {
		CoarsePage page = coarse.get(host.coarseKey(layer, blocksPerPixel, pageX, pageZ));
		if (page == null) {
			return false;
		}
		// 先按精确高度校准（跨落盘档要整页重来）。放在这里而不是只在绘制那条路上：
		// 否则这一帧会先按新高度填一遍，紧接着绘制时又整页作废，白填一次。
		refreshCoarseHeight(page, layer);
		if (page.remaining == 0 || budget <= 0) {
			return false;
		}
		// 【诊断计量】只在本方法这一层/每像素的"分支"上累加，不做逐像素的 nanoTime
		// （一页一批可到几千像素，逐像素取时钟会盖过降采样本身的耗时，把数字变成噪声）。
		long statT0 = ClientChunkColorProvider.STATS_ENABLED ? System.nanoTime() : 0L;
		int statDownsample = 0;
		int statFallback = 0;
		long statFallbackNanos = 0L;
		int size = page.size;
		int pixelsPerChunk = page.pixelsPerChunk;
		// 一个粗像素在一个区块内覆盖的列数（2 档 8、4 档 4）。每页固定，提出循环外
		int columnStep = SinanConstants.CHUNK_SIZE / pixelsPerChunk;
		int[] sweep = coarseSweepOrder(blocksPerPixel);
		// 游标起点：返回值要能表达"这一张有没有进展"
		int startCursor = page.fillCursor;
		NativeImage dayImage = page.textures.day().getPixels();
		NativeImage nightImage = page.textures.night().getPixels();
		// 更细那一档的页（叶页）：一张粗页管 COARSE_PACK² 张叶页，游标扫到哪一块就取那一块上的。
		// 扫描是区块优先的，同一个叶页的像素连着走，所以按"叶页变了没有"缓存。
		int cachedFinePageX = Integer.MIN_VALUE;
		int cachedFinePageZ = Integer.MIN_VALUE;
		TilePage finer = null;
		NativeImage finerDay = null;
		NativeImage finerNight = null;
		int painted = 0;
		// 同一区块的那些粗像素要取同一份 256 列数据，缓存上一块的结果免得重复去问。
		// 靠的是 coarseSweepOrder 的"区块优先"：同一个区块的像素连续扫过，缓存只在换区块时失效。
		int cachedChunkX = Integer.MIN_VALUE;
		int cachedChunkZ = Integer.MIN_VALUE;
		boolean cachedHasData = false;
		// "确定没有数据"也缓存：一个区块摊到几十个粗像素，逐像素再问一遍既慢又没必要
		boolean cachedKnownEmpty = false;
		// 计入预算的像素数：只有<b>真的要回世界问</b>的才算。
		//
		// "确定没有数据"的空像素不计预算：一张合并粗页管 COARSE_PACK² 张叶页格位，而玩家探索过的地方常常
		// 只占其中一条或一小片，其余大片都是"世界存档确定没有"的空格。空格的处理极轻，但以前它和真地形
		// 一样按像素吃预算：一张四分之三是空洞的粗页会把四分之三的预算花在空洞上——这正是"缩远之后粗档
		// 铺得慢"的来源。
		int spent = 0;
		// 本次访问走过多远。空格不计预算，光靠预算兜不住"整页都是空格"这种情况，所以再用"最多绕一整页"
		// 封顶（多走的都只是极轻的迭代）。
		int walked = 0;
		// 中间那一档（更细的是叶页）时"更细那一档还没铺到"的像素<b>先留着、不回落</b>（见 keepFinerOnly）
		long nowDefer = System.nanoTime();
		boolean keepFinerOnly = blocksPerPixel == WorldTilePages.COARSE_TIER_4
				&& (page.deferredSinceNanos == 0L || nowDefer - page.deferredSinceNanos < MAX_DEFER_NANOS);
		while (spent < budget && walked < page.pixels) {
			// 全局建数据预算用完：停在游标上，剩下的下一帧接着填。这一条必须排在游标前进之前：
			// 否则"预算已经用完、一个像素都没填"也会被算成"有进展"，白占掉这一帧的名额。
			if (colors.isWarmSpent()) {
				break;
			}
			int index = sweep[page.fillCursor];
			page.fillCursor = (page.fillCursor + 1) % page.pixels;
			walked++;
			if (page.fillCursor == 0) {
				// "停住了"= 这一遍既没填上、也没在等更细那一档。等更细那一档不算空转：它是在等真实进展，
				// 下一遍（叶页一写出东西就会把它叫醒，见 invalidateCoarseChunk）就能接着填。
				page.stalled = page.sweepPainted == 0 && page.sweepDeferred == 0;
				// 允许上屏：这一遍能填的都填上了，整页一起显影（见 CoarsePage#sweepDone）
				page.sweepDone = true;
				page.sweepPainted = 0;
				// 这一遍在等更细那一档？记下"从什么时候开始等"；某一遍不用等了就清零，下一回重新给机会
				if (page.sweepDeferred > 0) {
					if (page.deferredSinceNanos == 0L) {
						page.deferredSinceNanos = System.nanoTime();
					}
				} else {
					page.deferredSinceNanos = 0L;
				}
				page.sweepDeferred = 0;
				// 这一遍一块都没填上（剩下的全是"还没定论"）：退避翻倍，别每 1.5 秒把同样的像素重问一遍
				// ——实测那是每秒 40~65 万像素、370~460 毫秒的纯浪费（见 COARSE_RETRY_MAX_NANOS）。
				// <b>这一遍填上了东西就不等</b>：说明这一页还有活可干，立刻接着扫下一遍。
				// 以前这里也等一个 {@link #COARSE_RETRY_NANOS}，而"已经填过"的像素不计预算
				// （见下面的 {@code filled} 短路），所以一页接近填完时一遍常常只走一两个像素——
				// 于是每补一格都要等 1.5 秒，27 张页的收尾要 8 秒，屏幕上就是几条黑列过一会儿才补上。
				// 真有新数据时 {@link #markDirty} / {@link #wake} 也会把它清 0。
				if (page.stalled) {
					page.retryNanos = Math.min(page.retryNanos * 2, COARSE_RETRY_MAX_NANOS);
					page.retryAtNanos = System.nanoTime() + page.retryNanos;
				} else {
					page.retryNanos = COARSE_RETRY_NANOS;
					page.retryAtNanos = 0L;
				}
			}
			if (page.filled[index]) {
				// 已经填过的像素：只是查一下数组，不计预算
				continue;
			}
			// 先按"要回世界问"记一步；下面两条"确定没有数据"的空像素会把它退回来
			spent++;
			int pixelX = index % size;
			int pixelZ = index / size;
			// 像素坐标 → 世界区块坐标：一张粗页管 PAGE_CHUNKS × COARSE_PACK 个区块，每块占 pixelsPerChunk 个像素
			int chunkX = pageX * (WorldTilePages.PAGE_CHUNKS * WorldTilePages.COARSE_PACK)
					+ pixelX / pixelsPerChunk;
			int chunkZ = pageZ * (WorldTilePages.PAGE_CHUNKS * WorldTilePages.COARSE_PACK)
					+ pixelZ / pixelsPerChunk;
			// 第 1 步：更细那一档（叶页）的同一块画过这一块没有？画过就直接取对应的 2×2 / 4×4 个叶像素
			// 求平均——纯内存读，比回世界采样快得多。先算出这一像素落在哪张叶页上：粗页原点乘 COARSE_PACK，
			// 再加上像素在粗页内的叶页偏移。
			int finePageX = pageX * WorldTilePages.COARSE_PACK
					+ pixelX * blocksPerPixel / WorldTilePages.PAGE_BLOCKS;
			int finePageZ = pageZ * WorldTilePages.COARSE_PACK
					+ pixelZ * blocksPerPixel / WorldTilePages.PAGE_BLOCKS;
			if (finePageX != cachedFinePageX || finePageZ != cachedFinePageZ) {
				cachedFinePageX = finePageX;
				cachedFinePageZ = finePageZ;
				finer = host.finerPage(layer, finePageX, finePageZ);
				finerDay = finer == null ? null : finer.textures().day().getPixels();
				finerNight = finer == null ? null : finer.textures().night().getPixels();
				if (finerDay == null || finerNight == null) {
					finer = null;
				}
			}
			// 这一像素要不要"等更细那一档铺到这儿"（档=2，见下面的 deferToFiner）
			boolean deferToFiner = false;
			if (finer != null) {
				// 粗像素 (pixelX, pixelZ) 在<b>这一张叶页</b>里对应的叶像素左上角。
				// 它一定落在同一个叶区块里：blocksPerPixel 整除 16，而叶像素左上是它的倍数。
				int leafPixelX = pixelX * blocksPerPixel % WorldTilePages.PAGE_BLOCKS;
				int leafPixelZ = pixelZ * blocksPerPixel % WorldTilePages.PAGE_BLOCKS;
				int leafChunkX = leafPixelX / SinanConstants.CHUNK_SIZE;
				int leafChunkZ = leafPixelZ / SinanConstants.CHUNK_SIZE;
				int leafIndex = leafChunkZ * WorldTilePages.PAGE_CHUNKS + leafChunkX;
				if (finer.filled()[leafIndex]) {
					int fineDay = averageImageBlock(finerDay, leafPixelX, leafPixelZ, blocksPerPixel);
					int fineNight = averageImageBlock(finerNight, leafPixelX, leafPixelZ, blocksPerPixel);
					if (fineDay != 0 || fineNight != 0) {
						dayImage.setPixel(pixelX, pixelZ, fineDay);
						nightImage.setPixel(pixelX, pixelZ, fineNight);
						page.filled[index] = true;
						page.remaining--;
						page.dirtyDay = true;
						page.dirtyNight = true;
						page.everTerrain = true;
						page.sweepPainted++;
						painted++;
						statDownsample++;
						continue;
					}
					if (!finer.hasTerrain(leafIndex)) {
						// 更细那一页明确画过"这里没有数据"：与下面 isKnownEmpty 那条同一个处理。
						// 这是空像素，不计预算
						spent--;
						fillCoarseEmpty(page, dayImage, nightImage, pixelX, pixelZ, index);
						page.sweepPainted++;
						painted++;
						continue;
					}
					// 更细那一页画过地形、但这一小块里一列有数据的都没有：回落去直接采样，别把它当成
					// "没有数据"（叶页一整块里本就有透明列）
				} else {
					// 更细那一档还没铺到这一块。只有<b>它快走到这一格</b>才值得等：差得远说明它的前沿
					// 卡在别处（甚至已经铺完、不会再来），等下去就是每 2 秒空转一轮——收尾时
					// "最后一格走一步停两秒"就是这么来的（见下面的 deferToFiner）。
					int ahead = TilePage.positionOf(leafIndex) - finer.paintCursor;
					deferToFiner = ahead >= 0 && ahead <= DEFER_LOOKAHEAD;
				}
			} else {
				// 更细那一页还没<b>建</b>出来：只要它在界面要铺的范围里，就迟早会被建出来、铺上，
				// 同样值得等（见 Host#finerExpected）。范围之外的（外圈那些）仍走回落，远处不会因此空掉。
				deferToFiner = host.finerExpected(layer, finePageX, finePageZ);
			}
			if (deferToFiner && keepFinerOnly) {
				// 档=2 时先留着，不回世界重采一遍。
				//
				// 为什么值得等：粗页从叶页降采样是纯内存读，回世界采样却要按粗步长<b>逐块问数据</b>——
				// 实测档=2 时"回落"是"降采样"的 5~10 倍（45696px vs 216906px），而两个档位铺的是同一片
				// 地方：叶页本来就要铺、也是粗页降采样的来源，等于同一份数据被算了两遍，档=2 因此比
				// 档=4 慢近一倍。等叶页铺到这儿再取，这一份工作就省掉了。
				//
				// 不会一直等：等到 {@value #MAX_DEFER_NANOS} 纳秒还没等到就恢复回落（见 deferredSinceNanos）；
				// 叶页每写一块也会把这一页叫醒（见 invalidateCoarseChunk），所以它是跟着叶页往前走的。
				spent--;
				page.sweepDeferred++;
				continue;
			}
			// 第 2 步：回落——回世界按粗步长直接采样（可能走缓存、也可能要后台读盘）。
			statFallback++;
			if (chunkX != cachedChunkX || chunkZ != cachedChunkZ) {
				cachedChunkX = chunkX;
				cachedChunkZ = chunkZ;
				long statSampleT0 = ClientChunkColorProvider.STATS_ENABLED ? System.nanoTime() : 0L;
				cachedHasData = colors.shadeChunkPair(chunkX, chunkZ, dayPixels, nightPixels);
				if (ClientChunkColorProvider.STATS_ENABLED) {
					statFallbackNanos += System.nanoTime() - statSampleT0;
				}
				cachedKnownEmpty = !cachedHasData && colors.isKnownEmpty(chunkX, chunkZ);
			}
			if (!cachedHasData) {
				if (!cachedKnownEmpty) {
					// 还没定论（数据在路上 / 请求没排上 / 区块还没生成完）：留空，下次游标绕回来再问；
					// 不要标成"填过"。这一步照样计预算（它真的要问世界）
					continue;
				}
				// 世界存档确定没有这一块：也要记为已填（透明）。不记的话这一格的 filled 永远为假、
				// 一页的 remaining 永远大于 0，游标每绕一圈都在同一批空格上空转，加载圈也就永远转不完
				// （真有了数据仍会走 writeChunk → markDirty 把这一格重新作废）。空格不计预算。
				spent--;
				fillCoarseEmpty(page, dayImage, nightImage, pixelX, pixelZ, index);
				page.sweepPainted++;
				painted++;
				continue;
			}
			// 该像素在区块内那一小块（columnStep×columnStep 列）里的位置，取那一块里的列求平均
			int baseColumnX = pixelX % pixelsPerChunk * columnStep;
			int baseColumnZ = pixelZ % pixelsPerChunk * columnStep;
			dayImage.setPixel(pixelX, pixelZ,
					averageBlock(dayPixels, baseColumnX, baseColumnZ, columnStep));
			nightImage.setPixel(pixelX, pixelZ,
					averageBlock(nightPixels, baseColumnX, baseColumnZ, columnStep));
			page.filled[index] = true;
			page.remaining--;
			page.dirtyDay = true;
			page.dirtyNight = true;
			page.everTerrain = true;
			page.sweepPainted++;
			painted++;
		}
		// 【诊断计量】本次粗页填充的耗时，以及"降采样/回落"两条路各命中多少像素、回落真正花了多久
		if (ClientChunkColorProvider.STATS_ENABLED) {
			colors.stats().recordFillCoarse(System.nanoTime() - statT0, statDownsample, statFallback,
					statFallbackNanos);
		}
		// 填上了像素、或游标往前走了，都算"这一张有进展"（与叶页 fillSome 同一个口径）。
		return painted > 0 || page.fillCursor != startCursor;
	}

	/**
	 * 把一个粗像素记成"已填、没有数据"（透明），见 {@link #fillCoarse}。
	 *
	 * <p><b>必须记为已填。</b>否则这一格永远填不上、{@code remaining} 永远大于 0，游标每绕一圈都在同一批
	 * 空格上空转（"有些粗页一直转圈"的成因）。真有了数据时会被 {@link #markDirty} 重新作废，所以记成已填
	 * 不会把数据挡在外面。
	 *
	 * <p>写 0 是必要的：{@link #invalidateCoarseChunk} 只清 {@code filled} 不清像素，从"有数据"变成
	 * "确定没有"时得把旧颜色擦掉。
	 */
	private static void fillCoarseEmpty(CoarsePage page, NativeImage dayImage, NativeImage nightImage,
			int pixelX, int pixelZ, int index) {
		dayImage.setPixel(pixelX, pixelZ, 0);
		nightImage.setPixel(pixelX, pixelZ, 0);
		page.filled[index] = true;
		page.remaining--;
		// 两张像素都改过；不置 everTerrain：这是"确认没有数据"的透明格，不是真地形
		page.dirtyDay = true;
		page.dirtyNight = true;
	}

	/**
	 * 对 {@code step}×{@code step} 的一小块列（起点 {@code baseX}/{@code baseY}）逐通道求算术平均，
	 * <b>只算有数据的列</b>。
	 *
	 * <p>没有数据的一列在 {@code ChunkRecord#toColors} 里输出<b>全透明</b>（alpha == 0），算进去会把整格
	 * 平均得又暗又透。这一块都没数据时返回 0（全透明）。
	 */
	private static int averageBlock(int[] colors, int baseX, int baseY, int step) {
		int a = 0;
		int r = 0;
		int g = 0;
		int b = 0;
		int count = 0;
		for (int y = 0; y < step; y++) {
			int rowBase = (baseY + y) * SinanConstants.CHUNK_SIZE + baseX;
			for (int x = 0; x < step; x++) {
				int pixel = colors[rowBase + x];
				if ((pixel >>> 24) != 0) {
					a += pixel >>> 24;
					r += (pixel >> 16) & 0xFF;
					g += (pixel >> 8) & 0xFF;
					b += pixel & 0xFF;
					count++;
				}
			}
		}
		// step×step ≤ 16 个像素、每通道最大 255，int 累加不会溢出
		return count == 0 ? 0 : (a / count) << 24 | (r / count) << 16 | (g / count) << 8 | b / count;
	}

	/**
	 * 与 {@link #averageBlock} 同一口径，只是从<b>更细那一档那一页的贴图</b>上读：取 {@code step}² 个叶像素
	 * 逐通道求平均，<b>只算不透明的</b>。纯内存读，不回世界、不采样、不读盘；返回 0 表示一个不透明像素都没有。
	 */
	private static int averageImageBlock(NativeImage image, int baseX, int baseY, int step) {
		int a = 0;
		int r = 0;
		int g = 0;
		int b = 0;
		int count = 0;
		for (int y = 0; y < step; y++) {
			for (int x = 0; x < step; x++) {
				int pixel = image.getPixel(baseX + x, baseY + y);
				if ((pixel >>> 24) != 0) {
					a += pixel >>> 24;
					r += (pixel >> 16) & 0xFF;
					g += (pixel >> 8) & 0xFF;
					b += pixel & 0xFF;
					count++;
				}
			}
		}
		return count == 0 ? 0 : (a / count) << 24 | (r / count) << 16 | (g / count) << 8 | b / count;
	}

	/** 粗页两次上屏之间至少隔这么久，见 {@link #uploadCoarse}。 */
	private static final long COARSE_UPLOAD_INTERVAL_NANOS = 80_000_000L;

	/**
	 * 把一张粗页改动过的像素传上显存。
	 *
	 * <p>与叶页的 {@code upload} 分开：粗页的像素由 {@code fillCoarse} 写，调用方按每帧上传名额限流。
	 *
	 * <p><b>只传当前要画的那一张</b>：以前两张无条件都传等于上传带宽白翻一倍。没传的那张不标"传过"，
	 * 等天色需要时再补传，结果一致。
	 *
	 * <p><b>上屏时机 = "一整遍扫完"。</b>粗页一屏只有几十张，而每张都得<b>整张</b>重传（2 档一张就有 1 MB）：
	 * 填一点传一点的话，一屏粗页会以几十个像素为单位一格一格显影（实测每秒 183~211 次上传）——
	 * 于是同一片区域反复只显影几十个像素、扫了很多次才扫完。改成"游标走完一遍、把这一遍
	 * 能填的都填上了才传"：每页一次显影一批，扫的顺序就是看得见的顺序（一列列铺开）。
	 *
	 * <p>另加一个 {@value #COARSE_UPLOAD_INTERVAL_NANOS} 纳秒的下限，防止"页小、扫一圈很快"时连着传。
	 * 页<b>填完</b>时不受任何限制，最后一块立刻上屏。
	 *
	 * @return 有没有真的传过东西
	 */
	boolean uploadCoarse(int layer, int blocksPerPixel, int pageX, int pageZ,
			boolean wantDay, boolean wantNight) {
		CoarsePage page = coarse.get(host.coarseKey(layer, blocksPerPixel, pageX, pageZ));
		if (page == null) {
			return false;
		}
		long nowNanos = System.nanoTime();
		if (page.remaining > 0) {
			// 还没填完：要等"这一遍扫完了"，<b>而且这一遍没再填上新东西</b>。
			//
			// 后半条是"整页一起显影"的关键。一遍扫完常常只填上了"数据已经到手"的那几列，剩下的列要么还在
			// 读盘、要么正被上一层留下的旧像素占着（换层级时整页作废重扫，见 {@link #refreshCoarseHeight}）。
			// 这时候传上去，画面上就是<b>新老混着、夹着几列黑的</b>：页面出现后有几条黑列没加载、
			// 过一会儿才补上，切换层级时尤其明显。
			//
			// 等一遍真的一个像素都填不上（{@code stalled}）再传：这一页此刻能填的都填上了，整页一次显形；
			// 换层级时旧那层的样子会一直留着，直到新这层铺好才整体翻过去，中间不会露黑列。
			//
			// <b>不会因此一直不出图</b>：只要没有新数据，随后总有一遍填不上任何像素，那一遍就放行；
			// 填完的（{@code remaining == 0}）更是不受这条限制（见下面的分支）。
			if (!page.sweepDone || !page.stalled) {
				return false;
			}
			if (page.lastUploadNanos != 0L
					&& nowNanos - page.lastUploadNanos < COARSE_UPLOAD_INTERVAL_NANOS) {
				return false;
			}
		}
		boolean uploaded = false;
		if (wantDay && page.dirtyDay) {
			page.textures.day().upload();
			page.dirtyDay = false;
			uploaded = true;
		}
		if (wantNight && page.dirtyNight) {
			page.textures.night().upload();
			page.dirtyNight = false;
			uploaded = true;
		}
		if (uploaded) {
			// 记下这次上屏的时刻，并把"这一遍扫完了"用掉：下一次要等下一遍扫完（见本方法说明）
			page.lastUploadNanos = nowNanos;
			page.sweepDone = false;
		} else if (!page.dirtyDay && !page.dirtyNight) {
			// 这一遍一点可传的都没有（典型是"整遍空扫"）：许可也得用掉。
			// 留着的话，下一次扫掠进行到一半时它也还是 true，上面那句"扫完一整遍才上屏"就形同虚设——
			// 画面会按"游标扫到哪就传到哪"显影，屏幕上就会剩几列没扫完的黑线。
			page.sweepDone = false;
		}
		return uploaded;
	}

	/**
	 * 叶页的某个区块被写过之后，覆盖它的粗像素作废，下一轮重扫时重新问一遍。
	 *
	 * <p>传进来的是一张粗页管的 COARSE_PACK² 张叶页里的叶页坐标与区块序号，先折成粗页坐标、再算出这一块
	 * 在粗页里的位置，只作废那一个区块覆盖的粗像素，不必整页重扫。
	 *
	 * <p><b>逐个粗像素作废、且不动游标。</b>把游标拨回 0 的话，一边填叶页一边反复清游标，粗页就永远只扫
	 * 最前面那几个像素；不作废则一直显示改动前的样子。
	 */
	void markDirty(int layer, int leafPageX, int leafPageZ, int chunkIndex) {
		// 一张粗页都没建过（一直缩在近处、没缩出去过）：没有任何粗像素要作废。
		// 这是最常见的情况（叶页写一块就调一次这里），提前返回省掉两次查表 × 两档。
		if (coarse.isEmpty() && coarseCold.isEmpty()) {
			return;
		}
		int packX = Math.floorDiv(leafPageX, WorldTilePages.COARSE_PACK);
		int packZ = Math.floorDiv(leafPageZ, WorldTilePages.COARSE_PACK);
		// 这一张叶页落在粗页的哪个位置（0..COARSE_PACK-1），再加上叶页内的区块坐标
		int chunkX = Math.floorMod(leafPageX, WorldTilePages.COARSE_PACK) * WorldTilePages.PAGE_CHUNKS
				+ Math.floorMod(chunkIndex, WorldTilePages.PAGE_CHUNKS);
		int chunkZ = Math.floorMod(leafPageZ, WorldTilePages.COARSE_PACK) * WorldTilePages.PAGE_CHUNKS
				+ Math.floorMod(chunkIndex / WorldTilePages.PAGE_CHUNKS, WorldTilePages.PAGE_CHUNKS);
		invalidateCoarseChunk(layer, WorldTilePages.COARSE_TIER_4, packX, packZ, chunkX, chunkZ);
		invalidateCoarseChunk(layer, WorldTilePages.COARSE_TIER_8, packX, packZ, chunkX, chunkZ);
	}

	/** 把某一档粗页里、某个区块覆盖的那一小块像素标成待重填，见 {@link #markDirty}。 */
	private void invalidateCoarseChunk(int layer, int blocksPerPixel, int pageX, int pageZ,
			int chunkX, int chunkZ) {
		long key = host.coarseKey(layer, blocksPerPixel, pageX, pageZ);
		CoarsePage page = coarse.get(key);
		if (page == null) {
			// "冷"表里的那张也要一起作废：它留着像素，回到视野时会直接把旧像素画出来
			// （与叶页 requeue 先查常驻、再查淘汰集合是同一条）
			page = coarseCold.get(key);
		}
		if (page == null) {
			return;
		}
		int pixelsPerChunk = page.pixelsPerChunk;
		int baseX = chunkX * pixelsPerChunk;
		int baseZ = chunkZ * pixelsPerChunk;
		boolean invalidated = false;
		for (int z = 0; z < pixelsPerChunk; z++) {
			int row = (baseZ + z) * page.size + baseX;
			for (int x = 0; x < pixelsPerChunk; x++) {
				if (page.filled[row + x]) {
					page.filled[row + x] = false;
					page.remaining++;
					invalidated = true;
				}
			}
		}
		// 走到这儿就说明"这一页的更细来源又前进了一块"：立刻允许重扫，别等冷却期；同时把"停住了"与
		// 翻倍退避一起清掉——这一页又有真活干了，加载指示该照常出现。
		//
		// <b>不看 invalidated</b>：档=2 时粗页常常在"等更细那一档铺到这儿"（那些像素本来就没填过，
		// 一个都不会被作废），正是靠这一次次叫醒才跟着叶页往前走；只有像素被作废时才叫醒的话，
		// 等待中的粗页就只能干等到退避上限（30 秒）。
		page.stalled = false;
		page.retryAtNanos = 0L;
		page.retryNanos = COARSE_RETRY_NANOS;
	}

	/**
	 * <b>"这一格的数据刚到手 / 内容刚变"：把覆盖它的粗页从退避里叫醒。</b>
	 *
	 * <p>粗像素在"回世界采样还拿不到数据"时留空、{@code filled} 也不标，只等游标下一遍绕回来再问；而"再问"
	 * 的间隔在整遍空扫之后会逐次翻倍（见 {@link #COARSE_RETRY_MAX_NANOS}）。一页填到只剩下几个洞时，
	 * 之后每一遍都是空扫，退避会一路涨到上限——<b>那几个洞就要黑整整数十秒</b>，屏幕上是几列一直补不上的黑线。
	 *
	 * <p>数据到手这件事只有 {@code ClientChunkColorProvider} 知道（{@code drainArrivedChunks} /
	 * {@code drainChangedChunks}），由 {@code WorldTilePages#markArrived} / {@code #markStale} 转达。
	 * 这里只清退避、不做别的：真要重画哪些像素，交给下一遍扫掠按数据实际情况决定。
	 */
	void wake(int layer, int chunkX, int chunkZ) {
		wakeTier(layer, WorldTilePages.COARSE_TIER_4, chunkX, chunkZ);
		wakeTier(layer, WorldTilePages.COARSE_TIER_8, chunkX, chunkZ);
	}

	/** 某一档里覆盖这一格的粗页，见 {@link #wake}。 */
	private void wakeTier(int layer, int blocksPerPixel, int chunkX, int chunkZ) {
		// 一张粗页管 PAGE_CHUNKS × COARSE_PACK 个区块：直接由世界区块坐标折出粗页坐标
		int chunksPerPage = WorldTilePages.PAGE_CHUNKS * WorldTilePages.COARSE_PACK;
		CoarsePage page = coarse.get(host.coarseKey(layer, blocksPerPixel,
				Math.floorDiv(chunkX, chunksPerPage), Math.floorDiv(chunkZ, chunksPerPage)));
		if (page == null) {
			return;
		}
		page.stalled = false;
		page.retryAtNanos = 0L;
		page.retryNanos = COARSE_RETRY_NANOS;
	}

	/** 常驻的粗页张数（诊断用）。 */
	int residentCount() {
		return coarse.size();
	}

	/** "冷"表里的粗页张数（诊断用）。 */
	int coldCount() {
		return coarseCold.size();
	}

	/** 换世界：连堆里留的像素一起丢掉，免得画出别的存档的地形。 */
	void releaseAll() {
		for (CoarsePage page : coarse.values()) {
			page.textures.close();
		}
		coarse.clear();
		for (CoarsePage page : coarseCold.values()) {
			page.textures.close();
		}
		coarseCold.clear();
	}
}
