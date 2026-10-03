package io.kamihama.magianative;

import android.content.Context;
import android.content.SharedPreferences;

/** Installed provenance is recorded only after verified content commits, not when a route connects. */
final class CNPackageReceipt {
    private CNPackageReceipt() {}
    private static final java.util.concurrent.ConcurrentHashMap<Integer,Integer> targets =
            new java.util.concurrent.ConcurrentHashMap<Integer,Integer>();
    private static SharedPreferences prefs() {
        Context c = CNRestClientActivity.appContext();
        return c == null ? null : c.getSharedPreferences("CNPackageProvenance", Context.MODE_PRIVATE);
    }
    private static String key(int index) {
        return index >= 0 && index < CNCNDownloadUI.FILE_NAMES.length ? CNCNDownloadUI.FILE_NAMES[index] : "";
    }
    static void target(int index, int version) { if (index >= 0 && version > 0) targets.put(index, version); }
    static int installedVersion(int index) {
        String file = key(index);
        String field = file.equals("cn_scenario_update.zip") ? "scenario_version"
                : file.equals("cn_js_update.zip") ? "js_version"
                : file.equals("cn_js_delta.zip") ? "js_delta_version" : "";
        if (field.isEmpty()) return 0; // Fixed packages have no publisher-assigned numeric revision.
        try {
            Context c = CNRestClientActivity.appContext();
            return c == null ? -1 : c.getSharedPreferences("MagiaCN", Context.MODE_PRIVATE).getInt(field, -1);
        } catch (Throwable t) { return -1; }
    }
    static void installed(int index, String explicitRoute) {
        try {
            String name = key(index); SharedPreferences p = prefs();
            if (name.isEmpty() || p == null) return;
            String route = explicitRoute == null ? CNDownloadRoute.currentFile(index) : explicitRoute;
            if (route == null || route.isEmpty()) route = "来源未记录";
            if (!p.edit().putString(name + ".route", route)
                    .putInt(name + ".version", installedVersion(index)).commit())
                CNLog.w("资源身份", "来源显示记录未保存：" + name);
        } catch (Throwable t) { CNLog.w("资源身份", "来源显示记录未保存", t); }
    }
    static void installed(String name, boolean offline) {
        for (int i=0;i<CNCNDownloadUI.FILE_NAMES.length;i++)
            if (CNCNDownloadUI.FILE_NAMES[i].equals(name)) { installed(i, offline ? "离线导入" : null); return; }
    }
    static String label(int index, int status) {
        int version = installedVersion(index);
        String route = null;
        try {
            SharedPreferences p = prefs(); String name=key(index);
            if (p != null && p.getInt(name + ".version", -2) == version)
                route = p.getString(name + ".route", "来源未记录");
        } catch (Throwable ignored) {}
        if (status == 1) {
            Integer target = targets.get(index);
            return CNDownloadPresentation.packageIdentity(target == null ? (version == 0 ? 0 : -1) : target,
                    CNDownloadRoute.currentFile(index), true);
        }
        return CNDownloadPresentation.packageIdentity(version, route, false);
    }
}
