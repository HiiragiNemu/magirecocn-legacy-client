package io.kamihama.magianative;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

/** 在应用内显示下载，完整校验后交给系统覆盖安装；权限与安装确认均由系统展示。 */
public final class CNApkUpdateActivity extends Activity {
    private static final String TAG="CNApkUpdate", EXTRA="update_metadata";
    private static final int PERMISSION=71,INSTALL=72;
    private static final Handler UI=new Handler(Looper.getMainLooper());
    private static Job current;
    private Job job;
    private TextView status,action;
    private ProgressBar progress;
    private boolean installing,askingPermission;

    public static void start(Activity act,JSONObject metadata) {
        try {
            if(!CNVersionCheck.validClient(metadata))throw new IOException("更新信息不完整，请重启后重试");
            Intent i=new Intent(act,CNApkUpdateActivity.class);i.putExtra(EXTRA,metadata.toString());act.startActivity(i);
        }catch(Exception e){CNLog.e(TAG,"打开应用内更新失败",e);Toast.makeText(act,"打开更新失败，请重试",Toast.LENGTH_LONG).show();}
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);buildUi(this);
        try {
            JSONObject meta=new JSONObject(getIntent().getStringExtra(EXTRA));
            if(!CNVersionCheck.validClient(meta))throw new IOException("更新信息已失效，请返回重新检查");
            synchronized(CNApkUpdateActivity.class) {
                if(current==null || !current.hash.equalsIgnoreCase(meta.getString("sha256"))) {
                    if(current!=null)current.cancel();current=new Job(getApplicationContext(),meta);
                }
                job=current;job.owner=new WeakReference<CNApkUpdateActivity>(this);
            }
            ((TextView)findViewById(1001)).setText("客户端更新 · v"+job.version);
            render(this);if(!job.running && job.file==null && job.error==null)launch(job);
        }catch(Exception e){status.setText(e.getMessage());action.setEnabled(false);}
    }
    @Override protected void onDestroy() {
        if(job!=null && job.owner.get()==this) {
            job.owner.clear();if(isFinishing())job.cancel();
        }
        super.onDestroy();
    }
    @Override public void onBackPressed(){if(job!=null)job.cancel();finish();}
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==PERMISSION) {
            askingPermission=false;
            if(Build.VERSION.SDK_INT>=26 && getPackageManager().canRequestPackageInstalls())install(this);
            else {status.setText("安装权限尚未开启。开启后点击「安装更新」继续，已下载的 APK 会保留。");action.setEnabled(true);action.setText("安装更新");}
        } else if(request==INSTALL) {
            installing=false;status.setText(result==RESULT_OK?"安装已完成，请重新打开游戏。":"安装尚未完成，可点击「安装更新」重试。");action.setEnabled(true);action.setText("安装更新");
        }
    }
    private static int dp(Activity a,int n){return (int)(a.getResources().getDisplayMetrics().density*n+0.5f);}
    private static void buildUi(CNApkUpdateActivity a) {
        FrameLayout root=new FrameLayout(a);root.setBackgroundColor(0x99000000);
        LinearLayout panel=new LinearLayout(a);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(a,24),dp(a,22),dp(a,24),dp(a,18));
        GradientDrawable bg=new GradientDrawable();bg.setColor(Color.WHITE);bg.setCornerRadius(dp(a,16));panel.setBackground(bg);
        int width=Math.min(dp(a,400),a.getResources().getDisplayMetrics().widthPixels-dp(a,32));
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(width,ViewGroup.LayoutParams.WRAP_CONTENT,Gravity.CENTER);root.addView(panel,lp);
        TextView title=new TextView(a);title.setId(1001);title.setText("客户端更新");title.setTextSize(18);title.setTypeface(null,Typeface.BOLD);title.setTextColor(0xffd8308b);panel.addView(title);
        a.status=new TextView(a);a.status.setTextColor(0xff493652);a.status.setTextSize(14);a.status.setPadding(0,dp(a,16),0,dp(a,12));a.status.setText("准备下载…");panel.addView(a.status);
        a.progress=new ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal);a.progress.setMax(1000);panel.addView(a.progress,new LinearLayout.LayoutParams(-1,dp(a,10)));
        TextView help=new TextView(a);help.setText("下载与校验在应用内完成。随后由安卓确认覆盖安装，保留存档和已安装资源。");help.setTextSize(12);help.setTextColor(0xff796783);help.setPadding(0,dp(a,14),0,dp(a,12));panel.addView(help);
        LinearLayout buttons=new LinearLayout(a);buttons.setGravity(Gravity.END);panel.addView(buttons);
        TextView cancel=button(a,"取消",false);cancel.setOnClickListener(new Click(a,false));buttons.addView(cancel);
        a.action=button(a,"正在下载",true);a.action.setEnabled(false);a.action.setOnClickListener(new Click(a,true));
        LinearLayout.LayoutParams al=new LinearLayout.LayoutParams(-2,-2);al.leftMargin=dp(a,12);buttons.addView(a.action,al);a.setContentView(root);
    }
    private static TextView button(Activity a,String text,boolean primary) {
        TextView v=new TextView(a);v.setText(text);v.setTextSize(14);v.setTextColor(primary?Color.WHITE:0xff493652);v.setPadding(dp(a,16),dp(a,12),dp(a,16),dp(a,12));
        GradientDrawable bg=new GradientDrawable();bg.setColor(primary?0xffd8308b:0xfff4eaf3);bg.setCornerRadius(dp(a,12));v.setBackground(bg);return v;
    }
    private static final class Click implements View.OnClickListener {
        final CNApkUpdateActivity a;final boolean go;Click(CNApkUpdateActivity a,boolean go){this.a=a;this.go=go;}
        public void onClick(View v) {
            if(!go){if(a.job!=null)a.job.cancel();a.finish();return;}
            if(a.job==null)return;
            if(a.job.file!=null)install(a);else if(!a.job.running)launch(a.job);
        }
    }
    private static void render(CNApkUpdateActivity a) {
        Job j=a.job;if(j==null || a.isFinishing() || a.installing || a.askingPermission)return;
        a.progress.setProgress((int)(Math.min(j.done,j.size)*1000/j.size));
        if(j.error!=null){a.status.setText(j.error+"\n可重试；不会替换为旧版本文件。");a.action.setText("重试下载");a.action.setEnabled(true);}
        else if(j.file!=null){a.status.setText("APK 大小、SHA-256、包名和签名校验通过，准备安装。");a.action.setText("安装更新");a.action.setEnabled(true);if(!j.offered){j.offered=true;install(a);}}
        else {a.status.setText(String.format(Locale.CHINA,"正在下载 · 线路 %d\n%.1f%%   %.1f / %.1f MB   %.2f MB/s",j.route,100.0*j.done/j.size,j.done/1048576.0,j.size/1048576.0,j.speed/1048576.0));a.action.setText(j.done==j.size?"正在校验":"正在下载");a.action.setEnabled(false);}
    }
    private static final class Refresh implements Runnable {
        final Job job;Refresh(Job j){job=j;}
        public void run(){CNApkUpdateActivity a=job.owner.get();if(a!=null && a.job==job)render(a);}
    }
    private static void launch(Job j) {
        j.cancelled=false;j.error=null;j.done=0;j.offered=false;j.running=true;new Thread(new Download(j),"cn-apk-update").start();
    }
    private static final class Job implements CNApkDownload.Observer,CNApkDownload.Cancellation {
        final Context app;final String version,hash;final long size;final List<String> urls=new ArrayList<String>();
        volatile boolean cancelled,running,offered;volatile long done,speed;volatile int route;
        volatile File file;volatile String error;volatile HttpURLConnection connection;
        volatile WeakReference<CNApkUpdateActivity> owner=new WeakReference<CNApkUpdateActivity>(null);
        Job(Context app,JSONObject meta)throws Exception {
            this.app=app;version=meta.getString("version");hash=meta.getString("sha256").toLowerCase(Locale.US);size=meta.getLong("size");
            urls.add(meta.getString("apk_url"));JSONArray extras=meta.optJSONArray("_apk_urls");
            if(extras!=null)for(int i=0;i<extras.length();i++){String u=extras.getString(i);if(CNSafeLink.reject(u)==null && !urls.contains(u))urls.add(u);}
        }
        public boolean cancelled(){return cancelled;}
        void cancel(){cancelled=true;HttpURLConnection c=connection;if(c!=null)c.disconnect();}
        public void update(long d,long total,long bps,int route){done=d;speed=bps;this.route=route;UI.post(new Refresh(this));}
    }
    private static final class HttpTransport implements CNApkDownload.Transport {
        final Job j;HttpTransport(Job j){this.j=j;}
        public CNApkDownload.Response open(String url)throws IOException {
            if(CNSafeLink.reject(url)!=null)throw new IOException("更新地址不受支持");
            HttpURLConnection c=CNHttp.open(new URL(url),false,15000,20000);j.connection=c;
            try {
                c.setRequestProperty("Accept-Encoding","identity");c.setRequestProperty("Cache-Control","no-cache");
                if(c.getResponseCode()!=200 || !"https".equalsIgnoreCase(c.getURL().getProtocol()))throw new IOException("更新线路响应异常");
                return new HttpResponse(c);
            }catch(IOException e){c.disconnect();throw e;}
        }
    }
    private static final class HttpResponse implements CNApkDownload.Response {
        final HttpURLConnection c;HttpResponse(HttpURLConnection c){this.c=c;}
        public long length(){try{return Long.parseLong(c.getHeaderField("Content-Length"));}catch(Exception e){return -1;}}
        public InputStream stream()throws IOException{return new BufferedInputStream(c.getInputStream(),65536);}
        public void close(){c.disconnect();}
    }
    private static final class Download implements Runnable {
        final Job j;Download(Job j){this.j=j;}
        public void run() {
            try {
                CNLog.i(TAG,"应用内下载开始 version="+j.version+" routes="+j.urls.size());
                File f=CNApkDownload.fetch(j.urls,new File(j.app.getCacheDir(),"apk-update"),j.size,j.hash,new HttpTransport(j),j,j);
                verifyPackage(j.app,f);j.file=f;CNLog.i(TAG,"APK 校验通过 version="+j.version+" bytes="+j.size+" sha256="+j.hash);
            }catch(Exception e){j.error=j.cancelled?"已取消下载":String.valueOf(e.getMessage());CNLog.w(TAG,"应用内更新未完成: "+j.error);}
            finally {j.running=false;j.connection=null;UI.post(new Refresh(j));}
        }
    }
    static void verifyPackage(Context app,File f)throws Exception {
        PackageManager pm=app.getPackageManager();PackageInfo candidate=pm.getPackageArchiveInfo(f.getPath(),PackageManager.GET_SIGNATURES);
        PackageInfo installed=pm.getPackageInfo(app.getPackageName(),PackageManager.GET_SIGNATURES);
        if(candidate==null || !app.getPackageName().equals(candidate.packageName))throw new IOException("APK 包名不匹配");
        long oldCode=installed.versionCode,newCode=candidate.versionCode;
        if(Build.VERSION.SDK_INT>=28){oldCode=installed.getLongVersionCode();newCode=candidate.getLongVersionCode();}
        if(newCode<oldCode)throw new IOException("APK 系统版本低于已安装版本");
        if(candidate.signatures==null || installed.signatures==null || candidate.signatures.length==0
           || !new HashSet<Signature>(Arrays.asList(candidate.signatures)).equals(new HashSet<Signature>(Arrays.asList(installed.signatures))))
            throw new IOException("APK 签名与已安装客户端不匹配");
    }
    private static void install(CNApkUpdateActivity a) {
        Job j=a.job;if(j==null || j.file==null || a.installing || a.askingPermission)return;
        try {
            if(Build.VERSION.SDK_INT>=26 && !a.getPackageManager().canRequestPackageInstalls()) {
                a.askingPermission=true;a.status.setText("请在安卓设置中允许此应用安装更新，然后返回。");
                a.startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+a.getPackageName())),PERMISSION);
                CNLog.i(TAG,"等待用户允许安装来源");return;
            }
            Uri uri=Uri.parse("content://"+a.getPackageName()+".updateapk/"+j.file.getName());
            Intent i=new Intent(Intent.ACTION_INSTALL_PACKAGE);i.setDataAndType(uri,"application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);i.setClipData(ClipData.newRawUri("client update",uri));i.putExtra(Intent.EXTRA_RETURN_RESULT,true);
            a.installing=true;a.action.setEnabled(false);a.status.setText("已交给安卓安装器，请确认覆盖更新。");
            a.startActivityForResult(i,INSTALL);CNLog.i(TAG,"已打开系统安装确认 version="+j.version);
        }catch(Exception e){a.installing=false;a.askingPermission=false;a.status.setText("打开系统安装失败："+e.getMessage());a.action.setEnabled(true);CNLog.e(TAG,"系统安装未启动",e);}
    }
}
