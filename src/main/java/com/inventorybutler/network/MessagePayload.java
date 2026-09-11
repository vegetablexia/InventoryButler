package com.inventorybutler.network;

import com.inventorybutler.InventoryButler;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 服务端 -&gt; 客户端：一条轻提示（翻译键）。
 *
 * <p>以前服务端的提示走 {@code sendSystemMessage(…, true)}（动作栏）—— 但物品栏
 * 界面打开时 HUD 整个不渲染，动作栏文字玩家根本看不见，而本 mod 的提示
 * 几乎全都是在物品栏界面里触发的。改成自定义包之后，客户端自己决定画在哪儿：
 * 有容器界面就画在界面顶部，没有才走动作栏。</p>
 *
 * <p>只传<b>翻译键</b>不传成品文本：语言文件客户端自己有，一个 varint 字符串
 * 也比序列化整个 Component 便宜。</p>
 */
public record MessagePayload(String key) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<MessagePayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(InventoryButler.MOD_ID, "message"));

	public static final StreamCodec<RegistryFriendlyByteBuf, MessagePayload> STREAM_CODEC =
			StreamCodec.composite(ByteBufCodecs.STRING_UTF8, MessagePayload::key, MessagePayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
