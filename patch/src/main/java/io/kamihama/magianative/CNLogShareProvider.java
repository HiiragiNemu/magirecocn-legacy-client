package io.kamihama.magianative;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 「分享日志」的只读 ContentProvider：把 {@code cacheDir/share/} 里的日志包
 * 以 read 权限临时授权给系统分享面板选中的 App。
 *
 * <p>为什么不用 androidx 的 FileProvider：本补丁的编译 classpath 只有
 * android.jar + OkHttp/Okio（与 CI 的 DEPS 一致），androidx 不在其中，引用了
 * 编译期就挂。这里自己写一个极小的 provider——只服务 {@code cacheDir/share/}
 * 下的文件，且严格限制文件名，不开 files/ 与存储卡。
 *
 * <p>授权：manifest 里 {@code android:grantUriPermissions="true"} +
 * {@code exported="false"}。分享方在 ACTION_SEND 上带
 * {@code FLAG_GRANT_READ_URI_PERMISSION}，系统据此给接收方临时读权限——
 * 这是「非导出 provider + 一次性授权」的标准做法，接收方拿不到本应用的其它内容。
 *
 * <p>URI 形如：{@code content://io.kamihama.totentanz.logshare/<文件名>}。
 */
public final class CNLogShareProvider extends ContentProvider {
    /** 与 AndroidManifest.xml 里 provider 的 authorities 保持一致。 */
    public static final String AUTHORITY = "io.kamihama.totentanz.logshare";

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        return "text/plain";
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        File f = resolveShareFile(name);
        if (f == null) throw new FileNotFoundException(name);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** 只允许返回 {@code cacheDir/share/} 下确确实实的文件；否则 null。 */
    private File resolveShareFile(String name) {
        if (name == null || name.length() == 0) return null;
        // 只接受纯文件名，拒绝路径分隔符，杜绝目录穿越
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.contains("..")) return null;
        File dir = new File(getContext().getCacheDir(), "share");
        File f = new File(dir, name);
        if (!f.isFile()) return null;
        // 防符号链接把指针带到别处：只认 share 目录内的普通文件
        try {
            if (!f.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) {
                return null;
            }
        } catch (Throwable t) {
            return null;
        }
        return f;
    }

    // ---- 其余抽象方法：本 provider 只服务 openFile，这些不实现 ----

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) {
        return 0;
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }
}
