#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""打包 overlay —— 那些**无法从原包重建**、要放到外部发布渠道去的文件。

## 为什么要有这个脚本

`baseline/` 里 `from: overlay` 的那些 op（人手重绘的汉化图集）不在本仓库里，
构建时从外部发布渠道的 Release 取。既然要取，就要钉 sha256；既然要钉 sha256，
**这个 zip 就必须是可复现的**——否则「重打一次包，钉死项就失效」，谁也说不清
是文件变了还是打包方式变了。

Python 的 zipfile 默认会写入当前时间戳，同样的内容打两次得到不同的 sha256。
这里把三处不确定性都固定下来：

    ▸ 条目顺序 —— 按路径排序，不依赖 os.walk 的返回顺序
    ▸ 时间戳   —— 一律 1980-01-01（zip 格式能表示的最小值）
    ▸ 外部属性 —— 固定 0o644，不带宿主机的 umask 与所有者

于是「同一批文件 → 同一个 sha256」，钉死项才立得住。

用法：
    python3 tools/make-overlay.py            # 打到 work/apk-overlay-atlas.zip
    python3 tools/make-overlay.py --out X    # 指定输出
    python3 tools/make-overlay.py --check    # 只算 sha256，与 baseline.json 对照
"""

import argparse
import hashlib
import json
import os
import sys
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONF = os.path.join(REPO, "baseline", "baseline.json")
FIXED_DATE = (1980, 1, 1, 0, 0, 0)


def overlay_paths(conf):
    """清单里该进 overlay 包的文件：所有 from == "overlay" 的 op。

    过渡期里它们可能还标着 from == "repo"（图集尚未迁走），那时按 group 取，
    这样这个脚本在迁移前后都能用，且始终以 baseline.json 为准、不自己另立名单。
    """
    ops = [op for op in conf["ops"] if op.get("from") == "overlay"]
    if not ops:
        ops = [op for op in conf["ops"]
               if op.get("group") == "汉化图集" and op["kind"] in ("add", "replace")]
    return sorted(op["path"] for op in ops)


def build(paths, out):
    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for rel in paths:
            src = os.path.join(REPO, rel)
            if not os.path.isfile(src):
                raise SystemExit("要打包的文件不在仓库里：%s\n"
                                 "  （已经迁走了？那就从外部发布渠道取，别再重打这个包）" % rel)
            info = zipfile.ZipInfo(rel, date_time=FIXED_DATE)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            with open(src, "rb") as f:
                z.writestr(info, f.read())
    return out


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for blk in iter(lambda: f.read(1 << 20), b""):
            h.update(blk)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(REPO, "work", "apk-overlay-atlas.zip"))
    ap.add_argument("--check", action="store_true",
                    help="打完与 baseline.json 的 overlay.sha256 对照，不符则非零退出")
    args = ap.parse_args()

    with open(CONF, "r", encoding="utf-8") as f:
        conf = json.load(f)
    paths = overlay_paths(conf)
    if not paths:
        raise SystemExit("清单里没有要进 overlay 的文件")

    build(paths, args.out)
    digest = sha256(args.out)
    size = os.path.getsize(args.out)
    print("%s\n  %d 个文件，%.1f MB\n  sha256 %s" % (args.out, len(paths), size / 1e6, digest))

    want = (conf.get("overlay") or {}).get("sha256")
    if args.check:
        if not want:
            print("baseline.json 里还没有 overlay.sha256，无法对照", file=sys.stderr)
            return 1
        if want != digest:
            print("与 baseline.json 的 overlay.sha256 不符：\n  期望 %s\n  实得 %s"
                  % (want, digest), file=sys.stderr)
            return 1
        print("与 baseline.json 相符")
    elif want and want != digest:
        print("⚠ 与 baseline.json 的 overlay.sha256 不符（%s）——"
              "内容变了就要同步更新钉死项并重新上传资产" % want)
    return 0


if __name__ == "__main__":
    sys.exit(main())
