#!/usr/bin/env python3
"""字体守卫：钉死 assets/fonts/ 下每个文件的内容身份，并校验国服双字体路由。

## 为什么需要它

字体这条线在本仓库绕了两圈才落地，两次都是**改字体文件本体**惹的祸：

    702ebbf3  字体直替：MTF4a5kp/mbm 两个文件内容直接换成 TTZhiHeiGB3-W4
    703cb30f  Revert 上面那条
    e1c3bf6d  引擎字体替换 + 前缀规则
    6ad7aa23  回滚字体路径钩子：字体问题不通过换字体解决
    f7f41ce6  引擎 UI 字体路径重定向（最终方案：只改加载路径，不碰文件）
    dd06a6b6  修复字体重定向堆破坏

维护者最后定的路线是**只重定向加载路径，不用“把别的字体内容塞进旧文件名”来改路由**。
1.0.178 另外使用 2026-09-19 已审阅、补齐缺失字形的 TTDaYuan / TTZhiHei 两个
载体；构建时从固定提交取回，并以 Git blob SHA 精确钉死字节。

## 一个必须写下来的事实：国服自己就是直替做的

`assets/fonts/` 曾有四个**北京腾祥**的商业字体（约 58 MB），2026-08-14 清理掉了。

清理判据是「引擎到底请不请求它」，不是观感也不是许可偏好——在
`libmadomagi_native.so` 里查字符串：

    Totentanz 基线：MTF4a5kp / mbm_20160902
    国服 v2.2.1：TTZhiHeiGB3-W4 / TTDaYuanGB3

上传的国服 v2.2.1 离线包经双 ABI native 字符串/xref 复核：
通用 UI 大量引用 TTZhiHei，StoryMessage/StoryNarration/RaidScrollView 等剧情路径
引用 TTDaYuan。因此 1.0.178 把 Totentanz 的两条请求名分别映射到国服两条字体。

`MTF4a5kp.ttf` 仍从重建树删除，因为请求会在 native hook 中改写；
`mbm_20160902.ttf` 保留基线文件以兼容其它非 hook 消费者，但走 Label TTF 钩子的
剧情请求会重定向到 TTDaYuan。TTZhiHei / TTDaYuan 的 reviewed carrier 在重建后覆盖/加入。

> 这两件事是配套的，别只做一半：先撤开关再删文件才安全，反过来则会给
> `noFontHook` 留下一条必然失败的路径。

> 历史提醒：`koruri-semibold.ttf` 这个文件名是**误导性**的。Koruri 是 Apache-2.0
> 的日文开源字体，而那个文件的内容是腾祥嘉丽大圆——按文件名做合规审计会看走眼。
> 这不是本仓库造成的（根提交就这样，是国服官方汉化时替换文件内容留下的），
> 但清理时正好把这个雷一起拆了。同理，1.0.178 的路由目标不是“选一个覆盖率最大的字体统一全站”，而是恢复国服的
> UI=TTZhiHei、剧情=TTDaYuan 分工。

## 判据

1. 文件集合不多不少（多出来的字体不会被引用，少了会让引擎加载失败）；
2. 每个文件的内容身份与下表一致（原基线文件用 SHA-256；reviewed 字体用 Git blob SHA-1）；
3. 每个文件内部的字体家族名与下表一致；
   这一条额外挡住「换成同尺寸的另一个字体」，同时充当活文档：
   下一个人不必像我一样先解析一遍 name 表才知道每个文件到底是什么。

用法：python3 tools/check-fonts.py
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
    "mbm_20160902.ttf": (
        9070328,
        "sha256:51383ac04bf0835445a0de382c07e6467f43991c6a51cf13a4327cad51f58b03",
        "MagiReco CN Medium",
        "基线兼容载体；Label TTF 剧情路径会重定向到 TTDaYuan",
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


NATIVE_SRC = "magia-native/src/MagiaLegacy.cpp"


def check_redirect_target():
    """native 的重定向目标必须是确实随包发出去的字体。

    这条是哈希校验之外的另一半：哈希保证「文件没被换掉」，这条保证
    「代码指向的文件确实存在」。少了它，把 kTo 敲错一个字母不会有任何报错——
    引擎加载失败后自己回落，界面看上去只是「字体没生效」，而这在真机上要
    肉眼比对才发现，历史上字体这条线已经为类似的沉默失败来回过几轮。

    顺带校验长度：libc++ classic string 的短串上限取决于 ABI——ARM64 是 22，
    ARMv7 是 10。当前 22 字符目标在 ARM64 走短串、ARMv7 走 long；超过 22
    才会让两个 ABI 都进入「另分配缓冲」路径。
    """
    if not os.path.isfile(NATIVE_SRC):
        return ["找不到 " + NATIVE_SRC]
    text = open(NATIVE_SRC, encoding="utf-8").read()
    problems = []
    expected_routes = {
        "kUiFrom": "MTF4a5kp.ttf",
        "kUiTo": "TTZhiHeiGB3-W4.ttf",
        "kStoryFrom": "mbm_20160902.ttf",
        "kStoryTo": "TTDaYuanGB3.ttf",
    }
    found = {}
    for var, want in expected_routes.items():
        m = re.search(
            r'static\s+const\s+char\s+' + re.escape(var)
            + r'\[\]\s*=\s*"fonts/([^"]+)"',
            text,
        )
        if not m:
            problems.append("在 %s 里找不到字体路由常量 %s" % (NATIVE_SRC, var))
            continue
        found[var] = m.group(1)
        if found[var] != want:
            problems.append("%s 路由是 %s，应为 %s" % (var, found[var], want))

    for var in ("kUiTo", "kStoryTo"):
        target = found.get(var)
        if not target:
            continue
        if target not in EXPECTED:
            problems.append(
                "native 把字体重定向到 fonts/%s，但 %s 下没有登记该文件"
                % (target, FONT_DIR))
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
