package sinanmap.client.map.render;

/** 一页瓦片：白天与夜晚各一张贴图，绘制时按当前天色混合。 */
public final class TilePage {

	final TileVariants textures;

	/**
	 * 这一页挂在哪一层的键上，即<b>归档层</b>（见 {@link WorldTilePages#keyLayer}；地表是哨兵）。
	 *
	 * <p>它<b>不是</b>像素所在的那个精确高度（那是 {@link #pixelHeight}）：洞穴里上下 1 格会变高度、
	 * 但归档层不变，所以页对象与页键都不动——"下潜一格不必重新过一遍加载"。
	 *
	 * <p>换<b>归档档</b>时会"改挂"到新档（见 {@link WorldTilePages#adoptPreviousBandPage}），所以不是 final。
	 */
	int layer;

	/**
	 * 这些像素是按哪个<b>精确层高</b>画的（地表页就是地表哨兵）。
	 *
	 * <p>同一归档档内上下移动时页键不变、像素却按上一格的高度画，所以记在页上：
	 * {@link WorldTilePages#refreshCaveHeight} 据此判断"该不该把某些区块作废重填"。
	 */
	int pixelHeight;

	final int pageX;
	final int pageZ;

	/** 一页里的区块数：与 {@link WorldTilePages#PAGE_CHUNKS} 配套，几张逐块的表都按它开。 */
	static final int CHUNK_COUNT = WorldTilePages.PAGE_CHUNKS * WorldTilePages.PAGE_CHUNKS;

	/**
	 * 一次<b>上屏</b>的边长（区块）：{@value}×{@value} 个区块（= 64×64 方块）算一个"小块"。
	 *
	 * <p>贴图是 1 方块 1 像素，所以一个小块 = 贴图上 64×64 像素。数据是一块一块到的，若来一块就上屏一块，
	 * 屏幕上就看得见"一格一格地长"（用户反馈）；攒成一个 64×64 的小块再上屏，观感是一块块地出现。
	 * 一次显影的单位是 region 里的子纹理，而不是整页一起上屏。
	 */
	static final int SUB_SIDE = 4;

	/** 一页里有几个"小块"（每轴 {@code PAGE_CHUNKS / SUB_SIDE} 个）。 */
	static final int SUB_COUNT = WorldTilePages.PAGE_CHUNKS / SUB_SIDE * (WorldTilePages.PAGE_CHUNKS / SUB_SIDE);

	/** 每个区块问过了没有（"有数据"与"确认没有数据"都算问过）。 */
	final boolean[] filled = new boolean[CHUNK_COUNT];

	/** 每个"小块"里已经问出结果的区块数；到齐 {@code SUB_SIDE²} 才算这一小块可以上屏。 */
	final byte[] subDecided = new byte[SUB_COUNT];

	/** 有像素写下了、还没传上显存（见 {@link WorldTilePages#upload}）。 */
	boolean pendingUpload;

	/** 至少有一个"小块"凑齐了：现在允许上屏（凑不齐时靠安静时间兜底，见 upload）。 */
	boolean uploadReady;

	/**
	 * 这一块<b>画上的是地形</b>（而不是涂成"没有数据"的空洞）。
	 *
	 * <p>单独记是因为 {@link WorldTilePages#retryUnexplored} 要挑出"重新问一遍"的块：以前去读贴图角落像素，
	 * 但一整块里只要有一列没数据（洞穴里实心岩层那一列就是）角落就是透明的，于是好页被反复挑出来重填。
	 *
	 * <p>与 {@link #filled} 的区别：数据"正在来的路上"时两个都是 false；确认没有数据时
	 * {@code filled=true, hasTerrain=false}；画上地形时两个都是 true。
	 */
	final boolean[] hasTerrain = new boolean[CHUNK_COUNT];

	/**
	 * 这一格的贴图里<b>现在是不是地形像素</b>（可能来自上一格高度）。
	 *
	 * <p>与 {@link #hasTerrain} 的区别：{@code hasTerrain} 表示"这一格的数据就是当前高度的、
	 * 画的确实是地形"，{@code retryUnexplored} 靠它挑出需要重问的块；这一张只回答"像素里有没有东西"。
	 *
	 * <p>洞穴换高度时会出现两者不一致的时刻：那一格的旧像素还在（{@code painted = true}），但它已经
	 * 不代表当前高度了（{@code hasTerrain = false}）。这时重画若发现新高度那边没洞可画，
	 * 就<b>留着旧像素</b>、不涂成未探明——否则一页里会同时闪出一片黑。
	 */
	final boolean[] painted = new boolean[CHUNK_COUNT];

	/**
	 * 这一页有没有<b>真的画出过一块地形</b>（不是"确认没有数据"的空洞）。
	 *
	 * <p>加载指示据此把"在等真数据"与"外周根本没数据"分开：一页全是空洞、数据永远排不到时不该继续
	 * 转圈。单调只置 true 不清——"曾经出过地形"就是"这一片确实有数据"的凭据。
	 */
	boolean everTerrain;

	/**
	 * 页内填充推进到第几步（步到块的映射见 {@link WorldTilePages#SWEEP_ORDER}）。
	 *
	 * <p>页内<b>固定顺序</b>：列优先，不看朝向；"近的先出来"由<b>页与页之间</b>的顺序保证。
	 *
	 * <p>试过"以玩家为中心由内向外"：一页 512 方块见方，由内向外会让四条边最后才填上，画面上每个
	 * 区域边缘一圈一直黑着——比固定顺序更怪。
	 */
	int fillCursor;

	/**
	 * <b>问过、但结果还没到</b>的块（数据在路上；也包括"请求没排上"：见 {@link WorldTilePages#fillOne}）。
	 *
	 * <p>按块记布尔而不是排队表：排队表只认队首——队首没到时整队停着，表只涨不消，涨到上限后新块被
	 * 静默丢掉，那些块既没画上也不在待办里，整页看上去却"填完了"（"加载了一点就再也不动"）。按块记
	 * 则重复标记无害、不会丢、也没有队首阻塞。
	 */
	final boolean[] pending = new boolean[CHUNK_COUNT];

	/** {@link #pending} 里有多少个 true（{@link WorldTilePages#hasWork} 与加载指示都看它）。 */
	int pendingCount;

	/** 补画 {@link #pending} 的轮转游标：一块没到数据也不会挡住后面的块。 */
	int pendingCursor;

	/**
	 * 这一块的结果到了（画上了地形、或确认没有数据）：记进 {@link #filled}，并推进它所属"小块"的进度；
	 * 凑齐一小块就允许上屏，见 {@link #SUB_SIDE}。
	 */
	void decided(int index) {
		if (filled[index]) {
			return;
		}
		filled[index] = true;
		if (++subDecided[subOf(index)] >= SUB_SIDE * SUB_SIDE) {
			uploadReady = true;
		}
	}

	/** 这一块的结果作废了（洞穴换高度、地形被改动）：下次问出结果时重新计入它那一小块。 */
	void undecided(int index) {
		if (!filled[index]) {
			return;
		}
		filled[index] = false;
		subDecided[subOf(index)]--;
	}

	/** 区块序号 {@code z * PAGE_CHUNKS + x} → 它属于第几个"小块"。 */
	private static int subOf(int index) {
		int side = WorldTilePages.PAGE_CHUNKS / SUB_SIDE;
		return index / WorldTilePages.PAGE_CHUNKS / SUB_SIDE * side
				+ index % WorldTilePages.PAGE_CHUNKS / SUB_SIDE;
	}

	/** 记下"这一块还没问出结果"。重复标记无副作用。 */
	void markPending(int index) {
		if (!pending[index]) {
			pending[index] = true;
			pendingCount++;
		}
	}

	/** 这一块的结果到了、也画上了。 */
	void clearPending(int index) {
		if (pending[index]) {
			pending[index] = false;
			pendingCount--;
		}
	}

	/** 画上地形的块数（诊断用：按需数一遍，省得维护一个容易数错的计数器）。 */
	public int terrainCount() {
		int count = 0;
		for (boolean painted : hasTerrain) {
			if (painted) {
				count++;
			}
		}
		return count;
	}

	/** 问过的块数（含确认没有数据的空洞）。 */
	public int decidedCount() {
		int count = 0;
		for (boolean decided : filled) {
			if (decided) {
				count++;
			}
		}
		return count;
	}

	/** 还在等数据的块数（诊断用，见 {@code WorldMapScreen#logComposition}）。 */
	public int pendingCount() {
		return pendingCount;
	}

	/** {@link WorldTilePages#retryUnexplored} 的轮转游标，见那里。 */
	int retryCursor;

	/** 有没有写过像素。刚建出来或刚被回收的页是空的，不必浪费一次绘制。 */
	boolean loaded;

	/** 第一次写上像素的时刻；绘制时据此做"整页淡入"。0 表示还没写过。 */
	long loadedAtNanos;

	/** 白天/夜晚两张贴图<b>各自</b>有没有改动没传到显存（只传当前要画的那张，见 {@link WorldTilePages#upload}）。 */
	boolean dirtyDay = true;
	boolean dirtyNight = true;

	/** 上一次往这一页写像素的时刻：页还在陆续来数据时先不上传，靠它判断"静下来了没有"。 */
	long paintedAtNanos;

	TilePage(TileVariants textures, int layer, int pixelHeight, int pageX, int pageZ) {
		this.textures = textures;
		this.layer = layer;
		this.pixelHeight = pixelHeight;
		this.pageX = pageX;
		this.pageZ = pageZ;
	}

	public TileVariants textures() {
		return textures;
	}

	/** 逐块的"问过了没有"表。<b>只读</b>：粗页降采样时要看更细那一页画过这一块没有。 */
	boolean[] filled() {
		return filled;
	}

	/** 这一块画的是不是地形（而不是"确认没有数据"的空洞），见 {@link #hasTerrain}。 */
	boolean hasTerrain(int index) {
		return hasTerrain[index];
	}

	int layer() {
		return layer;
	}

	/** 这些像素是按哪个精确层高画的。 */
	int pixelHeight() {
		return pixelHeight;
	}

	/**
	 * 把"这批像素是按哪个精确高度画的"改成新值。
	 *
	 * <p>只改这个记号，<b>像素原样留着</b>；要不要作废某些区块由调用方先问
	 * {@code ClientChunkColorProvider#caveSliceCovers} 决定（见 {@link WorldTilePages#refreshCaveHeight}）。
	 * 归档层（{@link #layer}）是页键的一部分，不在这里改。
	 */
	void setPixelHeight(int newPixelHeight) {
		this.pixelHeight = newPixelHeight;
	}

	/**
	 * 把这一页改挂到另一个归档档：只换键层。
	 *
	 * <p>{@link #pixelHeight}（像素是按哪个精确高度画的）<b>刻意不动</b>——紧接着的
	 * {@link WorldTilePages#refreshCaveHeight} 正是拿它当"旧高度"，去问哪些区块的结果真的变了。
	 */
	void retarget(int newLayer) {
		this.layer = newLayer;
	}

	public boolean loaded() {
		return loaded;
	}

	/** 第一次写上像素的时刻，用于"整页淡入"；0 表示还没写过。 */
	public long loadedAtNanos() {
		return loadedAtNanos;
	}

	/**
	 * 从淘汰集合里回到常驻：把显存要回来并把堆里现有的像素传上去。
	 *
	 * <p>只有一次上传，没有采样、没有着色——这就是"移出去再回来不再重新加载"的全部代价。
	 */
	void reacquire() {
		textures.reacquireGpu();
		// 整张已经重传过一遍了：脏标记与"要上屏"的许可一起清掉，别再来一次
		dirtyDay = false;
		dirtyNight = false;
		pendingUpload = false;
		uploadReady = false;
	}

	/** 只回收显存，像素留着，见 {@link TileVariants#releaseGpu()}。 */
	void releaseGpu() {
		textures.releaseGpu();
	}
}
