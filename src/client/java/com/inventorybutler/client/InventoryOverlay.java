package com.inventorybutler.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 界面上的绘制：收藏星标 + 归位幽灵 + 垃圾桶按钮。
 *
 * <p><b>垃圾桶按钮的位置和风格</b>：跟着原版的配方书按钮（绿书）走 ——
 * 在带配方书的界面里，它就贴在绿书右边（{@code 书.x + 20 + 2, 书.y}），
 * 尺寸 20x18 和绿书一致。<b>边框是内凹样式</b>：深色的上/左缘、亮色的下/右缘，
 * 像原版槽位那样「凹进去」的按压感，而不是凸起的按钮。</p>
 *
 * <p><b>坐标系</b>：{@code AbstractContainerScreen.extractContents} 在调用
 * {@code extractSlots} 之前已经 {@code pose().translate(leftPos, topPos)} 过了，
 * 所以在 {@code extractSlot} / {@code extractSlots} 里拿到的坐标原点就是 GUI 的左上角，
 * 直接使用 {@code slot.x} / {@code slot.y} 即可。命名上把这个空间叫「GUI 空间」。</p>
 */
public final class InventoryOverlay {
	/**
	 * 按钮尺寸 = 原版配方书按钮的尺寸（20x18）。
	 *
	 * <p>风格要跟绿书统一，尺寸自然也得一模一样 —— 同一张按钮精灵图，
	 * 原样缩放才是它的设计分辨率。</p>
	 */
	public static final int BUTTON_WIDTH = 20;
	public static final int BUTTON_HEIGHT = 18;

	/** 和绿书之间留 2px 的间隔，与原版控件之间的习惯间距一致。 */
	private static final int BOOK_GAP = 2;

	/** 回退位置（没有配方书的界面）：按钮离 GUI 右边缘往外挪的距离。 */
	private static final int FALLBACK_MARGIN_X = 4;

	/** 回退位置：对齐容器第一行槽位（原版几乎都从 y=18 开始）。 */
	public static final int FALLBACK_Y = 18;

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

	/** 槽位底色（原版物品格那层灰）带上透明度，用来把「归位幽灵」洗淡。 */
	private static final int GHOST_FADE = 0xA88B8B8B;

	private InventoryOverlay() {
	}

	// ------------------------------------------------------------------
	// 按钮的位置（GUI 空间）
	// ------------------------------------------------------------------

	/**
	 * 有配方书的界面：垃圾桶贴在绿书右边。
	 *
	 * <p>{@code bookX} / {@code bookY} 是配方书按钮在 GUI 空间里的坐标
	 * （由 mixin 从原版 {@code getRecipeBookButtonPosition()} 换算过来），
	 * 右移一个按钮宽 + 一条 2px 缝、垂直不动 —— 和绿书排成一行。</p>
	 */
	public static int bookSideButtonX(int bookX) {
		return bookX + BUTTON_WIDTH + BOOK_GAP;
	}

	public static int bookSideButtonY(int bookY) {
		return bookY;
	}

	/** 没有配方书的界面：回退到 GUI 右缘外侧。 */
	public static int fallbackButtonX(int imageWidth) {
		return imageWidth + FALLBACK_MARGIN_X;
	}

	// ------------------------------------------------------------------
	// 绘制
	// ------------------------------------------------------------------

	/** 在槽位左上角画一个金色星标。 */
	public static void drawFavoriteBadge(GuiGraphicsExtractor g, Slot slot) {
		int x = slot.x - 1;
		int y = slot.y - 1;
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
	 * <p>换个思路：先按原样把图标画上去，再用<b>带透明度的槽位底色</b>盖住这 16x16。
	 * 视觉上就是图标被洗成了半透明的幽灵，而且因为盖的就是背包本来的灰色，
	 * 不会像叠一层黑色那样显得脏。只画图标不画 {@code itemDecorations}，
	 * 所以数量、耐久条这些都不会出现 —— 它只是「占位提示」，不是真的物品。</p>
	 */
	public static void drawGhostItem(GuiGraphicsExtractor g, Slot slot, ItemStack stack) {
		g.item(stack, slot.x, slot.y);
		g.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, GHOST_FADE);
	}

	/**
	 * 画垃圾桶按钮。
	 *
	 * <p>边框是<b>内凹</b>样式：深色的上/左缘 + 亮色的下/右缘 + 容器灰内芯，
	 * 和原版槽位一样是「凹进去」的观感；悬停时往内芯铺一层半透明白。</p>
	 *
	 * <p>垃圾桶里有东西时直接把那个物品显示出来（一眼就能看出「取回」能拿回什么），
	 * 空的时候显示带盖的垃圾桶图标。</p>
	 */
	public static void drawTrashButton(GuiGraphicsExtractor g, int x, int y, boolean hovered) {
		drawInsetFrame(g, x, y, hovered);
		if (!ClientTrashState.isEmpty()) {
			g.item(ClientTrashState.get(), x + 2, y + 1);
			return;
		}
		drawTrashIcon(g, x, y);
	}

	/**
	 * 内凹边框：先铺一层深色（自然形成上/左缘），再画内芯，
	 * 最后用亮色勾出下/右缘 —— 光源在左上时，凹进去的面就是「上暗下亮」，
	 * 和凸起按钮（上亮下暗）正好相反，一眼就能看出是往里凹的。
	 */
	private static void drawInsetFrame(GuiGraphicsExtractor g, int x, int y, boolean hovered) {
		g.fill(x, y, x + BUTTON_WIDTH, y + BUTTON_HEIGHT, SLOT_DARK);                // 上/左缘 + 打底
		g.fill(x + 1, y + 1, x + BUTTON_WIDTH - 1, y + BUTTON_HEIGHT - 1, SLOT_BG);  // 内芯
		g.fill(x + 1, y + BUTTON_HEIGHT - 1, x + BUTTON_WIDTH - 1, y + BUTTON_HEIGHT, SLOT_LIGHT); // 下缘
		g.fill(x + BUTTON_WIDTH - 1, y + 1, x + BUTTON_WIDTH, y + BUTTON_HEIGHT - 1, SLOT_LIGHT);  // 右缘
		if (hovered) {
			g.fill(x + 1, y + 1, x + BUTTON_WIDTH - 1, y + BUTTON_HEIGHT - 1, SLOT_HIGHLIGHT);
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
	 * 在 20x18 的按钮里手搓一个 12x12 的垃圾桶图标。
	 *
	 * <p>结构照主流 mod 的垃圾桶图标来：提手（3px，居中）→ 盖子（9px，比桶身每边宽
	 * 1px）→ 空 1px 的缝 → 桶身（7px 宽、8px 高，最后一行收窄出梯形感）。
	 * 桶身掏<b>两条 1px 的白色竖缝</b>当透气孔 —— 两条缝之间必须留深灰，
	 * 要是缝挨着缝，画出来的就不是「三格桶身」而是一整块白斑。</p>
	 */
	private static void drawTrashIcon(GuiGraphicsExtractor g, int ox, int oy) {
		int x = ox + 4;
		int y = oy + 3;

		g.fill(x + 5, y, x + 8, y + 1, ICON_MAIN);        // 提手（列 5..7）
		g.fill(x + 2, y + 1, x + 11, y + 2, ICON_MAIN);   // 盖子（列 2..10）
		g.fill(x + 3, y + 3, x + 10, y + 11, ICON_MAIN);  // 桶身（列 3..9，行 3..10）
		g.fill(x + 4, y + 11, x + 9, y + 12, ICON_MAIN);  // 桶底收窄一行（列 4..8）
		g.fill(x + 4, y + 4, x + 5, y + 10, ICON_LIGHT);  // 透气缝 1（列 4）
		g.fill(x + 8, y + 4, x + 9, y + 10, ICON_LIGHT);  // 透气缝 2（列 8）
	}

	/** 鼠标停在垃圾桶按钮上时给出原版风格的 tooltip。 */
	public static void addTrashTooltip(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		Component text = ClientTrashState.isEmpty()
				? Component.translatable("inventorybutler.tooltip.trash.empty")
				: Component.translatable("inventorybutler.tooltip.trash.reclaim");
		g.setTooltipForNextFrame(Minecraft.getInstance().font, text, mouseX, mouseY);
	}
}
