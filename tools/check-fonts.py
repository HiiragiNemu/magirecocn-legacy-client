#!/usr/bin/env python3
"""字体守卫：钉死 assets/fonts/ 下每个文件的哈希，并校验内容与文件名相符。

## 为什么需要它

字体这条线在本仓库绕了两圈才落地，两次都是**改字体文件本体**惹的祸：

    c5e46de8  字体直替：MTF4a5kp/mbm 两个文件内容直接换成 TTZhiHeiGB3-W4
    f4477262  Revert 上面那条
    43a2e86d  引擎字体替换 + 前缀规则
    1c29ba38  回滚字体路径钩子：字体问题不通过换字体解决
    3348273b  引擎 UI 字体路径重定向（最终方案：只改加载路径，不碰文件）
    5df4b46d  修复字体重定向堆破坏

维护者最后定的路线是**只重定向加载路径，绝不改文件内容**——可热回滚、
出问题改一行常量就退回去。但这个约定只写在提交信息里，没有任何机制拦着
下一个人再来一次「直替更省事」。本脚本就是那个机制。

## 一个必须写下来的事实：国服自己就是直替做的

`assets/fonts/` 里有三个文件**字节完全相同**：

    MTF4a5kp.ttf         17507340B   内部名 Tensentype JiaLiDaYuanGB18030
    TTDaYuanGB3.ttf      17507340B   内部名 Tensentype JiaLiDaYuanGB18030
    koruri-semibold.ttf  17507340B   内部名 Tensentype JiaLiDaYuanGB18030

`MTF4a5kp` 与 `koruri-semibold` 都是**日文**字体的文件名，内容却是中文的
「腾祥嘉丽大圆」。这不是本仓库造成的——它们在**根提交**（导入 APK 那一刻）
就已经是这样，也就是说**国服官方的汉化手段本身就是把日文字体文件内容换掉**。

这条事实很容易被误读成「仓库被污染了，得从国服 APK 恢复原始字体」。恰恰相反：
**当前状态就是国服的权威状态**，要钉住的正是它。同理，现行的
`MTF4a5kp → mbm_20160902` 路径重定向并不是「日文换中文」——后者国服早就
做完了——而是把 UI 汇到覆盖最好的那个字体上。

## 判据

1. 文件集合不多不少（多出来的字体不会被引用，少了会让引擎加载失败）；
2. 每个文件的 SHA-256 与下表一致；
3. 每个文件内部的字体家族名与下表一致——哈希能挡住「换内容」，
   这一条额外挡住「换成同尺寸的另一个字体」，同时充当活文档：
   下一个人不必像我一样先解析一遍 name 表才知道每个文件到底是什么。

用法：python3 tools/check-fonts.py
"""

import hashlib
import os
import re
import struct
import sys

FONT_DIR = "assets/fonts"

# 文件名 -> (大小, SHA-256, 内部家族名, 这个文件是干什么的)
# 家族名为 None 表示不是 TTF（位图字体的 .fnt/.png），只校验哈希。
EXPECTED = {
    "MTF4a5kp.ttf": (
        17507340,
        "01bbb65b3b21f8d445fe15412fc3b5864425033f534464be26de0aa7ed8150c0",
        "Tensentype JiaLiDaYuanGB18030",
        "引擎 UI 主字体。文件名是日文原版的，内容已被国服换成大圆；"
        "native 侧再把加载路径重定向到 mbm_20160902.ttf",
    ),
    "TTDaYuanGB3.ttf": (
        17507340,
        "01bbb65b3b21f8d445fe15412fc3b5864425033f534464be26de0aa7ed8150c0",
        "Tensentype JiaLiDaYuanGB18030",
        "大圆本体，与 MTF4a5kp.ttf 字节相同",
    ),
    "koruri-semibold.ttf": (
        17507340,
        "01bbb65b3b21f8d445fe15412fc3b5864425033f534464be26de0aa7ed8150c0",
        "Tensentype JiaLiDaYuanGB18030",
        "文件名是日文 Koruri，内容同样已被国服换成大圆",
    ),
    "TTZhiHeiGB3-W4.ttf": (
        8367096,
        "01a4be2e5fca489c30219b3bec5edac0b7c98128c5fa629c34a0208ed5b0ba34",
        "Tensentype ZhiHeiGB18030-W4",
        "GB 标准黑体。曾是路径重定向的目标（3348273b），现已改指 mbm；"
        "留着是因为引擎/前端可能仍有引用，且它是可用的回退选项",
    ),
    "mbm_20160902.ttf": (
        9070328,
        "51383ac04bf0835445a0de382c07e6467f43991c6a51cf13a4327cad51f58b03",
        "MagiReco CN Medium",
        "国服自制字体。剧情/台词本来就用它，现在 UI 也重定向到这里——"
        "它是几个字体里覆盖最好的（格式 12 cmap、30823 码位）",
    ),
    "witchText-export.fnt": (
        4525,
        "1ab05592922270fe52792431f7843a9f767aa50efbfbcbb22f65ea90a78a8118",
        None,
        "魔女文字的位图字体描述",
    ),
    "witchText-export.png": (
        2065782,
        "43cd69d857986ce393fea96e2ebedd2fe8282df2a7eff4c1d036fb9accdd5d7f",
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

    顺带校验长度：libc++ 短串上限是 22 字符，超了就会让 fontPathOverwrite
    走「另分配缓冲」的那条路——5df4b46d 修过堆破坏的那一条。
    """
    if not os.path.isfile(NATIVE_SRC):
        return ["找不到 " + NATIVE_SRC]
    text = open(NATIVE_SRC, encoding="utf-8").read()
    problems = []
    m = re.search(r'static\s+const\s+char\s+kTo\[\]\s*=\s*"fonts/([^"]+)"', text)
    if not m:
        return ["在 %s 里找不到字体重定向目标 kTo，重定向可能被删了或改了写法"
                % NATIVE_SRC]
    target = m.group(1)
    if target not in EXPECTED:
        problems.append(
            "native 把字体重定向到 fonts/%s，但 %s 下没有这个文件"
            "（引擎会加载失败并静默回落）" % (target, FONT_DIR))
    path_len = len("fonts/" + target)
    if path_len > 22:
        problems.append(
            "重定向目标路径 fonts/%s 是 %d 字符，超过 libc++ 短串上限 22。"
            "这会让每次重定向都走 fontPathOverwrite 的独立分配路径"
            "（5df4b46d 修过堆破坏的那条）。确认过所有权约定再放行。"
            % (target, path_len))
    return problems


def main():
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
        real_digest = sha256(path)
        if real_digest != digest:
            problems.append(
                "%s 内容被改过\n    实际 sha256 %s\n    应为      %s\n    用途：%s"
                % (name, real_digest, digest, purpose))
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
        print("字体问题**不通过换字体文件解决**——这是 1c29ba38 定下的路线。",
              file=sys.stderr)
        print("要换界面字体，改 magia-native/src/MagiaLegacy.cpp 里那对常量：",
              file=sys.stderr)
        print('    static const char kFrom[] = "fonts/MTF4a5kp.ttf";', file=sys.stderr)
        print('    static const char kTo[]   = "fonts/mbm_20160902.ttf";',
              file=sys.stderr)
        print("这样随时能热回滚；直接替换文件内容做不到，而且历史上已经回滚过一次"
              "（c5e46de8 → f4477262）。", file=sys.stderr)
        print("", file=sys.stderr)
        print("确实要改基线（例如换了新的授权字体），就更新本脚本的 EXPECTED 表，"
              "并在提交信息里写明来源与授权。", file=sys.stderr)
        return 1

    print("✔ 字体守卫通过（%d 个文件，哈希与内部家族名均相符）" % len(expected))
    for name in sorted(expected):
        size, _d, family, _p = EXPECTED[name]
        print("    %-22s %9d B  %s" % (name, size, family or "（位图字体）"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
