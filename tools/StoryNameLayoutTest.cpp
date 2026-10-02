#include "../magia-native/src/StoryNameLayout.h"
#include <cassert>
#include <cstdio>
#include <limits>
using namespace magia_story_name;
struct Node { Point position; Point anchor; int writes=0; float font=16, line=25; bool allow=true; };
const Point& positionOf(void* p) { return static_cast<Node*>(p)->position; }
const Point& anchorOf(void* p) { return static_cast<Node*>(p)->anchor; }
void setPosition(void* p,const Point& v) { auto* n=static_cast<Node*>(p);n->position=v;++n->writes; }
float fontOf(void* p) { return static_cast<Node*>(p)->font; }
float lineOf(void* p) { return static_cast<Node*>(p)->line; }
bool setFont(void* p,float v) { auto* n=static_cast<Node*>(p);if(!n->allow)return false;n->font=v;return true; }
void setLine(void* p,float v) { static_cast<Node*>(p)->line=v; }
bool apply(const Capture& c) { return c.apply(positionOf,anchorOf,setPosition,fontOf,lineOf,setFont,setLine); }
int main() {
    assert(cnNameBaselineY(17,1)==60);
    assert(cnNameBaselineY(20,1)==63);
    assert(cnNameBaselineY(34,2)==60);
    assert(cnNameBaselineY(51,3)==63.f-8.f/3.f);
    assert(cnNameBaselineY(9,.5f)==61);
    assert(cnNameBaselineY(0,1)==63);
    assert(cnNameBaselineY(17,0)==63);
    assert(cnNameBaselineY(200,1)==63);
    assert(cnNameBaselineY(std::numeric_limits<float>::quiet_NaN(),1)==63);
    assert(cnNameBaselineY(17,std::numeric_limits<float>::infinity())==63);
    for (int side=0;side<3;side++) {
        Node body{{-222,20},{0,1}},name{{side==0?-215.0f:side==2?215.0f:-55.0f,57},{side==2?1.0f:0.0f,.5f}};
        body.font=27;body.line=38;
        Capture c(side);c.record(&body,27);c.record(&name,16);
        assert(apply(c));assert(name.position.y==63);assert(name.font==20);assert(name.line==25);
        assert(body.writes==0&&body.font==27&&body.line==38);
        assert(!apply(c));assert(name.writes==1);
        name.position.y=57;name.font=16;name.writes=0;
        assert(c.apply(positionOf,anchorOf,setPosition,fontOf,lineOf,setFont,setLine,
            [](void*)->float{return cnNameBaselineY(17,1);}));
        assert(name.position.y==60 && name.anchor.y==.5f && body.writes==0);
        name.position.y=57;name.font=16;
        assert(c.apply(positionOf,anchorOf,setPosition,fontOf,lineOf,setFont,setLine,
            [](void*)->float{return std::numeric_limits<float>::quiet_NaN();}));
        assert(name.position.y==63 && name.font==20 && name.line==25);
    }
    Node n{{215,57},{1,.5}};Capture invalid(3);invalid.record(&n,27);invalid.record(&n,16);
    assert(!apply(invalid));assert(n.writes==0&&n.font==16);
    Capture changed(2);changed.record(&n,27);changed.record(&n,20);assert(!apply(changed));
    Capture extra(2);extra.record(&n,27);extra.record(&n,16);extra.record(&n,16);assert(!apply(extra));
    Capture unrelated(0);unrelated.record(&n,27);unrelated.record(&n,16);assert(!apply(unrelated));
    Capture good(2);good.record(&n,27);good.record(&n,16);
    assert(!good.apply(nullptr,anchorOf,setPosition,fontOf,lineOf,setFont,setLine));
    n.line=26;assert(!apply(good));n.line=25;n.allow=false;assert(!apply(good));assert(n.position.y==57&&n.font==16);n.allow=true;
    Capture* active=nullptr;Capture outer(0),inner(2);
    {Scope s(active,outer);assert(active==&outer);{Scope nested(active,inner);assert(active==&inner);}assert(active==&outer);}assert(active==nullptr);
    TtfConfigCopy before{},after{};for(size_t i=0;i<before.size();i++)before[i]=static_cast<unsigned char>(i*7);
    const float old=16;std::memcpy(before.data()+kTtfFontSizeOffset,&old,sizeof(old));
    assert(copyNameTtfConfig(before.data(),16,20,after));
    for(size_t i=0;i<before.size();i++)if(i<kTtfFontSizeOffset||i>=kTtfFontSizeOffset+4)assert(before[i]==after[i]);
    float actual;std::memcpy(&actual,after.data()+kTtfFontSizeOffset,4);assert(actual==20);
    assert(!copyNameTtfConfig(before.data(),18,20,after));assert(!copyNameTtfConfig(nullptr,16,20,after));
    std::puts("Story name layout: full name size, position, unchanged body/config and rejection guards passed");
}
