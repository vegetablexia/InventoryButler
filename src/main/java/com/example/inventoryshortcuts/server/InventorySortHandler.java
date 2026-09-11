package com.example.inventoryshortcuts.server;

import com.example.inventoryshortcuts.FavoriteStacks;
import com.example.inventoryshortcuts.ModConfig;
import com.example.inventoryshortcuts.network.SortPayload;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一键整理。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>被收藏的物品「钉」在原槽位，不参与排序，也不会被别的物品挤走；</li>
 *   <li>被「归位标记」认领的物品必须落回它的预留格；池子里没有这种物品时，
 *       那一格<b>留空</b>（对应界面上那个半透明图标）；</li>
 *   <li>其余物品先合并同类项，再按「分类 -&gt; 物品 id -&gt; 数量」排序后顺次填回空格；</li>
 *   <li>完全在服务端执行，客户端只发一个请求包，所以联机时也不会出现不同步。</li>
 * </ul>
 */
public final class InventorySortHandler {
	private InventorySortHandler() {
	}

	public static void sort(ServerPlayer player, SortPayload payload) {
		if (!ModConfig.sortEnabled) {
			return;
		}
		AbstractContainerMenu menu = player.containerMenu;
		if (menu == null || menu.containerId != payload.containerId()) {
			return;
		}

		Inventory inv = player.getInventory();
		// 0-8 快捷栏，9-35 主背包。护甲(36-39)和副手(40)不动。
		int start = ModConfig.sortIncludeHotbar ? 0 : 9;
		int end = 35;

		List<Integer> range = new ArrayList<>();
		for (int i = start; i <= end; i++) {
			range.add(i);
		}

		// 「收藏」：钉在原槽位不参与排序
		Set<Integer> pinned = new HashSet<>();
		for (int i : range) {
			if (FavoriteStacks.isFavorite(inv.getItem(i))) {
				pinned.add(i);
			}
		}

		// 「归位标记」：整理的范围内，被标记的物品必须落在它的预留格里。
		// 收藏优先 —— 某一格既被收藏又被标记时，听收藏的（不挪窝）。
		Map<Integer, ItemStack> reserved = new LinkedHashMap<>();
		if (ModConfig.pinEnabled) {
			for (Map.Entry<Integer, ItemStack> entry : PlacementHandler.pinsOf(player).entrySet()) {
				int home = entry.getKey();
				if (home >= start && home <= end && !pinned.contains(home)) {
					reserved.put(home, entry.getValue());
				}
			}
		}

		// 1) 把非锁定槽位的物品全部收进池子并合并同类项
		//
		// 预留格也一起收 —— 它里面的东西同样该参与「合并同类项 + 排序」，
		// 反正第 3 步会把标记的那份精确放回去。
		List<ItemStack> pool = new ArrayList<>();
		for (int i : range) {
			if (pinned.contains(i)) {
				continue;
			}
			ItemStack stack = inv.getItem(i);
			if (!stack.isEmpty()) {
				mergeInto(pool, stack.copy());
			}
			inv.setItem(i, ItemStack.EMPTY);
		}

		// 2) 排序
		pool.sort(Comparator
				.comparingInt(InventorySortHandler::category)
				.thenComparing(s -> BuiltInRegistries.ITEM.getKey(s.getItem()).toString())
				.thenComparing(Comparator.comparingInt(ItemStack::getCount).reversed()));

		// 3) 被标记归位的物品先落回它的预留格。
		//
		// 池子里找不到就空着 —— 这格留空是有意义的：格子上会显示半透明的物品图标，
		// 告诉玩家「这格是给它的」。这份「空」正是归位标记想表达的东西。
		for (Map.Entry<Integer, ItemStack> entry : reserved.entrySet()) {
			Iterator<ItemStack> it = pool.iterator();
			while (it.hasNext()) {
				ItemStack candidate = it.next();
				if (PlacementHandler.sameItem(candidate, entry.getValue())) {
					inv.setItem(entry.getKey(), candidate);
					it.remove();
					break;
				}
			}
		}

		// 4) 其余物品顺次填回空格（一个物品栈最多 maxStackSize，超出就拆成多格）
		List<Integer> free = new ArrayList<>();
		for (int i : range) {
			if (!pinned.contains(i) && !reserved.containsKey(i)) {
				free.add(i);
			}
		}
		int cursor = 0;
		for (ItemStack stack : pool) {
			int remaining = stack.getCount();
			int max = Math.max(1, stack.getMaxStackSize());
			while (remaining > 0 && cursor < free.size()) {
				int take = Math.min(remaining, max);
				ItemStack piece = stack.copy();
				piece.setCount(take);
				inv.setItem(free.get(cursor), piece);
				cursor++;
				remaining -= take;
			}
			if (cursor >= free.size()) {
				break;
			}
		}
		while (cursor < free.size()) {
			inv.setItem(free.get(cursor), ItemStack.EMPTY);
			cursor++;
		}

		inv.setChanged();
		menu.broadcastFullState();
	}

	/** 把 stack 尽量并进池子里已有的同类栈，剩下的作为新条目加进去。 */
	private static void mergeInto(List<ItemStack> pool, ItemStack stack) {
		for (ItemStack existing : pool) {
			if (stack.isEmpty()) {
				return;
			}
			int max = Math.max(1, existing.getMaxStackSize());
			if (ItemStack.isSameItemSameComponents(existing, stack) && existing.getCount() < max) {
				int move = Math.min(max - existing.getCount(), stack.getCount());
				existing.grow(move);
				stack.shrink(move);
			}
		}
		if (!stack.isEmpty()) {
			pool.add(stack);
		}
	}

	/** 排序用的粗分类，只求「同类物品聚在一起」。 */
	private static int category(ItemStack stack) {
		Item item = stack.getItem();
		Identifier key = BuiltInRegistries.ITEM.getKey(item);
		String path = key == null ? "" : key.getPath();

		if (path.endsWith("_helmet") || path.endsWith("_chestplate")
				|| path.endsWith("_leggings") || path.endsWith("_boots")) {
			return 30;
		}
		if (path.endsWith("_sword") || path.endsWith("_axe") || path.endsWith("_pickaxe")
				|| path.endsWith("_shovel") || path.endsWith("_hoe") || path.endsWith("_bow")
				|| path.endsWith("_crossbow") || path.endsWith("_trident") || path.endsWith("_shield")
				|| path.endsWith("_fishing_rod") || path.endsWith("_shears") || path.endsWith("_flint_and_steel")) {
			return 20;
		}
		if (path.endsWith("_potion") || path.equals("potion") || path.endsWith("_bucket")
				|| path.endsWith("_stew") || path.endsWith("_soup")) {
			return 40;
		}
		if (item instanceof BlockItem) {
			return 10;
		}
		return 50;
	}
}
