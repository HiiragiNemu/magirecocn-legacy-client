# 更新日志

客户端版本以 CI 构建号命名（`1.0.<构建号>`），每个版本对应的
APK 与改动说明都在 GitHub Releases：

👉 **[Releases · MagirecoCN-Revival-Project/legacy-client](https://github.com/MagirecoCN-Revival-Project/legacy-client/releases)**

## 两类更新，两种速度

| 更新类型 | 怎么拿到 | 要重装 APK 吗 |
|---|---|---|
| **热更新**（翻译、前端文本、引擎文本表） | 启动游戏自动检查，或重启触发 | 不用 |
| **客户端更新**（补丁层 bug 修复、新功能） | 下载新 APK 覆盖安装 | 要 |

绝大多数翻译修正走热更新——报错后通常当天就能通过热更下发。

## 最近的关键变化

- **1.0.80+** 序章流程修复（序章末尾自动重启、CONNECT 教学战斗卡死）
- **1.0.82+** 序章分段调试开关（测试用）
- **1.0.83+** armeabi-v7a（32 位设备）native string 布局兼容
- **1.0.84+** i18n 前缀匹配与热重载指纹修复

完整提交历史见
[commits · main](https://github.com/MagirecoCN-Revival-Project/legacy-client/commits/main)。
