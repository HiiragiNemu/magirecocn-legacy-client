"""Check published credits data and native rendering/update action contracts."""
import argparse,json,re
from pathlib import Path
p=argparse.ArgumentParser()
p.add_argument('--native',type=Path,default=Path(__file__).resolve().parents[1])
p.add_argument('--config',type=Path,required=True)
a=p.parse_args(); j=a.native/'patch/src/main/java/io/kamihama/magianative'
s=(j/'CNCNDownloadUI.java').read_text('utf-8'); v=(j/'CNVersionCheck.java').read_text('utf-8')
c=json.loads(a.config.read_text('utf-8')); rows=c['ui_credits']['list']
def ck(ok,label):
 assert ok,label
 print('PASS '+label)
for key,field in [('CREDIT_TEXTS','text'),('CREDIT_URLS','url'),('CREDIT_LINK_SPANS','span')]:
 body=re.search(r'private static final String\[\] '+key+r' = \{(.*?)\n    \};',s,re.S)[1]
 values=json.loads('['+body+']')
 ck(values==[x.get(field,'') for x in rows], 'remote and native '+field+' match')
body=re.search(r'private static final int\[\] CREDIT_KINDS = \{(.*?)\n    \};',s,re.S)[1]
ck(re.findall(r'KIND_(\w+)',body)==[x['type'].upper() for x in rows], 'all fallback row kinds aligned')
texts=[x['text'] for x in rows]; public='\n'.join(texts)+c['ui_credits']['footer']
ck('核心实现与全中文化补全以及整合汉化。' in texts,'approved opening wording')
ck(any('目前加速 CN BASE 主资源文件。' in x for x in texts),'CN BASE assistance retained')
ck(any('目前采纳 52 个剧情汉化' in x for x in texts),'group story wording updated')
ck('阵型技能汉化：12 个名称、13 条效果说明，共 25 个字段。' in texts,'formation 12+13=25')
ck('Connect 技能汉化：220 个名称、278 条效果说明，共 498 个字段。' in texts,'Connect 220+278=498')
w=c['contribution_stats']['updates_20260924']['water_non_story']
ck([x['translatedFields'] for x in w['files']]==[25,25,498] and w['effective_field_count']==548,'structured total 548')
ck(all(sum(x['fields'].values())==x['translatedFields'] for x in w['files']),'field subtotals agree')
ck(w['record_count']==sum(x['records'] for x in w['files'])==318,'unique ID denominator agrees')
ck(all(x not in public for x in ['未命中','命中','1,153','1,049','阵型技能 79']),'public wording contains only approved attribution counts')
ck(sum('记忆结晶简介校订：5 条' in x for x in texts)==1 and c['contribution_stats']['updates_20260926']['memoria_description_proofreading']['count']==5,'latest five proofreading credits preserved')
idx=texts.index('统计说明')
ck(texts[idx-2:idx]==['赞助支持 MADE IN MAGIUS','赞助支持 CyberNova'],'sponsorship order directly before statistics')
ck('private static boolean statisticsExpanded;' in s and 'statisticsSection && !statisticsExpanded' in s,'statistics default collapsed')
ck('statisticsExpanded = !statisticsExpanded;' in s and 'new StatisticsClick(act)' in s,'statistics can expand and collapse')
ck('statisticsExpanded   = false;' in s,'fresh overlay resets collapsed state')
ck(s.index('topLeft.addView(vOfflinePill,') < s.index('topLeft.addView(vApkUpdatePill,') < s.index('headRight.addView(vThemeChip,'),'manual APK button follows offline import')
ck('CNVersionCheck.checkManually(act)' in s and 'vApkUpdatePill.setEnabled(!busy)' in s,'button is wired and guarded during checks')
manual=v[v.index('private static final java.util.concurrent.atomic.AtomicBoolean MANUAL_RUNNING'):v.index('public static native String nativeClientVersion')]
ck(all(x not in manual for x in ['proceed();','PROCEEDED.set','STARTED.set','CNHotUpdate','CNDownloaderFix']),'manual request does not restart resource flow')
ck('fetchBestClientSection()' in manual and 'manualResult(local, client)' in manual,'manual check reuses live multi-source metadata rules')
ck('versionModalContinuesStartup = continueStartup;' in s and 'if (continueStartup) CNVersionCheck.continueWithCurrentVersion();' in s,'manual dismiss is isolated from startup continuation')
ck('CNApkUpdateActivity.start(act, metadata)' in s,'existing verified APK installer retained')
ck('CNDownloadUiAssist.applyBuiltinScrollbar(topLeftScroll, true)' in s,'narrow toolbar retains horizontal scrolling')
for person,voice in c['contribution_stats']['voice'].items():
 if isinstance(voice,dict):ck(voice['subtitle']+voice['home']-voice['overlap']==voice['unique_total'],person+' voice union agrees')
selection=c['contribution_stats']['selection_dialogue']
ck(selection['static_table_total']+selection['additional_runtime_ids']==selection['total']==sum(selection[k] for k in ['official','made_words','made_ellipsis','empty']),'selection totals agree')
