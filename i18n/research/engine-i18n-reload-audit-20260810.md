# `engine_i18n.tsv` 热重载文件身份审计（2026-08-10）

## 结论

旧实现只比较路径的秒级 `st_mtime`，并且在读完、关闭文件后才
`stat(ENGINE_I18N_PATH)`。这与客户端真实的热更提交方式不相容：

1. `CNHotUpdateTx.commit()` 先把 live 移入 backup，再把 stage 移到 live；
2. `move()` 在同一文件系统优先使用 `renameTo()`，即原子换名；
3. native 可能已经 `fopen()` 旧 inode，Java 随即换入新 inode；
4. native 读到旧内容，却在关闭后取得新路径的 mtime，并把新 mtime 记到旧表；
5. 之后路径 mtime 与记录相同，新表将一直不再加载。即使没有这个竞态，同一秒内
   两次替换也可能拥有相同的秒级 mtime。

因此这不是理论边界，而是当前热更事务能够真实形成的永久漏更路径。

## 代码证据

| 证据 | 位置 | 含义 |
|---|---|---|
| live → backup | `patch/src/main/java/io/kamihama/magianative/CNHotUpdateTx.java:313-317` | 旧路径被移走 |
| stage → live | 同文件 `:318-320` | 新 inode 换入原路径 |
| 同文件系统优先 rename | 同文件 `:643-657` | 原子换名是正常路径，不是异常退路 |
| 旧 native 实现 | `377ca40e03a846696167039b6eb24c6454924e3a:magia-native/src/MagiaLegacy.cpp` | `fclose()` 后 `stat(path)`，只保存 `st_mtime` |

审计时 `CNHotUpdateTx.java` SHA-256：
`32c70e2c9b464a94267dfbc86df4ddc853481b137587ab4170271accbe15c8c2`。

旧 native 文件 SHA-256：
`380f9a9cff1b8efe097b4d3ef151a84d374e8ecc3bc6cee0662c8b2593c22e00`。

## 修正合同

`MagiaLegacy.cpp` 现在用 `EngineI18nFileStamp` 绑定**真正被解析的 fd**：

- `device` + `inode`：识别同秒原子换名；
- `size`：识别同 inode 的尺寸变化；
- `mtimeSeconds` + `mtimeNanoseconds`：识别同秒原地更新；
- 打开后、解析前 `fstat(fd)`，解析完、关闭前再 `fstat(fd)`；
- 读取错误或两次 fd 指纹不一致时，不发布半份/混合表，继续保留上一个完整快照；
- 翻译表快照与对应 fd 指纹在同一把 `g_engineI18nMutex` 下发布；
- 每三秒的路径检查比较完整指纹，而不是只比较 mtime 秒值；
- 文件暂时缺失时继续保留上一个完整快照。

若原子换名发生在 fd 打开之后，当前一轮最多仍读到旧 inode；但它发布的也是旧 inode
指纹。下一轮路径检查必然看到新 inode 并重载，不会再把旧内容错绑到新路径时间戳。

## 自动验证

新增：

- `tools/check-engine-i18n-reload.py`
- `tools/test-check-engine-i18n-reload.py`
- `.github/workflows/build-apk.yml` 中的“校验 engine i18n 热重载文件身份合同”步骤

守卫验证生产源码包含完整字段、fd 前后检查、读取错误处理、同锁发布与全指纹比较；
mutation 自测会拒绝以下回退：

1. 恢复秒级 `g_engineI18nMtime`；
2. 漏掉 inode 或纳秒字段的采集/比较；
3. 漏掉任一次 `fstat(fd)`；
4. 忽略 `ferror()`；
5. 删除读取期间稳定性闸门；
6. 在 loader 内重新 `stat(path)`；
7. 不持锁读取或拆开发布 table/stamp；
8. reload 判断退回 mtime-only。

行为模型覆盖：相同指纹不重载；初始无指纹；设备、inode、size、mtime 秒或纳秒任一变化
均重载；两个 invalid 指纹视为相同。

本修正没有新增或生成译文，机器翻译条目数为 **0**。

## 验证边界

本轮没有重复进行 ARM64 / ARMv7 NDK 全量编译：该仓库 Action 已持续构建两个 ABI，
且本次改动没有触碰手写 ABI 布局。提交前执行新增静态守卫、自测、既有 native i18n
守卫、入口/ABI 守卫、工作流 YAML 解析和差异检查；最终 APK 编译仍由仓库现有手动
Action 复验。
