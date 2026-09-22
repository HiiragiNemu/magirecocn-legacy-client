package io.kamihama.magianative;

import java.lang.reflect.Field;
import java.util.*;

/** Production route planner: static CDN priority, hot identity eligibility and per-file labels. */
public final class PackageRoutingTest {
    private static int checks;
    private static final String MD5="0123456789abcdef0123456789abcdef";
    private static void check(boolean ok,String name) {
        if(!ok) throw new AssertionError(name);
        checks++; System.out.println("PASS "+name);
    }
    private static CNHotUpdateValidate.VerMeta meta(int version,String source) {
        return new CNHotUpdateValidate.VerMeta(version,100,MD5,source);
    }
    private static String pick(CNHotUpdateValidate.VerMeta meta,int attempt) {
        return CNDownloadRoute.plan(CNUpdateSources.downloadMirrors(meta),attempt).mirror.base;
    }
    public static void main(String[] args)throws Exception {
        String edge=CNEndpoints.LEGACY_EDGEONE_BASE,esa=CNEndpoints.LEGACY_ESA_BASE;
        String cf=CNEndpoints.PRIMARY_BASE_OVERRIDE,github=CNEndpoints.SECONDARY_BASE_OVERRIDE;
        check(!edge.isEmpty()&&!esa.isEmpty()&&!cf.isEmpty()&&!github.isEmpty(),"injected_four_endpoints");
        check(CNMirrors.selectable().size()==4,"builtin_has_four_manual_choices_before_config");
        List<CNMirrors.Mirror> routes=Arrays.asList(
            new CNMirrors.Mirror("EdgeOne",edge,140,8,true),
            new CNMirrors.Mirror("ESA",esa,120,8,true),
            new CNMirrors.Mirror("Cloudflare",cf,100,8,true),
            new CNMirrors.Mirror("GitHub",github,80,4,true));
        Field f=CNMirrors.class.getDeclaredField("mirrors");f.setAccessible(true);Object saved=f.get(null);
        f.set(null,routes);
        try {
            CNDownloadRoute.select("");
            check(pick(null,1).equals(edge),"base_defaults_to_EdgeOne");
            routes.get(0).cooldownUntilNs=Long.MAX_VALUE;
            check(pick(null,2).equals(esa),"failed_EdgeOne_does_not_skip_healthy_ESA");
            routes.get(1).cooldownUntilNs=Long.MAX_VALUE;
            check(pick(null,3).equals(cf),"both_CDNi_unavailable_then_Cloudflare");
            routes.get(2).cooldownUntilNs=Long.MAX_VALUE;
            check(pick(null,4).equals(github),"GitHub_last_fallback");
            for(CNMirrors.Mirror m:routes)m.cooldownUntilNs=0;
            check(CNDownloadRoute.select(cf)&&pick(null,1).equals(cf),"manual_Cloudflare_overrides_base_preference");
            CNHotUpdateValidate.VerMeta latest=CNUpdateSources.highestHot(Arrays.asList(meta(87,edge),meta(87,esa),meta(102,cf),meta(102,github)));
            check(latest.version==102&&latest.sourceBases.size()==2,"highest_JS102_records_only_matching_publishers");
            CNDownloadRoute.select(edge);
            check(pick(latest,1).equals(cf),"manual_old_EdgeOne_cannot_enter_JS102_candidates");
            check(CNUpdateSources.downloadMirrors(latest).size()==2,"old_edges_excluded_before_any_hot_payload_request");
            CNHotUpdateValidate.VerMeta scenario=CNUpdateSources.highestHot(Arrays.asList(meta(3289,edge),meta(3289,esa),meta(3299,cf),meta(3299,github)));
            check(scenario.version==3299&&pick(scenario,1).equals(cf),"scenario3299_filters_independently_from_JS102");
            CNDownloadRoute.select("");
            latest=CNUpdateSources.highestHot(Arrays.asList(meta(102,edge),meta(87,esa),meta(102,cf),meta(102,github)));
            check(pick(latest,1).equals(edge),"matching_EdgeOne_version_and_content_restores_CDN_priority");
            latest=CNUpdateSources.highestHot(Arrays.asList(meta(103,edge),meta(87,esa),meta(102,cf),meta(102,github)));
            check(latest.version==103&&pick(latest,1).equals(edge)&&CNUpdateSources.downloadMirrors(latest).size()==1,"higher_EdgeOne_version_wins_without_personal_downgrade");
            boolean conflict=false;
            try {CNUpdateSources.highestHot(Arrays.asList(meta(102,cf),new CNHotUpdateValidate.VerMeta(102,101,MD5,edge)));}
            catch(java.io.IOException expected){conflict=true;}
            check(conflict,"equal_version_different_content_is_not_interchangeable");
            latest=CNUpdateSources.highestHot(Arrays.asList(meta(102,github)));
            check(pick(latest,1).equals(github),"unanswered_or_failed_sources_not_assumed_current");
            CNDownloadRoute.clearFile(2);CNDownloadRoute.clearFile(14);
            CNDownloadRoute.recordUrl(2,edge+"cn_base_00_db.zip");
            CNDownloadRoute.recordUrl(14,cf+"cn_js_update.zip");
            check(CNDownloadRoute.fileLabel(2,1).equals("EdgeOne")&&CNDownloadRoute.fileLabel(14,1).equals("Cloudflare"),"parallel_files_have_independent_actual_route_labels");
            CNDownloadRoute.select(github);
            check(CNDownloadRoute.fileLabel(2,1).equals("EdgeOne"),"manual_preference_does_not_falsify_actual_route_label");
            CNDownloadRoute.recordUrl(2,esa+"cn_base_00_db.zip");
            check(CNDownloadRoute.fileLabel(2,1).equals("ESA"),"block_failover_updates_only_its_file_label");
            check(CNDownloadRoute.fileLabel(2,4).equals("本轮未下载"),"installed_unchecked_file_does_not_claim_network_activity");
            CNDownloadRoute.clearFile(2);
            check(CNDownloadRoute.fileLabel(2,0).equals("待连接"),"new_round_clears_previous_route_label");
        } finally {f.set(null,saved);CNDownloadRoute.select("");}
        System.out.println("PACKAGE_ROUTING_PASS "+checks);
    }
}
