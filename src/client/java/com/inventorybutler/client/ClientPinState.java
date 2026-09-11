package com.inventorybutler.client;

import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端持有的「归位标记」副本。
 *
 * <p>和服务端的垃圾桶一样：服务端才是权威，这里只是拿一份用来画界面的镜像。
 * 客户端拿着它不做任何判断，也就不会出现「客户端以为自己标上了、服务端其实没标」的情况。</p>
 *
 * <p>读写都发生在客户端主线程上（收包走 {@code context.client().execute(...)}，
 * 渲染走渲染线程那条主线程），所以用普通 {@link HashMap} 就够了。</p>
 */
public final class ClientPinState {
	/** 背包下标 -&gt; 那一格留给的物品（数量恒为 1，只用来取图标）。 */
	private static final Map<Integer, ItemStack> PINS = new HashMap<>();

	private ClientPinState() {
	}

	/** 用服务端推来的全量快照替换本地副本。 */
	public static void set(List<Integer> slots, List<ItemStack> items) {
		PINS.clear();
		int size = Math.min(slots.size(), items.size());
		for (int i = 0; i < size; i++) {
			ItemStack stack = items.get(i);
			if (stack != null && !stack.isEmpty()) {
				PINS.put(slots.get(i), stack);
			}
		}
	}

	/** 这个背包下标预留给了什么物品；没有标记就返回空栈。 */
	public static ItemStack at(int inventoryIndex) {
		return PINS.getOrDefault(inventoryIndex, ItemStack.EMPTY);
	}

	public static void clear() {
		PINS.clear();
	}
}
