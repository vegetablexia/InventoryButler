package com.inventorybutler.client.mixin;

import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 让垃圾桶按钮能「贴着绿书排」。
 *
 * <p>配方书按钮（绿书）的位置是各个界面自己定的：
 * 玩家背包在 {@code (104, h/2-22)}、工作台在 {@code (5, h/2-49)}、熔炉在 {@code (20, h/2-49)}，
 * 全走同一个受保护的 {@code getRecipeBookButtonPosition()}。外面调不到 protected 方法，
 * 就用 accessor mixin 借一下 —— 返回的是<b>屏幕绝对坐标</b>（已含 leftPos/topPos），
 * 调用方减掉即可得到 GUI 空间坐标。</p>
 */
@Mixin(AbstractRecipeBookScreen.class)
public interface AbstractRecipeBookScreenAccessor {
	@Invoker("getRecipeBookButtonPosition")
	ScreenPosition inventoryshortcuts$recipeBookButtonPosition();
}
