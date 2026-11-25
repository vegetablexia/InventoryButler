package com.inventorybutler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 极简 JSON 配置：无需 Cloth Config 之类的依赖，直接读写 config/inventorybutler.json。
 *
 * <p>手动改动文件后需要重启游戏生效；用 {@code /inventorybutler sortmode} 切换容器模式
 * 会立即生效并写回文件。双端都会加载：客户端读它决定界面行为，
 * 服务端读它做权威校验（所以联机时服务器上的这份文件说了算）。</p>
 */
public final class ModConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve(InventoryButler.MOD_ID + ".json");

	/**
	 * 收藏功能总开关。
	 *
	 * <p>关掉之后：不能切收藏、不画收藏边框、不换指针，<b>所有收藏保护也一起失效</b>
	 * （丢弃、搬进容器、整理）。各个 {@code favoriteProtectFrom*} 是它下面的分开关。</p>
	 */
	public static boolean favoriteEnabled = true;
	/** 收藏的物品按 Q 丢不掉、Shift 搬不进容器 */
	public static boolean favoriteProtectFromDrop = true;
	/** 整理时收藏的物品留在原地 */
	public static boolean favoriteProtectFromSort = true;

	/**
	 * 归位标记（Alt + 右键）。
	 *
	 * <p>和「收藏」并列的第二种标记：给物品在背包里指定一个固定的家，物品不在时那一格
	 * 显示半透明图标占位，物品回到背包时（Shift 快速移动 / 捡起掉落物 / 一键整理）
	 * 优先住回这一格。收藏管「不许动」，归位管「放哪儿」。</p>
	 */
	public static boolean pinEnabled = true;

	/**
	 * 按住修饰键时换鼠标指针图标。
	 *
	 * <p>按住 Alt（收藏）指针变金色星星，松手恢复箭头。
	 * 只在指针确实停在能生效的槽位上时才换。</p>
	 */
	public static boolean cursorIconsEnabled = true;

	/** 一键整理 */
	public static boolean sortEnabled = true;
	/**
	 * 整理时是否把快捷栏 9 格一起参与排序。
	 *
	 * <p>默认 {@code false} —— 和主流的整理类 mod（Inventory Profiles、Item Scroller 等）一致，
	 * 只整理主背包的 27 格（玩家 Inventory 下标 9-35），快捷栏那 9 格（下标 0-8）
	 * 保持玩家自己的摆位不动。毕竟快捷栏是「手感」的一部分，被整理打乱比乱糟糟更难受。</p>
	 */
	public static boolean sortIncludeHotbar = false;

	/**
	 * 每个玩家自己的容器整理模式（UUID -&gt; 模式）。
	 *
	 * <p>用玩家而不是全局开关：单人和联机都各用各的，不需要 OP 权限。
	 * 玩家第一次用 {@code /inventorybutler sortmode} 切换时写入配置，之后一直生效。
	 * 默认值由 {@link #sortModeFor(UUID)} 提供，老配置里没有这个字段时仍是旧版分类整理。</p>
	 */
	private static final Map<String, SortMode> PLAYER_SORT_MODES = new HashMap<>();
	private static final String PLAYER_SORT_MODES_KEY = "playerSortModes";

	private ModConfig() {
	}

	/** 这个玩家整理打开的储存容器时用的模式；没设置过时沿用旧版分类整理。 */
	public static SortMode sortModeFor(UUID player) {
		return PLAYER_SORT_MODES.getOrDefault(player.toString(), SortMode.CATEGORY);
	}

	/** 修改并持久化一个玩家的容器整理模式。 */
	public static void setSortMode(UUID player, SortMode mode) {
		PLAYER_SORT_MODES.put(player.toString(), mode);
		save();
	}

	public static void load(Logger logger) {
		if (!Files.exists(PATH)) {
			save();
			return;
		}
		boolean needsRewrite = false;
		try {
			String text = Files.readString(PATH, StandardCharsets.UTF_8);
			JsonObject root = JsonParser.parseString(text).getAsJsonObject();
			readValues(root);
			// 老版本留下过一批鼠标/滚轮手势的键（gesturesEnabled 等），以及垃圾桶相关的
			// trashEnabled / favoriteProtectFromTrash —— 功能都已删除，顺手把文件重写一遍，
			// 让这些僵尸键自己消失。
			needsRewrite = root.has("gesturesEnabled") || root.has("wheelItemsPerScroll")
					|| root.has("wheelWholeStackOnShift") || root.has("leftDragDistribute")
					|| root.has("trashEnabled") || root.has("favoriteProtectFromTrash");
		} catch (Exception e) {
			logger.warn("[{}] 配置文件解析失败，使用默认值: {}", InventoryButler.MOD_ID, e.toString());
			needsRewrite = true;
		}
		if (needsRewrite) {
			save();
		}
	}

	public static void save() {
		JsonObject root = new JsonObject();
		writeValues(root);
		try {
			Files.createDirectories(PATH.getParent());
			Files.writeString(PATH, GSON.toJson(root), StandardCharsets.UTF_8);
		} catch (IOException e) {
			InventoryButler.LOGGER.warn("无法写入配置文件 {}", PATH, e);
		}
	}

	private static void readValues(JsonObject root) {
		favoriteEnabled = getBool(root, "favoriteEnabled", favoriteEnabled);
		favoriteProtectFromDrop = getBool(root, "favoriteProtectFromDrop", favoriteProtectFromDrop);
		favoriteProtectFromSort = getBool(root, "favoriteProtectFromSort", favoriteProtectFromSort);
		pinEnabled = getBool(root, "pinEnabled", pinEnabled);
		cursorIconsEnabled = getBool(root, "cursorIconsEnabled", cursorIconsEnabled);
		sortEnabled = getBool(root, "sortEnabled", sortEnabled);
		sortIncludeHotbar = getBool(root, "sortIncludeHotbar", sortIncludeHotbar);

		PLAYER_SORT_MODES.clear();
		if (root.has(PLAYER_SORT_MODES_KEY) && root.get(PLAYER_SORT_MODES_KEY).isJsonObject()) {
			for (Map.Entry<String, JsonElement> entry
					: root.getAsJsonObject(PLAYER_SORT_MODES_KEY).entrySet()) {
				if (entry.getValue().isJsonPrimitive()) {
					PLAYER_SORT_MODES.put(entry.getKey(),
							SortMode.fromId(entry.getValue().getAsString()));
				}
			}
		}
	}

	private static void writeValues(JsonObject root) {
		root.addProperty("favoriteEnabled", favoriteEnabled);
		root.addProperty("favoriteProtectFromDrop", favoriteProtectFromDrop);
		root.addProperty("favoriteProtectFromSort", favoriteProtectFromSort);
		root.addProperty("pinEnabled", pinEnabled);
		root.addProperty("cursorIconsEnabled", cursorIconsEnabled);
		root.addProperty("sortEnabled", sortEnabled);
		root.addProperty("sortIncludeHotbar", sortIncludeHotbar);

		JsonObject modes = new JsonObject();
		PLAYER_SORT_MODES.forEach((player, mode) -> modes.addProperty(player, mode.id()));
		root.add(PLAYER_SORT_MODES_KEY, modes);
	}

	private static boolean getBool(JsonObject o, String key, boolean def) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsBoolean() : def;
	}
}
