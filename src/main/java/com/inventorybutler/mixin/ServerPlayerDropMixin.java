package com.inventorybutler.mixin;

import com.inventorybutler.FavoriteStacks;
import com.inventorybutler.ModConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 服务端侧的「收藏物品丢不掉」。
 *
 * <p>和客户端的 {@code LocalPlayerDropMixin} 对称：这里拦的是
 * {@link ServerPlayer#drop(boolean)}，也就是服务器处理
 * {@code ServerboundPlayerActionPacket(DROP_ITEM / DROP_ALL_ITEMS)} 的入口。
 * 客户端已经拦过一道，这里再拦一道是为了防止「没装本模组的客户端」或者
 * 其它模组直接发包绕过保护。</p>
 *
 * <p>注意别去拦 {@code Player.drop(ItemStack, boolean)}：那时物品已经从背包里被
 * 取出来了，拦下来只会让物品凭空消失。</p>
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerDropMixin {
	@Inject(method = "drop(Z)V", at = @At("HEAD"), cancellable = true)
	private void inventorybutler$blockDroppingFavorite(boolean dropStack, CallbackInfo ci) {
		if (!ModConfig.favoriteEnabled || !ModConfig.favoriteProtectFromDrop) {
			return;
		}
		ServerPlayer self = (ServerPlayer) (Object) this;
		ItemStack selected = self.getInventory().getSelectedItem();
		if (!selected.isEmpty() && FavoriteStacks.isFavorite(selected)) {
			ci.cancel();
		}
	}
}
