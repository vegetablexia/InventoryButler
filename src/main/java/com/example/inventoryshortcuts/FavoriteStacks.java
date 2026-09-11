package com.example.inventoryshortcuts;

import net.minecraft.util.Unit;
import net.minecraft.world.item.ItemStack;

/** 收藏状态的读写工具。 */
public final class FavoriteStacks {
	private FavoriteStacks() {
	}

	public static boolean isFavorite(ItemStack stack) {
		return stack != null && !stack.isEmpty() && stack.has(ModComponents.FAVORITE);
	}

	public static void setFavorite(ItemStack stack, boolean favorite) {
		if (stack == null || stack.isEmpty()) {
			return;
		}
		if (favorite) {
			stack.set(ModComponents.FAVORITE, Unit.INSTANCE);
		} else {
			stack.remove(ModComponents.FAVORITE);
		}
	}

	/** @return 切换之后是否处于「已收藏」状态 */
	public static boolean toggle(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		boolean next = !isFavorite(stack);
		setFavorite(stack, next);
		return next;
	}
}
