package com.inventorybutler.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端的轻提示入口（原版物品栏上方那一行字）。
 *
 * <p><b>层级问题</b>：动作栏文字挂在 HUD 上，而容器界面打开时 HUD 不渲染 ——
 * 本 mod 的提示又几乎全是在物品栏界面里触发的，直接走动作栏玩家根本看不见。
 * 所以这里做了分流：</p>
 * <ul>
 *   <li>有容器界面 → 记入 {@link ScreenMessage}，由 mixin 画在界面上方居中；</li>
 *   <li>没有容器界面（比如走着路按 Q）→ 照旧走原版动作栏。</li>
 * </ul>
 *
 * <p>每种提示各自节流 1.2 秒 —— 连着按 Q 的时候别把这块地方刷成跑马灯。
 * 按 translation key 分开记，不同的提示才不会互相吞掉。</p>
 *
 * <p><b>整理不在这里发提示</b>：整理是个高频、无争议的操作，成了就是成了，
 * 再弹一行字反而挡住视线，所以整理键不产生任何反馈。</p>
 */
public final class ClientFeedback {
	private static final long COOLDOWN_MS = 1200L;

	private static final Map<String, Long> LAST_SHOWN_AT = new HashMap<>();

	private ClientFeedback() {
	}

	/** 「收藏了，丢不掉」。 */
	public static void cannotDropFavorite() {
		show("inventorybutler.message.favorite.cannotDrop");
	}

	/** 「收藏了，搬不走」—— Shift 快捷移动进容器被拦下时提示。 */
	public static void cannotMoveFavorite() {
		show("inventorybutler.message.favorite.cannotMove");
	}

	/** 服务端发来的提示（{@link com.inventorybutler.network.MessagePayload}）直接按翻译键展示。 */
	public static void showTranslation(String translationKey) {
		show(translationKey);
	}

	private static void show(String translationKey) {
		long now = System.currentTimeMillis();
		Long last = LAST_SHOWN_AT.get(translationKey);
		if (last != null && now - last < COOLDOWN_MS) {
			return;
		}
		LAST_SHOWN_AT.put(translationKey, now);

		// 先记进界面层：如果此刻正开着容器界面，mixin 下一帧就会把它画在 GUI 上方
		ScreenMessage.show(translationKey);

		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.gui == null || mc.gui.hud == null) {
			return;
		}
		// 容器界面开着的时候动作栏反正看不见，别白发。
		// 注意 26.x 把「当前界面」从 Minecraft.screen 挪到了 Gui.screen()。
		if (mc.gui.screen() instanceof AbstractContainerScreen) {
			return;
		}
		// 26.x：actionbar 文本从 Gui 挪到了 Gui.hud（Hud#setOverlayMessage）
		mc.gui.hud.setOverlayMessage(Component.translatable(translationKey), false);
	}
}
