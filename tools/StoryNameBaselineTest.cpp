// Run against the actual Capture implementation, including an old-header
// override for the 1.0.200 counterexample; no copy of Capture's logic.
#ifndef STORY_NAME_HEADER
#define STORY_NAME_HEADER "../magia-native/src/StoryNameLayout.h"
#endif
#include STORY_NAME_HEADER
#include <cstdio>
#include <cmath>
using namespace magia_story_name;
struct Node { Point p, a; float font=16, line=25; int writes=0; };
const Point& pos(void* p){return static_cast<Node*>(p)->p;}
const Point& anchor(void* p){return static_cast<Node*>(p)->a;}
void write(void* p,const Point& v){auto* n=static_cast<Node*>(p);n->p=v;++n->writes;}
float size(void* p){return static_cast<Node*>(p)->font;}
float line(void* p){return static_cast<Node*>(p)->line;}
bool font(void* p,float v){static_cast<Node*>(p)->font=v;return true;}
void height(void* p,float v){static_cast<Node*>(p)->line=v;}
float baseline(void*){return 60.f;} // Real current font: ascender17 at CSF1, CN ascender20.
template<class T> auto run(const T& c,int)->decltype(c.apply(pos,anchor,write,size,line,font,height,baseline)){
 return c.apply(pos,anchor,write,size,line,font,height,baseline);
}
template<class T> bool run(const T& c,long){return c.apply(pos,anchor,write,size,line,font,height);}
int main(){
 int failures=0;
 for(int slot=0;slot<3;++slot){
  Node body{{-222,20},{0,1},27,38},name{{slot==0?-215.f:slot==2?215.f:-55.f,57},{slot==2?1.f:0.f,.5f}};
  Capture c(slot);c.record(&body,27);c.record(&name,16);
  const bool applied=run(c,0);
  const float actualBaseline=name.p.y+name.line*.5f-17.f;
  const float cnBaseline=63.f+25.f*.5f-20.f;
  const bool ok=applied && std::fabs(actualBaseline-cnBaseline)<.001f && !body.writes
   && body.font==27 && body.line==38 && name.font==20 && name.line==25;
  std::printf("slot=%d finalY=%.3f baseline=%.3f requiredCNBaseline=%.3f %s\n",slot,name.p.y,actualBaseline,cnBaseline,ok?"PASS":"FAIL");
  failures+=!ok;
 }
 return failures?1:0;
}
