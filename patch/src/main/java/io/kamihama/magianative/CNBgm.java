package io.kamihama.magianative;

import android.content.Context;
import android.content.SharedPreferences;
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
 */
public final class CNBgm {

    private static final String TAG = "BGM";

    /** 与 {@code assets/magia/bgm.json} 同名。 */
    private static final String META_ASSET = "magia/bgm.json";

    private static final String PREFS_NAME = "cnv_installer_ui";
    /** 玩家选的曲目：0=关闭，1=BGM1，2=BGM2。 */
    public  static final String PREF_BGM   = "bgm_track";

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
            if (in != null) try { in.close(); } catch (Throwable ignore) {}
        }
        tracks = out;
        return tracks;
    }

    /** 可选曲目数量（UI 用它决定要不要显示 BGM 胶囊）。 */
    public static int trackCount(Context ctx) {
        try { return tracks(ctx).length; } catch (Throwable t) { return 0; }
    }

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
     * 读回上次的选择。
     *
     * <p>没存过时默认 <b>BGM1</b>：需求是「给浮层加上 BGM」，默认静音的话等于没加。
     * 不喜欢点一下胶囊就关，选择会被记住。
     */
    public static int loadChoice(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                      .getInt(PREF_BGM, 1);
        } catch (Throwable t) { return 1; }
    }

    /**
     * 切到某首曲子。{@code id<=0} 表示关闭。重复选同一首不会重启播放。
     */
    public static synchronized void select(Context ctx, int id) {
        try {
            SharedPreferences.Editor e = ctx.getSharedPreferences(
                    PREFS_NAME, Context.MODE_PRIVATE).edit();
            e.putInt(PREF_BGM, id).apply();
        } catch (Throwable ignore) {}

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
            p.quit();
            try { p.join(800L); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ==================================================================
    // 解码 + 播放线程
    // ==================================================================

    private static final class PlayThread extends Thread {
        private final Context ctx;
        private final Track   track;
        private volatile boolean running = true;

        PlayThread(Context ctx, Track track) {
            super("cnv-bgm");
            this.ctx = ctx; this.track = track;
        }

        void quit() { running = false; }

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
                    if (afd != null) { try { afd.close(); } catch (Throwable ignore) {} afd = null; }
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
                if (afd != null) try { afd.close(); }   catch (Throwable ignore) {}
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

            while (running) {
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

        private void writeAll(AudioTrack out, byte[] pcm) {
            int off = 0;
            while (off < pcm.length && running) {
                int n = out.write(pcm, off, pcm.length - off);
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
            if (fos != null) try { fos.close(); } catch (Throwable ignore) {}
            if (in  != null) try { in.close();  } catch (Throwable ignore) {}
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
