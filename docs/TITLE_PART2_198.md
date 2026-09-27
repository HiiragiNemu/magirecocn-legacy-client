# 1.0.198：第二部 Touch 标题音乐

用户确认下载页 BGM2（うつろい / TrySail）为所需第二部标题曲。仅把
`assets/package/top/toppage_bg_02.ExportJson` 的帧事件从
`bgm_bgm00_system01` 改成 `bgm_bgm00_system02`。音频 01/02 文件本身不改，
其他场景调用 01 的行为保留；Connecting 与字体不动。

下载仍按文件身份匹配：同版本、大小和哈希的资源允许 EdgeOne / ESA 优先；
最新热更不混入旧镜像。国内线路失败后优先 Cloudflare，再公开 GitHub。
CN BASE 继续利用国内现有相同资源，无需把所有镜像版本号同步到最新。

验证：`python tools/test-title-part2-bgm.py [built.apk]` 检查单一帧事件差异；
`python tools/test-private-source-round.py` 检查实际选择器的同身份优先和故障回退。
这些检查不替代用户在设备上的音乐试听。
