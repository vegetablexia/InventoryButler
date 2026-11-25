package com.inventorybutler.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 界面上的绘制：收藏边框 + 归位描边 + 归位幽灵。
 *
 * <p><b>坐标系</b>：{@code AbstractContainerScreen.extractContents} 在调用
 * {@code extractSlots} 之前已经 {@code pose().translate(leftPos, topPos)} 过了，
 * 所以在 {@code extractSlot} / {@code extractSlots} 里拿到的坐标原点就是 GUI 的左上角，
 * 直接使用 {@code slot.x} / {@code slot.y} 即可。命名上把这个空间叫「GUI 空间」。</p>
 */
public final class InventoryOverlay {
	/**
	 * 收藏标记：槽位外框四条边的金色描边（1px），四角留空。
	 *
	 * <p>归位标记占的是<b>四角</b>（4px 长、2px 厚的白色角标），收藏标记就占<b>四条边</b>
	 * —— 两种描边正好互补，同一格上出现也不会叠在一起。金色线画在槽位最外圈的 1px 边框上，
	 * 不压物品图标、数量数字和耐久条；归位角标的白色主体会往物品区里让 1px，详见
	 * {@link #drawPinCorners}。</p>
	 */
	private static final int FAVORITE_LINE = 0xFFFFD64A;

	/**
	 * 没有归位角标时，每条边两端留给四角空位的像素数。
	 *
	 * <p>有归位角标时改用 {@link #PIN_CORNER_LENGTH}（4px）：加粗角标的深色外衬
	 * 占在外框上，金色中段要让开这 4px，两层才不会互相盖住。</p>
	 */
	private static final int FAVORITE_CORNER_GAP = 2;

	/**
	 * 归位幽灵的「洗淡」层：原版槽位灰 + alpha。
	 *
	 * <p>比上一版（0xA8）调淡了一档 —— 深色物品在被洗之后糊成一团是老问题，
	 * 少盖一点灰，图标本体能透出来更多；「这是个占位幽灵」的意思改由
	 * 四角的白色角标来承担（见 {@link #drawPinCorners}）。</p>
	 */
	private static final int GHOST_FADE = 0x908B8B8B;

	/**
	 * 归位标记四角的角标颜色。
	 *
	 * <p>完全不透明的白，比原来的 85% 白更经得住各种槽位底色；角标本身 4px 长、
	 * 2px 厚，外侧另有 1px 深灰外衬（见 {@link #PIN_CORNER_OUTLINE}），所以在槽位
	 * 右下角的纯白高光上也能看出边界。</p>
	 */
	private static final int PIN_CORNER = 0xFFFFFFFF;

	/** 归位角标的外衬：只压在外框的四个角上，给白色主体隔出边界。 */
	private static final int PIN_CORNER_OUTLINE = 0xFF2B2B2B;

	/** 归位角标每条臂的长度（外衬和白色主体同长）。 */
	private static final int PIN_CORNER_LENGTH = 4;

	/** 白色主体的厚度；从外框往里让 1px 再画。 */
	private static final int PIN_CORNER_THICKNESS = 2;

	private InventoryOverlay() {
	}

	// ------------------------------------------------------------------
	// 绘制
	// ------------------------------------------------------------------

	/**
	 * 给收藏物品的槽位描一圈金色边标：四条边各画一段，四角留空。
	 *
	 * <p><b>为什么画在外框上</b>：{@code slot.x/y} 里是 16×16 的物品区，外围还有
	 * 1px 的槽位边框。线画在这一圈边框上，就不和物品图标、右下角的数量、底部的
	 * 耐久条抢像素 —— 这个注入点在 {@code extractSlot} 的 TAIL，晚于原版的
	 * {@code itemDecorations}，画在物品区里面会盖住那些字。</p>
	 *
	 * <p><b>四角留多宽</b>：没有归位角标时留 {@link #FAVORITE_CORNER_GAP}（2px），
	 * 保持原来的收藏边标外观；同一格有归位角标时留 {@link #PIN_CORNER_LENGTH}（4px），
	 * 把位置让给角标的深色外衬，金色不会盖住它，两层仍然拼成一个完整边框。</p>
	 *
	 * @param pinOutlinePresent 这一格是否画了归位角标（深色外衬占四角）
	 */
	public static void drawFavoriteFrame(GuiGraphicsExtractor g, Slot slot, boolean pinOutlinePresent) {
		int x0 = slot.x - 1;
		int y0 = slot.y - 1;
		int x1 = slot.x + 17;
		int y1 = slot.y + 17;
		int gap = pinOutlinePresent ? PIN_CORNER_LENGTH : FAVORITE_CORNER_GAP;
		// 上、下两条边：两端各让出 gap 像素给四角
		g.fill(x0 + gap, y0, x1 - gap, y0 + 1, FAVORITE_LINE);
		g.fill(x0 + gap, y1 - 1, x1 - gap, y1, FAVORITE_LINE);
		// 左、右两条边
		g.fill(x0, y0 + gap, x0 + 1, y1 - gap, FAVORITE_LINE);
		g.fill(x1 - 1, y0 + gap, x1, y1 - gap, FAVORITE_LINE);
	}

	/**
	 * 画「归位幽灵」—— 预留位空着时用半透明图标提示这一格是留给谁的。
	 *
	 * <p><b>半透明是怎么做出来的</b>：26.2 的 {@link GuiGraphicsExtractor} 没有 alpha /
	 * 着色接口，连 {@code item(ItemStack, int, int)} 都没有颜色参数（只有个是旋转用的种子），
	 * 所以直接按 alpha 画物品这条路走不通。</p>
	 *
	 * <p>思路沿用「先按原样画图标、再盖带透明度的槽位灰」，灰从 0xA8 调淡到 0x90 ——
	 * 深色物品不再糊成一团。只画图标不画 {@code itemDecorations}，所以数量、耐久条
	 * 这些都不会出现 —— 它只是「占位提示」，不是真的物品。</p>
	 *
	 * <p><b>四角角标不在这里画</b>：占位图标只属于「格子空着」这一种情况，而归位标记
	 * 本身要一直看得见（格里坐着物品时也要看得到）。角标统一由 {@link #drawPinCorners}
	 * 画，空槽和占用槽的标记位置完全一致。</p>
	 */
	public static void drawGhostItem(GuiGraphicsExtractor g, Slot slot, ItemStack stack) {
		int x = slot.x;
		int y = slot.y;
		g.item(stack, x, y);
		g.fill(x, y, x + 16, y + 16, GHOST_FADE);
	}

	/**
	 * 归位标记的四角描边 —— 只要这一格被标记就画，不管格子里有没有物品。
	 *
	 * <p>画在槽位最外圈的 18×18 外框上（{@code slot.x - 1} 起），和收藏的金色四条边
	 * 共用同一个外框：收藏占四条边的中段，归位占四个角。收藏边标画在这之后；同格有
	 * 归位角标时它会把两端各让出 4px（见 {@link #drawFavoriteFrame}），两层仍然拼成
	 * 一个完整边框，像素上不重叠。</p>
	 *
	 * <p><b>白色主体为什么往物品区里让 1px</b>：槽位外框的下边和右边是纯白高光，
	 * 白色角标直接画在外框上会糊成一片。现在把 1px 深灰外衬压在外框上，白色主体
	 * 从外框往里让 1px 再画 2px 厚，右下角也能看出清楚边界。代价是白色会盖住物品
	 * 外沿 1–2px；非空槽的角标画在 {@code itemDecorations} 之前，数量和耐久条会
	 * 盖在角标上面，不会被角标挡住（由 {@code AbstractContainerScreenMixin} 负责）。</p>
	 *
	 * <p>四个 L 形角标，每条臂 4px，白色 2px 厚，外衬 1px；一共 16 次 {@code fill}，
	 * 每帧开销可以忽略。</p>
	 */
	public static void drawPinCorners(GuiGraphicsExtractor g, Slot slot) {
		// 外框左上角（收藏边标也是从 slot.x - 1 / slot.y - 1 起画的）
		drawCornerMarks(g, slot.x - 1, slot.y - 1);
	}

	/**
	 * 在 18×18 外框的四个角上各画一个 L：
	 * 外衬（1px 深灰）占外框本身，白色主体从外框往里让 1px、厚 2px、长 4px。
	 */
	private static void drawCornerMarks(GuiGraphicsExtractor g, int x, int y) {
		// 外衬：外框上的 4px L，先画，给白色主体垫底
		// 左上
		g.fill(x, y, x + PIN_CORNER_LENGTH, y + 1, PIN_CORNER_OUTLINE);
		g.fill(x, y, x + 1, y + PIN_CORNER_LENGTH, PIN_CORNER_OUTLINE);
		// 右上
		g.fill(x + 18 - PIN_CORNER_LENGTH, y, x + 18, y + 1, PIN_CORNER_OUTLINE);
		g.fill(x + 17, y, x + 18, y + PIN_CORNER_LENGTH, PIN_CORNER_OUTLINE);
		// 左下
		g.fill(x, y + 17, x + PIN_CORNER_LENGTH, y + 18, PIN_CORNER_OUTLINE);
		g.fill(x, y + 18 - PIN_CORNER_LENGTH, x + 1, y + 18, PIN_CORNER_OUTLINE);
		// 右下
		g.fill(x + 18 - PIN_CORNER_LENGTH, y + 17, x + 18, y + 18, PIN_CORNER_OUTLINE);
		g.fill(x + 17, y + 18 - PIN_CORNER_LENGTH, x + 18, y + 18, PIN_CORNER_OUTLINE);

		// 白色主体：从外框往里让 1px，长 4px、厚 2px
		// 左上
		g.fill(x + 1, y + 1, x + 1 + PIN_CORNER_LENGTH, y + 1 + PIN_CORNER_THICKNESS, PIN_CORNER);
		g.fill(x + 1, y + 1, x + 1 + PIN_CORNER_THICKNESS, y + 1 + PIN_CORNER_LENGTH, PIN_CORNER);
		// 右上
		g.fill(x + 17 - PIN_CORNER_LENGTH, y + 1, x + 17, y + 1 + PIN_CORNER_THICKNESS, PIN_CORNER);
		g.fill(x + 17 - PIN_CORNER_THICKNESS, y + 1, x + 17, y + 1 + PIN_CORNER_LENGTH, PIN_CORNER);
		// 左下
		g.fill(x + 1, y + 17 - PIN_CORNER_THICKNESS, x + 1 + PIN_CORNER_LENGTH, y + 17, PIN_CORNER);
		g.fill(x + 1, y + 17 - PIN_CORNER_LENGTH, x + 1 + PIN_CORNER_THICKNESS, y + 17, PIN_CORNER);
		// 右下
		g.fill(x + 17 - PIN_CORNER_LENGTH, y + 17 - PIN_CORNER_THICKNESS, x + 17, y + 17, PIN_CORNER);
		g.fill(x + 17 - PIN_CORNER_THICKNESS, y + 17 - PIN_CORNER_LENGTH, x + 17, y + 17, PIN_CORNER);
	}
}