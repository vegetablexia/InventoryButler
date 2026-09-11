package com.inventorybutler.server;

import com.inventorybutler.FavoriteStacks;
import com.inventorybutler.InventoryButler;
import com.inventorybutler.ModConfig;
import com.inventorybutler.network.FavoriteTogglePayload;
import com.inventorybutler.network.MessagePayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** 收藏切换的服务端权威实现。 */
public final class FavoriteHandler {
	private FavoriteHandler() {
	}

	public static void toggle(ServerPlayer player, FavoriteTogglePayload payload) {
		if (!ModConfig.favoriteEnabled) {
			return;
		}
		AbstractContainerMenu menu = player.containerMenu;
		if (menu == null || menu.containerId != payload.containerId()) {
			return;
		}
		int slotId = payload.slotId();
		if (slotId < 0 || slotId >= menu.slots.size()) {
			return;
		}
		Slot slot = menu.getSlot(slotId);
		ItemStack stack = slot.getItem();
		if (stack.isEmpty()) {
			return;
		}
		boolean nowFavorite = FavoriteStacks.toggle(stack);
		slot.setChanged();
		menu.broadcastChanges();
		// 提示改走自定义包：动作栏文字在物品栏界面打开时看不见（HUD 不渲染），
		// 客户端收到后会画在界面上方。收藏切换恰恰只发生在界面里。
		ServerPlayNetworking.send(player, new MessagePayload(nowFavorite
				? "inventorybutler.message.favorite.on"
				: "inventorybutler.message.favorite.off"));
		InventoryButler.LOGGER.debug("玩家 {} 把 {} {} 了",
				player.getGameProfile().name(),
				BuiltInRegistries.ITEM.getKey(stack.getItem()),
				nowFavorite ? "收藏" : "取消收藏");
	}
}
