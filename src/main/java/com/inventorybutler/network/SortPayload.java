package com.inventorybutler.network;

import com.inventorybutler.InventoryShortcuts;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** 客户端 -> 服务端：请求整理玩家背包。 */
public record SortPayload(int containerId) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<SortPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(InventoryShortcuts.MOD_ID, "sort"));

	public static final StreamCodec<RegistryFriendlyByteBuf, SortPayload> STREAM_CODEC =
			StreamCodec.composite(
					ByteBufCodecs.VAR_INT, SortPayload::containerId,
					SortPayload::new
			);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
