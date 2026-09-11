package com.inventorybutler.client.mixin;

import com.inventorybutler.FavoriteStacks;
import com.inventorybutler.ModConfig;
import com.inventorybutler.client.ClientFeedback;
import com.inventorybutler.client.ClientPinState;
import com.inventorybutler.client.ClientTrashState;
import com.inventorybutler.client.CursorIcons;
import com.inventorybutler.client.InventoryOverlay;
import com.inventorybutler.client.InventoryButlerClient;
import com.inventorybutler.client.ScreenMessage;
import com.inventorybutler.client.SlotSections;
import com.inventorybutler.network.FavoriteTogglePayload;
import com.inventorybutler.network.PinPayload;
import com.inventorybutler.network.SortPayload;
import com.inventorybutler.network.TrashPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 所有「物品栏界面」交互的入口。
 *
 * <p>26.x 相对旧版本有几处必须注意的改动，这里集中说明一下：</p>
 * <ul>
 *   <li>鼠标事件不再是一堆 {@code double}/{@code int} 散参数，而是
 *       {@link MouseButtonEvent} 这个 record —— 修饰键直接问它
 *       （{@code hasAltDown()} / {@code hasControlDown()}），不用再去查全局按键状态。</li>
 *   <li>键盘事件变成了 {@link KeyEvent}，配合 {@code KeyMapping.matches(KeyEvent)} 判断。</li>
 *   <li>渲染拆成了 {@code extractXxx} 系列，坐标空间在 {@code extractContents} 里
 *       已经被 {@code translate(leftPos, topPos)} 过，所以 {@code slot.x}/{@code slot.y} 直接可用。</li>
 * </ul>
 *
 * <p><b>操作表</b>（这里不再做任何鼠标拖拽 / 滚轮手势，那部分功能已移除）：</p>
 * <ul>
 *   <li><b>点垃圾桶按钮</b>：贴在配方书按钮（绿书）右边、同尺寸（20x18），
 *       内凹槽位风格的边框；没有配方书的界面回退到 GUI 右缘外侧</li>
 *   <li><b>鼠标中键</b> / <b>R</b>（可改绑）：一键整理（只整理主背包，快捷栏不动），不弹提示</li>
 *   <li><b>Shift + 点击</b>：背包里的收藏物<b>不能</b>被快捷移动进容器（服务端同样拦截；
 *       从容器往背包搬不受限）</li>
 *   <li><b>T</b>（可改绑）：给指向的这一格打「归位标记」—— 这一格以后就留给这个物品，
 *       物品不在这儿时格子显示半透明图标占位</li>
 *   <li>Alt + 左键：收藏 / 取消收藏</li>
 *   <li>Ctrl + 左键：把这一格丢进垃圾桶</li>
 *   <li>Delete（可改绑）：把鼠标指向的那格丢进垃圾桶</li>
 *   <li>按住 Alt / Ctrl 时鼠标指针会变成<b>星星 / 垃圾桶</b>（指针指着能生效的槽位才变，
 *       实现在 {@code CursorIcons}）</li>
 * </ul>
 *
 * <p><b>两种标记的区别</b>：收藏（金星星标）管「不许动」，归位（半透明图标）管「放哪儿」。
 * 前者防丢防删防整理，后者只管物品回家时优先住回自己那一格。两者可以同时用。</p>
 */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {
	@Shadow
	protected Slot hoveredSlot;

	@Shadow
	protected AbstractContainerMenu menu;

	@Shadow
	protected int imageWidth;

	@Shadow
	protected int leftPos;

	@Shadow
	protected int topPos;

	@Shadow
	protected abstract boolean isHovering(int x, int y, int width, int height, double mouseX, double mouseY);

	/**
	 * 本次「按下 - 松开」里，鼠标是不是按在垃圾桶按钮上。
	 *
	 * <p>这个状态必须自己记：按钮画在 GUI 的空地上（或外侧），原版不会替它处理点击；
	 * 尤其松手落在 GUI 外时，{@code hasClickedOutside} 分支会把光标上拿着的物品
	 * 直接丢到地上。按下时拦掉还不够，得让对应的那次松开也一起被吞掉。</p>
	 */
	@Unique
	private boolean inventorybutler$customButtonPressed;

	// ------------------------------------------------------------------
	// 鼠标
	// ------------------------------------------------------------------

	@Inject(method = "mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z",
			at = @At("HEAD"), cancellable = true)
	private void inventorybutler$onMouseClicked(MouseButtonEvent event, boolean doubleClick,
			CallbackInfoReturnable<Boolean> cir) {
		AbstractContainerMenu menu = this.menu;
		if (menu == null) {
			return;
		}

		// 每次按下都从「没按在按钮上」重新开始判断。
		//
		// 不重置的话，万一上一次点击的 mouseReleased 被别的 mod 吞掉了（没轮到我们），
		// 这个标志就会一直挂着 true，之后某次普通松手会被莫名其妙地吞掉一次。
		// 重置是无害的：同一次点击里 mouseClicked 一定先于 mouseReleased 执行。
		inventorybutler$customButtonPressed = false;

		// 1) 鼠标中键：一键整理
		//
		// 创造模式的物品栏里，中键是原版「复制物品」的绑定（keyPickItem），
		// 那是很顺手的老习惯，不该被抢走；而且创造模式物品栏本来也没什么好整理的，
		// 所以那边不做中键整理，把中键还给原版。
		if (!(screen() instanceof CreativeModeInventoryScreen)
				&& matchesMouse(InventoryButlerClient.sortKey(), event)) {
			if (ModConfig.sortEnabled) {
				// 整理不发任何提示 —— 这是个高频操作，弹一行字反而挡住视线
				ClientPlayNetworking.send(new SortPayload(menu.containerId));
			}
			cir.setReturnValue(true);
			return;
		}

		// 2) 自绘按钮只认左键 —— 原版按钮也都是左键。
		//
		// 顺序上放在槽位判定之前：垃圾桶在 GUI 里的空地上，先判按钮可以少走一遍
		// 36 个槽位的循环。
		if (event.button() == 0 && inventorybutler$showButtons()) {
			int[] pos = inventorybutler$trashButtonPos();
			if (ModConfig.trashEnabled
					&& isOverButton(pos[0], pos[1], event.x(), event.y())) {
				boolean carrying = !menu.getCarried().isEmpty();
				// 手上有东西就丢进去；手是空的且垃圾桶有东西，就取回来（撤销）
				int action = (carrying || ClientTrashState.isEmpty())
						? TrashPayload.ACTION_CARRIED
						: TrashPayload.ACTION_RECLAIM;
				ClientPlayNetworking.send(new TrashPayload(menu.containerId, -1, action));
				// 记一笔，等会儿的 mouseReleased 要一起吞掉
				inventorybutler$customButtonPressed = true;
				cir.setReturnValue(true);
				return;
			}
		}

		Slot slot = slotAt(event.x(), event.y());
		if (slot == null) {
			return;
		}

		// 3) Shift+点击（快捷移动）：背包里的收藏物不许被搬进容器。
		//
		// 服务端在菜单入口也有同样的拦截（权威），这里拦是为了两件事：
		// 弹一行提示告诉玩家为什么没动静 + 不让客户端的点击预测和服务端打架。
		// 只拦背包方向 —— 从容器往背包搬（拿回东西）不受限。
		if (ModConfig.favoriteEnabled && ModConfig.favoriteProtectFromDrop
				&& event.button() == 0 && event.hasShiftDown()
				&& slot.container instanceof Inventory
				&& FavoriteStacks.isFavorite(slot.getItem())) {
			ClientFeedback.cannotMoveFavorite();
			cir.setReturnValue(true);
			return;
		}

		// 4) Alt + 左键：收藏 / 取消收藏
		if (ModConfig.favoriteEnabled && event.button() == 0 && event.hasAltDown()) {
			ClientPlayNetworking.send(new FavoriteTogglePayload(menu.containerId, slot.index));
			cir.setReturnValue(true);
			return;
		}

		// 5) Ctrl + 左键：把这一格丢进垃圾桶（泰拉瑞亚式的删除）
		if (ModConfig.trashEnabled && event.button() == 0 && event.hasControlDown()
				&& SlotSections.acceptsTransfer(slot)) {
			ClientPlayNetworking.send(new TrashPayload(
					menu.containerId, slot.index, TrashPayload.ACTION_SLOT));
			cir.setReturnValue(true);
		}
	}

	/**
	 * 修「点界面外的按钮时，光标上的物品被丢到地上」。
	 *
	 * <p>根因：自绘按钮画在 GUI 右侧外面（{@code imageWidth + 4}），点它 = 点到界面外。
	 * 原版 {@code mouseReleased} 里有一条：只要这次点击是在界面外按下的，松手时就把
	 * 光标上拿着的物品走 {@code slotClicked(slot, -999, button, PICKUP)} 丢出去。</p>
	 *
	 * <p>对整理按钮来说，这一下丢出去的东西和「整理」毫无关系，纯属误伤；
	 * 对垃圾桶来说更糟 —— 取回时物品刚好被放到了光标上，于是「取回」变成「丢出」。
	 * 所以这里把两种情况都吞掉：松手落在任一按钮上、或本次点击本来就是按在按钮上的。</p>
	 */
	@Inject(method = "mouseReleased(Lnet/minecraft/client/input/MouseButtonEvent;)Z",
			at = @At("HEAD"), cancellable = true)
	private void inventorybutler$onMouseReleased(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
		if (inventorybutler$customButtonPressed || inventorybutler$isOverAnyButton(event)) {
			inventorybutler$customButtonPressed = false;
			cir.setReturnValue(true);
		}
	}

	// ------------------------------------------------------------------
	// 键盘
	// ------------------------------------------------------------------

	@Inject(method = "keyPressed(Lnet/minecraft/client/input/KeyEvent;)Z",
			at = @At("HEAD"), cancellable = true)
	private void inventorybutler$onKeyPressed(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
		AbstractContainerMenu menu = this.menu;
		if (menu == null) {
			return;
		}

		// 先拦「界面打开时按丢弃键」。
		//
		// 界面打开时的 Q 走的是完全另一条路，跟 LocalPlayer.drop 没关系：
		//   keyPressed -> options.keyDrop.matches -> slotClicked(slot, index, ctrl?1:0, THROW)
		// 服务端的 AbstractContainerMenuMixin 会在菜单入口把它拦下，
		// 但那属于「事后补救」；在客户端先挡一道，玩家才能立刻看到提示。
		if (matches(inventorybutler$dropKey(), event)) {
			if (ModConfig.favoriteEnabled && ModConfig.favoriteProtectFromDrop
					&& hoveredSlot != null && !hoveredSlot.getItem().isEmpty()
					&& FavoriteStacks.isFavorite(hoveredSlot.getItem())) {
				ClientFeedback.cannotDropFavorite();
				cir.setReturnValue(true);
				return;
			}
			// 不是收藏物，放行给原版处理
			return;
		}

		// 中键是鼠标事件，永远不会以键盘事件的形式到这里；
		// 这里管的是 R 这个第二绑定（以及玩家自己改到键盘上的任何键）。
		if (matches(InventoryButlerClient.sortKey(), event)
				|| matches(InventoryButlerClient.sortAltKey(), event)) {
			if (ModConfig.sortEnabled) {
				ClientPlayNetworking.send(new SortPayload(menu.containerId));
			}
			cir.setReturnValue(true);
			return;
		}

		if (matches(InventoryButlerClient.trashKey(), event)) {
			if (ModConfig.trashEnabled && hoveredSlot != null && SlotSections.acceptsTransfer(hoveredSlot)) {
				ClientPlayNetworking.send(new TrashPayload(
						menu.containerId, hoveredSlot.index, TrashPayload.ACTION_SLOT));
			}
			cir.setReturnValue(true);
			return;
		}

		if (matches(InventoryButlerClient.favoriteKey(), event)) {
			if (ModConfig.favoriteEnabled && hoveredSlot != null && !hoveredSlot.getItem().isEmpty()) {
				ClientPlayNetworking.send(new FavoriteTogglePayload(menu.containerId, hoveredSlot.index));
			}
			cir.setReturnValue(true);
			return;
		}

		// T：给指向的这一格打「归位标记」（再按一次取消）。
		//
		// 只认玩家背包 0-35 —— 箱子的格子、护甲格没有「归位」可言。
		// 服务端还会再挡一道，这里先挡是为了不白发一个注定被丢掉的包。
		if (matches(InventoryButlerClient.pinKey(), event)) {
			if (ModConfig.pinEnabled && hoveredSlot != null
					&& SlotSections.playerIndex(hoveredSlot) >= 0) {
				ClientPlayNetworking.send(new PinPayload(menu.containerId, hoveredSlot.index));
				cir.setReturnValue(true);
			}
			// 没命中有效槽位就放行，别把 T 键对其它界面组件的作用吞掉
		}
	}

	private static boolean matches(KeyMapping mapping, KeyEvent event) {
		return mapping != null && !mapping.isUnbound() && mapping.matches(event);
	}

	private static boolean matchesMouse(KeyMapping mapping, MouseButtonEvent event) {
		return mapping != null && !mapping.isUnbound() && mapping.matchesMouse(event);
	}

	/**
	 * 原版的「丢弃」绑定。
	 *
	 * <p>⚠️ 这里为什么用 {@code Minecraft.getInstance()} 而不是
	 * {@code @Shadow protected Minecraft minecraft;}：</p>
	 *
	 * <p>{@code minecraft} 这个字段是声明在<b>父类 {@code Screen}</b> 上的，
	 * 而 Mixin 的 {@code @Shadow} <b>字段</b>解析只会在目标类自己声明的字段里找
	 * （{@code TargetClassContext.findAliasedField} 只遍历 {@code classNode.fields}
	 * 和别的 mixin 加进来的字段，<b>不向上遍历父类</b>）。方法不同 ——
	 * 方法走的是 {@code findFieldInHierarchy}，所以父类方法可以正常 shadow。</p>
	 *
	 * <p>踩这个坑的症状很典型：<b>编译期一切正常</b>（注解处理器会沿继承链找得到），
	 * 一启动就崩：</p>
	 *
	 * <pre>
	 * InvalidMixinException: @Shadow field minecraft was not located in the
	 * target class ...AbstractContainerScreen. No refMap loaded.
	 * </pre>
	 *
	 * <p>结论：mixin 里要用父类的字段，一律走静态访问器 / 自己算，别 {@code @Shadow}。</p>
	 */
	private static KeyMapping inventorybutler$dropKey() {
		Minecraft mc = Minecraft.getInstance();
		return mc == null ? null : mc.options.keyDrop;
	}

	// ------------------------------------------------------------------
	// 渲染
	// ------------------------------------------------------------------

	/**
	 * 每个槽位画完之后的补充绘制：先「归位幽灵」，再「收藏星标」。
	 *
	 * <p>两件事合并在同一个注入点里，是为了保证先后顺序 —— 幽灵那层是用带透明度的
	 * 槽位底色盖出来的，要是盖在星标上面会把星标一起洗淡。</p>
	 */
	@Inject(method = "extractSlot(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/inventory/Slot;II)V",
			at = @At("TAIL"))
	private void inventorybutler$drawSlotDecorations(GuiGraphicsExtractor extractor, Slot slot,
			int mouseX, int mouseY, CallbackInfo ci) {
		ItemStack stack = slot.getItem();

		// 归位幽灵：这一格被标记过、而标记的物品现在不在这儿（格是空的），
		// 就用半透明图标占位，告诉玩家「这格是给它的」。
		// 只在「空」的时候画 —— 格里有别的东西时再叠一层图标会看不清。
		if (ModConfig.pinEnabled && stack.isEmpty()) {
			int home = SlotSections.playerIndex(slot);
			if (home >= 0) {
				ItemStack pinned = ClientPinState.at(home);
				if (!pinned.isEmpty()) {
					InventoryOverlay.drawGhostItem(extractor, slot, pinned);
				}
			}
		}

		if (ModConfig.favoriteEnabled && !stack.isEmpty() && FavoriteStacks.isFavorite(stack)) {
			InventoryOverlay.drawFavoriteBadge(extractor, slot);
		}
	}

	/**
	 * 槽位都画完之后，补上垃圾桶按钮：贴在配方书按钮（绿书）右边，和绿书排成一行。
	 *
	 * <p>绿书的位置各界面不同（背包 104 / 工作台 5 / 熔炉 20，纵向全走 {@code h/2-…}），
	 * 所以位置一律从原版 {@code getRecipeBookButtonPosition()} 换算，不写死；
	 * 没有配方书的界面回退到 GUI 右缘外侧。</p>
	 */
	@Inject(method = "extractSlots(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V", at = @At("TAIL"))
	private void inventorybutler$drawButtons(GuiGraphicsExtractor extractor,
			int mouseX, int mouseY, CallbackInfo ci) {
		if (this.menu == null || !inventorybutler$showButtons()) {
			return;
		}

		// 顺便把「指针现在指着什么」告诉 CursorIcons —— 按住 Alt/Ctrl 时它要用这个
		// 决定指针换不换图标。这里上报、applyCursor 在帧末读取，是同一帧的数据。
		//
		// 只在能画按钮的界面（即非创造模式物品栏）上报，于是创造模式那边
		// 指针图标也跟着一起不生效 —— 那边有搜索框，Ctrl 是复制粘贴。
		CursorIcons.reportPointerTarget(slotAt(mouseX, mouseY));

		if (ModConfig.trashEnabled) {
			int[] pos = inventorybutler$trashButtonPos();
			boolean hovered = isOverButton(pos[0], pos[1], mouseX, mouseY);
			InventoryOverlay.drawTrashButton(extractor, pos[0], pos[1], hovered);
			if (hovered) {
				InventoryOverlay.addTrashTooltip(extractor, mouseX, mouseY);
			}
		}
	}

	/**
	 * 物品栏界面内的轻提示：画在 GUI 顶上方居中，带一层半透明底条保证可读。
	 *
	 * <p>动作栏文字挂在 HUD 上，容器界面打开时 HUD 不渲染 —— 收藏 / 归位 / 垃圾桶
	 * 这些提示又全都是在界面里触发的，所以走这里显示（{@link ScreenMessage} 暂存，
	 * {@code ClientFeedback} 负责分流）。所有容器界面（包括创造模式）都画。</p>
	 */
	@Inject(method = "extractSlots(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V", at = @At("TAIL"))
	private void inventorybutler$drawScreenMessage(GuiGraphicsExtractor extractor,
			int mouseX, int mouseY, CallbackInfo ci) {
		Component message = ScreenMessage.current();
		if (message == null || client() == null) {
			return;
		}
		Font font = client().font;
		int textWidth = font.width(message);
		int centerX = this.imageWidth / 2;
		// 底条：比文字上下各多 2px、左右各多 4px；文字画在底条中间
		extractor.fill(centerX - textWidth / 2 - 4, -18, centerX + textWidth / 2 + 4, -2, 0x90505050);
		extractor.centeredText(font, message, centerX, -14, 0xFFFFFFFF);
	}

	// ------------------------------------------------------------------
	// 小工具
	// ------------------------------------------------------------------

	/** mixin 类里访问不到父类的 minecraft 字段（Screen 声明的），走静态入口。 */
	private Minecraft client() {
		return Minecraft.getInstance();
	}

	/** mixin 类本身不是 AbstractContainerScreen 的子类，需要显式转型才能传给工具类。 */
	private AbstractContainerScreen<?> screen() {
		return (AbstractContainerScreen<?>) (Object) this;
	}

	/**
	 * 垃圾桶按钮的位置（GUI 空间）。
	 *
	 * <p>带配方书的界面：绿书右边（书按钮 x + 20 宽 + 2px 缝，y 与绿书齐平）。
	 * 绿书位置是「屏幕绝对坐标」（{@code getRecipeBookButtonPosition()} 内部加了
	 * leftPos/topPos），这里减回去换回 GUI 空间。</p>
	 *
	 * <p>没有配方书的界面（箱子、漏斗这些）：回退到 GUI 右缘外侧、第一行槽位的高度。</p>
	 */
	private int[] inventorybutler$trashButtonPos() {
		if (screen() instanceof AbstractRecipeBookScreen) {
			ScreenPosition book = ((AbstractRecipeBookScreenAccessor) screen())
					.inventorybutler$recipeBookButtonPosition();
			return new int[]{
					InventoryOverlay.bookSideButtonX(book.x() - this.leftPos),
					InventoryOverlay.bookSideButtonY(book.y() - this.topPos),
			};
		}
		return new int[]{InventoryOverlay.fallbackButtonX(this.imageWidth), InventoryOverlay.FALLBACK_Y};
	}

	/**
	 * 要不要画工具栏那一列按钮。
	 *
	 * <p>创造模式物品栏右下角本来就自带一个「销毁物品」格，再加上它的界面布局完全不同
	 * （标签页 / 搜索框 / 滚动条），往里塞按钮很容易打架。所以那边不画 ——
	 * 中键整理同样在创造模式里让给了原版。</p>
	 */
	private boolean inventorybutler$showButtons() {
		return !(screen() instanceof CreativeModeInventoryScreen);
	}

	/** 鼠标是不是停在垃圾桶按钮上。用原版的 isHovering —— 它内部会自己加上 leftPos/topPos。 */
	private boolean isOverButton(int x, int y, double mouseX, double mouseY) {
		return isHovering(x, y, InventoryOverlay.BUTTON_WIDTH, InventoryOverlay.BUTTON_HEIGHT, mouseX, mouseY);
	}

	/** 松手时用：这一次松开是不是落在垃圾桶按钮上的。 */
	private boolean inventorybutler$isOverAnyButton(MouseButtonEvent event) {
		if (event.button() != 0 || !inventorybutler$showButtons()) {
			return false;
		}
		int[] pos = inventorybutler$trashButtonPos();
		return ModConfig.trashEnabled && isOverButton(pos[0], pos[1], event.x(), event.y());
	}

	private Slot slotAt(double mouseX, double mouseY) {
		AbstractContainerMenu menu = this.menu;
		if (menu == null) {
			return null;
		}
		for (Slot slot : menu.slots) {
			if (slot.isActive() && isHovering(slot.x, slot.y, 16, 16, mouseX, mouseY)) {
				return slot;
			}
		}
		return null;
	}
}
