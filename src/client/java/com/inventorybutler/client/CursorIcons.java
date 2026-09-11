package com.inventorybutler.client;

import com.inventorybutler.InventoryButler;
import com.inventorybutler.ModConfig;
import com.inventorybutler.client.mixin.CursorTypeInvoker;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.cursor.CursorType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.Slot;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.system.MemoryUtil;

import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;

/**
 * 「按住修饰键时鼠标指针换图标」。
 *
 * <p>按住 <b>Alt</b>（收藏）指针变成金色星星，按住 <b>Ctrl</b>（删除）变成垃圾桶，
 * 松手立刻变回箭头。图标只在指针确实停在<b>能生效的槽位</b>上时才换 ——
 * 界面空白处、铁砧的输入框上都不换，免得你按 Ctrl+C 打字时指针变成垃圾桶。</p>
 *
 * <p><b>为什么不自己调 GLFW？</b>因为原版留了一条更好的路：
 * {@code Gui} 每帧末尾会调 {@code GuiGraphicsExtractor.applyCursor(window)}，
 * 把当帧攒下来的 {@code pendingCursor} 交给 {@code Window.selectCursor}。
 * 而 {@code selectCursor} 是按<b>引用</b>去重的（{@code currentCursor != target} 才真的
 * 调 {@code glfwSetCursor}）。于是只要往 {@code pendingCursor} 里塞一个自定义
 * {@link CursorType}，就同时得到两个好处：</p>
 * <ul>
 *   <li>恢复箭头不用我管 —— 渲染器每帧都是新建的、{@code pendingCursor} 每帧从
 *       {@code DEFAULT} 起步，我一停止覆盖，原版下一帧自己就换回来了；</li>
 *   <li>不会和输入框的 I 型光标、链接的手型光标打架 —— 那些是别的组件通过
 *       {@code requestCursor} 提的，我停止覆盖之后它们照常生效。</li>
 * </ul>
 *
 * <p><b>为什么要自己造 CursorType？</b>因为 26.2 的 {@code CursorType} 只暴露了
 * {@code createStandardCursor}（系统标准光标），没有「用图片当光标」的接口，
 * 私有的 {@code (String, long)} 构造器外面够不着。所以就自己用
 * {@code GLFW.glfwCreateCursor} 造 GLFW 光标，再用 {@link CursorTypeInvoker}
 * 借一下那个私有构造器，把它包成 {@code CursorType}。</p>
 */
public final class CursorIcons {

	/**
	 * 位图物理边长（GLFW 拿到的实际尺寸）。
	 *
	 * <p>GLFW 会把整张图原样交给系统，不跟着游戏的 GUI 缩放变。Windows 上光标位图
	 * 常见尺寸就是 32，取 32 既不糊也不会比原版箭头大太多。</p>
	 */
	private static final int SIZE = 32;

	/**
	 * 逻辑分辨率：光标实际是 16x16 的粗点阵画，每个「画素」占 {@code SIZE/16 = 2x2}
	 * 个物理像素。
	 *
	 * <p>之前用 32x32 + 4x4 超采样画的是带抗锯齿的「平滑图」，边缘半透明的过渡像素
	 * 一多反而显得糊、不像这套 UI 的风格。改成 16x16 硬边像素画：无过渡、无半透明，
	 * 边缘全是齐刷刷的 2x2 方块 —— 和原版 UI 的像素味一致，远看也更清楚。</p>
	 */
	private static final int LOGICAL = 16;

	/** 一个逻辑画素的物理边长（SIZE / LOGICAL = 2）。 */
	private static final int PIXEL = SIZE / LOGICAL;

	/**
	 * 热点：正中心。
	 *
	 * <p>原版箭头热点在图的左上角（箭尖），换成居中图标之后热点也就跟着挪到中心。
	 * 这不影响精度 —— 系统就是按热点算鼠标坐标的，看见的图标中心 = 真正点下去的位置，
	 * 两者自洽，玩家一秒就能适应。</p>
	 */
	private static final int HOTSPOT = SIZE / 2;

	/** 5 角星：外接半径，以及内接半径相对外接半径的比例（正五角星的经典值）。 */
	private static final double STAR_RADIUS = 0.427;
	private static final double STAR_INNER_RATIO = 0.382;
	/** 描边宽度（归一化单位，0.075 ≈ 2.4 像素）。 */
	private static final double OUTLINE = 0.075;
	/**
	 * 星形包围盒的垂直中心。
	 *
	 * <p>正五角星「上 1 尖、下 2 尖」，包围盒并不以中心对称：上面伸出 R、下面伸出
	 * {@code sin(54°)·R ≈ 0.809R}。所以要把中心下移一点，星形在画布里才是居中的。</p>
	 */
	private static final double STAR_CENTER_Y = 0.541;

	private static final int STAR_FILL = 0xFFFFC93C;
	private static final int STAR_LINE = 0xFF5A3A00;
	private static final int TRASH_FILL = 0xFFE2E2E2;
	private static final int TRASH_LINE = 0xFF2B2B2B;

	private static CursorType starCursor;
	private static CursorType trashCursor;
	/** 造光标失败过就不再重试，免得每帧都刷日志。 */
	private static boolean unavailable;

	/**
	 * 本帧「指针停在能收藏的格子上」/「停在能删除的格子上」。
	 *
	 * <p>由 {@code AbstractContainerScreenMixin} 在每次绘制物品栏时上报。
	 * {@link #contextReady} 是配套的「本帧确实画过物品栏界面」标志：
	 * 换了界面、关了界面，它就不会被重新置位，指针自然也就不换图标了。</p>
	 */
	private static boolean contextReady;
	private static boolean overFavoriteTarget;
	private static boolean overTrashTarget;

	private CursorIcons() {
	}

	/**
	 * 物品栏界面每次绘制时上报一次「指针现在指着什么」。
	 *
	 * <p>传 null 表示没指着槽位。创造模式物品栏那边压根不会调到这里
	 * （那个界面整列自绘按钮都不画，这里跟着一起跳过）。</p>
	 */
	public static void reportPointerTarget(Slot slot) {
		contextReady = true;
		// 和 AbstractContainerScreenMixin 里真正发包的条件保持一致：
		// 收藏只要有槽位就行，删除还要求这一格允许转移（箱子格不行）。
		overFavoriteTarget = slot != null;
		overTrashTarget = slot != null && SlotSections.acceptsTransfer(slot);
	}

	/**
	 * 这一帧要不要强行换光标。返回 null = 不干预，交回原版。
	 *
	 * <p>这个方法是「消费型」的：读过一次就把 {@link #contextReady} 清掉，
	 * 于是下一次绘制物品栏之前（比如界面已经关了）它都会一直返回 null。</p>
	 */
	public static CursorType override() {
		boolean wasContainerScreen = contextReady;
		contextReady = false;

		if (!wasContainerScreen || unavailable || !ModConfig.cursorIconsEnabled) {
			return null;
		}

		Minecraft mc = Minecraft.getInstance();
		if (mc == null) {
			return null;
		}
		Window window = mc.getWindow();
		if (window == null) {
			return null;
		}

		// Alt 优先于 Ctrl —— 和 mouseClicked 里的判定顺序一致，两个键一起按时结果不会打架。
		if (overFavoriteTarget && ModConfig.favoriteEnabled && isDown(window, GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT)) {
			return star(mc);
		}
		if (overTrashTarget && ModConfig.trashEnabled && isDown(window, GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL)) {
			return trash(mc);
		}
		return null;
	}

	/**
	 * 退出存档 / 关掉界面时清一下。
	 *
	 * <p>其实不清也无所谓（{@code reportPointerTarget} 每帧都会覆写），
	 * 但显式清掉更符合直觉，也省得排查问题时被上一局的残留状态误导。</p>
	 */
	public static void reset() {
		contextReady = false;
		overFavoriteTarget = false;
		overTrashTarget = false;
	}

	private static boolean isDown(Window window, int left, int right) {
		return InputConstants.isKeyDown(window, left) || InputConstants.isKeyDown(window, right);
	}

	private static CursorType star(Minecraft mc) {
		if (starCursor == null) {
			starCursor = build(mc, true);
		}
		return starCursor;
	}

	private static CursorType trash(Minecraft mc) {
		if (trashCursor == null) {
			trashCursor = build(mc, false);
		}
		return trashCursor;
	}

	/** 画位图 → 造 GLFW 光标 → 包成 CursorType。三样都不成的话返回 null 并永久停用。 */
	private static CursorType build(Minecraft mc, boolean star) {
		ByteBuffer pixels = MemoryUtil.memAlloc(SIZE * SIZE * 4);
		GLFWImage.Buffer image = null;
		try {
			paint(pixels, star);
			pixels.flip();

			image = GLFWImage.malloc(1);
			image.width(SIZE);
			image.height(SIZE);
			image.pixels(pixels);

			// GLFW 会把图像数据拷走，所以这个临时缓冲区可以随即释放。
			long handle = GLFW.glfwCreateCursor(image.get(0), HOTSPOT, HOTSPOT);
			if (handle == 0L) {
				unavailable = true;
				InventoryButler.LOGGER.warn("[{}] 创建自定义光标失败（GLFW 返回 0），指针图标功能关闭",
						InventoryButler.MOD_ID);
				return null;
			}

			CursorType type = wrap(star ? "inventorybutler_star" : "inventorybutler_trash", handle);
			if (type == null) {
				GLFW.glfwDestroyCursor(handle);
				unavailable = true;
			}
			return type;
		} catch (Throwable t) {
			unavailable = true;
			InventoryButler.LOGGER.warn("[{}] 创建自定义光标时出错，指针图标功能关闭: {}",
					InventoryButler.MOD_ID, t.toString());
			return null;
		} finally {
			if (image != null) {
				image.free();
			}
			MemoryUtil.memFree(pixels);
		}
	}

	/**
	 * 把 GLFW 光标句柄包成原版的 {@link CursorType}。
	 *
	 * <p>首选 {@link CursorTypeInvoker}（mixin 借私有构造器）—— 它是编译期就会校验的，
	 * 目标一改这里直接编译不过，比运行时才发现要早。万一 mixin 因为别的原因没应用上
	 * （比如和别的 mod 的 mixin 配置撞了），退回反射，功能不至于整个废掉。</p>
	 */
	private static CursorType wrap(String name, long handle) {
		try {
			return CursorTypeInvoker.inventorybutler$create(name, handle);
		} catch (Throwable primary) {
			try {
				Constructor<CursorType> ctor = CursorType.class.getDeclaredConstructor(String.class, long.class);
				ctor.setAccessible(true);
				return ctor.newInstance(name, handle);
			} catch (Throwable fallback) {
				InventoryButler.LOGGER.warn("[{}] 无法构造 CursorType: {}", InventoryButler.MOD_ID, fallback.toString());
				return null;
			}
		}
	}

	// ------------------------------------------------------------------
	// 位图：先按 16x16 逻辑分辨率点采样成硬边点阵，再放大成 2x2 方块
	// ------------------------------------------------------------------

	private static void paint(ByteBuffer out, boolean star) {
		int[][] map = rasterize(star);
		for (int py = 0; py < SIZE; py++) {
			int[] row = map[py / PIXEL];
			for (int px = 0; px < SIZE; px++) {
				int color = row[px / PIXEL];
				if (color == 0) {
					// 全透明像素必须连 RGB 一起写 0：Windows 上有 alpha=0 但 RGB 非零的像素
					// 偶尔会被渲染成一圈黑边。
					out.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0);
					continue;
				}
				out.put((byte) ((color >> 16) & 0xFF))
						.put((byte) ((color >> 8) & 0xFF))
						.put((byte) (color & 0xFF))
						.put((byte) 0xFF);
			}
		}
	}

	/**
	 * 在 16x16 逻辑网格上点采样出整张点阵（0 = 透明，否则 ARGB）。
	 *
	 * <p>每个逻辑画素只取<b>中心一个点</b>做判定，要么整个画素着色、要么整个透明，
	 * 没有半透明的过渡像素 —— 硬边像素画就是这么来的。轮廓 / 填充的区分沿用
	 * 「内缩一圈」的思路：落在内缩轮廓之外的一圈画成描边色。</p>
	 */
	private static int[][] rasterize(boolean star) {
		int[][] map = new int[LOGICAL][LOGICAL];
		for (int ly = 0; ly < LOGICAL; ly++) {
			for (int lx = 0; lx < LOGICAL; lx++) {
				double u = (lx + 0.5) / LOGICAL;
				double v = (ly + 0.5) / LOGICAL;
				boolean outer = star ? insidePolygon(STAR_OUTER, u, v) : trashOuter(u, v);
				if (!outer) {
					continue;
				}
				boolean inner = star ? insidePolygon(STAR_INNER, u, v) : trashInner(u, v);
				if (!inner) {
					map[ly][lx] = star ? STAR_LINE : TRASH_LINE;
				} else {
					map[ly][lx] = star ? STAR_FILL : TRASH_FILL;
				}
			}
		}
		return map;
	}

	private static final double[] STAR_OUTER = starPolygon(STAR_RADIUS);
	private static final double[] STAR_INNER = starPolygon(STAR_RADIUS - OUTLINE);

	/** 10 个顶点交替取外接 / 内接半径，从正上方那一尖开始。 */
	private static double[] starPolygon(double radius) {
		double[] points = new double[20];
		for (int i = 0; i < 10; i++) {
			double r = (i % 2 == 0) ? radius : radius * STAR_INNER_RATIO;
			double angle = -Math.PI / 2 + i * Math.PI / 5;
			points[i * 2] = 0.5 + Math.cos(angle) * r;
			points[i * 2 + 1] = STAR_CENTER_Y + Math.sin(angle) * r;
		}
		return points;
	}

	/** 射线法判断点在不在多边形里。顶点不多，直接用最朴素的写法。 */
	private static boolean insidePolygon(double[] polygon, double px, double py) {
		int count = polygon.length / 2;
		boolean inside = false;
		for (int i = 0, j = count - 1; i < count; j = i++) {
			double xi = polygon[i * 2];
			double yi = polygon[i * 2 + 1];
			double xj = polygon[j * 2];
			double yj = polygon[j * 2 + 1];
			if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) {
				inside = !inside;
			}
		}
		return inside;
	}

	/**
	 * 垃圾桶外轮廓：<b>敞口、顶部向内凹</b>的桶 —— 两侧的口沿立壁比中间的口沿
	 * 高出一截，中间凹下去形成「往里扔」的开口。
	 *
	 * <p>和按钮上的垃圾桶图标同一个造型：没有盖子和提手，两条立壁 + 凹下去的桶口
	 * 就是辨识度本身。立壁保持实心深色 —— 16 逻辑像素上再掏孔就糊成一团了。</p>
	 */
	private static boolean trashOuter(double u, double v) {
		// 两侧的口沿立壁：比桶身上沿（0.36）再高出约 3 个逻辑像素
		if (((u >= 0.17 && u <= 0.33) || (u >= 0.67 && u <= 0.83)) && v >= 0.13 && v <= 0.36) {
			return true;
		}
		return trashBody(u, v, 0.0);
	}

	/** 垃圾桶内部（填充色）：只有桶身填浅色，口沿立壁保持深色描边。 */
	private static boolean trashInner(double u, double v) {
		return trashBody(u, v, 0.045);
	}

	/**
	 * 桶身：上宽下窄的梯形。
	 *
	 * <p>{@code inset} 同时收紧上下边缘和左右半宽，就把梯形整体缩小了一圈。</p>
	 */
	private static boolean trashBody(double u, double v, double inset) {
		double top = 0.36 + inset;
		double bottom = 0.95 - inset;
		if (v < top || v > bottom) {
			return false;
		}
		double t = (v - top) / (bottom - top);
		double halfWidth = (0.31 - inset) + ((0.22 - inset) - (0.31 - inset)) * t;
		return halfWidth > 0 && u >= 0.5 - halfWidth && u <= 0.5 + halfWidth;
	}
}
