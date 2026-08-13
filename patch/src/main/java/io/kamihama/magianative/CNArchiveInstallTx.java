package io.kamihama.magianative;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/** Crash-resumable base-resource extraction transaction. */
public final class CNArchiveInstallTx {
    private static final String TAG = "CNArchiveInstallTx";
    private static final String SCHEMA = "CNV-EXTRACT-1";
    private static final int CHECKPOINT_ENTRIES = 32;
    private static final long CHECKPOINT_BYTES = 64L * 1024L * 1024L;
    private static final long CHECKPOINT_NS = 1_000_000_000L;

    public interface Cancel { boolean isCancelled(); }
    public interface Progress {
        void onProgress(int doneEntries, int totalEntries, long doneBytes, long totalBytes);
    }

    public static final class CancelledException extends IOException {
        private static final long serialVersionUID = 1L;
        CancelledException(String message) { super(message); }
    }

    public static final class InstallIOException extends IOException {
        private static final long serialVersionUID = 1L;
        InstallIOException(String message, Throwable cause) { super(message, cause); }
        InstallIOException(String message) { super(message); }
    }

    private static final class State {
        String fingerprint;
        int next;
    }

    private CNArchiveInstallTx() {}

    static File stateFile(File stateRoot, String archiveName) {
        return new File(stateRoot, archiveName + ".extract.tx");
    }

    static void clearState(File stateFile) {
        deleteQuietly(stateFile);
        deleteQuietly(new File(stateFile.getPath() + ".tmp"));
    }

    public static void extract(File archive, File root, File stateFile,
                        Cancel cancel, Progress progress) throws IOException {
        if (archive == null || !archive.isFile()) {
            throw new InstallIOException("Archive is missing: " + archive);
        }
        if (root == null || (!root.isDirectory() && !root.mkdirs() && !root.isDirectory())) {
            throw new InstallIOException("Cannot create extraction root: " + root);
        }
        File stateParent = stateFile == null ? null : stateFile.getParentFile();
        if (stateParent != null && !stateParent.isDirectory()
                && !stateParent.mkdirs() && !stateParent.isDirectory()) {
            throw new InstallIOException("Cannot create extraction state directory: " + stateParent);
        }

        ZipFile zip;
        try {
            zip = new ZipFile(archive);
        } catch (IOException e) {
            throw corrupt("Cannot open ZIP central directory: " + archive, e);
        }

        try {
            ArrayList<ZipEntry> entries = new ArrayList<ZipEntry>();
            Enumeration<? extends ZipEntry> enumeration = zip.entries();
            long totalBytes = 0L;
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                entries.add(entry);
                if (!entry.isDirectory() && entry.getSize() > 0) totalBytes += entry.getSize();
            }
            if (entries.isEmpty()) throw new ZipException("Archive contains no entries: " + archive);
            if (totalBytes >= 256L * 1024L * 1024L
                    && archive.length() > 0
                    && totalBytes / archive.length() > 200L) {
                throw new ZipException("Archive expansion ratio is too high: "
                        + totalBytes + " / " + archive.length());
            }

            String fingerprint = fingerprint(archive, entries);
            State prior = readState(stateFile);
            int next = 0;
            if (prior != null && fingerprint.equals(prior.fingerprint)
                    && prior.next >= 0 && prior.next <= entries.size()) {
                next = prior.next;
                CNLog.i(TAG, "extract-resume-accept file=" + archive.getName()
                        + " entries=" + next + "/" + entries.size());
            } else {
                clearState(stateFile);
            }

            long doneBytes = 0L;
            for (int i = 0; i < next; i++) {
                ZipEntry e = entries.get(i);
                if (!e.isDirectory() && e.getSize() > 0) doneBytes += e.getSize();
            }
            // 这里是**唯一**能事先知道解压后要占多少的时刻（totalBytes 由 zip 目录
            // 逐条累加而来）。先看装不装得下：不查的话，1.4G 的 03 会解压到一半写满，
            // 玩家看到的是一句语焉不详的 extract-paused，而真正该做的是去腾空间。
            // 断点与已解压的内容都保留，腾完接着装。
            CNDiskSpace.require(root, totalBytes - doneBytes, archive.getName() + " 解压");
            if (progress != null) progress.onProgress(next, entries.size(), doneBytes, totalBytes);
            CNLog.i(TAG, "extract-start file=" + archive.getName() + " entries="
                    + entries.size() + " resume=" + next);

            int sinceCheckpoint = 0;
            long bytesSinceCheckpoint = 0L;
            long lastCheckpointNs = System.nanoTime();
            String rootCanonical = root.getCanonicalPath();
            String prefix = rootCanonical + File.separator;

            for (int i = next; i < entries.size(); i++) {
                if (cancel != null && cancel.isCancelled()) {
                    saveState(stateFile, fingerprint, i);
                    throw new CancelledException("Extraction restart requested at entry " + i);
                }
                ZipEntry entry = entries.get(i);
                File out = safeTarget(root, rootCanonical, prefix, entry.getName());
                if (entry.isDirectory()) {
                    if (!out.isDirectory() && !out.mkdirs() && !out.isDirectory()) {
                        throw new InstallIOException("Cannot create directory " + out);
                    }
                } else {
                    writeEntry(zip, entry, out, cancel);
                    if (entry.getSize() > 0) {
                        doneBytes += entry.getSize();
                        bytesSinceCheckpoint += entry.getSize();
                    }
                }

                sinceCheckpoint++;
                long now = System.nanoTime();
                boolean checkpoint = i + 1 == entries.size()
                        || sinceCheckpoint >= CHECKPOINT_ENTRIES
                        || bytesSinceCheckpoint >= CHECKPOINT_BYTES
                        || now - lastCheckpointNs >= CHECKPOINT_NS;
                if (checkpoint) {
                    saveState(stateFile, fingerprint, i + 1);
                    sinceCheckpoint = 0;
                    bytesSinceCheckpoint = 0L;
                    lastCheckpointNs = now;
                    if (progress != null) {
                        progress.onProgress(i + 1, entries.size(), doneBytes, totalBytes);
                    }
                    CNLog.i(TAG, "extract-progress file=" + archive.getName() + " entries="
                            + (i + 1) + "/" + entries.size());
                }
            }

            clearState(stateFile);
            if (progress != null) {
                progress.onProgress(entries.size(), entries.size(), totalBytes, totalBytes);
            }
            CNLog.i(TAG, "extract-complete file=" + archive.getName()
                    + " entries=" + entries.size());
        } finally {
            try { zip.close(); } catch (Throwable ignore) {}
        }
    }

    private static File safeTarget(File root, String rootCanonical, String prefix, String name)
            throws IOException {
        File out = new File(root, name);
        String canonical = out.getCanonicalPath();
        if (!canonical.equals(rootCanonical) && !canonical.startsWith(prefix)) {
            throw new ZipException("ZIP entry escapes extraction root: " + name);
        }
        return out;
    }

    private static void writeEntry(ZipFile zip, ZipEntry entry, File out, Cancel cancel)
            throws IOException {
        File parent = out.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new InstallIOException("Cannot create directory " + parent);
        }
        File temp = new File(out.getPath() + ".cnv-install.tmp");
        File backup = new File(out.getPath() + ".cnv-install.bak");
        recoverInterruptedSwap(out, temp, backup);
        deleteQuietly(temp);

        InputStream in = null;
        FileOutputStream raw = null;
        OutputStream output = null;
        CRC32 crc = new CRC32();
        long copied = 0L;
        try {
            try {
                in = new BufferedInputStream(zip.getInputStream(entry), 65536);
            } catch (IOException e) {
                throw corrupt("Cannot open ZIP entry: " + entry.getName(), e);
            }
            try {
                raw = new FileOutputStream(temp, false);
                output = new BufferedOutputStream(raw, 65536);
            } catch (IOException e) {
                throw new InstallIOException("Cannot create extraction temp: " + temp, e);
            }
            byte[] buf = new byte[65536];
            while (true) {
                if (cancel != null && cancel.isCancelled()) {
                    throw new CancelledException("Extraction restart requested: " + entry.getName());
                }
                int n;
                try {
                    n = in.read(buf);
                } catch (IOException e) {
                    throw corrupt("Cannot inflate ZIP entry: " + entry.getName(), e);
                }
                if (n < 0) break;
                if (n == 0) continue;
                try {
                    output.write(buf, 0, n);
                } catch (IOException e) {
                    throw new InstallIOException("Cannot write extraction temp: " + temp, e);
                }
                crc.update(buf, 0, n);
                copied += n;
            }
            try {
                output.flush();
                raw.getFD().sync();
            } catch (IOException e) {
                throw new InstallIOException("Cannot sync extraction temp: " + temp, e);
            }
        } finally {
            closeQuietly(output);
            closeQuietly(raw);
            closeQuietly(in);
        }

        if (entry.getSize() >= 0 && copied != entry.getSize()) {
            deleteQuietly(temp);
            throw new ZipException("Entry size mismatch: " + entry.getName()
                    + " expected=" + entry.getSize() + " actual=" + copied);
        }
        if (entry.getCrc() >= 0 && crc.getValue() != entry.getCrc()) {
            deleteQuietly(temp);
            throw new ZipException("Entry CRC mismatch: " + entry.getName());
        }

        boolean backedUp = false;
        try {
            if (backup.exists() && !backup.delete() && backup.exists()) {
                throw new InstallIOException("Cannot clear stale backup: " + backup);
            }
            if (out.exists()) {
                if (!out.renameTo(backup)) {
                    throw new InstallIOException("Cannot back up existing file: " + out);
                }
                backedUp = true;
            }
            if (!temp.renameTo(out)) {
                throw new InstallIOException("Cannot promote extraction temp: " + temp);
            }
            deleteQuietly(backup);
        } catch (IOException e) {
            if (!out.exists() && backedUp && backup.exists()) {
                try { backup.renameTo(out); } catch (Throwable ignore) {}
            }
            deleteQuietly(temp);
            throw e;
        }
    }

    private static void recoverInterruptedSwap(File out, File temp, File backup)
            throws InstallIOException {
        if (backup.exists() && !out.exists()) {
            if (!backup.renameTo(out)) {
                throw new InstallIOException("Cannot restore interrupted extraction backup: " + out);
            }
        } else if (backup.exists()) {
            deleteQuietly(backup);
        }
        deleteQuietly(temp);
    }

    private static String fingerprint(File archive, ArrayList<ZipEntry> entries)
            throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            update(md, String.valueOf(archive.length()));
            for (int i = 0; i < entries.size(); i++) {
                ZipEntry e = entries.get(i);
                update(md, "\n");
                update(md, e.getName());
                update(md, ":" + e.getSize() + ":" + e.getCompressedSize()
                        + ":" + e.getCrc() + ":" + e.getMethod());
            }
            return hex(md.digest());
        } catch (Exception e) {
            throw new IOException("Cannot fingerprint ZIP central directory", e);
        }
    }

    private static void update(MessageDigest md, String s) throws Exception {
        md.update(s.getBytes("UTF-8"));
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (int i = 0; i < bytes.length; i++) {
            sb.append(String.format(Locale.US, "%02x", bytes[i] & 0xff));
        }
        return sb.toString();
    }

    private static State readState(File file) {
        if (file == null || !file.isFile() || file.length() > 4096) return null;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            if (!SCHEMA.equals(reader.readLine())) return null;
            State state = new State();
            state.fingerprint = reader.readLine();
            String next = reader.readLine();
            state.next = Integer.parseInt(next == null ? "-1" : next.trim());
            return state;
        } catch (Throwable t) {
            return null;
        } finally {
            closeQuietly(reader);
        }
    }

    private static void saveState(File file, String fingerprint, int next)
            throws InstallIOException {
        if (file == null) return;
        File temp = new File(file.getPath() + ".tmp");
        FileOutputStream raw = null;
        Writer writer = null;
        try {
            raw = new FileOutputStream(temp, false);
            writer = new OutputStreamWriter(raw, "UTF-8");
            writer.write(SCHEMA); writer.write('\n');
            writer.write(fingerprint); writer.write('\n');
            writer.write(String.valueOf(next)); writer.write('\n');
            writer.flush();
            raw.getFD().sync();
            closeQuietly(writer); writer = null;
            closeQuietly(raw); raw = null;
            if (file.exists() && !file.delete()) {
                throw new InstallIOException("Cannot replace extraction state: " + file);
            }
            if (!temp.renameTo(file)) {
                throw new InstallIOException("Cannot promote extraction state: " + file);
            }
        } catch (IOException e) {
            if (e instanceof InstallIOException) throw (InstallIOException) e;
            throw new InstallIOException("Cannot save extraction state: " + file, e);
        } finally {
            closeQuietly(writer);
            closeQuietly(raw);
            deleteQuietly(temp);
        }
    }

    private static ZipException corrupt(String message, Throwable cause) {
        ZipException out = new ZipException(message);
        try { out.initCause(cause); } catch (Throwable ignore) {}
        return out;
    }

    private static void deleteQuietly(File file) {
        if (file == null || !file.exists()) return;
        try { file.delete(); } catch (Throwable ignore) {}
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (Throwable ignore) {}
    }
}
