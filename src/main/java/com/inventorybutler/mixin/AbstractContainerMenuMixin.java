package com.inventorybutler.mixin;

import com.inventorybutler.FavoriteStacks;
import com.inventorybutler.ModConfig;
import com.inventorybutler.server.PlacementHandler;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「收藏物丢不掉 / 搬不走」的防线 —— 拦在容器菜单的点击入口。
 *
 * <p>上一版只在 {@code LocalPlayer.drop} / {@code ServerPlayer.drop} 上拦，那只能覆盖
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
 *   <li>{@code QUICK_MOVE}（Shift+点击）：玩家背包里的收藏物 → 不许被快捷搬进容器，
 *       <b>从容器里搬出来不受限</b>（那是拿回自己东西，方向是安全的）。</li>
 * </ul>
 */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {
	@Shadow
	@Final
	public NonNullList<Slot> slots;

	@Shadow
	public abstract ItemStack getCarried();

	@Inject(method = "clicked(IILnet/minecraft/world/inventory/ContainerInput;Lnet/minecraft/world/entity/player/Player;)V",
			at = @At("HEAD"), cancellable = true)
	private void inventoryshortcuts$protectFavorites(int slotId, int button, ContainerInput input,
			Player player, CallbackInfo ci) {
		if (!ModConfig.favoriteEnabled || !ModConfig.favoriteProtectFromDrop) {
			return;
		}

		if (input == ContainerInput.THROW) {
			if (slotId >= 0 && slotId < slots.size()
					&& FavoriteStacks.isFavorite(slots.get(slotId).getItem())) {
				ci.cancel();
			}
			return;
		}

		// Shift 快速移动：只拦「背包 → 容器」这个方向。
		//
		// 判断依据是点击的槽位装在谁的容器里：玩家背包槽（含护甲格）的
		// slot.container 都是 Inventory，箱子/熔炉这些的则是别的容器。
		// 反方向（容器 → 背包）是「拿回东西」，永远放行。
		if (input == ContainerInput.QUICK_MOVE
				&& slotId >= 0 && slotId < slots.size()
				&& slots.get(slotId).container instanceof Inventory
				&& FavoriteStacks.isFavorite(slots.get(slotId).getItem())) {
			ci.cancel();
			return;
		}

		// -999 == SLOT_CLICKED_OUTSIDE：点到界面外 = 把光标上的东西丢出去。
		if (slotId == AbstractContainerMenu.SLOT_CLICKED_OUTSIDE
				&& FavoriteStacks.isFavorite(getCarried())) {
			ci.cancel();
		}
	}

	/**
	 * Shift 快速移动之后的归位。
	 *
	 * <p>「Shift 从箱子取出到背包」这条路径<b>不经过 {@code Inventory.add}</b> —— 原版是
	 * 直接在容器之间 {@code slot.set} 搬的（{@code moveItemStackTo}），所以
	 * {@code InventoryAddMixin} 那个钩子抓不到它，得在菜单的点击入口另接一道。</p>
	 *
	 * <p>放在 {@code TAIL} 而不是 {@code HEAD}：必须等原版把东西真正搬完，
	 * 我们才知道背包最后长什么样，才有得可归位。</p>
	 */
	@Inject(method = "clicked(IILnet/minecraft/world/inventory/ContainerInput;Lnet/minecraft/world/entity/player/Player;)V",
			at = @At("TAIL"))
	private void inventoryshortcuts$reflowPinnedAfterQuickMove(int slotId, int button, ContainerInput input,
			Player player, CallbackInfo ci) {
		if (input == ContainerInput.QUICK_MOVE && player instanceof ServerPlayer serverPlayer) {
			PlacementHandler.reflow(serverPlayer);
		}
	}
}
