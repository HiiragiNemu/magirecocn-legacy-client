#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""安装完成标记的 Java / native 解析器**跨语言等价**检查（F-074）。

## 为什么要有这一份

「这台设备的基础资源装完了没」这个判据同时存在两份实现：

    Java   CNDownloaderFix.parseFinalFlag(String)
    native MagiaLegacy.cpp 的 parseFinalFlag(const std::string&)

两边控制的是**同一件事的两半**：Java 决定要不要启动安装器、要不要接力热更；
native 决定要不要跳过引擎自带的下载场景、要不要叫起 Java 安装器、下载回调静默组
与放行组的极性。两边一旦对同一份标记给出不同结论，就会出现「Java 认为没装完、
正在装，native 认为装好了、把引擎放进一棵缺资源的树」这种谁都不报错的状态。

原本只有 native 一份「文件在不在」的判据，Java 那边收紧成按正文判之后，两边就
不再是同一个状态机了——这正是本检查存在的理由。

## 判据

不比对源码文本（两种语言写法必然不同），而是**把两份实现都跑起来**，喂同一批
向量，逐条比结论。做法：

  1. 从 `MagiaLegacy.cpp` 里按花括号配平抠出 `parseFinalFlag`，塞进一个几行的
     C++ harness，用宿主 g++ 编译；
  2. 从 `CNDownloaderFix.java` 里抠出 `parseFinalFlag` 与它依赖的 `parseIntOr`，
     连同 `ARCHIVE_COUNT` 的实际取值塞进一个独立 Java 类，用 javac 编译；
  3. 同一张向量表喂给两边，任何一条结论不一致就红。

抠函数而不是链接真实产物，是为了让这个检查**不依赖构建树**：NDK 与 android.jar
都不需要，5 秒出结果，可以放进每次 push 的守卫步骤。代价是它只覆盖这两个纯函数
——「谁在什么时候调它」由 check-download-ui-contract.py 那边钉。

用法：python3 tools/check-final-flag-parity.py
退出码：0 = 两边一致；1 = 有分歧或抠不出函数。
"""

import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CPP = os.path.join(ROOT, "magia-native", "src", "MagiaLegacy.cpp")
JAVA = os.path.join(ROOT, "patch", "src", "main", "java",
                    "io", "kamihama", "magianative", "CNDownloaderFix.java")

# 向量表。三类：正常、边界、以及「只问文件在不在」会放过去的那些半截内容。
VECTORS = [
    "schema=2\narchives=15\n",
    "schema=3\narchives=15\n",
    "schema=1\narchives=15\n",
    "archives=15\nschema=2\n",
    "schema=2\nnote=x\narchives=15\n",
    "schema=2\r\narchives=15\r\n",
    "  schema = 2  \n  archives = 15  \n",
    "schema=2\narchives=15",
    "",
    "schema=2\n",
    "archives=15\n",
    "schema=2\narch",
    "schema=2\narchives=14\n",
    "schema=2\narchives=16\n",
    "schema=0\narchives=15\n",
    "schema=-1\narchives=15\n",
    "schema=x\narchives=15\n",
    "schema=\narchives=15\n",
    "schema=2\narchives=\n",
    "schema=2\narchives=15x\n",
    "schema=02\narchives=15\n",
    "   ",
    "\n\n\n",
    "=2\narchives=15\n",
    "schema=2\narchives=15\nschema=0\n",
    "schema=2\narchives=15\narchives=14\n",
    "SCHEMA=2\narchives=15\n",
    "schema=2147483647\narchives=15\n",
]


def die(msg):
    print("✘ " + msg, file=sys.stderr)
    raise SystemExit(1)


def extract(text, signature, kind):
    """从签名那行起按花括号配平抠出函数体（含签名）。"""
    i = text.find(signature)
    if i < 0:
        die("%s 里找不到 %r —— 签名改了？改了就同步改本脚本，"
            "不要把这个检查删掉。" % (kind, signature))
    j = text.index("{", i)
    depth, k = 0, j
    while k < len(text):
        if text[k] == "{":
            depth += 1
        elif text[k] == "}":
            depth -= 1
            if depth == 0:
                return text[i:k + 1]
        k += 1
    die("%s 里 %r 的花括号没配平" % (kind, signature))


def encode(vectors):
    """向量走 \\xHH 转义传给两边，免得 shell / 源码字面量把控制字符吃掉。"""
    out = []
    for v in vectors:
        out.append("".join("\\x%02X" % b for b in v.encode("utf-8")))
    return out


DECODE_CPP = r'''
static std::string decodeHex(const char* s) {
    std::string out;
    for (size_t i = 0; s[i]; ) {
        if (s[i] == '\\' && s[i + 1] == 'x') {
            int hi = s[i + 2], lo = s[i + 3];
            auto v = [](int c) {
                if (c >= '0' && c <= '9') return c - '0';
                if (c >= 'a' && c <= 'f') return c - 'a' + 10;
                return c - 'A' + 10;
            };
            out.push_back((char)(v(hi) * 16 + v(lo)));
            i += 4;
        } else {
            out.push_back(s[i]);
            i++;
        }
    }
    return out;
}
'''

DECODE_JAVA = r'''
    static String decodeHex(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            if (s.charAt(i) == '\\' && i + 3 < s.length() && s.charAt(i + 1) == 'x') {
                b.append((char) Integer.parseInt(s.substring(i + 2, i + 4), 16));
                i += 4;
            } else {
                b.append(s.charAt(i));
                i++;
            }
        }
        return b.toString();
    }
'''


def run_cpp(tmp, body, archives, maxbytes, vectors):
    src = os.path.join(tmp, "parity.cpp")
    with open(src, "w", encoding="utf-8") as f:
        f.write("#include <string>\n#include <cstdio>\n#include <cstdlib>\n")
        f.write("static const long FINAL_FLAG_ARCHIVES = %d;\n" % archives)
        f.write("static const size_t FINAL_FLAG_MAX_BYTES = %d;\n" % maxbytes)
        f.write(body + "\n")
        f.write(DECODE_CPP)
        f.write("int main(int argc, char** argv) {\n"
                "    for (int i = 1; i < argc; i++) {\n"
                "        std::printf(\"%d\\n\", parseFinalFlag(decodeHex(argv[i])) ? 1 : 0);\n"
                "    }\n"
                "    return 0;\n"
                "}\n")
    exe = os.path.join(tmp, "parity")
    r = subprocess.run(["g++", "-std=c++17", "-O0", "-o", exe, src],
                       capture_output=True, text=True)
    if r.returncode != 0:
        die("native 侧 harness 编译失败：\n" + r.stderr)
    r = subprocess.run([exe] + vectors, capture_output=True, text=True)
    if r.returncode != 0:
        die("native 侧 harness 运行失败：\n" + r.stderr)
    return r.stdout.split()


def run_java(tmp, body, helper, archives, vectors):
    src = os.path.join(tmp, "Parity.java")
    with open(src, "w", encoding="utf-8") as f:
        f.write("public class Parity {\n")
        f.write("    private static final int ARCHIVE_COUNT = %d;\n" % archives)
        f.write(body.replace("public static boolean parseFinalFlag",
                             "static boolean parseFinalFlag") + "\n")
        f.write(helper + "\n")
        f.write(DECODE_JAVA)
        f.write("    public static void main(String[] a) {\n"
                "        for (int i = 0; i < a.length; i++) {\n"
                "            System.out.println(parseFinalFlag(decodeHex(a[i])) ? 1 : 0);\n"
                "        }\n"
                "    }\n}\n")
    r = subprocess.run(["javac", "-nowarn", "-encoding", "UTF-8", "-d", tmp, src],
                       capture_output=True, text=True)
    if r.returncode != 0:
        die("Java 侧 harness 编译失败：\n" + r.stderr)
    r = subprocess.run(["java", "-cp", tmp, "Parity"] + vectors,
                       capture_output=True, text=True)
    if r.returncode != 0:
        die("Java 侧 harness 运行失败：\n" + r.stderr)
    return [l for l in r.stdout.split() if l in ("0", "1")]


def main():
    for tool in ("g++", "javac", "java"):
        if shutil.which(tool) is None:
            die("找不到 %s，跑不了跨语言等价检查" % tool)

    cpp = open(CPP, encoding="utf-8").read()
    java = open(JAVA, encoding="utf-8").read()

    cpp_body = extract(cpp, "static bool parseFinalFlag(const std::string& body)",
                       "MagiaLegacy.cpp")
    java_body = extract(java, "public static boolean parseFinalFlag(String body)",
                        "CNDownloaderFix.java")
    java_helper = extract(java, "private static int parseIntOr(String s, int fallback)",
                          "CNDownloaderFix.java")

    m = re.search(r"ARCHIVE_COUNT\s*=\s*(\d+)", java)
    if not m:
        die("CNDownloaderFix 里读不出 ARCHIVE_COUNT")
    archives = int(m.group(1))

    m = re.search(r"FINAL_FLAG_ARCHIVES\s*=\s*(\d+)", cpp)
    if not m:
        die("MagiaLegacy.cpp 里读不出 FINAL_FLAG_ARCHIVES")
    cpp_archives = int(m.group(1))
    if cpp_archives != archives:
        die("两边的包数不一致：Java ARCHIVE_COUNT=%d，native FINAL_FLAG_ARCHIVES=%d"
            % (archives, cpp_archives))

    m = re.search(r"FINAL_FLAG_MAX_BYTES\s*=\s*(\d+)", cpp)
    cpp_max = int(m.group(1)) if m else 16384
    m = re.search(r"FINAL_FLAG_MAX_BYTES\s*=\s*(\d+)L", java)
    if m and int(m.group(1)) != cpp_max:
        die("两边的正文大小上限不一致：Java %s，native %d" % (m.group(1), cpp_max))

    enc = encode(VECTORS)
    tmp = tempfile.mkdtemp(prefix="flag-parity-")
    try:
        native = run_cpp(tmp, cpp_body, archives, cpp_max, enc)
        jvm = run_java(tmp, java_body, java_helper, archives, enc)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    if len(native) != len(VECTORS) or len(jvm) != len(VECTORS):
        die("结果条数对不上：native %d 条、Java %d 条、向量 %d 条"
            % (len(native), len(jvm), len(VECTORS)))

    bad = []
    for i, v in enumerate(VECTORS):
        if native[i] != jvm[i]:
            bad.append((v, jvm[i], native[i]))
    for i, v in enumerate(VECTORS):
        mark = "✓" if native[i] == jvm[i] else "✗"
        print("%s %-40r java=%s native=%s" % (mark, v, jvm[i], native[i]))

    if bad:
        print("", file=sys.stderr)
        for v, j, n in bad:
            print("✘ 分歧: %r → Java %s / native %s" % (v, j, n), file=sys.stderr)
        die("Java 与 native 的安装完成判据已经分叉——两边控制的是同一件事的两半，"
            "分叉意味着「一边在装、另一边已经把引擎放进去了」，且谁都不报错。")

    print("✔ %d 条向量上 Java 与 native 结论一致" % len(VECTORS))
    return 0


if __name__ == "__main__":
    sys.exit(main())
