package com.inventorybutler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 极简 JSON 配置：无需 Cloth Config 之类的依赖，直接读写 config/inventorybutler.json。
 *
 * <p>改动后需要重启游戏生效。双端都会加载：客户端读它决定界面行为，
 * 服务端读它做权威校验（所以联机时服务器上的这份文件说了算）。</p>
 */
public final class ModConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve(InventoryButler.MOD_ID + ".json");

	/** 收藏/锁定 */
	public static boolean favoriteEnabled = true;
	/** 收藏的物品按 Q 丢不掉 */
	public static boolean favoriteProtectFromDrop = true;
	/** 收藏的物品丢不进垃圾桶 */
	public static boolean favoriteProtectFromTrash = true;
	/** 整理时收藏的物品留在原地 */
	public static boolean favoriteProtectFromSort = true;

	/**
	 * 归位标记（T 键）。
	 *
	 * <p>和「收藏」并列的第二种标记：给物品在背包里指定一个固定的家，物品不在时那一格
	 * 显示半透明图标占位，物品回到背包时（Shift 快速移动 / 捡起掉落物 / 一键整理）
	 * 优先住回这一格。收藏管「不许动」，归位管「放哪儿」。</p>
	 */
	public static boolean pinEnabled = true;

	/** 垃圾桶（右下角按钮 + Ctrl+左键） */
	public static boolean trashEnabled = true;

	/**
	 * 按住修饰键时换鼠标指针图标。
	 *
	 * <p>按住 Alt（收藏）指针变金色星星，按住 Ctrl（删除）变垃圾桶，松手恢复箭头。
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

	private ModConfig() {
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
			favoriteEnabled = getBool(root, "favoriteEnabled", favoriteEnabled);
			favoriteProtectFromDrop = getBool(root, "favoriteProtectFromDrop", favoriteProtectFromDrop);
			favoriteProtectFromTrash = getBool(root, "favoriteProtectFromTrash", favoriteProtectFromTrash);
			favoriteProtectFromSort = getBool(root, "favoriteProtectFromSort", favoriteProtectFromSort);
			pinEnabled = getBool(root, "pinEnabled", pinEnabled);
			cursorIconsEnabled = getBool(root, "cursorIconsEnabled", cursorIconsEnabled);
			trashEnabled = getBool(root, "trashEnabled", trashEnabled);
			sortEnabled = getBool(root, "sortEnabled", sortEnabled);
			sortIncludeHotbar = getBool(root, "sortIncludeHotbar", sortIncludeHotbar);
			// 老版本留下过一批鼠标/滚轮手势的键（gesturesEnabled 等），功能已经删掉了，
			// 顺手把文件重写一遍，让这些僵尸键自己消失。
			needsRewrite = root.has("gesturesEnabled") || root.has("wheelItemsPerScroll")
					|| root.has("wheelWholeStackOnShift") || root.has("leftDragDistribute");
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
		root.addProperty("favoriteEnabled", favoriteEnabled);
		root.addProperty("favoriteProtectFromDrop", favoriteProtectFromDrop);
		root.addProperty("favoriteProtectFromTrash", favoriteProtectFromTrash);
		root.addProperty("favoriteProtectFromSort", favoriteProtectFromSort);
		root.addProperty("pinEnabled", pinEnabled);
		root.addProperty("cursorIconsEnabled", cursorIconsEnabled);
		root.addProperty("trashEnabled", trashEnabled);
		root.addProperty("sortEnabled", sortEnabled);
		root.addProperty("sortIncludeHotbar", sortIncludeHotbar);
		try {
			Files.createDirectories(PATH.getParent());
			Files.writeString(PATH, GSON.toJson(root), StandardCharsets.UTF_8);
		} catch (IOException e) {
			InventoryButler.LOGGER.warn("无法写入配置文件 {}", PATH, e);
		}
	}

	private static boolean getBool(JsonObject o, String key, boolean def) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsBoolean() : def;
	}
}
