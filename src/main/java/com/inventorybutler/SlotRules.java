package com.inventorybutler;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * 槽位规则 —— 客户端与服务端共用的「这一格是谁的」判断。
 *
 * <p>以前这些判断分散在客户端旧的 {@code SlotSections}（已删除）和服务端的
 * {@code PlacementHandler#inventoryIndex} 两份实现里，规则一旦对不上，就会出现
 * 「客户端能做、服务端拒绝」或者反过来。现在集中到这一份，两端引用同一套规则。</p>
 *
 * <p>玩家背包下标（{@link Slot#getContainerSlot()}）的约定：0-8 快捷栏，9-35 主背包，
 * 36-39 护甲，40 副手。归位标记与整理只认 0-35。</p>
 */
public final class SlotRules {
	/** 快捷栏最后一格的下标。 */
	public static final int HOTBAR_LAST = 8;
	/** 主背包最后一格的下标。 */
	public static final int MAIN_LAST = 35;
	/** 归位标记认的格子数：快捷栏 9 + 主背包 27。 */
	public static final int INVENTORY_SLOTS = 36;

	private SlotRules() {
	}

	/**
	 * 槽位对应的玩家背包下标（0-8 快捷栏，9-35 主背包）。
	 *
	 * <p>不属于玩家背包的槽位（箱子、护甲、副手、合成结果，以及模组用
	 * {@link Slot#isFake()} 标出来的纯展示槽）返回 -1。
	 * 「归位标记」只认这 36 格，因为归位的目标就是玩家自己的背包。</p>
	 */
	public static int inventoryIndex(Slot slot) {
		if (slot == null || slot.isFake() || !(slot.container instanceof Inventory)) {
			return -1;
		}
		int index = slot.getContainerSlot();
		return (index >= 0 && index < INVENTORY_SLOTS) ? index : -1;
	}

	/** 槽位装的是不是玩家自己的背包（含护甲、副手），用于判断「搬进容器」的方向。 */
	public static boolean isPlayerInventory(Slot slot) {
		return slot != null && slot.container instanceof Inventory;
	}
}