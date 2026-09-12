package io.kamihama.magianative;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** JVM regression: the first healthy-but-stale route must not suppress later updates. */
public final class MultiSourceUpdateTest {
    static int passed;
    static final String MD5="0123456789abcdef0123456789abcdef";
    static final String SHA=MD5+MD5;
    static void check(boolean ok,String name) { if(!ok)throw new AssertionError(name);passed++;System.out.println("PASS "+name); }
    static CNHotUpdateValidate.VerMeta hot(int v) {return new CNHotUpdateValidate.VerMeta(v,100,MD5);}
    static CNUpdateSources.ClientIdentity client(String v) {return new CNUpdateSources.ClientIdentity(v,100,SHA);}
    static List<CNHotUpdateValidate.VerMeta> values(List<CNUpdateSources.Reply<CNHotUpdateValidate.VerMeta>> replies) {
        List<CNHotUpdateValidate.VerMeta> out=new ArrayList<CNHotUpdateValidate.VerMeta>();
        for(CNUpdateSources.Reply<CNHotUpdateValidate.VerMeta> r:replies)if(r.error==null)out.add(r.value);
        return out;
    }
    public static void main(String[] args)throws Exception {
        List<String> routes=Arrays.asList("edge-old","cloud-new","github-new");
        List<CNUpdateSources.Reply<CNHotUpdateValidate.VerMeta>> replies=CNUpdateSources.collect(routes,
            new CNUpdateSources.Loader<CNHotUpdateValidate.VerMeta>() {
                public CNHotUpdateValidate.VerMeta load(String route)throws Exception {
                    if(route.equals("edge-old"))return hot(87);
                    Thread.sleep(60);return hot(91);
                }
            },1000);
        check(replies.size()==3,"all_routes_queried_after_old_success");
        check(CNUpdateSources.highestHot(values(replies)).version==91,"old_edge_does_not_hide_js91");
        replies=CNUpdateSources.collect(routes,new CNUpdateSources.Loader<CNHotUpdateValidate.VerMeta>() {
            public CNHotUpdateValidate.VerMeta load(String route)throws Exception {
                if(!route.equals("github-new"))throw new IOException("HTTP 403");return hot(91);
            }
        },1000);
        check(CNUpdateSources.highestHot(values(replies)).version==91,"github_alone_recovers_two_failed_routes");
        check(CNUpdateSources.highestHot(Arrays.asList(hot(3289),hot(3291))).version==3291,"scenario_highest_independent");
        check(CNUpdateSources.highestHot(Arrays.asList(hot(91),new CNHotUpdateValidate.VerMeta(999,0,""))).version==91,"incomplete_high_version_rejected");
        boolean conflict=false;
        try {CNUpdateSources.highestHot(Arrays.asList(hot(91),new CNHotUpdateValidate.VerMeta(91,101,MD5)));}
        catch(IOException expected){conflict=true;}
        check(conflict,"same_high_version_conflict_holds_installed_content");
        check(CNUpdateSources.highestHot(Arrays.asList(hot(87),new CNHotUpdateValidate.VerMeta(87,101,MD5),hot(91))).version==91,"obsolete_conflict_does_not_hide_valid_higher_version");
        long start=System.nanoTime();
        replies=CNUpdateSources.collect(Arrays.asList("slow-edge","new-github"),new CNUpdateSources.Loader<CNHotUpdateValidate.VerMeta>() {
            public CNHotUpdateValidate.VerMeta load(String route)throws Exception {
                if(route.equals("slow-edge")){Thread.sleep(10000);return hot(87);}return hot(91);
            }
        },150);
        check((System.nanoTime()-start)/1000000<1500,"slow_route_does_not_block_global_budget");
        check(CNUpdateSources.highestHot(values(replies)).version==91,"fast_new_reply_survives_slow_route_timeout");
        check(CNUpdateSources.highestClientIndex(Arrays.asList(client("1.0.171"),client("1.0.174"),client("1.0.173")))==1,"client174_wins_while_edge171");
        check(CNUpdateSources.highestClientIndex(Arrays.asList(client("1.0.176"),client("1.0.175")))==0,"higher_edge_version_wins_over_personal");
        check(CNUpdateSources.highestClientIndex(Arrays.asList(client("1.0.9"),client("1.0.10")))==1,"client_version_numeric_not_lexical");
        check(CNUpdateSources.highestClientIndex(Arrays.asList(client("invalid"),client("1.0.174")))==1,"malformed_client_version_rejected");
        conflict=false;
        try{CNUpdateSources.highestClientIndex(Arrays.asList(client("1.0.174"),new CNUpdateSources.ClientIdentity("1.0.174",101,SHA)));}
        catch(IOException expected){conflict=true;}
        check(conflict,"client_equal_version_identity_conflict_rejected");
        check(CNUpdateSources.highestHot(new ArrayList<CNHotUpdateValidate.VerMeta>())==null,"all_failed_keeps_local_version");
        Thread.currentThread().interrupt();boolean interrupted=false;
        try{CNUpdateSources.collect(routes,new CNUpdateSources.Loader<String>(){public String load(String s){return s;}},100);}
        catch(InterruptedException expected){interrupted=true;}finally{Thread.interrupted();}
        check(interrupted,"caller_cancellation_propagates");
        check(CNSafeLink.reject(CNEndpoints.EDGEONE_BASE+CNUpdateSources.CLIENT_APK)==null,"exact_build_time_publisher_apk_allowed");
        check(CNSafeLink.reject("https://unrelated.pages.dev/client.apk")!=null,"unrelated_public_suffix_host_still_rejected");
        List<CNMirrors.Mirror> pinned=CNUpdateSources.mirrors(CNEndpoints.ESA_BASE);
        check(!pinned.isEmpty() && pinned.get(0).base.equals(CNEndpoints.ESA_BASE),"selected_identity_publisher_is_first_download_route");
        java.lang.reflect.Field field=CNMirrors.class.getDeclaredField("mirrors");field.setAccessible(true);
        Object saved=field.get(null);
        try {
            field.set(null,Arrays.asList(new CNMirrors.Mirror("stale","https://old.example/",100,0,true)));
            boolean primary=false,secondary=false;
            for(CNMirrors.Mirror m:CNUpdateSources.mirrors(null)) {
                primary|=m.base.equals(CNEndpoints.EDGEONE_BASE);secondary|=m.base.equals(CNEndpoints.ESA_BASE);
            }
            check(primary&&secondary,"old_remote_config_cannot_remove_builtin_update_publishers");
        } finally {field.set(null,saved);}
        System.out.println("MULTISOURCE_UPDATE_PASS "+passed);
    }
}
