#pragma once

#include <cmath>

#include "common/rt64_plume.h"
#include "hle/rt64_framebuffer_pair.h"

namespace podone_hud {
    enum class Layout { None, Minimap, ProgressRing };
    enum class Anchor { None, Left, Right, Center, ExtendLeft, ExtendRight, Stretch };

    bool race_clock_running();
    void note_original_start();

    bool tracing(uint64_t workload);
    void trace_layout(uint64_t workload, unsigned pair, unsigned width, unsigned height, float x_scale, float y_scale,
                      float source_aspect, float target_aspect, Layout layout, float offset, unsigned anchored);

    constexpr int framebuffer_width = 320;
    constexpr int left_margin = 8;
    constexpr int right_margin = framebuffer_width - left_margin;
    constexpr int top_margin = 16;
    constexpr int banner_top = 42;

    inline bool matches(const RT64::FixedRect& rect, int x, int y, int right, int bottom) {
        return rect.ulx == 4 * x && rect.uly == 4 * y && rect.lrx == 4 * right && rect.lry == 4 * bottom;
    }

    inline bool inside(const RT64::FixedRect& rect, int x, int y, int right, int bottom) {
        return rect.ulx >= 4 * x && rect.uly >= 4 * y && rect.lrx <= 4 * right && rect.lry <= 4 * bottom;
    }

    inline bool hud_rectangle(const RT64::DrawCall& call) {
        return call.tileCount == 1 && !call.otherMode.zCmp() && !call.otherMode.zUpd()
            && matches(call.scissorRect, left_margin, top_margin, right_margin, 464);
    }

    inline bool timer_digit(const RT64::DrawCall& call) {
        if (!hud_rectangle(call)) return false;
        // The progress ring puts the race time at the upper right.
        return inside(call.rect, 122, 42, 199, 82) || inside(call.rect, 202, 50, 280, 98);
    }

    inline unsigned progress_ring_edge(const RT64::FixedRect& rect) {
        if (matches(rect, 23, 40, 293, 44)) return 1;
        if (matches(rect, 23, 440, 293, 444)) return 2;
        if (matches(rect, 22, 42, 33, 440)) return 4;
        if (matches(rect, 283, 42, 294, 440)) return 8;
        return 0;
    }

    inline Layout race_layout(const RT64::FramebufferPair& pair) {
        // Match HUD parts by position. Their texture addresses can change
        // from track to track.
        unsigned badges = 0, ring_edges = 0;
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
                if (badges == 15) return Layout::Minimap;
                ring_edges |= progress_ring_edge(call.rect);
                if (ring_edges == 15) return Layout::ProgressRing;
            }
        }
        return Layout::None;
    }

    inline Anchor anchor(const RT64::DrawCall& call, Layout layout) {
        if (!hud_rectangle(call)) return Anchor::None;
        const auto& rect = call.rect;
        if (layout == Layout::ProgressRing) {
            if (progress_ring_edge(rect)) return Anchor::Stretch;
            if (inside(rect, 40, 58, 85, 134)) return Anchor::Left;
            if (inside(rect, 199, 58, 281, 134)) return Anchor::Right;
            // The rank icons and their outlined digits follow the same
            // horizontal transform as the ring, including on its corners.
            const bool marker_size = rect.width(true, true) <= 24 && rect.height(true, true) <= 38;
            const bool on_ring = rect.uly <= 4 * 34 || rect.lry >= 4 * 432
                || rect.lrx <= 4 * 47 || rect.ulx >= 4 * 272;
            if (marker_size && on_ring && !matches(rect, 275, 320, 286, 328)) return Anchor::Stretch;
            if (inside(rect, left_margin, 312, 105, 464)) return Anchor::Left;
            if (inside(rect, 216, 312, right_margin, 464)) return Anchor::Right;
            return Anchor::None;
        }
        if (layout != Layout::Minimap) return Anchor::None;
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

    inline float banner_offset(const RT64::DrawCall& call, Layout layout, float scale) {
        if (layout != Layout::Minimap || !hud_rectangle(call)
                || !inside(call.rect, 0, banner_top, 320, 118)) return 0.0f;
        return -std::round((banner_top - top_margin) * scale);
    }

    inline void place(RenderViewport& viewport, Anchor anchor, float offset) {
        switch (anchor) {
        case Anchor::Left: viewport.x -= offset; break;
        case Anchor::Right: viewport.x += offset; break;
        case Anchor::ExtendLeft: viewport.x -= offset; viewport.width += offset; break;
        case Anchor::ExtendRight: viewport.width += offset; break;
        case Anchor::Center:
        case Anchor::Stretch:
        case Anchor::None:
            break;
        }
    }
}
