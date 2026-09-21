#!/usr/bin/env python3
"""Execute the production skill-box wrapper with mock engine functions, not a device test."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
src = (root / "magia-native/src/MagiaLegacy.cpp").read_text(encoding="utf-8")
begin = "// BEGIN official-skill-label-box"
end = "// END official-skill-label-box"
assert src.count(begin) == src.count(end) == 1, "Missing scoped skill-label fix"
block = src.split(begin, 1)[1].split(end, 1)[0]
assert "setPosition" not in block and "setLineHeight" not in block
assert "FontFreeType" not in block and "fontPathFix" not in block
test = r'''
#include <cassert>
#include <cstdio>
PRODUCTION
static int self, art, node, label, previousLabel, calls, adjustments;
static const char bone[] = "skill_title";
static void original(void* s, void* a, const char* b, void** n, void** l) {
    assert(s == &self && a == &art && b == bone);
    ++calls;
    if (n) *n = &node;
    if (l) *l = &label;
}
static void emptyOriginal(void*, void*, const char*, void**, void** l) {
    ++calls;
    if (l) *l = nullptr;
}
static void dimensions(void* l, float w, float h) {
    assert(calls > adjustments && l == &label);
    assert(w == 768.0f && h == 34.0f);
    ++adjustments;
}
int main() {
    skillTitleOld = original;
    skillLabelSetDimensions = dimensions;
    void* n = nullptr; void* l = &previousLabel;
    skillTitleNew(&self, &art, bone, &n, &l);
    assert(calls == 1 && adjustments == 1 && n == &node && l == &label);
    puts("PASS forwards all arguments, calls original first, expands produced label to 768x34");
    skillTitleNew(&self, &art, bone, &n, nullptr);
    assert(calls == 2 && adjustments == 1);
    skillTitleOld = emptyOriginal;
    skillTitleNew(&self, &art, bone, &n, &l);
    assert(calls == 3 && adjustments == 1 && l == nullptr);
    puts("PASS absent output/label never dereferenced");
    skillTitleOld = original; skillLabelSetDimensions = nullptr;
    skillTitleNew(&self, &art, bone, &n, &l);
    assert(calls == 4 && adjustments == 1);
    puts("PASS absent layout function retains original behavior");
    skillLabelSetDimensions = dimensions;
    for (int i=0; i<100; ++i) skillTitleNew(&self, &art, bone, &n, &l);
    assert(calls == 104 && adjustments == 101);
    puts("PASS repeated creation does not accumulate offsets or dimensions");
    return 0;
}
'''.replace("PRODUCTION", block)
compiler = os.environ.get("CXX") or shutil.which("clang++") or shutil.which("g++")
assert compiler, "C++ host compiler required"
with tempfile.TemporaryDirectory(prefix="skill-label-test-") as temp:
    d = Path(temp)
    p = d / "test.cpp"
    p.write_text(test, encoding="utf-8")
    exe = d / ("test.exe" if os.name == "nt" else "test")
    subprocess.run([compiler, "-std=c++17", str(p), "-o", str(exe)], check=True)
    subprocess.run([str(exe)], check=True)
