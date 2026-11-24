#!/usr/bin/env python3
"""
OptIcon M2 — 重绘引擎实验台（策略三黑盒化批次）

对 IconRedrawEngine 的 Python 忠实移植（M1 算法：双模式背景估计 + 色距键控 + 覆盖率守卫），
批量跑真实语料、计算三指标（前景覆盖率 / 白占比 / 与官方单色参照的剪影 IoU）、
按输入类分组网格搜索阈值，产出参数表（M3 分级路由的输入）。

忠实性契约：
  - 量化桶/守卫阈值/键控语义与 app/src/main/.../IconRedrawEngine.kt 逐行对应，
    改 Kotlin 核心必须同步改这里（对拍由单测+抽样目检保证）。
  - 渲染段（缩放/偏移/圆角）不影响键控指标，harness 只实现键控级。

用法：
  python redraw_harness.py selftest                      # 合成夹具自测（四输入类全覆盖）
  python redraw_harness.py run --pure <apk-or-zip> --anip <dir> [--grid] [--out report.md]
  python redraw_harness.py run --sources <dir> --refs <dir> --pairs <pairs.json> [...]

指标：
  coverage  键控后前景不透明占比（守卫带 1%~90%）
  white     输出中纯白(255,255,255)像素占前景比（半透明羽化占比 = 1-white）
  iou       输出剪影 vs 参照单色剪影的交并比（96x96 二值化后）
"""

from __future__ import annotations
import argparse
import io
import json
import math
import re
import struct
import sys
import zipfile
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path

OUTPUT_SIZE = 96
FG_COVERAGE_MAX = 0.90
FG_COVERAGE_MIN = 0.01
OPAQUE_ALPHA = 128


# ━━━━━━━━━━ 忠实移植段（对应 IconRedrawEngine.kt M1） ━━━━━━━━━━

def estimate_background(pixels, w, h):
    """≥3 不透明角 → 角均值 CORNER_SAMPLE；否则全图量化主色 DOMINANT_COLOR；全透明 → None"""
    corners = [pixels[0], pixels[w - 1], pixels[(h - 1) * w], pixels[h * w - 1]]
    opaque = [c for c in corners if ((c >> 24) & 0xFF) > OPAQUE_ALPHA]
    if len(opaque) >= 3:
        r = sum((c >> 16) & 0xFF for c in opaque) // len(opaque)
        g = sum((c >> 8) & 0xFF for c in opaque) // len(opaque)
        b = sum(c & 0xFF for c in opaque) // len(opaque)
        return ((r << 16) | (g << 8) | b, "CORNER_SAMPLE")
    dom = dominant_opaque_color(pixels)
    return (dom, "DOMINANT_COLOR") if dom is not None else None


def dominant_opaque_color(pixels):
    buckets = Counter()
    for p in pixels:
        if ((p >> 24) & 0xFF) <= OPAQUE_ALPHA:
            continue
        key = (((p >> 16) & 0xFF) // 32 << 10) | (((p >> 8) & 0xFF) // 32 << 5) | ((p & 0xFF) // 32)
        buckets[key] += 1
    if not buckets:
        return None
    k = max(buckets, key=buckets.get)
    r = ((k >> 10) & 0x1F) * 32 + 16
    g = ((k >> 5) & 0x1F) * 32 + 16
    b = (k & 0x1F) * 32 + 16
    return (r << 16) | (g << 8) | b


def key_background(pixels, bg_rgb, threshold):
    br, bg_, bb = (bg_rgb >> 16) & 0xFF, (bg_rgb >> 8) & 0xFF, bg_rgb & 0xFF
    t2 = threshold * threshold
    out = [0] * len(pixels)
    for i, c in enumerate(pixels):
        a = (c >> 24) & 0xFF
        if a == 0:
            continue
        dr = ((c >> 16) & 0xFF) - br
        dg = ((c >> 8) & 0xFF) - bg_
        db = (c & 0xFF) - bb
        if dr * dr + dg * dg + db * db <= t2:
            continue  # 背景 → 透明
        out[i] = (a << 24) | 0x00FFFFFF
    return out


def foreground_coverage(pixels):
    if not pixels:
        return 0.0
    fg = sum(1 for p in pixels if ((p >> 24) & 0xFF) >= OPAQUE_ALPHA)
    return fg / len(pixels)


def alpha_whiten(pixels):
    """TRANSPARENT_MARGIN 通路：保 alpha 刷白（对应 Kotlin alphaWhiten）"""
    return [((p >> 24) & 0xFF) << 24 | 0x00FFFFFF if ((p >> 24) & 0xFF) else 0 for p in pixels]


def key_bilinear_gradient(pixels, w, h, threshold):
    """GRADIENT 通路：四角色双线性插值逐像素背景（对应 Kotlin keyBilinearGradient）"""
    w1, h1 = w - 1, h - 1
    c00, c10, c01, c11 = pixels[0], pixels[w1], pixels[h1 * w], pixels[-1]
    if all(((c >> 24) & 0xFF) <= OPAQUE_ALPHA for c in (c00, c10, c01, c11)):
        return None
    t2 = float(threshold * threshold)

    def ch(c, s):
        return float((c >> s) & 0xFF)

    out = [0] * len(pixels)
    for y in range(h):
        v = 0.0 if h1 == 0 else y / h1
        for x in range(w):
            i = y * w + x
            c = pixels[i]
            a = (c >> 24) & 0xFF
            if a == 0:
                continue
            u = 0.0 if w1 == 0 else x / w1
            w00, w10, w01, w11 = (1 - u) * (1 - v), u * (1 - v), (1 - u) * v, u * v
            br = ch(c00, 16) * w00 + ch(c10, 16) * w10 + ch(c01, 16) * w01 + ch(c11, 16) * w11
            bg_ = ch(c00, 8) * w00 + ch(c10, 8) * w10 + ch(c01, 8) * w01 + ch(c11, 8) * w11
            bb = ch(c00, 0) * w00 + ch(c10, 0) * w10 + ch(c01, 0) * w01 + ch(c11, 0) * w11
            dr = ((c >> 16) & 0xFF) - br
            dg = ((c >> 8) & 0xFF) - bg_
            db = (c & 0xFF) - bb
            if dr * dr + dg * dg + db * db <= t2:
                continue
            out[i] = (a << 24) | 0x00FFFFFF
    return out


def redraw_core(pixels, w, h, threshold):
    """M3 路由版：TRANSPARENT_MARGIN→alpha 白化 / GRADIENT→双线性 / FLAT·MASK→角采样键控。
    返回 (keyed_pixels|None, coverage, bg_mode, reject_reason)"""
    if w == 0 or h == 0:
        return None, 0.0, None, "empty source"
    cls = classify_input(pixels, w, h)
    if cls == "transparent_margin":
        keyed, mode = alpha_whiten(pixels), "ALPHA_SILHOUETTE"
    elif cls == "gradient":
        keyed = key_bilinear_gradient(pixels, w, h, threshold)
        mode = "BILINEAR_GRADIENT"
        if keyed is None:
            return None, 0.0, mode, "gradient corners not opaque enough"
    else:
        est = estimate_background(pixels, w, h)
        if est is None:
            return None, 0.0, None, "no opaque pixels"
        keyed, mode = key_background(pixels, est[0], threshold), est[1]
    cov = foreground_coverage(keyed)
    if cov > FG_COVERAGE_MAX:
        return None, cov, mode, f"degenerate fg={cov:.2f}"
    if cov < FG_COVERAGE_MIN:
        return None, cov, mode, "over-keyed"
    return keyed, cov, mode, None


# ━━━━━━━━━━ PNG 编解码（纯 stdlib：zlib+struct 手解非交错 RGBA8） ━━━━━━━━━━

def decode_png(data: bytes):
    """解码 PNG → (pixels list[ARGB int], w, h)。仅支持非交错 truecolor/gray+alpha8，
    覆盖图标包与 ANIP 缓存的实际产出；调色板图回退失败。"""
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "not a png"
    pos, idat, w, h, bitd, ctype, pal, trns = 8, bytearray(), 0, 0, 0, 0, None, None
    while pos < len(data):
        ln = struct.unpack(">I", data[pos:pos + 4])[0]
        chunk = data[pos + 4:pos + 8]
        payload = data[pos + 8:pos + 8 + ln]
        if chunk == b"IHDR":
            w, h, bitd, ctype = struct.unpack(">IIBB", payload[:10])
        elif chunk == b"PLTE":
            pal = payload
        elif chunk == b"tRNS":
            trns = payload
        elif chunk == b"IDAT":
            idat += payload
        elif chunk == b"IEND":
            break
        pos += 12 + ln
    assert bitd == 8, f"unsupported bit depth {bitd}"
    assert ctype in (0, 2, 4, 6), f"unsupported color type {ctype}"
    ch = {0: 1, 2: 3, 4: 2, 6: 4}[ctype]
    raw = zlib_decompress(idat)
    stride = w * ch
    # 反滤波
    out = bytearray(h * stride)
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        ft = raw[p]; p += 1
        line = bytearray(raw[p:p + stride]); p += stride
        if ft == 1:
            for i in range(ch, stride):
                line[i] = (line[i] + line[i - ch]) & 0xFF
        elif ft == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif ft == 3:
            for i in range(stride):
                left = line[i - ch] if i >= ch else 0
                line[i] = (line[i] + ((left + prev[i]) >> 1)) & 0xFF
        elif ft == 4:
            for i in range(stride):
                a = line[i - ch] if i >= ch else 0
                b = prev[i]
                c = prev[i - ch] if i >= ch else 0
                pp = a + b - c
                pa, pb, pc = abs(pp - a), abs(pp - b), abs(pp - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        out[y * stride:(y + 1) * stride] = line
        prev = line
    pixels = [0] * (w * h)
    i = 0
    for y in range(h):
        row = out[y * stride:(y + 1) * stride]
        for x in range(w):
            o = x * ch
            if ctype == 6:
                a, r, g, b = row[o + 3], row[o], row[o + 1], row[o + 2]
            elif ctype == 2:
                a, r, g, b = 255, row[o], row[o + 1], row[o + 2]
            elif ctype == 4:
                a, g = row[o + 1], row[o]
                r = g; b = g
            else:
                g = row[o]
                a = trns[g] if (trns and g < len(trns)) else 255
                r = g; b = g
            pixels[i] = (a << 24) | (r << 16) | (g << 8) | b
            i += 1
    return pixels, w, h


def zlib_decompress(data):
    import zlib
    return zlib.decompress(data)


def encode_png(pixels, w, h):
    """pixels(ARGB) → PNG bytes（ctype 6 非交错，滤波 0）"""
    import zlib
    stride = w * 4
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        for x in range(w):
            p = pixels[y * w + x]
            raw += bytes([(p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF, (p >> 24) & 0xFF])
    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(bytes(raw), 6)) + chunk(b"IEND", b""))


# ━━━━━━━━━━ 指标 ━━━━━━━━━━

def silhouette(keyed) -> set:
    return {i for i, p in enumerate(keyed) if ((p >> 24) & 0xFF) >= OPAQUE_ALPHA}


def white_ratio(keyed) -> float:
    fg = [p for p in keyed if ((p >> 24) & 0xFF) >= OPAQUE_ALPHA]
    if not fg:
        return 0.0
    pure = sum(1 for p in fg if (p & 0x00FFFFFF) == 0x00FFFFFF)
    return pure / len(fg)


def iou_vs_reference(keyed, ref_pixels, ref_w, ref_h) -> float:
    """输出剪影 vs 参照剪影 IoU。参照按最近邻缩放到 96x96 后按格交集。"""
    # 输出剪影栅格化到 96x96：源图尺寸未知位置按比例映射
    # （harness 在缩放源到 96 后跑键控，因此这里直接按索引同尺寸相交）
    a = silhouette(keyed)
    rb = silhouette(ref_pixels)
    inter = len(a & rb)
    union = len(a | rb)
    return inter / union if union else 0.0


def resize_nearest(pixels, w, h, nw, nh):
    out = [0] * (nw * nh)
    for y in range(nh):
        sy = min(h - 1, y * h // nh)
        for x in range(nw):
            sx = min(w - 1, x * w // nw)
            out[y * nw + x] = pixels[sy * w + sx]
    return out


# ━━━━━━━━━━ 输入类判别（M3 路由的观测器） ━━━━━━━━━━

def classify_input(pixels, w, h) -> str:
    """粗分类：flat(实色底) / gradient(渐变底) / transparent_margin(透明边距) / mask_like(蒙版图)
    渐变判据用边缘环平滑度（端差大 & 相邻差小），棋盘类高频花色不会误判。"""
    corners = [pixels[0], pixels[w - 1], pixels[(h - 1) * w], pixels[h * w - 1]]
    opaque_corners = [c for c in corners if ((c >> 24) & 0xFF) > OPAQUE_ALPHA]
    if len(opaque_corners) <= 1:
        return "transparent_margin"

    def rgb(c):
        return ((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF)

    # 边缘环采样：每边 24 点、逐边连续排列（交错排列会让边间跳变污染平滑度）
    ring = []
    edges = [((x, 0) for x in range(0, w, max(1, w // 24))),
             ((x, h - 1) for x in range(0, w, max(1, w // 24))),
             ((0, y) for y in range(0, h, max(1, h // 24))),
             ((w - 1, y) for y in range(0, h, max(1, h // 24)))]
    for edge in edges:
        for x, y in edge:
            p = pixels[y * w + x]
            if ((p >> 24) & 0xFF) > OPAQUE_ALPHA:
                ring.append(rgb(p))
    if len(ring) >= 8:
        spread = max(abs(a[0] - b[0]) + abs(a[1] - b[1]) + abs(a[2] - b[2]) for a in ring for b in ring)
        # 平滑度按每条边内部相邻差算（边间接缝的跳变不代表梯度不平滑）
        per_edge = 24
        smooth = max(
            (max(abs(a[0] - b[0]) + abs(a[1] - b[1]) + abs(a[2] - b[2])
                 for a, b in zip(ring[i:i + per_edge - 1], ring[i + 1:i + per_edge]))
             if len(ring[i:i + per_edge]) >= 2 else 0)
            for i in range(0, len(ring), per_edge)
        )
        if spread > 60 and smooth < 30:
            return "gradient"

    op = sum(1 for p in pixels if ((p >> 24) & 0xFF) > OPAQUE_ALPHA)
    colors = {p & 0x00FFFFFF for p in pixels if ((p >> 24) & 0xFF) > OPAQUE_ALPHA}
    if op / (w * h) > 0.95 and len(colors) <= 2:
        return "mask_like"
    return "flat"


# ━━━━━━━━━━ 语料源 ━━━━━━━━━━

def load_pure_sources(path: Path):
    """Pure 图标包 zip/apk → {package: (pixels, w, h)}。component 形如 ComponentInfo{pkg/activity}"""
    zf = zipfile.ZipFile(path)
    apk_name = next(n for n in zf.namelist() if n.endswith("base.apk"))
    apk = zipfile.ZipFile(io.BytesIO(zf.read(apk_name)))
    af = apk.read("assets/appfilter.xml").decode("utf-8", "ignore")
    items = re.findall(r'<item component="ComponentInfo\{([^/]+)/[^"]+\}" drawable="([^"]+)"', af)
    drawables = {}
    for n in apk.namelist():
        if "drawable-nodpi" in n and n.endswith(".png"):
            drawables[Path(n).name[:-4]] = n
    out = {}
    for pkg, dr in items:
        if dr in drawables and pkg not in out:
            try:
                px, w, h = decode_png(apk.read(drawables[dr]))
                out[pkg] = (px, w, h)
            except AssertionError:
                continue
    return out


def load_anip_refs(anip_dir: Path):
    """ANIP/fankes_cache 目录 → {package: (pixels, w, h)}。文件名 = 包名.png（大小写不敏感试探）"""
    refs = {}
    for f in anip_dir.glob("**/*.png"):
        refs[f.stem] = (f, None)  # 懒解码
    return refs


# ━━━━━━━━━━ 主流程 ━━━━━━━━━━

def run_grid(sources, refs, thresholds, resize_to=96):
    """按输入类分组跑网格，返回 per-class 最佳阈值与统计"""
    rows = []
    for pkg, (px, w, h) in sources.items():
        ref = refs.get(pkg) or refs.get(pkg.lower())
        if ref is None:
            continue
        if isinstance(ref, tuple) and ref[1] is None:
            f = ref[0]
            try:
                rpx, rw, rh = decode_png(f.read_bytes())
            except AssertionError:
                continue
            refs[pkg] = (rpx, rw, rh)
            ref = (rpx, rw, rh)
        rpx, rw, rh = ref
        cls = classify_input(px, w, h)
        s96 = resize_nearest(px, w, h, resize_to, resize_to)
        r96 = resize_nearest(rpx, rw, rh, resize_to, resize_to)
        for th in thresholds:
            keyed, cov, mode, rej = redraw_core(s96, resize_to, resize_to, th)
            if rej:
                rows.append(dict(pkg=pkg, cls=cls, th=th, rejected=rej, cov=round(cov, 3),
                                 white=None, iou=None, mode=mode))
                continue
            rows.append(dict(pkg=pkg, cls=cls, th=th, rejected=None, cov=round(cov, 3),
                             white=round(white_ratio(keyed), 3),
                             iou=round(iou_vs_reference(keyed, r96, resize_to, resize_to), 3),
                             mode=mode))
    return rows


def summarize(rows, thresholds):
    by_class = defaultdict(lambda: defaultdict(list))
    for r in rows:
        if not r["rejected"]:
            by_class[r["cls"]][r["th"]].append(r)
    lines = ["| 输入类 | 样本对 | 最佳 th | IoU 均值 | 拒绝率 |", "|---|---|---|---|---|"]
    detail = {}
    for cls in sorted(by_class):
        best_th, best_iou = None, -1.0
        total = 0
        for th in thresholds:
            rs = by_class[cls][th]
            total = max(total, len(rs))
            ious = [r["iou"] for r in rs if r["iou"] is not None]
            if ious:
                m = sum(ious) / len(ious)
                if m > best_iou:
                    best_iou, best_th = m, th
        rej = sum(1 for r in rows if r["cls"] == cls and r["rejected"])
        rej_total = sum(1 for r in rows if r["cls"] == cls and r["th"] == thresholds[0])
        lines.append(f"| {cls} | {rej_total} | {best_th} | {best_iou:.3f} | {rej / max(1, rej_total):.1%} |")
        detail[cls] = (best_th, best_iou)
    return "\n".join(lines), detail


def selftest():
    """合成夹具自测：四输入类 × 已知期望（无外部依赖，CI 可跑）"""
    import random
    random.seed(42)
    ok = True

    def check(name, cond):
        nonlocal ok
        print(("  PASS " if cond else "  FAIL ") + name)
        ok = ok and cond

    S = 96
    # 1) flat：实色底 + 深色 glyph → 键控成功，IoU 对完美参照=1
    bg, glyph = (255 << 24) | (245 << 16) | (245 << 8) | 245, 0xFF000000 | (40 << 16) | (40 << 8) | 40
    px = [bg] * (S * S)
    for y in range(30, 66):
        for x in range(30, 66):
            px[y * S + x] = glyph
    keyed, cov, mode, rej = redraw_core(px, S, S, 30)
    check("flat keying accepted", rej is None and mode == "CORNER_SAMPLE")
    check("flat coverage ≈ glyph area", rej is None and abs(cov - (36 * 36) / (S * S)) < 0.01)
    check("flat white ratio = 1", rej is None and abs(white_ratio(keyed) - 1.0) < 0.001)

    # 2) transparent_margin：透明角 + 彩色合成盘(dominant) + 盘内深色 glyph → DOMINANT 回退键控成功
    px2 = [0] * (S * S)
    disc, g2 = (255 << 24) | (70 << 16) | (130 << 8) | 220, 0xFF000000 | (40 << 16) | (40 << 8) | 40
    cx, cy, r = S / 2, S / 2, S * 0.42
    for y in range(S):
        for x in range(S):
            if (x - cx) ** 2 + (y - cy) ** 2 <= r * r:
                px2[y * S + x] = g2 if (28 <= x < 68 and 28 <= y < 68) else disc
    keyed2, cov2, mode2, rej2 = redraw_core(px2, S, S, 30)
    import math
    disc_frac = math.pi * (S * 0.42) ** 2 / (S * S)
    check("transparent margin → alpha silhouette", rej2 is None and mode2 == "ALPHA_SILHOUETTE")
    check("transparent margin coverage ≈ disc area", rej2 is None and abs(cov2 - disc_frac) < 0.02)

    # 3) gradient：环端差大 & 相邻差小 → 分类 gradient，双线性模型精确建模线性渐变 → 键控成功
    px3 = [0] * (S * S)
    for y in range(S):
        for x in range(S):
            v = 40 + (x * 170 // S)
            px3[y * S + x] = (255 << 24) | (v << 16) | (v << 8) | v
    for y in range(30, 66):
        for x in range(30, 66):
            px3[y * S + x] = glyph
    cls3 = classify_input(px3, S, S)
    check("gradient classified", cls3 == "gradient")
    keyed3, cov3, mode3, rej3 = redraw_core(px3, S, S, 30)
    check("gradient bilinear keys background", rej3 is None and mode3 == "BILINEAR_GRADIENT")
    check("gradient glyph coverage ≈ area", rej3 is None and abs(cov3 - (36 * 36) / (S * S)) < 0.02)

    # 4) mask_like：全图两色、不透明占 100% → 键控清背景类（同 flat 的键控行为），分类正确
    px4 = [bg] * (S * S)
    for y in range(0, S):
        for x in range(0, S):
            px4[y * S + x] = glyph if (x // 8 + y // 8) % 2 else bg
    # 蒙版图定义=不透明>95% 且 ≤2 色：条纹图满足
    check("mask classified", classify_input(px4, S, S) == "mask_like")

    # 5) 守卫：全 glyph 图（背景估计=glyph）→ 全键空 → over-keyed 拒绝
    px5 = [glyph] * (S * S)
    _, _, _, rej5 = redraw_core(px5, S, S, 30)
    check("over-keyed rejected", rej5 == "over-keyed")

    # 6) 守卫：噪点花图（主色只占 5%）→ 退化拒绝
    px6 = [0xFF000000 | ((random.randrange(256)) << 16) | (random.randrange(256) << 8) | random.randrange(256)
           for _ in range(S * S)]
    _, _, _, rej6 = redraw_core(px6, S, S, 30)
    check("noise degen or overkey rejected", rej6 is not None)

    print("SELFTEST " + ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("selftest")
    run = sub.add_parser("run")
    run.add_argument("--pure", help="Pure 图标包 zip/apk 路径（彩色源）")
    run.add_argument("--anip", help="ANIP/fankes_cache 目录（官方单色参照）")
    run.add_argument("--grid", action="store_true", help="跑阈值网格（默认单点 30）")
    run.add_argument("--out", default="redraw_harness_report.md")
    args = ap.parse_args()

    if args.cmd == "selftest":
        sys.exit(selftest())

    thresholds = [10, 20, 30, 40, 60, 80, 100] if args.grid else [30]
    sources = load_pure_sources(Path(args.pure))
    print(f"sources loaded: {len(sources)}")
    refs = load_anip_refs(Path(args.anip))
    print(f"references loaded: {len(refs)}")
    pairs = set(sources) & {k for k in refs}
    print(f"paired by package: {len(pairs)}")
    rows = run_grid(sources, refs, thresholds)
    table, detail = summarize(rows, thresholds)
    rej_by_mode = Counter(r["rejected"] for r in rows if r["rejected"])
    report = (
        "# 重绘实验台报告\n\n"
        f"- 语料: {len(sources)} 源 / {len(refs)} 参照 / {len(pairs)} 配对\n"
        f"- 阈值网格: {thresholds}\n\n## 分输入类最佳参数\n\n{table}\n\n"
        f"## 拒绝原因分布\n\n"
        + "\n".join(f"- {k}: {v}" for k, v in rej_by_mode.most_common())
        + "\n\n## 参数表建议（M3 路由默认值）\n\n"
        + json.dumps({k: {"threshold": v[0], "mean_iou": round(v[1], 3)} for k, v in detail.items()},
                     ensure_ascii=False, indent=2) + "\n"
    )
    Path(args.out).write_text(report, encoding="utf-8")
    print(report)


if __name__ == "__main__":
    main()
