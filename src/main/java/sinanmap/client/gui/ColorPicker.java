package sinanmap.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;

/**
 * 取色板：上面一块"饱和度 / 明度"方块，下面一条横向色相条。
 *
 * <p>它不是 GUI 控件（不继承 {@code AbstractWidget}、也不挂到 {@code Screen} 上），与
 * {@link SinanTextField} 同一个路子：位置与开关由宿主决定，鼠标 / 按键事件由宿主显式转进来。
 * 好处是"浮层压在别的控件之上"这类层级问题完全由宿主掌握，不必和原版的焦点机制打交道。
 *
 * <p><b>为什么抽成一个类</b>：路径点颜色与叠加层网格颜色用的是同一个东西，两处各写一份必然长歪
 * （一边改了另一边不改）；抽出来之后"取色"这件事只有一个实现。
 *
 * <p><b>颜色怎么给</b>：宿主先用 {@link #setColor(int)} 把当前颜色喂进来（它会反解成 HSV，游标因此
 * 落在当前颜色上而不是角落里），之后每次拖动都由它算好新颜色，宿主用 {@link #color()} 取走。
 * 颜色一律按<b>不透明</b>处理（alpha 由宿主的语义决定，比如网格色的半透明是固定档）。
 */
public final class ColorPicker {

	/** 饱和度/明度方块的尺寸。 */
	private static final int SV_WIDTH = 122;
	private static final int SV_HEIGHT = 66;

	/** 色相条的高度，以及它与方块之间留的空隙。 */
	private static final int HUE_HEIGHT = 12;
	private static final int SV_GAP = 4;

	/** 面板内边距与外描边宽度。 */
	private static final int PANEL_PAD = 6;
	private static final int PANEL_BORDER_WIDTH = 2;

	/** 取色区边框的深色（半透明黑，压在渐变上也看得见）。 */
	private static final int PANEL_BORDER = 0x90000000;

	/** 十字游标那一条臂的半长（像素）：外接范围正好 {@code 5×5}（中心 1 格 + 每边 2 格）。 */
	private static final int MARKER_ARM = 2;

	/** 十字本体：纯白实心。逐格上色，每格只涂一次。 */
	private static final int MARKER_BODY = 0xFFFFFFFF;

	/** 十字描边：半透明深色，最多 1 像素，且只落在本体之外的相邻格上。 */
	private static final int MARKER_OUTLINE = 0x80101014;

	/** 拖动目标：0 没拖、{@link #DRAG_SV} 饱和度/明度方块、{@link #DRAG_HUE} 色相条。 */
	public static final int DRAG_NONE = 0;
	public static final int DRAG_SV = 1;
	public static final int DRAG_HUE = 2;

	private boolean open;
	private int drag = DRAG_NONE;

	private float hue;
	private float saturation = 1.0F;
	private float brightness = 1.0F;
	private int color = 0xFFFFFFFF;

	private int left;
	private int top;

	/** 面板宽度（不含描边）。 */
	public int width() {
		return PANEL_PAD * 2 + SV_WIDTH;
	}

	/** 面板高度（不含描边）。 */
	public int height() {
		return PANEL_PAD * 2 + SV_HEIGHT + SV_GAP + HUE_HEIGHT;
	}

	/** 摆到屏幕上的哪个位置（宿主每帧按布局算好；只影响绘制与命中，不影响颜色）。 */
	public void setPosition(int left, int top) {
		this.left = left;
		this.top = top;
	}

	public boolean isOpen() {
		return open;
	}

	public void open() {
		open = true;
	}

	public void close() {
		open = false;
		drag = DRAG_NONE;
	}

	public int dragKind() {
		return drag;
	}

	/** 当前颜色（不透明 ARGB）。 */
	public int color() {
		return color;
	}

	/**
	 * 换成这个颜色：记下来并<b>反解成 HSV</b>，游标因此落在当前颜色上。
	 *
	 * <p>不透明处理：alpha 忽略（见类注释）。
	 */
	public void setColor(int argb) {
		this.color = 0xFF000000 | argb & 0xFFFFFF;
		int red = color >> 16 & 0xFF;
		int green = color >> 8 & 0xFF;
		int blue = color & 0xFF;
		int max = Math.max(red, Math.max(green, blue));
		int min = Math.min(red, Math.min(green, blue));
		int range = max - min;
		brightness = max / 255.0F;
		saturation = max == 0 ? 0.0F : range / (float) max;
		if (range == 0) {
			hue = 0.0F;
			return;
		}
		float r = (max - red) / (float) range;
		float g = (max - green) / (float) range;
		float b = (max - blue) / (float) range;
		if (red == max) {
			hue = b - g;
		} else if (green == max) {
			hue = 2.0F + r - b;
		} else {
			hue = 4.0F + g - r;
		}
		hue /= 6.0F;
		if (hue < 0.0F) {
			hue += 1.0F;
		}
	}

	/** 光标（含描边）是不是落在面板上。 */
	public boolean contains(double mouseX, double mouseY) {
		return open && inside(mouseX, mouseY, left - PANEL_BORDER_WIDTH, top - PANEL_BORDER_WIDTH,
				width() + PANEL_BORDER_WIDTH * 2, height() + PANEL_BORDER_WIDTH * 2);
	}

	/**
	 * 光标该长什么样：在方块里是"十字"（两个方向都能拖）、在色相条上是"左右"（只有一维）。
	 *
	 * @return {@link #DRAG_SV} / {@link #DRAG_HUE}；都不在就返回 {@link #DRAG_NONE}
	 */
	public int hoverKind(double mouseX, double mouseY) {
		if (!open) {
			return DRAG_NONE;
		}
		if (inside(mouseX, mouseY, svLeft(), svTop(), SV_WIDTH, SV_HEIGHT)) {
			return DRAG_SV;
		}
		return inside(mouseX, mouseY, svLeft(), hueTop(), SV_WIDTH, HUE_HEIGHT) ? DRAG_HUE : DRAG_NONE;
	}

	/**
	 * 在面板上按下：记下拖的是哪一块并立刻取色。
	 *
	 * <p>只有落在两个取色区（方块 / 色相条）上才算"吃掉了这一下"；点在面板的内边距那圈也返回
	 * {@code false}，由宿主当作"点在外面"处理——两边宿主都借这一下收起取色板（与路径点界面一致）。
	 */
	public boolean mouseClicked(double mouseX, double mouseY) {
		if (!open) {
			return false;
		}
		if (inside(mouseX, mouseY, svLeft(), svTop(), SV_WIDTH, SV_HEIGHT)) {
			drag = DRAG_SV;
			pickFromSv(mouseX, mouseY);
			return true;
		}
		if (inside(mouseX, mouseY, svLeft(), hueTop(), SV_WIDTH, HUE_HEIGHT)) {
			drag = DRAG_HUE;
			pickFromHue(mouseX);
			return true;
		}
		return false;
	}

	/** 拖动取色（宿主把每一次鼠标移动都转进来）。 */
	public void mouseDragged(double mouseX, double mouseY) {
		if (drag == DRAG_SV) {
			pickFromSv(mouseX, mouseY);
		} else if (drag == DRAG_HUE) {
			pickFromHue(mouseX);
		}
	}

	public void mouseReleased() {
		drag = DRAG_NONE;
	}

	/** 画整块取色板：底板 + 方块 + 色相条 + 两个游标。 */
	public void render(GuiGraphicsExtractor graphics) {
		if (!open) {
			return;
		}
		graphics.fill(left - PANEL_BORDER_WIDTH, top - PANEL_BORDER_WIDTH,
				left + width() + PANEL_BORDER_WIDTH, top + height() + PANEL_BORDER_WIDTH,
				0xF0101014);
		// 高光那条线画在<b>底边</b>（下边白线，与输入框统一）
		graphics.fill(left - PANEL_BORDER_WIDTH, top + height() + PANEL_BORDER_WIDTH - 1,
				left + width() + PANEL_BORDER_WIDTH, top + height() + PANEL_BORDER_WIDTH,
				0x60FFFFFF);

		int svLeft = svLeft();
		int svTop = svTop();
		drawSvSquare(graphics, svLeft, svTop);
		drawHueBar(graphics, svLeft, hueTop());
		// 两个游标：方块里一个十字、色相条上一根小竖条（色相只有一维，用十字是误导）
		marker(graphics, svLeft + Math.round(saturation * (SV_WIDTH - 1)),
				svTop + Math.round((1.0F - brightness) * (SV_HEIGHT - 1)));
		hueMarker(graphics, svLeft + Math.round(hue * (SV_WIDTH - 1)), hueTop());
	}

	private int svLeft() {
		return left + PANEL_PAD;
	}

	private int svTop() {
		return top + PANEL_PAD;
	}

	/** 横向色相条的位置：就在方块正下方。 */
	private int hueTop() {
		return svTop() + SV_HEIGHT + SV_GAP;
	}

	/**
	 * 饱和度 / 明度方块：横轴是饱和度、纵轴是明度，色相由色相条给定。
	 *
	 * <p>横向渐变没有现成接口（原版 {@code fillGradient} 只做竖向），所以按列画：一列一个竖向渐变
	 * （上全亮、下全黑），列间饱和度不同。只在取色板打开时画，代价可以接受。
	 */
	private void drawSvSquare(GuiGraphicsExtractor graphics, int left, int top) {
		float hue = effectiveHue();
		for (int x = 0; x < SV_WIDTH; x++) {
			float sat = x / (float) (SV_WIDTH - 1);
			graphics.fillGradient(left + x, top, left + x + 1, top + SV_HEIGHT,
					hsvAt(hue, sat, 1.0F), hsvAt(hue, sat, 0.0F));
		}
		frame(graphics, left, top, SV_WIDTH, SV_HEIGHT);
	}

	/**
	 * 参与换算的色相：把 {@code 1.0} 折回 {@code 0}。
	 *
	 * <p>色相是个<b>环</b>，{@code 1.0} 与 {@code 0.0} 是同一种红。但 {@code Mth.hsvToArgb} 先取整
	 * {@code hue * 6} 再 {@code % 6}：{@code hue = 1.0} 时整数部分归 0、小数部分却是 {@code 6.0}，
	 * 算出来偏黄（色相条最右端那块黄、方块拖到最右时也偏黄）。所有换算都走这一个入口。
	 */
	private float effectiveHue() {
		return hue >= 1.0F ? 0.0F : hue;
	}

	/**
	 * 色相条：横向铺开，右边回到红色。一维色相<b>横着放</b>（竖着摆既挤、又容易被当成第二个方块去拖）；
	 * 同样按列画，每列一个饱和度/明度拉满的纯色相。
	 */
	private void drawHueBar(GuiGraphicsExtractor graphics, int left, int top) {
		for (int x = 0; x < SV_WIDTH; x++) {
			graphics.fill(left + x, top, left + x + 1, top + HUE_HEIGHT,
					hueColor(x / (float) (SV_WIDTH - 1)));
		}
		frame(graphics, left, top, SV_WIDTH, HUE_HEIGHT);
	}

	/**
	 * 色相（0..1）→ 饱和度/明度都拉满的纯色。
	 *
	 * <p>色相是个<b>环</b>，{@code 1.0} 与 {@code 0.0} 是同一种红。但 {@code Mth.hsvToArgb} 先取整
	 * {@code hue * 6} 再 {@code % 6}：{@code hue = 1.0} 时整数部分归 0、小数部分却是 {@code 6.0}，
	 * 算出来偏黄（色相条最右端那块黄）。这里把 {@code 1.0} 折回 {@code 0}，色带才红→…→红平滑闭合。
	 */
	private static int hueColor(float hue) {
		return hsvAt(hue >= 1.0F ? 0.0F : hue, 1.0F, 1.0F);
	}

	/** 给一块取色区描一圈深色边：压在任何底色上都能看清边界。 */
	private static void frame(GuiGraphicsExtractor graphics, int left, int top, int width, int height) {
		graphics.fill(left - 1, top - 1, left + width + 1, top, PANEL_BORDER);
		graphics.fill(left - 1, top + height, left + width + 1, top + height + 1, PANEL_BORDER);
		graphics.fill(left - 1, top, left, top + height, PANEL_BORDER);
		graphics.fill(left + width, top, left + width + 1, top + height, PANEL_BORDER);
	}

	/**
	 * 饱和度/明度方块里的"十"字游标。
	 *
	 * <p><b>以 (x, y) 为正中心</b>：横臂与竖臂各 1 像素宽、半长 {@link #MARKER_ARM} 格，加中心一格，
	 * 本体外接正好 {@code 5×5}。
	 *
	 * <p>画法不是矩形拼贴，而是<b>先把整块像素算成网格、再逐格上色</b>：取 {@code (2r+1)×(2r+1)} 网格
	 * （{@code r = ARM + 1}，多留一圈给描边），本体标进 {@code body}，本体格的上下左右邻格中
	 * <b>不属于本体</b>的标进 {@code outline}（集合，多次标只算一次），最后每格只调一次 {@code fill}。
	 * 这样"任何像素只被涂一次"是结构上保证的，也就不会出现深浅不一的黑色杂点。
	 */
	private static void marker(GuiGraphicsExtractor graphics, int x, int y) {
		int arm = MARKER_ARM;
		int radius = arm + 1;
		int size = radius * 2 + 1;
		boolean[] body = new boolean[size * size];
		boolean[] outline = new boolean[size * size];
		for (int dx = -arm; dx <= arm; dx++) {
			body[markerIndex(dx, 0, radius)] = true;
		}
		for (int dy = -arm; dy <= arm; dy++) {
			body[markerIndex(0, dy, radius)] = true;
		}
		for (int dx = -arm; dx <= arm; dx++) {
			for (int dy = -arm; dy <= arm; dy++) {
				if (!body[markerIndex(dx, dy, radius)]) {
					continue;
				}
				markOutline(outline, body, dx + 1, dy, radius);
				markOutline(outline, body, dx - 1, dy, radius);
				markOutline(outline, body, dx, dy + 1, radius);
				markOutline(outline, body, dx, dy - 1, radius);
			}
		}
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dy = -radius; dy <= radius; dy++) {
				int index = markerIndex(dx, dy, radius);
				if (body[index]) {
					graphics.fill(x + dx, y + dy, x + dx + 1, y + dy + 1, MARKER_BODY);
				} else if (outline[index]) {
					graphics.fill(x + dx, y + dy, x + dx + 1, y + dy + 1, MARKER_OUTLINE);
				}
			}
		}
	}

	/** 网格下标：把以中心为原点的偏移 (dx, dy) 映射进 {@code (2r+1)×(2r+1)} 的一维数组。 */
	private static int markerIndex(int dx, int dy, int radius) {
		int size = radius * 2 + 1;
		return (dx + radius) * size + (dy + radius);
	}

	/** 把 (dx, dy) 标成描边——除非那一格是本体（本体不描边）。 */
	private static void markOutline(boolean[] outline, boolean[] body, int dx, int dy, int radius) {
		int index = markerIndex(dx, dy, radius);
		if (!body[index]) {
			outline[index] = true;
		}
	}

	/** 横向色相条上的游标：一根小竖条 + 深色描边（同样是"线"而不是"块"）。 */
	private static void hueMarker(GuiGraphicsExtractor graphics, int x, int top) {
		graphics.fill(x - 1, top, x + 2, top + HUE_HEIGHT, PANEL_BORDER);
		graphics.fill(x, top, x + 1, top + HUE_HEIGHT, 0xFFFFFFFF);
	}

	/** 方块上的一点 → 饱和度 / 明度。 */
	private void pickFromSv(double mouseX, double mouseY) {
		saturation = (float) Math.clamp((mouseX - svLeft()) / (SV_WIDTH - 1.0), 0.0, 1.0);
		brightness = (float) Math.clamp(1.0 - (mouseY - svTop()) / (SV_HEIGHT - 1.0), 0.0, 1.0);
		applyColor();
	}

	/** 色相条上的 x → 色相。 */
	private void pickFromHue(double mouseX) {
		hue = (float) Math.clamp((mouseX - svLeft()) / (SV_WIDTH - 1.0), 0.0, 1.0);
		applyColor();
	}

	/** 由 HSV 算出颜色。 */
	private void applyColor() {
		// 与 hueColor 同一个道理：游标拖到最右端时 hue 恰好是 1.0，折回 0 才不会算出黄色
		color = hsvAt(hue >= 1.0F ? 0.0F : hue, saturation, brightness);
	}

	/** HSV → 不透明颜色（用原版的换算，省得自己写一遍分段的 RGB 公式）。 */
	private static int hsvAt(float hue, float saturation, float brightness) {
		return Mth.hsvToArgb(hue, saturation, brightness, 255);
	}

	private static boolean inside(double mouseX, double mouseY, int x, int y, int w, int h) {
		return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
	}
}
