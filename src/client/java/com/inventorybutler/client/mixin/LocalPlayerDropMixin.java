package com.inventorybutler.client.mixin;

import com.inventorybutler.FavoriteStacks;
import com.inventorybutler.ModConfig;
import com.inventorybutler.client.ClientFeedback;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 收藏物品「按 Q 丢不掉」—— 界面<b>关着</b>时的那条路。
 *
 * <p>26.x 的丢弃链路是：</p>
 *
 * <pre>
 * Minecraft.handleKeybinds()
 *   -&gt; LocalPlayer.drop(boolean)          &lt;-- 客户端本地预测
 *        - 发 ServerboundPlayerActionPacket(DROP_ITEM)
 *        - Inventory.removeFromSelected(...)  ★ 物品在这一步就已经离开背包了
 *   -&gt; (服务器) ServerPlayer.drop(boolean)  &lt;-- 服务器权威，真正生成掉落物
 * </pre>
 *
 * <p>所以要在 HEAD 直接返回，物品根本不会被取出来。服务端那侧由
 * {@code ServerPlayerDropMixin} 对称地拦住。</p>
 *
 * <p><b>注意这条只覆盖「界面关着」的情况。</b>物品栏界面开着时按 Q，原版走的是
 * {@code AbstractContainerScreen.keyPressed -> slotClicked(..., ContainerInput.THROW)},
 * 跟 {@code drop(boolean)} 完全无关。那条路由 {@code AbstractContainerScreenMixin}
 * （客户端，负责提示）和 {@code AbstractContainerMenuMixin}（服务端，负责拦截）一起管。</p>
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerDropMixin {
	@Inject(method = "drop(Z)Z", at = @At("HEAD"), cancellable = true)
	private void inventorybutler$blockDroppingFavorite(boolean dropStack,
			CallbackInfoReturnable<Boolean> cir) {
		if (!ModConfig.favoriteEnabled || !ModConfig.favoriteProtectFromDrop) {
			return;
		}
		LocalPlayer self = (LocalPlayer) (Object) this;
		ItemStack selected = self.getInventory().getSelectedItem();
		if (selected.isEmpty() || !FavoriteStacks.isFavorite(selected)) {
			return;
		}

		// 返回 false = 这一下 Q 什么都没做（原版对空手按 Q 也是返回 false）。
		cir.setReturnValue(false);
		ClientFeedback.cannotDropFavorite();
	}
}
