#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成中文版「加载中」动画雪碧图，替换 /magica/resource/image_web/common/global/connecting.png。

## 为什么有这个脚本

原版 connecting.png 是一张 334×54 的静态图：左边一行英文 "Connecting..."，
右边一只静止的白色丘比剪影，底下压着一条从左往右渐显的深紫色半透明条。
它是 base.css 里 `#loading p` 的背景图，切页面时出现——所以它**不是文本**，
汉化流水线两次都漏掉了它（提取器只找日文假名/汉字，而这是一张图）。

我们没有美工。所以这里不画新图，而是**把 APK 里已有的国服素材重新拼一遍**：

  · 文字取自 assets/package/loading/loading_icon.png —— 国服自带的
    「数据加载中 . . .」8 帧波浪高亮动画。
  · 丘比取自 assets/package/loading/loading_char.png —— 国服自带的
    8 帧丘比奔跑循环（白色剪影，和原版右边那只同风格）。
  · 底条直接从原版 connecting.png 上抠：第 53 行是纯底条（没有文字/丘比
    压在上面），按列复制它的 RGBA 铺满 32..53 行，就得到逐像素一致的底条。

## 输出：默认 APNG，而不是雪碧图 + CSS

一开始的方案是「竖向雪碧图 + CSS `steps(8)` 逐帧滚动」。改成 APNG 是因为
它把这件事的**耦合整个去掉了**：

  · 雪碧图必须配套改 `magica/css/_common/base.css` 的 `#loading p`。那意味着
    要下发一整份 base.css——而我们手上没有原文件，只有那一条规则的抄录，
    重打一份全量 CSS 的风险远大于收益。
  · APNG 是**原地替换**：文件名、尺寸（334×54）、MIME（image/png）全不变，
    CSS 一个字都不用动。
  · 降级是干净的：不认 APNG 的旧 WebView 会把它当普通 PNG，显示第 1 帧——
    静止的中文「数据加载中」+ 丘比，仍然比原来的英文强。Chromium 从 M59
    起支持 APNG，绝大多数机器上是动的。

雪碧图那条路仍然保留：`--sheet` 会输出 334×432 的竖图并打印配套 CSS。
万一真机上 APNG 不动，换过去即可。

## 为什么文字是静的、只有丘比在动

loading_icon.png 那 8 帧是「高亮从左扫到右」的波浪效果。原图 271px 宽时看着
是流光；缩到 334×54 这条里只有 ~150px 宽，逐帧亮度跳变会读成**闪烁**而不是
流光。所以这里把 8 帧按 alpha 取最大值合并成一张**完整不透明**的文字，
动画只留给丘比——那本来就是个跑步循环，缩小后依然读得出来。

## 用法

    python3 tools/make-connecting-sprite.py <原版connecting.png> <输出.png>
    python3 tools/make-connecting-sprite.py <原版> <输出> --sheet   # 雪碧图模式

原版 connecting.png 不在本仓库里（它在资源包 /magica/resource/ 下面，
不是 apktool 树的一部分），所以必须显式传进来。

依赖：Pillow。
"""

import sys
from collections import deque

try:
    from PIL import Image
except ImportError:  # pragma: no cover
    sys.stderr.write("需要 Pillow：pip install Pillow\n")
    sys.exit(2)

import os

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ICON = os.path.join(REPO, "assets", "package", "loading", "loading_icon.png")
CHAR = os.path.join(REPO, "assets", "package", "loading", "loading_char.png")

FRAMES = 8
FRAME_W, FRAME_H = 334, 54

# —— loading_icon.png 的帧网格 ——
# 542×283，2 列 4 行，帧间距 271×71。最后一行只有 70 行像素（283 = 71*4 - 1），
# 所以裁剪时要 min() 一下，否则 PIL 会补透明，不影响结果但会让 bbox 偏大。
ICON_COLS, ICON_PITCH_W, ICON_PITCH_H = 2, 271, 71

# —— loading_char.png 的帧网格 ——
# 256×256，2 列 4 行，帧 128×64，正好整除。
CHAR_COLS, CHAR_W, CHAR_H = 2, 128, 64

# 中性色判据：气泡是彩色的（粉/蓝），文字是黑白描边。
# max(r,g,b) - min(r,g,b) < NEUTRAL_TOL 的像素判为文字。
NEUTRAL_TOL = 34

# 连通域过滤：气泡残渣（小圆点）集中在 y 13..30，文字块在 y 21..56。
# 用「面积 + 垂直重心」两个条件筛，比单纯按面积干净得多。
MIN_COMPONENT_PX = 25
MIN_CENTROID_Y = 34

BAR_TOP = 32          # 底条起始行（原版 32..53）
BAR_SAMPLE_ROW = 53   # 这一行是纯底条，逐列采样它来重建

# —— 版面（在 334×54 的帧内）——
# 原版：文字 x43..212 y19..49（170×31），丘比 x223..304 y4..48（82×45）。
# 中文文字比英文短一点、丘比源素材更宽，所以两边各自重排：
TEXT_BOX = (38, 18, 156, 30)    # x, y, w, h —— 等比缩放后按这个高度对齐
CHAR_BOX = (203, 5, 102, 44)    # 同上；右边缘 305 与原版一致

FRAME_MS = 100                  # APNG 每帧毫秒；8 帧 = 0.8s 一轮跑动

SHEET_CSS = u"""
—— 雪碧图模式还要改 CSS ——

在 magica/css/_common/base.css 里，把 #loading p 的规则改成：

    #loading p {
        background: url(/magica/resource/image_web/common/global/connecting.png)
                    0 0 no-repeat;
        width: 334px; height: 54px;
        position: absolute; bottom: 20px; right: 0;
        animation: connectingAnim .8s steps(8) infinite;
    }
    @keyframes connectingAnim { to { background-position: 0 -432px; } }

⚠ 图和 CSS 必须**同时**到达客户端，否则中间态是「只显示第 1 帧」（旧 CSS +
新图）或者「整条空白」（新 CSS + 旧图）。两个文件都放进同一个
cn_js_update.zip 就是原子的——热更是一次性解压覆盖，见 tools/i18n-package.py。
"""


def load_frames(path, cols, pitch_w, pitch_h, count):
    """按网格切帧。超出图像的部分交给 crop 补透明。"""
    im = Image.open(path).convert("RGBA")
    out = []
    for i in range(count):
        c, r = i % cols, i // cols
        box = (c * pitch_w, r * pitch_h,
               min(c * pitch_w + pitch_w, im.width),
               min(r * pitch_h + pitch_h, im.height))
        f = Image.new("RGBA", (pitch_w, pitch_h), (0, 0, 0, 0))
        f.paste(im.crop(box), (0, 0))
        out.append(f)
    return out


def neutral_mask(frame):
    """只留下中性色（黑/白/灰）像素——即文字，滤掉彩色气泡。"""
    px = frame.load()
    out = Image.new("RGBA", frame.size, (0, 0, 0, 0))
    op = out.load()
    for y in range(frame.height):
        for x in range(frame.width):
            r, g, b, a = px[x, y]
            if a > 8 and max(r, g, b) - min(r, g, b) < NEUTRAL_TOL:
                op[x, y] = (r, g, b, a)
    return out


def merge_max_alpha(frames):
    """逐像素取 alpha 最大的那一帧的颜色，把 8 帧波浪合成一张完整文字。"""
    w, h = frames[0].size
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    op = out.load()
    ps = [f.load() for f in frames]
    for y in range(h):
        for x in range(w):
            best = (0, 0, 0, 0)
            for p in ps:
                if p[x, y][3] > best[3]:
                    best = p[x, y]
            op[x, y] = best
    return out


def drop_bubble_residue(img):
    """
    连通域过滤：去掉气泡边缘残留的中性色碎片。

    判据是「面积够大」**且**「垂直重心够低」。只按面积筛不行——有几块残渣
    面积也不小；但残渣全都浮在上半部（气泡区），文字块都压在下半部。
    """
    px = img.load()
    w, h = img.size
    seen = [[False] * w for _ in range(h)]
    keep = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    kp = keep.load()
    kept = 0
    total = 0
    for y0 in range(h):
        for x0 in range(w):
            if seen[y0][x0] or px[x0, y0][3] <= 8:
                continue
            total += 1
            comp = []
            q = deque([(x0, y0)])
            seen[y0][x0] = True
            while q:
                x, y = q.popleft()
                comp.append((x, y))
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < w and 0 <= ny < h and not seen[ny][nx] \
                            and px[nx, ny][3] > 8:
                        seen[ny][nx] = True
                        q.append((nx, ny))
            if len(comp) < MIN_COMPONENT_PX:
                continue
            if sum(p[1] for p in comp) / len(comp) < MIN_CENTROID_Y:
                continue
            kept += 1
            for x, y in comp:
                kp[x, y] = px[x, y]
    sys.stderr.write("  连通域 %d 个，保留 %d 个\n" % (total, kept))
    return keep


def hairline_trim(img, core=190):
    """
    去掉挂在字形上的半透明毛刺。

    气泡边缘有几缕 alpha 100~130 的斜线正好搭在「中」字右下角，连通域过滤
    拿它没办法——它和字是连着的。但它和真正的字形有个稳定差别：字形是硬边
    （像素非 255 即 0，抗锯齿最多 1 px），毛刺整条都是半透明。

    所以规则是：alpha ≥ core 的像素（字形实心部分）保留；其余像素只有在
    4 邻域里挨着实心像素时才保留（这保住了 1 px 的抗锯齿边）。毛刺除了
    紧贴字形的那一行，其余整条被剪掉。
    """
    px = img.load()
    w, h = img.size
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    op = out.load()
    for y in range(h):
        for x in range(w):
            a = px[x, y][3]
            if a <= 8:
                continue
            if a >= core:
                op[x, y] = px[x, y]
                continue
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < w and 0 <= ny < h and px[nx, ny][3] >= core:
                    op[x, y] = px[x, y]
                    break
    return out


def union_bbox(frames):
    """所有帧的 bbox 并集。用它统一裁剪，跑动才不会因逐帧裁剪而抖。"""
    boxes = [f.getbbox() for f in frames if f.getbbox()]
    return (min(b[0] for b in boxes), min(b[1] for b in boxes),
            max(b[2] for b in boxes), max(b[3] for b in boxes))


def fit(img, box):
    """等比缩放到 box 的高度；若超宽再按宽度收一次。返回 (图, 左上角坐标)。"""
    bx, by, bw, bh = box
    scale = bh / img.height
    if img.width * scale > bw:
        scale = bw / img.width
    w = max(1, round(img.width * scale))
    h = max(1, round(img.height * scale))
    resized = img.resize((w, h), Image.LANCZOS)
    # 水平居中于 box，垂直底部对齐（文字/角色都以基线为准更稳）
    return resized, (bx + (bw - w) // 2, by + (bh - h))


def build_bar(original):
    """
    逐列复制原版第 53 行的 RGBA，铺满 32..53 行，重建底条。

    第 53 行是纯底条：颜色恒为 (45,27,50)，alpha 从 x=0 的 14 平滑升到
    x≈64 的 191 后保持不变。这样重建出来的底条与原版逐像素一致，
    左侧渐显、右侧半透明的观感都不会变。
    """
    px = original.load()
    bar = Image.new("RGBA", (FRAME_W, FRAME_H), (0, 0, 0, 0))
    bp = bar.load()
    for x in range(FRAME_W):
        c = px[x, BAR_SAMPLE_ROW]
        for y in range(BAR_TOP, FRAME_H):
            bp[x, y] = c
    return bar


def verify_apng(path, frames):
    """
    回读校验：把写出的 APNG 逐帧解回来，和合成时的帧逐像素比。

    差异矩形 + OP_NONE 那套优化一旦算错，肉眼在静态图上看不出来——错的是
    **播放中间某一帧**的某个角落。这里花 0.1 秒把它钉死，比在真机上肉眼盯
    一条 334×54 的小条子可靠得多。
    """
    from PIL import ImageChops
    im = Image.open(path)
    n = getattr(im, "n_frames", 1)
    if n != len(frames):
        sys.stderr.write("✘ 回读帧数 %d ≠ %d\n" % (n, len(frames)))
        return False
    for i in range(n):
        im.seek(i)
        box = ImageChops.difference(im.convert("RGBA"), frames[i]).getbbox()
        if box:
            sys.stderr.write("✘ 第 %d 帧回读不一致，差异区域 %s\n" % (i, box))
            return False
    sys.stderr.write("  ✔ 回读校验：%d 帧逐像素一致\n" % n)
    return True


def main():
    import argparse
    ap = argparse.ArgumentParser(
        description="生成中文版 connecting.png（默认 APNG，可选竖向雪碧图）")
    ap.add_argument("original", help="原版 connecting.png（334×54）")
    ap.add_argument("out", help="输出 png 路径")
    ap.add_argument("--sheet", action="store_true",
                    help="输出 334×432 竖向雪碧图而不是 APNG（需要配套改 base.css）")
    ap.add_argument("--frame-ms", type=int, default=FRAME_MS,
                    help="APNG 每帧毫秒数（默认 %d，8 帧 = %.1fs 一轮）"
                         % (FRAME_MS, FRAME_MS * FRAMES / 1000.0))
    args = ap.parse_args()
    orig_path, out_path = args.original, args.out

    original = Image.open(orig_path).convert("RGBA")
    if original.size != (FRAME_W, FRAME_H):
        sys.stderr.write("✘ 原版尺寸应为 %dx%d，实际 %s\n"
                         % (FRAME_W, FRAME_H, original.size))
        return 1

    sys.stderr.write("提取文字（loading_icon.png，8 帧合并）…\n")
    icon_frames = load_frames(ICON, ICON_COLS, ICON_PITCH_W, ICON_PITCH_H, FRAMES)
    text = merge_max_alpha([neutral_mask(f) for f in icon_frames])
    text = drop_bubble_residue(text)
    text = hairline_trim(text)
    tb = text.getbbox()
    text = text.crop(tb)
    sys.stderr.write("  文字 bbox %s -> %dx%d\n" % (tb, text.width, text.height))

    sys.stderr.write("提取丘比（loading_char.png，8 帧）…\n")
    char_frames = load_frames(CHAR, CHAR_COLS, CHAR_W, CHAR_H, FRAMES)
    cb = union_bbox(char_frames)
    char_frames = [f.crop(cb) for f in char_frames]
    sys.stderr.write("  丘比并集 bbox %s -> %dx%d\n"
                     % (cb, char_frames[0].width, char_frames[0].height))

    bar = build_bar(original)
    text_img, text_at = fit(text, TEXT_BOX)
    sys.stderr.write("  文字放置 %dx%d @%s\n"
                     % (text_img.width, text_img.height, text_at))

    frames = []
    for i, cf in enumerate(char_frames):
        frame = bar.copy()
        frame.alpha_composite(text_img, text_at)
        ci, cat = fit(cf, CHAR_BOX)
        frame.alpha_composite(ci, cat)
        if i == 0:
            sys.stderr.write("  丘比放置 %dx%d @%s（右边缘 %d）\n"
                             % (ci.width, ci.height, cat, cat[0] + ci.width))
        frames.append(frame)

    if args.sheet:
        sheet = Image.new("RGBA", (FRAME_W, FRAME_H * FRAMES), (0, 0, 0, 0))
        for i, f in enumerate(frames):
            sheet.paste(f, (0, i * FRAME_H))
        sheet.save(out_path, optimize=True)
        sys.stderr.write("✔ 已写出雪碧图 %s（%dx%d，%d 帧，%d 字节）\n"
                         % (out_path, sheet.width, sheet.height, FRAMES,
                            os.path.getsize(out_path)))
        sys.stderr.write(SHEET_CSS)
    else:
        # DISPOSE_OP_NONE(0) + BLEND_OP_SOURCE(0)。
        #
        # SOURCE 而不是 OVER：每帧都带半透明底条，OVER 会把它一层层叠上去，
        # 越播越不透明。SOURCE 是直接覆盖，逐帧都等于我们合成出来的那张。
        #
        # OP_NONE 而不是 OP_BACKGROUND：画布保留上一帧，于是 Pillow 只需把
        # **相邻帧的差异矩形**写进 fdAT。这里只有右边丘比在动，差异矩形很小，
        # 体积从 87KB 降到 31KB。正确性不受影响——差异矩形按定义覆盖了所有
        # 变化的像素（包括丘比原来占着、现在该还原成底条的那部分），
        # 下面的回读校验就是盯着这一点。
        frames[0].save(out_path, save_all=True, append_images=frames[1:],
                       duration=args.frame_ms, loop=0, disposal=0, blend=0,
                       default_image=False, optimize=True)
        sys.stderr.write("✔ 已写出 APNG %s（%dx%d，%d 帧 × %dms，%d 字节）\n"
                         % (out_path, FRAME_W, FRAME_H, FRAMES, args.frame_ms,
                            os.path.getsize(out_path)))
        if not verify_apng(out_path, frames):
            return 1
        sys.stderr.write("  直接替换 magica/resource/image_web/common/global/"
                         "connecting.png 即可，**不用改 CSS**。\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
