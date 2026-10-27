#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成翻页器 App 图标（纯标准库，手写 PNG）。"""

from __future__ import annotations

import math
import struct
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
RES = HERE / "res"

SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
SS = 4  # 超采样倍数，用于抗锯齿


def round_rect_sdf(px, py, cx, cy, hw, hh, r):
    qx = abs(px - cx) - (hw - r)
    qy = abs(py - cy) - (hh - r)
    return math.hypot(max(qx, 0.0), max(qy, 0.0)) + min(max(qx, qy), 0.0) - r


def in_triangle(px, py, a, b, c):
    def sign(p1, p2, p3):
        return (p1[0] - p3[0]) * (p2[1] - p3[1]) - (p2[0] - p3[0]) * (p1[1] - p3[1])
    d1 = sign((px, py), a, b)
    d2 = sign((px, py), b, c)
    d3 = sign((px, py), c, a)
    neg = d1 < 0 or d2 < 0 or d3 < 0
    pos = d1 > 0 or d2 > 0 or d3 > 0
    return not (neg and pos)


def lerp(a, b, t):
    return a + (b - a) * t


def sample(u, v, size):
    """u,v 为归一化坐标（0..1），返回 (r,g,b,a)。"""
    # 背景：圆角矩形 + 竖向渐变
    sdf = round_rect_sdf(u * size, v * size, size / 2, size / 2,
                         size / 2 - size * 0.004, size / 2 - size * 0.004, size * 0.225)
    if sdf > 0:
        return (0, 0, 0, 0)
    t = v
    bg = (int(lerp(0x10, 0x1B, t)), int(lerp(0x18, 0x28, t)), int(lerp(0x26, 0x45, t)))

    # 上箭头（蓝）
    up_tri = in_triangle(u, v, (0.50, 0.145), (0.305, 0.335), (0.695, 0.335))
    up_bar = (0.437 <= u <= 0.563) and (0.325 <= v <= 0.470)
    # 下箭头（白）
    dn_tri = in_triangle(u, v, (0.50, 0.855), (0.305, 0.665), (0.695, 0.665))
    dn_bar = (0.437 <= u <= 0.563) and (0.530 <= v <= 0.675)

    if up_tri or up_bar:
        return (0x4D, 0x8D, 0xFF, 255)
    if dn_tri or dn_bar:
        return (0xE8, 0xED, 0xF7, 255)
    # 中间分割线
    if 0.494 <= v <= 0.506 and 0.30 <= u <= 0.70:
        return (0x35, 0x45, 0x5E, 255)
    return (bg[0], bg[1], bg[2], 255)


def render(size):
    w = size * SS
    rows = []
    for y in range(w):
        row = bytearray()
        for x in range(w):
            row += bytes(sample(x / w, y / w, w))
        rows.append(bytes(row))

    # box filter 降采样
    out = bytearray()
    for y in range(size):
        for x in range(size):
            r = g = b = a = 0
            for dy in range(SS):
                base = rows[y * SS + dy]
                for dx in range(SS):
                    i = (x * SS + dx) * 4
                    r += base[i]; g += base[i + 1]; b += base[i + 2]; a += base[i + 3]
            n = SS * SS
            out += bytes((r // n, g // n, b // n, a // n))
    return bytes(out), size


def write_png(path, pixels, size):
    raw = b""
    for y in range(size):
        raw += b"\x00" + pixels[y * size * 4:(y + 1) * size * 4]

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png)


def main():
    for dens, size in SIZES.items():
        px, s = render(size)
        out = RES / f"mipmap-{dens}" / "ic_launcher.png"
        write_png(out, px, s)
        print(f"{out.name:<16} {dens:<8} {size}x{size}  {out.stat().st_size} B")


if __name__ == "__main__":
    main()
