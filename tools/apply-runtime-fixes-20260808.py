#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def read(rel):
    return (ROOT / rel).read_text(encoding="utf-8")


def write(rel, text):
    (ROOT / rel).write_text(text, encoding="utf-8", newline="\n")


def replace_once(text, old, new, label):
    n = text.count(old)
    if n != 1:
        raise RuntimeError(f"{label}: expected exactly 1 match, got {n}")
    return text.replace(old, new, 1)


def sub_once(text, pattern, repl, label, flags=0):
    out, n = re.subn(pattern, repl, text, count=1, flags=flags)
    if n != 1:
        raise RuntimeError(f"{label}: expected exactly 1 regex match, got {n}")
    return out


# ---------------------------------------------------------------------------
# 1) CNCNDownloadUI: completed-marker UI + explicit native gate release
# ---------------------------------------------------------------------------
rel = "patch/src/main/java/io/kamihama/magianative/CNCNDownloadUI.java"
s = read(rel)

s = replace_once(
    s,
    "    public static void hide() {\n",
    """    /**\n     * 浮层撤掉以后，显式在 Cocos GL 线程通知 native 释放被闸住的主页跳转/BGM。\n     *\n     * 旧实现只靠后续 Label::setString / LoadingSceneLayerInfo::setText 等 hook\n     * 顺带调用 maybeReleaseDeferredTop()。如果浮层恰好在最后一次文本更新之后关闭，\n     * 就再也没有回调来补推主页，表现为热更已经结束但游戏永久黑屏。\n     */\n    private static void releaseEngineGate() {\n        try {\n            org.cocos2dx.lib.Cocos2dxHelper.runOnGLThread(new Runnable() {\n                @Override public void run() {\n                    try {\n                        CNDownloaderFix.nativeReleaseDeferredTop();\n                    } catch (Throwable t) {\n                        CNLog.w(\"界面\", \"GL 线程释放引擎闸门失败: \" + t);\n                    }\n                }\n            });\n        } catch (Throwable t) {\n            // Cocos 尚未初始化时仍保留 native 侧原有的 setString 兜底释放路径。\n            CNLog.w(\"界面\", \"无法调度 GL 线程释放引擎闸门，保留兜底路径: \" + t);\n        }\n    }\n\n    public static void hide() {\n""",
    "insert releaseEngineGate",
)

s = replace_once(
    s,
    """        if (!isShowing || (handler = uiHandler) == null) {\n            return;\n        }\n""",
    """        if (!isShowing || (handler = uiHandler) == null) {\n            // 即使浮层没真正建成/handler 已丢，也必须释放 native 闸门。\n            releaseEngineGate();\n            return;\n        }\n""",
    "hide fail-open gate release",
)

s = replace_once(
    s,
    """                CNLog.i(\"界面\", \"下载浮层关闭（日志继续记录）\");\n                // 先摘掉监听再拆视图，避免拆到一半又被日志回调碰上\n""",
    """                CNLog.i(\"界面\", \"下载浮层关闭（日志继续记录）\");\n                // stopOverlayFlag() 已在 hide() 里同步删掉标记；现在从 UI 线程\n                // 明确投递到 GL 线程释放 deferred top，不再碰运气等下一次文本 hook。\n                releaseEngineGate();\n                // 先摘掉监听再拆视图，避免拆到一半又被日志回调碰上\n""",
    "HideRunnable explicit gate release",
)

marker = "    public static void setDownloadSpeed(int i, float f) {\n"
insert = """    /**\n     * 把一个已经安装、但本轮确认需要热更新的槽位切回“等待本轮更新”。\n     * 其它有有效 marker 的基础资源保持 100% / 已完成，不再在热更新页伪装成 0%。\n     */\n    public static void markFilePending(int i) {\n        if (i < 0 || i >= FILE_COUNT) return;\n        if (fileStatus != null) fileStatus[i] = 0;\n        if (fileProgress != null) fileProgress[i] = 0;\n        if (fileSpeed != null) fileSpeed[i] = 0.0f;\n        if (fileDownloaded != null) fileDownloaded[i] = 0.0f;\n        Handler handler = uiHandler;\n        if (handler != null) handler.post(new UpdateRunnable());\n    }\n\n"""
s = replace_once(s, marker, insert + marker, "markFilePending")
write(rel, s)


# ---------------------------------------------------------------------------
# 2) CNDownloaderFix: expose native release, seed installed UI, don't block on config
# ---------------------------------------------------------------------------
rel = "patch/src/main/java/io/kamihama/magianative/CNDownloaderFix.java"
s = read(rel)

s = replace_once(
    s,
    """    private CNDownloaderFix() {\n    }\n\n""",
    """    private CNDownloaderFix() {\n    }\n\n    /** 由 CNCNDownloadUI 在 Cocos GL 线程调用，释放被下载浮层闸住的主页/BGM。 */\n    public static native void nativeReleaseDeferredTop();\n\n    /** 独立重启跳板进程只负责把主进程重新拉起，绝不能再启动安装/热更线程。 */\n    private static boolean isRestartProcess() {\n        try {\n            if (android.os.Build.VERSION.SDK_INT >= 28) {\n                String n = android.app.Application.getProcessName();\n                if (n != null && n.endsWith(\":cnrestart\")) return true;\n            }\n        } catch (Throwable ignore) {}\n        try {\n            FileInputStream in = new FileInputStream(\"/proc/self/cmdline\");\n            ByteArrayOutputStream bos = new ByteArrayOutputStream();\n            int b;\n            while ((b = in.read()) > 0 && bos.size() < 256) bos.write(b);\n            in.close();\n            String n = new String(bos.toByteArray(), StandardCharsets.UTF_8);\n            return n.endsWith(\":cnrestart\");\n        } catch (Throwable ignore) {\n            return false;\n        }\n    }\n\n""",
    "native release declaration and restart-process guard",
)

s = replace_once(
    s,
    """    public static void triggerInstaller() {\n        // 这个方法同样由外部（Application.onCreate）直接调用，出了事不能把\n""",
    """    public static void triggerInstaller() {\n        if (isRestartProcess()) {\n            try { android.util.Log.i(TAG, \"restart trampoline process: skip installer/hot-update\"); }\n            catch (Throwable ignore) {}\n            return;\n        }\n        // 这个方法同样由外部（Application.onCreate）直接调用，出了事不能把\n""",
    "skip installer in restart trampoline",
)

s = replace_once(
    s,
    """                        if (finalFlag.isFile()) {\n                            CNLog.i(TAG, \"triggerInstaller: flag 已存在，无需安装，转入版本与热更检查\");\n""",
    """                        if (finalFlag.isFile()) {\n                            CNLog.i(TAG, \"triggerInstaller: flag 已存在，无需安装，转入版本与热更检查\");\n                            // 热更新页仍展示 15 个槽位，因此先按 marker 还原真实安装状态：\n                            // 已装好的 13 个基础包必须是 100% / 完成，而不是 0% / 等待中。\n                            syncInstalledUiState();\n""",
    "seed installed UI before version/hotupdate",
)

old = """        // 线路列表：先走系统网络，失败再直连；两次都失败就用内置默认线路\n        CNCNDownloadUI.updateSimple(\"准备中\", \"正在获取下载线路…\", 0);\n        CNMirrors.refresh(false);\n        if (!CNMirrors.isLoaded()) {\n            CNMirrors.refresh(true);\n        }\n        // 这两次是背靠背发的，开机头一两秒网络还没就绪时会一起失败（0117 真机\n        // 就是这样，六次全挤在同一秒）。失败了交给带退避的后台重试——这条路比\n        // 热更那条更要紧：首次安装要拉 15GB，线路表拿不到就整个装在内置默认线路上。\n        // 中途拿到新表也安全：mirrors 是 volatile，pick() 每次尝试都重新读。\n        CNMirrors.ensureLoadedAsync();\n"""
new = """        // 内置 fallback 从进程启动起就可用。远程 config 只是优化线路顺序/参数，\n        // 不能成为首次安装的同步前置条件；服务器故障时直接用内置线路开跑，\n        // 后台拿到新表后 pick() 会自然切到新配置。\n        CNCNDownloadUI.updateSimple(\"准备中\", \"正在准备下载线路…\", 0);\n        CNMirrors.ensureLoadedAsync();\n"""
s = replace_once(s, old, new, "nonblocking installer mirror init")

s = replace_once(
    s,
    """    private static void resetUiForRun() {\n""",
    """    /** 按 15 个完成 marker 把 UI 恢复成真实已安装状态，供正常启动/热更新复用。 */\n    static void syncInstalledUiState() {\n        resetUiForRun();\n    }\n\n    private static void resetUiForRun() {\n""",
    "expose installed UI sync",
)
write(rel, s)


# ---------------------------------------------------------------------------
# 3) CNHotUpdateCheck: fail-open deadline, immediate defaults, system proxy
# ---------------------------------------------------------------------------
rel = "patch/src/main/java/io/kamihama/magianative/CNHotUpdateCheck.java"
s = read(rel)
s = s.replace("import java.net.Proxy;\n", "")

s = replace_once(
    s,
    """    private static final int VER_CONNECT_TIMEOUT_MS = 5000;\n    private static final int VER_READ_TIMEOUT_MS    = 12000;\n""",
    """    private static final int VER_CONNECT_TIMEOUT_MS = 5000;\n    private static final int VER_READ_TIMEOUT_MS    = 12000;\n    /** 两份版本查询合计最多占用启动关键路径 25 秒，超过即 fail-open 进入游戏。 */\n    private static final long VERSION_QUERY_DEADLINE_MS = 25000L;\n""",
    "hotupdate version deadline constant",
)

s = replace_once(
    s,
    """                    } catch (Throwable th) {\n                        CNLog.e(TAG, \"热更检查异常终止: \" + th, th);\n                        try { CNCNDownloadUI.hide(); } catch (Throwable ignore) {}\n                    }\n""",
    """                    } catch (Throwable th) {\n                        running = false;\n                        CNLog.e(TAG, \"热更检查异常终止（fail-open 进入游戏）: \" + th, th);\n                        try { CNCNDownloadUI.hide(); } catch (Throwable ignore) {}\n                        String msg = pendingRestartMsg;\n                        pendingRestartMsg = null;\n                        if (msg != null) {\n                            try { CNDownloaderFix.noticeAndRestart(msg); }\n                            catch (Throwable ignore) {}\n                        }\n                    }\n""",
    "hotupdate exceptional fail-open",
)

s = replace_once(
    s,
    """        Activity act = awaitUsableActivity();\n""",
    """        // final flag 已存在时，15 个基础资源的 marker 才是 UI 的事实源。\n        // 先恢复真实完成状态；稍后只有确认“需要热更”的 0/1 号槽位才切回等待。\n        CNDownloaderFix.syncInstalledUiState();\n\n        Activity act = awaitUsableActivity();\n""",
    "sync UI before hotupdate overlay",
)

old = """            final VerMeta[] metas = new VerMeta[2];\n            try {\n                metas[0] = fScenario.get();\n                metas[1] = fJs.get();\n            } catch (Throwable t) {\n                CNLog.w(TAG, \"并行版本查询异常: \" + t);\n            }\n            pool.shutdown();\n"""
new = """            final VerMeta[] metas = new VerMeta[2];\n            final long deadlineNs = System.nanoTime()\n                    + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(VERSION_QUERY_DEADLINE_MS);\n            try {\n                long left = deadlineNs - System.nanoTime();\n                if (left <= 0L) throw new java.util.concurrent.TimeoutException(\"版本查询总超时\");\n                metas[0] = fScenario.get(left, java.util.concurrent.TimeUnit.NANOSECONDS);\n                left = deadlineNs - System.nanoTime();\n                if (left <= 0L) throw new java.util.concurrent.TimeoutException(\"版本查询总超时\");\n                metas[1] = fJs.get(left, java.util.concurrent.TimeUnit.NANOSECONDS);\n            } catch (java.util.concurrent.TimeoutException t) {\n                anyFailure = true;\n                CNLog.w(TAG, \"版本查询超过 \" + VERSION_QUERY_DEADLINE_MS\n                        + \"ms，本次跳过未完成项并放行进入游戏\");\n                fScenario.cancel(true);\n                fJs.cancel(true);\n            } catch (Throwable t) {\n                anyFailure = true;\n                CNLog.w(TAG, \"并行版本查询异常: \" + t);\n            } finally {\n                pool.shutdownNow();\n            }\n"""
s = replace_once(s, old, new, "bounded parallel version query")

s = replace_once(
    s,
    """                VerMeta meta = metas[i];\n                if (meta == null) continue;  // 查询失败已在 fetchMetaSafe 里提示\n""",
    """                VerMeta meta = metas[i];\n                if (meta == null) {\n                    anyFailure = true;\n                    continue;  // 查询失败已在 fetchMetaSafe / 总 deadline 里提示\n                }\n""",
    "meta-null counts as failure",
)

s = replace_once(
    s,
    """                File tmp = new File(FILES_DIR, pkg.tmpName);\n""",
    """                // 只有真正需要更新的槽位回到等待/0%；其余有效 marker 的\n                // 13 个基础资源继续显示 100% / 已完成。\n                CNCNDownloadUI.markFilePending(pkg.slot);\n                File tmp = new File(FILES_DIR, pkg.tmpName);\n""",
    "pending only changed hotupdate slots",
)

old = """        // 线路表可能还没拉过（热更检查不一定跟在安装器后面跑）\n        if (!CNMirrors.isLoaded()) {\n            CNMirrors.refresh(false);\n            if (!CNMirrors.isLoaded()) CNMirrors.refresh(true);\n        }\n        Exception last = null;\n"""
new = """        // 内置 fallback 一直存在；远程 config 只在后台刷新，绝不在\n        // 版本查询关键路径同步等 api.example.test。\n        if (!CNMirrors.isLoaded()) CNMirrors.ensureLoadedAsync();\n        Exception last = null;\n"""
s = replace_once(s, old, new, "nonblocking hotupdate mirror lookup")

s = replace_once(
    s,
    """        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);\n""",
    """        // 尊重 Android 系统代理。未配置系统代理时 openConnection() 本身就是直连；\n        // 显式 Proxy.NO_PROXY 会绕开 MuMu/Clash/mitm 链，正是本次真机长超时的来源。\n        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();\n""",
    "version json honor system proxy",
)
write(rel, s)


# ---------------------------------------------------------------------------
# 4) CNHotUpdate: don't synchronously wait for remote mirror table
# ---------------------------------------------------------------------------
rel = "patch/src/main/java/io/kamihama/magianative/CNHotUpdate.java"
s = read(rel)
old = """        // 线路表可能还没拉过（热更新不一定跟在安装器后面跑）\n        if (!CNMirrors.isLoaded()) {\n            CNMirrors.refresh(false);\n            if (!CNMirrors.isLoaded()) CNMirrors.refresh(true);\n        }\n\n"""
new = """        // 内置 fallback 可立即使用；远程线路表只做后台优化，服务器故障\n        // 不能在真正下载更新包之前再同步卡两轮 15 秒。\n        if (!CNMirrors.isLoaded()) CNMirrors.ensureLoadedAsync();\n\n"""
s = replace_once(s, old, new, "nonblocking hotupdate download mirror lookup")
write(rel, s)


# ---------------------------------------------------------------------------
# 5) CNVersionCheck: honor system proxy and fail open faster
# ---------------------------------------------------------------------------
rel = "patch/src/main/java/io/kamihama/magianative/CNVersionCheck.java"
s = read(rel)
s = s.replace("import java.net.Proxy;\n", "")
s = replace_once(
    s,
    """    private static final int CONNECT_TIMEOUT_MS = 15000;\n    private static final int READ_TIMEOUT_MS    = 15000;\n""",
    """    // config 是可选控制面：失败必须快速放行，不能先白等 15+15 秒。\n    private static final int CONNECT_TIMEOUT_MS = 5000;\n    private static final int READ_TIMEOUT_MS    = 7000;\n""",
    "version-check tighter fail-open timeouts",
)
s = replace_once(
    s,
    """        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);\n""",
    """        // 尊重 Android 系统代理；无系统代理时自然直连。显式 NO_PROXY 会绕开\n        // 用户已经配置好的 MuMu → Clash/mitm 链，并在控制面宕机时白等完整超时。\n        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();\n""",
    "version-check honor system proxy",
)
write(rel, s)


# ---------------------------------------------------------------------------
# 6) CNRestart + independent trampoline Activity: no AlarmManager/BAL dependency
# ---------------------------------------------------------------------------
rel = "patch/src/main/java/io/kamihama/magianative/CNRestart.java"
write(rel, r'''package io.kamihama.magianative;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Process;

/**
 * 真正的冷重启：先在独立 :cnrestart 进程启动一个透明前台 Activity，确认跳板已经
 * 存活后再杀主游戏进程；跳板保持前台，因此随后重新拉起 AppActivity 不受 Android
 * 10+ / Android 15 Background Activity Launch (BAL) 限制。
 *
 * 旧版 AlarmManager + PendingIntent 已由真机日志实锤失败：闹钟确实触发，但
 * ActivityTaskManager 返回 BAL_BLOCK / result code=102。继续调 alarm 延迟没有意义。
 */
public final class CNRestart {

    private static final String TAG = "MagiaCNRestart";
    /** 给独立跳板 Activity 足够时间完成 onCreate。 */
    private static final long TRAMPOLINE_SETTLE_MS = 650L;

    private CNRestart() {}

    /** Toast 提示 → 等 countdownMs → 冷重启。会阻塞，调用方必须放工作线程。 */
    public static void restartWithNotice(String toastText, long countdownMs) {
        try {
            final Activity act = RestClient.getCurrentActivity();
            if (act != null && toastText != null && !toastText.isEmpty()) {
                act.runOnUiThread(new ToastRunnable(act, toastText));
            }
            CNLog.i(TAG, "将在 " + countdownMs + "ms 后通过独立跳板进程重启");
            Thread.sleep(countdownMs);
            restartNow();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            CNLog.e(TAG, "重启流程出错", t);
        }
    }

    private static final class ToastRunnable implements Runnable {
        private final Context ctx;
        private final String msg;
        ToastRunnable(Context ctx, String msg) { this.ctx = ctx; this.msg = msg; }
        @Override public void run() {
            try { android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show(); }
            catch (Throwable ignore) {}
        }
    }

    /**
     * 启动独立 :cnrestart Activity → 等它成为前台 → 杀主进程。
     * 若跳板起不来则绝不杀当前游戏，避免再次把玩家直接扔回桌面。
     */
    public static void restartNow() {
        Activity act = null;
        try { act = RestClient.getCurrentActivity(); } catch (Throwable ignore) {}
        if (act == null) {
            CNLog.e(TAG, "拿不到前台 Activity，拒绝自杀式重启；保持当前进程运行");
            return;
        }
        try {
            Intent trampoline = new Intent(act, CNRestartActivity.class);
            trampoline.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            act.startActivity(trampoline);
            try { act.overridePendingTransition(0, 0); } catch (Throwable ignore) {}
            CNLog.i(TAG, "独立重启跳板已启动，等待 " + TRAMPOLINE_SETTLE_MS + "ms 后结束主进程");
        } catch (Throwable t) {
            CNLog.e(TAG, "启动独立重启跳板失败；保持当前进程运行", t);
            return;
        }

        try { Thread.sleep(TRAMPOLINE_SETTLE_MS); }
        catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
        }

        try { CNLog.flushNow(); } catch (Throwable ignore) {}
        Process.killProcess(Process.myPid());
    }
}
''')

rel_new = "patch/src/main/java/io/kamihama/magianative/CNRestartActivity.java"
write(rel_new, r'''package io.kamihama.magianative;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * 独立 :cnrestart 进程中的透明重启跳板。
 *
 * 它先成为“可见 Activity”，主进程随后自杀；延迟后从这个仍可见的 Activity
 * 启动真正的 launcher Activity，因此不会再命中 Android 15 的 BAL_BLOCK。
 */
public final class CNRestartActivity extends Activity {
    private static final String TAG = "MagiaCNRestart";
    private static final long RELAUNCH_DELAY_MS = 1200L;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        } catch (Throwable ignore) {}
        Log.i(TAG, "restart trampoline visible in pid=" + android.os.Process.myPid());

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                relaunchMain();
            }
        }, RELAUNCH_DELAY_MS);
    }

    private void relaunchMain() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (launch == null) {
                Log.e(TAG, "trampoline: launcher intent missing");
                finish();
                return;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            Log.i(TAG, "trampoline: relaunching main activity");
            startActivity(launch);
            try { overridePendingTransition(0, 0); } catch (Throwable ignore) {}
        } catch (Throwable t) {
            Log.e(TAG, "trampoline relaunch failed", t);
        } finally {
            try { finishAndRemoveTask(); } catch (Throwable t) { finish(); }
        }
    }
}
''')


# ---------------------------------------------------------------------------
# 7) Manifest: register the independent restart trampoline process
# ---------------------------------------------------------------------------
rel = "AndroidManifest.xml"
s = read(rel)
needle = """        </activity>\n        <service android:exported=\"true\" android:name=\"jp.f4samurai.pnote.util.MyFcmListenerService\">\n"""
replacement = """        </activity>\n        <activity android:excludeFromRecents=\"true\" android:exported=\"false\" android:launchMode=\"singleTask\" android:name=\"io.kamihama.magianative.CNRestartActivity\" android:noHistory=\"true\" android:process=\":cnrestart\" android:screenOrientation=\"userLandscape\" android:taskAffinity=\"\" android:theme=\"@android:style/Theme.Translucent.NoTitleBar\"/>\n        <service android:exported=\"true\" android:name=\"jp.f4samurai.pnote.util.MyFcmListenerService\">\n"""
s = replace_once(s, needle, replacement, "manifest restart activity")
write(rel, s)


# ---------------------------------------------------------------------------
# 8) MagiaLegacy.cpp: native GL-thread release entry + RegisterNatives binding
# ---------------------------------------------------------------------------
rel = "magia-native/src/MagiaLegacy.cpp"
s = read(rel)

marker = "\nstatic void pushSceneTopNew(void* self, const std::string& arg) {\n"
insert = r'''
// Java 浮层在 hide() 完成时会通过 Cocos2dxHelper.runOnGLThread() 调这里。
// 这样 deferred top/BGM 的释放有一个确定事件，不再依赖“之后也许还会发生”的
// Label::setString / LoadingSceneLayerInfo::setText 回调。
static void nativeReleaseDeferredTop(JNIEnv*, jclass) {
    LOGI("[Overlay] Java 通知浮层已撤，立即释放 deferred top/BGM");
    maybeReleaseDeferredTop();
}
'''
s = replace_once(s, marker, "\n" + insert + marker, "native overlay release function")

marker = """        // 把 nativeClientVersion 绑到 CNVersionCheck 上（客户端版本号硬编码在\n"""
insert = r'''        // 浮层关闭后的 deferred top/BGM 必须显式在 GL 线程释放。
        if (gClsDownloaderFix) {
            JNINativeMethod m[] = {
                { (char*)"nativeReleaseDeferredTop", (char*)"()V",
                  (void*)nativeReleaseDeferredTop },
            };
            if (env->RegisterNatives(gClsDownloaderFix, m, 1) != 0) {
                if (env->ExceptionCheck()) env->ExceptionClear();
                LOGE("[JNI] RegisterNatives(CNDownloaderFix) 失败——浮层释放将退回文本 hook 兜底");
            }
        }

'''
s = replace_once(s, marker, insert + marker, "register overlay release native")
write(rel, s)


# ---------------------------------------------------------------------------
# Validation: fail closed before the workflow commits anything
# ---------------------------------------------------------------------------
checks = {
    "patch/src/main/java/io/kamihama/magianative/CNHotUpdateCheck.java": [
        "VERSION_QUERY_DEADLINE_MS = 25000L",
        "CNDownloaderFix.syncInstalledUiState();",
        "CNCNDownloadUI.markFilePending(pkg.slot);",
        "new URL(url).openConnection();",
    ],
    "patch/src/main/java/io/kamihama/magianative/CNCNDownloadUI.java": [
        "CNDownloaderFix.nativeReleaseDeferredTop();",
        "public static void markFilePending(int i)",
    ],
    "patch/src/main/java/io/kamihama/magianative/CNDownloaderFix.java": [
        "public static native void nativeReleaseDeferredTop();",
        "static void syncInstalledUiState()",
        "restart trampoline process: skip installer/hot-update",
    ],
    "patch/src/main/java/io/kamihama/magianative/CNRestart.java": [
        "CNRestartActivity.class",
        "Process.killProcess(Process.myPid())",
    ],
    "patch/src/main/java/io/kamihama/magianative/CNRestartActivity.java": [
        "relaunching main activity",
    ],
    "magia-native/src/MagiaLegacy.cpp": [
        "nativeReleaseDeferredTop",
        "Java 通知浮层已撤",
    ],
    "AndroidManifest.xml": [
        "io.kamihama.magianative.CNRestartActivity",
        "android:process=\":cnrestart\"",
    ],
}
for rel, needles in checks.items():
    text = read(rel)
    for needle in needles:
        if needle not in text:
            raise RuntimeError(f"validation failed: {rel} missing {needle!r}")

# Confirm the two control-plane version paths no longer bypass the Android system proxy.
for rel in [
    "patch/src/main/java/io/kamihama/magianative/CNHotUpdateCheck.java",
    "patch/src/main/java/io/kamihama/magianative/CNVersionCheck.java",
]:
    if "openConnection(Proxy.NO_PROXY)" in read(rel):
        raise RuntimeError(f"{rel}: control-plane request still bypasses system proxy")

print("runtime fixes applied and validated")
