package com.inventorybutler.client.mixin;

import com.inventorybutler.FavoriteStacks;
import com.inventorybutler.ModConfig;
import com.inventorybutler.client.ClientFeedback;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 收藏物品「按 Q 丢不掉」—— 界面<b>关着</b>时的那条路。
 *
 * <p>26.3 把客户端丢弃的入口从 {@code LocalPlayer.drop(boolean)} 挪到了
 * {@code MultiPlayerGameMode.dropItem(LocalPlayer, boolean)}：</p>
 *
 * <pre>
 * Minecraft.handleKeybinds()
 *   -&gt; options.keyDrop.consumeClick()
 *   -&gt; gameMode.dropItem(player, hasControlDown())     &lt;-- 客户端本地预测
 *        - 发 ServerboundPlayerActionPacket(DROP_ITEM / DROP_ALL_ITEMS)
 *        - Inventory.removeFromSelected(...)            ★ 物品在这一步就已经离开背包了
 *   -&gt; (服务器) ServerPlayer.drop(boolean)              &lt;-- 服务器权威，真正生成掉落物
 * </pre>
 *
 * <p>所以在 HEAD 直接取消，物品根本不会被取出来。服务端那侧由
 * {@code ServerPlayerDropMixin} 对称地拦住。</p>
 *
 * <p><b>注意这条只覆盖「界面关着」的情况。</b>物品栏界面开着时按 Q，原版走的是
 * {@code AbstractContainerScreen.keyPressed -> slotClicked(..., ContainerInput.THROW)},
 * 跟 {@code dropItem} 完全无关。那条路由 {@code AbstractContainerScreenMixin}
 * （客户端，负责提示）和 {@code AbstractContainerMenuMixin}（服务端，负责拦截）一起管。</p>
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeDropMixin {
	@Inject(method = "dropItem(Lnet/minecraft/client/player/LocalPlayer;Z)V", at = @At("HEAD"), cancellable = true)
	private void inventorybutler$blockDroppingFavorite(LocalPlayer player, boolean dropStack, CallbackInfo ci) {
		if (!ModConfig.favoriteEnabled || !ModConfig.favoriteProtectFromDrop) {
			return;
		}
		ItemStack selected = player.getInventory().getSelectedItem();
		if (selected.isEmpty() || !FavoriteStacks.isFavorite(selected)) {
			return;
		}
		ClientFeedback.cannotDropFavorite();
		ci.cancel();
	}
}