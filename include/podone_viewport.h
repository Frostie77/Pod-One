#pragma once

#include "hle/rt64_workload.h"

namespace podone_viewport {
    inline float pixel_aspect(const RT64::Workload& workload, unsigned framebuffer_width) {
        const unsigned vi_width = workload.viFbSize.x;
        const unsigned vi_height = workload.viFbSize.y;
        if (workload.viAspectRatio <= 0.0f || vi_width == 0 || vi_height == 0 || framebuffer_width != vi_width) return 1.0f;
        return workload.viAspectRatio * float(vi_height) / float(vi_width);
    }

    inline bool landscape(const RT64::FixedRect& rect, float pixel_aspect) {
        return !rect.isEmpty() && rect.width(true, true) * pixel_aspect > rect.height(true, true);
    }
}
