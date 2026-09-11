package com.inventorybutler;

import com.inventorybutler.network.FavoriteTogglePayload;
import com.inventorybutler.network.MessagePayload;
import com.inventorybutler.network.PinPayload;
import com.inventorybutler.network.PinSyncPayload;
import com.inventorybutler.network.SortPayload;
import com.inventorybutler.network.TrashPayload;
import com.inventorybutler.network.TrashSyncPayload;
import com.inventorybutler.server.FavoriteHandler;
import com.inventorybutler.server.InventorySortHandler;
import com.inventorybutler.server.PlacementHandler;
import com.inventorybutler.server.TrashHandler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 入口（双端都会执行）。
 *
 * <p>服务端负责一切会改变物品的事情：改收藏标记、整理、销毁。
 * 客户端只负责「好看」和「好按」：绘制星标、垃圾桶按钮，以及把按键/鼠标操作翻译成网络包。</p>
 */
public class InventoryShortcuts implements ModInitializer {
	public static final String MOD_ID = "inventoryshortcuts";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		LOGGER.info("Inventory Shortcuts 正在加载（收藏锁定 / 归位标记 / 垃圾桶 / 一键整理）");

		ModConfig.load(LOGGER);

		// 数据组件要在世界加载之前注册
		ModComponents.FAVORITE.getClass();

		// ---- 网络包注册 ----
		// 注意 Fabric API 较新版本里这两个方法已改名：
		//   playC2S() -> serverboundPlay()，playS2C() -> clientboundPlay()
		PayloadTypeRegistry.serverboundPlay().register(FavoriteTogglePayload.TYPE, FavoriteTogglePayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SortPayload.TYPE, SortPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(TrashPayload.TYPE, TrashPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(PinPayload.TYPE, PinPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TrashSyncPayload.TYPE, TrashSyncPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PinSyncPayload.TYPE, PinSyncPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(MessagePayload.TYPE, MessagePayload.STREAM_CODEC);

		ServerPlayNetworking.registerGlobalReceiver(FavoriteTogglePayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> FavoriteHandler.toggle(player, payload));
		});

		ServerPlayNetworking.registerGlobalReceiver(SortPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> InventorySortHandler.sort(player, payload));
		});

		ServerPlayNetworking.registerGlobalReceiver(TrashPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> TrashHandler.handle(player, payload));
		});

		ServerPlayNetworking.registerGlobalReceiver(PinPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> PlacementHandler.toggle(player, payload));
		});

		// 进服时把已有的归位标记推给客户端 —— 客户端是只在「收到同步」时才更新的，
		// 不推的话玩家一进来看到的是一片没有图标的背包。
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				PlacementHandler.sync(handler.getPlayer()));

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> TrashHandler.clear(handler.getPlayer()));
	}
}
