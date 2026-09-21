package io.kamihama.magianative;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Player route preference; independent of destructive per-file restart generations. */
public final class CNDownloadRoute {
    private CNDownloadRoute() {}
    private static final class Choice {
        final long generation;
        final String base;
        Choice(long generation, String base) { this.generation=generation; this.base=base; }
    }
    private static volatile Choice choice = new Choice(0L, "");
    private static final ThreadLocal<Plan> transfer = new ThreadLocal<Plan>();

    public static final class Plan {
        final Choice choice;
        public final CNMirrors.Mirror mirror;
        boolean switchable = true;
        Plan(Choice choice, CNMirrors.Mirror mirror) { this.choice=choice; this.mirror=mirror; }
    }
    public static final class Changed extends IOException {
        Changed() { super("玩家切换线路：保留下载状态，重新连接所选线路"); }
    }

    public static synchronized boolean select(String base) {
        String wanted=base==null?"":base;
        if (!wanted.isEmpty()) {
            boolean found=false;
            for (CNMirrors.Mirror m : CNMirrors.selectable()) if (m.base.equals(wanted)) found=true;
            if (!found) return false;
        }
        if (!choice.base.equals(wanted)) choice=new Choice(choice.generation+1L,wanted);
        return true;
    }
    public static String selectedBase() { return choice.base; }
    public static String describe() {
        String base=choice.base;
        for (CNMirrors.Mirror m : CNMirrors.selectable()) if (m.base.equals(base)) return m.name;
        return "自动选择";
    }

    /** Snapshot the choice and mirror together so a concurrent click cannot be lost. */
    public static Plan plan(List<CNMirrors.Mirror> candidates, int attempt) {
        Choice selected=choice;
        List<CNMirrors.Mirror> ordered=new ArrayList<CNMirrors.Mirror>();
        for (CNMirrors.Mirror m : CNMirrors.selectable()) {
            if (m.base.equals(selected.base)) { ordered.add(m); break; }
        }
        for (CNMirrors.Mirror m : candidates) {
            if (m.enabled && (ordered.isEmpty() || !ordered.get(0).base.equals(m.base))) ordered.add(m);
        }
        if (ordered.isEmpty()) throw new IllegalStateException("没有可用下载线路");
        return new Plan(selected,ordered.get(Math.max(0,attempt-1)%ordered.size()));
    }

    public static void enter(Plan plan) { transfer.set(plan); }
    public static void leave() { transfer.remove(); }
    public static void deferUnidentifiedTransfer() {
        Plan active=transfer.get();
        if (active!=null) active.switchable=false;
    }
    public static boolean changed() {
        Plan active=transfer.get();
        return active!=null && active.switchable && active.choice.generation!=choice.generation;
    }
    public static void check() throws Changed { if (changed()) throw new Changed(); }

    /** Full publisher identity, not the shortened cache-busting URL token. */
    public static String hotResumeKey(CNHotUpdateValidate.VerMeta meta) {
        if (!CNUpdateSources.validHot(meta)) return null;
        return "cn-hot:"+meta.version+":"+meta.size+":"+meta.md5.toLowerCase(java.util.Locale.US);
    }
}
