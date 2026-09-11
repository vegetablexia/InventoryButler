package com.example.inventoryshortcuts.client;

import net.minecraft.world.item.ItemStack;

/**
 * 客户端持有的垃圾桶镜像，仅用于渲染。
 * 真正的数据在服务端（见 {@code TrashHandler}），客户端每次操作后由服务端下发。
 */
public final class ClientTrashState {
	private static ItemStack stack = ItemStack.EMPTY;

	private ClientTrashState() {
	}

	public static ItemStack get() {
		return stack;
	}

	public static void set(ItemStack newStack) {
		stack = newStack == null ? ItemStack.EMPTY : newStack.copy();
	}

	public static boolean isEmpty() {
		return stack.isEmpty();
	}
}
