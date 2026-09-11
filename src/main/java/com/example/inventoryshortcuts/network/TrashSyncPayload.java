package com.example.inventoryshortcuts.network;

import com.example.inventoryshortcuts.InventoryShortcuts;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/** 服务端 -> 客户端：同步垃圾桶当前内容（空栈表示垃圾桶是空的）。 */
public record TrashSyncPayload(ItemStack stack) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<TrashSyncPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(InventoryShortcuts.MOD_ID, "trash_sync"));

	public static final StreamCodec<RegistryFriendlyByteBuf, TrashSyncPayload> STREAM_CODEC =
			StreamCodec.composite(
					ItemStack.OPTIONAL_STREAM_CODEC, TrashSyncPayload::stack,
					TrashSyncPayload::new
			);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
