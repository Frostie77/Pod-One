#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <limits>
#include <android/log.h>
#include <sys/system_properties.h>

#include "hle/rt64_workload_queue.h"
#include "podone_hud.h"

namespace {
    constexpr char log_tag[] = "PodOneHud";
    constexpr unsigned capture_workloads = 2;
    constexpr unsigned max_calls_per_workload = 240;
    std::array<std::atomic<uint64_t>, capture_workloads> trace_workloads{};
    using Clock = std::chrono::steady_clock;
    using namespace std::chrono_literals;
    std::atomic<Clock::time_point> last_clock_advance{};
    std::atomic<Clock::time_point> last_original_start{};

    uint64_t clock_signature(const RT64::Workload& workload) {
        // The USA ROM loads its race-timer font atlas once, then picks the
        // digits with texture coordinates. Use native HUD coordinates.
        constexpr uint32_t timer_atlas = 0x0009D8E0;
        constexpr uint64_t basis = 14695981039346656037ull;
        constexpr uint64_t prime = 1099511628211ull;
        for (unsigned f = 0; f < workload.fbPairCount; ++f) {
            const auto& pair = workload.fbPairs[f];
            if (pair.colorImage.width != podone_hud::framebuffer_width || !podone_hud::has_race_hud(pair)) continue;
            uint64_t signature = basis;
            bool found_digits = false;
            for (unsigned p = 0; p < pair.projectionCount; ++p) {
                const auto& projection = pair.projections[p];
                if (projection.type != RT64::Projection::Type::Rectangle) continue;
                bool timer_font = false;
                for (unsigned d = 0; d < projection.gameCallCount; ++d) {
                    const auto& call = projection.gameCalls[d];
                    const auto& desc = call.callDesc;
                    for (unsigned i = 0; i < desc.loadCount; ++i) {
                        const auto& load = workload.drawData.loadOperations[desc.loadIndex + i];
                        if (load.type != RT64::LoadOperation::Type::TLUT) timer_font = load.texture.address == timer_atlas;
                    }
                    if (!timer_font || !podone_hud::hud_rectangle(desc)
                        || !podone_hud::inside(desc.rect, 125, 46, 195, 80)) continue;

                    const size_t uv = size_t(call.meshDesc.rawVertexStart) * 2;
                    const auto& coords = workload.drawData.triTcFloats;
                    if (uv + 1 >= coords.size()) continue;
                    found_digits = true;
                    signature = (signature ^ uint32_t(desc.rect.ulx)) * prime;
                    signature = (signature ^ uint32_t(std::lround(coords[uv] * 32.0f))) * prime;
                    signature = (signature ^ uint32_t(std::lround(coords[uv + 1] * 32.0f))) * prime;
                }
            }
            if (found_digits) return signature;
        }
        return 0;
    }

    void observe_race_clock(const RT64::Workload& workload) {
        static uint64_t last = 0, previous = 0;
        const uint64_t current = clock_signature(workload);
        if (current == 0) {
            last_clock_advance.store({});
            last = previous = 0;
        } else if (current != last) {
            // The two alternating display lists can repeat the last two
            // timer values as Racer opens its own pause menu.
            if (last != 0 && current != previous) last_clock_advance.store(Clock::now());
            previous = last;
            last = current;
        }
    }

    struct Bounds {
        float left, top, right, bottom;
    };

    Bounds orthographic_bounds(const RT64::DrawData& draw, const RT64::GameCall& call) {
        const float infinity = std::numeric_limits<float>::infinity();
        Bounds bounds{infinity, infinity, -infinity, -infinity};
        const size_t begin = call.meshDesc.faceIndicesStart;
        const size_t end = std::min(draw.faceIndices.size(), begin + size_t(call.callDesc.triangleCount) * 3);
        for (size_t i = begin; i < end; ++i) {
            const uint32_t vertex = draw.faceIndices[i];
            if (vertex >= draw.posScreen.size()) continue;
            const auto& position = draw.posScreen[vertex];
            const float x = position.x, y = position.y;
            if (!std::isfinite(x) || !std::isfinite(y)) continue;
            bounds.left = std::min(bounds.left, x);
            bounds.top = std::min(bounds.top, y);
            bounds.right = std::max(bounds.right, x);
            bounds.bottom = std::max(bounds.bottom, y);
        }
        return bounds;
    }

    void log_perspective(const RT64::DrawData& draw, const RT64::Projection& projection, unsigned pair, unsigned index) {
        unsigned triangles = 0, behind = 0, crosses_camera = 0, outside_depth = 0, no_near_clip = 0;
        unsigned crosses_left = 0, crosses_right = 0;
        const auto& scissor = projection.scissorRect;
        const auto viewport = draw.rspViewports[projection.transformsIndex].rect(
            &draw.viewportClipRatios[projection.transformsIndex * 4]);
        for (unsigned i = 0; i < projection.gameCallCount; ++i) {
            const auto& call = projection.gameCalls[i];
            const size_t begin = call.meshDesc.faceIndicesStart;
            const size_t end = std::min(draw.faceIndices.size(), begin + size_t(call.callDesc.triangleCount) * 3);
            for (size_t face = begin; face + 2 < end; face += 3) {
                unsigned in_front = 0, outside = 0, left = 0, right = 0;
                for (size_t v = face; v < face + 3; ++v) {
                    const uint32_t vertex = draw.faceIndices[v];
                    if (vertex >= draw.posTransformed.size() || vertex >= draw.posScreen.size()) continue;
                    const float w = draw.posTransformed[vertex].w;
                    const auto& position = draw.posScreen[vertex];
                    const float x = position.x, z = position.z;
                    in_front += w > 0.0f;
                    outside += z < 0.0f || z > 1.0f;
                    left += x * 4.0f < scissor.ulx;
                    right += x * 4.0f > scissor.lrx;
                }
                ++triangles;
                behind += in_front == 0;
                crosses_camera += in_front > 0 && in_front < 3;
                outside_depth += outside > 0;
                no_near_clip += call.callDesc.NoN;
                crosses_left += in_front == 3 && left > 0 && left < 3;
                crosses_right += in_front == 3 && right > 0 && right < 3;
            }
        }
        __android_log_print(ANDROID_LOG_INFO, log_tag,
            "perspective %u/%u triangles=%u behind=%u crosses_camera=%u depth_outside=%u no_near_clip=%u side_crossings=%u,%u viewport=%d,%d,%d,%d clip=%d,%d,%d,%d",
            pair, index, triangles, behind, crosses_camera, outside_depth, no_near_clip, crosses_left, crosses_right,
            viewport.ulx, viewport.uly, viewport.lrx, viewport.lry, scissor.ulx, scissor.uly, scissor.lrx, scissor.lry);
    }

    void log_workload(const RT64::Workload& workload) {
        const auto& draw = workload.drawData;
        __android_log_print(ANDROID_LOG_INFO, log_tag, "workload=%llu vi=%ux%u aspect=%.3f pairs=%u",
            static_cast<unsigned long long>(workload.workloadId), unsigned(workload.viFbSize.x),
            unsigned(workload.viFbSize.y), workload.viAspectRatio, workload.fbPairCount);

        unsigned logged = 0;
        for (uint32_t f = 0; f < workload.fbPairCount; ++f) {
            const auto& pair = workload.fbPairs[f];
            if (pair.gameCallCount == 0) continue;
            __android_log_print(ANDROID_LOG_INFO, log_tag, "pair=%u dl=%08x fb=%08x width=%u depth=%08x depth_clear=%u clip=%d,%d,%d,%d",
                f, pair.displayListAddress, pair.colorImage.address, unsigned(pair.colorImage.width),
                pair.depthImage.address, unsigned(pair.fastPaths.clearDepthOnly),
                pair.scissorRect.ulx, pair.scissorRect.uly, pair.scissorRect.lrx, pair.scissorRect.lry);
            for (uint32_t p = 0; p < pair.projectionCount; ++p) {
                const auto& projection = pair.projections[p];
                using Type = RT64::Projection::Type;
                if (projection.type == Type::Perspective) {
                    log_perspective(draw, projection, f, p);
                    continue;
                }
                const bool rect = projection.type == Type::Rectangle;
                if (!rect && projection.type != Type::Orthographic) continue;
                for (uint32_t i = 0; i < projection.gameCallCount; ++i) {
                    const auto& call = projection.gameCalls[i];
                    const auto& desc = call.callDesc;
                    const auto& r = desc.rect;
                    const Bounds bounds = rect ? Bounds{r.ulx * .25f, r.uly * .25f, r.lrx * .25f, r.lry * .25f}
                                               : orthographic_bounds(draw, call);
                    if (bounds.left >= bounds.right || bounds.top >= bounds.bottom) continue;
                    if (logged++ == max_calls_per_workload) {
                        __android_log_print(ANDROID_LOG_INFO, log_tag, "workload reached capture limit");
                        return;
                    }
                    const auto* tile = desc.tileCount ? &draw.callTiles[desc.tileIndex] : nullptr;
                    uint32_t texture = 0;
                    for (uint32_t load = 0; load < desc.loadCount; ++load) {
                        const auto& op = draw.loadOperations[desc.loadIndex + load];
                        if (op.type != RT64::LoadOperation::Type::TLUT) texture = op.texture.address;
                    }
                    const auto& scissor = desc.scissorRect;
                    __android_log_print(ANDROID_LOG_INFO, log_tag,
                        "%s %u/%u id=%u box=%.2f,%.2f,%.2f,%.2f clip=%d,%d,%d,%d tiles=%u tex=%08x tmem=%016llx depth=%u%u",
                        rect ? "rect" : "ortho", f, p, desc.uid, bounds.left, bounds.top, bounds.right, bounds.bottom,
                        scissor.ulx, scissor.uly, scissor.lrx, scissor.lry, desc.tileCount, texture,
                        tile ? static_cast<unsigned long long>(tile->tmemHashOrID) : 0ull,
                        unsigned(desc.otherMode.zCmp()), unsigned(desc.otherMode.zUpd()));
                }
            }
        }
    }

    void capture_hud(const RT64::Workload& workload) {
        static char last_request[PROP_VALUE_MAX] = {};
        static unsigned remaining = 0;
        char request[PROP_VALUE_MAX] = {};
        // A distinct ADB request value captures both alternating display lists once.
        if (__system_property_get("debug.podone.hud", request) == 0 || std::strcmp(request, "0") == 0) return;

        if (std::strcmp(last_request, request) != 0) {
            std::strcpy(last_request, request);
            remaining = capture_workloads;
            for (auto& workload_id : trace_workloads) workload_id.store(0);
            __android_log_print(ANDROID_LOG_INFO, log_tag, "begin request=%s", request);
        }
        if (remaining > 0 && workload.fbPairCount > 0) {
            trace_workloads[capture_workloads - remaining].store(workload.workloadId);
            log_workload(workload);
            if (--remaining == 0) __android_log_print(ANDROID_LOG_INFO, log_tag, "end request=%s", request);
        }
    }
}

bool podone_hud::race_clock_running() {
    const auto now = Clock::now();
    return now - last_clock_advance.load() < 120ms && now - last_original_start.load() >= 250ms;
}

void podone_hud::note_original_start() {
    last_original_start.store(Clock::now());
}

bool podone_hud::tracing(uint64_t workload) {
    if (workload == 0) return false;
    return std::any_of(trace_workloads.begin(), trace_workloads.end(),
        [workload](const auto& id) { return id.load() == workload; });
}

void podone_hud::trace_layout(uint64_t workload, unsigned pair, unsigned width, unsigned height, float x_scale, float y_scale,
                             float source_aspect, float target_aspect, bool race_hud, float offset, unsigned anchored) {
    __android_log_print(ANDROID_LOG_INFO, log_tag,
        "layout workload=%llu pair=%u fb=%ux%u scale=%.3f,%.3f aspect=%.3f->%.3f race_hud=%u offset=%.0f anchored=%u",
        static_cast<unsigned long long>(workload), pair, width, height, x_scale, y_scale,
        source_aspect, target_aspect, unsigned(race_hud), offset, anchored);
}

// The producer still owns this workload until advanceToNextWorkload publishes it.
void original_advance(RT64::WorkloadQueue* queue) asm("__real__ZN4RT6413WorkloadQueue21advanceToNextWorkloadEv");
void capture_advance(RT64::WorkloadQueue* queue) asm("__wrap__ZN4RT6413WorkloadQueue21advanceToNextWorkloadEv");

void capture_advance(RT64::WorkloadQueue* queue) {
    const auto& workload = queue->workloads[queue->writeCursor];
    observe_race_clock(workload);
    capture_hud(workload);
    original_advance(queue);
}
