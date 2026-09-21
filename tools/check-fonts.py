#!/usr/bin/env python3
"""验证原生字体资源：保持引擎逐项选择，不再用调用者白名单重新分流。

已核对的国服/现行引擎调用点（包含同函数混用两种字体的 Raid cut-in）表明：
MTF4a5kp 资源应承载 reviewed 智黑，mbm 资源应承载 reviewed 大圆。
这是 APK 原生资源别名；不改变 JS 包/WebView 中 mbm 字体族的智黑路由。
保留 reviewed 两种字体及音乐符号，Cocos 加载、缓存、字号与位置交回原引擎。
"""

import hashlib
import argparse
import os
import re
import struct
import sys

FONT_DIR = "assets/fonts"

# 文件名 -> (大小, 内容标识, 内部家族名, 这个文件是干什么的)
# 内容标识支持 sha256:<hex> 或 gitblob:<sha1>；Git blob 同样精确绑定全部字节。
EXPECTED = {
    "MTF4a5kp.ttf": (
        8431292,
        "gitblob:e588b7ddb4b5a1761bf94d73d732b3330d292413",
        "Tensentype ZhiHeiGB18030-W4",
        "原生 MTF 资源：保留引擎的智黑选择",
    ),
    "mbm_20160902.ttf": (
        17571336,
        "gitblob:b121abca3ef624104c84adf2a25de2ea2bea7cf0",
        "Tensentype JiaLiDaYuanGB18030",
        "原生 mbm 资源：保留引擎的大圆选择，与 WebView 字体族分离",
    ),
    "witchText-export.fnt": (
        4525,
        "sha256:1ab05592922270fe52792431f7843a9f767aa50efbfbcbb22f65ea90a78a8118",
        None,
        "魔女文字的位图字体描述",
    ),
    "TTDaYuanGB3.ttf": (
        17571336,
        "gitblob:b121abca3ef624104c84adf2a25de2ea2bea7cf0",
        "Tensentype JiaLiDaYuanGB18030",
        "reviewed 剧情/ADV/叙事字体；2026-09-19 补齐缺失字形版",
    ),
    "TTZhiHeiGB3-W4.ttf": (
        8431292,
        "gitblob:e588b7ddb4b5a1761bf94d73d732b3330d292413",
        "Tensentype ZhiHeiGB18030-W4",
        "reviewed 通用 UI/对话框字体；2026-09-19 补齐缺失字形版",
    ),
    "witchText-export.png": (
        2065782,
        "sha256:43cd69d857986ce393fea96e2ebedd2fe8282df2a7eff4c1d036fb9accdd5d7f",
        None,
        "魔女文字的字形图集",
    ),
}


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def git_blob_sha1(path):
    data = open(path, "rb").read()
    h = hashlib.sha1()
    h.update(("blob %d\0" % len(data)).encode("ascii"))
    h.update(data)
    return h.hexdigest()


def content_id(path, spec):
    if spec.startswith("sha256:"):
        return sha256(path), spec[7:], "sha256"
    if spec.startswith("gitblob:"):
        return git_blob_sha1(path), spec[8:], "git blob"
    raise ValueError("未知内容标识：" + spec)


def family_name(path):
    """从 TTF 的 name 表里取家族名（nameID 1）。解析不了就返回 None。"""
    with open(path, "rb") as f:
        data = f.read()
    if len(data) < 12:
        return None
    num_tables = struct.unpack(">H", data[4:6])[0]
    off, tables = 12, {}
    for _ in range(num_tables):
        if off + 16 > len(data):
            return None
        tag, _cs, t_off, t_len = struct.unpack(">4sIII", data[off:off + 16])
        off += 16
        tables[tag] = (t_off, t_len)
    if b"name" not in tables:
        return None
    n_off, _ = tables[b"name"]
    count, str_off = struct.unpack(">HH", data[n_off + 2:n_off + 6])
    for i in range(count):
        rec = n_off + 6 + 12 * i
        if rec + 12 > len(data):
            return None
        pid, _eid, _lid, nid, length, s_off = struct.unpack(">HHHHHH", data[rec:rec + 12])
        if nid != 1:
            continue
        raw = data[n_off + str_off + s_off:n_off + str_off + s_off + length]
        try:
            return (raw.decode("utf-16-be") if pid == 3 else raw.decode("latin1")).strip()
        except UnicodeDecodeError:
            return None
    return None


# 玩家实际文本里会出现的音乐符号。旧国服原始 TTZhiHei / TTDaYuan 两个文件
# 都缺 U+266A/U+266F；2026-09-19 reviewed carrier 必须把它们补回来。
# U+F6DB 仍是独立未闭合问题，不在这里伪装成已解决。
REQUIRED_GLYPHS = {
    "MTF4a5kp.ttf": {0x266A: "♪", 0x266F: "♯"},
    "mbm_20160902.ttf": {0x266A: "♪", 0x266F: "♯"},
    "TTDaYuanGB3.ttf": {0x266A: "♪", 0x266F: "♯"},
    "TTZhiHeiGB3-W4.ttf": {0x266A: "♪", 0x266F: "♯"},
}


def font_has_codepoint(path, cp):
    """只解析构建所需的 cmap format 4/12，判断 cp 是否映射到非零 glyph。"""
    data = open(path, "rb").read()
    if len(data) < 12:
        return False
    num_tables = struct.unpack(">H", data[4:6])[0]
    tables = {}
    off = 12
    for _ in range(num_tables):
        if off + 16 > len(data):
            return False
        tag, _cs, t_off, t_len = struct.unpack(">4sIII", data[off:off + 16])
        off += 16
        tables[tag] = (t_off, t_len)
    if b"cmap" not in tables:
        return False
    c_off, c_len = tables[b"cmap"]
    if c_off + 4 > len(data):
        return False
    _version, count = struct.unpack(">HH", data[c_off:c_off + 4])
    for i in range(count):
        rec = c_off + 4 + i * 8
        if rec + 8 > len(data):
            continue
        platform, encoding, rel = struct.unpack(">HHI", data[rec:rec + 8])
        if platform not in (0, 3):
            continue
        sub = c_off + rel
        if sub + 2 > len(data):
            continue
        fmt = struct.unpack(">H", data[sub:sub + 2])[0]

        if fmt == 12 and sub + 16 <= len(data):
            n_groups = struct.unpack(">I", data[sub + 12:sub + 16])[0]
            pos = sub + 16
            for _ in range(n_groups):
                if pos + 12 > len(data):
                    break
                start, end, glyph0 = struct.unpack(">III", data[pos:pos + 12])
                pos += 12
                if start <= cp <= end:
                    return glyph0 + (cp - start) != 0

        if fmt == 4 and cp <= 0xFFFF and sub + 14 <= len(data):
            length = struct.unpack(">H", data[sub + 2:sub + 4])[0]
            end_sub = min(len(data), sub + length)
            seg_count = struct.unpack(">H", data[sub + 6:sub + 8])[0] // 2
            end_base = sub + 14
            start_base = end_base + 2 * seg_count + 2
            delta_base = start_base + 2 * seg_count
            range_base = delta_base + 2 * seg_count
            if range_base + 2 * seg_count > end_sub:
                continue
            for seg in range(seg_count):
                end_code = struct.unpack(">H", data[end_base + 2*seg:end_base + 2*seg + 2])[0]
                start_code = struct.unpack(">H", data[start_base + 2*seg:start_base + 2*seg + 2])[0]
                if not (start_code <= cp <= end_code):
                    continue
                delta = struct.unpack(">h", data[delta_base + 2*seg:delta_base + 2*seg + 2])[0]
                ro = struct.unpack(">H", data[range_base + 2*seg:range_base + 2*seg + 2])[0]
                if ro == 0:
                    return ((cp + delta) & 0xFFFF) != 0
                glyph_pos = range_base + 2*seg + ro + 2*(cp - start_code)
                if glyph_pos + 2 > end_sub:
                    return False
                glyph = struct.unpack(">H", data[glyph_pos:glyph_pos + 2])[0]
                if glyph == 0:
                    return False
                return ((glyph + delta) & 0xFFFF) != 0
    return False


NATIVE_SRC = "magia-native/src/MagiaLegacy.cpp"


def check_redirect_target():
    """验证字体参数留给原引擎；文本翻译入口继续存在。"""
    if not os.path.isfile(NATIVE_SRC):
        return ["找不到 " + NATIVE_SRC]
    text = open(NATIVE_SRC, encoding="utf-8").read()
    problems = []
    for token in ("isCnStoryFontCaller", "g_storyFontDepth", "fontPathFix(",
                  "fontPathOverwrite(", "getFontAtlasTtfNew", "fontFreeTypeCreateNew",
                  "setTtfCfgInternalNew"):
        if token in text:
            problems.append("仍存在额外字体分流/改写: " + token)
    for token in ("translateTtfInitialText", "createWithTtfCfgOld(cfg, &fk, h, i)",
                  "createWithTtfCfgOld(cfg, text, h, i)",
                  "createWithTtfStrOld(&fk, font, size, dims, h, v)",
                  "createWithTtfStrOld(text, font, size, dims, h, v)"):
        if token not in text:
            problems.append("构造文本翻译或字体/布局参数透传缺失: " + token)
    return problems


def main():
    # 字体在**重建树**里，不在仓库里（2026-08-14 起原包派生文件已从仓库删除）。
    # 所以要么给 --tree 指到重建树，要么先跑 baseline.py apply。
    ap = argparse.ArgumentParser()
    ap.add_argument("--tree", default=os.environ.get("TREE", "."),
                    help="客户端基线树根，默认取环境变量 TREE，再默认当前目录")
    args = ap.parse_args()
    global FONT_DIR
    FONT_DIR = os.path.join(args.tree, FONT_DIR)

    if not os.path.isdir(FONT_DIR):
        print("找不到目录 " + FONT_DIR, file=sys.stderr)
        return 2

    actual = set(os.listdir(FONT_DIR))
    expected = set(EXPECTED)
    problems = list(check_redirect_target())

    for extra in sorted(actual - expected):
        problems.append(
            "多出文件 %s/%s —— 新增字体必须同时登记到本脚本，否则没人知道它"
            "该是什么内容" % (FONT_DIR, extra))
    for missing in sorted(expected - actual):
        problems.append(
            "缺少文件 %s/%s —— 引擎会加载失败" % (FONT_DIR, missing))

    for name in sorted(expected & actual):
        size, digest, family, purpose = EXPECTED[name]
        path = os.path.join(FONT_DIR, name)
        real_size = os.path.getsize(path)
        if real_size != size:
            problems.append(
                "%s 大小不符：%d，应为 %d\n    用途：%s"
                % (name, real_size, size, purpose))
            continue
        real_digest, expected_digest, digest_kind = content_id(path, digest)
        if real_digest != expected_digest:
            problems.append(
                "%s 内容被改过\n    实际 %s %s\n    应为      %s\n    用途：%s"
                % (name, digest_kind, real_digest, expected_digest, purpose))
            continue
        if family is not None:
            real_family = family_name(path)
            if real_family != family:
                problems.append(
                    "%s 的内部家族名是「%s」，应为「%s」\n    用途：%s"
                    % (name, real_family, family, purpose))
        for cp, glyph in REQUIRED_GLYPHS.get(name, {}).items():
            if not font_has_codepoint(path, cp):
                problems.append(
                    "%s 缺少 U+%04X「%s」——这是产品文本实际使用的符号"
                    % (name, cp, glyph))

    if problems:
        print("✘ 字体守卫未通过：", file=sys.stderr)
        for p in problems:
            print("  · " + p, file=sys.stderr)
        print("", file=sys.stderr)
        print("字体路由按国服双字体分工：UI→TTZhiHei，剧情→TTDaYuan。", file=sys.stderr)
        print("reviewed 字体内容来自固定提交 71d3278e…，不得临时换回原始缺字形版本。",
              file=sys.stderr)
        print("", file=sys.stderr)
        print("确实要改基线（例如换了新的授权字体），就更新本脚本的 EXPECTED 表，"
              "并在提交信息里写明来源与授权。", file=sys.stderr)
        return 1

    print("✔ 字体守卫通过（%d 个文件，内容身份/家族名/双字体路由均相符）" % len(expected))
    for name in sorted(expected):
        size, _d, family, _p = EXPECTED[name]
        print("    %-22s %9d B  %s" % (name, size, family or "（位图字体）"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
