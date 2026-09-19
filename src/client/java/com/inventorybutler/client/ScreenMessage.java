package com.inventorybutler.client;

import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.Queue;

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
 *
 * <p><b>队列 + 渐隐</b>（相对早期版本的两个行为修正）：</p>
 * <ul>
 *   <li>提示不再只有一条通道 —— 连续做两个不同操作，前一条不会被后一条<b>顶掉</b>，
 *       而是排队依次显示（最多积压 {@link #MAX_QUEUED} 条，超出丢最旧的，
 *       宁可丢提示也不让队列无限变长）。</li>
 *   <li>每条提示淡入 150ms → 停留 2.2s → 淡出 400ms，不再「啪一下消失」。
 *       淡入淡出的系数由 {@link #fade()} 提供，绘制层用它去缩放底纹和文字的 alpha。</li>
 * </ul>
 */
public final class ScreenMessage {
	/** 淡入时长（毫秒）。 */
	private static final long FADE_IN_MS = 150L;
	/** 完全显示的停留时长（毫秒）—— 和原版动作栏提示的观感一致。 */
	private static final long HOLD_MS = 2200L;
	/** 淡出时长（毫秒）。 */
	private static final long FADE_OUT_MS = 400L;
	/** 一条提示从出现到彻底消失的总时长。 */
	private static final long TOTAL_MS = FADE_IN_MS + HOLD_MS + FADE_OUT_MS;

	/** 积压队列上限：再多的提示直接丢最旧的（提示是轻量反馈，不值得排队等太久）。 */
	private static final int MAX_QUEUED = 4;

	private static String key;
	private static long shownAt;
	private static final Queue<String> PENDING = new ArrayDeque<>();

	/** {@link #current()} 本帧算出的淡入淡出系数，{@link #fade()} 读走。 */
	private static float lastFade;

	private ScreenMessage() {
	}

	public static void show(String translationKey) {
		long now = System.currentTimeMillis();
		// 正在显示同一条 → 只重置计时（连按同一类拦截时刷新停留时长，不重复排队）
		if (translationKey.equals(key) && now - shownAt < TOTAL_MS) {
			shownAt = now;
			return;
		}
		if (key != null) {
			PENDING.add(translationKey);
			while (PENDING.size() > MAX_QUEUED) {
				PENDING.poll();
			}
			return;
		}
		key = translationKey;
		shownAt = now;
	}

	/**
	 * 当前该显示的消息；没有或已过期返回 null（顺带推进队列、把过期状态清掉）。
	 *
	 * <p>配套的 {@link #fade()} 返回这条消息本帧的淡入淡出系数（0..1）——
	 * 两个方法必须在同一帧里按「先 current() 后 fade()」的顺序调用。</p>
	 */
	public static Component current() {
		long now = System.currentTimeMillis();
		if (key == null) {
			pullNext(now);
			// 新拉出来的这条从淡入的 0 开始
			lastFade = key == null ? 0f : fadeAt(now - shownAt);
			return key == null ? null : Component.translatable(key);
		}
		long elapsed = now - shownAt;
		if (elapsed >= TOTAL_MS) {
			key = null;
			pullNext(now);
			lastFade = key == null ? 0f : fadeAt(now - shownAt);
			return key == null ? null : Component.translatable(key);
		}
		lastFade = fadeAt(elapsed);
		return Component.translatable(key);
	}

	/** 本帧的淡入淡出系数：淡入段线性升到 1，停留段为 1，淡出段线性降到 0。 */
	public static float fade() {
		return lastFade;
	}

	private static float fadeAt(long elapsed) {
		if (elapsed <= 0) {
			return 0f;
		}
		if (elapsed < FADE_IN_MS) {
			return elapsed / (float) FADE_IN_MS;
		}
		long fadeOutStart = FADE_IN_MS + HOLD_MS;
		if (elapsed > fadeOutStart) {
			return Math.max(0f, 1f - (elapsed - fadeOutStart) / (float) FADE_OUT_MS);
		}
		return 1f;
	}

	private static void pullNext(long now) {
		String next = PENDING.poll();
		if (next == null) {
			return;
		}
		key = next;
		shownAt = now;
	}

	public static void clear() {
		key = null;
		PENDING.clear();
		lastFade = 0f;
	}
}
