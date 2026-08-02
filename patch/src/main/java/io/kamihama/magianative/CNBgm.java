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

    /** 读回上次的选择；没存过默认关闭——不问自来的音乐挺讨厌的。 */
    public static int loadChoice(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                      .getInt(PREF_BGM, 0);
        } catch (Throwable t) { return 0; }
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
                afd = ctx.getAssets().openFd(track.file);
                ex = new MediaExtractor();
                ex.setDataSource(afd.getFileDescriptor(),
                                 afd.getStartOffset(), afd.getLength());

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

                codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
                codec.configure(fmt, null, null, 0);
                codec.start();

                out = buildAudioTrack(track);
                out.play();

                decodeLoop(ex, codec, out);
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
        private void decodeLoop(MediaExtractor ex, MediaCodec codec, AudioTrack out) {
            final int bytesPerFrame = 2 * track.channels;   // PCM 16bit
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long framesWritten = track.loopStart;
            boolean sawInputEos = false;

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
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false);

                    if (framesWritten >= track.loopEnd
                            || (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        if (!running) break;
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

    private static AudioTrack buildAudioTrack(Track t) {
        int chMask = (t.channels >= 2)
                ? AudioFormat.CHANNEL_OUT_STEREO
                : AudioFormat.CHANNEL_OUT_MONO;
        int min = AudioTrack.getMinBufferSize(
                t.sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) min = t.sampleRate * 2 * t.channels / 4;   // 兜底约 250ms
        // 缓冲开大一些：解码线程被系统调度挤开时不至于断音，
        // 也给 seek+flush 的那几毫秒留出余量。
        int bufSize = Math.max(min * 4, t.sampleRate * 2 * t.channels / 2);

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioFormat af = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(t.sampleRate)
                .setChannelMask(chMask)
                .build();
        return new AudioTrack(attrs, af, bufSize,
                AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
    }
}
