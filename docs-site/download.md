# 下载与安装

## 下载

公测包发布在 GitHub Releases：

- **最新版（推荐）**：[Releases · latest](https://github.com/MagirecoCN-Revival-Project/legacy-client/releases/latest)
- 全部历史版本：[Releases 列表](https://github.com/MagirecoCN-Revival-Project/legacy-client/releases)

APK 文件名形如 `legacy-client-1.0.<构建号>.apk`，构建号单调递增，
数字越大越新。

::: tip 国内下载慢？
GitHub Release 附件可以走我们自己的加速镜像：
把下载链接里的 `https://github.com/MagirecoCN-Revival-Project/legacy-client/releases/download/`
换成 `https://（已下线线路）/g/m/releases/download/` 即可；
`gh-proxy.org` 这类公共镜像（链接前拼 `https://gh-proxy.org/`）也行。
:::

## 安装

1. 手机设置里允许「安装未知来源应用」（不同 ROM 叫法不同）。
2. 直接覆盖安装即可，**不用卸载旧版**——账号数据在游戏服务端，不在本地。
3. 首次启动会下载资源与热更新，请保持网络畅通；下载浮层会显示每个文件的进度。

::: warning 覆盖安装失败（签名冲突）
说明设备上那份和公测包签名不同。先备份好**引继码与引继密码**
（游戏内：设置 → 数据转移），再卸载旧版装公测包。
:::

## 版本号说明

客户端内部版本号是 `1.0.<CI 构建号>`，与 APK 自身的 versionName 无关
（那是上游包的身份，不能动）。反馈问题时请带上这个构建号，
它能把问题定位到具体某一次 CI 构建。

## 设备要求

- Android 设备，arm64 为主力支持架构；armeabi-v7a（32 位）有兼容层支持，
  遇到问题欢迎反馈。
- 无需 root；正式包普通玩家即可安装。

## 下一步

装好了？公测最需要的是你的反馈——[三个层次，选一个适合你的](/feedback/)。
