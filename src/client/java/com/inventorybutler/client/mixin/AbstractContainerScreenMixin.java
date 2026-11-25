package com.inventorybutler.client.mixin;

import com.inventorybutler.FavoriteStacks;
import com.inventorybutler.ModConfig;
import com.inventorybutler.client.ClientFeedback;
import com.inventorybutler.client.ClientPinState;
import com.inventorybutler.client.CursorIcons;
import com.inventorybutler.SlotRules;
import com.inventorybutler.client.InventoryOverlay;
import com.inventorybutler.client.InventoryButlerClient;
import com.inventorybutler.client.ScreenMessage;
import com.inventorybutler.network.FavoriteTogglePayload;
import com.inventorybutler.network.PinPayload;
import com.inventorybutler.network.SortPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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
 *   <li><b>鼠标中键</b>（可在按键设置里改绑）：一键整理（主背包 + 打开的储存容器；
 *       快捷栏默认不动），成功时不弹提示；服务端没装本 mod 时会说明一句</li>
 *   <li><b>Shift + 点击</b>：收藏物<b>不能</b>被快捷移动进容器（服务端同样拦截；
 *       从容器往背包搬、以及背包内部的换格不受限）</li>
 *   <li><b>Alt + 右键</b>：给指向的这一格打 / 取消「归位标记」—— 这一格以后就留给这个物品，
 *       物品不在这儿时格子显示半透明图标占位</li>
 *   <li><b>Alt + 左键</b>：收藏 / 取消收藏</li>
 *   <li>按住 Alt 时鼠标指针会变成<b>星星</b>（指针指着能生效的槽位才变，
 *       实现在 {@code CursorIcons}）</li>
 *   <li><b>文本框有焦点时</b>（铁砧改名、创造模式搜索框……）键盘热键一律让路，
 *       字母键和 Delete 仍然归输入框</li>
 * </ul>
 *
 * <p><b>两种标记的区别</b>：收藏（金色边标）管「不许动」，归位（半透明图标）管「放哪儿」。
 * 前者防丢防整理，后者只管物品回家时优先住回自己那一格。两者可以同时用。</p>
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
	protected int imageHeight;

	/**
	 * 原版正在进行的「拖拽分堆」。
	 *
	 * <p>拖拽期间左/右键被原版用来决定分堆目标，鼠标中键如果这时被我们拿去整理，
	 * 松手时那套按旧槽位记录的分堆集合就可能落到已经变了的格子上。为了不碰这种
	 * 半途状态，拖拽中一律不接管任何点击。</p>
	 */
	@Shadow
	private boolean isQuickCrafting;

	@Shadow
	protected abstract boolean isHovering(int x, int y, int width, int height, double mouseX, double mouseY);

	// ------------------------------------------------------------------
	// 鼠标
	// ------------------------------------------------------------------

	@Inject(method = "mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z",
			at = @At("HEAD"), cancellable = true)
	private void inventorybutler$onMouseClicked(MouseButtonEvent event, boolean doubleClick,
			CallbackInfoReturnable<Boolean> cir) {
		AbstractContainerMenu menu = this.menu;
		if (menu == null || this.isQuickCrafting) {
			return;
		}

		boolean creative = screen() instanceof CreativeModeInventoryScreen;

		// 1) 鼠标中键：一键整理
		//
		// 创造模式的物品栏里，中键是原版「复制物品」的绑定（keyPickItem），
		// 那是很顺手的老习惯，不该被抢走；而且创造模式物品栏本来也没什么好整理的，
		// 所以那边不做中键整理，把中键还给原版。
		//
		// 只接 GUI 范围内的点击：JEI / EMI 的物品列表、书签栏和配方书都在 GUI 之外，
		// 那里也有自己的中键操作，抢过来会让别的 mod 的界面失灵。
		if (!creative && matchesMouse(InventoryButlerClient.sortKey(), event)
				&& isHovering(0, 0, this.imageWidth, this.imageHeight, event.x(), event.y())) {
			if (!ModConfig.sortEnabled) {
				// 功能关着时把这个键还给原版，而不是吞掉（创造模式的中键「复制物品」同理）
				return;
			}
			// 整理不发任何提示 —— 这是个高频操作，弹一行字反而挡住视线。
			// 但「服务端没装本 mod」是例外：不说一声，玩家只会觉得按键坏了。
			if (!ClientPlayNetworking.canSend(SortPayload.TYPE)) {
				ClientFeedback.showTranslation("inventorybutler.message.server.missing");
				// 不吞掉这次点击：让原版 / 别的 mod 拿到它
				return;
			}
			ClientPlayNetworking.send(new SortPayload(serverContainerId()));
			cir.setReturnValue(true);
			return;
		}

		Slot slot = slotAt(event.x(), event.y());
		if (slot == null) {
			return;
		}

		// 2) Shift+点击（快捷移动）的收藏保护不在这里做。
		//
		// 同一个槽位在背包界面里是「背包内换格」，在箱子界面里却是「搬进箱子」——
		// 只看「点的是哪个格」会把前者也误拦。真正的位置判断在
		// AbstractContainerMenuMixin#moveItemStackTo 里按目的地判断，客户端预测和
		// 服务端执行跑的是同一段代码，所以这里放行即可。

		// 3) Alt + 左键：收藏 / 取消收藏
		//
		// 空格子没有可收藏的东西，此时放行给原版（左键放东西 / 创造模式垃圾桶等）。
		// 创造模式的菜单是客户端专用的物品选择器，槽位下标和服务端的 InventoryMenu
		// 对不上；只有真正的玩家背包格能收藏（物品选择页是底部快捷栏，「物品栏」页
		// 是整块背包），都要先换算成服务端 InventoryMenu 下标再发。
		// 26.3 的鼠标按钮编号跟着 SDL 走：左=1、中=2、右=3（GLFW 时代是 0/1/2）。
		// 不能再用 0 / 1 这种字面量，否则 Alt+左键会被当成右键分支。
		if (ModConfig.favoriteEnabled && event.button() == InputConstants.MOUSE_BUTTON_LEFT
				&& event.hasAltDown() && !slot.getItem().isEmpty()) {
			int slotId = serverSlotId(slot);
			if (slotId < 0) {
				return;
			}
			if (!ClientPlayNetworking.canSend(FavoriteTogglePayload.TYPE)) {
				ClientFeedback.showTranslation("inventorybutler.message.server.missing");
				return;
			}
			ClientPlayNetworking.send(new FavoriteTogglePayload(serverContainerId(), slotId));
			cir.setReturnValue(true);
			return;
		}

		// 4) Alt + 右键：打 / 取消归位标记。
		//
		// 只认玩家背包 0-35 —— 箱子的格子、护甲格没有「归位」可言。
		// 空格子只有在已经有标记时才有效（取消标记）；否则放行给原版，
		// 别吞掉 Alt+右键原本的半叠拿起 / 放一个。
		if (ModConfig.pinEnabled && event.button() == InputConstants.MOUSE_BUTTON_RIGHT
				&& event.hasAltDown()) {
			int home = inventoryHome(slot);
			if (home < 0) {
				return;
			}
			if (slot.getItem().isEmpty() && ClientPinState.at(home).isEmpty()) {
				return;
			}
			int slotId = serverSlotId(slot);
			if (slotId < 0) {
				return;
			}
			if (!ClientPlayNetworking.canSend(PinPayload.TYPE)) {
				ClientFeedback.showTranslation("inventorybutler.message.server.missing");
				return;
			}
			ClientPlayNetworking.send(new PinPayload(serverContainerId(), slotId));
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

		// 铁砧的改名框、创造模式的搜索框都开着 EditBox；有焦点时字母 / Delete 属于输入，
		// 我们的热键必须让路 —— 否则改绑到字母键上的热键会在输入框里触发。
		if (((Screen) (Object) this).getFocused() instanceof EditBox) {
			return;
		}

		// 先拦「界面打开时按丢弃键」。
		//
		// 界面打开时的 Q 走的是完全另一条路，跟 MultiPlayerGameMode.dropItem 没关系：
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

		// 中键是鼠标事件，正常不会走到键盘事件这里；但如果玩家把整理改绑到键盘键上，
		// 键盘路径也要能用。功能关着时不认这个键 —— 把它还给原版，
		// 免得玩家改绑到有原版含义的键上却被吞掉。
		if (matches(InventoryButlerClient.sortKey(), event)) {
			// 和鼠标中键那条路保持一致：拖拽分堆进行中不整理（松手时会按旧槽位落堆），
			// 创造模式物品栏也不整理（界面菜单是客户端专用的选择器，服务端对不上）。
			if (this.isQuickCrafting || screen() instanceof CreativeModeInventoryScreen) {
				return;
			}
			if (ModConfig.sortEnabled) {
				if (!ClientPlayNetworking.canSend(SortPayload.TYPE)) {
					ClientFeedback.showTranslation("inventorybutler.message.server.missing");
					return;
				}
				ClientPlayNetworking.send(new SortPayload(serverContainerId()));
				cir.setReturnValue(true);
			}
			return;
		}

		if (matches(InventoryButlerClient.favoriteKey(), event)) {
			if (ModConfig.favoriteEnabled && hoveredSlot != null && !hoveredSlot.getItem().isEmpty()) {
				int slotId = serverSlotId(hoveredSlot);
				if (slotId < 0) {
					return;
				}
				if (!ClientPlayNetworking.canSend(FavoriteTogglePayload.TYPE)) {
					ClientFeedback.showTranslation("inventorybutler.message.server.missing");
					return;
				}
				ClientPlayNetworking.send(new FavoriteTogglePayload(serverContainerId(), slotId));
				cir.setReturnValue(true);
			}
			return;
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

	/**
	 * 界面关闭 / 被替换时清空提示队列。
	 *
	 * <p>提示是对「刚才这次操作」的反馈：界面都关了，剩下的几条再在下一个界面里
	 * 补播只会让人一头雾水（换存档 / 切屏之后尤其明显）。</p>
	 */
	@Inject(method = "removed()V", at = @At("TAIL"))
	private void inventorybutler$clearMessagesOnClose(CallbackInfo ci) {
		ScreenMessage.clear();
	}

	// ------------------------------------------------------------------
	// 渲染
	// ------------------------------------------------------------------

	/**
	 * 非空槽位的归位角标，要在原版画数量 / 耐久条之前画。
	 *
	 * <p>加粗后的白色角标会往物品区里压 1–2px，而堆叠数量就在右下角、耐久条就在下沿；
	 * 要是等到 {@code extractSlot} 的 TAIL 再画，会把它们盖住。角标在
	 * {@code itemDecorations} 之前落笔，原版随后画的数量和耐久条就压在角标上面 ——
	 * 信息优先，角标仍然露在外面。</p>
	 */
	@Inject(method = "extractSlot(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/inventory/Slot;II)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;itemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V",
					shift = At.Shift.BEFORE))
	private void inventorybutler$drawPinCornersBeforeDecorations(GuiGraphicsExtractor extractor, Slot slot,
			int mouseX, int mouseY, CallbackInfo ci) {
		if (!ModConfig.pinEnabled) {
			return;
		}
		int home = inventoryHome(slot);
		if (home < 0 || ClientPinState.at(home).isEmpty()) {
			return;
		}
		InventoryOverlay.drawPinCorners(extractor, slot);
	}

	/**
	 * 槽位收尾的补充绘制。
	 *
	 * <p><b>空槽</b>：先画「归位幽灵」，再把归位角标压在它上面（空槽没有收藏，
	 * 实际只有前两步）。幽灵是用带透明度的槽位底色盖出来的，角标必须在它之后画，
	 * 否则会被一起洗淡。</p>
	 *
	 * <p><b>非空槽</b>：归位角标已经在 {@code itemDecorations} 之前画过，数量和耐久条
	 * 会压在它上面；这里只补收藏边框。收藏边框和归位角标共用外框，有归位角标时
	 * 金色中段两端各让出 4px 给深色外衬，像素上不重叠，拼起来仍是一整圈。</p>
	 *
	 * <p>把「这一格有没有归位角标」传给收藏边框，决定四角留 2px 还是 4px。</p>
	 */
	@Inject(method = "extractSlot(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/inventory/Slot;II)V",
			at = @At("TAIL"))
	private void inventorybutler$drawSlotDecorations(GuiGraphicsExtractor extractor, Slot slot,
			int mouseX, int mouseY, CallbackInfo ci) {
		ItemStack stack = slot.getItem();
		boolean pinOutlinePresent = false;

		if (ModConfig.pinEnabled) {
			int home = inventoryHome(slot);
			if (home >= 0) {
				ItemStack pinned = ClientPinState.at(home);
				if (!pinned.isEmpty()) {
					pinOutlinePresent = true;
					if (stack.isEmpty()) {
						// 幽灵图标只在空格上画：格里有别的东西时再叠一层图标会看不清。
						InventoryOverlay.drawGhostItem(extractor, slot, pinned);
						// 空槽没有 itemDecorations，角标在这里补；压在幽灵上面。
						InventoryOverlay.drawPinCorners(extractor, slot);
					}
					// 非空槽的角标已在 itemDecorations 之前画好，这里不再覆盖数量/耐久条。
				}
			}
		}

		if (ModConfig.favoriteEnabled && !stack.isEmpty() && FavoriteStacks.isFavorite(stack)) {
			InventoryOverlay.drawFavoriteFrame(extractor, slot, pinOutlinePresent);
		}
	}

	/**
	 * 槽位都画完之后，把「指针现在是不是停在能收藏的格子上」告诉 CursorIcons ——
	 * 按住 Alt 时它要用这个决定指针换不换星星图标。这里上报、applyCursor 在帧末读取，
	 * 是同一帧的数据。
	 *
	 * <p>判定必须和 Alt+左键真正发包的条件一致：空格子不能收藏；创造模式里只有
	 * 底部快捷栏那几格是真正的背包格，物品选择器里的「物品种类」格子不算。</p>
	 */
	@Inject(method = "extractSlots(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V", at = @At("TAIL"))
	private void inventorybutler$reportPointerTarget(GuiGraphicsExtractor extractor,
			int mouseX, int mouseY, CallbackInfo ci) {
		if (this.menu != null) {
			CursorIcons.reportPointerTarget(isFavoriteTarget(slotAt(mouseX, mouseY)));
		}
	}

	/**
	 * 物品栏界面内的轻提示：画在 GUI 顶上方居中，原版底纹风格 + 淡入淡出。
	 *
	 * <p>动作栏文字挂在 HUD 上，容器界面打开时 HUD 不渲染 —— 收藏 / 归位
	 * 这些提示又全都是在界面里触发的，所以走这里显示（{@link ScreenMessage} 暂存并
	 * 排队，{@code ClientFeedback} 负责分流）。所有容器界面（包括创造模式）都画。</p>
	 *
	 * <p><b>底纹走原版色源</b>：和 {@code GuiGraphicsExtractor#textWithBackdrop} 一样，
	 * 底色取 {@code options.getBackgroundColor(0)}（随玩家「文字背景不透明度」辅助设置走），
	 * 文字带投影；底纹和文字的 alpha 都乘上 {@link ScreenMessage#fade()} 的淡入淡出系数。
	 * 没有直接调 {@code textWithBackdrop} 是因为它内部 alpha 是死的，做不了渐隐。</p>
	 */
	@Inject(method = "extractSlots(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V", at = @At("TAIL"))
	private void inventorybutler$drawScreenMessage(GuiGraphicsExtractor extractor,
			int mouseX, int mouseY, CallbackInfo ci) {
		Component message = ScreenMessage.current();
		Minecraft mc = client();
		if (message == null || mc == null) {
			return;
		}
		float fade = ScreenMessage.fade();
		if (fade <= 0f) {
			return;
		}

		Font font = mc.font;
		int textWidth = font.width(message);
		int textX = this.imageWidth / 2 - textWidth / 2;
		int textY = -14;
		// 底纹：文字四周各外扩 2px（原版 textWithBackdrop 的习惯）
		int backdrop = mc.options.getBackgroundColor(0.0F);
		if (backdrop == 0) {
			// 玩家把「文字背景不透明度」调到 0 时原版就完全不画底 ——
			// 这里用回经典半透明深灰兜底，提示条在亮色界面上不能没有底。
			backdrop = 0x90505050;
		}
		extractor.fill(textX - 2, textY - 2, textX + textWidth + 2, textY + 11,
				ARGB.multiplyAlpha(backdrop, fade));
		extractor.text(font, message, textX, textY,
				ARGB.multiplyAlpha(0xFFFFFFFF, fade), true);
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
	 * 发往服务端的菜单 id。
	 *
	 * <p>创造模式的界面用的是客户端专用的 {@code ItemPickerMenu}，服务端那边打开的
	 * 仍是玩家自己的 {@code InventoryMenu}；两者的槽位布局完全不同，所以不能直接
	 * 拿界面菜单的 id / 下标去发。这里统一换算到服务端真正持有的那个菜单。</p>
	 */
	private int serverContainerId() {
		Minecraft mc = client();
		if (screen() instanceof CreativeModeInventoryScreen && mc != null && mc.player != null) {
			return mc.player.inventoryMenu.containerId;
		}
		return this.menu.containerId;
	}

	/**
	 * 槽位在服务端菜单里的下标。
	 *
	 * <p>普通容器界面里客户端和服务端的菜单是同一套布局，{@code slot.index} 直接可用。
	 * 创造模式则先由 {@link #inventoryHome(Slot)} 找到真实背包下标，再到服务端
	 * {@code InventoryMenu} 里找对应的槽位：物品选择页的快捷栏（界面下标 45-53）
	 * 换算成 36-44；「物品栏」页的 {@code SlotWrapper} 自带 InventoryMenu 下标，
	 * 主背包 / 快捷栏都能正确对上。物品选择器里那 45 个「物品种类」格子、护甲格、
	 * 副手格都不是可收藏的背包格，返回 -1，让 Alt+左键 / 右键保持原版的取物行为。</p>
	 */
	private int serverSlotId(Slot slot) {
		if (!(screen() instanceof CreativeModeInventoryScreen)) {
			return slot.index;
		}
		int home = inventoryHome(slot);
		if (home < 0) {
			return -1;
		}
		Minecraft mc = client();
		if (mc == null || mc.player == null) {
			return -1;
		}
		for (Slot candidate : mc.player.inventoryMenu.slots) {
			if (SlotRules.inventoryIndex(candidate) == home) {
				return candidate.index;
			}
		}
		return -1;
	}

	/**
	 * 这个槽位对应的玩家背包下标（0-8 快捷栏、9-35 主背包），不是真背包格时返回 -1。
	 *
	 * <p>普通容器界面直接用 {@link SlotRules#inventoryIndex(Slot)}。创造模式要小心：
	 * 「物品栏」页会把服务端 {@code InventoryMenu} 的槽位包成
	 * {@code SlotWrapper(target, inventoryMenuIndex, x, y)} —— 包装槽把自己的
	 * {@code containerSlot} 填成了 <b>InventoryMenu 下标</b>，不是真正背包下标，所以
	 * 直接问 {@code SlotRules} 会把护甲格 5-8 误认成快捷栏 5-8，还会把真正的快捷栏
	 * 包装槽（InventoryMenu 下标 36-44）判成 -1。这里按创造模式的两种页签分开换算：</p>
	 *
	 * <ul>
	 *   <li><b>「物品栏」页</b>：包装槽是直接 {@code menu.slots.add(...)} 塞进去的，
	 *       没走 {@code addSlot}，所以 {@code slot.index} 恒为 0；真正可靠的是
	 *       {@code getContainerSlot()}（构造时填入的 InventoryMenu 下标）。
	 *       0-4 合成、5-8 护甲、45 副手都不是归位对象；9-35 是主背包，
	 *       36-44 是快捷栏（减 36 得到背包 0-8）；</li>
	 *   <li><b>物品选择页</b>：0-44 是物品种类（容器不是 {@link Inventory}，自然排除），
	 *       45-53 是普通 {@code Slot}（走过 {@code addSlot}，index 45-53），
	 *       {@code containerSlot} 就是背包 0-8。</li>
	 * </ul>
	 */
	private int inventoryHome(Slot slot) {
		if (!(screen() instanceof CreativeModeInventoryScreen)) {
			return SlotRules.inventoryIndex(slot);
		}
		if (slot == null || slot.isFake() || !(slot.container instanceof Inventory)) {
			return -1;
		}
		int containerSlot = slot.getContainerSlot();
		// 「物品栏」页的包装槽：containerSlot 是 InventoryMenu 下标 9-44。
		if (containerSlot >= 9 && containerSlot <= 35) {
			// 主背包
			return containerSlot;
		}
		if (containerSlot >= 36 && containerSlot <= 44) {
			// 快捷栏
			return containerSlot - 36;
		}
		// 物品选择页底部的快捷栏是普通 Slot：index 45-53、containerSlot 0-8。
		// 「物品栏」页的护甲包装槽 containerSlot 5-8 但 index 为 0，会被这里排除。
		if (slot.index >= 45 && slot.index <= 53
				&& containerSlot >= 0 && containerSlot <= SlotRules.HOTBAR_LAST) {
			return containerSlot;
		}
		// 0-4 合成格、5-8 护甲、45 副手、以及不是背包的槽位
		return -1;
	}

	/** 指针下的这一格能不能被 Alt+左键收藏（和发包条件保持一致）。 */
	private boolean isFavoriteTarget(Slot slot) {
		if (slot == null || slot.getItem().isEmpty()) {
			return false;
		}
		if (screen() instanceof CreativeModeInventoryScreen) {
			return inventoryHome(slot) >= 0;
		}
		return true;
	}

	private Slot slotAt(double mouseX, double mouseY) {
		AbstractContainerMenu menu = this.menu;
		if (menu == null) {
			return null;
		}
		for (Slot slot : menu.slots) {
			if (slot.isActive() && !slot.isFake()
					&& isHovering(slot.x, slot.y, 16, 16, mouseX, mouseY)) {
				return slot;
			}
		}
		return null;
	}
}