package com.inventorybutler.client;

import com.inventorybutler.InventoryButler;
import com.inventorybutler.PinAccess;
import com.inventorybutler.network.MessagePayload;
import com.inventorybutler.network.PinSyncPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * 客户端入口。
 *
 * <p>这里只做三件事：注册按键、注册收包、把服务端的权威数据（归位标记）
 * 镜像到本地供绘制使用。真正的逻辑全部在 {@code AbstractContainerScreenMixin}
 * 里（因为它需要槽位信息），以及服务端的几个 Handler 里。</p>
 */
public class InventoryButlerClient implements ClientModInitializer {
	/**
	 * 按键分类。
	 *
	 * <p>26.x 的分类不再是「一个字符串」，而是一个 {@link Identifier}，
	 * 翻译键由 {@code id.toLanguageKey("key.category")} 生成 ——
	 * 也就是 {@code key.category.inventorybutler.main}。</p>
	 */
	public static final KeyMapping.Category CATEGORY =
			KeyMapping.Category.register(
					Identifier.fromNamespaceAndPath(InventoryButler.MOD_ID, "main"));

	private static KeyMapping sortKey;
	private static KeyMapping favoriteKey;

	@Override
	public void onInitializeClient() {
		// Fabric API 新版本把 KeyBindingHelper 改名成了 KeyMappingHelper（包名也从
		// client.keybinding.v1 挪到了 client.keymapping.v1）。
		//
		// 整理键默认绑「鼠标中键」—— 这是泰拉瑞亚以及一票整理 mod 的习惯位置。
		// KeyMapping 的构造函数第二个参数换成 InputConstants.Type.MOUSE 之后，
		// 原版的「按键设置」界面里会正确显示成「鼠标 中键」，也能被鼠标事件匹配到。
		sortKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.inventorybutler.sort",
				InputConstants.Type.MOUSE, InputConstants.MOUSE_BUTTON_MIDDLE, CATEGORY));
		// 收藏默认不绑键盘键：Alt+左键是主操作，这里只留一个可选的键盘替代键。
		// 26.3 起输入后端换成 SDL：`KEYSYM` 改叫 `KEYBOARD`，未绑定键用 UNKNOWN（值 0）。
		favoriteKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.inventorybutler.favorite",
				InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), CATEGORY));

		// 服务端是归位标记的权威，客户端只是拿一份副本用于渲染。
		ClientPlayNetworking.registerGlobalReceiver(PinSyncPayload.TYPE, (payload, context) ->
				context.client().execute(() -> ClientPinState.set(payload.slots(), payload.items())));

		// 客户端预测（Shift 快移）也要知道归位格，否则物品会先预测落在默认格、
		// 等服务端纠正时闪一下。
		PinAccess.setClientPins(ClientPinState::snapshot);

		// 服务端发来的轻提示：显示层级由 ClientFeedback 分流（界面内画在 GUI 上方，界面外走动作栏）
		ClientPlayNetworking.registerGlobalReceiver(MessagePayload.TYPE, (payload, context) ->
				context.client().execute(() -> ClientFeedback.showTranslation(payload.key())));

		// 退出时清掉本地副本，免得进了别的存档还挂着上一个存档的幽灵图标
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			ClientPinState.clear();
			CursorIcons.reset();
			ScreenMessage.clear();
			ClientFeedback.clear();
		});

		InventoryButler.LOGGER.info("Inventory Butler 客户端已就绪");
	}

	public static KeyMapping sortKey() {
		return sortKey;
	}

	public static KeyMapping favoriteKey() {
		return favoriteKey;
	}
}
