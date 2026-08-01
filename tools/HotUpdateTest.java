import io.kamihama.magianative.CNHotUpdate;
import io.kamihama.magianative.CNCNDownloadUI;
import java.io.*; import java.security.MessageDigest;

/** 验证 CNHotUpdate 的非主线分支（直连单线程续传）与短读处理。 */
public class HotUpdateTest {
    static int pass=0, fail=0;
    static void check(String n, boolean ok, String d){
        if(ok){pass++;System.out.println("  ✅ "+n+(d.isEmpty()?"":"  — "+d));}
        else {fail++;System.out.println("  ❌ "+n+"  — "+d);} }
    static String sha(File f) throws Exception {
        MessageDigest m=MessageDigest.getInstance("SHA-256");
        FileInputStream i=new FileInputStream(f); byte[] b=new byte[65536]; int n;
        while((n=i.read(b))>=0) m.update(b,0,n); i.close();
        StringBuilder s=new StringBuilder(); for(byte x:m.digest()) s.append(String.format("%02x",x));
        return s.toString(); }
    public static void main(String[] a) throws Exception {
        String base=a[0], sha=a[1]; long size=Long.parseLong(a[2]);
        new File("hot").mkdirs();

        System.out.println("\n[1] 非主线地址 -> 直连单线程下载");
        File t=new File("hot/x.zip"); t.delete(); new File("hot/x.zip.part").delete();
        boolean ok=CNHotUpdate.download(base,"hot/x.zip","显示名",3);
        check("返回 true", ok, "");
        check("内容正确", sha(t).equals(sha), "sha="+sha(t).substring(0,12));
        check("UI 槽位标记为完成", CNCNDownloadUI.fileStatus[3]==2, "status="+CNCNDownloadUI.fileStatus[3]);

        System.out.println("\n[2] 目标已存在 -> 直接算完成，不重复下载");
        long mtime=t.lastModified();
        CNCNDownloadUI.fileStatus[3]=0;
        ok=CNHotUpdate.download(base,"hot/x.zip","显示名",3);
        check("返回 true", ok, "");
        check("文件未被重写", t.lastModified()==mtime, "");
        check("仍标记为完成", CNCNDownloadUI.fileStatus[3]==2, "");

        System.out.println("\n[3] 服务端提前断流 -> 报失败，不提交残缺文件");
        File t2=new File("hot/y.zip"); t2.delete(); new File("hot/y.zip.part").delete();
        ok=CNHotUpdate.download(base+"?truncate=65536","hot/y.zip","显示名",4);
        check("返回 false", !ok, "");
        check("未生成成品", !t2.exists(), "");
        check("保留残片供续传", new File("hot/y.zip.part").exists()
              && new File("hot/y.zip.part").length()>0,
              "残片="+new File("hot/y.zip.part").length());

        System.out.println("\n[4] 承接上一步残片续传补齐");
        long before=new File("hot/y.zip.part").length();
        ok=CNHotUpdate.download(base,"hot/y.zip","显示名",4);
        check("返回 true", ok, "");
        check("内容正确", sha(t2).equals(sha), "sha="+sha(t2).substring(0,12));
        check("确实是续传（起点>0）", before>0 && before<size, "续传起点="+before+" 总长="+size);

        System.out.println("\n通过 "+pass+" / 失败 "+fail);
        if(fail>0) System.exit(1);
    }
}
