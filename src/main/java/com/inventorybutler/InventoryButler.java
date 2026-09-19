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
public class InventoryButler implements ModInitializer {
	public static final String MOD_ID = "inventorybutler";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		LOGGER.info("Inventory Butler 正在加载（收藏锁定 / 归位标记 / 垃圾桶 / 一键整理）");

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

		// 进服时把服务端的权威状态推给客户端 —— 客户端只在「收到同步」时才更新，
		// 不推的话玩家一进来看到的是一片没有图标的背包，垃圾桶里也还挂着上一局的东西。
		//
		// 垃圾桶必须一起推：它只活在服务端内存里、退出时已经清掉了，而客户端那份镜像
		// 还留着上一个存档的物品图标 —— 不重置就会显示一个「取回」按钮但按了没反应。
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.getPlayer();
			PlacementHandler.sync(player);
			TrashHandler.sync(player);
		});

		// 两份数据都只活在服务端内存里，玩家退出即清空
		// （防止按 UUID 攒着不放，也避免同一玩家重进时看到上一局的残留标记）
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			ServerPlayer player = handler.getPlayer();
			TrashHandler.clear(player);
			PlacementHandler.clear(player);
		});
	}
}
