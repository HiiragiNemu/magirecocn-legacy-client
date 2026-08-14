#!/usr/bin/env python3
"""把 APK 里的 CRI HCA BGM 转成浮层能播的 OGG，并导出循环点。

APK 自带的 assets/resource/sound_native/bgm/*.hca 是 CRI HCA、且 ciphType=56
（加密）。Android 的 MediaPlayer / MediaCodec 都不认这个格式，所以在构建期转成
OGG(Vorbis) 放进 assets/magia/。

密钥不用我们操心：vgmstream 自带 CRI 游戏的密钥库，能直接解 ciphType-56。

同时把 HCA 头里的循环点导出到 assets/magia/bgm.json —— 播放器按这个循环，而不是
简单地整文件 setLooping()。两者的差别在于文件尾部那几百帧编码器 padding
**不属于循环区**，整文件循环会把它放出来并在接缝留下空隙。

用法：
    python3 tools/convert-bgm.py                 # 需要 vgmstream-cli 与 ffmpeg 在 PATH
    python3 tools/convert-bgm.py --vgmstream ... --ffmpeg ...
"""

import argparse
import json
import os
import re
import struct
import subprocess
import sys

BGM_DIR = "assets/resource/sound_native/bgm"
OUT_DIR = "assets/magia"
# 源文件 → 浮层里的编号。顺序即 BGM1 / BGM2，改动会直接改变玩家看到的编号。
TRACKS = [
    (1, "bgm00_system01_hca.hca"),
    (2, "bgm00_system02_hca.hca"),
]


def run(cmd):
    return subprocess.run(cmd, shell=True, text=True, capture_output=True)


def hca_header(path):
    """从 HCA 头读采样率/声道/加密类型。仅用于校验与日志，循环点以 vgmstream 为准。

    HCA 的块签名首字节带 0x80 混淆位，比较前要掩掉。
    """
    d = open(path, "rb").read(0x200)

    def sig(o):
        return bytes(b & 0x7F for b in d[o : o + 4])

    if sig(0) != b"HCA\x00":
        raise ValueError(f"{path} 不是 HCA")
    header_size = struct.unpack_from(">H", d, 6)[0]
    info = {"ciph": 0}
    off = 8
    while off < header_size - 2:
        s = sig(off)
        if s == b"fmt\x00":
            info["channels"] = d[off + 4]
            info["sample_rate"] = struct.unpack_from(">I", d, off + 4)[0] & 0x00FFFFFF
            off += 16
        elif s == b"comp":
            off += 16
        elif s == b"dec\x00":
            off += 12
        elif s == b"ciph":
            info["ciph"] = struct.unpack_from(">H", d, off + 4)[0]
            off += 6
        elif s == b"loop":
            off += 16
        elif s == b"ath\x00":
            off += 6
        elif s in (b"vbr\x00", b"rva\x00"):
            off += 8
        elif s == b"comm":
            off += 5 + d[off + 4]
        else:
            break
    return info


def probe_loop(vgmstream, path):
    """用 vgmstream 拿循环点（单位：采样帧）。

    直接读 HCA 头里的 loop 块也能拿到，但那是**块**号，换算回采样帧还要减掉
    编码器延迟；vgmstream 已经处理好这些，直接用它的结果不容易出错。
    """
    r = run(f'"{vgmstream}" -m "{path}"')
    if r.returncode != 0:
        raise RuntimeError(f"vgmstream -m 失败: {r.stderr.strip()}")
    out = r.stdout

    def grab(pattern):
        m = re.search(pattern, out)
        return int(m.group(1)) if m else None

    return {
        "sample_rate": grab(r"sample rate:\s*(\d+)"),
        "channels": grab(r"channels:\s*(\d+)"),
        "loop_start": grab(r"loop start:\s*(\d+) samples"),
        "loop_end": grab(r"loop end:\s*(\d+) samples"),
        "total": grab(r"stream total samples:\s*(\d+)"),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--vgmstream", default="vgmstream-cli")
    ap.add_argument("--ffmpeg", default="ffmpeg")
    ap.add_argument("--quality", default="5", help="libvorbis -q:a，默认 5")
    # 工程树根。默认是当前目录（仓库自己那棵树）；CI 的 baseline 腿从Totentanz 整包
    # 重建出另一棵树，源与产物都得落在那棵树里，而不是仓库根。
    ap.add_argument("--tree", default=".", help="客户端基线树根，默认当前目录")
    args = ap.parse_args()

    global BGM_DIR, OUT_DIR
    BGM_DIR = os.path.join(args.tree, BGM_DIR)
    OUT_DIR = os.path.join(args.tree, OUT_DIR)

    os.makedirs(OUT_DIR, exist_ok=True)
    entries = []
    failed = []

    for track_id, name in TRACKS:
        src = os.path.join(BGM_DIR, name)
        if not os.path.isfile(src):
            print(f"::error::缺少源文件 {src}")
            failed.append(name)
            continue

        hdr = hca_header(src)
        print(f"[{name}] {hdr.get('sample_rate')}Hz {hdr.get('channels')}ch "
              f"ciphType={hdr['ciph']}" + ("（加密，靠 vgmstream 的密钥库解）"
                                           if hdr["ciph"] else ""))

        meta = probe_loop(args.vgmstream, src)
        wav = os.path.join(OUT_DIR, f"bgm{track_id}.wav")
        ogg = os.path.join(OUT_DIR, f"bgm{track_id}.ogg")

        r = run(f'"{args.vgmstream}" -i -o "{wav}" "{src}"')
        if r.returncode != 0:
            print(f"::error::vgmstream 解码失败 {name}: {r.stderr.strip()}")
            failed.append(name)
            continue

        r = run(f'"{args.ffmpeg}" -y -hide_banner -loglevel error '
                f'-i "{wav}" -c:a libvorbis -q:a {args.quality} "{ogg}"')
        if os.path.exists(wav):
            os.remove(wav)
        if r.returncode != 0 or not os.path.exists(ogg):
            print(f"::error::ffmpeg 编码失败 {name}: {r.stderr.strip()[-800:]}")
            failed.append(name)
            continue

        # -i 让 vgmstream 输出**不展开循环**的单遍音频，长度应当等于 total。
        # 若这里对不上，说明重建出来的东西和元数据不是一回事，循环点也就不可信。
        entries.append({
            "id": track_id,
            "file": f"magia/bgm{track_id}.ogg",
            "source": name,
            "sample_rate": meta["sample_rate"],
            "channels": meta["channels"],
            "loop_start": meta["loop_start"],
            "loop_end": meta["loop_end"],
            "total": meta["total"],
        })
        print(f"  ✔ {ogg}（{os.path.getsize(ogg)//1024} KB）"
              f" loop=[{meta['loop_start']}, {meta['loop_end']}) / {meta['total']}")

    if entries:
        with open(os.path.join(OUT_DIR, "bgm.json"), "w", encoding="utf-8") as f:
            json.dump({
                "_comment": "由 tools/convert-bgm.py 生成，勿手改。loop_* 单位为采样帧。",
                "tracks": entries,
            }, f, ensure_ascii=False, indent=2)
        print(f"✅ 写出 {OUT_DIR}/bgm.json（{len(entries)} 首）")

    if failed:
        print("::warning::以下 BGM 未能转换，浮层将没有对应曲目：\n  " + "\n  ".join(failed))
        # 音频不是关键路径，不阻断构建
    return 0


if __name__ == "__main__":
    sys.exit(main())
