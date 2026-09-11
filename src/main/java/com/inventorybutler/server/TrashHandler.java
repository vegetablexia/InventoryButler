package com.inventorybutler.server;

import com.inventorybutler.FavoriteStacks;
import com.inventorybutler.InventoryShortcuts;
import com.inventorybutler.ModConfig;
import com.inventorybutler.network.MessagePayload;
import com.inventorybutler.network.TrashPayload;
import com.inventorybutler.network.TrashSyncPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 垃圾桶。
 *
 * <p>和原版行为不同：垃圾桶会「记住」最后一次被销毁的那一个物品栈，
 * 玩家可以在关闭界面前把它取回来 —— 相当于一次撤销，这也是泰拉瑞亚的玩法。</p>
 *
 * <p>权威数据放在服务端，客户端只拿到一份用于显示的副本，
 * 所以既不会凭空复制物品，也不会出现客户端自己删了半天服务端还在的情况。</p>
 */
public final class TrashHandler {
	private static final Map<UUID, ItemStack> TRASH = new HashMap<>();

	private TrashHandler() {
	}

	public static void handle(ServerPlayer player, TrashPayload payload) {
		if (!ModConfig.trashEnabled) {
			return;
		}
		AbstractContainerMenu menu = player.containerMenu;
		if (menu == null || menu.containerId != payload.containerId()) {
			return;
		}

		switch (payload.action()) {
			case TrashPayload.ACTION_SLOT -> fromSlot(player, menu, payload.slotId());
			case TrashPayload.ACTION_CARRIED -> fromCarried(player, menu);
			case TrashPayload.ACTION_RECLAIM -> reclaim(player, menu);
			default -> InventoryShortcuts.LOGGER.warn("未知的垃圾桶动作: {}", payload.action());
		}
	}

	private static void fromSlot(ServerPlayer player, AbstractContainerMenu menu, int slotId) {
		if (slotId < 0 || slotId >= menu.slots.size()) {
			return;
		}
		Slot slot = menu.getSlot(slotId);
		ItemStack stack = slot.getItem();
		if (stack.isEmpty()) {
			return;
		}
		if (rejectIfProtected(player, stack)) {
			return;
		}
		// 旧内容就此销毁
		setTrash(player, stack.copy());
		slot.set(ItemStack.EMPTY);
		slot.setChanged();
		menu.broadcastChanges();
	}

	private static void fromCarried(ServerPlayer player, AbstractContainerMenu menu) {
		ItemStack carried = menu.getCarried();
		if (carried.isEmpty()) {
			return;
		}
		if (rejectIfProtected(player, carried)) {
			return;
		}
		setTrash(player, carried.copy());
		menu.setCarried(ItemStack.EMPTY);
		menu.broadcastChanges();
	}

	private static void reclaim(ServerPlayer player, AbstractContainerMenu menu) {
		ItemStack trashed = getTrash(player);
		if (trashed.isEmpty()) {
			return;
		}
		if (!menu.getCarried().isEmpty()) {
			// 光标上有东西时不接收，避免出现「合并到光标」这种容易出 bug 的分支
			return;
		}
		menu.setCarried(trashed.copy());
		setTrash(player, ItemStack.EMPTY);
		menu.broadcastChanges();
	}

	private static boolean rejectIfProtected(ServerPlayer player, ItemStack stack) {
		if (ModConfig.favoriteProtectFromTrash && FavoriteStacks.isFavorite(stack)) {
			// 提示走自定义包：这个提示只在物品栏界面里触发，动作栏文字看不见
			ServerPlayNetworking.send(player,
					new MessagePayload("inventorybutler.message.favorite.protected"));
			return true;
		}
		return false;
	}

	public static ItemStack getTrash(ServerPlayer player) {
		return TRASH.getOrDefault(player.getUUID(), ItemStack.EMPTY);
	}

	private static void setTrash(ServerPlayer player, ItemStack stack) {
		if (stack.isEmpty()) {
			TRASH.remove(player.getUUID());
		} else {
			TRASH.put(player.getUUID(), stack);
		}
		ServerPlayNetworking.send(player, new TrashSyncPayload(getTrash(player)));
	}

	/** 玩家断开时清空，防止内存泄漏；垃圾桶里的东西本来就等于已经销毁了。 */
	public static void clear(ServerPlayer player) {
		TRASH.remove(player.getUUID());
	}
}
