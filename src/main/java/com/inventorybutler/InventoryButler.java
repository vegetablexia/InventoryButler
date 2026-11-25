package com.inventorybutler;

import com.inventorybutler.network.FavoriteTogglePayload;
import com.inventorybutler.network.MessagePayload;
import com.inventorybutler.network.PinPayload;
import com.inventorybutler.network.PinSyncPayload;
import com.inventorybutler.network.SortPayload;
import com.inventorybutler.server.FavoriteHandler;
import com.inventorybutler.server.InventorySortHandler;
import com.inventorybutler.server.PlacementHandler;
import com.inventorybutler.server.SortModeCommand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiConsumer;

/**
 * 入口（双端都会执行）。
 *
 * <p>服务端负责一切会改变物品的事情：改收藏标记、整理、归位。
 * 客户端只负责「好看」和「好按」：绘制收藏边框，以及把按键/鼠标操作翻译成网络包。</p>
 */
public class InventoryButler implements ModInitializer {
	public static final String MOD_ID = "inventorybutler";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		LOGGER.info("Inventory Butler 正在加载（收藏锁定 / 归位标记 / 一键整理）");

		ModConfig.load(LOGGER);

		// 数据组件要在世界加载之前注册；init() 的调用本身就是「确保类已初始化」
		ModComponents.init();

		registerNetworking();
		registerConnectionEvents();
		// 每个玩家用 /inventorybutler sortmode 切换打开容器时的整理方式
		SortModeCommand.register();
	}

	// ------------------------------------------------------------------
	// 网络
	// ------------------------------------------------------------------

	private static void registerNetworking() {
		// 客户端 -> 服务端
		registerServerbound(FavoriteTogglePayload.TYPE, FavoriteTogglePayload.STREAM_CODEC, FavoriteHandler::toggle);
		registerServerbound(SortPayload.TYPE, SortPayload.STREAM_CODEC, InventorySortHandler::sort);
		registerServerbound(PinPayload.TYPE, PinPayload.STREAM_CODEC, PlacementHandler::toggle);

		// 服务端 -> 客户端（在客户端入口注册接收器）
		registerClientbound(PinSyncPayload.TYPE, PinSyncPayload.STREAM_CODEC);
		registerClientbound(MessagePayload.TYPE, MessagePayload.STREAM_CODEC);
	}

	/**
	 * 注册一个客户端 -> 服务端的 play 包。
	 *
	 * <p>所有请求都切回服务端主线程再执行 —— {@code Handler} 会直接改玩家背包 / 菜单，
	 * 在收包线程碰这些东西是不安全的。</p>
	 */
	private static <T extends CustomPacketPayload> void registerServerbound(
			CustomPacketPayload.Type<T> type,
			StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
			BiConsumer<ServerPlayer, T> handler) {
		PayloadTypeRegistry.serverboundPlay().register(type, codec);
		ServerPlayNetworking.registerGlobalReceiver(type, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> handler.accept(player, payload));
		});
	}

	private static <T extends CustomPacketPayload> void registerClientbound(
			CustomPacketPayload.Type<T> type,
			StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
		PayloadTypeRegistry.clientboundPlay().register(type, codec);
	}

	// ------------------------------------------------------------------
	// 连接事件
	// ------------------------------------------------------------------

	private static void registerConnectionEvents() {
		// 进服时把服务端的权威状态推给客户端 —— 客户端只在「收到同步」时才更新，
		// 不推的话玩家一进来看到的是一片没有图标的背包。
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				PlacementHandler.sync(handler.getPlayer()));

		// 归位标记只活在服务端内存里，玩家退出即清空
		// （防止按 UUID 攒着不放，也避免同一玩家重进时看到上一局的残留标记）
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				PlacementHandler.clear(handler.getPlayer()));
	}
}
