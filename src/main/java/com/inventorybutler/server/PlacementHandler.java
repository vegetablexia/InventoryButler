package com.inventorybutler.server;

import com.inventorybutler.FavoriteStacks;
import com.inventorybutler.InventoryShortcuts;
import com.inventorybutler.ModConfig;
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
 * <p>这是和「收藏」（金星星标）并列的第二种标记，两者互不干涉：</p>
 * <ul>
 *   <li><b>收藏</b>管的是「不许动」：丢不掉、删不掉、整理时不挪窝；</li>
 *   <li><b>归位</b>管的是「放哪儿」：物品走了以后格子留一个半透明图标当占位，
 *       下次它回到背包时自动住回这一格。</li>
 * </ul>
 *
 * <p><b>匹配只看物品本身</b>（{@code ItemStack.getItem()}），刻意忽略附魔、自定义名称
 * 之类的数据组件 —— 玩家想要的是「镐子那一格」，不是「精确到耐久和附魔的那一把镐子」。</p>
 *
 * <p><b>为什么放在服务端</b>：三个触发时机（Shift 快速移动、捡起掉落物、一键整理）
 * 全都是服务端在改背包，而且联机时背包的权威也在服务端。客户端只拿一份副本画图标。</p>
 */
public final class PlacementHandler {
	/** 能当「预留位」的背包格子数：0-8 快捷栏，9-35 主背包。护甲 / 副手不参与。 */
	public static final int INVENTORY_SLOTS = 36;

	/**
	 * 玩家 -&gt; (背包下标 -&gt; 那一格留给的物品栈)。
	 *
	 * <p>用 {@link LinkedHashMap} 是为了让同步和归位的遍历顺序稳定 —— 两个预留位互相
	 * 交换时，顺序固定才不会出现「这一轮换过去、下一轮又换回来」的抖动。</p>
	 *
	 * <p>和垃圾桶一样只活在内存里：玩家退出服务器时这里的内容会被清掉。</p>
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
		return pins == null ? Map.of() : new LinkedHashMap<>(pins);
	}

	/**
	 * 是不是「同一种物品」。
	 *
	 * <p>只比 {@code getItem()}：附魔、改名、药水效果这些一律不管。这正是玩家按 T 时
	 * 想要的手感 —— 附了魔的镐子和刚合成出来的镐子，在「该住哪一格」这件事上是同一个东西。</p>
	 */
	public static boolean sameItem(ItemStack a, ItemStack b) {
		return a != null && b != null && !a.isEmpty() && !b.isEmpty() && a.getItem() == b.getItem();
	}

	/** 槽位对应的玩家背包下标（0-35）；箱子、护甲、副手、合成结果格一律返回 -1。 */
	public static int inventoryIndex(Slot slot) {
		if (slot == null || !(slot.container instanceof Inventory)) {
			return -1;
		}
		int index = slot.getContainerSlot();
		return (index >= 0 && index < INVENTORY_SLOTS) ? index : -1;
	}

	// ------------------------------------------------------------------
	// 按 T 切换标记
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
		int home = inventoryIndex(slot);
		if (home < 0) {
			// 不是玩家背包的格子（箱子、护甲……）没有「归位」可言
			return;
		}

		Map<Integer, ItemStack> pins = PINNED.computeIfAbsent(player.getUUID(), k -> new LinkedHashMap<>());
		ItemStack here = slot.getItem();
		ItemStack existing = pins.get(home);

		boolean nowPinned;
		if (here.isEmpty() || (existing != null && sameItem(existing, here))) {
			// 空格子、或者这一格本来就留给这个物品 —— 这次的 T 就是「取消」
			pins.remove(home);
			nowPinned = false;
		} else {
			// 一个物品只允许有一个「家」：先把它在别处的标记清掉，免得出现两个候选格
			pins.entrySet().removeIf(entry -> sameItem(entry.getValue(), here));
			pins.put(home, here.copyWithCount(1));
			nowPinned = true;
		}

		ServerPlayNetworking.send(player, new MessagePayload(nowPinned
				? "inventorybutler.message.pin.on"
				: "inventorybutler.message.pin.off"));
		InventoryShortcuts.LOGGER.debug("玩家 {} {} 背包第 {} 格的归位标记",
				player.getGameProfile().name(), nowPinned ? "设置" : "取消", home);

		sync(player);
		// 刚标完就归位一次：同类物品如果正散在别处，直接搬过来，玩家马上能看到效果
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
	 *   <li>{@code AbstractContainerMenu.clicked} 里的 {@code QUICK_MOVE} —— Shift 快速移动；</li>
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
			if (want.isEmpty() || home < 0 || home >= INVENTORY_SLOTS) {
				continue;
			}

			ItemStack sitting = inv.getItem(home);
			if (sameItem(sitting, want)) {
				continue;
			}
			// 预留位上坐着收藏物就别动它 —— 「收藏」的第一原则是不被挪走，优先级高于归位
			if (FavoriteStacks.isFavorite(sitting)) {
				continue;
			}

			int from = findSlotOf(inv, want, home);
			if (from < 0) {
				// 背包里现在没有这个东西，让它空着就行 —— 半透明图标会告诉玩家「这格等着它」
				continue;
			}

			ItemStack moving = inv.getItem(from);
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

	/** 在背包 0-35 里找第一个装着 want 这种物品的格子（忽略 NBT / 附魔）。 */
	private static int findSlotOf(Inventory inv, ItemStack want, int exclude) {
		for (int i = 0; i < INVENTORY_SLOTS; i++) {
			if (i == exclude) {
				continue;
			}
			if (sameItem(inv.getItem(i), want)) {
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
				items.add(entry.getValue());
			}
		}
		ServerPlayNetworking.send(player, new PinSyncPayload(List.copyOf(slots), List.copyOf(items)));
	}
}
