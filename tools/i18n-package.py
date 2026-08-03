#!/usr/bin/env python3
"""把汉化过的前端文件打进 cn_js_update.zip（热更包）。

## 为什么走热更而不是打进 APK

维护者定的：译文随热更下发，不进 APK。理由是**可管理**——

  · 改一句话不用重出 APK、不用玩家重装；
  · 出了问题把版本号回退即可，不必召回一个 100MB 的包；
  · 与既有的 js/libs/*.json 汉化走同一条路，只有一处版本号要维护。

客户端这边**不需要任何改动**：现成的热更流程（CNHotUpdateCheck →
CNHotUpdate.download → 解压到 <files>/）本来就把包解到
`/data/data/<pkg>/files/`，而前端正是从那里读 `magica/js/**`、
`magica/template/**`（真机日志里 `MagiaHook-URL … → MagiaHook-Path
/data/data/…/files/magica/…` 就是这条路）。

## 包的结构

沿用现有 cn_js_update.zip 的布局，路径以 `magica/` 打头：

    magica/js/libs/*.json          既有的数据表汉化（原样保留）
    magica/js/_common/*.js         本次新增：页面/逻辑代码
    magica/template/**/*.html      本次新增：模板

## 增量还是全量

**全量**。热更是「下载→解压覆盖」，包里没有的文件不会被删也不会被还原，
所以每次都必须把此前已下发过的内容一并带上——否则老版本包里汉化过、新版本
包里漏掉的文件，会永远停在旧状态且无人察觉。本脚本的做法是：以旧包为底，
把新文件叠上去。

用法：
    python3 tools/i18n-package.py <汉化后的前端根目录> <改动清单> \\
            --base <旧的 cn_js_update.zip> -o <新包>
"""

import argparse
import os
import sys
import zipfile


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('root', help='汉化后的前端根目录（含 js/ 与 template/）')
    ap.add_argument('changed', help='i18n-apply.py 产出的改动清单')
    ap.add_argument('--base', help='旧的 cn_js_update.zip，作为底包')
    ap.add_argument('-o', '--out', default='cn_js_update.zip')
    args = ap.parse_args()

    files = [l.strip() for l in open(args.changed, encoding='utf-8') if l.strip()]
    if not files:
        print('改动清单是空的', file=sys.stderr)
        return 1

    seen = set()
    n_base = 0
    with zipfile.ZipFile(args.out, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        # 先铺底包。新文件同名时以新的为准，所以底包里的同名项要跳过。
        new_names = {'magica/' + f.replace(os.sep, '/') for f in files}
        if args.base and os.path.isfile(args.base):
            with zipfile.ZipFile(args.base) as b:
                for it in b.infolist():
                    if it.is_dir() or it.filename in new_names:
                        continue
                    z.writestr(it, b.read(it.filename))
                    seen.add(it.filename)
                    n_base += 1

        n_new = 0
        for rel in files:
            src = os.path.join(args.root, rel)
            if not os.path.isfile(src):
                print('  ⚠ 清单里有但磁盘上没有：%s' % rel, file=sys.stderr)
                continue
            name = 'magica/' + rel.replace(os.sep, '/')
            if name in seen:
                continue
            z.write(src, name)
            seen.add(name)
            n_new += 1

    size = os.path.getsize(args.out)
    print('底包沿用 %d 项，新增/覆盖 %d 项，共 %d 项' % (n_base, n_new, len(seen)))
    print('→ %s（%.1f MB）' % (args.out, size / 1024.0 / 1024.0))
    print('\n发布前还要做两件事（都在服务端，不在本仓库）：')
    print('  1. 把包传到 assets.example.test/cn_js_update.zip')
    print('  2. version_js.json 的 version 加 1，否则客户端认为无更新、不会下载')
    return 0


if __name__ == '__main__':
    sys.exit(main())
