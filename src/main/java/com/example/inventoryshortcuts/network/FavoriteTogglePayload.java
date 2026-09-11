package com.example.inventoryshortcuts.network;

import com.example.inventoryshortcuts.InventoryShortcuts;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** 客户端 -> 服务端：切换某个槽位物品的收藏状态。 */
public record FavoriteTogglePayload(int containerId, int slotId) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<FavoriteTogglePayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(InventoryShortcuts.MOD_ID, "favorite_toggle"));

	public static final StreamCodec<RegistryFriendlyByteBuf, FavoriteTogglePayload> STREAM_CODEC =
			StreamCodec.composite(
					ByteBufCodecs.VAR_INT, FavoriteTogglePayload::containerId,
					ByteBufCodecs.VAR_INT, FavoriteTogglePayload::slotId,
					FavoriteTogglePayload::new
			);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
