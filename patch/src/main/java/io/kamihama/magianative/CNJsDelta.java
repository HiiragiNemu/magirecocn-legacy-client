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
                    || !m.getString("base_js_sha256").matches("[0-9a-f]{64}"))
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
        validate(archive, baseVersion, version);
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
            CNAtomicReplace.commit(candidate, cache);
        } finally { CNAtomicReplace.discard(candidate); }
    }

    /** A later manual reinstall of an old ZIP must not erase the cumulative layer. */
    static void reapplyCached(File root, int baseVersion) throws Exception {
        File cache = new File(root, CACHE);
        if (!cache.isFile()) return;
        JSONObject m = validate(cache, baseVersion, -1);
        // A crash after new content committed but before cache promotion must not restore an older cache.
        File active = new File(root, MANIFEST);
        if (active.isFile() && new JSONObject(read(new FileInputStream(active)))
                .optInt("version", 0) > m.getInt("version"))
            throw new IOException("补充包缓存落后，等待重新取得最新补充包");
        CNHotUpdateTx.apply(cache, root, "js_delta");
    }

    static boolean installedMatches(File root, int baseVersion, int version) {
        try {
            JSONObject m = validate(new File(root, CACHE), baseVersion, version);
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
