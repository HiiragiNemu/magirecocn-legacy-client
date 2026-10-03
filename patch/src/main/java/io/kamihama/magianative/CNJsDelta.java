package io.kamihama.magianative;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** Cumulative last-writer layer. The full JS archive and its version remain independent. */
final class CNJsDelta {
    static final String NAME = "cn_js_delta.zip";
    static final String MANIFEST = "magica/.cn_js_delta.json";
    private static final String CACHE = ".cn_js_delta_cache.zip";
    static final int BASE_VERSION = 103;
    static final String BASE_SHA256 = "bffc1acc31f65c24cc2f9042a492e4f18c5ba9e41806ed725e1e0730c60a7a9d";
    static final String SCENARIO_SHA256 = "f268c6e4791b778984db2de934f6bc5e0b0b155b217e198663ec3c34d64ffa6c";

    static void verifyBaseline(File archive, boolean scenario) throws Exception {
        if (!hash(new FileInputStream(archive)).equals(scenario ? SCENARIO_SHA256 : BASE_SHA256))
            throw new IOException("完整基础包身份不符，请重新下载固定基线");
    }

    private static void fence(File root, File archive, JSONObject incoming) throws Exception {
        File active = new File(root, MANIFEST);
        int next = incoming.getInt("version");
        if (active.isFile()) {
            String text = read(new FileInputStream(active));
            JSONObject current = new JSONObject(text);
            int old = current.getInt("version");
            if (next < old) throw new IOException("拒绝旧补充包覆盖已安装新版本");
            if (next == old) {
                ZipFile zip = new ZipFile(archive);
                try {
                    if (!text.equals(read(zip.getInputStream(zip.getEntry(MANIFEST)))))
                        throw new IOException("同版本补充包内容身份冲突");
                } finally { zip.close(); }
            }
        }
        File cache = new File(root, CACHE);
        if (cache.isFile()) {
            JSONObject cached;
            try { cached = validate(cache, BASE_VERSION, -1); }
            catch (Exception corrupt) {
                if (!active.isFile()) throw corrupt;
                // A checked incoming archive may repair a broken cache, never use it for replay.
                return;
            }
            int old = cached.getInt("version");
            if (next < old) throw new IOException("拒绝回退最新补充包缓存");
            if (next == old && !hash(new FileInputStream(cache)).equals(hash(new FileInputStream(archive))))
                throw new IOException("同版本补充包压缩包身份冲突");
        }
    }

    /** Check before any baseline writer starts, never after it has erased new text. */
    static void requireReplayReady(File root, int baseVersion) throws Exception {
        File cache = new File(root, CACHE), active = new File(root, MANIFEST);
        if (!cache.isFile()) {
            if (active.isFile()) throw new IOException("最新补充包缓存缺失，基础包重装暂缓");
            return;
        }
        JSONObject m = validate(cache, baseVersion, -1);
        fence(root, cache, m);
    }

    private static void verifyInstalled(File root, JSONObject m) throws Exception {
        JSONArray entries = m.getJSONArray("entries");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.getJSONObject(i);
            File file = new File(root, e.getString("path"));
            if (!file.isFile() || file.length() != e.getLong("size") || !hash(new FileInputStream(file)).equals(e.getString("sha256")))
                throw new IOException("补充包最终落盘校验失败: " + e.getString("path"));
        }
    }

    static JSONObject validate(File archive, int baseVersion, int expectedVersion) throws Exception {
        ZipFile z = new ZipFile(archive);
        try {
            ZipEntry manifest = z.getEntry(MANIFEST);
            if (manifest == null || manifest.getSize() > 8 * 1024 * 1024)
                throw new IOException("补充包缺少清单或清单过大");
            JSONObject m = new JSONObject(read(z.getInputStream(manifest)));
            if (!"magireco-cn-js-delta/v1".equals(m.getString("schema"))
                    || m.getInt("base_js_version") != baseVersion
                    || m.getInt("version") <= 0
                    || (expectedVersion > 0 && m.getInt("version") != expectedVersion)
                    || baseVersion != BASE_VERSION
                    || !BASE_SHA256.equals(m.getString("base_js_sha256")))
                throw new IOException("补充包版本或固定 JS 基包不匹配");
            JSONArray entries = m.getJSONArray("entries");
            Set<String> declared = new HashSet<String>();
            declared.add(MANIFEST);
            for (int i = 0; i < entries.length(); i++) {
                JSONObject e = entries.getJSONObject(i);
                String path = e.getString("path");
                if (!validPath(path) || !declared.add(path))
                    throw new IOException("补充包路径重复或非法");
                ZipEntry ze = z.getEntry(path);
                if (ze == null || ze.isDirectory() || ze.getSize() != e.getLong("size")
                        || !hash(z.getInputStream(ze)).equals(e.getString("sha256")))
                    throw new IOException("补充包文件校验失败: " + path);
            }
            Set<String> actual = new HashSet<String>();
            Enumeration<? extends ZipEntry> all = z.entries();
            while (all.hasMoreElements()) {
                ZipEntry e = all.nextElement();
                if (e.isDirectory()) continue;
                if (!actual.add(e.getName())) throw new IOException("ZIP 条目重复");
            }
            if (!actual.equals(declared)) throw new IOException("补充包清单与内容不一致");
            return m;
        } finally { z.close(); }
    }

    private static boolean validPath(String path) {
        if (!(path.startsWith("magica/") || path.startsWith("madomagi/"))
                || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0) return false;
        for (String part : path.split("/", -1))
            if (part.length() == 0 || part.equals(".") || part.equals("..")) return false;
        for (int i = 0; i < path.length(); i++) if (path.charAt(i) < 32) return false;
        return true;
    }

    static void apply(File archive, File root, int baseVersion, int version) throws Exception {
        synchronized (CNDownloaderFix.extractCommitLock()) {
        JSONObject manifest = validate(archive, baseVersion, version);
        fence(root, archive, manifest);
        File cache = new File(root, CACHE);
        File candidate = CNAtomicReplace.stage(cache);
        try {
            InputStream in = new FileInputStream(archive);
            try {
                FileOutputStream out = new FileOutputStream(candidate);
                try {
                    byte[] b = new byte[65536]; int n;
                    while ((n = in.read(b)) != -1) out.write(b, 0, n);
                    out.getFD().sync();
                } finally { out.close(); }
            } finally { in.close(); }
            validate(candidate, baseVersion, version);
            CNHotUpdateTx.apply(candidate, root, "js_delta");
            verifyInstalled(root, manifest);
            CNAtomicReplace.commit(candidate, cache);
        } finally { CNAtomicReplace.discard(candidate); }
        }
    }

    /** A later manual reinstall of an old ZIP must not erase the cumulative layer. */
    static void reapplyCached(File root, int baseVersion) throws Exception {
        synchronized (CNDownloaderFix.extractCommitLock()) {
            requireReplayReady(root, baseVersion);
            File cache = new File(root, CACHE);
            if (!cache.isFile()) return;
            JSONObject m = validate(cache, baseVersion, -1);
            CNHotUpdateTx.apply(cache, root, "js_delta");
            verifyInstalled(root, m);
        }
    }

    static boolean installedMatches(File root, int baseVersion, int version) {
        try {
            JSONObject m = validate(new File(root, CACHE), baseVersion, version);
            fence(root, new File(root, CACHE), m);
            if (!new File(root, MANIFEST).isFile()) return false;
            JSONArray entries = m.getJSONArray("entries");
            for (int i = 0; i < entries.length(); i++) {
                JSONObject e = entries.getJSONObject(i);
                File f = new File(root, e.getString("path"));
                if (!f.isFile() || f.length() != e.getLong("size")
                        || !hash(new FileInputStream(f)).equals(e.getString("sha256"))) return false;
            }
            return true;
        } catch (Exception e) { return false; }
    }

    private static String hash(InputStream in) throws Exception {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) != -1) d.update(b, 0, n);
            StringBuilder out = new StringBuilder();
            for (byte v : d.digest()) out.append(String.format(Locale.US, "%02x", v & 255));
            return out.toString();
        } finally { in.close(); }
    }
    private static String read(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) != -1) {
                if (out.size() + n > 8 * 1024 * 1024) throw new IOException("补充包清单过大");
                out.write(b, 0, n);
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally { in.close(); }
    }
}
