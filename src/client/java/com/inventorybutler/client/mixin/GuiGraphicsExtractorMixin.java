package com.inventorybutler.client.mixin;

import com.inventorybutler.client.CursorIcons;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.cursor.CursorType;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 换鼠标指针图标的落点。
 *
 * <p>与「自己调 {@code GLFW.glfwSetCursor}」相比，这里走的是原版自己的通道：
 * {@code Gui} 每帧末尾会调 {@code applyCursor(window)}，把当帧攒下来的光标交给
 * {@code Window.selectCursor}，而后者是按<b>引用</b>去重的
 * （{@code currentCursor != target} 才真的落到 GLFW）。这么做的收益是：</p>
 * <ul>
 *   <li><b>恢复箭头不用写代码</b> —— 渲染器每帧都是新对象，{@code pendingCursor} 每帧从
 *       {@code CursorType.DEFAULT} 起步。我一停止覆盖，原版下一帧自己就把箭头换回来了；</li>
 *   <li><b>不和输入框的 I 型光标、链接的手型光标打架</b> —— 那些是别的组件通过
 *       {@code requestCursor} 提的，我不覆盖时它们照常生效。</li>
 * </ul>
 *
 * <p><b>为什么注入在 TAIL 而不是 HEAD 改 {@code pendingCursor}：</b>
 * 实测这个 modpack 里 IMBlocker 也在 {@code applyCursor} 上做了 {@code @Inject}，
 * 两边的 HEAD 回调谁先谁后取决于 mixin 优先级 —— 谁最后写 {@code pendingCursor} 谁生效，
 * 结果不确定。改成 TAIL 并<b>直接调公开的 {@code Window.selectCursor}</b>，
 * 就永远排在所有 HEAD 回调和原版逻辑之后，一定是我们赢；
 * 副作用是连 {@code @Shadow pendingCursor} 都不需要了，少一处可能踩坑的地方。</p>
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiGraphicsExtractorMixin {

	@Inject(method = "applyCursor(Lcom/mojang/blaze3d/platform/Window;)V", at = @At("TAIL"))
	private void inventorybutler$overrideCursor(Window window, CallbackInfo ci) {
		CursorType custom = CursorIcons.override();
		if (custom != null) {
			window.selectCursor(custom);
		}
	}
}
