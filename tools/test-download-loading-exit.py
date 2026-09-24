#!/usr/bin/env python3
"""Run the actual native fast path with host fakes; no emulator or APK execution."""
from pathlib import Path
import argparse,os,shutil,subprocess,sys

ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--source',type=Path,default=ROOT/'magia-native/src/MagiaLegacy.cpp');p.add_argument('--out',type=Path,default=ROOT/'.build/loading-exit-test');p.add_argument('--cxx',default=os.environ.get('CXX','c++'));a=p.parse_args()
text=a.source.read_text('utf8')
def function(name):
 start=text.index('static ',text.index(name)-30);brace=text.index('{',text.index(name,start));depth=1;end=brace+1
 while depth:
  depth+=(text[end]=='{')-(text[end]=='}');end+=1
 return text[start:end]
helper=function('completeReadyDownload(') if 'completeReadyDownload(' in text else ''
code=r'''
#include <functional>
#include <mutex>
#include <unordered_map>
#include <string>
#include <vector>
#include <iostream>
#define LOGI(...) ((void)0)
#define LOGE(...) ((void)0)
static bool ready;
static bool managerPresent;
static std::vector<std::string> events;
static bool resourcesReady(){return ready;}
static void oldEnter(void*){events.push_back("old");}
static void (*downloadSceneLayerOnEnterOld)(void*)=oldEnter;
static std::unordered_map<void*,void*> g_layerInfoMap;
static std::unordered_map<void*,std::function<void()>> g_infoCallbackMap;
static std::mutex g_layerInfoMutex,g_infoCallbackMutex;
using GetSceneLayerManagerFn=void*(*)();
using PopSceneLayerFn=void(*)(void*,int);
static void* getManager(){return managerPresent ? (void*)3 : nullptr;}
static void popLayer(void* manager,int type){events.push_back(manager==(void*)3&&type==33?"pop33":"WRONG");}
static GetSceneLayerManagerFn getSceneLayerManagerFn=getManager;
static PopSceneLayerFn popSceneLayerFn=popLayer;
'''+helper+'\n'+function('downloadSceneLayerOnEnterNew(')+r'''
int main(){
 int fail=0;
 for(int test=0;test<7;++test){
  events.clear();g_layerInfoMap.clear();g_infoCallbackMap.clear();
  ready=test!=1;managerPresent=test!=6;getSceneLayerManagerFn=test==4?nullptr:getManager;popSceneLayerFn=test==5?nullptr:popLayer;
  if(test!=2)g_layerInfoMap[(void*)1]=(void*)2;
  if(test!=3)g_infoCallbackMap[(void*)2]=[]{events.push_back("callback");};
  downloadSceneLayerOnEnterNew((void*)1);
  const std::vector<std::string> want=test==0?std::vector<std::string>{"pop33","callback"}:std::vector<std::string>{"old"};
  bool ok=events==want;fail+=!ok;std::cout<<(ok?"PASS ":"FAIL ")<<test<<":";
  for(const auto& e:events)std::cout<<" "<<e;std::cout<<"\n";
 }
 return fail?1:0;
}
'''
a.out.mkdir(parents=True,exist_ok=True);source=a.out/'loading-exit.cpp';source.write_text(code,encoding='utf8');exe=a.out/('loading-exit.exe' if os.name=='nt' else 'loading-exit')
cmd=[a.cxx,'-std=c++17',str(source),'-o',str(exe)]
r=subprocess.run(cmd,capture_output=True,encoding='utf8',errors='replace');print(r.stdout,r.stderr);assert r.returncode==0
r=subprocess.run([str(exe)],capture_output=True,encoding='utf8',errors='replace');print(r.stdout,r.stderr);raise SystemExit(r.returncode)
