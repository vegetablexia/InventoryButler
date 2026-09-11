package com.inventorybutler.network;

import com.inventorybutler.InventoryShortcuts;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 -&gt; 客户端：全量同步「归位标记」。
 *
 * <p>两个 list 一一对应：{@code slots[i]} 是玩家背包下标，{@code items[i]} 是那一格
 * 留给什么物品。客户端只拿它画半透明图标，不做任何权威判断。</p>
 *
 * <p>之所以全量发而不是发增量：数量最多 36 条，一条包比维护「加/删/改」三种增量
 * 简单得多，也不会出现客户端和服务端对不上的中间态。</p>
 */
public record PinSyncPayload(List<Integer> slots, List<ItemStack> items) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<PinSyncPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(InventoryShortcuts.MOD_ID, "pin_sync"));

	/**
	 * 手写 codec 而不是 {@code StreamCodec.composite}：这是两个需要保持等长的 list
	 * （下标 + 物品栈），composite 表达不了这种「配对」关系，硬套反而更容易写错。
	 */
	public static final StreamCodec<RegistryFriendlyByteBuf, PinSyncPayload> STREAM_CODEC =
			StreamCodec.of(PinSyncPayload::write, PinSyncPayload::read);

	private static void write(RegistryFriendlyByteBuf buf, PinSyncPayload payload) {
		int size = Math.min(payload.slots().size(), payload.items().size());
		buf.writeVarInt(size);
		for (int i = 0; i < size; i++) {
			buf.writeVarInt(payload.slots().get(i));
			ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, payload.items().get(i));
		}
	}

	private static PinSyncPayload read(RegistryFriendlyByteBuf buf) {
		int size = buf.readVarInt();
		List<Integer> slots = new ArrayList<>(size);
		List<ItemStack> items = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			slots.add(buf.readVarInt());
			items.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
		}
		return new PinSyncPayload(List.copyOf(slots), List.copyOf(items));
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
