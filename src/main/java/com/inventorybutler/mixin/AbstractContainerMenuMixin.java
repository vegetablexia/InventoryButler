package com.inventorybutler.mixin;

import com.inventorybutler.FavoriteStacks;
import com.inventorybutler.ModConfig;
import com.inventorybutler.PinAccess;
import com.inventorybutler.SlotRules;
import com.inventorybutler.network.MessagePayload;
import com.inventorybutler.server.PlacementHandler;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * 「收藏物丢不掉 / 搬不走」的防线 —— 拦在容器菜单的点击入口。
 *
 * <p>上一版只在客户端丢弃入口 / {@code ServerPlayer.drop} 上拦，那只能覆盖
 * <b>界面关着</b>时的 Q 键。物品栏界面打开时按 Q，原版走的是另一条路：</p>
 *
 * <pre>
 * AbstractContainerScreen.keyPressed(KeyEvent)
 *   -&gt; options.keyDrop.matches(event)
 *   -&gt; slotClicked(hoveredSlot, index, ctrl?1:0, ContainerInput.THROW)   ← 跟 drop(boolean) 无关
 *   -&gt; (服务端) AbstractContainerMenu.clicked(...) -&gt; doClick(...)
 *        - slot.remove(count)                 ★ 物品从槽位里被拿走
 *        - player.drop(stack, false)          ★ 生成掉落物
 * </pre>
 *
 * <p>所以在菜单入口再拦一道：</p>
 * <ul>
 *   <li>{@code THROW}（界面打开时按 Q / Ctrl+Q）：目标是收藏物 → 整个点击丢弃；</li>
 *   <li>{@code SLOT_CLICKED_OUTSIDE}（点到界面外）：光标上是收藏物 → 丢弃；</li>
 * </ul>
 *
 * <p><b>Shift 快捷移动</b>不能只看「点的是哪个格」—— 同一格在背包界面里是「在背包内挪位置」，
 * 在箱子界面里却是「搬进箱子」。上一版把玩家背包里的收藏物一律拦下，连背包内换格都动不了。
 * 现在把判断下移到 {@link AbstractContainerMenu#moveItemStackTo}：只有<b>目的地整段都是
 * 玩家自己的背包格</b>时才放行；混进任何一个外部容器格（哪怕同一段里还有背包格）就取消。</p>
 */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {
	@Shadow
	@Final
	public NonNullList<Slot> slots;

	@Shadow
	public abstract ItemStack getCarried();

	/**
	 * 本次 {@code clicked} 调用是否还在进行中。
	 *
	 * <p>{@code moveItemStackTo} 不知道自己在哪次点击里、也不知道玩家是谁；只有把它和
	 * {@code clicked} 的调用区间对上，才能在拦截后由 TAIL 给玩家发一条提示。
	 * 不在这段区间里的移动（自动合成、别的 mod 的代劳）不会被误报成「玩家操作被拦」。</p>
	 */
	@Unique
	private boolean inventorybutler$clickInProgress;

	/** 本次点击里有没有发生过「收藏物被拦下、不让搬进容器」。 */
	@Unique
	private boolean inventorybutler$quickMoveBlocked;

	/**
	 * 本次点击的玩家。客户端预测和服务端执行都会走 {@code clicked}，
	 * 归位快移需要知道读哪一份标记（服务端按 UUID，客户端读镜像）。
	 */
	@Unique
	private Player inventorybutler$clickPlayer;

	/** 本次点击是不是 Shift 快移。 */
	@Unique
	private boolean inventorybutler$quickMoveClick;

	/** 本次点击的来源槽位（菜单下标），-1 表示点到界面外等无效来源。 */
	@Unique
	private int inventorybutler$clickSourceSlot = -1;

	@Inject(method = "clicked(IILnet/minecraft/world/inventory/ContainerInput;Lnet/minecraft/world/entity/player/Player;)V",
			at = @At("HEAD"), cancellable = true)
	private void inventorybutler$protectFavorites(int slotId, int button, ContainerInput input,
			Player player, CallbackInfo ci) {
		inventorybutler$clickInProgress = false;
		inventorybutler$quickMoveBlocked = false;

		// 记下这次点击的上下文。moveItemStackTo 里要靠它判断「这是不是一次 Shift 快移、
		// 从哪一格出发」。客户端预测和服务端执行读的是同一套规则，落位才能一致。
		inventorybutler$clickPlayer = player;
		inventorybutler$quickMoveClick = input == ContainerInput.QUICK_MOVE;
		inventorybutler$clickSourceSlot = slotId;

		if (ModConfig.favoriteEnabled && ModConfig.favoriteProtectFromDrop) {
			if (input == ContainerInput.THROW) {
				if (slotId >= 0 && slotId < slots.size()
						&& FavoriteStacks.isFavorite(slots.get(slotId).getItem())) {
					ci.cancel();
					return;
				}
			} else if (slotId == AbstractContainerMenu.SLOT_CLICKED_OUTSIDE
					&& FavoriteStacks.isFavorite(getCarried())) {
				// -999 == SLOT_CLICKED_OUTSIDE：点到界面外 = 把光标上的东西丢出去
				ci.cancel();
				return;
			}
		}

		// 其余点击（含 Shift 快移）继续走原版；收藏的方向判断在 moveItemStackTo 里做。
		// 即使收藏保护关着也要置位 —— 归位快移不依赖收藏开关。
		inventorybutler$clickInProgress = true;
	}

	/**
	 * Shift 快捷移动的收藏保护：<b>只拦「搬进外部容器」这个方向</b>。
	 *
	 * <p>原版每个菜单的 {@code quickMoveStack} 最终都会调 {@code moveItemStackTo(stack, start, end, ...)}，
	 * 而这里的 {@code [start, end)} 就是这次搬运的目的地。判定规则：</p>
	 * <ul>
	 *   <li>目的地整段都是玩家背包格（{@code slot.container instanceof Inventory}）才放行
	 *       —— 背包 / 快捷栏 / 护甲格都属于玩家自己，挪位置是玩家的权利；</li>
	 *   <li>只要混进一个外部容器格（箱子、熔炉、铁砧输入格……）就取消，物品留在原处。
	 *       原版菜单的目的地区间都是纯的；这条主要防模组菜单把两种格混进一段；</li>
	 *   <li>反方向（容器 -&gt; 背包）的目的地全是玩家背包格，自然放行 —— 那是拿回自己的东西。</li>
	 * </ul>
	 *
	 * <p>返回 {@code false} 等价于「这一格放不下」，原版 {@code quickMoveStack} 会就此收手，
	 * 不会再去试别的目的地，所以物品不会被搬走一半。客户端也会跑这段代码（客户端预测），
	 * 所以界面里的表现和服务端一致；提示由 {@code clicked} 的 TAIL 在服务端发。</p>
	 */
	@Inject(method = "moveItemStackTo(Lnet/minecraft/world/item/ItemStack;IIZ)Z",
			at = @At("HEAD"), cancellable = true)
	private void inventorybutler$blockMovingFavoriteIntoContainer(ItemStack stack, int startIndex, int endIndex,
			boolean reverse, CallbackInfoReturnable<Boolean> cir) {
		if (!inventorybutler$clickInProgress
				|| !ModConfig.favoriteEnabled || !ModConfig.favoriteProtectFromDrop
				|| stack == null || stack.isEmpty() || !FavoriteStacks.isFavorite(stack)) {
			return;
		}
		if (startIndex < 0 || endIndex > slots.size() || startIndex >= endIndex) {
			return;
		}
		// 目的地必须全部是玩家自己的背包格（含护甲、副手）才放行。
		// 以前是「只要有一个玩家格就放行」，遇到模组菜单把「容器 + 背包」混进
		// 同一段目的地区间时，收藏物仍可能被挪进容器 —— 收藏保护就漏了。
		for (int i = startIndex; i < endIndex; i++) {
			if (!SlotRules.isPlayerInventory(slots.get(i))) {
				inventorybutler$quickMoveBlocked = true;
				cir.setReturnValue(false);
				return;
			}
		}
	}

	/**
	 * Shift 快移的「直接归位」。
	 *
	 * <p>原版 Shift 快移会把物品塞进目的地里第一个能放的格子（箱子 -&gt; 背包时从
	 * 快捷栏最后一格开始倒着找）。如果这件物品有归位标记，服务端随后会在
	 * {@code clicked} 的 TAIL 里把它换回预留格 —— 但<b>客户端预测只看到原版的落点</b>
	 * （26.3 的 {@code MultiPlayerGameMode.handleContainerInput} 会先在本地跑一遍
	 * {@code menu.clicked}），于是物品会先在默认格闪一下，再被服务端纠正。</p>
	 *
	 * <p>这里让客户端预测和服务端在<b>同一个位置</b>（{@code moveItemStackTo} 的入口）
	 * 就按预留格落位：两边读同一套规则、算出同样的结果，客户端预测直接是最终状态，
	 * 就没有中间态可闪了。背包内部的 Shift 快移（主背包 ↔ 快捷栏、护甲 → 背包）
	 * 也走这里 —— 快移是自动操作，应当听归位标记的。</p>
	 *
	 * <p>只在两种条件下接管：目的地整段都是玩家背包，并且<b>整叠</b>能完整放进预留格
	 * （空格或同物品同组件且余量足够）。其余情况交给原版，TAIL 的 reflow 兜底；
	 * 从预留格自己往别处 Shift 移出也不拦，否则物品会变成「放进去拿不出来」。</p>
	 */
	@Inject(method = "moveItemStackTo(Lnet/minecraft/world/item/ItemStack;IIZ)Z",
			at = @At("HEAD"), cancellable = true)
	private void inventorybutler$quickMovePinnedHome(ItemStack stack, int startIndex, int endIndex,
			boolean reverse, CallbackInfoReturnable<Boolean> cir) {
		if (!ModConfig.pinEnabled || !inventorybutler$quickMoveClick
				|| inventorybutler$clickPlayer == null) {
			return;
		}
		if (stack == null || stack.isEmpty()
				|| startIndex < 0 || endIndex > slots.size() || startIndex >= endIndex) {
			return;
		}
		// 收藏物不自动搬进预留格 ——「收藏」的优先级高于「归位」。
		// 收藏总开关关掉时这条一起失效，和 README 的「所有收藏保护一起失效」一致。
		if (ModConfig.favoriteEnabled && FavoriteStacks.isFavorite(stack)) {
			return;
		}
		// 只接管「目的地全是玩家自己的背包格」的搬运：往容器里搬（包括把东西 Shift 进箱子）
		// 必须保持原版行为，否则玩家就放不进去了。
		for (int i = startIndex; i < endIndex; i++) {
			if (!SlotRules.isPlayerInventory(slots.get(i))) {
				return;
			}
		}
		Map<Integer, ItemStack> pins = PinAccess.pinsFor(inventorybutler$clickPlayer);
		if (pins.isEmpty()) {
			return;
		}
		int home = -1;
		for (Map.Entry<Integer, ItemStack> entry : pins.entrySet()) {
			if (PlacementHandler.sameItem(stack, entry.getValue())) {
				home = entry.getKey();
				break;
			}
		}
		if (home < 0 || home >= SlotRules.INVENTORY_SLOTS) {
			return;
		}
		// 从预留格自己往别处 Shift 移出：尊重玩家主动挪走的意图。
		if (inventorybutler$clickSourceSlot >= 0 && inventorybutler$clickSourceSlot < slots.size()
				&& SlotRules.inventoryIndex(slots.get(inventorybutler$clickSourceSlot)) == home) {
			return;
		}
		Slot homeSlot = null;
		for (Slot candidate : slots) {
			if (SlotRules.inventoryIndex(candidate) == home) {
				homeSlot = candidate;
				break;
			}
		}
		if (homeSlot == null || !homeSlot.isActive() || homeSlot.isFake()) {
			return;
		}

		ItemStack homeStack = homeSlot.getItem();
		if (homeStack.isEmpty()) {
			if (!homeSlot.mayPlace(stack)) {
				return;
			}
			int max = homeSlot.getMaxStackSize(stack);
			if (stack.getCount() > max) {
				// 整叠放不下预留格：交给原版先放一部分，TAIL 的 reflow 再兜底。
				return;
			}
			ItemStack piece = stack.split(stack.getCount());
			homeSlot.setByPlayer(piece);
			homeSlot.setChanged();
			cir.setReturnValue(true);
			return;
		}
		if ((ModConfig.favoriteEnabled && FavoriteStacks.isFavorite(homeStack))
				|| !ItemStack.isSameItemSameComponents(homeStack, stack)) {
			return;
		}
		int max = homeSlot.getMaxStackSize(homeStack);
		int total = homeStack.getCount() + stack.getCount();
		if (total > max) {
			return;
		}
		homeStack.setCount(total);
		stack.setCount(0);
		homeSlot.setChanged();
		cir.setReturnValue(true);
	}

	/**
	 * 点击处理完之后的两件收尾：
	 * <ul>
	 *   <li>快移被收藏保护拦下时，在服务端说明原因（客户端那侧的预测也已被拦，物品不会动，
	 *       只是「按了没反应」需要一个解释）；</li>
	 *   <li>真正完成了一次「从外部容器拿出物品」的 Shift 快移后触发归位 —— 这条路径
	 *       <b>不经过 {@code Inventory.add}</b>，原版是直接在容器之间 {@code slot.set} 搬的
	 *       （{@code moveItemStackTo}），所以 {@code InventoryAddMixin} 抓不到，得在这里另接一道。
	 *       来源格本来就在玩家背包里的 Shift 快移不触发（那是玩家自己挪位置）。</li>
	 * </ul>
	 *
	 * <p>放在 {@code TAIL} 而不是 {@code HEAD}：必须等原版把东西真正搬完，
	 * 我们才知道背包最后长什么样，才有得可归位。</p>
	 */
	@Inject(method = "clicked(IILnet/minecraft/world/inventory/ContainerInput;Lnet/minecraft/world/entity/player/Player;)V",
			at = @At("TAIL"))
	private void inventorybutler$afterClick(int slotId, int button, ContainerInput input,
			Player player, CallbackInfo ci) {
		boolean blocked = inventorybutler$quickMoveBlocked;
		inventorybutler$clickInProgress = false;
		inventorybutler$quickMoveBlocked = false;
		inventorybutler$clickPlayer = null;
		inventorybutler$quickMoveClick = false;
		inventorybutler$clickSourceSlot = -1;

		if (input != ContainerInput.QUICK_MOVE || !(player instanceof ServerPlayer serverPlayer)) {
			return;
		}
		if (blocked) {
			ServerPlayNetworking.send(serverPlayer,
					new MessagePayload("inventorybutler.message.favorite.cannotMove"));
			return;
		}
		// 只有「物品从外部容器进入背包」才需要归位。来源格是玩家自己背包的 Shift 快移
		// 属于玩家手动挪位置 —— 如果再 reflow，刚被挪出预留格的物品会被立刻吸回去，
		// 和「手动摆放永远优先」冲突。
		if (slotId < 0 || slotId >= slots.size()
				|| SlotRules.isPlayerInventory(slots.get(slotId))) {
			return;
		}
		PlacementHandler.reflow(serverPlayer);
	}
}
