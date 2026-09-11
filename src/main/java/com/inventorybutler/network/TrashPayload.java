package com.inventorybutler.network;

import com.inventorybutler.InventoryButler;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** 客户端 -> 服务端：垃圾桶操作。 */
public record TrashPayload(int containerId, int slotId, int action) implements CustomPacketPayload {
	/** 把指定槽位里的物品栈丢进垃圾桶（垃圾桶原有内容被销毁）。 */
	public static final int ACTION_SLOT = 0;
	/** 把鼠标光标上拿着的物品丢进垃圾桶。 */
	public static final int ACTION_CARRIED = 1;
	/** 把垃圾桶里的物品取回到鼠标光标上（撤销）。 */
	public static final int ACTION_RECLAIM = 2;

	public static final CustomPacketPayload.Type<TrashPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(InventoryButler.MOD_ID, "trash"));

	public static final StreamCodec<RegistryFriendlyByteBuf, TrashPayload> STREAM_CODEC =
			StreamCodec.composite(
					ByteBufCodecs.VAR_INT, TrashPayload::containerId,
					ByteBufCodecs.VAR_INT, TrashPayload::slotId,
					ByteBufCodecs.VAR_INT, TrashPayload::action,
					TrashPayload::new
			);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
