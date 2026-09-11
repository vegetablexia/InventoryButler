package com.inventorybutler;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Unit;

/**
 * 自定义数据组件。
 *
 * <p>「收藏」直接挂在 {@code ItemStack} 上（而不是记在某个槽位下标上），
 * 这样物品在背包/箱子之间移动、被丢进世界、死亡掉落之后，收藏标记都还在，
 * 行为和泰拉瑞亚一致。</p>
 *
 * <p>副作用：带收藏标记的物品栈与不带标记的物品栈无法合并（组件不同），
 * 这正是「锁定」想要的效果 —— 收藏的那一叠不会被自动并走。</p>
 */
public final class ModComponents {
	public static final DataComponentType<Unit> FAVORITE = Registry.register(
			BuiltInRegistries.DATA_COMPONENT_TYPE,
			Identifier.fromNamespaceAndPath(InventoryButler.MOD_ID, "favorite"),
			DataComponentType.<Unit>builder()
					.persistent(Unit.CODEC)
					.networkSynchronized(Unit.STREAM_CODEC)
					.build()
	);

	private ModComponents() {
	}
}
