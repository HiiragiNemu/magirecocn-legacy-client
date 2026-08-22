package io.kamihama.magianative;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * 安装浮层的背景音乐。
 *
 * <p><b>为什么不用 MediaPlayer。</b>曲目来自 APK 自带的
 * {@code assets/resource/sound_native/bgm/*.hca}，构建期由
 * {@code tools/convert-bgm.py} 转成 OGG 放进 {@code assets/magia/}，同时把 HCA 头里的
 * <b>循环点</b>导出到 {@code assets/magia/bgm.json}。这两首的循环区是
 * {@code [0, total-235)}——文件尾部那 235 帧（约 5.3ms）是编码器 padding，
 * <b>不属于循环区</b>。{@code MediaPlayer.setLooping(true)} 只会整文件循环：既会把
 * padding 放出来，接缝处还会有一个可闻的空隙。
 *
 * <p>所以这里自己解码：{@link MediaExtractor} + {@link MediaCodec} 出 PCM，写进
 * {@link AudioTrack}（STREAM 模式），写满 {@code loopEnd} 帧就把提取器 seek 回
 * {@code loopStart} 继续写。AudioTrack 全程不停，接缝是**采样级精确且无缝**的。
 *
 * <p>能这么干的前提是两首的 {@code loopStart} 都是 0——0 必然是同步点，seek 回去
 * 重建出来的第一帧就是准确的。若将来出现 {@code loopStart > 0} 的曲子，seek 到非同步点
 * 会有几毫秒误差，届时需要改成「预解码整段循环区到内存」。{@link #LOOP_START_MUST_BE_ZERO}
 * 处有断言式的降级处理。
 *
 * <p>整个类<b>绝不外抛</b>：BGM 是锦上添花，任何环节出问题都只能安静降级，
 * 不允许影响安装流程。
 *
 * <p><b>播放状态只存在内存里，且默认关闭——这是有意为之，别改成持久化。</b>
 * 浮层的 BGM 与引擎自己的 BGM 是两套独立的播放器，谁也不知道谁。一旦选择被记到
 * SharedPreferences，下次启动只要浮层露一下脸（比如资源已就位、装完直接进游戏
 * 那条路径），我们的 BGM 就会自动起播，和引擎的 BGM 撞在一起变成二重奏。
 * 只存内存意味着<b>必须由玩家在本次会话里主动打开才会响</b>，永远不会自动起播，
 * 从根上排除了这种撞车。代价是每次启动都要重新点一下——值得。
 */
public final class CNBgm {

    private static final String TAG = "BGM";

    /** 与 {@code assets/magia/bgm.json} 同名。 */
    private static final String META_ASSET = "magia/bgm.json";

    /** 见类注释：目前两首的 loopStart 都是 0，seek 回 0 才能保证采样精确。 */
    private static final boolean LOOP_START_MUST_BE_ZERO = true;

    private CNBgm() {}

    // ==================================================================
    // 曲目元数据
    // ==================================================================

    private static final class Track {
        final int    id;
        final String file;
        final int    sampleRate;
        final int    channels;
        final long   loopStart;   // 采样帧
        final long   loopEnd;     // 采样帧（不含）
        Track(int id, String file, int sampleRate, int channels, long loopStart, long loopEnd) {
            this.id = id; this.file = file;
            this.sampleRate = sampleRate; this.channels = channels;
            this.loopStart = loopStart; this.loopEnd = loopEnd;
        }
    }

    private static Track[] tracks;

    /** 读 bgm.json。失败返回空表——没有曲目就等于「关闭」，不是错误。 */
    private static synchronized Track[] tracks(Context ctx) {
        if (tracks != null) return tracks;
        Track[] out = new Track[0];
        InputStream in = null;
        try {
            in = ctx.getAssets().open(META_ASSET);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            JSONArray arr = new JSONObject(bos.toString("UTF-8")).getJSONArray("tracks");
            out = new Track[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out[i] = new Track(
                        o.getInt("id"),
                        o.getString("file"),
                        o.getInt("sample_rate"),
                        o.getInt("channels"),
                        o.getLong("loop_start"),
                        o.getLong("loop_end"));
            }
            CNLog.i(TAG, "曲目表载入 " + out.length + " 首");
        } catch (Throwable t) {
            CNLog.w(TAG, "读不到 " + META_ASSET + "，BGM 不可用", t);
            out = new Track[0];
        } finally {
            CNIo.closeQuietly(in);
        }
        tracks = out;
        return tracks;
    }

    /** 可选曲目数量（UI 用它决定要不要显示 BGM 胶囊）。 */
    public static int trackCount(Context ctx) {
        try { return tracks(ctx).length; } catch (Throwable t) { return 0; }
    }

    /**
     * 曲名表，下标即胶囊上的编号（1 起）。
     *
     * <h3>为什么敢按编号绑</h3>
     *
     * 编号不是「文件排序排出来的」，而是在 {@code tools/convert-bgm.py} 的
     * {@code TRACKS} 里<b>显式写死</b>的：
     *
     * <pre>
     * TRACKS = [
     *     (1, "bgm00_system01_hca.hca"),
     *     (2, "bgm00_system02_hca.hca"),
     * ]
     * </pre>
     *
     * 所以「1 号是哪首」有唯一出处，不会因为文件改名或目录顺序变化而漂。
     * <b>改那张表就必须同步改这里</b>——它决定的是玩家看到的曲名。
     *
     * <h3>曲目考据</h3>
     *
     * 两首都是 TrySail 唱的手游<b>主线</b>主题曲，作词作曲渡辺翔：
     *
     * <ul>
     *   <li>1 =「かかわり」——第一部主题曲（2017）；</li>
     *   <li>2 =「うつろい」——第二部主题曲（2021，与「ごまかし」同单曲发行）。</li>
     * </ul>
     *
     * ⚠ 最容易写错的是把 1 号写成「ごまかし」：那是 2020 年 <b>TV 动画</b>的 OP，
     * 同样四假名、同样 TrySail、同样出现在「マギアレコード」名下，但不是手游主线的
     * 主题曲。三处来源交叉核对过才落的笔。
     */
    private static final String[] TITLES = {
            "かかわり",
            "うつろい",
    };

    /** 演唱者。两首同一位，单独抽出来免得曲名表里重复三遍。 */
    private static final String ARTIST = "TrySail";

    /**
     * 某个编号的曲名，形如 {@code かかわり ／ TrySail}。
     *
     * <p>查不到返回 {@code null}，调用方回落到只显示编号——将来若加进第三首而这张
     * 表没跟上，<b>宁可只报编号，也不能张冠李戴</b>。
     */
    public static String title(int id) {
        if (id < 1 || id > TITLES.length) return null;
        return TITLES[id - 1] + " ／ " + ARTIST;
    }

    // ---- JVM 回归测试入口 ----
    public static int titleCountForTest() { return TITLES.length; }

    // ==================================================================
    // 播放控制
    // ==================================================================

    private static volatile PlayThread thread;
    /** 当前曲目：0=关闭。 */
    private static volatile int current = 0;
    /** 浮层不可见时暂停，但记住选择。 */
    private static volatile boolean paused = false;

    public static int current() { return current; }

    /**
     * 切到某首曲子。{@code id<=0} 表示关闭。重复选同一首不会重启播放。
     *
     * <p>选择<b>不落盘</b>，只改 {@link #current} 这个进程内的静态字段——原因见类注释：
     * 一旦持久化，下次启动浮层一露脸就会自动起播，和引擎的 BGM 撞成二重奏。
     */
    public static synchronized void select(Context ctx, int id) {
        ensureLifecycle(ctx);
        if (id == current && thread != null && !paused) return;
        stopInternal();
        current = id;
        paused = false;
        if (id <= 0) { CNLog.i(TAG, "BGM 关闭"); return; }

        Track t = find(ctx, id);
        if (t == null) { CNLog.w(TAG, "没有编号为 " + id + " 的曲目"); current = 0; return; }
        start(ctx, t);
    }

    private static Track find(Context ctx, int id) {
        Track[] all = tracks(ctx);
        for (int i = 0; i < all.length; i++) if (all[i].id == id) return all[i];
        return null;
    }

    private static void start(Context ctx, Track t) {
        try {
            PlayThread p = new PlayThread(ctx.getApplicationContext(), t);
            p.setDaemon(true);
            thread = p;
            p.start();
            CNLog.i(TAG, "开始播放 BGM" + t.id + " loop=[" + t.loopStart + ", " + t.loopEnd + ")");
        } catch (Throwable e) {
            CNLog.e(TAG, "BGM 启动失败（已忽略）", e);
            thread = null;
        }
    }

    /** 浮层隐藏 / 进后台时调用。保留选择，回来还能接着放。 */
    public static synchronized void pause() {
        if (thread == null) return;
        paused = true;
        stopInternal();
        CNLog.i(TAG, "BGM 暂停");
    }

    /** 浮层重新可见时调用。 */
    public static synchronized void resume(Context ctx) {
        if (!paused || current <= 0) return;
        paused = false;
        Track t = find(ctx, current);
        if (t != null) start(ctx, t);
    }

    /** 安装结束时调用：彻底停掉，不保留。 */
    public static synchronized void stop() {
        stopInternal();
        current = 0;
        paused = false;
        CNLog.i(TAG, "BGM 停止");
    }

    private static void stopInternal() {
        PlayThread p = thread;
        thread = null;
        if (p != null) {
            // F-R5-01：先置 stopped 再 quit——run() finally 的自清守卫
            // `thread == this && !stopped` 由此对正常终止路径永久失效，只有
            // 真自死（从未被 stopInternal 终止过）才自清，不会在 join 超时后
            // 误清新线程的引用并清空 current。
            p.stopped = true;
            // F-C-06：quit() 里已顺手 pause+flush 旧 AudioTrack——立即静音、
            // 并尝试唤醒阻塞中的 write()（缓冲 0.5s 起步，失焦时甚至无限阻塞）。
            // 再 interrupt 一次兜底：线程若恰好睡在 dequeue/write 之外的地方
            // 能立刻醒。至此旧线程通常在几十 ms 内退出，join 上限从 800ms 降到
            // 400ms 只是保险：即便超时，旧线程的 AudioTrack 也已被静音，快速
            // 切曲不会再「二重奏」，UI 线程（BgmPillClick / StayClick→hide→stop）
            // 的最坏卡顿也一并减半。join 留在调用线程是为了把新旧切换串行化，
            // 但有了 pause 在前，它不再是防二重奏的关键路径。
            p.quit();
            p.interrupt();
            try { p.join(400L); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ==================================================================
    // 后台/隐藏暂停接线（F-R4-02）
    // ==================================================================

    /**
     * 把 {@link #pause}/{@link #resume} 接到「应用整体进出后台」上。
     *
     * <p>这两个方法本是为「浮层隐藏/进后台时暂停、回来接着放」写的，但长期零调用方
     * ——注释描述的行为从未兑现，BGM 在切后台后照响不误。主机是基础 APK 的
     * Activity，Java 侧覆写不了它的 onStop，只能注册 Application 级生命周期回调。
     *
     * <p><b>为什么用前台 Activity 计数而不是「任一 onStop 就 pause」</b>（F-R5-01）：
     * 主进程里不止宿主机一个 Activity——CNOfflineImportActivity 就在主进程。宿主被
     * 同进程 Activity 盖住再回来时，Android 的确定顺序是 A.onStart → A.onResume →
     * B.onStop；若裸绑 onStart/onStop，「先续播、再被迟到的 B.onStop 暂停」，BGM
     * 卡在暂停态。计数法把语义修正为「应用整体还在前台就不动」：计数 1→0 才暂停、
     * 0→1 才续播，迟到的那次 B.onStop 到不了 0，配对错乱从根上消失（与
     * ProcessLifecycleOwner 的 onStart/onStop 计数同源）。代价是离线导入等前台遮挡
     * 期间 BGM 继续放——符合「进后台才暂停」的字面语义。注册失败退回旧行为
     * （安装期持续播），BGM 本来就是锦上添花。
     */
    private static volatile boolean lifecycleBound;

    private static synchronized void ensureLifecycle(Context ctx) {
        if (lifecycleBound) return;
        try {
            // registerActivityLifecycleCallbacks 挂在 Application 上，而
            // getApplicationContext() 的静态类型是 Context——运行期对象就是
            // Application，直接 cast（也顺带让它自身成为回调持的 Context）。
            // cast 与注册都放进 try：ContextWrapper 非常规实现或 getApplicationContext
            // 返回非 Application 时优雅降级，别把崩溃点提前到 select 的热路径。
            final Application app = (Application) ctx.getApplicationContext();
            LifecycleHook hook = new LifecycleHook(app);
            // F-R6-01：**计数基线必须对齐注册时刻的前台状态**。回调不补发历史事件
            // ——select()（点胶囊）才注册时宿主机已经 onStart，计数器若从 0 起算会
            // 恒差一，第一次「被同进程 Activity 盖住再回来」就复现 F-2 想消灭的错乱
            // （A.onStop→C=0→提前 pause）。胶囊只在安装浮层挂起时可点，彼时宿主机是
            // 唯一（或最上层）已启动 Activity，seed 为 1 即 T 的下界；不会 over-seed
            // 造成「该停不停」，最多在多 Activity 栈时把「进后台」的暂停延迟到最后一
            // 个 onStop——可接受。
            hook.foreground = 1;
            app.registerActivityLifecycleCallbacks(hook);
            lifecycleBound = true;
        } catch (Throwable t) {
            CNLog.w(TAG, "生命周期回调注册失败，后台不自动暂停", t);
        }
    }

    /**
     * 前台 Activity 计数（F-R5-01）。回调都在主线程串行执行，字段无需同步；计数只
     * 反映「应用整体是否在前台」，与具体是哪个 Activity 无关——见 {@link #ensureLifecycle}。
     * 空实现的那五个方法是接口要求，无实质作用。
     */
    private static final class LifecycleHook implements Application.ActivityLifecycleCallbacks {
        private final Context app;
        private int foreground;
        LifecycleHook(Context app) { this.app = app; }
        @Override public void onActivityStarted(Activity a) {
            if (++foreground == 1) {
                // 0→1：应用回到前台，接续播放（resume 内守卫，未暂停/未选曲是空操作）。
                try { resume(app); } catch (Throwable ignore) {}
            }
        }
        @Override public void onActivityStopped(Activity a) {
            if (--foreground <= 0) {
                foreground = 0;   // 防御：不会出现负计数
                // 1→0：应用整体进后台，暂停（thread==null 时也是空操作）。
                try { pause(); } catch (Throwable ignore) {}
            }
        }
        @Override public void onActivityCreated(Activity a, android.os.Bundle s) {}
        @Override public void onActivityResumed(Activity a) {}
        @Override public void onActivityPaused(Activity a) {}
        @Override public void onActivitySaveInstanceState(Activity a, android.os.Bundle s) {}
        @Override public void onActivityDestroyed(Activity a) {}
    }

    // ==================================================================
    // 解码 + 播放线程
    // ==================================================================

    private static final class PlayThread extends Thread {
        private final Context ctx;
        private final Track   track;
        private volatile boolean running = true;
        /** 收到过 stopInternal 的终止请求（F-R5-01）：自清守卫让位，避免误清新线程引用。 */
        private volatile boolean stopped;
        /**
         * 当前在写的 AudioTrack（F-C-06）。volatile：quit() 从别的线程
         * （多半是 UI 线程）拿它做 pause/flush。不置回 null——线程收尾的
         * finally 里 release 之后若再有迟到的 quit()，pause 抛异常安静吞掉。
         */
        private volatile AudioTrack outRef;

        PlayThread(Context ctx, Track track) {
            super("cnv-bgm");
            this.ctx = ctx; this.track = track;
        }

        void quit() {
            running = false;
            // F-C-06：只置 running 标志唤不醒阻塞中的 AudioTrack.write()——
            // 缓冲按 0.5s 起步，单次 write 最多阻塞整段缓冲时长；设备音频
            // 失焦/挂起时甚至无限阻塞，stopInternal 的 join 超时后旧线程仍在
            // 出声，快速连点 BGM 胶囊就成了「二重奏」。pause 让旧轨立即静音
            // （线程晚死也不出声），flush 丢弃排队数据并唤醒阻塞的 write。
            // 未在播放状态时 pause 会抛 IllegalStateException，安静吞掉即可。
            AudioTrack at = outRef;
            if (at != null) {
                try { at.pause(); } catch (Throwable ignore) {}
                try { at.flush(); } catch (Throwable ignore) {}
            }
        }

        @Override public void run() {
            MediaExtractor  ex    = null;
            MediaCodec      codec = null;
            AudioTrack      out   = null;
            AssetFileDescriptor afd = null;
            try {
                ex = new MediaExtractor();
                // openFd() 只对**未压缩**的 asset 有效；一旦打包时被 deflate 了，
                // 它会抛 "This file can not be opened as a file descriptor"。
                // apktool.yml 的 doNotCompress 已经加了 ogg，但不能把能不能出声
                // 押在打包细节上——失败就落一份到 cacheDir 再放。
                try {
                    afd = ctx.getAssets().openFd(track.file);
                    ex.setDataSource(afd.getFileDescriptor(),
                                     afd.getStartOffset(), afd.getLength());
                } catch (Throwable notFd) {
                    if (afd != null) { CNIo.closeQuietly(afd); afd = null; }
                    CNLog.w(TAG, "asset 是压缩的，改用 cacheDir 副本: " + notFd);
                    java.io.File cached = extractToCache(ctx, track.file);
                    if (cached == null) { CNLog.e(TAG, "释放 BGM 到 cacheDir 失败"); return; }
                    ex.setDataSource(cached.getAbsolutePath());
                }

                int audioTrackIdx = -1;
                MediaFormat fmt = null;
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    MediaFormat f = ex.getTrackFormat(i);
                    String mime = f.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) {
                        audioTrackIdx = i; fmt = f; break;
                    }
                }
                if (audioTrackIdx < 0) {
                    CNLog.w(TAG, "文件里没有音频轨: " + track.file);
                    return;
                }
                ex.selectTrack(audioTrackIdx);

                // 以**容器里的实际格式**为准建 AudioTrack，而不是照抄 bgm.json。
                // 两者理应一致，不一致就说明音频重转过而元数据没跟上——那时用错
                // 采样率会变调，宁可信文件本身。
                int srcRate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                        ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : track.sampleRate;
                int srcCh   = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                        ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : track.channels;
                if (srcRate != track.sampleRate || srcCh != track.channels) {
                    CNLog.w(TAG, "bgm.json 与音频实际格式不一致：json="
                            + track.sampleRate + "Hz/" + track.channels + "ch 实际="
                            + srcRate + "Hz/" + srcCh + "ch，以实际为准");
                }
                CNLog.i(TAG, "解码 " + track.file + " mime=" + fmt.getString(MediaFormat.KEY_MIME)
                        + " " + srcRate + "Hz/" + srcCh + "ch");

                codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
                codec.configure(fmt, null, null, 0);
                codec.start();

                out = buildAudioTrack(srcRate, srcCh);
                outRef = out;   // F-C-06：登记给 quit() 做 pause/flush
                out.play();

                decodeLoop(ex, codec, out, srcCh);
            } catch (Throwable t) {
                CNLog.e(TAG, "BGM 播放线程异常（已忽略）", t);
            } finally {
                if (out != null) {
                    try { out.pause(); out.flush(); } catch (Throwable ignore) {}
                    try { out.release(); } catch (Throwable ignore) {}
                }
                if (codec != null) {
                    try { codec.stop(); } catch (Throwable ignore) {}
                    try { codec.release(); } catch (Throwable ignore) {}
                }
                if (ex != null)  try { ex.release(); }  catch (Throwable ignore) {}
                CNIo.closeQuietly(afd);
                // F-R4-01：播放线程自死（解码异常/设备掉队）时静默退出，静态
                // thread 仍指向已死线程——current() 谎报「在播」、同 id 的
                // select() 被早返回守卫吞掉，同一首再也点不响。收尾时若 thread
                // 仍持有本线程就清掉，恢复「未播放」初始状态。
                // F-R5-01：再加 !stopped——stopInternal 先置 stopped 再 quit，
                // 正常终止路径自此不会自清；只有真自死（从未被 stop 过）才清。
                // 残余的「检查与写入之间被抢占」窗口是微秒级，对装饰性 BGM
                // 足够；彻底原子化要持类锁，会与 stopInternal 持锁 join 死锁。
                if (thread == this && !stopped) {
                    thread = null;
                    current = 0;
                }
            }
        }

        /**
         * 主解码循环。核心是 {@code framesWritten}：它是**已经写进 AudioTrack 的
         * 采样帧数**，一旦达到 {@code loopEnd} 就把提取器 seek 回 {@code loopStart}、
         * flush 解码器，并把计数拨回 {@code loopStart}。写出去的帧数被精确裁剪到
         * 循环点，所以尾部 padding 永远不会被播出来，接缝也没有空隙
         * （AudioTrack 里还压着已排队的音频，seek+flush 的几毫秒不会造成断流）。
         */
        private void decodeLoop(MediaExtractor ex, MediaCodec codec, AudioTrack out,
                                int channels) {
            final int bytesPerFrame = 2 * channels;   // PCM 16bit
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long framesWritten = track.loopStart;
            boolean sawInputEos = false;
            long totalWritten = 0L;
            int  loops = 0;

            // F-C-06：除 running 外也看线程中断标志——stopInternal 现在会
            // interrupt，两处任一置位都应尽快退出，别等下一次 write 返回。
            while (running && !Thread.currentThread().isInterrupted()) {
                if (!sawInputEos) {
                    int inIdx = codec.dequeueInputBuffer(10000L);
                    if (inIdx >= 0) {
                        ByteBuffer in = getInputBuffer(codec, inIdx);
                        int size = (in == null) ? -1 : ex.readSampleData(in, 0);
                        if (size < 0) {
                            // 读到文件尾。正常情况下 loopEnd 会先到，走不到这里；
                            // 真到了就当作一次循环处理，避免整段停掉。
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            sawInputEos = true;
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }

                int outIdx = codec.dequeueOutputBuffer(info, 10000L);
                if (outIdx >= 0) {
                    ByteBuffer buf = getOutputBuffer(codec, outIdx);
                    if (buf != null && info.size > 0) {
                        int write = framesToWrite(info.size / bytesPerFrame,
                                                  framesWritten, track.loopEnd);
                        if (write > 0) {
                            byte[] pcm = new byte[write * bytesPerFrame];
                            // 显式设边界：MediaCodec 只保证 info.offset/size 有效，
                            // 缓冲自身的 position/limit 不一定是我们要的那一段。
                            buf.limit(info.offset + info.size);
                            buf.position(info.offset);
                            buf.get(pcm, 0, pcm.length);
                            writeAll(out, pcm);
                            framesWritten += write;
                            if (totalWritten == 0L) {
                                // 第一块真正写进声卡。有这一行就说明解码链是通的，
                                // 「没声音」的锅在音量/焦点那边；没有就说明卡在更早的地方。
                                CNLog.i(TAG, "首块 PCM 已写入 AudioTrack（" + write + " 帧）");
                            }
                            totalWritten += write;
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false);

                    if (framesWritten >= track.loopEnd
                            || (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        if (!running) break;
                        CNLog.i(TAG, "循环点到达，回到起点（第 " + (++loops) + " 圈）");
                        // 回到循环起点。loopStart==0 时 0 必是同步点，重建出来精确；
                        // 若将来有非零起点，这里会有几毫秒误差（见类注释）。
                        long seekUs = LOOP_START_MUST_BE_ZERO && track.loopStart == 0
                                ? 0L
                                : track.loopStart * 1000000L / track.sampleRate;
                        ex.seekTo(seekUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                        codec.flush();
                        framesWritten = track.loopStart;
                        sawInputEos = false;
                    }
                }
            }
        }

        /** 单次 write 的最大字节数：8192B ≈ 46ms 的 44.1kHz 立体声 PCM。 */
        private static final int WRITE_CHUNK_BYTES = 8192;

        private void writeAll(AudioTrack out, byte[] pcm) {
            int off = 0;
            // F-C-06：分小块写、每块之间看一次退出标志。整段一次写时，单次
            // write 最多阻塞「整段缓冲」的时长（0.5s 起步），收到 quit 也要
            // 等它自然返回才停；切小后正常路径的退出延迟降到几十毫秒。
            // （失焦导致的无限阻塞这条路靠 quit() 的 pause+flush 与 interrupt
            // 解围，分块帮不上，但也不妨碍。）
            while (off < pcm.length && running
                    && !Thread.currentThread().isInterrupted()) {
                int n = out.write(pcm, off,
                        Math.min(pcm.length - off, WRITE_CHUNK_BYTES));
                if (n <= 0) break;
                off += n;
            }
        }
    }

    /**
     * 把 asset 释放成 cacheDir 里的真实文件，供 {@link MediaExtractor} 按路径读。
     *
     * <p>只在 {@code openFd()} 失败（asset 被压缩）时才走这条路。已经释放过且大小
     * 一致就直接复用，不重复写盘。
     */
    private static java.io.File extractToCache(Context ctx, String assetPath) {
        InputStream in = null;
        java.io.FileOutputStream fos = null;
        try {
            java.io.File dir = new java.io.File(ctx.getCacheDir(), "cnv_bgm");
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) return null;
            String name = assetPath.substring(assetPath.lastIndexOf('/') + 1);
            java.io.File dst = new java.io.File(dir, name);

            in = ctx.getAssets().open(assetPath);
            if (dst.isFile() && dst.length() > 0 && dst.length() == in.available()) {
                return dst;                      // 已经释放过，直接用
            }
            java.io.File tmp = new java.io.File(dir, name + ".tmp");
            fos = new java.io.FileOutputStream(tmp);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            fos.flush();
            try { fos.getFD().sync(); } catch (Throwable ignore) {}
            fos.close(); fos = null;
            // 先写临时文件再改名：中途被杀不会留下半截文件被下次当成完整的用
            if (dst.isFile() && !dst.delete()) { /* 覆盖失败也继续尝试 rename */ }
            if (!tmp.renameTo(dst)) { tmp.delete(); return null; }
            CNLog.i(TAG, "已释放 " + assetPath + " → " + dst.getAbsolutePath()
                    + "（" + dst.length() / 1024 + " KB）");
            return dst;
        } catch (Throwable t) {
            CNLog.w(TAG, "释放 asset 失败: " + assetPath, t);
            return null;
        } finally {
            CNIo.closeQuietly(fos);
            CNIo.closeQuietly(in);
        }
    }

    /**
     * 这一块解码输出里，有多少帧可以写出去而不越过循环终点。
     *
     * <p>抽成独立方法是为了能在 JVM 上直接验证：循环是否精确停在 {@code loopEnd}、
     * 会不会多写一帧（多写就会把尾部的编码器 padding 放出来）、会不会写负数。
     * 这段算术是「按循环点循环」的全部要害。
     */
    static int framesToWrite(int availFrames, long framesWritten, long loopEnd) {
        if (availFrames <= 0) return 0;
        long room = loopEnd - framesWritten;
        if (room <= 0) return 0;
        return (int) Math.min((long) availFrames, room);
    }

    /** API 21+ 用 getInputBuffer；早期签名在 21 上仍可用，这里统一走新 API。 */
    private static ByteBuffer getInputBuffer(MediaCodec codec, int idx) {
        try { return codec.getInputBuffer(idx); } catch (Throwable t) { return null; }
    }

    private static ByteBuffer getOutputBuffer(MediaCodec codec, int idx) {
        try { return codec.getOutputBuffer(idx); } catch (Throwable t) { return null; }
    }

    private static AudioTrack buildAudioTrack(int sampleRate, int channels) {
        int chMask = (channels >= 2)
                ? AudioFormat.CHANNEL_OUT_STEREO
                : AudioFormat.CHANNEL_OUT_MONO;
        int min = AudioTrack.getMinBufferSize(
                sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) min = sampleRate * 2 * channels / 4;   // 兜底约 250ms
        // 缓冲开大一些：解码线程被系统调度挤开时不至于断音，
        // 也给 seek+flush 的那几毫秒留出余量。
        int bufSize = Math.max(min * 4, sampleRate * 2 * channels / 2);

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioFormat af = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(chMask)
                .build();
        AudioTrack at = new AudioTrack(attrs, af, bufSize,
                AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
        CNLog.i(TAG, "AudioTrack 就绪 " + sampleRate + "Hz/" + channels + "ch"
                + " buf=" + bufSize + " state=" + at.getState());
        return at;
    }
}
