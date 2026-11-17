#pragma once

#include <cmath>

#include "common/rt64_plume.h"
#include "hle/rt64_framebuffer_pair.h"

namespace podone_hud {
    bool race_clock_running();
    void note_original_start();

    bool tracing(uint64_t workload);
    void trace_layout(uint64_t workload, unsigned pair, unsigned width, unsigned height, float x_scale, float y_scale,
                      float source_aspect, float target_aspect, bool race_hud, float offset, unsigned anchored);

    constexpr int framebuffer_width = 320;
    constexpr int left_margin = 8;
    constexpr int right_margin = framebuffer_width - left_margin;

    enum class Anchor { None, Left, Right, Center, ExtendLeft, ExtendRight };

    inline bool matches(const RT64::FixedRect& rect, int x, int y, int right, int bottom) {
        return rect.ulx == 4 * x && rect.uly == 4 * y && rect.lrx == 4 * right && rect.lry == 4 * bottom;
    }

    inline bool inside(const RT64::FixedRect& rect, int x, int y, int right, int bottom) {
        return rect.ulx >= 4 * x && rect.uly >= 4 * y && rect.lrx <= 4 * right && rect.lry <= 4 * bottom;
    }

    inline bool hud_rectangle(const RT64::DrawCall& call) {
        return call.tileCount == 1 && !call.otherMode.zCmp() && !call.otherMode.zUpd()
            && matches(call.scissorRect, left_margin, 16, right_margin, 464);
    }

    inline bool timer_digit(const RT64::DrawCall& call) {
        if (!hud_rectangle(call)) return false;
        // The progress ring puts the race time at the upper right.
        return inside(call.rect, 122, 42, 199, 82) || inside(call.rect, 202, 50, 280, 98);
    }

    inline bool has_race_hud(const RT64::FramebufferPair& pair) {
        // The lap and position badges and the two pieces of the timer are
        // drawn together in the race HUD. Match their layout, since textures
        // can be loaded at different addresses on each track.
        unsigned badges = 0;
        for (unsigned p = 0; p < pair.projectionCount; ++p) {
            const auto& projection = pair.projections[p];
            if (projection.type != RT64::Projection::Type::Rectangle) continue;
            for (unsigned i = 0; i < projection.gameCallCount; ++i) {
                const auto& call = projection.gameCalls[i].callDesc;
                if (!hud_rectangle(call)) continue;
                if (matches(call.rect, 18, 68, 66, 116)) badges |= 1;
                if (matches(call.rect, 254, 68, 302, 116)) badges |= 2;
                if (matches(call.rect, 109, 70, 173, 118)) badges |= 4;
                if (matches(call.rect, 173, 70, 211, 118)) badges |= 8;
                if (badges == 15) return true;
            }
        }
        return false;
    }

    inline Anchor anchor(const RT64::DrawCall& call) {
        if (!hud_rectangle(call)) return Anchor::None;
        const auto& rect = call.rect;
        if (matches(rect, 66, 70, 109, 76)) return Anchor::ExtendLeft;
        if (matches(rect, 211, 70, 254, 76)) return Anchor::ExtendRight;
        if (inside(rect, left_margin, 42, 66, 116) || inside(rect, left_margin, 312, 105, 464)) {
            return Anchor::Left;
        }
        if (inside(rect, 254, 42, right_margin, 116) || inside(rect, 216, 116, right_margin, 464)) {
            return Anchor::Right;
        }
        if (inside(rect, 109, 42, 211, 118)) return Anchor::Center;
        return Anchor::None;
    }

    inline float edge_offset(float wide_scale, float native_scale) {
        // Preserve the original padding inside Racer's eight-pixel overscan mask.
        return std::round((framebuffer_width / 2 - left_margin) * (wide_scale - native_scale));
    }

    inline void place(RenderViewport& viewport, Anchor anchor, float offset) {
        switch (anchor) {
        case Anchor::Left: viewport.x -= offset; break;
        case Anchor::Right: viewport.x += offset; break;
        case Anchor::ExtendLeft: viewport.x -= offset; viewport.width += offset; break;
        case Anchor::ExtendRight: viewport.width += offset; break;
        case Anchor::Center:
        case Anchor::None:
            break;
        }
    }
}
