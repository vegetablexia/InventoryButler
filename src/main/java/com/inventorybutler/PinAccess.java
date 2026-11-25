package com.inventorybutler;

import com.inventorybutler.server.PlacementHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 归位标记的统一读取入口。
 *
 * <p>服务端的权威数据在 {@link PlacementHandler}；客户端只有一份用于绘制幽灵图标的
 * 镜像（{@code ClientPinState}）。两边都需要在 Shift 快移时读到同一份「这格留给什么」，
 * 才能算出同样的落位：</p>
 *
 * <ul>
 *   <li><b>服务端</b>照常读 {@link PlacementHandler#pinsOf(ServerPlayer)}；</li>
 *   <li><b>客户端</b>读由客户端入口注入的只读快照（默认空表，客户端还没收到同步时
 *       就不做预测，交给服务端的广播兜底）。</li>
 * </ul>
 *
 * <p>为什么不能让客户端也读 {@link PlacementHandler}：它按玩家 UUID 存着全服所有玩家的
 * 标记，客户端没有那份数据；而客户端的预测只需要自己这一份镜像。</p>
 */
public final class PinAccess {
	/** 客户端镜像的数据源；默认空表，客户端初始化时替换成 ClientPinState 的快照。 */
	private static Supplier<Map<Integer, ItemStack>> clientPins = Map::of;

	private PinAccess() {
	}

	/** 客户端入口调用，把本地归位标记接到快读入口上。 */
	public static void setClientPins(Supplier<Map<Integer, ItemStack>> supplier) {
		clientPins = supplier;
	}

	/** 当前玩家生效的归位标记（背包下标 -&gt; 预留物品），只读。 */
	public static Map<Integer, ItemStack> pinsFor(Player player) {
		if (player instanceof ServerPlayer serverPlayer) {
			return PlacementHandler.pinsOf(serverPlayer);
		}
		return clientPins.get();
	}
}