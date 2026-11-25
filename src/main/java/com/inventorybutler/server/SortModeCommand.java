package com.inventorybutler.server;

import com.inventorybutler.InventoryButler;
import com.inventorybutler.ModConfig;
import com.inventorybutler.SortMode;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /inventorybutler sortmode ...} —— 玩家单独切换「容器整理」的落位方式。
 *
 * <p>每名玩家各存一份（配置里的 {@code playerSortModes}），所以不需要 OP 权限，
 * 也不会互相干扰。默认是旧的 {@link SortMode#CATEGORY}。</p>
 *
 * <ul>
 *   <li>{@code /inventorybutler sortmode} —— 查看自己当前的模式；</li>
 *   <li>{@code /inventorybutler sortmode topmost} —— 原位堆叠：同种物品只往最靠上的堆上加；</li>
 *   <li>{@code /inventorybutler sortmode category} —— 恢复旧的分类整理。</li>
 * </ul>
 *
 * <p>指令只影响打开的储存容器；玩家背包仍然按分类整理。</p>
 */
public final class SortModeCommand {
	private SortModeCommand() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				register(dispatcher));
	}

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal(InventoryButler.MOD_ID)
				.then(Commands.literal("sortmode")
						.executes(context -> show(context.getSource()))
						.then(Commands.literal(SortMode.TOPMOST.id())
								.executes(context -> set(context.getSource(), SortMode.TOPMOST)))
						.then(Commands.literal(SortMode.CATEGORY.id())
								.executes(context -> set(context.getSource(), SortMode.CATEGORY)))));
	}

	private static int show(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			return playersOnly(source);
		}
		SortMode mode = ModConfig.sortModeFor(player.getUUID());
		source.sendSuccess(() -> Component.translatable(
				"inventorybutler.command.sortmode.current",
				Component.translatable(mode.translationKey())), false);
		return 1;
	}

	private static int set(CommandSourceStack source, SortMode mode) {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			return playersOnly(source);
		}
		ModConfig.setSortMode(player.getUUID(), mode);
		source.sendSuccess(() -> Component.translatable(
				"inventorybutler.command.sortmode.set",
				Component.translatable(mode.translationKey())), false);
		return 1;
	}

	/** 控制台/命令方块没有自己的背包，也就没有「自己的容器整理模式」。 */
	private static int playersOnly(CommandSourceStack source) {
		source.sendFailure(Component.translatable("inventorybutler.command.players_only"));
		return 0;
	}
}