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
    private static final java.util.concurrent.ConcurrentHashMap<Integer,String> fileRoutes =
            new java.util.concurrent.ConcurrentHashMap<Integer,String>();

    static boolean accelerated(CNMirrors.Mirror m) {
        return m.base.equals(CNEndpoints.LEGACY_EDGEONE_BASE) || m.base.equals(CNEndpoints.LEGACY_ESA_BASE);
    }
    public static void clearFile(int index) { fileRoutes.remove(index); }
    public static void recordFile(int index, String route) {
        if (index>=0 && route!=null && !route.isEmpty()) fileRoutes.put(index,route);
    }
    public static void recordUrl(int index, String url) {
        for (CNMirrors.Mirror m : CNUpdateSources.mirrors(null)) {
            if (url.startsWith(m.base)) { recordFile(index,shortName(m)); return; }
        }
    }
    static String shortName(CNMirrors.Mirror m) {
        if (m.base.equals(CNEndpoints.LEGACY_EDGEONE_BASE)) return "CyberNova EdgeOne";
        if (m.base.equals(CNEndpoints.LEGACY_ESA_BASE)) return "CyberNova ESA";
        if (!CNEndpoints.PRIMARY_BASE_OVERRIDE.isEmpty() && m.base.equals(CNEndpoints.PRIMARY_BASE_OVERRIDE)) return "MadeInMagius Cloudflare";
        if (!CNEndpoints.SECONDARY_BASE_OVERRIDE.isEmpty() && m.base.equals(CNEndpoints.SECONDARY_BASE_OVERRIDE)) return "MadeInMagius GitHub";
        return m.name;
    }
    /** Display ownership without changing mirror identity, priority or version eligibility. */
    public static String displayName(CNMirrors.Mirror m) {
        String label=shortName(m);
        if (accelerated(m)) return label+" 加速";
        if (!CNEndpoints.PRIMARY_BASE_OVERRIDE.isEmpty() && m.base.equals(CNEndpoints.PRIMARY_BASE_OVERRIDE)) return label+" 中转";
        if (!CNEndpoints.SECONDARY_BASE_OVERRIDE.isEmpty() && m.base.equals(CNEndpoints.SECONDARY_BASE_OVERRIDE)) return label+" 直连";
        return label;
    }
    public static String fileLabel(int index, int status) {
        if (status==4) return "本轮未下载";
        String label=fileRoutes.get(index);
        if (label==null) return status==2 ? "本地已安装" : "待连接";
        return status==3 ? "失败 · "+label : label;
    }

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
        for (CNMirrors.Mirror m : CNMirrors.selectable()) if (m.base.equals(base)) return displayName(m);
        return "自动选择";
    }

    /** Snapshot the choice and mirror together so a concurrent click cannot be lost. */
    public static Plan plan(List<CNMirrors.Mirror> candidates, int attempt) {
        Choice selected=choice;
        List<CNMirrors.Mirror> ordered=new ArrayList<CNMirrors.Mirror>();
        // Manual preference must not inject an old or unidentified hot-update publisher.
        long now=System.nanoTime();
        boolean hasHealthy=false;
        for (CNMirrors.Mirror m : candidates) if (m.enabled && m.cooldownUntilNs<=now) hasHealthy=true;
        for (CNMirrors.Mirror m : candidates) {
            if (m.enabled && (!hasHealthy || m.cooldownUntilNs<=now) && m.base.equals(selected.base)) { ordered.add(m); break; }
        }
        for (CNMirrors.Mirror m : candidates) {
            if (m.enabled && (!hasHealthy || m.cooldownUntilNs<=now)
                    && (ordered.isEmpty() || !ordered.get(0).base.equals(m.base))) ordered.add(m);
        }
        if (ordered.isEmpty()) throw new IllegalStateException("没有可用下载线路");
        // A failed route is already removed by cooldown. Applying attempt % size again
        // skips the still-healthy second CDN and prematurely jumps to GitHub/Cloudflare.
        return new Plan(selected,ordered.get(hasHealthy ? 0 : Math.max(0,attempt-1)%ordered.size()));
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
