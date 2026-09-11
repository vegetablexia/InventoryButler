#!/usr/bin/env python3
"""生成 mod 图标 assets/inventoryshortcuts/icon.png（纯标准库，无需 Pillow）。

设计：深色圆角底 + 3x3 物品格 + 左上角金色收藏星标。
可修改下面的常量后重跑：python tools/gen_icon.py
"""

import struct
import zlib
from pathlib import Path

SIZE = 128

BG = (43, 43, 51, 255)          # 底板
BORDER = (74, 74, 87, 255)      # 边框
SLOT = (110, 110, 122, 255)     # 物品格
SLOT_INNER = (86, 86, 98, 255)  # 物品格内阴影
GOLD = (255, 200, 61, 255)      # 收藏色
GOLD_DARK = (176, 124, 20, 255)  # 星标描边
CLEAR = (0, 0, 0, 0)

# 7x7 星形位图
STAR = [
    "...#...",
    "...#...",
    "#######",
    ".#####.",
    "..###..",
    ".##.##.",
    "##...##",
]


def new_canvas():
    return [[CLEAR] * SIZE for _ in range(SIZE)]


def put(canvas, x, y, color):
    if 0 <= x < SIZE and 0 <= y < SIZE:
        canvas[y][x] = color


def fill_rect(canvas, x0, y0, w, h, color):
    for y in range(y0, y0 + h):
        for x in range(x0, x0 + w):
            put(canvas, x, y, color)


def fill_round_rect(canvas, x0, y0, w, h, r, color):
    """圆角矩形：四角用圆内判定裁剪。"""
    for y in range(y0, y0 + h):
        for x in range(x0, x0 + w):
            dx = 0
            dy = 0
            if x < x0 + r:
                dx = x0 + r - x
            elif x >= x0 + w - r:
                dx = x - (x0 + w - r - 1)
            if y < y0 + r:
                dy = y0 + r - y
            elif y >= y0 + h - r:
                dy = y - (y0 + h - r - 1)
            if dx * dx + dy * dy <= r * r:
                put(canvas, x, y, color)


def draw_star(canvas, cx, cy, scale, color, outline):
    """以 (cx, cy) 为中心画 7x7 星形，scale 为每个像素的边长。"""
    w = 7 * scale
    ox = cx - w // 2
    oy = cy - w // 2
    filled = set()
    for ry, row in enumerate(STAR):
        for rx, ch in enumerate(row):
            if ch == '#':
                for dy in range(scale):
                    for dx in range(scale):
                        put(canvas, ox + rx * scale + dx, oy + ry * scale + dy, color)
                        filled.add((ox + rx * scale + dx, oy + ry * scale + dy))
    # 描边：所有与实心像素 4 邻接的空像素
    for (x, y) in filled:
        for (nx, ny) in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
            if (nx, ny) not in filled and 0 <= nx < SIZE and 0 <= ny < SIZE:
                put(canvas, nx, ny, outline)


def build():
    c = new_canvas()
    fill_round_rect(c, 4, 4, SIZE - 8, SIZE - 8, 20, BORDER)
    fill_round_rect(c, 8, 8, SIZE - 16, SIZE - 16, 16, BG)

    # 3x3 物品格
    slot, gap = 24, 6
    grid = slot * 3 + gap * 2
    sx = (SIZE - grid) // 2
    sy = (SIZE - grid) // 2 + 4
    for row in range(3):
        for col in range(3):
            x = sx + col * (slot + gap)
            y = sy + row * (slot + gap)
            fill_round_rect(c, x, y, slot, slot, 4, SLOT)
            fill_rect(c, x + 2, y + 2, slot - 4, slot - 4, SLOT_INNER)

    # 左上格内放金色星标，表示“已收藏”
    draw_star(c, sx + slot // 2, sy + slot // 2, 4, GOLD, GOLD_DARK)
    # 右下格内放一个“销毁”提示色块
    fill_round_rect(c, sx + 2 * (slot + gap) + 4, sy + 2 * (slot + gap) + 4,
                    slot - 8, slot - 8, 3, (196, 76, 76, 255))
    return c


def write_png(path, canvas):
    raw = bytearray()
    for row in canvas:
        raw.append(0)  # filter type: none
        for (r, g, b, a) in row:
            raw += bytes((r, g, b, a))
    def chunk(tag, data):
        out = struct.pack('>I', len(data)) + tag + data
        return out + struct.pack('>I', zlib.crc32(tag + data) & 0xFFFFFFFF)
    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', SIZE, SIZE, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(bytes(raw), 9))
    png += chunk(b'IEND', b'')
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png)


if __name__ == '__main__':
    out = Path(__file__).resolve().parent.parent / 'src/main/resources/assets/inventoryshortcuts/icon.png'
    write_png(out, build())
    print(f'wrote {out}')
