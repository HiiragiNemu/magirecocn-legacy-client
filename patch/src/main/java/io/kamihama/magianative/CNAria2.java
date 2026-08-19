package io.kamihama.magianative;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * aria2 进程内备用引擎（libaria2c.so，JNI 加载）——兜底下载引擎。
 *
 * <p>主分块下载器（{@link CNChunkedDownload}）修不动时兜底：单 URL 同步下载到
 * 本地，走 aria2 的多连接 + 断点续传。默认<b>不启用</b>——只有 debug 开关
 * {@link CNDebugFlags#USE_ARIA2}、云端 {@code settings.force_aria2}，或构建期
 * 主引擎选了 aria2c 时才被 {@link CNDownloaderFix} 调用。
 *
 * <p>为什么是<b>进程内 JNI</b>（libaria2c.so，2026-08 由 Kimi 交叉编译）：
 *
 * <ul>
 *   <li><b>SELinux exec 闸</b>：app targetSdk≥29 后，Android 10+ 禁止执行应用
 *       私有目录下的二进制（error=13）。共享库走 {@code System.loadLibrary}，
 *       由 linker 放置到只读 nativeLibraryDir，完全绕开 exec（与 libarchive 同思路）；</li>
 *   <li><b>16KB 页设备</b>：包内可执行文件都是 4KB 对齐，在 Android 15+ 的 16KB
 *       页设备上 error=8 起不来。libaria2c.so 按 {@code -Wl,-z,max-page-size=16384}
 *       构建，4KB/16KB 通吃；</li>
 *   <li><b>符号卫生</b>：最早一版 JNI（{@code libaria2.so}）把 OpenSSL 符号以
 *       GLOBAL + JUMP_SLOT 动态重定位暴露，被进程内其他 libssl/libcrypto 抢占
 *       污染，TLS 路径 {@code br 0} 杀进程。本构建做彻底符号卫生，不再抢占。</li>
 * </ul>
 *
 * <p>期间曾改为解压内置全静态 aria2c 可执行文件跑独立子进程（崩溃隔离好、无
 * 符号抢占），但 exec 路径撞上上面两条，才又回到进程内 JNI。构建与依赖明细见
 * THIRD-PARTY-NOTICES.md。
 *
 * <p>线程：{@link #download} 是阻塞调用，必须在后台线程执行；进度回调
 * {@link Progress#onProgress} 在下载线程上触发，实现方自行切 UI 线程。
 */
public final class CNAria2 {
    /** 下载进度回调（下载线程上触发；total=0 表示未知）。 */
    public interface Progress {
        void onProgress(long done, long total);
    }

    /** 取消回调：下载轮询循环轮询，返回 true 即取消下载。 */
    public interface Cancel {
        boolean isCancelled();
    }

    /** 成功。 */
    public static final int OK           = 0;
    /** addUri 失败（URL 无效等）。 */
    public static final int ERR_ADD      = 1;
    /** 下载出错（aria2 报 error 事件）。 */
    public static final int ERR_DOWNLOAD = 2;
    /** 子进程异常退出（run 循环中断）。 */
    public static final int ERR_RUN      = 3;
    /** 被取消。 */
    public static final int CANCELLED    = -1;
    /** 初始化 / 二进制解压 / RPC 就绪失败。 */
    public static final int ERR_INIT     = -2;
    /** 其它（Java 异常）。 */
    public static final int ERR_OTHER    = -3;
    /** 已有下载在跑（串行化冲突）。 */
    public static final int ERR_BUSY     = -4;
    /** F-025：取消后旧 GID 未能确认进入终态，目标路径可能仍被写——inUse 保持占用。 */
    public static final int ERR_IN_USE   = -5;

    private static final String TAG = "CNAria2";

    // 🔴 aria2 是 GPLv2-or-later（附 OpenSSL 链接例外）。libaria2c.so 由我们
    // 交叉编译（aria2 1.37.0，OpenSSL 1.1.1w 静态链入），分发它带着「提供对应
    // 源码」的义务，构建来源与书面要约写在 THIRD-PARTY-NOTICES.md，
    // tools/check-third-party-notices.py 在 CI 里守着，别把那条删了。

    /** busy 门：同一时刻只允许一个下载（原 native 的 {@code g_inUse} 语义）。 */
    private static final AtomicBoolean inUse = new AtomicBoolean(false);

    private static final Object INIT_LOCK = new Object();
    private static volatile boolean initDone;
    private static volatile boolean available;

    // ── keep-alive 会话（2026-08-18）──
    // aria2 的进程内接口不支持同一进程重复执行 reqinfo->execute()：第二次
    // execute 会踩 OptionParser 全局单例的 use-after-free（memcmp 崩，
    // 复现 APK 的 A1→A2→A1 序列实锤）。所以**一个进程只允许一个 aria2 会话**：
    // 首次启动后跨下载复用，线程死了绝不重启（重启=第二次 execute=崩），
    // 直接让位主引擎。这是 aria2 的设计用法（RPC 守护进程）。
    private static volatile int sPort;
    private static volatile String sSecret;
    private static volatile boolean sServerUp;        // 本进程会话活着（线程在 + RPC 可及）
    private static volatile boolean sSessionStarted;  // 本进程是否已起过会话（起过就不能再起）

    private CNAria2() {}

    /**
     * 备用引擎是否可用：首次调用时按 failover 决策加载一个后端
     * （Aria2EngineFailover 选 openssl/gnutls → {@code CNAria2Lib.load}；
     * 加载期失败当场换组；两组都失败或连续死亡达上限则放弃）。结果进程内缓存。
     */
    public static boolean isAvailable() {
        if (initDone) return available;
        synchronized (INIT_LOCK) {
            if (initDone) return available;
            available = ensureEngineLoaded();
            initDone = true;
            return available;
        }
    }

    /** 首次加载引擎：failover 决策 + 加载期当场换组。失败返回 false → 回退主引擎。 */
    private static boolean ensureEngineLoaded() {
        if (CNAria2Lib.loadedBackend() != null) return true;
        if (Aria2EngineFailover.giveUp()) {
            CNLog.w(TAG, "aria2 双后端连续死亡达上限，本进程不再尝试（回退主引擎）");
            return false;
        }
        CNAria2Lib.Backend b = Aria2EngineFailover.pickBackend();
        try {
            CNAria2Lib.load(b);
            return true;
        } catch (UnsatisfiedLinkError e) {
            CNLog.w(TAG, b + " 加载失败: " + e);
            if (Aria2EngineFailover.giveUp()) return false;
            CNAria2Lib.Backend b2 = Aria2EngineFailover.shouldFallbackOnLoadError(b);
            try {
                CNAria2Lib.load(b2);
                return true;
            } catch (UnsatisfiedLinkError e2) {
                CNLog.e(TAG, b2 + " 也加载失败，aria2 不可用（回退主引擎）: " + e2);
                return false;
            }
        }
    }

    /**
     * 同步下载单个 URL 到 {@code outDir/outName}。阻塞直到完成/出错/取消，
     * 必须在后台线程调用。同一时刻只允许一个下载。
     *
     * @param url      直链（下载源）
     * @param outDir   输出目录（须已存在的绝对路径）
     * @param outName  输出文件名
     * @param ua       User-Agent（可为 null）
     * @param referer  Referer（可为 null）
     * @param headers  附加请求头（"Name: value" 数组，可为 null）
     * @param maxConns 每服务器连接数（1-16；越界自动忽略）
     * @param proxy    代理 URL（可为 null 表示直连）
     * @param progress 进度回调（可为 null）
     * @param cancel   取消回调（可为 null；isCancelled() 返回 true 即取消）
     * @return {@link #OK} 或错误码
     */
    public static int download(String url, String outDir, String outName,
            String ua, String referer, String[] headers, int maxConns,
            String proxy, Progress progress, Cancel cancel) {
        if (!inUse.compareAndSet(false, true)) {
            CNLog.w(TAG, "已有下载在跑（busy），拒绝并发");
            return ERR_BUSY;
        }
        // F-025：方法级持有下载结果，finally 据此决定是否释放 inUse
        // （ERR_IN_USE 时不释放）。必须在 try 外声明。
        int result = ERR_RUN;
        try {
            if (url == null || url.isEmpty() || outName == null) return ERR_ADD;
            if (!isAvailable()) {
                CNLog.w(TAG, "aria2c 不可用，备用引擎放弃");
                return ERR_INIT;
            }
            if (outDir == null) outDir = CNPaths.filesDir();
            int maxC = (maxConns > 0 && maxConns <= 16) ? maxConns : 8;
            File ariaDir = new File(CNPaths.filesDir(), "aria2");
            // 目录只放 cacerts.pem（下方 ensureSystemCaStore 用）；日志不落文件，
            // 由 native 源码层 AndroidLogFile sink 把 aria2 控制台输出直进 logcat
            // （tools/aria2/patches/0001-console-android-log-sink.patch）。旧版本
            // 这里挂着 --log=<filesDir>/aria2/aria2.log，目录没建 → 启动即在
            // Logger.cc 打不开文件直接退出（玩家日志实锤的「无法下载」根因）。
            ariaDir.mkdirs();

            // ── 会话：启动一次，跨下载复用（keep-alive，2026-08-18）──
            // aria2 进程内接口不支持同一进程重复 execute（第二次 execute 踩
            // OptionParser 全局单例 UAF 崩，复现 APK A1→A2→A1 实锤）。所以
            // 会话活着就复用；死了**绝不重启**（重启=第二次 execute=崩），
            // 直接让位主引擎。本方法因此不再在出口关停会话——线程一直挂着做
            // RPC 守护，随进程退出一起消失，死生状由 Aria2EngineFailover 处理。
            if (!sServerUp || !CNAria2Lib.isRunning()) {
                if (sSessionStarted) {
                    CNLog.w(TAG, "aria2 会话已在本进程结束，不再重启（二次 execute 会崩），回退主引擎");
                    return ERR_INIT;
                }
                int port = 16000 + (int) (Math.random() * 7000); // 16000-22999
                // RPC 只听 127.0.0.1，但**同机的其它应用照样够得着**，挡住它们的
                // 只有这个 token。Math.random() 不是密码学随机源，而且只有 31 位——
                // 猜中就等于拿到一个能以本应用身份往私有目录里写文件的下载器
                // （dir/out 都是 RPC 参数），随后正是安装器要去解压的地方。
                String secret = "cn" + Long.toHexString(
                        new java.security.SecureRandom().nextLong() & 0x7fffffffffffffffL);

                List<String> args = new ArrayList<>();
                args.add("--enable-rpc");
                args.add("--rpc-listen-port=" + port);
                args.add("--rpc-listen-all=false");
                args.add("--rpc-secret=" + secret);
                // ⚠ 别加 --async-dns：那是 ENABLE_ASYNC_DNS 条件编译选项，本构建
                // 无 c-ares 时未注册，传了 option_processing 直接失败（基线胶水
                // Context(true) 因此 exit() 杀进程 = 2026-08-18「打开就闪退」根因）。
                // DNS 由 bionic getaddrinfo 系统解析，本来也不需要它。
                args.add("--file-allocation=none");   // 1.3GB 文件 prealloc 会坑闪存
                args.add("--allow-overwrite=true");
                args.add("--continue=true");          // 断点续传：目标旁有 .aria2 控制文件就续，没有就从头
                args.add("--auto-file-renaming=false");
                args.add("--no-conf");
                args.add("--daemon=false");
                // 原则（2026-08-18）：日志不分开。aria2 不写独立日志文件——
                // 控制台输出经 native AndroidLogFile sink 直进 logcat，随游戏
                // 主日志一起进玩家分享包。别改回 --log=<文件>。
                // info 级 + 每 1 秒一条下载摘要：玩家实时看到进度。F-024 把
                // keep-alive 长驻的进程级 fd 重定向换成了源码层 sink，info 级 +
                // summary 的日志量也远低于旧 pipe 转发，不会刷爆 logcat。
                args.add("--console-log-level=info");
                args.add("--summary-interval=1");

                // 进程内 JNI 启动 aria2 线程（libaria2c.so），替代 exec 子进程：
                // 绕开 SELinux exec 闸 + 16KB 页对齐，且符号卫生避免 OpenSSL 抢占崩溃。
                // 立「生死状」：若 native 崩溃，armed 标记留在盘上，下次启动
                // Aria2EngineFailover 读到就换后端（openssl ↔ gnutls）。
                Aria2EngineFailover.arm();
                int startRc = CNAria2Lib.start(args.toArray(new String[0]));
                sSessionStarted = true;   // 起过一次就不能再起（二次 execute 会崩）
                if (startRc != 0) {
                    CNLog.w(TAG, "libaria2c 启动失败 rc=" + startRc);
                    return ERR_INIT;
                }
                if (!waitRpc(port, secret, cancel)) {
                    CNLog.w(TAG, "aria2c RPC 未就绪（进程可能已退出）");
                    // F-048：waitRpc 失败时 native 线程可能仍存活，而 sPort/sSecret
                    // 尚未发布——线程变成 Java 侧再也无法寻址/回收的孤儿会话。
                    // 必须先确认关停再返回；确认不了则标记不可回收、后续 fail-closed。
                    if (!shutdownAria2(port, secret)) {
                        CNLog.e(TAG, "aria2 会话未能确认停止，标记为不可回收（sSessionStarted=true 防重启）");
                    }
                    return ERR_INIT;
                }
                sPort = port;
                sSecret = secret;
                sServerUp = true;
                CNLog.i(TAG, "aria2 RPC 会话就绪 port=" + port + "（keep-alive，跨下载复用）");
            }
            // 复用会话句柄
            int port = sPort;
            String secret = sSecret;

            // addUri 下发（dir/out/header/连接数放 per-uri 选项；CLI 只留 daemon）
            JSONObject opt = new JSONObject();
            opt.put("dir", outDir);
            opt.put("out", outName);
            opt.put("max-connection-per-server", maxC);
            opt.put("split", maxC);
            JSONArray hdrs = new JSONArray();
            if (ua != null) hdrs.put("User-Agent: " + ua);
            if (referer != null) hdrs.put("Referer: " + referer);
            if (headers != null) {
                for (String h : headers) {
                    if (h != null && !h.isEmpty()) hdrs.put(h);
                }
            }
            opt.put("header", hdrs);
            if (proxy != null && !proxy.isEmpty()) opt.put("all-proxy", proxy);
            // 🔴 证书校验**没有**关掉的余地，拿不到 CA 桶就整条路不走。
            //
            // 原先这里在拿不到 CA 桶时下发 check-certificate=false，理由写的是
            // 「文件下载后走结构 + 分块 MD5 校验，完整性有独立防线」。那条理由
            // 在 2026-08-13 被另一次改动**抽掉了**：按维护者口径，aria2 模式下
            // 额外的内容校验一律旁路（不套 manifest 块指纹，也不比对热更两包的
            // version json size/MD5），只剩「ZIP 结构合法且至少有一个条目」。
            //
            // 两次改动各自都说得通，合起来是个洞：传输层不认证 + 内容层不认证。
            // 中间人可以整包替换，而 cn_js_update.zip 装的是 WebView 里跑的前端
            // 脚本——那就不是「资源坏了」，是在玩家设备上执行攻击者的代码。
            //
            // 所以拿不到 CA 桶时返回 ERR_INIT：调用方会回退主引擎，那条路用
            // OkHttp 做完整 TLS 验证，功能一点不少。宁可不用备用引擎，
            // 也不能用一条不认证的。
            File cacerts = new File(ariaDir, "cacerts.pem");
            if (!cacerts.isFile() || cacerts.length() <= 0) {
                ensureCacerts(ariaDir);
            }
            if (!cacerts.isFile() || cacerts.length() <= 0) {
                CNLog.w(TAG, "拼不出 CA 证书桶，aria2 放弃本次下载（回退主引擎做完整 TLS 校验）");
                return ERR_INIT;
            }
            opt.put("ca-certificate", cacerts.getAbsolutePath());
            // 每次下载前立「生死状」：keep-alive 会话跨下载常驻，原生崩溃的
            // 死生标记按**下载窗口**记——本次下载没把进程炸死，finally 的
            // disarm 就清掉；炸死了标记留在盘上，下次启动换后端。
            Aria2EngineFailover.arm();
            JSONArray uris = new JSONArray();
            uris.put(url);

            JSONObject addRes = rpc(port, secret, "aria2.addUri", uris, opt);
            if (addRes == null || addRes.optJSONObject("error") != null) {
                CNLog.w(TAG, "aria2.addUri 失败: " + (addRes == null ? "无响应" : addRes.toString()));
                return ERR_ADD;
            }
            String gid = addRes.optString("result", "");
            if (gid.isEmpty()) {
                CNLog.w(TAG, "aria2.addUri 未返回 gid");
                return ERR_ADD;
            }

            File target = new File(outDir, outName);
            // F-023：轮询必须有界——RPC 半失效时 while(true) 会永远占住 inUse 与
            // 安装器工作线程。连续 RPC 失败（null/无 result）超过阈值按失败退出；
            // 已知总长但进度停滞超过阈值也按失败退出。
            int rpcFailures = 0;
            final int maxRpcFailures = 10;
            long lastProgressBytes = -1L;
            long lastProgressAt = System.currentTimeMillis();
            final long stallLimitMs = 60_000L;
            while (true) {
                if (cancel != null && cancel.isCancelled()) {
                    CNLog.w(TAG, "aria2 下载被取消: " + outName);
                    // F-025：取消不能只发一次 best-effort remove 就释放 inUse——
                    // 旧 GID 可能仍持 fd 写同一产物，调用方立刻清理+重下会并发写。
                    // 先等任务进入终态；确认不了则 ERR_IN_USE，inUse 保持占用，
                    // 调用方按「路径被占用」fail-closed（不清产物、不重下同路径）。
                    if (quiesceGid(port, secret, gid)) {
                        result = CANCELLED;
                    } else {
                        CNLog.e(TAG, "aria2 取消后旧 GID 未进入终态，拒绝释放 inUse（fail-closed）: "
                                + outName);
                        result = ERR_IN_USE;
                    }
                    break;
                }
                if (!CNAria2Lib.isRunning()) {
                    CNLog.w(TAG, "aria2c 进程内线程意外退出: " + outName);
                    sServerUp = false;   // 会话死了；sSessionStarted 保持 true → 本进程不再重启
                    result = ERR_RUN;
                    break;
                }
                JSONObject res = rpc(port, secret, "aria2.tellStatus", gid);
                if (res == null) {
                    if (++rpcFailures > maxRpcFailures) {
                        CNLog.w(TAG, "aria2.tellStatus 连续 " + maxRpcFailures + " 次无响应，按失败处理");
                        result = ERR_DOWNLOAD;
                        break;
                    }
                    sleep(500);
                    continue;
                }
                rpcFailures = 0;
                JSONObject err = res.optJSONObject("error");
                if (err != null) {
                    CNLog.w(TAG, "aria2.tellStatus 报错: " + err.optString("message"));
                    result = ERR_DOWNLOAD;
                    break;
                }
                JSONObject st = res.optJSONObject("result");
                if (st == null) {
                    if (++rpcFailures > maxRpcFailures) {
                        CNLog.w(TAG, "aria2.tellStatus 连续 " + maxRpcFailures + " 次无 result，按失败处理");
                        result = ERR_DOWNLOAD;
                        break;
                    }
                    sleep(500);
                    continue;
                }
                rpcFailures = 0;
                long done = optLong(st, "completedLength");
                long total = optLong(st, "totalLength");
                if (progress != null) progress.onProgress(done, total > 0 ? total : 0);
                // F-023 停滞检测：已知总长但 completedLength 长时间不增长 → 卡死
                long now = System.currentTimeMillis();
                if (done == lastProgressBytes) {
                    if (total > 0 && now - lastProgressAt > stallLimitMs) {
                        CNLog.w(TAG, "aria2 进度停滞 " + (stallLimitMs / 1000) + "s（done=" + done
                                + " total=" + total + "），按失败处理");
                        result = ERR_DOWNLOAD;
                        break;
                    }
                } else {
                    lastProgressBytes = done;
                    lastProgressAt = now;
                }
                String status = st.optString("status", "");
                if ("complete".equals(status)) { result = OK; break; }
                if ("error".equals(status))    { result = ERR_DOWNLOAD; break; }
                if ("removed".equals(status))  { result = ERR_DOWNLOAD; break; }
                sleep(500);
            }

            if (result == OK) {
                boolean fileOk = target.isFile() && target.length() > 0;
                if (fileOk) {
                    CNLog.i(TAG, "aria2 下载完成: " + outName + " len=" + target.length());
                } else {
                    CNLog.w(TAG, "aria2 完成但产物缺失/空: " + outName);
                    result = ERR_DOWNLOAD;
                }
            }
            // 出口：**不关停会话**（keep-alive，2026-08-18）。会话跨下载常驻，
            // 随进程退出一起消失；死生状由 finally 按本次下载窗口 disarm。
            return result;
        } catch (Throwable t) {
            CNLog.w(TAG, "aria2 进程内异常: " + t);
            return ERR_OTHER;
        } finally {
            // F-007：只有确认会话仍存活（线程在 + RPC 曾可达）才清 armed 标记。
            // keep-alive 下 native 线程常驻；启动后 RPC 未就绪、任务早退、线程刚
            // 死亡或会话空闲期崩溃时，无条件 disarm 会把「未确认安全」记成干净
            // 窗口。线程已死 → 保留标记，下次启动 pickBackend() 读到就换后端。
            // 若 native 崩溃（SIGSEGV/SIGABRT），finally 根本来不及跑，标记自然
            // 留在盘上——与这里的条件是同一套语义。
            if (CNAria2Lib.isRunning() && sServerUp) {
                Aria2EngineFailover.disarm();
            } else {
                CNLog.w(TAG, "aria2 会话未确认健康（isRunning=" + CNAria2Lib.isRunning()
                        + " serverUp=" + sServerUp + "），保留 armed 标记");
            }
            // 会话死亡（线程意外退出）时置 sServerUp=false 已在下载循环里做过；
            // 这里兜底再查一次（覆盖取消/早退等出口），保证本进程不会再去重启
            // 一个已死的 aria2（重启 = 第二次 execute = 崩）。
            if (sServerUp && !CNAria2Lib.isRunning()) {
                sServerUp = false;
                CNLog.w(TAG, "aria2 会话线程已退出，本次进程标记不可再用");
            }
            // F-025：ERR_IN_USE 时 inUse 保持占用（旧 GID 未确认停写），调用方
            // 收到该码 fail-closed——不清目标/控制文件、不在同路径重下。
            if (result != ERR_IN_USE) {
                inUse.set(false);
            } else {
                CNLog.w(TAG, "aria2 未确认停止，inUse 保持占用（调用方按路径被占用处理）");
            }
        }
    }

    /**
     * 关闭进程内 aria2 会话。keep-alive（2026-08-18）之后正常下载流程**不再调用**
     * 本方法——会话跨下载常驻、随进程退出消失。本方法保留给未来显式关停场景
     * （app 退出前收尾 / 维护者需要主动换组），调用后进程内会话即告结束，
     * 本进程后续 download() 会因 {@code sSessionStarted} 不再重启 aria2。
     * 优先 RPC shutdown（优雅），失败则 waitStopped 兜底。
     * {@code port/secret} 传 0/null 时跳过 RPC（例如启动失败、从未拿到端口）。
     */
    /**
     * F-025：取消后等待 aria2 任务进入终态（removed/error/complete）或线程退出。
     * 先 aria2.remove，有界轮询 tellStatus；不收敛 escalate 到 aria2.forceRemove
     * 再轮询。返回 true 才允许释放 inUse——旧 GID 不再写目标路径。
     */
    private static boolean quiesceGid(int port, String secret, String gid) {
        final int maxPolls = 12;                 // 12 × 250ms ≈ 3s 每轮
        final String[] cmds = { "aria2.remove", "aria2.forceRemove" };
        for (String cmd : cmds) {
            try { rpc(port, secret, cmd, gid); } catch (Throwable ignore) {}
            for (int i = 0; i < maxPolls; i++) {
                if (!CNAria2Lib.isRunning()) return true;   // 线程退出 = 必然停写
                JSONObject res = null;
                try { res = rpc(port, secret, "aria2.tellStatus", gid); }
                catch (Throwable ignore) {}
                if (res != null) {
                    if (res.optJSONObject("error") != null) return true;
                    JSONObject st = res.optJSONObject("result");
                    String status = st != null ? st.optString("status", "") : "";
                    if ("removed".equals(status) || "error".equals(status)
                            || "complete".equals(status)) return true;
                }
                try { Thread.sleep(250); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * 关闭进程内 aria2 会话并确认线程停止。
     *
     * @return true = 线程已确认退出；false = 超时/异常，线程可能仍存活
     */
    private static boolean shutdownAria2(int port, String secret) {
        try {
            if (port > 0 && secret != null) {
                rpc(port, secret, "aria2.shutdown");   // best-effort
            }
        } catch (Throwable ignore) {}
        try {
            int rc = CNAria2Lib.waitStopped(3000L);
            return rc != -2 && !CNAria2Lib.isRunning();
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================================================================
    // 子进程生命周期与 RPC
    // ==================================================================

    /** 等待 RPC 就绪（getVersion 轮询，200ms×40≈8s）。进程提前死或取消→false。 */
    private static boolean waitRpc(int port, String secret, Cancel cancel) {
        for (int i = 0; i < 40; i++) {
            if (cancel != null && cancel.isCancelled()) return false;
            if (!CNAria2Lib.isRunning()) return false;
            if (rpc(port, secret, "aria2.getVersion") != null) return true;
            sleep(200);
        }
        return false;
    }

    /** JSON-RPC 调用 127.0.0.1:<port>/jsonrpc。返回完整响应或 null（网络/解析失败）。 */
    private static JSONObject rpc(int port, String secret, String method, Object... params) {
        try {
            JSONObject req = new JSONObject();
            req.put("jsonrpc", "2.0");
            req.put("id", 1);
            req.put("method", method);
            JSONArray arr = new JSONArray();
            arr.put("token:" + secret);
            for (Object p : params) arr.put(p);
            req.put("params", arr);
            byte[] body = req.toString().getBytes("UTF-8");

            // 本地控制通道绝不服从系统 ProxySelector。否则全局抓包/HTTP 代理可
            // 截获 rpc-secret、并令 127.0.0.1 RPC 不可达（F-049）。
            HttpURLConnection c = (HttpURLConnection)
                    new URL("http://127.0.0.1:" + port + "/jsonrpc")
                            .openConnection(Proxy.NO_PROXY);
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod("POST");
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            c.setDoOutput(true);
            c.setDoInput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try {
                OutputStream os = c.getOutputStream();
                try { os.write(body); } finally { close(os); }
                int code = c.getResponseCode();
                if (code != 200) return null;
                InputStream in = c.getInputStream();
                try {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                    return new JSONObject(new String(bos.toByteArray(), "UTF-8"));
                } finally {
                    close(in);
                }
            } finally {
                try { c.disconnect(); } catch (Throwable ignore) {}
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** aria2 RPC 长度字段返回字符串，兼容 Number。失败返回 0。 */
    private static long optLong(JSONObject o, String key) {
        try {
            Object v = o.opt(key);
            if (v == null || v == JSONObject.NULL) return 0L;
            if (v instanceof Number) return ((Number) v).longValue();
            return Long.parseLong(v.toString().trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    // ==================================================================
    // CA 证书
    // ==================================================================

    /** 缺了才拼；拼出来的空文件当没拼出来。 */
    private static boolean ensureCacerts(File dir) {
        File pem = new File(dir, "cacerts.pem");
        if (pem.isFile() && pem.length() > 0) return true;
        return extractCacerts(dir);
    }

    /**
     * 拼装系统+用户 CA 证书到 files/aria2/cacerts.pem。失败（目录不可读/为空）
     * 返回 false，此时 {@link #download} <b>整条路不走</b>，回退主引擎——
     * 绝不改用 {@code --check-certificate=false}，理由见那边的红字。
     */
    private static boolean extractCacerts(File dir) {
        File pem = new File(dir, "cacerts.pem");
        File tmp = new File(dir, "cacerts.pem.tmp");
        deleteQuietly(tmp);
        try {
            // F-050：绝不直写最终路径——进程被杀会留下非零截断残片，而
            // ensureCacerts() 只认「isFile && length>0」会把残片永久当完整包。
            // 写唯一临时文件 → flush+fsync → 校验可解析 → 同目录 rename 原子发布。
            FileOutputStream fos = new FileOutputStream(tmp);
            boolean any = false;
            try {
                // F-052：只拼系统信任锚。network_security_config.xml 已明确只信任
                // system CA（用户 CA 是 device-admin 级中间人入口）；aria2 路径若
                // 再拼入用户 CA 就绕过了这套收紧，并可影响 WebView 本地优先执行的
                // 前端脚本。与系统策略保持一致：用户 CA 绝不进下载信任域。
                any = appendCerts(fos, new File("/system/etc/security/cacerts"));
                any = appendCerts(fos, new File("/apex/com.android.conscrypt/cacerts")) || any;
                fos.flush();
                fos.getFD().sync();
            } finally {
                close(fos);
            }
            if (!any || !isValidCaBundle(tmp)) {
                deleteQuietly(tmp);
                return false;
            }
            if (!tmp.renameTo(pem)) {          // Android 内部存储同目录 rename(2) 原子替换
                deleteQuietly(tmp);
                return false;
            }
            CNArchiveInstallTx.syncDir(dir);
            return true;
        } catch (Throwable t) {
            deleteQuietly(tmp);
            return false;
        }
    }

    /** 校验 PEM 至少含一个可解析的 X.509 证书——只凭「长度>0」会收下截断残片。 */
    private static boolean isValidCaBundle(File pem) {
        if (pem == null || !pem.isFile() || pem.length() <= 0) return false;
        InputStream in = null;
        try {
            in = new java.io.FileInputStream(pem);
            java.util.Collection<? extends java.security.cert.Certificate> certs =
                    java.security.cert.CertificateFactory.getInstance("X.509")
                            .generateCertificates(in);
            return certs != null && !certs.isEmpty();
        } catch (Throwable t) {
            return false;
        } finally {
            close(in);
        }
    }

    /** 把一个目录下可读的 PEM 证书文件顺序拼进 fos。 */
    private static boolean appendCerts(FileOutputStream fos, File dir) {
        File[] files = dir.listFiles();
        if (files == null) return false;
        boolean any = false;
        for (File f : files) {
            try {
                if (f.isFile() && f.canRead()) {
                    InputStream in = new java.io.FileInputStream(f);
                    try {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                    } finally {
                        close(in);
                    }
                    any = true;
                }
            } catch (Throwable ignore) {}
        }
        return any;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    private static void close(InputStream in) {
        try { if (in != null) in.close(); } catch (Throwable ignore) {}
    }

    private static void close(OutputStream out) {
        try { if (out != null) out.close(); } catch (Throwable ignore) {}
    }

    private static void deleteQuietly(File f) {
        try { if (f != null && f.exists() && !f.delete()) {
            CNLog.w(TAG, "删不掉临时文件 " + f);
        } } catch (Throwable ignore) {}
    }
}
