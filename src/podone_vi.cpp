// RT64 excludes Racer's undrawn overscan pixels with VI::cropRectangle.
// For the fill mode, map that drawn rectangle to the full presentation area.
#include <atomic>
#include "hle/rt64_vi.h"

namespace {
    std::atomic<bool> fill_screen{false};
}

void podone_set_fill_screen(bool enabled) {
    fill_screen.store(enabled);
}

// RT64 is linked statically. The VI renderer calls these methods from a
// different translation unit, so Android's linker can redirect them.
hlslpp::float4 original_view(const RT64::VI* vi) asm("__real__ZNK4RT642VI13viewRectangleEv");
hlslpp::float4 original_crop(const RT64::VI* vi) asm("__real__ZNK4RT642VI13cropRectangleEv");
hlslpp::float4 podone_view(const RT64::VI* vi) asm("__wrap__ZNK4RT642VI13viewRectangleEv");
hlslpp::float4 podone_crop(const RT64::VI* vi) asm("__wrap__ZNK4RT642VI13cropRectangleEv");

hlslpp::float4 podone_view(const RT64::VI* vi) {
    if (!fill_screen.load()) return original_view(vi);
    const hlslpp::float4 drawn = original_crop(vi);
    const float width = drawn.z, height = drawn.w;
    return {-float(drawn.x) / width, -float(drawn.y) / height, 1.0f / width, 1.0f / height};
}

hlslpp::float4 podone_crop(const RT64::VI* vi) {
    return fill_screen.load() ? hlslpp::float4(0.f, 0.f, 1.f, 1.f) : original_crop(vi);
}
