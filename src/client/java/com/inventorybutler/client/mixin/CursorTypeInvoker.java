package com.inventorybutler.client.mixin;

import com.mojang.blaze3d.platform.cursor.CursorType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 借 {@link CursorType} 的私有构造器。
 *
 * <p>26.2 的 {@code CursorType} 只对外开放了 {@code createStandardCursor}
 * （造箭头 / I 型光标 / 手型这类系统标准光标），没有「用一张图片当光标」的入口，
 * 而 {@code CursorType(String, long)} 是私有的 —— 那第二个参数就是 GLFW 光标句柄。
 * 想用自定义图片光标，就只能从这里借一下。</p>
 *
 * <p>构造器型 {@code @Invoker} 的规矩：方法名写 {@code <init>}，必须是
 * <b>static</b>，因此 mixin 本身得是接口（接口的 static 方法天然是 public）。
 * 参数表要和目标构造器一一对应，返回值就是目标类型。</p>
 */
@Mixin(CursorType.class)
public interface CursorTypeInvoker {

	@Invoker("<init>")
	static CursorType inventoryshortcuts$create(String name, long handle) {
		// Mixin 会把这个方法体整个替换成 new CursorType(name, handle)。
		// 走到这行说明 mixin 没应用上（CursorIcons 里有反射兜底，会记一条日志）。
		throw new AssertionError("CursorTypeInvoker 未生效");
	}
}
