# 标题页加载与日服音频

本次对象是游戏内带 TOUCH SCREEN 的标题页，不是中文下载覆盖界面的音乐。

## 加载状态

日服 3.2.2 和国服原包已有原生 loading 图案。`pushSceneDownload` 依次压入
Loading(33)、Download(27)。原下载状态机会清除 33；完成回调
`SceneCommand::onDownloaded` 只清除 27。

资源已经齐备时，自有 `downloadSceneLayerOnEnterNew` 跳过原下载状态机，因而
需要在完成回调前补上 `SceneLayerManager::popSceneLayer(33)`。
此操作仅限该快速完成分支，沿用 GL 线程，不在标题页出现时一概隐藏 loading。
取不到清理接口或单例时，回退原 `onEnter`，不执行不完整的快速回调。

`tools/test-download-loading-exit.py` 实际编译该函数并验证完成顺序、资源未就绪、
缺少 info/回调、缺少接口和单例等 7 种情况。两个架构另做 NDK 编译。

## 音频来源

用户指定日服 3.2.2 的 `assets/resource/sound_native/bgm`、`se` 共 8 个文件。
与 1.0.189 比较，2 首系统 HCA、ACB、ACF 已逐字节一致；另外 4 首 HCA 通过
基线清单新增，均不转码。逐文件路径和摘要见 `original-sound-3.2.2.json`。

日服第二部标题动画 `toppage_bg_02.ExportJson` 的第 40 帧事件仍名为
`bgm_bgm00_system01`，原生回调去掉 `bgm_` 后调用
`SoundManager::bgmPlayFade("bgm00_system01", true)`。当前引擎沿用该调用，
当前外置标题动画与指定日服原件逐字节一致。名称中的 `system01` 不是“第一部”
的判断依据。新增四首 HCA 本身也不改变这一帧的选曲。

保持原版事件，不把选曲强行改成凭文件名猜测的曲目；下载覆盖界面播放器保持不变。
以上为源件、调用与构建验证；更新后的实际标题页听感由设备播放验收。
