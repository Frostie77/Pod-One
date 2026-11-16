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

foreach(section projection transforms vertices match ticks)
    string(FIND "${rt64_workloads}" "${old_${section}}" match_start)
    if(match_start EQUAL -1)
        message(FATAL_ERROR "Pod One's RT64 frame matching patch needs to be updated")
    endif()
    string(REPLACE "${old_${section}}" "${new_${section}}" rt64_workloads "${rt64_workloads}")
endforeach()
podone_patch_rt64_source("${rt64_workloads_source}" "${rt64_workloads}")
