#include "../magia-native/src/StoryNameLayout.h"
#include <cassert>
#include <cstdio>
using namespace magia_story_name;
struct Node { Point position; Point anchor; int writes=0; };
const Point& positionOf(void* p) { return static_cast<Node*>(p)->position; }
const Point& anchorOf(void* p) { return static_cast<Node*>(p)->anchor; }
void setPosition(void* p,const Point& v) { auto* n=static_cast<Node*>(p);n->position=v;++n->writes; }
int main() {
    for (int side=0;side<3;side++) {
        Node body{{-222,20},{0,1}},name{{side==0?-215.0f:side==2?215.0f:-55.0f,57},{side==2?1.0f:0.0f,.5f}};
        Capture c(side);c.record(&body,27);c.record(&name,16);
        assert(c.apply(positionOf,anchorOf,setPosition));assert(name.position.y==63);assert(body.writes==0);
        assert(!c.apply(positionOf,anchorOf,setPosition));assert(name.writes==1);
    }
    Node n{{215,57},{1,.5}};Capture invalid(3);invalid.record(&n,27);invalid.record(&n,16);
    assert(!invalid.apply(positionOf,anchorOf,setPosition));assert(n.writes==0);
    Capture changed(2);changed.record(&n,27);changed.record(&n,20);assert(!changed.apply(positionOf,anchorOf,setPosition));
    Capture extra(2);extra.record(&n,27);extra.record(&n,16);extra.record(&n,16);assert(!extra.apply(positionOf,anchorOf,setPosition));
    Capture unrelated(0);unrelated.record(&n,27);unrelated.record(&n,16);assert(!unrelated.apply(positionOf,anchorOf,setPosition));
    Capture good(2);good.record(&n,27);good.record(&n,16);assert(!good.apply(nullptr,anchorOf,setPosition));
    Capture* active=nullptr;Capture outer(0),inner(2);
    { Scope s(active,outer);assert(active==&outer);{Scope nested(active,inner);assert(active==&inner);}assert(active==&outer); }
    assert(active==nullptr);
    std::puts("Story name layout: all guarded parent-local coordinate checks passed");
}
