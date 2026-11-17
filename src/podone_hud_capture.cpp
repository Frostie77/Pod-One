#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <limits>
#include <string>
#include <android/log.h>
#include <sys/system_properties.h>
#include <SDL_system.h>

#include "hle/rt64_workload_queue.h"
#include "podone_geometry.h"
#include "podone_hud.h"

namespace {
    constexpr char log_tag[] = "PodOneHud";
    constexpr char geometry_tag[] = "PodOneGeometry";
    constexpr unsigned capture_workloads = 2;
    constexpr unsigned max_calls_per_workload = 240;
    std::array<std::atomic<uint64_t>, capture_workloads> trace_workloads{};
    std::atomic<uint64_t> geometry_workload{0};
    std::string geometry_report_path;
    std::string geometry_request;
    using Clock = std::chrono::steady_clock;
    using namespace std::chrono_literals;
    constexpr auto clock_update_window = 250ms;
    std::atomic<Clock::time_point> last_clock_advance{};
    std::atomic<Clock::time_point> last_original_start{};

    uint64_t clock_signature(const RT64::Workload& workload) {
        // The USA ROM loads its race-timer font atlas once, then picks the
        // digits with texture coordinates. Use native HUD coordinates.
        constexpr uint32_t timer_atlas = 0x0009D8E0;
        constexpr uint64_t timer_atlas_hash = 0xD665535D5068A63Cull;
        constexpr uint64_t basis = 14695981039346656037ull;
        constexpr uint64_t prime = 1099511628211ull;
        for (unsigned f = 0; f < workload.fbPairCount; ++f) {
            const auto& pair = workload.fbPairs[f];
            if (pair.colorImage.width != podone_hud::framebuffer_width) continue;
            const auto layout = podone_hud::race_layout(pair);
            if (layout == podone_hud::Layout::None) continue;
            uint64_t signature = basis;
            unsigned digits = 0;
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
                    if (!podone_hud::timer_digit(desc, layout)) continue;
                    // A workload can reuse the font tile without a TMEM load.
                    if (!timer_font && workload.drawData.callTiles[desc.tileIndex].tmemHashOrID != timer_atlas_hash) continue;

                    const size_t uv = size_t(call.meshDesc.rawVertexStart) * 2;
                    const auto& coords = workload.drawData.triTcFloats;
                    if (uv + 1 >= coords.size()) continue;
                    ++digits;
                    signature = (signature ^ uint32_t(desc.rect.ulx)) * prime;
                    signature = (signature ^ uint32_t(std::lround(coords[uv] * 32.0f))) * prime;
                    signature = (signature ^ uint32_t(std::lround(coords[uv + 1] * 32.0f))) * prime;
                }
            }
            if (digits >= 6) return signature;
        }
        return 0;
    }

    void observe_race_clock(const RT64::Workload& workload) {
        static uint64_t last = 0, previous = 0;
        static Clock::time_point last_sample{};
        const uint64_t current = clock_signature(workload);
        if (current == 0) return;

        const auto now = Clock::now();
        if (now - last_sample >= clock_update_window) last = previous = 0;
        last_sample = now;
        if (current != last) {
            // Two buffered lists may alternate even in Racer's pause menu.
            // Require a third value before treating the timer as advancing.
            if (previous != 0 && current != previous) last_clock_advance.store(now);
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

    void write_geometry(std::ostream& report, const RT64::Workload& workload) {
        const auto& draw = workload.drawData;
        report.precision(std::numeric_limits<float>::max_digits10);
        report << "workload " << workload.workloadId << " vi " << unsigned(workload.viFbSize.x)
               << ' ' << unsigned(workload.viFbSize.y) << " aspect " << workload.viAspectRatio << '\n';
        for (size_t i = 0; i < draw.rspViewports.size(); ++i) {
            const auto& viewport = draw.rspViewports[i];
            report << "viewport " << i << " scale " << float(viewport.scale.x) << ' ' << float(viewport.scale.y)
                   << ' ' << float(viewport.scale.z) << " translate " << float(viewport.translate.x)
                   << ' ' << float(viewport.translate.y) << ' ' << float(viewport.translate.z) << '\n';
        }

        auto rect = [&report](const RT64::FixedRect& r) {
            report << ' ' << r.ulx << ' ' << r.uly << ' ' << r.lrx << ' ' << r.lry;
        };
        for (unsigned f = 0; f < workload.fbPairCount; ++f) {
            const auto& pair = workload.fbPairs[f];
            report << "pair " << f << " color " << std::hex << pair.colorImage.address
                   << " depth " << pair.depthImage.address << std::dec << " width " << pair.colorImage.width
                   << " depth_clear " << pair.fastPaths.clearDepthOnly << " color_bounds";
            rect(pair.drawColorRect);
            report << " depth_bounds";
            rect(pair.drawDepthRect);
            report << '\n';
            for (unsigned p = 0; p < pair.projectionCount; ++p) {
                const auto& projection = pair.projections[p];
                for (unsigned i = 0; i < projection.gameCallCount; ++i) {
                    const auto& call = projection.gameCalls[i];
                    const auto& desc = call.callDesc;
                    if (pair.fastPaths.clearDepthOnly && desc.otherMode.cycleType() == G_CYC_FILL) {
                        report << "clear " << std::hex << desc.fillColor << std::dec << " rect";
                        rect(desc.rect);
                        report << '\n';
                    }
                    if (!projection.usesViewport()) continue;

                    const size_t begin = call.meshDesc.faceIndicesStart;
                    const size_t end = std::min(draw.faceIndices.size(), begin + size_t(desc.triangleCount) * 3);
                    report << "call " << p << ' ' << i << " projection " << projection.transformsIndex
                           << " depth_mode " << desc.otherMode.zMode() << " compare " << desc.otherMode.zCmp()
                           << " write " << desc.otherMode.zUpd() << " source " << desc.otherMode.zSource()
                           << " primitive_depth " << float(desc.rdpParams.primDepth.x)
                           << " cull " << ((desc.geometryMode & desc.cullBothMask) != 0)
                           << " no_near_clip " << desc.NoN << " scissor";
                    rect(desc.scissorRect);
                    report << " other_mode " << std::hex << desc.otherMode.H << ' ' << desc.otherMode.L
                           << " combiner " << desc.colorCombiner.H << ' ' << desc.colorCombiner.L << std::dec
                           << " blend_alpha " << float(desc.rdpParams.blendColor.w) << '\n';
                    for (size_t j = begin; j + 2 < end; j += 3) {
                        report << "tri";
                        for (size_t k = j; k < j + 3; ++k) report << ' ' << draw.faceIndices[k];
                        report << '\n';
                    }
                }
            }
        }
        report << "vertex_columns index viewport screen_x screen_y screen_z clip_x clip_y clip_z clip_w rebuilt_clip_x rebuilt_clip_y rebuilt_clip_z rebuilt_clip_w\n";
        for (size_t v = 0; v < draw.vertexCount(); ++v) {
            const auto& position = draw.posScreen[v];
            const auto& clip = draw.posTransformed[v];
            const auto& model = draw.worldTransforms[draw.worldIndices[v]];
            const auto& camera = draw.viewProjTransforms[draw.viewProjIndices[v]];
            const hlslpp::float4 local(draw.posFloats[v * 3], draw.posFloats[v * 3 + 1], draw.posFloats[v * 3 + 2], 1.0f);
            const auto rebuilt = hlslpp::mul(local, hlslpp::mul(model, camera));
            report << "vertex " << v << ' ' << draw.viewProjIndices[v] << ' ' << float(position.x) << ' '
                   << float(position.y) << ' ' << float(position.z) << ' ' << float(clip.x) << ' ' << float(clip.y)
                   << ' ' << float(clip.z) << ' ' << float(clip.w) << ' ' << float(rebuilt.x) << ' ' << float(rebuilt.y)
                   << ' ' << float(rebuilt.z) << ' ' << float(rebuilt.w) << '\n';
        }
        for (size_t i = 0; i + 1 < draw.modifyPosUints.size(); i += 2) {
            report << "vertex_override " << draw.modifyPosUints[i] << ' ' << draw.modifyPosUints[i + 1] << '\n';
        }
    }

    void capture_geometry(const RT64::Workload& workload) {
        static char last_request[PROP_VALUE_MAX] = {};
        char request[PROP_VALUE_MAX] = {};
        if (__system_property_get("debug.podone.geometry", request) == 0 || std::strcmp(request, "0") == 0
                || std::strcmp(last_request, request) == 0 || workload.drawData.faceIndices.empty()
                || geometry_workload.load() != 0) return;
        std::strcpy(last_request, request);

        const char* storage = SDL_AndroidGetInternalStoragePath();
        if (storage == nullptr) return;
        geometry_report_path = std::string(storage) + "/pod-one-geometry.txt";
        geometry_request = request;
        const std::string pending_path = geometry_report_path + ".tmp";
        std::ofstream report(pending_path);
        write_geometry(report, workload);
        report.close();
        if (report) {
            // Publish the request only after the CPU report is ready to append to.
            geometry_workload.store(workload.workloadId);
        } else {
            std::remove(pending_path.c_str());
            __android_log_print(ANDROID_LOG_ERROR, geometry_tag, "capture failed request=%s", request);
        }
    }
}

podone_geometry::GpuCapture::GpuCapture(RT64::RenderWorker& worker, const RT64::Workload& workload,
                                        float frame_weight, float aspect_scale)
    : worker(worker), frame_weight(frame_weight), aspect_scale(aspect_scale) {
    if (workload.workloadId == 0 || workload.workloadId != geometry_workload.load()) return;
    workload_id = workload.workloadId;
    vertex_count = workload.drawData.vertexCount();
    const uint64_t block_size = uint64_t(vertex_count) * 4 * sizeof(float);
    const auto& output = workload.outputBuffers;
    if (block_size == 0 || !output.screenPosBuffer.buffer || !output.shadedColBuffer.buffer) return;
    vertices = worker.device->createBuffer(RenderBufferDesc::ReadbackBuffer(2 * block_size));
    if (!vertices) return;

    const RenderBufferBarrier barriers[] = {
        { output.screenPosBuffer.buffer.get(), RenderBufferAccess::READ },
        { output.shadedColBuffer.buffer.get(), RenderBufferAccess::READ },
        { vertices.get(), RenderBufferAccess::WRITE }
    };
    worker.commandList->barriers(RenderBarrierStage::COPY, barriers, uint32_t(std::size(barriers)));
    worker.commandList->copyBufferRegion(vertices->at(0), output.screenPosBuffer.buffer->at(0), block_size);
    worker.commandList->copyBufferRegion(vertices->at(block_size), output.shadedColBuffer.buffer->at(0), block_size);
    worker.commandList->barriers(RenderBarrierStage::GRAPHICS, barriers, 2);
    worker.commandList->barriers(RenderBarrierStage::COPY, RenderBufferBarrier(vertices.get(), RenderBufferAccess::READ));
}

void podone_geometry::GpuCapture::before_draw(const RT64::RenderTarget& depth, unsigned pair) {
    if (workload_id == 0 || depth_samples || depth.format != RenderFormat::D32_FLOAT
            || depth.multisampling.sampleCount != 1 || !depth.texture) return;
    const uint64_t pixels = uint64_t(depth.width) * depth.height;
    if (pixels == 0 || pixels > 8 * 1024 * 1024) return;
    depth_samples = worker.device->createBuffer(RenderBufferDesc::ReadbackBuffer(2 * pixels * sizeof(float)));
    if (!depth_samples) return;
    depth_width = depth.width;
    depth_height = depth.height;
    depth_scale_x = depth.resolutionScale.x;
    depth_scale_y = depth.resolutionScale.y;
    depth_pair = pair;
    copy_depth(depth, 0);
}

void podone_geometry::GpuCapture::after_draw(const RT64::RenderTarget& depth, unsigned pair) {
    if (!depth_samples || pair != depth_pair) return;
    copy_depth(depth, 1);
    worker.commandList->barriers(RenderBarrierStage::COPY, RenderBufferBarrier(depth_samples.get(), RenderBufferAccess::READ));
    depth_after = true;
}

void podone_geometry::GpuCapture::copy_depth(const RT64::RenderTarget& depth, unsigned layer) {
    const uint64_t layer_bytes = uint64_t(depth_width) * depth_height * sizeof(float);
    const RenderBufferBarrier buffer(depth_samples.get(), RenderBufferAccess::WRITE);
    const RenderTextureBarrier texture(depth.texture.get(), RenderTextureLayout::COPY_SOURCE);
    worker.commandList->barriers(RenderBarrierStage::COPY, &buffer, 1, &texture, 1);
    const auto src = RenderTextureCopyLocation::Subresource(depth.texture.get());
    const auto dst = RenderTextureCopyLocation::PlacedFootprint(depth_samples.get(), RenderFormat::D32_FLOAT,
        depth_width, depth_height, 1, depth_width, layer_bytes * layer);
    worker.commandList->copyTextureRegion(dst, src);
}

void podone_geometry::GpuCapture::finish() {
    if (workload_id == 0) return;
    const std::string pending_path = geometry_report_path + ".tmp";
    std::ofstream report(pending_path, std::ios::app);
    report.precision(std::numeric_limits<float>::max_digits10);
    report << "gpu_frame " << workload_id << " weight " << frame_weight << " aspect_scale " << aspect_scale << '\n';
    if (vertices) {
        const RenderRange range(0, uint64_t(vertex_count) * 8 * sizeof(float));
        const auto* values = static_cast<const float*>(vertices->map(0, &range));
        if (values) {
            report << "gpu_vertex_columns index screen_x screen_y screen_z clip_w color_r color_g color_b color_a\n";
            const float* colors = values + size_t(vertex_count) * 4;
            for (uint32_t i = 0; i < vertex_count; ++i) {
                report << "gpu_vertex " << i;
                for (unsigned j = 0; j < 4; ++j) report << ' ' << values[size_t(i) * 4 + j];
                for (unsigned j = 0; j < 4; ++j) report << ' ' << colors[size_t(i) * 4 + j];
                report << '\n';
            }
            vertices->unmap();
        }
    }
    if (depth_after) {
        const size_t pixels = size_t(depth_width) * depth_height;
        const RenderRange range(0, 2 * pixels * sizeof(float));
        const auto* values = static_cast<const float*>(depth_samples->map(0, &range));
        if (values) {
            constexpr unsigned step = 8;
            report << "gpu_depth pair " << depth_pair << " width " << depth_width << " height " << depth_height
                   << " scale " << depth_scale_x << ' ' << depth_scale_y << " step " << step << '\n';
            for (unsigned y = step / 2; y < depth_height; y += step) {
                for (unsigned x = step / 2; x < depth_width; x += step) {
                    const size_t i = size_t(y) * depth_width + x;
                    report << "gpu_depth_sample " << x << ' ' << y << ' ' << values[i] << ' ' << values[pixels + i] << '\n';
                }
            }
            depth_samples->unmap();
        }
    }
    report.close();
    if (report && std::rename(pending_path.c_str(), geometry_report_path.c_str()) == 0) {
        __android_log_print(ANDROID_LOG_INFO, geometry_tag, "saved request=%s", geometry_request.c_str());
    } else {
        std::remove(pending_path.c_str());
        __android_log_print(ANDROID_LOG_ERROR, geometry_tag, "capture failed request=%s", geometry_request.c_str());
    }
    geometry_workload.store(0);
}

bool podone_hud::race_clock_running() {
    const auto now = Clock::now();
    return now - last_clock_advance.load() < clock_update_window && now - last_original_start.load() >= 250ms;
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
                             float source_aspect, float target_aspect, Layout layout, float offset, unsigned anchored) {
    const char* hud_mode = layout == Layout::ProgressRing ? "progress-ring" : layout == Layout::Minimap ? "minimap" : "none";
    __android_log_print(ANDROID_LOG_INFO, log_tag,
        "layout workload=%llu pair=%u fb=%ux%u scale=%.3f,%.3f aspect=%.3f->%.3f race_hud=%u offset=%.0f anchored=%u mode=%s",
        static_cast<unsigned long long>(workload), pair, width, height, x_scale, y_scale,
        source_aspect, target_aspect, unsigned(layout != Layout::None), offset, anchored, hud_mode);
}

// The producer still owns this workload until advanceToNextWorkload publishes it.
void original_advance(RT64::WorkloadQueue* queue) asm("__real__ZN4RT6413WorkloadQueue21advanceToNextWorkloadEv");
void capture_advance(RT64::WorkloadQueue* queue) asm("__wrap__ZN4RT6413WorkloadQueue21advanceToNextWorkloadEv");

void capture_advance(RT64::WorkloadQueue* queue) {
    const auto& workload = queue->workloads[queue->writeCursor];
    observe_race_clock(workload);
    capture_hud(workload);
    capture_geometry(workload);
    original_advance(queue);
}
