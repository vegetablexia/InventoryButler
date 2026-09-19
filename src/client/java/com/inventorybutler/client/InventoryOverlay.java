package com.inventorybutler.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 界面上的绘制：收藏星标 + 归位幽灵 + 垃圾桶按钮。
 *
 * <p><b>垃圾桶按钮的位置和风格</b>：统一放在 <b>GUI 右下角、快捷栏（最后一行物品栏）
 * 的右下角</b> —— 右缘包边与 GUI 右缘包边占同样的三列（竖着看是一条连续的线），顶部
 * 上移 3px 盖掉 GUI 底边框的暗色段（横着看两块面板灰连成一片）。所有非创造模式的容器
 * 界面共用这一个位置，不再跟着配方书按钮（绿书）跑：绿书各界面位置不同、配方书展开时
 * 还会盖住旁边的东西，而右下角这块空地在任何界面上都是空的。</p>
 *
 * <p><b>风格是「面板上鼓出来的一格」</b>：32x27 的面板灰口袋，正中嵌一个 18x18 的内凹
 * 槽位（1px 边框 + 16x16 内芯，与原版槽位网格一格完全一致），深色上/左缘、亮色下/右缘，
 * 和原版物品栏的格子「凹进去」的观感同源。口袋的右缘与底缘走原版面板的层次
 * （面板灰 → 暗灰 ×2 → 黑 ×1），<b>顶部不画边</b> —— 那一侧正是与面板接驳的地方，
 * 画边就等于给连体处描一条缝。</p>
 *
 * <p><b>四种状态</b>：空置（桶图标）/ 悬停（内凹暗面提亮一档 + 内芯铺白）/ 有内容
 * （直接画物品，可点取回）/ <b>危险</b>（手上拿着收藏物、服务端会拒收 —— 内芯换红灰
 * 再加一道深色叉）。危险态的判定口径见 {@code AbstractContainerScreenMixin} 的
 * {@code inventorybutler$carriedIsProtected}。</p>
 *
 * <p><b>坐标系</b>：{@code AbstractContainerScreen.extractContents} 在调用
 * {@code extractSlots} 之前已经 {@code pose().translate(leftPos, topPos)} 过了，
 * 所以在 {@code extractSlot} / {@code extractSlots} 里拿到的坐标原点就是 GUI 的左上角，
 * 直接使用 {@code slot.x} / {@code slot.y} 即可。命名上把这个空间叫「GUI 空间」。</p>
 */
public final class InventoryOverlay {
	/**
	 * 按钮整体尺寸（含包边）：32x27。
	 *
	 * <p>结构：18x18 槽位嵌在面板灰口袋里 —— 左侧「黑 1 + 灰 6」，右侧「灰 4 + 暗灰 2 +
	 * 黑 1」，底部「灰 3 + 暗灰 2 + 黑 1」，顶部只垫 3px 面板灰（与 GUI 底边框接驳，
	 * 不画边）。右侧那 2px 暗灰 + 1px 黑与 GUI 右缘包边<b>用的是同样的三列</b>，
	 * 竖着看是一条连续的线。两个底角黑线 2px 阶梯过渡，角外不留灰。</p>
	 */
	public static final int BUTTON_WIDTH = 32;
	public static final int BUTTON_HEIGHT = 27;

	/** 内嵌槽位的尺寸：与原版槽位网格一格完全一致（1px 边框 + 16x16 内芯）。 */
	private static final int SLOT_SIZE = 18;

	/**
	 * 槽位在按钮内的偏移：左边让出包边（黑 1 + 灰 6），上面让出接驳垫层。
	 *
	 * <p>{@code SLOT_OFFSET_X} 取 <b>7</b> 而不是 6，是为了让槽芯左缘落在
	 * {@code imageWidth - 24} 上 —— 快捷栏第 9 格的 x 同样是 {@code imageWidth - 24}
	 * （快捷栏恒为 {@code x = 8 + 18k, k = 0..8}，故 k=8 → 152；176 - 24 = 152），
	 * 于是桶格与正上方那一格严格同列。取 6 会错开 1px，两个 16x16 的方块上下不同列，
	 * 放大后看着像渲染故障。</p>
	 */
	private static final int SLOT_OFFSET_X = 7;
	private static final int SLOT_OFFSET_Y = 3;

	/** 面板包边的三种颜色：面板灰、暗灰、黑 —— 均取自原版容器贴图的调色板。 */
	private static final int PANEL_GRAY = 0xFFC6C6C6;
	private static final int PANEL_DARK = 0xFF555555;
	private static final int PANEL_BLACK = 0xFF000000;

	/**
	 * 按钮上缘相对 GUI 底边的偏移：<b>-3 —— 按钮上移 3px，顶部垫层直接盖掉 GUI
	 * 底边框的那截「阴影」（暗灰×2 + 黑×1）</b>。
	 *
	 * <p>盖掉之后，GUI 内部的面板灰和按钮口袋的面板灰之间没有任何暗色隔断，
	 * 两块灰色连成一片 —— 看起来口袋就是面板本体向下鼓出来的一块；
	 * 原版底边框的暗线在口袋两侧照常走，流到口袋边缘就「拐进」了
	 * 按钮自己的边框（左缘黑线正好从黑线所在的那一行起笔）。</p>
	 */
	private static final int BUTTON_MARGIN_BOTTOM = -3;

	/** 按钮图标的颜色。 */
	private static final int ICON_MAIN = 0xFF3F3F3F;
	/** 桶身上的透气缝：用白色掏空，靠明暗差把「三格桶身」讲清楚。 */
	private static final int ICON_LIGHT = 0xFFFFFFFF;

	/** 内凹边框的三种颜色：内芯（容器灰）、上/左缘（深）、下/右缘（亮）。 */
	private static final int SLOT_BG = 0xFF8B8B8B;
	private static final int SLOT_DARK = 0xFF373737;
	private static final int SLOT_LIGHT = 0xFFFFFFFF;
	/** 悬停高亮：往内芯铺一层半透明白（原版悬停高亮的做法）。 */
	private static final int SLOT_HIGHLIGHT = 0x80FFFFFF;

	/**
	 * 悬停时把内凹的暗面提亮一档（{@code #373737 → #555555}）。
	 *
	 * <p>悬停反馈落在「凹槽」这个识别特征上：凹槽变浅，看着就像这格张开了嘴、等着接你
	 * 手上的东西。比单纯叠一层白更贴原版语言 —— 原版按钮的悬停也是靠描边换色，
	 * 而不是往上盖色块。</p>
	 */
	private static final int SLOT_BEVEL_HOVER = 0xFF555555;

	/**
	 * 危险态：手上拿着的物品会被服务端拒收（收藏物）。
	 *
	 * <p>槽芯换成 {@code #AB7F7F} 红灰 + 一道 {@code #1F1F1F} 的叉。两个色值都取自
	 * TrashSlot 的 {@code trashslot_danger.png}。红灰的明度与常态槽芯 {@code #8B8B8B}
	 * <b>刻意保持一致</b>（55 对 55）—— 危险态只做色相变化、不动明暗结构，于是
	 * 「凹进去」的层次在任何状态下都不变，红色只管说「这个动作被禁止」。</p>
	 */
	private static final int DANGER_BG = 0xFFAB7F7F;
	private static final int DANGER_MARK = 0xFF1F1F1F;

	/**
	 * 收藏星标：经典实心五角星点阵（7 列 6 行），每行一个 7 位掩码。
	 *
	 * <p>形状是像素画里最通用的那颗「收藏星」：上 1 尖、两腰展开、底部开叉出两条腿 ——
	 * 中间那一小块缺口（row4 的 {@code .##.##.}）就是两个底角之间的凹槽，
	 * 缺了它就成了水滴，有了它才认得出是星星。</p>
	 */
	private static final int[] STAR_ROWS = {
			0b0001000,
			0b0011100,
			0b1111111,
			0b0111110,
			0b0110110,
			0b1100011,
	};

	private static final int STAR_COLOR = 0xFFFFD64A;
	private static final int STAR_SHADOW = 0xC0201400;

	/**
	 * 归位幽灵的「洗淡」层：原版槽位灰 + alpha。
	 *
	 * <p>比上一版（0xA8）调淡了一档 —— 深色物品在被洗之后糊成一团是老问题，
	 * 少盖一点灰，图标本体能透出来更多；「这是个占位幽灵」的意思改由
	 * 四角的白色角标来承担（见 {@link #drawGhostItem}）。</p>
	 */
	private static final int GHOST_FADE = 0x908B8B8B;

	/** 归位幽灵四角的角标颜色：半透明白，比整格高亮克制，但一眼可辨。 */
	private static final int GHOST_CORNER = 0xB4FFFFFF;

	private InventoryOverlay() {
	}

	// ------------------------------------------------------------------
	// 按钮的位置（GUI 空间）
	// ------------------------------------------------------------------

	/**
	 * 按钮 x：右缘包边的黑色外线正好落在 GUI 右缘黑线所在的那一列。
	 *
	 * <p>右侧包边 6px（灰3 + 暗2 + 黑1）占据 {@code imageWidth - 6} 起的那几列 ——
	 * 暗灰/黑两段和 GUI 自带的右缘包边<b>同列对齐</b>，竖着看就是物品栏右缘的
	 * 包边一路延续到了按钮上，严丝合缝。</p>
	 */
	public static int buttonX(int imageWidth) {
		return imageWidth - BUTTON_WIDTH;
	}

	/**
	 * 按钮 y：上移 3px，顶部垫层盖住 GUI 底边框的暗色线（详见
	 * {@link #BUTTON_MARGIN_BOTTOM}）。
	 *
	 * <p>按钮上部 3px 压在原版 GUI 的绘制范围之内（覆盖其底边框暗线），
	 * 其余主体仍在 {@code imageHeight} 之外 —— 对原版来说那属于「界面外」，
	 * 点击的松开事件必须由 {@code AbstractContainerScreenMixin} 拦截，
	 * 否则光标上拿着的物品会被原版当作「点到界面外」丢到地上。</p>
	 */
	public static int buttonY(int imageHeight) {
		return imageHeight + BUTTON_MARGIN_BOTTOM;
	}

	// ------------------------------------------------------------------
	// 绘制
	// ------------------------------------------------------------------

	/**
	 * 在槽位<b>内部</b>左上角画一个金色星标。
	 *
	 * <p>上一版从 {@code slot.x-1, slot.y-1} 起画、压在槽位边框上；现在整体收进
	 * 槽位内 —— 星标盖到的是物品图标的左上角像素，而不是 GUI 的格子线，
	 * 槽位网格保持完整。数量数字在右下角，互不打架。</p>
	 */
	public static void drawFavoriteBadge(GuiGraphicsExtractor g, Slot slot) {
		int x = slot.x;
		int y = slot.y;
		// 先画一层偏移的暗色，保证在任何亮度的物品上都看得清
		drawStar(g, x + 1, y + 1, STAR_SHADOW);
		drawStar(g, x, y, STAR_COLOR);
	}

	/**
	 * 画「归位幽灵」—— 预留位空着时用半透明图标提示这一格是留给谁的。
	 *
	 * <p><b>半透明是怎么做出来的</b>：26.2 的 {@link GuiGraphicsExtractor} 没有 alpha /
	 * 着色接口，连 {@code item(ItemStack, int, int)} 都没有颜色参数（只有个是旋转用的种子），
	 * 所以直接按 alpha 画物品这条路走不通。</p>
	 *
	 * <p>思路沿用「先按原样画图标、再盖带透明度的槽位灰」，两处改进：</p>
	 * <ul>
	 *   <li>盖的灰从 0xA8 调淡到 0x90 —— 深色物品不再糊成一团；</li>
	 *   <li>四角加 2px 的白色<b>角标</b>（L 形），把「这格是被标记的预留位」和
	 *       「只是个空格子」明确区分开 —— 角标是标记语言，不依赖图标本身的辨识度。</li>
	 * </ul>
	 *
	 * <p>只画图标不画 {@code itemDecorations}，所以数量、耐久条这些都不会出现 ——
	 * 它只是「占位提示」，不是真的物品。</p>
	 */
	public static void drawGhostItem(GuiGraphicsExtractor g, Slot slot, ItemStack stack) {
		int x = slot.x;
		int y = slot.y;
		g.item(stack, x, y);
		g.fill(x, y, x + 16, y + 16, GHOST_FADE);
		drawCornerMarks(g, x, y);
	}

	/** 四个 2px 长的 L 形角标（上下每条 2 段 fill，共 8 次调用，每帧开销可忽略）。 */
	private static void drawCornerMarks(GuiGraphicsExtractor g, int x, int y) {
		// 左上
		g.fill(x, y, x + 2, y + 1, GHOST_CORNER);
		g.fill(x, y, x + 1, y + 2, GHOST_CORNER);
		// 右上
		g.fill(x + 14, y, x + 16, y + 1, GHOST_CORNER);
		g.fill(x + 15, y, x + 16, y + 2, GHOST_CORNER);
		// 左下
		g.fill(x, y + 14, x + 2, y + 16, GHOST_CORNER);
		g.fill(x, y + 15, x + 1, y + 16, GHOST_CORNER);
		// 右下
		g.fill(x + 14, y + 15, x + 16, y + 16, GHOST_CORNER);
		g.fill(x + 15, y + 14, x + 16, y + 16, GHOST_CORNER);
	}

	/**
	 * 画垃圾桶按钮：先铺一圈「面板包边」（左右下三侧 + 顶部面板灰垫层），
	 * 再画内嵌的 18x18 内凹槽位。
	 *
	 * <p>包边逐层照抄原版容器 GUI 面板边缘：面板灰 ×3 → 暗灰 ×2 → 黑 ×1；
	 * 顶部不画暗色，用面板灰垫层直接接住 GUI 自带的底边框（详见
	 * {@link #drawPanelBezel}）。</p>
	 *
	 * <p>槽位边框是<b>内凹</b>样式：深色的上/左缘 + 亮色的下/右缘 + 容器灰内芯，
	 * 和原版槽位一样是「凹进去」的观感；悬停时内凹暗面提亮一档、再往内芯铺一层半透明白。</p>
	 *
	 * <p>三种内容，按优先级：<b>危险</b>（{@code blocked}，红灰槽芯 + 深色叉）→
	 * <b>有物品</b>（直接把那个物品画在 16x16 内芯里，一眼就能看出「取回」能拿回什么）→
	 * <b>空</b>（带盖的垃圾桶图标）。</p>
	 */
	public static void drawTrashButton(GuiGraphicsExtractor g, int x, int y, boolean hovered, boolean blocked) {
		drawPanelBezel(g, x, y);
		int sx = x + SLOT_OFFSET_X;
		int sy = y + SLOT_OFFSET_Y;
		// 被拒收时不做「接收反馈」：内凹面提亮和铺白的意思都是「这格收得下你手上的东西」，
		// 危险态下那样画会说反话 —— 红色加叉自己就把话讲完了。
		drawInsetFrame(g, sx, sy, hovered && !blocked);
		if (blocked) {
			drawDangerMark(g, sx, sy);
			return;
		}
		if (!ClientTrashState.isEmpty()) {
			g.item(ClientTrashState.get(), sx + 1, sy + 1);
			return;
		}
		drawTrashIcon(g, sx, sy);
	}

	/**
	 * 面板包边：照目标截图逐像素复刻的结构。
	 *
	 * <ul>
	 *   <li><b>左缘</b>：只有 1px 黑色勾边，<b>不画暗灰</b> —— 暗灰竖带悬空在面板
	 *       「内部」看起来就像投影（从第 2 行起笔，那一行正好是 GUI 底边黑线所在行，
	 *       黑线流到口袋左缘正好拐进这条竖线）；</li>
	 *   <li><b>底部</b>：暗灰 ×2 + 黑 ×1（原版面板底缘的层次）；</li>
	 *   <li><b>右缘</b>：暗灰 ×2 + 黑 ×1，与 GUI 右缘包边同列，竖着看无缝延续；</li>
	 *   <li><b>顶部</b>：只垫面板灰，盖掉 GUI 底边框在口袋这一段的暗线；</li>
	 *   <li><b>两个底角</b>：黑线圆弧过渡 —— 倒数第 2 行黑线左右各收 1 格、
	 *       最后 1 行收 2 格，<b>角外不留灰</b>（转角外侧是透明的）；底部暗灰带
	 *       两端斜切收头，右角暗灰向上加宽一格（原版角部的收法）。</li>
	 * </ul>
	 */
	private static void drawPanelBezel(GuiGraphicsExtractor g, int x, int y) {
		int w = BUTTON_WIDTH;
		int h = BUTTON_HEIGHT;
		// 面板灰主体 —— 底部两角按圆角逐行收进（角外不留灰）
		g.fill(x, y, x + w, y + h - 2, PANEL_GRAY);                 // 主体到倒数第 3 行
		g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, PANEL_GRAY); // 倒数第 2 行，左右各收 1 格
		g.fill(x + 2, y + h - 1, x + w - 2, y + h, PANEL_GRAY);     // 最后 1 行，左右各收 2 格
		// 暗灰 ×2：右缘竖带（与 GUI 右缘包边同列，通到顶）+ 底部横带
		g.fill(x + w - 3, y, x + w - 1, y + h - 2, PANEL_DARK);
		g.fill(x + 2, y + h - 3, x + w - 2, y + h - 1, PANEL_DARK);
		// 底部暗灰带的右端斜切收头（原版角部：暗灰斜着收进面板灰）
		g.fill(x + w - 4, y + h - 4, x + w - 3, y + h - 3, PANEL_DARK);   // 右角向上加宽一格
		// 黑 ×1：右缘竖线（通到顶）+ 底部横线 + 左缘竖线（从第 2 行起笔）
		g.fill(x + w - 1, y, x + w, y + h - 1, PANEL_BLACK);
		g.fill(x + 2, y + h - 1, x + w - 2, y + h, PANEL_BLACK);
		g.fill(x, y + 2, x + 1, y + h - 2, PANEL_BLACK);
		// 圆角：黑线拐进底部横线的两个过渡格
		g.fill(x + 1, y + h - 2, x + 2, y + h - 1, PANEL_BLACK);
		g.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, PANEL_BLACK);
	}

	/**
	 * 内凹边框：先铺一层深色（自然形成上/左缘），再画内芯，
	 * 最后用亮色勾出下/右缘 —— 光源在左上时，凹进去的面就是「上暗下亮」，
	 * 和凸起按钮（上亮下暗）正好相反，一眼就能看出是往里凹的。
	 *
	 * <p>画法与原版物品格的观感逐像素对应：18x18 = 1px 边框 + 16x16 内芯。
	 * 悬停时上/左缘从 {@code #373737} 提到 {@link #SLOT_BEVEL_HOVER}，再往内芯铺一层
	 * 半透明白 —— 提亮是直接换底色选出来的，<b>不额外多一次 fill</b>。</p>
	 */
	private static void drawInsetFrame(GuiGraphicsExtractor g, int x, int y, boolean hovered) {
		int s = SLOT_SIZE;
		g.fill(x, y, x + s, y + s, hovered ? SLOT_BEVEL_HOVER : SLOT_DARK); // 上/左缘 + 打底
		g.fill(x + 1, y + 1, x + s - 1, y + s - 1, SLOT_BG);     // 内芯
		g.fill(x + 1, y + s - 1, x + s - 1, y + s, SLOT_LIGHT);  // 下缘
		g.fill(x + s - 1, y + 1, x + s, y + s - 1, SLOT_LIGHT);  // 右缘
		if (hovered) {
			g.fill(x + 1, y + 1, x + s - 1, y + s - 1, SLOT_HIGHLIGHT);
		}
	}

	/**
	 * 危险标记：把槽芯整格换成红灰，再盖一道 2px 粗的深色叉。
	 *
	 * <p>和 {@link #drawInsetFrame} 分开画、而不是把「槽芯色」做成参数，是为了不让
	 * 「槽芯色」和「状态」互相污染：内凹边框永远只用原版槽位那三个色，危险态只管
	 * 自己往上盖一层。代价是多 1 次 fill（底色），且只在危险态发生。</p>
	 *
	 * <p>叉的形状与 TrashSlot 的 {@code trashslot_danger.png} <b>逐像素一致</b>
	 * （已按该贴图的像素图核对）：11 行（{@code wy+2 … wy+12}）× 12 列
	 * （{@code wx+2 … wx+13}），左右各留 2px、上与下各留 2px / 3px。
	 * 两条腿各 2px 宽 —— 1px 的斜线在 16×16 里会被边框和物品图标的对比吃掉，
	 * 根本看不出是个叉；斜线用 11 段 2×1 的横条上下错位 1 格拼出来，
	 * 到第 6 段两条腿在正中并成 2px 宽的 {@code ####} 交叉点。</p>
	 */
	private static void drawDangerMark(GuiGraphicsExtractor g, int sx, int sy) {
		int wx = sx + 1;
		int wy = sy + 1;
		g.fill(wx, wy, wx + 16, wy + 16, DANGER_BG);
		for (int i = 0; i < 11; i++) {
			g.fill(wx + 2 + i, wy + 2 + i, wx + 4 + i, wy + 3 + i, DANGER_MARK);    // ↘
			g.fill(wx + 12 - i, wy + 2 + i, wx + 14 - i, wy + 3 + i, DANGER_MARK);  // ↗
		}
	}

	/**
	 * 画星标 —— 按行合并成 run 再 {@code fill}。
	 *
	 * <p>一次 {@code fill} 就是一个 quad；逐像素画的话这颗星两层要发 80 多次调用，
	 * 合并之后每层最多 7 次。星标是每个收藏格<b>每帧</b>都要画的，省的就是这个。</p>
	 */
	private static void drawStar(GuiGraphicsExtractor g, int x, int y, int color) {
		for (int row = 0; row < STAR_ROWS.length; row++) {
			int bits = STAR_ROWS[row];
			int col = 0;
			while (col < 7) {
				if ((bits & (1 << (6 - col))) == 0) {
					col++;
					continue;
				}
				int end = col;
				while (end < 7 && (bits & (1 << (6 - end))) != 0) {
					end++;
				}
				g.fill(x + col, y + row, x + end, y + row + 1, color);
				col = end;
			}
		}
	}

	/**
	 * 在 18x18 的槽位里手搓一个 12x12 的垃圾桶图标（居中，即左上角 +3,+3）。
	 *
	 * <p>结构：提手（3px，居中）→ 盖子（9px，比桶身每边宽 1px）→ 空 1px 的缝 →
	 * 桶身（7px 宽、8px 高，最后一行收窄出梯形感）。桶身掏<b>两条 1px 的白色竖缝</b>
	 * 当透气孔 —— 两条缝之间必须留深灰，要是缝挨着缝，画出来的就不是「三格桶身」
	 * 而是一整块白斑。</p>
	 *
	 * <p>这个造型同时是 {@code CursorIcons} 里垃圾桶指针的蓝本 ——
	 * 按钮与指针共用同一套「提手 → 盖 → 缝 → 梯形桶身」的识别特征。</p>
	 */
	private static void drawTrashIcon(GuiGraphicsExtractor g, int ox, int oy) {
		int x = ox + 3;
		int y = oy + 3;

		g.fill(x + 5, y, x + 8, y + 1, ICON_MAIN);        // 提手（列 5..7）
		g.fill(x + 2, y + 1, x + 11, y + 2, ICON_MAIN);   // 盖子（列 2..10）
		g.fill(x + 3, y + 3, x + 10, y + 11, ICON_MAIN);  // 桶身（列 3..9，行 3..10）
		g.fill(x + 4, y + 11, x + 9, y + 12, ICON_MAIN);  // 桶底收窄一行（列 4..8）
		g.fill(x + 4, y + 4, x + 5, y + 10, ICON_LIGHT);  // 透气缝 1（列 4）
		g.fill(x + 8, y + 4, x + 9, y + 10, ICON_LIGHT);  // 透气缝 2（列 8）
	}

	/**
	 * 鼠标停在垃圾桶按钮上时给出原版风格的 tooltip。
	 *
	 * <p>危险态的文案优先于「空 / 取回」—— 手上拿着收藏物时，按下去也只会收到服务端
	 * 一条「已被收藏」，不如悬停时先把话说清楚。用词与服务端回的那条提示
	 * （{@code inventorybutler.message.favorite.protected}）刻意区分开：这里讲的是
	 * 「这个动作被禁止」，那条讲的是「刚才那次操作被挡下了」。</p>
	 */
	public static void addTrashTooltip(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean blocked) {
		Component text;
		if (blocked) {
			text = Component.translatable("inventorybutler.tooltip.trash.blocked");
		} else if (ClientTrashState.isEmpty()) {
			text = Component.translatable("inventorybutler.tooltip.trash.empty");
		} else {
			text = Component.translatable("inventorybutler.tooltip.trash.reclaim");
		}
		g.setTooltipForNextFrame(Minecraft.getInstance().font, text, mouseX, mouseY);
	}
}
