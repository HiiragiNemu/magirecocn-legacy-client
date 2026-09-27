package io.kamihama.magianative;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Independent update discovery; a successful stale response never ends the search. */
public final class CNUpdateSources {
    static final String CLIENT_META = "magireco-latest-legacy-client.version.json";
    static final String CLIENT_APK = "magireco-latest-legacy-client.apk";
    private static final String TAG = "CNUpdateSources";
    static final long QUERY_BUDGET_MS = 6500L;

    private CNUpdateSources() {}

    /** Base packages use the CDN directory; hot packages use matching publisher identities only. */
    static List<CNMirrors.Mirror> downloadMirrors(CNHotUpdateValidate.VerMeta expected) {
        LinkedHashMap<String,CNMirrors.Mirror> available = new LinkedHashMap<String,CNMirrors.Mirror>();
        for (CNMirrors.Mirror m : CNMirrors.selectable()) available.put(m.base,m);
        // 配置源不可达时，基础包也必须保留独立公开兜底；热更仍受下方身份集合约束。
        if (CNPublicResources.ENABLED) {
            for (CNMirrors.Mirror m : mirrors(null)) if (!available.containsKey(m.base)) available.put(m.base,m);
        }
        if (expected != null) for (CNMirrors.Mirror m : mirrors(expected.sourceBase)) {
            if (!available.containsKey(m.base)) available.put(m.base,m);
        }
        List<CNMirrors.Mirror> fast = new ArrayList<CNMirrors.Mirror>();
        List<CNMirrors.Mirror> fallback = new ArrayList<CNMirrors.Mirror>();
        for (CNMirrors.Mirror m : available.values()) {
            if (!m.enabled) continue;
            if (expected != null && expected.sourceBases != null && !expected.sourceBases.contains(m.base)) continue;
            (CNDownloadRoute.accelerated(m) ? fast : fallback).add(m);
        }
        fast.addAll(fallback);
        CNDownloadRoute.sortSources(fast);
        return fast;
    }

    /** Built-in publishers survive replacement of the remote mirror list by an old config. */
    public static List<CNMirrors.Mirror> mirrors(String preferredBase) {
        LinkedHashMap<String,CNMirrors.Mirror> out = new LinkedHashMap<String,CNMirrors.Mirror>();
        add(out, CNEndpoints.EDGEONE_BASE, "内置更新主线路");
        add(out, CNEndpoints.ESA_BASE, "内置更新备用线路");
        add(out, CNEndpoints.LEGACY_EDGEONE_BASE, "原 EdgeOne 更新源");
        add(out, CNEndpoints.LEGACY_ESA_BASE, "原 ESA 更新源");
        if (CNPublicResources.ENABLED) {
            add(out, CNPublicResources.RELEASE_BASE, "独立公开资源更新源");
            add(out, CNPublicResources.LEGACY_RELEASE_BASE, "旧仓应急入口");
        }
        for (CNMirrors.Mirror m : CNMirrors.healthy()) {
            if (m.enabled && !out.containsKey(m.base)) out.put(m.base, m);
        }
        List<CNMirrors.Mirror> result = new ArrayList<CNMirrors.Mirror>();
        // Pin bytes to the publisher of the selected identity; other publishers remain fallbacks.
        if (preferredBase != null && out.containsKey(preferredBase)) result.add(out.remove(preferredBase));
        List<CNMirrors.Mirror> remaining = new ArrayList<CNMirrors.Mirror>(out.values());
        CNDownloadRoute.sortSources(remaining);
        result.addAll(remaining);
        return result;
    }

    private static void add(LinkedHashMap<String,CNMirrors.Mirror> out, String base, String name) {
        if (base == null || base.isEmpty() || !base.startsWith("https://") || !base.endsWith("/")) return;
        if (!out.containsKey(base)) out.put(base, new CNMirrors.Mirror(name, base, 100, 0, true));
    }

    static List<String> clientUrls() {
        LinkedHashSet<String> urls = new LinkedHashSet<String>();
        for (CNMirrors.Mirror m : mirrors(null)) urls.add(m.urlFor(CLIENT_META));
        if (!CNEndpoints.MIRRORS_URL.isEmpty()) urls.add(CNEndpoints.MIRRORS_URL);
        if (!CNEndpoints.API_BASE.isEmpty()) urls.add(CNEndpoints.API_BASE + "legacy/config.json");
        if (CNPublicResources.ENABLED) urls.add(CNPublicResources.CONFIG_URL);
        return new ArrayList<String>(urls);
    }

    interface Loader<T> { T load(String url) throws Exception; }
    static final class Reply<T> {
        final String url;
        final T value;
        final Exception error;
        Reply(String url, T value, Exception error) { this.url=url; this.value=value; this.error=error; }
    }

    /** Collect all responses within one bounded parallel budget, not first-success wins. */
    static <T> List<Reply<T>> collect(final List<String> urls, final Loader<T> loader,
                                     long budgetMs) throws InterruptedException {
        List<String> normal = new ArrayList<String>();
        List<String> emergency = new ArrayList<String>();
        for (String url : urls) (CNPublicResources.legacyUrl(url) ? emergency : normal).add(url);
        long started = System.nanoTime();
        long reserve = emergency.isEmpty() ? 0L : Math.min(1500L, budgetMs/4L);
        List<Reply<T>> answers = collectWithin(normal, loader, Math.max(1L,budgetMs-reserve));
        for (Reply<T> reply : answers) if (reply.error == null && reply.value != null) return answers;
        long remaining = budgetMs-TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
        if (remaining > 0) answers.addAll(collectWithin(emergency,loader,remaining));
        return answers;
    }

    private static <T> List<Reply<T>> collectWithin(final List<String> urls, final Loader<T> loader,
                                                  long budgetMs) throws InterruptedException {
        final List<Reply<T>> out = new ArrayList<Reply<T>>();
        if (urls.isEmpty()) return out;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(6, urls.size()), new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "cn-update-source"); t.setDaemon(true); return t;
            }
        });
        ExecutorCompletionService<Reply<T>> service = new ExecutorCompletionService<Reply<T>>(pool);
        List<Future<Reply<T>>> pending = new ArrayList<Future<Reply<T>>>();
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        try {
            for (final String url : urls) pending.add(service.submit(new Callable<Reply<T>>() {
                public Reply<T> call() {
                    try { return new Reply<T>(url, loader.load(url), null); }
                    catch (Exception e) { return new Reply<T>(url, null, e); }
                }
            }));
            for (int i=0;i<urls.size();i++) {
                long remaining=deadline-System.nanoTime();
                if (remaining<=0) break;
                Future<Reply<T>> f=service.poll(remaining,TimeUnit.NANOSECONDS);
                if (f==null) break;
                try { out.add(f.get()); }
                catch (java.util.concurrent.ExecutionException e) {
                    out.add(new Reply<T>("worker",null,new java.io.IOException("update worker failed",e)));
                }
            }
            // Deterministic publisher preference when the same identity appears on several routes.
            List<Reply<T>> ordered = new ArrayList<Reply<T>>();
            for (String url : urls) for (Reply<T> r : out) if (url.equals(r.url)) ordered.add(r);
            return ordered;
        } finally {
            for (Future<Reply<T>> f : pending) if (!f.isDone()) f.cancel(true);
            pool.shutdownNow();
        }
    }

    static boolean validHot(CNHotUpdateValidate.VerMeta m) {
        return m!=null && m.version>0 && m.size>0 && m.md5!=null && m.md5.matches("(?i)[0-9a-f]{32}");
    }

    static final class ClientIdentity {
        final String version, sha256;
        final long size;
        ClientIdentity(String version, long size, String sha256) {
            this.version=version; this.size=size; this.sha256=sha256;
        }
    }

    static int highestClientIndex(List<ClientIdentity> values) throws java.io.IOException {
        int best=-1; boolean conflict=false;
        for (int i=0;i<values.size();i++) {
            ClientIdentity m=values.get(i);
            if (m==null || m.version==null || !m.version.matches("[0-9]+(\\.[0-9]+){1,3}")
                    || m.size<=0 || m.sha256==null || !m.sha256.matches("(?i)[0-9a-f]{64}")) continue;
            int cmp=best<0 ? 1 : CNVersionCheck.compareVersion(m.version,values.get(best).version);
            if (cmp>0) { best=i; conflict=false; }
            else if (cmp==0) {
                ClientIdentity old=values.get(best);
                if (m.size!=old.size || !m.sha256.equalsIgnoreCase(old.sha256)) conflict=true;
            }
        }
        if (conflict) throw new java.io.IOException("同一最高客户端版本的文件身份冲突");
        return best;
    }

    static CNHotUpdateValidate.VerMeta highestHot(List<CNHotUpdateValidate.VerMeta> values)
            throws java.io.IOException {
        CNHotUpdateValidate.VerMeta best=null;
        boolean conflict=false;
        for (CNHotUpdateValidate.VerMeta m : values) {
            if (!validHot(m)) continue;
            if (best==null || m.version>best.version) { best=m; conflict=false; }
            else if (m.version==best.version && (m.size!=best.size || !m.md5.equalsIgnoreCase(best.md5))) conflict=true;
        }
        if (conflict) throw new java.io.IOException("同一最高版本的文件身份冲突，保留已安装内容");
        if (best == null) return null;
        LinkedHashSet<String> sources = new LinkedHashSet<String>();
        for (CNHotUpdateValidate.VerMeta m : values) {
            if (validHot(m) && m.version==best.version && m.size==best.size
                    && m.md5.equalsIgnoreCase(best.md5) && m.sourceBases!=null) sources.addAll(m.sourceBases);
        }
        return new CNHotUpdateValidate.VerMeta(best.version,best.size,best.md5,best.sourceBase,
                sources.isEmpty() ? null : new ArrayList<String>(sources));
    }
}
