package com.inventorybutler.server;

import com.inventorybutler.FavoriteStacks;
import com.inventorybutler.InventoryButler;
import com.inventorybutler.ModConfig;
import com.inventorybutler.SlotRules;
import com.inventorybutler.network.MessagePayload;
import com.inventorybutler.network.PinPayload;
import com.inventorybutler.network.PinSyncPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 「归位标记」—— 给物品在背包里指定一个固定的家。
 *
 * <p>这是和「收藏」（金色边标）并列的第二种标记，两者互不干涉：</p>
 * <ul>
 *   <li><b>收藏</b>管的是「不许动」：丢不掉、整理时不挪窝；</li>
 *   <li><b>归位</b>管的是「放哪儿」：物品走了以后格子留一个半透明图标当占位，
 *       下次它回到背包时自动住回这一格。</li>
 * </ul>
 *
 * <p><b>匹配只看物品本身</b>（{@code ItemStack.getItem()}），刻意忽略附魔、自定义名称
 * 之类的数据组件 —— 玩家想要的是「镐子那一格」，不是「精确到耐久和附魔的那一把镐子」。
 * 也正因为这样，标记里只存<b>物品类型</b>，不存整份物品栈：同步包小，服务端也不用
 * 替玩家保管物品数据。</p>
 *
 * <p><b>收藏优先级高于归位</b>：已经收藏的物品既不会被移出预留格，也不会被搬进预留格
 * ——「不许动」压过「放哪儿」。两个标记同时用在一件物品上时，归位对它不生效。</p>
 *
 * <p><b>为什么放在服务端</b>：三个触发时机（Shift 快速移动、捡起掉落物、一键整理）
 * 全都是服务端在改背包，而且联机时背包的权威也在服务端。客户端只拿一份副本画图标。</p>
 */
public final class PlacementHandler {
	/**
	 * 玩家 -&gt; (背包下标 -&gt; 那一格留给的物品栈)。
	 *
	 * <p>用 {@link LinkedHashMap} 是为了让同步和归位的遍历顺序稳定 —— 两个预留位互相
	 * 交换时，顺序固定才不会出现「这一轮换过去、下一轮又换回来」的抖动。</p>
	 *
	 * <p>只活在内存里：玩家退出服务器时这里的内容会被清掉。</p>
	 */
	private static final Map<UUID, Map<Integer, ItemStack>> PINNED = new HashMap<>();

	private PlacementHandler() {
	}

	// ------------------------------------------------------------------
	// 读写
	// ------------------------------------------------------------------

	/** 某个玩家当前的预留位快照（背包下标 -&gt; 留给的物品）。没有标记时返回空表。 */
	public static Map<Integer, ItemStack> pinsOf(ServerPlayer player) {
		Map<Integer, ItemStack> pins = PINNED.get(player.getUUID());
		if (pins == null) {
			return Map.of();
		}
		// 值也拷贝一份：调用方（整理）只该读这份快照，不能碰到标记本身
		Map<Integer, ItemStack> snapshot = new LinkedHashMap<>();
		pins.forEach((home, stack) -> snapshot.put(home, stack.copy()));
		return snapshot;
	}

	/**
	 * 是不是「同一种物品」。
	 *
	 * <p>只比 {@code getItem()}：附魔、改名、药水效果这些一律不管。这正是玩家按 Alt+右键 时
	 * 想要的手感 —— 附了魔的镐子和刚合成出来的镐子，在「该住哪一格」这件事上是同一个东西。</p>
	 */
	public static boolean sameItem(ItemStack a, ItemStack b) {
		return a != null && b != null && !a.isEmpty() && !b.isEmpty() && a.getItem() == b.getItem();
	}

	/**
	 * 这叠物品此刻是否受「收藏」保护。
	 *
	 * <p>收藏总开关关掉时，物品上残留的收藏组件不再有任何效力 —— 归位也不该被它挡住，
	 * 这和 README「关掉 favoriteEnabled 后所有收藏保护一起失效」一致。</p>
	 */
	private static boolean favoriteProtectionActive(ItemStack stack) {
		return ModConfig.favoriteEnabled && FavoriteStacks.isFavorite(stack);
	}

	// ------------------------------------------------------------------
	// 按 Alt+右键 切换标记
	// ------------------------------------------------------------------

	public static void toggle(ServerPlayer player, PinPayload payload) {
		if (!ModConfig.pinEnabled) {
			return;
		}
		AbstractContainerMenu menu = player.containerMenu;
		if (menu == null || menu.containerId != payload.containerId()) {
			return;
		}
		int slotId = payload.slotId();
		if (slotId < 0 || slotId >= menu.slots.size()) {
			return;
		}
		Slot slot = menu.getSlot(slotId);
		if (!slot.isActive()) {
			return;
		}
		int home = SlotRules.inventoryIndex(slot);
		if (home < 0) {
			// 不是玩家背包的格子（箱子、护甲、模组的假槽……）没有「归位」可言
			return;
		}

		Map<Integer, ItemStack> pins = PINNED.computeIfAbsent(player.getUUID(), k -> new LinkedHashMap<>());
		ItemStack here = slot.getItem();
		ItemStack existing = pins.get(home);

		boolean nowPinned;
		if (here.isEmpty() || (existing != null && sameItem(existing, here))) {
			// 空格子、或者这一格本来就留给这个物品 —— 这次操作就是「取消」。
			// 空格子上本来就没有标记时什么也没发生，别谎报一条「已取消」。
			if (existing == null) {
				return;
			}
			pins.remove(home);
			nowPinned = false;
		} else {
			// 一个物品只允许有一个「家」：先把它在别处的标记清掉，免得出现两个候选格。
			// 只存物品类型、不存整份物品栈：匹配规则本来就只看 getItem()，客户端幽灵图标
			// 也只需要物品外观。这样既不会把整套组件（甚至整本成书）塞进同步包，
			// 也不会让服务端替玩家长期保管一份物品数据。
			pins.entrySet().removeIf(entry -> sameItem(entry.getValue(), here));
			pins.put(home, new ItemStack(here.getItem()));
			nowPinned = true;
		}

		ServerPlayNetworking.send(player, new MessagePayload(nowPinned
				? "inventorybutler.message.pin.on"
				: "inventorybutler.message.pin.off"));
		InventoryButler.LOGGER.debug("玩家 {} {} 背包第 {} 格的归位标记",
				player.getGameProfile().name(), nowPinned ? "设置" : "取消", home);

		sync(player);
		// 刚标完顺带跑一次归位：把其它已有预留位里位置不对的物品放回去。
		// 新标记这一格本来就已经在位，所以这里不会把散落别处的同类物品聚拢。
		reflow(player);
	}

	// ------------------------------------------------------------------
	// 归位
	// ------------------------------------------------------------------

	/**
	 * 把被标记的物品送回它的预留位。
	 *
	 * <p>调用时机（都是「物品刚进背包」的时刻，而不是每 tick 扫描）：</p>
	 * <ul>
	 *   <li>{@code Inventory.add} —— 捡起地上的掉落物、{@code /give}、合成产物回包；</li>
	 *   <li>{@code AbstractContainerMenu.clicked} 里的 {@code QUICK_MOVE} —— Shift 快速移动的兜底。
	 *       正常情况下 {@code moveItemStackTo} 已经让物品直接落进预留格（客户端预测与服务端
	 *       用同一套规则，避免先落在默认格再被纠正的闪烁），这里只处理它放不下的残余情况；</li>
	 *   <li>一键整理 —— 那边自己会把物品排进预留格，这里只是兜底。</li>
	 * </ul>
	 *
	 * <p><b>为什么不做成「每 tick 强制归位」</b>：那样玩家想把石头从预留格手动挪走都挪不动，
	 * 会被立刻吸回去。玩家的手动摆放永远优先，我们只在「物品新进背包」这个瞬间插手。</p>
	 *
	 * <p>搬运用<b>整格交换</b>，不做「找空位放」：交换永远不会丢东西，也不会因为背包满而
	 * 一半成功一半失败。</p>
	 */
	public static void reflow(ServerPlayer player) {
		if (!ModConfig.pinEnabled) {
			return;
		}
		Map<Integer, ItemStack> pins = PINNED.get(player.getUUID());
		if (pins == null || pins.isEmpty()) {
			return;
		}

		Inventory inv = player.getInventory();
		boolean changed = false;
		for (Map.Entry<Integer, ItemStack> entry : pins.entrySet()) {
			int home = entry.getKey();
			ItemStack want = entry.getValue();
			if (want.isEmpty() || home < 0 || home >= SlotRules.INVENTORY_SLOTS) {
				continue;
			}

			ItemStack sitting = inv.getItem(home);
			if (sameItem(sitting, want)) {
				continue;
			}
			// 预留位上坐着收藏物就别动它 —— 「收藏」的第一原则是不被挪走，优先级高于归位
			if (favoriteProtectionActive(sitting)) {
				continue;
			}

			int from = findSlotOf(inv, want, home);
			if (from < 0) {
				// 背包里现在没有这个东西，让它空着就行 —— 半透明图标会告诉玩家「这格等着它」
				continue;
			}

			ItemStack moving = inv.getItem(from);
			// 收藏物不会被自动搬运：玩家手动把它放在别处，就尊重玩家的摆法。
			// 「收藏」的优先级高于「归位」（见类注释）。
			if (favoriteProtectionActive(moving)) {
				continue;
			}
			inv.setItem(home, moving);
			inv.setItem(from, sitting);
			changed = true;
		}

		if (changed) {
			inv.setChanged();
			AbstractContainerMenu menu = player.containerMenu;
			if (menu != null) {
				menu.broadcastChanges();
			}
		}
	}

	/**
	 * 在背包 0-35 里找第一个装着 want 这种物品的格子（忽略 NBT / 附魔）。
	 *
	 * <p>收藏物直接跳过：它们是「不许动」的，不能拿来当搬运的源。</p>
	 */
	private static int findSlotOf(Inventory inv, ItemStack want, int exclude) {
		for (int i = 0; i < SlotRules.INVENTORY_SLOTS; i++) {
			if (i == exclude) {
				continue;
			}
			ItemStack candidate = inv.getItem(i);
			if (favoriteProtectionActive(candidate)) {
				continue;
			}
			if (sameItem(candidate, want)) {
				return i;
			}
		}
		return -1;
	}

	// ------------------------------------------------------------------
	// 同步 / 清理
	// ------------------------------------------------------------------

	/** 把服务端的标记全量推给客户端（客户端只用它画图标）。 */
	public static void sync(ServerPlayer player) {
		Map<Integer, ItemStack> pins = PINNED.get(player.getUUID());
		List<Integer> slots = new ArrayList<>();
		List<ItemStack> items = new ArrayList<>();
		if (pins != null) {
			for (Map.Entry<Integer, ItemStack> entry : pins.entrySet()) {
				slots.add(entry.getKey());
				// 只发物品类型：客户端只需要画图标。不要把玩家物品上的整套组件
				// （附魔、成书正文……）塞进每一条同步包。
				items.add(new ItemStack(entry.getValue().getItem()));
			}
		}
		ServerPlayNetworking.send(player, new PinSyncPayload(List.copyOf(slots), List.copyOf(items)));
	}

	/**
	 * 玩家断开时清空标记。
	 *
	 * <p>两件事一起办：一是别让 {@link #PINNED} 跟着玩家 UUID 一直攒着（内存泄漏），
	 * 二是同一玩家下次再进来时不该看到上一局的幽灵图标 —— 那时候物品早就换了一批，
	 * 残留的预留位只会让人迷惑。重进后重新按 Alt+右键 标记即可。</p>
	 */
	public static void clear(ServerPlayer player) {
		PINNED.remove(player.getUUID());
	}
}
