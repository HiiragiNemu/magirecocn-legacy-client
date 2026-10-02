#ifndef MAGIA_STORY_NAME_LAYOUT_H
#define MAGIA_STORY_NAME_LAYOUT_H

#include <cmath>
#include <array>
#include <cstring>

namespace magia_story_name {
struct Point { float x; float y; };
using ReadPoint = const Point& (*)(void*);
using WritePoint = void (*)(void*, const Point&);
using ReadFloat = float (*)(void*);
using WriteFloat = void (*)(void*, float);
using SetFontSize = bool (*)(void*, float);

// CN TTDaYuanGB3 has hhea ascender=2007, unitsPerEm=2048. The original
// FreeType path rounds that scaled ascender up to a raster pixel. Label's
// single-line baseline is y + lineHeight/(2*scale) - ascender/scale.
// Copying y=63 alone therefore moves retained-font glyphs above the nameplate.
// Map the baseline, not a screenshot pixel or the bounds of a particular name.
inline float cnNameBaselineY(float rasterAscender, float rasterScale) {
    constexpr float originalY = 63.0f;
    if (!std::isfinite(rasterAscender) || !std::isfinite(rasterScale)
        || rasterScale < 0.25f || rasterScale > 8.0f || rasterAscender <= 0.0f)
        return originalY;
    const float reference = std::ceil(20.0f * rasterScale * (2007.0f / 2048.0f));
    const float offset = (rasterAscender - reference) / rasterScale;
    return std::fabs(offset) <= 10.0f ? originalY + offset : originalY;
}

// ABI of the public _ttfConfig value returned by the pinned engine's getter.
// AArch64 copies bytes 24..51, ARM32 copies 12..35; their padded sizes are
// 56 and 36. The leading libc++ string is borrowed for this one synchronous
// setter call only. Never reinterpret or write offsets inside a Label object.
constexpr size_t kTtfConfigBytes = sizeof(void*) == 8 ? 56 : 36;
constexpr size_t kTtfFontSizeOffset = sizeof(void*) * 3;
using TtfConfigCopy = std::array<unsigned char, kTtfConfigBytes>;
inline bool copyNameTtfConfig(const void* source, float expected, float size, TtfConfigCopy& target) {
    if (!source || !std::isfinite(size) || size <= 0) return false;
    std::memcpy(target.data(), source, target.size());
    float current;
    std::memcpy(&current, target.data() + kTtfFontSizeOffset, sizeof(current));
    if (current != expected) return false;
    std::memcpy(target.data() + kTtfFontSizeOffset, &size, sizeof(size));
    return true;
}

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
    bool apply(ReadPoint positionOf, ReadPoint anchorOf, WritePoint setPosition,
               ReadFloat fontSizeOf, ReadFloat lineHeightOf, SetFontSize setFontSize,
               WriteFloat setLineHeight, ReadFloat baselineYOf = nullptr) const {
        if (!expected || calls != 2 || !name || position < 0 || position > 2
            || !positionOf || !anchorOf || !setPosition || !fontSizeOf || !lineHeightOf
            || !setFontSize || !setLineHeight) return false;
        const Point current = positionOf(name);
        const Point anchor = anchorOf(name);
        const float expectedX = position == 0 ? -215.0f : position == 2 ? 215.0f : -55.0f;
        const float expectedAnchor = position == 2 ? 1.0f : 0.0f;
        if (current.x != expectedX || current.y != 57.0f
            || anchor.x != expectedAnchor || anchor.y != 0.5f) return false;
        // The 199 correction moved only Y and left the legacy 16px name in a
        // 20px CN layout. Admit the complete original label, then update only
        // its rendering size and reapply the unchanged native line height.
        if (fontSizeOf(name) != 16.0f || lineHeightOf(name) != 25.0f) return false;
        if (!setFontSize(name, 20.0f)) return false;
        setLineHeight(name, 25.0f);
        const float mappedY = baselineYOf ? baselineYOf(name) : 63.0f;
        const float y = std::isfinite(mappedY) && std::fabs(mappedY - 63.0f) <= 10.0f
            ? mappedY : 63.0f;
        setPosition(name, Point{current.x, y});
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
