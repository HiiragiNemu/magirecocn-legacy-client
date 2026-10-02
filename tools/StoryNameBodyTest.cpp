// Execute the production name-only em-body mapping, without a graphics mock.
#include "../magia-native/src/StoryNameLayout.h"
#include <cstdio>
#include <limits>
using namespace magia_story_name;
static int checks = 0, failures = 0;
static void check(bool ok, const char* what) {
    ++checks;
    if (!ok) { ++failures; std::printf("FAIL %s\n", what); }
}
static bool equal(float a, float b) { return std::fabs(a - b) < 0.0001f; }
struct Label { Point p, anchor; float size = 16, line = 25; };
static const Point& pos(void* p) { return static_cast<Label*>(p)->p; }
static const Point& anchor(void* p) { return static_cast<Label*>(p)->anchor; }
static float size(void* p) { return static_cast<Label*>(p)->size; }
static float line(void* p) { return static_cast<Label*>(p)->line; }
static bool setSize(void* p, float x) { static_cast<Label*>(p)->size=x; return true; }
static void setLine(void* p, float x) { static_cast<Label*>(p)->line=x; }
static void setPos(void* p, const Point& x) { static_cast<Label*>(p)->p=x; }
static float bodyY(void*) { return cnNameBodyY(17, 1, true); }
int main() {
    constexpr float refAxis = (1599.0f - 449.0f) / 4096.0f;
    constexpr float keptAxis = (850.0f - 150.0f) / 2000.0f;
    for (float scale : {0.25f, 0.5f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f, 4.0f, 8.0f}) {
        const float asc = std::ceil(20.0f * scale * 0.85f);
        const float refAsc = std::ceil(20.0f * scale * (2007.0f/2048.0f));
        const float targetCenter = 63 + 25/(2*scale) - refAsc/scale + 20*refAxis;
        const float actualCenter = cnNameBodyY(asc, scale, true)
            + 25/(2*scale) - asc/scale + 20*keptAxis;
        check(equal(targetCenter, actualCenter), "same native em-body center across raster scales");
        check(equal(cnNameBodyY(asc, scale, false), cnNameBaselineY(asc, scale)), "unknown profile preserves 201 mapping");
        check(equal(cnNameBodyY(refAsc, scale, false),63), "original font keeps original local coordinate");
        check(retainedNameBodyProfile("fonts/mbm_20160902.ttf", "Magius Round Symbols",asc,scale), "known runtime profile");
    }
    for (const char* path : {"fonts/mbm_20160902.ttf","fonts/TTDaYuanGB3.ttf","fonts/MTF4a5kp.ttf"}) {
        check(retainedNameBodyProfile(path,"Magius Round Symbols",17,1),"all unchanged release aliases");
        check(!retainedNameBodyProfile(path,"Another font",17,1),"unknown family");
        check(!retainedNameBodyProfile(path,nullptr,17,1),"missing optional family API");
        check(!retainedNameBodyProfile(path,"Magius Round Symbols",18,1),"changed raster metrics");
    }
    check(!retainedNameBodyProfile("fonts/other.ttf","Magius Round Symbols",17,1),"unknown path");
    check(!retainedNameBodyProfile(nullptr,"Magius Round Symbols",17,1),"null path");
    for (float scale : {0.0f, -1.0f, 0.24f, 8.01f, std::numeric_limits<float>::infinity(), std::numeric_limits<float>::quiet_NaN()}) {
        check(equal(cnNameBodyY(17,scale,true),cnNameBaselineY(17,scale)),"invalid scale preserves fallback");
        check(!retainedNameBodyProfile("fonts/mbm_20160902.ttf","Magius Round Symbols",17,scale),"invalid scale rejects profile");
    }
    for (float asc : {0.0f, -1.0f, 18.0f, 500.0f, std::numeric_limits<float>::infinity(), std::numeric_limits<float>::quiet_NaN()})
        check(equal(cnNameBodyY(asc,1,true),cnNameBaselineY(asc,1)),"invalid ascent preserves fallback");
    for (int slot=0; slot<3; ++slot) {
        Label dialogue{{-220,20},{0,1},27,38};
        const Label before=dialogue;
        const float x=slot==0?-215.0f:slot==1?-55.0f:215.0f;
        Label name{{x,57},{slot==2?1.0f:0.0f,0.5f}};
        Capture c(slot); c.record(&dialogue,27);c.record(&name,16);
        check(c.apply(pos,anchor,setPos,size,line,setSize,setLine,bodyY),"real capture accepts slot");
        check(equal(name.p.y,bodyY(nullptr)) && name.p.x==x,"only native name Y mapped");
        check(name.size==20 && name.line==25 && name.anchor.y==0.5f,"name size line and anchor retained");
        check(dialogue.p.x==before.p.x && dialogue.p.y==before.p.y && dialogue.size==27 && dialogue.line==38,"dialogue untouched");
        check(!c.apply(pos,anchor,setPos,size,line,setSize,setLine,bodyY),"no repeated offset");
    }
    std::printf("STORY_NAME_BODY checks=%d failures=%d nativeY=%.6f\n",checks,failures,bodyY(nullptr));
    return failures?1:0;
}
