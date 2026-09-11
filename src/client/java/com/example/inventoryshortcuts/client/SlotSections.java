package com.example.inventoryshortcuts.client;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * 判断一个槽位属于哪块区域，用来决定「哪些格子允许被丢进垃圾桶」。
 */
public final class SlotSections {
	public enum Section {
		/** 主背包 27 格 */
		PLAYER_MAIN,
		/** 快捷栏 9 格 */
		PLAYER_HOTBAR,
		PLAYER_ARMOR,
		PLAYER_OFFHAND,
		/** 打开的箱子 / 工作台 / 熔炉等外部容器 */
		CONTAINER,
		OTHER
	}

	private SlotSections() {
	}

	public static Section of(Slot slot) {
		if (slot == null) {
			return Section.OTHER;
		}
		if (!(slot.container instanceof Inventory)) {
			return Section.CONTAINER;
		}
		int index = slot.getContainerSlot();
		if (index >= 0 && index <= 8) {
			return Section.PLAYER_HOTBAR;
		}
		if (index >= 9 && index <= 35) {
			return Section.PLAYER_MAIN;
		}
		if (index >= 36 && index <= 39) {
			return Section.PLAYER_ARMOR;
		}
		if (index == 40) {
			return Section.PLAYER_OFFHAND;
		}
		return Section.OTHER;
	}

	/**
	 * 槽位对应的玩家背包下标（0-8 快捷栏，9-35 主背包）。
	 *
	 * <p>不属于玩家背包的槽位（箱子、护甲、副手、合成结果）返回 -1。
	 * 「归位标记」只认这 36 格，因为归位的目标就是玩家自己的背包。</p>
	 */
	public static int playerIndex(Slot slot) {
		Section section = of(slot);
		if (section != Section.PLAYER_MAIN && section != Section.PLAYER_HOTBAR) {
			return -1;
		}
		return slot.getContainerSlot();
	}

	private static boolean acceptsTransfer(Section section) {
		return section == Section.PLAYER_MAIN
				|| section == Section.PLAYER_HOTBAR
				|| section == Section.CONTAINER;
	}

	/**
	 * 这个槽位能不能被丢进垃圾桶 / 参与整理。
	 *
	 * <p>护甲格、副手格、合成结果格（{@code isFake()} 的槽位）都不参与，
	 * 免得把身上的装备或者还没合成的产物误删。</p>
	 */
	public static boolean acceptsTransfer(Slot slot) {
		return slot != null && acceptsTransfer(of(slot)) && slot.isActive() && !slot.isFake();
	}
}
