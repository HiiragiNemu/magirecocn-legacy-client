#!/usr/bin/env python3
"""Phone layout source contracts; device rendering acceptance remains separate."""
import argparse
from pathlib import Path
import re

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--source-root',type=Path,default=Path(__file__).resolve().parents[1])
a=p.parse_args()
s=(a.source_root/'patch/src/main/java/io/kamihama/magianative/CNCNDownloadUI.java').read_text('utf8')
assist=(a.source_root/'patch/src/main/java/io/kamihama/magianative/CNDownloadUiAssist.java').read_text('utf8')
route=s[s.index('private static final class RouteMenuClick'):s.index('private static final class RouteChoice')]
surface=re.search(r'return darkMode \? (0x[0-9A-F]+) : (0x[0-9A-F]+);',s[s.index('static int controlSurfaceColor()'):])
checks={
    'logo fills measured button stack without stretching':'ImageView.ScaleType.CENTER_CROP' in s and '0, ViewGroup.LayoutParams.MATCH_PARENT, 1f' in s and 'logoRow.setBaselineAligned(false)' in s,
    'redownload aligned to filename top':'headRow.setGravity(Gravity.TOP)' in s and 'actionLp.gravity = Gravity.TOP' in s,
    'resource identities reuse one existing line':'route.setSingleLine(true)' in s and 'CNPackageReceipt.label(fileIdx' in s,

    'capsule fill is translucent (20 percent) in both themes':bool(surface) and all((int(surface[i],16)>>24)==0x33 for i in (1,2)),
    'dialog bounded by host height':'host.getHeight()-dp(act,24)' in route and 'new FrameLayout.LayoutParams(width,height,Gravity.CENTER)' in route,
    'tip scrolls with choices':'choices.addView(tip,lpRow(' in route and 'panel.addView(tip,' not in route,
    'body uses remaining height':'panel.addView(scroll,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,\n                    0,1f))' in route,
    'footer outside scroll':'panel.addView(actions,' in route and 'choices.addView(actions' not in route,
    'actions wrap with equal widths':'new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f)' in route and 'button.setIncludeFontPadding(true)' in route and 'button.setSingleLine(false)' in route,
    'close and continue preserved':'"关闭",null,true' in route and '"继续下载",null,true' in route,
    'toolbar has no asymmetric gutter':'topLeftScroll.setPadding(0, 0, 0, 0)' in s and 'topLeftScroll.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY)' in s,
    'compact six toolbar buttons':all(f'{v}.setPadding(dp(act, 12), dp(act, 3), dp(act, 12), dp(act, 3))' in s for v in ['vLogPill','vBgmPill','vTutorialPill','vOfflinePill','vThemeChip','vGitHubChip']),
    'viewport follows measured chrome':'new ChromeBounds(glass, mainScroll, true)' in s and 'new ChromeBounds(glass, mainScroll, false)' in s,
    'removed logo/version separator':'leftCol.addView(divider, divLp)' not in s,
    'routes do not squeeze file names':'row.addView(route, new LinearLayout.LayoutParams(' in s and 'headRow.addView(route,' not in s,
    'chooser uses credited labels':'CNDownloadRoute.displayName(mirror)' in route,
    'support links immediately above statistics':'"赞助支持 MADE IN MAGIUS",\n        "赞助支持 CyberNova",\n        "统计说明",' in s,
    'version panel fixed above scroll':all(x in s for x in ['leftCol.addView(versionPanel, versionLp)', 'versionPanel.addView(vVersionInfo,', 'versionPanel.addView(versionGrid,', 'versionBg.setStroke(']) and s.index('leftCol.addView(versionPanel,') < s.index('leftCol.addView(contribScroll,'),
    'compact header options':all(x in s for x in ['styleCompactOption(act, vStorageChip)', 'styleCompactOption(act, vRouteChip)', 'view.setMinHeight(dp(act, 32))', 'logoLp.bottomMargin = dp(act, 2)']),
    'option pills retain click actions':all(x in s for x in ['view.setBackground(optionBackground(act))', 'new StorageAccessClick(act)', 'new RouteMenuClick(act)']),
    'enter bubble matches real click bounds':'vStatus.setBackground(actionVisible ? optionBackground(vStatus.getContext()) : null)' in s and s.count('vStatus.setOnClickListener(STAY_TOGGLE)')==2 and 'vStatus.setClickable(false)' in s,
    'top outline controls separated from backdrop':s.count('bg.setColor(controlSurfaceColor())')>=3 and 'bg.setColor(CNCNDownloadUI.controlSurfaceColor())' in assist and 'v.setTextColor(color("COLOR_TEXT",' in assist,
    'day footer black with subdued fill':'0xFF000000' in s and 'vFooter.setBackgroundColor(darkMode ? 0xC01B1428 : 0x99FFFFFF)' in s,
}

def luminance(c):
    values=[x/255 for x in c]
    values=[x/12.92 if x<=0.04045 else ((x+0.055)/1.055)**2.4 for x in values]
    return sum(x*w for x,w in zip(values,[0.2126,0.7152,0.0722]))
def rgb(c): return [(c>>shift)&255 for shift in [16,8,0]]
def contrast(fg,bg):
    x,y=sorted([luminance(fg),luminance(bg)])
    return (y+0.05)/(x+0.05)
fg=re.search(r'vFooter.setTextColor\(darkMode \? (0x[0-9A-F]+) : (0x[0-9A-F]+)\)',s)
bg=re.search(r'vFooter.setBackgroundColor\(darkMode \? (0x[0-9A-F]+) : (0x[0-9A-F]+)\)',s)
if fg and bg:
    for i,theme in enumerate(['night','day'],1):
        f,b=int(fg[i],16),int(bg[i],16);alpha=((b>>24)&255)/255
        # Check the worst possible solid backdrops for both palettes.
        ratios=[contrast(rgb(f),[alpha*c+(1-alpha)*under for c in rgb(b)]) for under in [0,255]]
        checks[f'{theme} footer contrast >= 7:1']=min(ratios)>=7
        print(theme,'minimum footer contrast',round(min(ratios),2))
else: checks['footer has dedicated contrasting strip']=False
for label,ok in checks.items(): print(('PASS ' if ok else 'FAIL ')+label)
raise SystemExit(0 if all(checks.values()) else 1)
