#ifndef MAGIA_STORY_NAME_LAYOUT_H
#define MAGIA_STORY_NAME_LAYOUT_H

#include <cmath>

namespace magia_story_name {
struct Point { float x; float y; };
using ReadPoint = const Point& (*)(void*);
using WritePoint = void (*)(void*, const Point&);

// Only the two labels constructed by StoryMessageUnit::createMessageArea are
// admitted. No engine object layout, vtable index, font replacement or global
// Node position hook is required. The installed engine keeps its safe-area
// transform; the CN name coordinate is expressed in that shared local wrap.
struct Capture {
    int position;
    unsigned calls = 0;
    void* name = nullptr;
    bool expected = true;
    explicit Capture(int p) : position(p) {}
    void record(void* label, float size) {
        ++calls;
        if (calls == 1) expected = expected && size == 27.0f;
        else if (calls == 2) {
            expected = expected && size == 16.0f;
            name = label;
        } else expected = false;
    }
    bool apply(ReadPoint positionOf, ReadPoint anchorOf, WritePoint setPosition) const {
        if (!expected || calls != 2 || !name || position < 0 || position > 2
            || !positionOf || !anchorOf || !setPosition) return false;
        const Point current = positionOf(name);
        const Point anchor = anchorOf(name);
        const float expectedX = position == 0 ? -215.0f : position == 2 ? 215.0f : -55.0f;
        const float expectedAnchor = position == 2 ? 1.0f : 0.0f;
        if (current.x != expectedX || current.y != 57.0f
            || anchor.x != expectedAnchor || anchor.y != 0.5f) return false;
        setPosition(name, Point{current.x, 63.0f});
        return true;
    }
};

class Scope {
    Capture*& slot;
    Capture* previous;
public:
    Scope(Capture*& target, Capture& value) : slot(target), previous(target) { slot = &value; }
    ~Scope() { slot = previous; }
    Scope(const Scope&) = delete;
    Scope& operator=(const Scope&) = delete;
};
}
#endif
