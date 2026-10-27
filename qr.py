#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
极简 QR 码生成器 —— 仅用标准库，输出 SVG。
范围：Byte 模式 / 纠错等级 M / Version 1-4，足够容纳局域网 URL。
"""

from __future__ import annotations

# 每个版本 EC=M 的 (数据块数, 每块数据码字, 每块纠错码字)
_SPEC_M: dict[int, tuple[int, int, int]] = {
    1: (1, 16, 10),
    2: (1, 28, 16),
    3: (1, 44, 26),
    4: (2, 32, 18),
}

# ---- GF(256) 表，本原多项式 0x11D -------------------------------------
_EXP = [0] * 512
_LOG = [0] * 256
_x = 1
for _i in range(255):
    _EXP[_i] = _x
    _LOG[_x] = _i
    _x <<= 1
    if _x & 0x100:
        _x ^= 0x11D
for _i in range(255, 512):
    _EXP[_i] = _EXP[_i - 255]


def _mul(a: int, b: int) -> int:
    if a == 0 or b == 0:
        return 0
    return _EXP[_LOG[a] + _LOG[b]]


def _rs_generator(n: int) -> list[int]:
    """生成多项式 ∏(x - α^i)，降幂存储。"""
    g = [1]
    for i in range(n):
        ng = [0] * (len(g) + 1)
        e = _EXP[i]
        for j, c in enumerate(g):
            ng[j] ^= c
            ng[j + 1] ^= _mul(c, e)
        g = ng
    return g


def _rs_encode(data: list[int], necs: int) -> list[int]:
    gen = _rs_generator(necs)
    res = list(data) + [0] * necs
    for i in range(len(data)):
        coef = res[i]
        if coef:
            for j, gc in enumerate(gen):
                res[i + j] ^= _mul(gc, coef)
    return res[len(data):]


# ---- BCH(15,5) 格式信息 -----------------------------------------------
def _bit_len(x: int) -> int:
    return x.bit_length()


def _format_bits(data: int) -> int:
    d = data << 10
    while _bit_len(d) >= 11:
        d ^= 0x537 << (_bit_len(d) - 11)
    return ((data << 10) | d) ^ 0x5412


# ---- 数据编码 ----------------------------------------------------------
def _pick_version(nbytes: int) -> int | None:
    for v in (1, 2, 3, 4):
        blocks, per, _ = _SPEC_M[v]
        total = blocks * per
        count_bits = 8 if v < 10 else 16
        if 4 + count_bits + nbytes * 8 <= total * 8:
            return v
    return None


def _build_codewords(payload: bytes, version: int) -> list[int]:
    blocks, per, necs = _SPEC_M[version]
    total_data = blocks * per

    bits: list[int] = []

    def put(val: int, n: int) -> None:
        for i in range(n - 1, -1, -1):
            bits.append((val >> i) & 1)

    put(0b0100, 4)
    put(len(payload), 8)
    for b in payload:
        put(b, 8)

    total_bits = total_data * 8
    bits += [0] * min(4, total_bits - len(bits))
    while len(bits) % 8:
        bits.append(0)
    pad = (0xEC, 0x11)
    k = 0
    while len(bits) < total_bits:
        put(pad[k % 2], 8)
        k += 1

    cws = [int("".join(map(str, bits[i:i + 8])), 2) for i in range(0, total_bits, 8)]

    data_blocks: list[list[int]] = []
    ec_blocks: list[list[int]] = []
    idx = 0
    for _ in range(blocks):
        blk = cws[idx:idx + per]
        idx += per
        data_blocks.append(blk)
        ec_blocks.append(_rs_encode(blk, necs))

    out: list[int] = []
    for i in range(per):
        for blk in data_blocks:
            out.append(blk[i])
    for i in range(necs):
        for e in ec_blocks:
            out.append(e[i])
    return out


# ---- 矩阵构建 ----------------------------------------------------------
def _build_matrix(version: int, codewords: list[int], mask_id: int = 0) -> list[list[int]]:
    size = 17 + 4 * version
    m: list[list[int | None]] = [[None] * size for _ in range(size)]

    # 定位图案（含分隔符）
    for cr, cc in ((3, 3), (3, size - 4), (size - 4, 3)):
        for dr in range(-4, 5):
            for dc in range(-4, 5):
                r, c = cr + dr, cc + dc
                if 0 <= r < size and 0 <= c < size:
                    d = max(abs(dr), abs(dc))
                    m[r][c] = 1 if d in (0, 1, 3) else 0

    # 定时图案
    for i in range(8, size - 8):
        v = 1 if i % 2 == 0 else 0
        m[6][i] = v
        m[i][6] = v

    # 校正图案（V2+ 位于右下）
    if version > 1:
        cr = cc = size - 7
        for dr in range(-2, 3):
            for dc in range(-2, 3):
                if m[cr + dr][cc + dc] is None:
                    m[cr + dr][cc + dc] = 0 if max(abs(dr), abs(dc)) == 1 else 1

    # 预留格式信息区
    for i in range(9):
        if m[8][i] is None:
            m[8][i] = 0
        if m[i][8] is None:
            m[i][8] = 0
    for i in range(8):
        m[8][size - 1 - i] = 0
        m[size - 1 - i][8] = 0
    m[size - 8][8] = 1  # 暗模块

    # 数据位放置（右下起，两列一组，蛇形）
    bits: list[int] = []
    for cw in codewords:
        for i in range(7, -1, -1):
            bits.append((cw >> i) & 1)

    inc = -1
    row = size - 1
    col = size - 1
    idx = 0
    while col > 0:
        if col == 6:
            col -= 1
        while True:
            for c in (col, col - 1):
                if m[row][c] is None:
                    m[row][c] = bits[idx] if idx < len(bits) else 0
                    idx += 1
            row += inc
            if row < 0 or row >= size:
                row -= inc
                inc = -inc
                break
        col -= 2

    # 掩码
    for r in range(size):
        for c in range(size):
            if _is_function(version, size, r, c):
                continue
            if _mask(mask_id, r, c):
                m[r][c] ^= 1

    # 写回格式信息
    fmt = _format_bits(0 << 3 | mask_id)  # 纠错等级 M = 0
    for i in range(15):
        bit = (fmt >> i) & 1
        if i < 6:
            m[i][8] = bit
        elif i < 8:
            m[i + 1][8] = bit
        else:
            m[size - 15 + i][8] = bit
    for i in range(15):
        bit = (fmt >> i) & 1
        if i < 8:
            m[8][size - 1 - i] = bit
        elif i == 8:
            m[8][7] = bit
        else:
            m[8][14 - i] = bit
    m[size - 8][8] = 1

    return [[int(v) for v in row_] for row_ in m]


def _mask(mask_id: int, r: int, c: int) -> bool:
    if mask_id == 0:
        return (r + c) % 2 == 0
    if mask_id == 1:
        return r % 2 == 0
    return (r * c) % 3 == 0


def _is_function(version: int, size: int, r: int, c: int) -> bool:
    # 定位 + 分隔符
    if (r < 9 and c < 9) or (r < 9 and c >= size - 8) or (r >= size - 8 and c < 9):
        return True
    if r == 6 or c == 6:
        return True
    if version > 1:
        cr = cc = size - 7
        if abs(r - cr) <= 2 and abs(c - cc) <= 2:
            return True
    return False


# ---- 对外接口 ----------------------------------------------------------
def make_matrix(text: str) -> list[list[int]]:
    """返回 QR 矩阵（1=黑），失败抛 ValueError。"""
    payload = text.encode("utf-8")
    version = _pick_version(len(payload))
    if version is None:
        raise ValueError(f"内容过长（{len(payload)} 字节），超出本生成器能力")
    cws = _build_codewords(payload, version)
    return _build_matrix(version, cws, mask_id=0)


def make_svg(text: str, scale: int = 8, border: int = 4, dark: str = "#0a0d13",
             light: str = "#ffffff") -> str:
    m = make_matrix(text)
    size = len(m)
    dim = (size + border * 2) * scale
    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{dim}" height="{dim}" '
        f'viewBox="0 0 {dim} {dim}" shape-rendering="crispEdges">',
        f'<rect width="{dim}" height="{dim}" fill="{light}"/>',
        f'<path fill="{dark}" d="',
    ]
    d: list[str] = []
    for r in range(size):
        c = 0
        while c < size:
            if m[r][c]:
                start = c
                while c < size and m[r][c]:
                    c += 1
                x = (start + border) * scale
                y = (r + border) * scale
                d.append(f"M{x} {y}h{(c - start) * scale}v{scale}h-{(c - start) * scale}z")
            else:
                c += 1
    parts.append("".join(d))
    parts.append('"/></svg>')
    return "".join(parts)


if __name__ == "__main__":
    import sys

    url = sys.argv[1] if len(sys.argv) > 1 else "http://192.168.1.100:8000"
    mm = make_matrix(url)
    print(f"text={url}  size={len(mm)}x{len(mm)}")
    for row in mm:
        print("".join("██" if v else "  " for v in row))
