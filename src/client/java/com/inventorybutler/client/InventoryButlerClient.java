package com.inventorybutler.client;

import com.inventorybutler.InventoryButler;
import com.inventorybutler.network.MessagePayload;
import com.inventorybutler.network.PinSyncPayload;
import com.inventorybutler.network.TrashSyncPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端入口。
 *
 * <p>这里只做三件事：注册按键、注册收包、把服务端的两份权威数据（垃圾桶内容、
 * 归位标记）镜像到本地供绘制使用。真正的逻辑全部在 {@code AbstractContainerScreenMixin}
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
	private static KeyMapping sortAltKey;
	private static KeyMapping trashKey;
	private static KeyMapping favoriteKey;
	private static KeyMapping pinKey;

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
				InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, CATEGORY));
		// 老玩家习惯的 R 键继续保留，作为整理的第二绑定。
		sortAltKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.inventorybutler.sort.alt",
				InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, CATEGORY));
		trashKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.inventorybutler.trash",
				InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_DELETE, CATEGORY));
		favoriteKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.inventorybutler.favorite",
				InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY));
		// 「归位标记」默认 T：原版物品栏界面没占用 T，不会和别的功能打架。
		pinKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.inventorybutler.pin",
				InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_T, CATEGORY));

		// 服务端是垃圾桶 / 归位标记的权威，客户端只是拿一份副本用于渲染。
		ClientPlayNetworking.registerGlobalReceiver(TrashSyncPayload.TYPE, (payload, context) ->
				context.client().execute(() -> ClientTrashState.set(payload.stack())));

		ClientPlayNetworking.registerGlobalReceiver(PinSyncPayload.TYPE, (payload, context) ->
				context.client().execute(() -> ClientPinState.set(payload.slots(), payload.items())));

		// 服务端发来的轻提示：显示层级由 ClientFeedback 分流（界面内画在 GUI 上方，界面外走动作栏）
		ClientPlayNetworking.registerGlobalReceiver(MessagePayload.TYPE, (payload, context) ->
				context.client().execute(() -> ClientFeedback.showTranslation(payload.key())));

		// 退出时清掉本地副本，免得进了别的存档还挂着上一个存档的幽灵图标 / 垃圾桶残留
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			ClientPinState.clear();
			ClientTrashState.clear();
			CursorIcons.reset();
			ScreenMessage.clear();
		});

		InventoryButler.LOGGER.info("Inventory Butler 客户端已就绪");
	}

	public static KeyMapping sortKey() {
		return sortKey;
	}

	public static KeyMapping sortAltKey() {
		return sortAltKey;
	}

	public static KeyMapping trashKey() {
		return trashKey;
	}

	public static KeyMapping favoriteKey() {
		return favoriteKey;
	}

	public static KeyMapping pinKey() {
		return pinKey;
	}
}
