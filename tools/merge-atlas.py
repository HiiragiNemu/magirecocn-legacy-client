#!/usr/bin/env python3
"""把汉化版图集与英文底包图集合并，保留汉化画面且不丢帧。

## 为什么需要它

本仓库的底包是**美服英文版**，（旧客户端仓库） 那边的同名图集是**国服汉化版**。
直接拿汉化版覆盖，绝大多数图集没问题（帧集合完全一致，只是画面从英文换成中文），
但有三个图集国服比美服少几帧：

    story/story_ui_sprites00   少 story_ui_fukidashi_05 / story_ui_select / story_ui_selected
    memoria/web_ef_memoria0    少 memoria_limit_break_04_b … 08_b
    quest/ef_battle00/ef_battle000  少 ch_charo_03_00 / cm_ui_tittle_bg / sp_cm_dummy

这些帧在美服底包里存在，引擎可能引用；直接覆盖会让它们查不到而渲染不出来。
本脚本以汉化版为底、把仅英文版才有的帧追加进去，得到「画面是中文、帧一个不少」
的合并图集。

## 做法

汉化版的图集原样保留（坐标不变，故其 plist 坐标可直接沿用），把缺的帧用简单的
货架式排布追加到画布下方，再输出 format 2 的 plist——与本仓库其余图集一致。

前置条件：两边都没有 rotated 帧（已核对）。若将来出现旋转帧，这里会直接报错，
而不是悄悄产出错位的图集。
"""

import argparse
import os
import plistlib
import re
import sys

from PIL import Image


def parse_plist(path):
    """解析图集 plist，返回 {帧名: (x, y, w, h, rotated)}，兼容三种键名格式。"""
    with open(path, "rb") as f:
        d = plistlib.load(f)
    out = {}
    for name, v in d["frames"].items():
        if "frame" in v:                      # format 2 / 3
            m = list(map(int, re.findall(r"-?\d+", v["frame"])))
            x, y, w, h = m[:4]
            rot = bool(v.get("rotated", False))
        elif "textureRect" in v:              # format 1
            m = list(map(int, re.findall(r"-?\d+", v["textureRect"])))
            x, y, w, h = m[:4]
            rot = bool(v.get("textureRotated", False))
        else:                                 # format 0
            x, y = int(v["x"]), int(v["y"])
            w, h = int(v["width"]), int(v["height"])
            rot = bool(v.get("rotated", False))
        out[name] = (x, y, w, h, rot)
    return out


def emit_plist(frames, tex_name, tex_size):
    """输出 format 2 的 plist。frames: {name: (x, y, w, h)}。"""
    lines = ['<?xml version="1.0" encoding="UTF-8"?>',
             '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" '
             '"http://www.apple.com/DTDs/PropertyList-1.0.dtd">',
             '<plist version="1.0"><dict><key>frames</key><dict>']
    for name in sorted(frames):
        x, y, w, h = frames[name]
        lines.append(
            f"<key>{name}</key><dict>"
            f"<key>frame</key><string>{{{{{x},{y}}},{{{w},{h}}}}}</string>"
            f"<key>offset</key><string>{{0,0}}</string>"
            f"<key>rotated</key><false/>"
            f"<key>sourceColorRect</key><string>{{{{0,0}},{{{w},{h}}}}}</string>"
            f"<key>sourceSize</key><string>{{{w},{h}}}</string>"
            f"</dict>")
    lines.append("</dict><key>metadata</key><dict>"
                 "<key>format</key><integer>2</integer>"
                 f"<key>realTextureFileName</key><string>{tex_name}</string>"
                 f"<key>size</key><string>{{{tex_size[0]},{tex_size[1]}}}</string>"
                 f"<key>textureFileName</key><string>{tex_name}</string>"
                 "</dict></dict></plist>")
    return "".join(lines)


def shelf_pack(sizes, width, start_y, gap=2):
    """把 [(name, w, h)] 按货架式排进 width 宽的区域，返回 {name: (x, y)} 与总高。"""
    pos = {}
    x, y, row_h = gap, start_y + gap, 0
    for name, w, h in sorted(sizes, key=lambda s: -s[2]):
        if x + w + gap > width:
            x = gap
            y += row_h + gap
            row_h = 0
        pos[name] = (x, y)
        x += w + gap
        row_h = max(row_h, h)
    return pos, y + row_h + gap


def merge(cn_base, en_base, out_base):
    cn_p, en_p = cn_base + ".plist", en_base + ".plist"
    cn_frames, en_frames = parse_plist(cn_p), parse_plist(en_p)

    for tag, fr in (("汉化版", cn_frames), ("英文版", en_frames)):
        rot = [k for k, v in fr.items() if v[4]]
        if rot:
            print(f"::error::{tag}存在 rotated 帧，本脚本不支持：{rot[:5]}")
            return 1

    missing = sorted(set(en_frames) - set(cn_frames))
    cn_img = Image.open(cn_base + ".png").convert("RGBA")

    if not missing:
        print(f"  {os.path.basename(cn_base)}: 帧集合一致，直接采用汉化版")
        merged = {k: v[:4] for k, v in cn_frames.items()}
        out_img = cn_img
    else:
        en_img = Image.open(en_base + ".png").convert("RGBA")
        sizes = [(m, en_frames[m][2], en_frames[m][3]) for m in missing]
        pos, new_h = shelf_pack(sizes, cn_img.width, cn_img.height)
        out_img = Image.new("RGBA", (cn_img.width, new_h), (0, 0, 0, 0))
        out_img.paste(cn_img, (0, 0))
        merged = {k: v[:4] for k, v in cn_frames.items()}
        for m in missing:
            ex, ey, ew, eh, _ = en_frames[m]
            out_img.paste(en_img.crop((ex, ey, ex + ew, ey + eh)), pos[m])
            merged[m] = (pos[m][0], pos[m][1], ew, eh)
        print(f"  {os.path.basename(cn_base)}: 采用汉化版 + 追加 {len(missing)} 个"
              f"仅英文版才有的帧，画布 {cn_img.size} → {out_img.size}")

    tex = os.path.basename(out_base) + ".png"
    out_img.save(out_base + ".png")
    with open(out_base + ".plist", "w", encoding="utf-8") as f:
        f.write(emit_plist(merged, tex, out_img.size))

    # 自校验：合并后必须覆盖英文版的全部帧，且每一帧的像素与其来源一致
    back = parse_plist(out_base + ".plist")
    lost = sorted(set(en_frames) - set(back))
    if lost:
        print(f"::error::合并后仍缺帧：{lost}")
        return 1
    check = Image.open(out_base + ".png").convert("RGBA")
    bad = []
    for name, (x, y, w, h, _) in back.items():
        got = check.crop((x, y, x + w, y + h))
        if name in cn_frames:
            sx, sy, sw, sh, _ = cn_frames[name]
            src = cn_img.crop((sx, sy, sx + sw, sy + sh))
        else:
            sx, sy, sw, sh, _ = en_frames[name]
            src = Image.open(en_base + ".png").convert("RGBA").crop((sx, sy, sx + sw, sy + sh))
        if got.tobytes() != src.tobytes():
            bad.append(name)
    if bad:
        print(f"::error::合并后这些帧的像素与来源不一致：{bad[:8]}")
        return 1
    print(f"      ✔ 自校验通过：{len(back)} 帧齐全，像素逐帧一致")
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cn", required=True, help="汉化版图集路径前缀（不含扩展名）")
    ap.add_argument("--en", required=True, help="英文底包图集路径前缀（不含扩展名）")
    ap.add_argument("--out", required=True, help="输出路径前缀（不含扩展名）")
    a = ap.parse_args()
    return merge(a.cn, a.en, a.out)


if __name__ == "__main__":
    sys.exit(main())
