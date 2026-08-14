# 进阶排查指南

一份带日志的反馈，顶十份截图。这一页给会用 adb、或者愿意折腾一下的人。

## 一、游戏自己的日志（首选）

客户端补丁层的日志（`CNLog`）写在应用私有目录：

```
/data/data/io.kamihama.totentanz/log/
```

下载失败、热更回滚、换线过程、崩溃前最后的动作，都在这里。
**报问题时把这个目录整个打包附上**，基本能回答九成的「当时发生了什么」。

导出方式（客户端已开 `android:debuggable`，`run-as` 直接用，**不需要 root**）：

```bash
adb shell "run-as io.kamihama.totentanz tar czf /sdcard/cnlog.tgz log"
adb pull /sdcard/cnlog.tgz

# root 设备也可以直接打整个私有目录
adb shell "su -c 'tar czf /sdcard/cnlog.tgz -C /data/data/io.kamihama.totentanz log'"
adb pull /sdcard/cnlog.tgz
```

::: tip 下载阶段的浮层自带日志
资源下载 / 热更新浮层**左上角有个 LOG 胶囊**，点进去就是当前日志，
截图发来也行——适合不想开 adb 的情况。
:::

## 二、logcat（崩溃/闪退必备）

闪退、黑屏、卡死类问题，logcat 里有堆栈：

```bash
# 崩溃发生后立刻抓（别重启游戏，日志会被冲掉）
adb logcat -d > logcat.txt

# 只要补丁层与引擎相关的（噪音小很多）
adb logcat -d -s MagiaCN_Legacy:V AndroidRuntime:E > logcat.txt
```

抓完把 `logcat.txt` 附到 Issue。

## 三、调试开关目录（协助二分定位）

`/data/data/io.kamihama.totentanz/debug/` 下**建一个同名空文件 = 打开开关，
删掉 = 关闭，重启游戏生效**（客户端已开 debuggable，`adb shell run-as
io.kamihama.totentanz ...` 直接可操作，无需 root）。开关一律只做一件事：
把客户端退回更接近原包的行为，用来回答「是哪一步把游戏搞挂的」。

常用的几个：

| 开关 | 效果 | 用来定位 |
|---|---|---|
| `logI18nMiss` | 把「流经 native 文本 hook 但没翻到」的日文打进 logcat | 收集漏翻（[方法见仓库 i18n/README](https://github.com/MagirecoCN-Revival-Project/legacy-client/blob/main/i18n/README.md)） |
| `logI18nMissAll` | 同上但不筛字符集（噪音大） | 确认英文/纯汉字串走不走 native 层 |
| `skipOverlay` | 不出现下载浮层 | 浮层本身导致的卡死 |
| `failDownload` | 注入下载失败 | 协助复现换线/重试问题 |

完整开关表与边界规则见仓库 README 的[「调试开关目录」](https://github.com/MagirecoCN-Revival-Project/legacy-client#%E8%B0%83%E8%AF%95%E5%BC%80%E5%85%B3%E7%9B%AE%E5%BD%95%E6%8E%92%E6%9F%A5%E7%94%A8%E7%8E%A9%E5%AE%B6%E7%A2%B0%E4%B8%8D%E5%88%B0)一节。

::: warning 用完记得删
开关是为排查设计的退化行为，开着某些开关游戏**本来就不会正常**。
排查完把 `debug/` 里自己建的文件删掉，免得下次把「自己开的开关」当成新 bug 报上来。
:::

## 四、报漏翻的快速方法

```bash
adb shell "run-as io.kamihama.totentanz touch debug/logI18nMiss"
# 重启游戏，把漏翻的画面走一遍，然后：
adb logcat -d -s MagiaCN_Legacy | sed -n 's/.*\[i18n-miss\]\[[^]]*\] //p' | sort -u > miss.tsv
```

`miss.tsv` 就是现成的「日文原文清单」，直接附到
[误译/漏翻 Issue](https://github.com/MagirecoCN-Revival-Project/legacy-client/issues/new?template=translation.yml)。

## 五、信息 checklist

进阶反馈请带齐：

- [ ] 构建号（`1.0.xx`）与设备/ROM/Android 版本
- [ ] `log/` 目录打包
- [ ] logcat（崩溃类必带）
- [ ] 复现步骤 + 必现/偶发
- [ ] 截图或录屏（有的话）
