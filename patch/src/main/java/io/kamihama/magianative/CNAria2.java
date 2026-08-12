package io.kamihama.magianative;

import android.content.Context;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * aria2c 子进程备用引擎——替代原先的 libaria2 JNI 包装。
 *
 * <p>主分块下载器（{@link CNChunkedDownload}）修不动时兜底：单 URL 同步下载到
 * 本地，走 aria2 的多连接 + 断点续传。默认<b>不启用</b>——只有 debug 开关
 * {@link CNDebugFlags#USE_ARIA2} 或云端 {@code settings.force_aria2} 打开时才被
 * {@link CNDownloaderFix} 调用。
 *
 * <p>为什么改成子进程（2026-08-12）：旧的 JNI 版（{@code libaria2.so}，OpenSSL
 * 静态编进共享库但符号走 GLOBAL + JUMP_SLOT 动态重定位）在真机上被进程内其他
 * libssl/libcrypto 抢占/污染，TLS 路径 {@code br} 跳到 0，直接杀掉游戏进程（连崩
 * 两次）。现改为解压内置的<b>全静态</b> aria2c 可执行文件（v1.37.0，ELF 静态、
 * 0 条 NEEDED，来源 Cross-Compiled-Binaries-Android，sha256 见下），用
 * ProcessBuilder 起独立子进程 + JSON-RPC 控制：
 *
 * <ul>
 *   <li><b>崩溃隔离</b>：aria2 再崩只死子进程，Java 捕获进程退出→回退主引擎，
 *       App 永不闪退；</li>
 *   <li><b>无符号抢占</b>：独立进程命名空间 + 全静态二进制，JUMP_SLOT 这类
 *       共享库专利根本不存在。</li>
 * </ul>
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

    private static final String TAG = "CNAria2";

    // 内置静态二进制（Cross-Compiled-Binaries-Android，aria2 v1.37.0，全静态）：
    //   aria2c-arm64 sha256 6705bac56e0752b26b22d4aa98cf5caa0f4672904e6cbf0ac2f516cc5f05797d
    //   aria2c-arm   sha256 b06494c59df4c3536ad68dfc1ce5b33d3e638cd1eae845ae5452709b35ed1270bb
    // 资产文件、对应 ABI 前缀、预期字节数三者同步维护；换二进制时三处一起改。
    private static final String[][] ASSETS = {
            {"arm64-v8a", "aria2/aria2c-arm64", "10146592"},
            {"armeabi-v7a", "aria2/aria2c-arm", "8319744"},
    };

    /** busy 门：同一时刻只允许一个下载（原 native 的 {@code g_inUse} 语义）。 */
    private static final AtomicBoolean inUse = new AtomicBoolean(false);

    private static final Object INIT_LOCK = new Object();
    private static volatile boolean initDone;
    private static volatile boolean available;

    private CNAria2() {}

    /** 备用引擎是否可用（静态二进制已解压、可执行、--version 能跑）。 */
    public static boolean isAvailable() {
        if (initDone) return available;
        synchronized (INIT_LOCK) {
            if (initDone) return available;
            boolean ok = false;
            try {
                ok = ensureBinary() && checkExecutable();
            } catch (Throwable t) {
                CNLog.w(TAG, "aria2c 初始化异常: " + t);
                ok = false;
            }
            available = ok;
            initDone = true;
            return available;
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
        Process proc = null;
        try {
            if (url == null || url.isEmpty() || outName == null) return ERR_ADD;
            if (!isAvailable()) {
                CNLog.w(TAG, "aria2c 不可用，备用引擎放弃");
                return ERR_INIT;
            }
            if (outDir == null) outDir = CNPaths.filesDir();
            int maxC = (maxConns > 0 && maxConns <= 16) ? maxConns : 8;

            int port = 16000 + (int) (Math.random() * 7000); // 16000-22999
            String secret = "cn" + Long.toHexString((long) (Math.random() * 0x7fffffffL));
            File ariaDir = new File(CNPaths.filesDir(), "aria2");
            File bin = new File(ariaDir, "aria2c");

            List<String> args = new ArrayList<>();
            args.add(bin.getAbsolutePath());
            args.add("--enable-rpc");
            args.add("--rpc-listen-port=" + port);
            args.add("--rpc-listen-all=false");
            args.add("--rpc-secret=" + secret);
            args.add("--async-dns");
            // Android 8+ 没有 /etc/resolv.conf，静态二进制必须显式给 DNS。
            args.add("--async-dns-server=223.5.5.5,119.29.29.29");
            args.add("--file-allocation=none");   // 1.3GB 文件 prealloc 会坑闪存
            args.add("--allow-overwrite=true");
            args.add("--auto-file-renaming=false");
            args.add("--no-conf");
            args.add("--daemon=false");
            args.add("--log-level=warn");
            args.add("--log=" + new File(ariaDir, "aria2.log").getAbsolutePath());

            proc = new ProcessBuilder(args).redirectErrorStream(true).start();

            if (!waitRpc(port, secret, proc, cancel)) {
                CNLog.w(TAG, "aria2c RPC 未就绪（进程可能已退出）");
                return ERR_INIT;
            }

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
            File cacerts = new File(ariaDir, "cacerts.pem");
            if (cacerts.isFile()) {
                opt.put("ca-certificate", cacerts.getAbsolutePath());
            } else {
                // 拿不到系统 CA 桶时放行（文件下载后走结构+分块 sha256 校验，
                // 完整性有独立防线，传输层不校验证书可接受）。
                opt.put("check-certificate", false);
            }
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
            int result = ERR_RUN;
            while (true) {
                if (cancel != null && cancel.isCancelled()) {
                    rpc(port, secret, "aria2.remove", gid); // best-effort
                    CNLog.w(TAG, "aria2 下载被取消: " + outName);
                    return CANCELLED;
                }
                if (!proc.isAlive()) {
                    CNLog.w(TAG, "aria2c 子进程意外退出: " + outName);
                    result = ERR_RUN;
                    break;
                }
                JSONObject res = rpc(port, secret, "aria2.tellStatus", gid);
                if (res == null) {
                    sleep(500);
                    continue;
                }
                JSONObject err = res.optJSONObject("error");
                if (err != null) {
                    CNLog.w(TAG, "aria2.tellStatus 报错: " + err.optString("message"));
                    result = ERR_DOWNLOAD;
                    break;
                }
                JSONObject st = res.optJSONObject("result");
                if (st == null) {
                    sleep(500);
                    continue;
                }
                long done = optLong(st, "completedLength");
                long total = optLong(st, "totalLength");
                if (progress != null) progress.onProgress(done, total > 0 ? total : 0);
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
            return result;
        } catch (Throwable t) {
            CNLog.w(TAG, "aria2 子进程异常: " + t);
            return ERR_OTHER;
        } finally {
            if (proc != null) {
                try { proc.destroy(); } catch (Throwable ignore) {}
            }
            inUse.set(false);
        }
    }

    // ==================================================================
    // 子进程生命周期与 RPC
    // ==================================================================

    /** 等待 RPC 就绪（getVersion 轮询，200ms×40≈8s）。进程提前死或取消→false。 */
    private static boolean waitRpc(int port, String secret, Process proc, Cancel cancel) {
        for (int i = 0; i < 40; i++) {
            if (cancel != null && cancel.isCancelled()) return false;
            if (!proc.isAlive()) return false;
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

            HttpURLConnection c = (HttpURLConnection)
                    new URL("http://127.0.0.1:" + port + "/jsonrpc").openConnection();
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
    // 二进制解压与 CA 证书
    // ==================================================================

    /** 按 ABI 选资产解压到 files/aria2/aria2c（尺寸不符才重抽）。 */
    private static boolean ensureBinary() {
        String abi = primaryAbi();
        String asset = null;
        long expect = -1;
        for (String[] row : ASSETS) {
            if (abi.startsWith(row[0])) {
                asset = row[1];
                try { expect = Long.parseLong(row[2]); } catch (Throwable ignore) {}
                break;
            }
        }
        if (asset == null) {
            CNLog.w(TAG, "不支持的 ABI: " + abi + "（备用引擎不可用）");
            return false;
        }
        try {
            File dir = new File(CNPaths.filesDir(), "aria2");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                CNLog.w(TAG, "创建 aria2 目录失败: " + dir);
                return false;
            }
            File bin = new File(dir, "aria2c");
            if (bin.isFile() && bin.length() == expect) {
                bin.setExecutable(true, false);
                return true;
            }
            Context ctx = appContext();
            if (ctx == null) {
                CNLog.w(TAG, "拿不到 Application Context，无法解压 aria2c");
                return false;
            }
            InputStream in = ctx.getAssets().open(asset);
            try {
                File tmp = new File(dir, "aria2c.tmp");
                FileOutputStream fos = new FileOutputStream(tmp);
                try {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                } finally {
                    close(fos);
                }
                if (tmp.length() != expect) {
                    CNLog.w(TAG, "aria2c 资产尺寸不符 expected=" + expect + " actual=" + tmp.length());
                    deleteQuietly(tmp);
                    return false;
                }
                if (!tmp.setExecutable(true, false)) {
                    deleteQuietly(tmp);
                    return false;
                }
                if (bin.exists() && !bin.delete()) {
                    deleteQuietly(tmp);
                    return false;
                }
                if (!tmp.renameTo(bin)) {
                    deleteQuietly(tmp);
                    return false;
                }
            } finally {
                close(in);
            }
            extractCacerts(dir);
            return true;
        } catch (Throwable t) {
            CNLog.w(TAG, "aria2c 解压失败: " + t);
            return false;
        }
    }

    /** 跑一次 {@code aria2c --version} 验证可执行（有界 5s）。 */
    private static boolean checkExecutable() {
        File bin = new File(new File(CNPaths.filesDir(), "aria2"), "aria2c");
        try {
            Process p = new ProcessBuilder(bin.getAbsolutePath(), "--version")
                    .redirectErrorStream(true).start();
            boolean exited = p.waitFor(5, TimeUnit.SECONDS);
            if (!exited) {
                try { p.destroy(); } catch (Throwable ignore) {}
                return false;
            }
            return p.exitValue() == 0;
        } catch (Throwable t) {
            CNLog.w(TAG, "aria2c --version 执行失败: " + t);
            return false;
        }
    }

    /**
     * 拼装系统+用户 CA 证书到 files/aria2/cacerts.pem。失败（目录不可读/为空）
     * 返回 false，下载时改走 {@code --check-certificate=false}。
     */
    private static boolean extractCacerts(File dir) {
        File pem = new File(dir, "cacerts.pem");
        try {
            FileOutputStream fos = new FileOutputStream(pem);
            boolean any = false;
            try {
                any = appendCerts(fos, new File("/system/etc/security/cacerts"));
                any = appendCerts(fos, new File("/apex/com.android.conscrypt/cacerts")) || any;
                any = appendCerts(fos, new File("/data/misc/user/0/cacerts-added")) || any;
            } finally {
                close(fos);
            }
            if (any && pem.length() > 0) return true;
            deleteQuietly(pem);
            return false;
        } catch (Throwable t) {
            deleteQuietly(pem);
            return false;
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

    /** 取主 ABI。优先 SUPPORTED_ABIS[0]（API 21+），兜底 CPU_ABI。 */
    private static String primaryAbi() {
        try {
            if (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0) {
                return Build.SUPPORTED_ABIS[0];
            }
        } catch (Throwable ignore) {}
        try {
            return Build.CPU_ABI;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 取 Application Context。与原包同一手法（反射 ActivityThread）。 */
    private static Context appContext() {
        try {
            Class<?> cls = Class.forName("android.app.ActivityThread");
            Object thread = cls.getMethod("currentActivityThread").invoke(null);
            return (Context) cls.getMethod("getApplication").invoke(thread);
        } catch (Throwable t) {
            return null;
        }
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
