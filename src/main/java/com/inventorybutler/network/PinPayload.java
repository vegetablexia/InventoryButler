package com.inventorybutler.network;

import com.inventorybutler.InventoryButler;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端 -&gt; 服务端：切换某个槽位的「归位标记」。
 *
 * <p>只有玩家背包 0-35 号格（快捷栏 + 主背包）能被标记，服务端会再把菜单槽位下标
 * 换算成玩家背包下标；箱子、护甲、副手之类的格子会被服务端直接忽略。</p>
 */
public record PinPayload(int containerId, int slotId) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<PinPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(InventoryButler.MOD_ID, "pin"));

	public static final StreamCodec<RegistryFriendlyByteBuf, PinPayload> STREAM_CODEC =
			StreamCodec.composite(
					ByteBufCodecs.VAR_INT, PinPayload::containerId,
					ByteBufCodecs.VAR_INT, PinPayload::slotId,
					PinPayload::new
			);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
