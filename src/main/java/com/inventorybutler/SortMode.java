package com.inventorybutler;

/**
 * 整理<b>打开的储存容器</b>时使用的落位规则。
 *
 * <p>玩家背包的整理始终按 {@link #CATEGORY} 进行；这个枚举只决定容器里的物品怎么放。
 * 每个玩家通过 {@code /inventorybutler sortmode <mode>} 单独选择，互不影响。</p>
 *
 * <p>默认是 {@link #CATEGORY}，所以老玩家升级后行为不变；想要「同种物品只往最靠上的
 * 那一堆上加」的玩家可以自己切到 {@link #TOPMOST}。</p>
 */
public enum SortMode {
	/** 旧行为：合并后按分类 -&gt; 物品 id -&gt; 数量重新排序。 */
	CATEGORY("category", "inventorybutler.sortmode.category"),

	/**
	 * 原位堆叠：同种物品只往它当前最靠上的那一堆上加，物品种类不换格子；
	 * 同种格在合并后空出来的位置保持为空，不会被别的种类填上。
	 */
	TOPMOST("topmost", "inventorybutler.sortmode.topmost");

	private final String id;
	private final String translationKey;

	SortMode(String id, String translationKey) {
		this.id = id;
		this.translationKey = translationKey;
	}

	public String id() {
		return id;
	}

	public String translationKey() {
		return translationKey;
	}

	/** 配置文件和指令里用的字符串；无法识别时退回旧版分类整理。 */
	public static SortMode fromId(String id) {
		if (id != null) {
			for (SortMode mode : values()) {
				if (mode.id.equalsIgnoreCase(id)) {
					return mode;
				}
			}
		}
		return CATEGORY;
	}
}