#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <android/log.h>
#include <sys/system_properties.h>

#include "hle/rt64_workload_queue.h"

namespace {
    constexpr char log_tag[] = "PodOneHud";
    constexpr unsigned capture_workloads = 2;
    constexpr unsigned max_calls_per_workload = 240;

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

    void log_workload(const RT64::Workload& workload) {
        const auto& draw = workload.drawData;
        __android_log_print(ANDROID_LOG_INFO, log_tag, "workload=%llu vi=%ux%u aspect=%.3f pairs=%u",
            static_cast<unsigned long long>(workload.workloadId), unsigned(workload.viFbSize.x),
            unsigned(workload.viFbSize.y), workload.viAspectRatio, workload.fbPairCount);

        unsigned logged = 0;
        for (uint32_t f = 0; f < workload.fbPairCount; ++f) {
            const auto& pair = workload.fbPairs[f];
            if (pair.gameCallCount == 0) continue;
            __android_log_print(ANDROID_LOG_INFO, log_tag, "pair=%u dl=%08x fb=%08x width=%u clip=%d,%d,%d,%d",
                f, pair.displayListAddress, pair.colorImage.address, unsigned(pair.colorImage.width),
                pair.scissorRect.ulx, pair.scissorRect.uly, pair.scissorRect.lrx, pair.scissorRect.lry);
            for (uint32_t p = 0; p < pair.projectionCount; ++p) {
                const auto& projection = pair.projections[p];
                using Type = RT64::Projection::Type;
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
            __android_log_print(ANDROID_LOG_INFO, log_tag, "begin request=%s", request);
        }
        if (remaining > 0 && workload.fbPairCount > 0) {
            log_workload(workload);
            if (--remaining == 0) __android_log_print(ANDROID_LOG_INFO, log_tag, "end request=%s", request);
        }
    }
}

// The producer still owns this workload until advanceToNextWorkload publishes it.
void original_advance(RT64::WorkloadQueue* queue) asm("__real__ZN4RT6413WorkloadQueue21advanceToNextWorkloadEv");
void capture_advance(RT64::WorkloadQueue* queue) asm("__wrap__ZN4RT6413WorkloadQueue21advanceToNextWorkloadEv");

void capture_advance(RT64::WorkloadQueue* queue) {
    capture_hud(queue->workloads[queue->writeCursor]);
    original_advance(queue);
}
