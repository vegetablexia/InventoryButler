package com.inventorybutler.server;

import com.inventorybutler.FavoriteStacks;
import com.inventorybutler.InventoryButler;
import com.inventorybutler.ModConfig;
import com.inventorybutler.network.MessagePayload;
import com.inventorybutler.network.SortPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
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
 *   <li>被收藏的物品「钉」在原槽位，不参与排序，也不会被别的物品挤走
 *       （把 {@code favoriteProtectFromSort} 关掉后，收藏物也照常参与排序）；</li>
 *   <li>被「归位标记」认领的物品必须落回它的预留格；池子里没有这种物品时，
 *       那一格<b>留空</b>（对应界面上那个半透明图标）；</li>
 *   <li>其余物品先合并同类项，再按「分类 -&gt; 物品 id -&gt; 数量」排序后顺次填回空格；</li>
 *   <li>完全在服务端执行，客户端只发一个请求包，所以联机时也不会出现不同步。</li>
 * </ul>
 *
 * <p><b>不丢东西</b>：整理分成「先算清、再落盘」两步 —— 池子内容和预留格归属全部先算出来，
 * 确认放得下之后才动背包。万一真的放不下（只会出现在异常物品栈上，比如单格数量超过
 * {@code maxStackSize}），宁可整单放弃并记一条日志，也不静默丢掉多余的物品。</p>
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

		// 「收藏」：默认钉在原槽位不参与排序。
		// 关掉 favoriteProtectFromSort 之后，收藏物只是保留星标，照常参与排序。
		Set<Integer> pinned = new HashSet<>();
		if (ModConfig.favoriteEnabled && ModConfig.favoriteProtectFromSort) {
			for (int i : range) {
				if (FavoriteStacks.isFavorite(inv.getItem(i))) {
					pinned.add(i);
				}
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

		// 1) 把非锁定槽位的物品全部收进池子并合并同类项。
		//
		// 预留格也一起收 —— 它里面的东西同样该参与「合并同类项 + 排序」，
		// 反正第 3 步会把标记的那份精确放回去。
		//
		// 这一步只读不写：背包要等第 5 步确认放得下之后才动，
		// 中途放弃也不会留下「一半格子已经清空」的残局。
		List<ItemStack> pool = new ArrayList<>();
		for (int i : range) {
			if (pinned.contains(i)) {
				continue;
			}
			ItemStack stack = inv.getItem(i);
			if (!stack.isEmpty()) {
				mergeInto(pool, stack.copy());
			}
		}

		// 2) 排序
		pool.sort(Comparator
				.comparingInt(InventorySortHandler::category)
				.thenComparing(s -> BuiltInRegistries.ITEM.getKey(s.getItem()).toString())
				.thenComparing(Comparator.comparingInt(ItemStack::getCount).reversed()));

		// 3) 被标记归位的物品先落回它的预留格（只是记账，先不写背包）。
		//
		// 池子里找不到就空着 —— 这格留空是有意义的：格子上会显示半透明的物品图标，
		// 告诉玩家「这格是给它的」。这份「空」正是归位标记想表达的东西。
		Map<Integer, ItemStack> assigned = new LinkedHashMap<>();
		for (Map.Entry<Integer, ItemStack> entry : reserved.entrySet()) {
			Iterator<ItemStack> it = pool.iterator();
			while (it.hasNext()) {
				ItemStack candidate = it.next();
				if (PlacementHandler.sameItem(candidate, entry.getValue())) {
					assigned.put(entry.getKey(), candidate);
					it.remove();
					break;
				}
			}
		}

		// 4) 目标格 = 普通空格，再接上「预留了、但没认领到物品」的空格。
		//
		// 后面那一截是溢出兜底：池子放不下时先牺牲还没用上的幽灵预留格，也绝不让物品消失
		// —— 幽灵只是一条提示，物品才是玩家的东西。
		List<Integer> targets = new ArrayList<>();
		for (int i : range) {
			if (!pinned.contains(i) && !reserved.containsKey(i)) {
				targets.add(i);
			}
		}
		for (Map.Entry<Integer, ItemStack> entry : reserved.entrySet()) {
			if (!assigned.containsKey(entry.getKey())) {
				targets.add(entry.getKey());
			}
		}

		// 容量预检：需要多少格 vs 有多少格。
		//
		// 正常情况合并只会让占用变少，池子必然放得下；这道检查兜的是异常物品栈
		// （单格数量大于 maxStackSize 之类）。那种时候宁可整单放弃、背包一点不动，
		// 也不能像以前那样 break 掉多余的条目 —— 玩家会莫名其妙少一组东西。
		int needed = 0;
		for (ItemStack stack : pool) {
			needed += slotsNeeded(stack);
		}
		if (needed > targets.size()) {
			InventoryButler.LOGGER.warn("[{}] 本次整理已放弃：需要 {} 格、可用 {} 格。"
							+ "为避免丢失物品，背包保持原样（请检查是否存在异常的物品栈）",
					InventoryButler.MOD_ID, needed, targets.size());
			// 整理平时是不发提示的（高频操作，弹字反而碍事），但「按了没反应」更让人困惑，
			// 所以这种情况单独说一声
			ServerPlayNetworking.send(player,
					new MessagePayload("inventorybutler.message.sort.failed"));
			return;
		}

		// 5) 落盘：先清空所有非锁定格，再按计划写回
		for (int i : range) {
			if (!pinned.contains(i)) {
				inv.setItem(i, ItemStack.EMPTY);
			}
		}
		for (Map.Entry<Integer, ItemStack> entry : assigned.entrySet()) {
			inv.setItem(entry.getKey(), entry.getValue());
		}
		int cursor = 0;
		for (ItemStack stack : pool) {
			int remaining = stack.getCount();
			int max = Math.max(1, stack.getMaxStackSize());
			while (remaining > 0 && cursor < targets.size()) {
				int take = Math.min(remaining, max);
				ItemStack piece = stack.copy();
				piece.setCount(take);
				inv.setItem(targets.get(cursor), piece);
				cursor++;
				remaining -= take;
			}
		}
		// 没被用到的目标格在「清空」那一步就已经是空的了，不用再处理

		inv.setChanged();
		menu.broadcastFullState();
	}

	/** 这一叠物品放下来要占几个格子（按 maxStackSize 拆分后向上取整）。 */
	private static int slotsNeeded(ItemStack stack) {
		int max = Math.max(1, stack.getMaxStackSize());
		return (stack.getCount() + max - 1) / max;
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
