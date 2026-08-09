#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""cocos2d 图集的拆包与回填 —— **一份代码同时负责两个方向**。

## 为什么拆和装必须写在一起

图集回填最容易坏在「拆的时候按 A 理解、装的时候按 B 理解」：帧被旋转过、被裁掉
透明边、坐标系上下颠倒……任何一处两边理解不一致，装回去的图就会错位或糊掉，而且
**打包能过、构建能过，只有真机上肉眼能看出来**。

所以这里不拆成两个脚本。同一份 `parse_frame()` 既用于导出也用于回填，格式理解
只有一处，不可能对不上。

## 三种 plist 格式（本仓库三种都有）

    format 0  (55 个图集)  x / y / width / height / originalWidth / originalHeight
                            / offsetX / offsetY，**没有旋转**
    format 2  (11 个图集)  frame '{{x,y},{w,h}}' / offset / rotated / sourceSize
                            / sourceColorRect
    format 3  ( 3 个图集)  textureRect / spriteOffset / spriteSize
                            / spriteSourceSize / textureRotated

旋转帧在图集里是**顺时针转了 90°** 存放的（cocos2d/TexturePacker 的约定：
plist 里记的 w/h 是**旋转后**的占位尺寸）。导出时转正，回填时转回去。

## 「完美还原」是怎么保证的

  1. 回填**不重新排版**：每张图原地贴回它原来的矩形，坐标、尺寸、旋转都取自
     同一份 plist。plist 自身一个字节都不改。
  2. 尺寸不符**直接拒绝**，不缩放、不裁剪。美工把 326×64 改成 327×64 的话，
     这里会报错并指名道姓，而不是悄悄缩一下——那种"能跑但糊了"最难查。
  3. 未改动的像素**逐像素校验**：回填后重新导出一遍，与导出时记录的
     pixel_sha256 比对，只有被替换的帧允许不同。
  4. 图集 PNG 会被重新编码（文件字节必然变），所以校验一律看**像素**不看文件。

用法：
    python3 tools/atlas-io.py export  <输出目录>
    python3 tools/atlas-io.py restore <输出目录> [--dry-run]
    python3 tools/atlas-io.py verify  <输出目录>
"""

import hashlib
import json
import os
import plistlib
import re
import shutil
import sys

try:
    from PIL import Image
except ImportError:
    print("需要 Pillow：pip3 install Pillow")
    sys.exit(2)

ASSET_ROOTS = ["assets"]
MANIFEST = "manifest.json"
FRAME_DIR = "frames"       # 图集拆出来的帧
LOOSE_DIR = "loose"        # 独立 PNG
PAIR = re.compile(r"\{\s*([-\d.]+)\s*,\s*([-\d.]+)\s*\}")


def nums(s):
    """把 '{{x,y},{w,h}}' / '{a,b}' 这类字符串抽成数字列表。"""
    return [float(v) for m in PAIR.finditer(str(s)) for v in m.groups()]


def parse_frame(name, fr, fmt):
    """归一化成 (x, y, 占位宽, 占位高, rotated)。

    「占位」= 帧在图集里**实际压住的矩形**，crop 与 paste 都只认它。

    ⚠ 旋转帧必须交换宽高。plist 里 `frame` / `textureRect` 记的是**转正后**的
    尺寸，而图集里躺着的是转过 90° 的样子——占位是 h×w，不是 w×h。

    这一步漏了会怎样：qb_help_06 记作 {{1,1},{600,376}} 且 rotated。按 600 宽算，
    它会压到 x=1..601；而同一张图集里 qb_help_08 在 x=379..979。两者重叠——
    物理上不可能，图集打包器不会这么排。按交换后的 376 宽算（x=1..377）才对得上。
    **本工具第一版就是漏了交换，回填时糊掉了隔壁的 qb_help_08，被 verify 抓出来。**
    这段注释留着，别再改回去。
    """
    if fmt == 0:
        # format 0 没有旋转字段，全是正放
        return (int(fr["x"]), int(fr["y"]),
                int(fr["width"]), int(fr["height"]), False)
    if fmt in (1, 2):
        x, y, w, h = nums(fr["frame"])
        rot = bool(fr.get("rotated", False))
    elif fmt == 3:
        x, y, w, h = nums(fr["textureRect"])
        rot = bool(fr.get("textureRotated", False))
    else:
        raise ValueError("%s: 不认识的 plist format=%r" % (name, fmt))
    if rot:
        w, h = h, w                      # ← 占位是宽高互换的
    return (int(x), int(y), int(w), int(h), rot)


def atlases():
    """产出 (plist 路径, png 路径, format, frames dict)。跳过粒子等非图集 plist。"""
    for root in ASSET_ROOTS:
        for dirpath, _, names in os.walk(root):
            for n in sorted(names):
                if not n.endswith(".plist"):
                    continue
                p = os.path.join(dirpath, n)
                try:
                    with open(p, "rb") as fh:
                        d = plistlib.load(fh)
                except Exception:
                    continue
                if not (isinstance(d, dict) and "frames" in d):
                    continue          # 粒子系统等，没有 frames
                md = d.get("metadata", {}) or {}
                tex = md.get("textureFileName") or (n[:-6] + ".png")
                png = os.path.join(dirpath, os.path.basename(tex))
                if not os.path.isfile(png):
                    print("  ⚠ 跳过 %s：找不到贴图 %s" % (p, png))
                    continue
                yield p, png, int(md.get("format", 0)), d["frames"]


def loose_images():
    """图集贴图之外的独立 PNG。"""
    used = set()
    for _, png, _, _ in atlases():
        used.add(os.path.normpath(png))
    for root in ASSET_ROOTS:
        for dirpath, _, names in os.walk(root):
            for n in sorted(names):
                if not n.lower().endswith(".png"):
                    continue
                p = os.path.normpath(os.path.join(dirpath, n))
                if p not in used:
                    yield p


def pixel_sha(img):
    """像素指纹。图集回填必然重新编码，文件哈希会变、像素不该变。"""
    im = img.convert("RGBA")
    h = hashlib.sha256()
    h.update(b"%d,%d|" % im.size)
    h.update(im.tobytes())
    return h.hexdigest()


def cut(atlas, box, rotated):
    """从图集里取出一帧并转正。"""
    x, y, w, h = box
    im = atlas.crop((x, y, x + w, y + h))
    if rotated:
        # 图集里是顺时针 90°，转回来给人看
        im = im.transpose(Image.Transpose.ROTATE_90)
    return im


def uncut(im, rotated):
    """把人编辑过的图转回图集里的摆放方向。"""
    if rotated:
        im = im.transpose(Image.Transpose.ROTATE_270)
    return im


# ── export ────────────────────────────────────────────────────────────
def do_export(out):
    os.makedirs(out, exist_ok=True)
    man = {"schema": 1, "frames": [], "loose": [], "atlas_pixel_sha": {}}
    nf = 0
    for plist, png, fmt, frames in atlases():
        atlas = Image.open(png).convert("RGBA")
        man["atlas_pixel_sha"][png] = pixel_sha(atlas)
        stem = os.path.splitext(png)[0]
        for name in sorted(frames):
            box = parse_frame(name, frames[name], fmt)
            im = cut(atlas, box[:4], box[4])
            rel = os.path.join(FRAME_DIR, stem, name if name.lower().endswith(".png")
                               else name + ".png")
            dst = os.path.join(out, rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            im.save(dst)
            man["frames"].append({
                "file": rel.replace("\\", "/"),
                "plist": plist, "atlas": png, "format": fmt, "frame": name,
                "x": box[0], "y": box[1], "w": box[2], "h": box[3],
                "rotated": box[4],
                "upright_size": list(im.size),
                "pixel_sha": pixel_sha(im),
            })
            nf += 1
    nl = 0
    for p in loose_images():
        im = Image.open(p).convert("RGBA")
        rel = os.path.join(LOOSE_DIR, p)
        dst = os.path.join(out, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(p, dst)
        man["loose"].append({
            "file": rel.replace("\\", "/"), "src": p,
            "size": list(im.size), "pixel_sha": pixel_sha(im),
        })
        nl += 1
    with open(os.path.join(out, MANIFEST), "w", encoding="utf-8") as fh:
        json.dump(man, fh, ensure_ascii=False, indent=1)
    print("✔ 导出完成：图集帧 %d，独立图 %d，图集 %d 张"
          % (nf, nl, len(man["atlas_pixel_sha"])))
    print("  清单：%s" % os.path.join(out, MANIFEST))


# ── restore ───────────────────────────────────────────────────────────
def do_restore(out, dry):
    man = json.load(open(os.path.join(out, MANIFEST), encoding="utf-8"))
    edits, errs, byatlas = 0, [], {}

    for f in man["frames"]:
        path = os.path.join(out, f["file"])
        if not os.path.isfile(path):
            errs.append("缺文件：%s" % f["file"]); continue
        im = Image.open(path).convert("RGBA")
        if pixel_sha(im) == f["pixel_sha"]:
            continue                                  # 没改，不动
        want = tuple(f["upright_size"])
        if im.size != want:
            errs.append("尺寸不符：%s 期望 %dx%d，实得 %dx%d"
                        "（图集是定位贴回，不允许改尺寸）"
                        % (f["file"], want[0], want[1], im.size[0], im.size[1]))
            continue
        byatlas.setdefault(f["atlas"], []).append((f, im))
        edits += 1

    loose_edits = []
    for l in man["loose"]:
        path = os.path.join(out, l["file"])
        if not os.path.isfile(path):
            errs.append("缺文件：%s" % l["file"]); continue
        im = Image.open(path).convert("RGBA")
        if pixel_sha(im) == l["pixel_sha"]:
            continue
        if list(im.size) != l["size"]:
            errs.append("尺寸不符：%s 期望 %dx%d，实得 %dx%d"
                        % (l["file"], l["size"][0], l["size"][1], *im.size))
            continue
        loose_edits.append((l, path))

    if errs:
        print("✘ 回填中止（%d 个问题）：" % len(errs))
        for e in errs:
            print("   · %s" % e)
        print("\n  一个都没写。修好上面这些再跑——尺寸不符一律不缩放不裁剪，")
        print("  因为那种'能跑但糊了'比直接报错难查得多。")
        return 1

    print("将回填：图集帧 %d（分布在 %d 张图集）、独立图 %d"
          % (edits, len(byatlas), len(loose_edits)))
    if dry:
        for a, items in sorted(byatlas.items()):
            print("   %s ← %d 帧" % (a, len(items)))
        for l, _ in loose_edits:
            print("   %s" % l["src"])
        print("\n（--dry-run，未写入任何文件）")
        return 0

    for a, items in sorted(byatlas.items()):
        atlas = Image.open(a).convert("RGBA")
        for f, im in items:
            atlas.paste(uncut(im, f["rotated"]), (f["x"], f["y"]))
        atlas.save(a)
        print("   ✔ %s ← %d 帧" % (a, len(items)))
    for l, path in loose_edits:
        shutil.copy2(path, l["src"])
        print("   ✔ %s" % l["src"])
    print("\n回填完成。**务必**再跑一次 verify 核对未改动的像素没被动过：")
    print("   python3 tools/atlas-io.py verify %s" % out)
    return 0


# ── verify ────────────────────────────────────────────────────────────
def do_verify(out):
    """重新拆一遍当前仓库，与清单比对：只有被替换的帧允许不同。"""
    man = json.load(open(os.path.join(out, MANIFEST), encoding="utf-8"))
    want = {f["file"]: f for f in man["frames"]}
    edited, intact, drift = 0, 0, []
    for plist, png, fmt, frames in atlases():
        atlas = Image.open(png).convert("RGBA")
        stem = os.path.splitext(png)[0]
        for name in sorted(frames):
            rel = os.path.join(FRAME_DIR, stem,
                               name if name.lower().endswith(".png") else name + ".png")
            rel = rel.replace("\\", "/")
            f = want.get(rel)
            if not f:
                continue
            now = pixel_sha(cut(atlas, parse_frame(name, frames[name], fmt)[:4],
                                parse_frame(name, frames[name], fmt)[4]))
            edp = os.path.join(out, rel)
            newsha = pixel_sha(Image.open(edp).convert("RGBA")) if os.path.isfile(edp) else None
            if now == f["pixel_sha"]:
                intact += 1
            elif newsha is not None and now == newsha:
                edited += 1            # 与美工交回来的那张一致 —— 正是预期
            else:
                drift.append(rel)
    print("原样未动 %d 帧，按预期替换 %d 帧" % (intact, edited))
    if drift:
        print("\n✘ %d 帧既不等于原样、也不等于交回来的图——说明回填串位了：" % len(drift))
        for d in drift[:20]:
            print("   · %s" % d)
        return 1
    print("✔ 未改动的像素一字未动，替换的帧逐一对上")
    return 0


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    cmd, out = sys.argv[1], sys.argv[2]
    if cmd == "export":
        return do_export(out)
    if cmd == "restore":
        return do_restore(out, "--dry-run" in sys.argv)
    if cmd == "verify":
        return do_verify(out)
    print("未知命令 %r" % cmd)
    return 2


if __name__ == "__main__":
    sys.exit(main())
