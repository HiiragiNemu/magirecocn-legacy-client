package io.kamihama.magianative;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Exercises the real transaction and cumulative-layer code against the first public candidate. */
public final class JsDeltaInstallTest {
    static int count;
    static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        System.out.println("PASS " + name); count++;
    }
    static byte[] member(File zip, String name) throws Exception {
        ZipFile z = new ZipFile(zip);
        try { InputStream in = z.getInputStream(z.getEntry(name));
            try { ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[65536]; int n;
                while ((n = in.read(b)) != -1) out.write(b, 0, n); return out.toByteArray();
            } finally { in.close(); }
        } finally { z.close(); }
    }
    public static void main(String[] args) throws Exception {
        File base = new File(args[0]), delta = new File(args[1]), root = new File(args[2]);
        String path = "madomagi/resource/image_native/scene/top/toppage_bg_020.png";
        byte[] before = member(base, path), after = member(delta, path);
        root.mkdirs();
        check(!Arrays.equals(before, after), "test asset actually differs from JS102");
        check(CNCNDownloadUI.FILE_NAMES.length == 16, "16 UI package slots");
        check(CNCNDownloadUI.FILE_NAMES[15].equals(CNJsDelta.NAME), "delta is last slot");
        check(CNDownloaderFix.isHotSlot(15) && !CNDownloaderFix.usesChunkManifest(CNJsDelta.NAME), "delta uses version identity not static manifest");
        check(CNDownloaderFix.parseFinalFlag("schema=2\narchives=15\n"), "old 15-package completion preserved");
        check(CNDownloaderFix.parseFinalFlag("schema=2\narchives=16\n"), "new completion supported");
        CNJsDelta.validate(delta, 102, 1);
        for (int wrong : new int[]{0, 101, 103}) {
            boolean rejected = false;
            try { CNJsDelta.validate(delta, wrong, 1); } catch (Exception e) { rejected = true; }
            check(rejected, "reject different base " + wrong);
        }
        boolean rejected = false;
        try { CNJsDelta.validate(delta, 102, 2); } catch (Exception e) { rejected = true; }
        check(rejected, "reject wrong delta version");
        CNHotUpdateTx.apply(base, root, "js");
        check(Arrays.equals(before, Files.readAllBytes(new File(root,path).toPath())), "baseline real JS102 installation");
        CNJsDelta.apply(delta, root, 102, 1);
        check(Arrays.equals(after, Files.readAllBytes(new File(root,path).toPath())), "delta replaces exact scene/top PNG");
        check(CNJsDelta.installedMatches(root,102,1), "installed manifest and payload match");
        String plist = path.replace(".png", ".plist");
        check(Arrays.equals(member(base,plist), Files.readAllBytes(new File(root,plist).toPath())), "paired plist unchanged");
        CNHotUpdateTx.apply(base, root, "js");
        check(!CNJsDelta.installedMatches(root,102,1), "old package overwrite detected");
        CNJsDelta.reapplyCached(root,102);
        check(Arrays.equals(after, Files.readAllBytes(new File(root,path).toPath())), "manual base reinstall restored from cached delta without downloading");
        CNJsDelta.reapplyCached(root,102);
        check(CNJsDelta.installedMatches(root,102,1), "repeat apply is idempotent");
        System.out.println("JS_DELTA_INSTALL_PASS " + count);
    }
}
