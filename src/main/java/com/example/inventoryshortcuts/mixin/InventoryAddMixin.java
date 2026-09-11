package com.example.inventoryshortcuts.mixin;

import com.example.inventoryshortcuts.server.PlacementHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 「物品刚进背包」的归位钩子。
 *
 * <p>{@code Inventory.add(ItemStack)} 是原版所有「塞东西进背包」的公共漏斗，
 * 26.2 里确认过两条主要路径都会经过它：</p>
 *
 * <pre>
 * 捡起地上的掉落物: ItemEntity.playerTouch -> Inventory.add(ItemStack)
 * /give、合成产物:   Player.addItem        -> Inventory.add(ItemStack)
 * </pre>
 *
 * <p>注意 <b>Shift 快速移动不经过这里</b> —— 它是容器之间直接 {@code slot.set}，
 * 那条路在 {@code AbstractContainerMenuMixin} 里单独接。</p>
 *
 * <p><b>为什么不 {@code @Shadow} 那个 {@code player} 字段</b>：它确实声明在
 * {@code Inventory} 自己身上，shadow 是能用的；但直接转型取用更省事，
 * 也顺手避开了「{@code @Shadow} 字段不向上遍历父类、编译通过但一启动就崩」那类坑。</p>
 */
@Mixin(Inventory.class)
public abstract class InventoryAddMixin {
	@Inject(method = "add(Lnet/minecraft/world/item/ItemStack;)Z", at = @At("TAIL"))
	private void inventoryshortcuts$reflowPinnedAfterAdd(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		Player owner = ((Inventory) (Object) this).player;
		// 只在服务端做：客户端那份背包只是镜像，改了会被服务端覆盖回去
		if (owner instanceof ServerPlayer serverPlayer) {
			PlacementHandler.reflow(serverPlayer);
		}
	}
}
