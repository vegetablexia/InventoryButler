package com.inventorybutler.server;

import com.inventorybutler.FavoriteStacks;
import com.inventorybutler.InventoryButler;
import com.inventorybutler.ModConfig;
import com.inventorybutler.SlotRules;
import com.inventorybutler.SortMode;
import com.inventorybutler.network.MessagePayload;
import com.inventorybutler.network.SortPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
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
 *   <li>打开<b>纯储存容器</b>（箱子 / 木桶、潜影盒、发射器 / 投掷器、漏斗）时，
 *       容器里的物品也会在<b>容器内部</b>单独整理一遍，不会和玩家背包混排；
 *       容器落位有「分类排序」和「原位堆叠」两种模式，玩家用
 *       {@code /inventorybutler sortmode} 自行切换（玩家背包始终按分类整理）；</li>
 *   <li>完全在服务端执行，客户端只发一个请求包，所以联机时也不会出现不同步。</li>
 * </ul>
 *
 * <p><b>不丢东西</b>：整理分成「先算清、再落盘」两步 —— 池子内容和归位格归属全部先算出来，
 * 确认放得下之后才动背包。万一真的放不下（只会出现在异常物品栈上，比如单格数量超过
 * {@code maxStackSize}），宁可整单放弃并记一条日志，也不静默丢掉多余的物品。</p>
 *
 * <p>命名上刻意区分两种「钉住」：{@code lockedSlots} 是<b>收藏</b>锁住的槽位，
 * {@code pinHomes} 是<b>归位标记</b>的预留格。两者互不相关，混用会很容易读错。</p>
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

		SortMode mode = ModConfig.sortModeFor(player.getUUID());
		if (!sortPlayerInventory(player, menu)) {
			// 背包因为容量异常整单放弃了 —— 容器也保持原样，别整理一半
			return;
		}
		sortOpenStorage(player, menu, mode);
		// 整理范围默认不含快捷栏，但快捷栏里的归位预留格仍然要兑现；
		// reflow 只搬「非收藏、且匹配预留物品」的格子，范围外的普通物品不受影响。
		PlacementHandler.reflow(player);
		// 两块区域各自整理完之后统一广播一次 —— 客户端看到的是最终状态，
		// 不会出现「背包先跳一下、容器再跳一下」的两段刷新。
		menu.broadcastFullState();
	}

	/**
	 * 整理玩家自己的背包；容器界面的外部储存格由
	 * {@link #sortOpenStorage(ServerPlayer, AbstractContainerMenu, SortMode)} 另行整理。
	 *
	 * @return 是否真的执行了整理。容量预检不通过时返回 {@code false}，背包一点没动
	 */
	private static boolean sortPlayerInventory(ServerPlayer player, AbstractContainerMenu menu) {
		// 0-8 快捷栏，9-35 主背包。护甲(36-39)和副手(40)不动。
		int start = ModConfig.sortIncludeHotbar ? 0 : SlotRules.HOTBAR_LAST + 1;
		int end = SlotRules.MAIN_LAST;
		List<Integer> range = slotRange(start, end);
		Inventory inv = player.getInventory();

		// 1) 收藏锁定的槽位：保持不动，也不进池子
		Set<Integer> lockedSlots = favoriteLockedSlots(inv, range);

		// 2) 归位预留格：只认整理范围内的格子；被收藏锁住的格子让位给收藏
		Map<Integer, ItemStack> pinHomes = pinHomesIn(player, start, end, lockedSlots);

		// 3) 把其余槽位的物品收进池子并合并同类项（只读，背包此时一点没动）
		List<ItemStack> pool = collectPool(inv, range, lockedSlots);
		sortPool(pool);

		// 4) 池子里有对应物品的预留格先认领（只记账，先不写背包）
		Map<Integer, ItemStack> pinAssignments = assignPins(pool, pinHomes);

		// 5) 目标格 = 普通空格，再接上「预留了、但没认领到物品」的空格。
		//    后面那一截是溢出兜底：池子放不下时先牺牲还没用上的幽灵预留格，也绝不让物品消失
		//    —— 幽灵只是一条提示，物品才是玩家的东西。
		List<Integer> targets = sortTargets(range, lockedSlots, pinHomes, pinAssignments);

		// 6) 容量预检：需要多少格 vs 有多少格。
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
			return false;
		}

		// 7) 落盘：先清空所有非锁定格，再按计划写回
		writeBack(inv, range, lockedSlots, pinAssignments, pool, targets);

		inv.setChanged();
		return true;
	}

	// ------------------------------------------------------------------
	// 打开的储存容器
	// ------------------------------------------------------------------

	/**
	 * 玩家背包之外，把当前打开的储存容器也整理一遍。
	 *
	 * <p>只认原版四种「纯储存」菜单：箱子/木桶（{@link ChestMenu}）、潜影盒
	 * （{@link ShulkerBoxMenu}）、发射器/投掷器（{@link DispenserMenu}）和漏斗
	 * （{@link HopperMenu}）<b>本身</b>，不认模组给它们加的菜单子类（子类可能追加
	 * 升级格、锁定位等有语义的槽位）。工作台、熔炉、铁砧、酿造台这些界面的槽位各有语义
	 * （合成、烧炼、输入顺序），整排排序会把它们搅乱，所以一律不碰。</p>
	 *
	 * <p>容器的物品只在容器内部整理，绝不会和玩家背包里的物品混在一起排。
	 * 收藏物同样「钉」在原槽位（与背包整理的规则一致）；归位标记只属于玩家背包，
	 * 这里不参与。</p>
	 *
	 * <p>落位规则由玩家自己的 {@code /inventorybutler sortmode} 选择：
	 * {@link SortMode#CATEGORY} 是原来的分类排序，{@link SortMode#TOPMOST}
	 * 是原位堆叠 —— 同种物品只往它当前最靠上的那堆上加，物品种类不换格。</p>
	 */
	private static void sortOpenStorage(ServerPlayer player, AbstractContainerMenu menu, SortMode mode) {
		List<Slot> slots = storageSlots(menu);
		if (slots.isEmpty()) {
			return;
		}

		Set<Slot> locked = new HashSet<>();
		if (ModConfig.favoriteEnabled && ModConfig.favoriteProtectFromSort) {
			for (Slot slot : slots) {
				if (FavoriteStacks.isFavorite(slot.getItem())) {
					locked.add(slot);
				}
			}
		}

		if (mode == SortMode.TOPMOST) {
			sortOpenStorageInPlace(player, slots, locked);
		} else {
			sortOpenStorageByCategory(player, slots, locked);
		}
	}

	/** 旧版分类整理：合并同类项后按分类、物品 id、数量重新落位。 */
	private static void sortOpenStorageByCategory(ServerPlayer player, List<Slot> slots, Set<Slot> locked) {
		List<Slot> targets = new ArrayList<>();
		for (Slot slot : slots) {
			if (!locked.contains(slot)) {
				targets.add(slot);
			}
		}

		List<ItemStack> pool = new ArrayList<>();
		for (Slot slot : targets) {
			ItemStack stack = slot.getItem();
			if (!stack.isEmpty()) {
				mergeInto(pool, stack.copy());
			}
		}
		sortPool(pool);

		int needed = 0;
		for (ItemStack stack : pool) {
			needed += slotsNeeded(stack);
		}
		if (needed > targets.size()) {
			// 和背包整理同一套兜底：宁可整块放弃，也不静默丢物品
			InventoryButler.LOGGER.warn("[{}] 容器整理已放弃：需要 {} 格、可用 {} 格。"
							+ "为避免丢失物品，容器保持原样（请检查是否存在异常的物品栈）",
					InventoryButler.MOD_ID, needed, targets.size());
			ServerPlayNetworking.send(player,
					new MessagePayload("inventorybutler.message.sort.failed"));
			return;
		}

		for (Slot slot : targets) {
			slot.set(ItemStack.EMPTY);
		}
		int cursor = 0;
		for (ItemStack stack : pool) {
			int remaining = stack.getCount();
			int max = Math.max(1, stack.getMaxStackSize());
			while (remaining > 0 && cursor < targets.size()) {
				int take = Math.min(remaining, max);
				ItemStack piece = stack.copy();
				piece.setCount(take);
				Slot target = targets.get(cursor);
				target.set(piece);
				target.setChanged();
				cursor++;
				remaining -= take;
			}
			if (remaining > 0) {
				InventoryButler.LOGGER.error("[{}] 容器整理出现容量异常：{} x{} 没能放下"
								+ "（已由预检拦截，理论上不应发生）",
						InventoryButler.MOD_ID, itemId(stack), remaining);
			}
		}
	}

	/**
	 * A 模式：原位堆叠。
	 *
	 * <p>同种物品只把数量往它当前最靠上的那一堆上堆；那一堆满了再堆下一个同种格。
	 * 物品种类之间不换格子，因此整理后每种物品仍在它原来最早出现的那一格，
	 * 清空的同种格就留在原地（不会被别的种类填上）。</p>
	 */
	private static void sortOpenStorageInPlace(ServerPlayer player, List<Slot> slots, Set<Slot> locked) {
		List<Slot> cells = new ArrayList<>();
		for (Slot slot : slots) {
			if (!locked.contains(slot)) {
				cells.add(slot);
			}
		}
		if (!stackInPlace(cells)) {
			InventoryButler.LOGGER.warn("[{}] 容器原位堆叠已放弃：存在异常的物品栈，"
							+ "容器保持原样（请检查单格数量是否超过 maxStackSize）",
					InventoryButler.MOD_ID);
			ServerPlayNetworking.send(player,
					new MessagePayload("inventorybutler.message.sort.failed"));
		}
	}

	/**
	 * 原位堆叠的核心：按当前格子顺序分组，组内从最靠上的格子开始依次填满。
	 *
	 * <p>只调整数量、不换格子，所以每种物品的「最靠上的那一堆」永远是优先目标。
	 * 如果一个组的总量超过这些格子能装下的上限（只可能出现在异常物品栈上），
	 * 直接返回 {@code false}；所有预检都在写格子之前完成，失败时不会有任何修改。</p>
	 */
	private static boolean stackInPlace(List<Slot> cells) {
		List<ItemStack> samples = new ArrayList<>();
		List<List<Slot>> groups = new ArrayList<>();
		for (Slot cell : cells) {
			ItemStack stack = cell.getItem();
			if (stack.isEmpty()) {
				continue;
			}
			int group = -1;
			for (int i = 0; i < samples.size(); i++) {
				if (ItemStack.isSameItemSameComponents(samples.get(i), stack)) {
					group = i;
					break;
				}
			}
			if (group < 0) {
				samples.add(stack.copy());
				groups.add(new ArrayList<>());
				group = groups.size() - 1;
			}
			groups.get(group).add(cell);
		}

		// 先全部预检，确认没有异常栈再动任何格子
		for (int i = 0; i < groups.size(); i++) {
			long total = 0;
			for (Slot cell : groups.get(i)) {
				total += cell.getItem().getCount();
			}
			int max = Math.max(1, samples.get(i).getMaxStackSize());
			if (total > (long) groups.get(i).size() * max) {
				return false;
			}
		}

		for (int i = 0; i < groups.size(); i++) {
			ItemStack sample = samples.get(i);
			int max = Math.max(1, sample.getMaxStackSize());
			int remaining = 0;
			for (Slot cell : groups.get(i)) {
				remaining += cell.getItem().getCount();
			}
			for (Slot cell : groups.get(i)) {
				int take = Math.min(remaining, max);
				if (take <= 0) {
					cell.set(ItemStack.EMPTY);
				} else {
					ItemStack piece = sample.copy();
					piece.setCount(take);
					cell.set(piece);
				}
				cell.setChanged();
				remaining -= take;
			}
		}
		return true;
	}

	/**
	 * 这个菜单里的「外部储存格」—— 即不属于玩家背包的那些槽位。
	 *
	 * <p>菜单类型先过白名单：只有那四个原版纯储存菜单类本身的外部槽位才允许排序；
	 * 其它菜单（包括模组子类）返回空表，{@link #sortOpenStorage} 于是什么也不做。
	 * 白名单里的菜单，外部槽位就是容器本身。</p>
	 */
	private static List<Slot> storageSlots(AbstractContainerMenu menu) {
		// 只认这四个原版菜单类「本身」。模组如果继承了它们再追加升级格 / 槽位锁之类的
		// 特殊槽，那些槽也满足「不在玩家背包里」，一并排序会把它们搅乱，所以宁可不动。
		Class<?> type = menu.getClass();
		if (type != ChestMenu.class && type != ShulkerBoxMenu.class
				&& type != DispenserMenu.class && type != HopperMenu.class) {
			return List.of();
		}
		List<Slot> slots = new ArrayList<>();
		for (Slot slot : menu.slots) {
			if (!SlotRules.isPlayerInventory(slot) && slot.isActive() && !slot.isFake()) {
				slots.add(slot);
			}
		}
		return slots;
	}

	// ------------------------------------------------------------------
	// 分步计算
	// ------------------------------------------------------------------

	private static List<Integer> slotRange(int start, int end) {
		List<Integer> range = new ArrayList<>(end - start + 1);
		for (int i = start; i <= end; i++) {
			range.add(i);
		}
		return range;
	}

	/** 收藏锁定：默认钉在原槽位不参与排序；关掉 favoriteProtectFromSort 后只是保留收藏边框。 */
	private static Set<Integer> favoriteLockedSlots(Inventory inv, List<Integer> range) {
		Set<Integer> locked = new HashSet<>();
		if (!ModConfig.favoriteEnabled || !ModConfig.favoriteProtectFromSort) {
			return locked;
		}
		for (int i : range) {
			if (FavoriteStacks.isFavorite(inv.getItem(i))) {
				locked.add(i);
			}
		}
		return locked;
	}

	/** 整理范围内、未被收藏锁定的归位预留格（背包下标 -&gt; 留给的物品）。 */
	private static Map<Integer, ItemStack> pinHomesIn(ServerPlayer player, int start, int end,
			Set<Integer> lockedSlots) {
		Map<Integer, ItemStack> pinHomes = new LinkedHashMap<>();
		if (!ModConfig.pinEnabled) {
			return pinHomes;
		}
		for (Map.Entry<Integer, ItemStack> entry : PlacementHandler.pinsOf(player).entrySet()) {
			int home = entry.getKey();
			if (home >= start && home <= end && !lockedSlots.contains(home)) {
				pinHomes.put(home, entry.getValue());
			}
		}
		return pinHomes;
	}

	/**
	 * 把非锁定槽位的物品全部收进池子并合并同类项。
	 *
	 * <p>预留格里东西也一起收 —— 它同样该参与「合并同类项 + 排序」，反正后面会把
	 * 标记的那一份精确放回去。这里拷贝物品栈，不碰背包本身。</p>
	 */
	private static List<ItemStack> collectPool(Inventory inv, List<Integer> range, Set<Integer> lockedSlots) {
		List<ItemStack> pool = new ArrayList<>();
		for (int i : range) {
			if (lockedSlots.contains(i)) {
				continue;
			}
			ItemStack stack = inv.getItem(i);
			if (!stack.isEmpty()) {
				mergeInto(pool, stack.copy());
			}
		}
		return pool;
	}

	private static void sortPool(List<ItemStack> pool) {
		pool.sort(Comparator
				.comparingInt(InventorySortHandler::categoryRank)
				.thenComparing(InventorySortHandler::itemId)
				.thenComparing(Comparator.comparingInt(ItemStack::getCount).reversed()));
	}

	/**
	 * 让池子里对应的物品回到预留格；找不到就空着。
	 *
	 * <p>这一步只从池子里取走物品并记账，等容量预检通过后才写背包。
	 * 空着的预留格是有意义的：界面上会显示半透明图标，告诉玩家「这格是给它的」。</p>
	 */
	private static Map<Integer, ItemStack> assignPins(List<ItemStack> pool, Map<Integer, ItemStack> pinHomes) {
		Map<Integer, ItemStack> assignments = new LinkedHashMap<>();
		for (Map.Entry<Integer, ItemStack> entry : pinHomes.entrySet()) {
			Iterator<ItemStack> it = pool.iterator();
			while (it.hasNext()) {
				ItemStack candidate = it.next();
				if (PlacementHandler.sameItem(candidate, entry.getValue())) {
					assignments.put(entry.getKey(), candidate);
					it.remove();
					break;
				}
			}
		}
		return assignments;
	}

	private static List<Integer> sortTargets(List<Integer> range, Set<Integer> lockedSlots,
			Map<Integer, ItemStack> pinHomes, Map<Integer, ItemStack> pinAssignments) {
		List<Integer> targets = new ArrayList<>();
		for (int i : range) {
			if (!lockedSlots.contains(i) && !pinHomes.containsKey(i)) {
				targets.add(i);
			}
		}
		for (Map.Entry<Integer, ItemStack> entry : pinHomes.entrySet()) {
			if (!pinAssignments.containsKey(entry.getKey())) {
				targets.add(entry.getKey());
			}
		}
		return targets;
	}

	/**
	 * 落盘：先清空所有非锁定格，再写回归位格，最后把池子顺次填进目标格。
	 *
	 * <p>没被用到的目标格在「清空」那一步就已经是空的了，不必再处理。</p>
	 */
	private static void writeBack(Inventory inv, List<Integer> range, Set<Integer> lockedSlots,
			Map<Integer, ItemStack> pinAssignments, List<ItemStack> pool, List<Integer> targets) {
		for (int i : range) {
			if (!lockedSlots.contains(i)) {
				inv.setItem(i, ItemStack.EMPTY);
			}
		}
		for (Map.Entry<Integer, ItemStack> entry : pinAssignments.entrySet()) {
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
			// 容量预检保证这里不会剩下放不下的物品；真剩下了也绝不静默丢弃
			if (remaining > 0) {
				InventoryButler.LOGGER.error("[{}] 整理出现容量异常：{} x{} 没能放下（已由预检拦截，"
								+ "理论上不应发生）",
						InventoryButler.MOD_ID, itemId(stack), remaining);
			}
		}
	}

	// ------------------------------------------------------------------
	// 小组件
	// ------------------------------------------------------------------

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

	/** 排序用的物品 id；未注册的物品（理论上不存在）排在最前，而不是抛 NPE。 */
	private static String itemId(ItemStack stack) {
		Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
		return key == null ? "" : key.toString();
	}

	/** 排序用的粗分类，只求「同类物品聚在一起」。 */
	private static int categoryRank(ItemStack stack) {
		Item item = stack.getItem();
		Identifier key = BuiltInRegistries.ITEM.getKey(item);
		String path = key == null ? "" : key.getPath();

		// 注意：原版不少物品的名字是「单个词」，没有前缀下划线 —— bow、crossbow、
		// trident、shield、shears、fishing_rod、flint_and_steel、bucket、mace 都是，
		// 光靠 endsWith("_bow") 之类会漏掉它们，所以要 equals 和 endsWith 各判一次。
		if (path.endsWith("_helmet") || path.endsWith("_chestplate")
				|| path.endsWith("_leggings") || path.endsWith("_boots")
				|| path.equals("elytra") || path.endsWith("_armor")) {
			return 30;
		}
		if (path.endsWith("_sword") || path.endsWith("_axe") || path.endsWith("_pickaxe")
				|| path.endsWith("_shovel") || path.endsWith("_hoe")
				|| path.equals("bow") || path.endsWith("_bow")
				|| path.equals("crossbow") || path.endsWith("_crossbow")
				|| path.equals("trident") || path.endsWith("_trident")
				|| path.equals("shield") || path.endsWith("_shield")
				|| path.equals("shears") || path.endsWith("_shears")
				|| path.equals("fishing_rod") || path.endsWith("_fishing_rod")
				|| path.equals("flint_and_steel") || path.endsWith("_flint_and_steel")
				|| path.equals("mace")) {
			return 20;
		}
		if (path.equals("potion") || path.endsWith("_potion")
				|| path.equals("bucket") || path.endsWith("_bucket")
				|| path.endsWith("_stew") || path.endsWith("_soup")) {
			return 40;
		}
		if (item instanceof BlockItem) {
			return 10;
		}
		return 50;
	}
}
