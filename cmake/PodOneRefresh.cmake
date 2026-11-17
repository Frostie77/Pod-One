set(rt64_transforms_source "${RT64_DIR}/src/render/rt64_transform_processor.cpp")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${rt64_transforms_source}")
file(READ "${rt64_transforms_source}" rt64_transforms)
set(old_upload [=[            const bool prevFrameValid = (p.prevFrame != nullptr);]=])
set(new_upload [=[
            // process() only builds interpolated matrices for a matched workload.
            const bool prevFrameValid = (p.prevFrame != nullptr) && p.curFrame->frameMap.workloads[w].mapped;
]=])
string(FIND "${rt64_transforms}" "${old_upload}" upload_match)
if(upload_match EQUAL -1)
    message(FATAL_ERROR "Pod One's RT64 transform upload patch needs to be updated")
endif()
string(REPLACE "${old_upload}" "${new_upload}" rt64_transforms "${rt64_transforms}")
podone_patch_rt64_source("${rt64_transforms_source}" "${rt64_transforms}")

set(rt64_workloads_source "${RT64_DIR}/src/hle/rt64_workload_queue.cpp")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${rt64_workloads_source}")
file(READ "${rt64_workloads_source}" rt64_workloads)
set(old_capture_include [=[#include "rt64_workload_queue.h"]=])
set(new_capture_include [=[
#include "rt64_workload_queue.h"
#include "podone_geometry.h"
]=])
set(old_capture_setup [=[
            // Record all framebuffer pairs.
]=])
set(new_capture_setup [=[
            podone_geometry::GpuCapture capture(*ext.workloadGraphicsWorker, workload, curFrameWeight, workloadConfig.aspectRatioScale);

            // Record all framebuffer pairs.
]=])
set(old_capture_depth [=[                    framebufferRenderer->recordFramebuffer(ext.workloadGraphicsWorker, framebufferIndex++);]=])
set(new_capture_depth [=[
                    const bool captureDepth = colorTarget && depthTarget && fbPair.depthWrite;
                    if (captureDepth) capture.before_draw(*depthTarget, f);
                    framebufferRenderer->recordFramebuffer(ext.workloadGraphicsWorker, framebufferIndex++);
                    if (captureDepth) capture.after_draw(*depthTarget, f);
]=])
set(old_capture_finish [=[
            workerMutex.unlock();

            // Update the GPU profiler with the results from the timestamps of the frame.
]=])
set(new_capture_finish [=[
            workerMutex.unlock();
            capture.finish();

            // Update the GPU profiler with the results from the timestamps of the frame.
]=])
set(old_projection [=[        const bool processProjections = aspectRatioAdjustment || prevFrame.matched|| curFrame.isDebuggerCameraEnabled(*this);]=])
set(new_projection [=[        const bool processProjections = aspectRatioAdjustment || (curFrame.matched && prevFrame.matched) || curFrame.isDebuggerCameraEnabled(*this);]=])
set(old_transforms [=[        const bool processTransforms = prevFrame.matched;]=])
set(new_transforms [=[        const bool processTransforms = curFrame.matched && prevFrame.matched;]=])
set(old_vertices [=[            const bool processWorldVertices = prevFrame.matched;]=])
set(new_vertices [=[            const bool processWorldVertices = processTransforms;]=])
set(old_match [=[
                const bool requiresFrameMatching = (workloadConfig.targetRate > 0) || workloadConfig.raytracingEnabled;
                bool generateInterpolatedFrames = false;
]=])
set(new_match [=[
                // Use the game's transforms on workloads that will only render a native frame.
                const bool displayRateAboveOriginal = workload.viOriginalRate > 0 && workloadConfig.targetRate > workload.viOriginalRate;
                const bool generateInterpolatedFrames = !workload.paused && displayRateAboveOriginal && !interpolationTargetKey.isEmpty();
                const bool requiresFrameMatching = generateInterpolatedFrames || workloadConfig.raytracingEnabled;
]=])
set(old_ticks [=[

                    const bool displayRateAboveOriginal = (workload.viOriginalRate > 0) && (workloadConfig.targetRate > workload.viOriginalRate);
                    generateInterpolatedFrames = !workload.paused && displayRateAboveOriginal && !interpolationTargetKey.isEmpty();

                    const bool resetTicks = !generateInterpolatedFrames || (originalRateForTicks != workload.viOriginalRate) || (displayRateForTicks != workloadConfig.targetRate) || !displayRateAboveOriginal;
                    if (resetTicks) {
                        logicalTicks = 0;
                        displayTicks = 0;
                        originalRateForTicks = workload.viOriginalRate;
                        displayRateForTicks = workloadConfig.targetRate;
                    }
                }
]=])
set(new_ticks [=[
                }

                if (!generateInterpolatedFrames || originalRateForTicks != workload.viOriginalRate || displayRateForTicks != workloadConfig.targetRate) {
                    logicalTicks = 0;
                    displayTicks = 0;
                    originalRateForTicks = workload.viOriginalRate;
                    displayRateForTicks = workloadConfig.targetRate;
                    frameReduction = false;
                }
]=])

foreach(section projection transforms vertices match ticks capture_include capture_setup capture_depth capture_finish)
    string(FIND "${rt64_workloads}" "${old_${section}}" match_start)
    if(match_start EQUAL -1)
        message(FATAL_ERROR "Pod One's RT64 frame matching patch needs to be updated")
    endif()
    string(REPLACE "${old_${section}}" "${new_${section}}" rt64_workloads "${rt64_workloads}")
endforeach()
podone_patch_rt64_source("${rt64_workloads_source}" "${rt64_workloads}")

set(rt64_match_source "${RT64_DIR}/src/hle/rt64_game_frame.cpp")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${rt64_match_source}")
file(READ "${rt64_match_source}" rt64_match)

set(old_candidates [=[
        const RigidBody *prevRigidBody;
        const hlslpp::float4x4 &firstCurViewProj = firstCurWorkload.drawData.viewProjTransforms[firstCurProj.transformsIndex];
]=])
set(new_candidates [=[
        // Automatic call matching uses material and triangle count. Require the
        // same local geometry as well so a culled object cannot match a
        // different object that happens to share its draw state.
        auto geometryHashes = [](const DrawData &draw, std::vector<uint64_t> &hashes) {
            hashes.resize(draw.worldTransformVertexIndices.size());
            for (size_t i = 0; i < hashes.size(); ++i) {
                const size_t first = size_t(draw.worldTransformVertexIndices[i]) * 3;
                const size_t count = size_t(draw.worldTransformVertexCount(uint32_t(i))) * 3;
                hashes[i] = count > 0 ? XXH3_64bits(draw.posFloats.data() + first, count * sizeof(float)) : 0;
            }
        };
        thread_local std::vector<uint64_t> curGeometry, prevGeometry;
        geometryHashes(firstCurWorkload.drawData, curGeometry);
        geometryHashes(firstPrevWorkload.drawData, prevGeometry);

        const RigidBody *prevRigidBody;
        const hlslpp::float4x4 &firstCurViewProj = firstCurWorkload.drawData.viewProjTransforms[firstCurProj.transformsIndex];
]=])
set(old_test [=[        for (const IndexPair &indices : transformCheckSet) {]=])
set(new_test [=[
        for (const IndexPair &indices : transformCheckSet) {
            const auto &curDraw = firstCurWorkload.drawData;
            const auto &curGroup = curDraw.transformGroups[curDraw.worldTransformGroups[indices.first]];
            if (curGroup.matrixId == G_EX_ID_AUTO &&
                (curGeometry[indices.first] == 0 || curGeometry[indices.first] != prevGeometry[indices.second])) {
                continue;
            }
]=])

foreach(section candidates test)
    string(FIND "${rt64_match}" "${old_${section}}" match_start)
    if(match_start EQUAL -1)
        message(FATAL_ERROR "Pod One's RT64 geometry matching patch needs to be updated")
    endif()
    string(REPLACE "${old_${section}}" "${new_${section}}" rt64_match "${rt64_match}")
endforeach()
podone_patch_rt64_source("${rt64_match_source}" "${rt64_match}")
