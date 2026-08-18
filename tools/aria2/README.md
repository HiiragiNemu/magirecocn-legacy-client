# libaria2c 重建链（aria2 1.37.0 进程内库，双 ABI × 双 TLS 后端）

重建 `lib/arm64-v8a|armeabi-v7a/libaria2c_{ossl,gnutls}.so` 的全部输入都在这里。
触发方式：GitHub Actions → `🔧 构建 libaria2c.so（双 TLS 后端）` → Run workflow，
下载 artifact 后**真机验证**再入库（哈希必变，需同步更新 `baseline.json` 与
`THIRD-PARTY-NOTICES.md` 的 sha256 pin）。

## 文件

| 文件 | 作用 |
|---|---|
| `../build-aria2.sh` | 主构建脚本：9 依赖静态构建 + 最终链接 + 构建后自检 |
| `aria2_jni.cpp` | JNI 胶水（4 个导出符号，契约见 `CNAria2Lib.java`） |
| `compat_arm.c` | 仅 armv7：fseeko/ftello 兜底（32 位 bionic 为 API 24） |
| `libaria2c.map` | version script：只导出 4 个 JNI 入口 @@CNARIA2LIB_1.0 |
| `compare-with-baseline.sh` | 新产物 vs 入库基线的结构对拍（SONAME/NEEDED/导出/对齐） |

## 符号与功能等价性（补丁30 B轮对拍增强）

- 符号面：导出恰 4 个 JNI 入口由 version script + 自检 + 基线对拍三重钉死；
  UND 中弱符号（如基线实测的 `w getentropy`）合法可空回退，强 UND 对
  NDK API-21 stub 零缺口 + 高 API 黑名单；
- 功能面：aria2 FeatureConfig 的编译期功能摘要可从二进制 strings 提取——
  基线两组实测：启 BitTorrent/FF3Cookie/GZip/HTTPS/MessageDigest/Metalink/
  XML-RPC/SFTP，禁 Async DNS（佐证原构建无 c-ares/libuv，与本链一致）；
  compare-with-baseline.sh 对功能指纹与组件版本字面量做逐项对拍。

## 等价性说明（为什么不能说"产物与基线逐位相同"）

- 原构建嵌入路径 `/home/kimi/aria2build/...`，构建机不同则字符串不同；
- runner NDK 版本会漂移（原配方 r25c），note 段与 libc++ 静态链入代码会变；
- NOTICES 未记录每个 configure 开关，本链是"配方级等价"而非逐位复现。

因此验收靠三层闸门，而不是哈希相等：
1. 构建后自检（build-aria2.sh `verify_so`）：导出恰 4 符号、NEEDED 仅
   liblog/libdl/libm/libc、SONAME=文件名、LOAD 全 0x4000、marker 可探测、
   armv7 UND 对 NDK **API-21 stub** 零缺口 + 高 API 符号黑名单；
2. 基线对拍（compare-with-baseline.sh）：硬项不一致即 CI 失败；
3. 真机验证（RPC 启停、下载、崩溃换组 failover）后才允许替换入库。

## 历史坑（改动前先读）

- **符号抢占**：旧版 OpenSSL 符号 GLOBAL+JUMP_SLOT 暴露，被进程内其他
  libssl/libcrypto 抢占污染而崩 → `-fvisibility=hidden` + `-Bsymbolic` +
  version script，一条都不能少。
- **`Context(true)` 会 exit()**：standalone 模式下 aria2 参数错误直接杀进程，
  胶水必须用 `Context(false, ...)`（详见 aria2_jni.cpp 头注）。
- **armv7 fseeko/ftello**：NDK 官方文档（32 位 ABI 的 64 位文件偏移）——
  32 位 bionic 的 fseeko/ftello 是 API 24；别信 configure 探测结果，
  以 NDK API-21 stub + 黑名单双重把关。
- **libssh2 不支持 GnuTLS**：gnutls 组内嵌的 libcrypto(OpenSSL 1.1.1w) 仅供
  libssh2 的 SFTP 原语，TLS 路径 100% 走 GnuTLS（NOTICES 有记）。
- **SONAME 去重**：两组共存靠 SONAME 各异，必须是重链产物而非 cp 改名。
