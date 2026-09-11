package com.example.inventoryshortcuts.client;

import net.minecraft.network.chat.Component;

/**
 * 「物品栏界面内的提示」—— 在容器界面上方居中显示的一条轻提示。
 *
 * <p>背景：本 mod 的提示几乎全都是在<b>物品栏界面里</b>触发的（收藏、归位、垃圾桶、
 * 快捷移动被拦），但原版的动作栏提示挂在 HUD 上 —— <b>容器界面打开时 HUD 不渲染</b>，
 * 玩家根本看不见。所以提示改在这里暂存，由
 * {@code AbstractContainerScreenMixin} 在每帧绘制容器界面时画在 GUI 顶上方。</p>
 *
 * <p>界面外触发的提示（比如走着路按 Q 丢东西被拦）HUD 还在，仍走原版动作栏，
 * 两条路不冲突：{@link ClientFeedback} 负责分流。</p>
 */
public final class ScreenMessage {
	/** 每条提示停留 2.5 秒 —— 和原版动作栏提示的观感一致。 */
	private static final long TTL_MS = 2500L;

	private static String key;
	private static long shownAt;

	private ScreenMessage() {
	}

	public static void show(String translationKey) {
		key = translationKey;
		shownAt = System.currentTimeMillis();
	}

	/** 当前该显示的消息；没有或已过期返回 null（顺带把过期状态清掉）。 */
	public static Component current() {
		if (key == null) {
			return null;
		}
		if (System.currentTimeMillis() - shownAt > TTL_MS) {
			key = null;
			return null;
		}
		return Component.translatable(key);
	}

	public static void clear() {
		key = null;
	}
}
