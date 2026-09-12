package io.kamihama.magianative;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** 系统安装器只获一次性读权限；不暴露存档，也不复用日志分享的目录。 */
public final class CNApkProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    @Override public String getType(Uri uri){return "application/vnd.android.package-archive";}
    private File resolve(Uri uri) throws FileNotFoundException {
        String name=uri.getLastPathSegment();
        if(!uri.getAuthority().equals(getContext().getPackageName()+".updateapk")
           || uri.getPathSegments().size()!=1 || name==null || !name.matches("[0-9a-f]{64}\\.apk"))
            throw new FileNotFoundException("无效的更新文件");
        File dir=new File(getContext().getCacheDir(),"apk-update"),f=new File(dir,name);
        try { if(!f.isFile() || !f.getCanonicalFile().getParentFile().equals(dir.getCanonicalFile()))throw new FileNotFoundException(name); }
        catch(java.io.IOException e){throw new FileNotFoundException("更新文件不存在");}
        return f;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if(!"r".equals(mode))throw new FileNotFoundException("更新文件只读");
        return ParcelFileDescriptor.open(resolve(uri),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort) {
        try {
            File f=resolve(uri);String[] cols=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
            MatrixCursor c=new MatrixCursor(cols,1);Object[] row=new Object[cols.length];
            for(int i=0;i<cols.length;i++) {
                if(OpenableColumns.DISPLAY_NAME.equals(cols[i]))row[i]="client-update.apk";
                if(OpenableColumns.SIZE.equals(cols[i]))row[i]=f.length();
            }
            c.addRow(row);return c;
        }catch(FileNotFoundException e){return null;}
    }
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException("只读");}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException("只读");}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException("只读");}
}
