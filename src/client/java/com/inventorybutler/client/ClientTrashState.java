package com.inventorybutler.client;

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

	/**
	 * 退出存档时清掉。
	 *
	 * <p>服务端那边玩家一断线就把垃圾桶清空了，客户端这份镜像要是不跟着清，
	 * 换存档之后按钮上会挂着一个上一个存档留下的物品图标 —— 看着能「取回」，按下去却没反应。</p>
	 */
	public static void clear() {
		stack = ItemStack.EMPTY;
	}
}
