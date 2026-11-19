set(rt64_projection_source "${RT64_DIR}/src/render/rt64_projection_processor.cpp")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${rt64_projection_source}")
file(READ "${rt64_projection_source}" rt64_projection)

set(old_include [=[#include "rt64_projection_processor.h"]=])
set(new_include [=[
#include "rt64_projection_processor.h"
#include "podone_viewport.h"
]=])
set(old_horizontal [=[                    bool horizontalRatio = (intersectionRect.width(true, true) > intersectionRect.height(true, true));]=])
set(new_horizontal [=[
                    const float pixelAspect = proj.type == Projection::Type::Perspective
                        ? podone_viewport::pixel_aspect(workload, fbPair.colorImage.width) : 1.0f;
                    bool horizontalRatio = podone_viewport::landscape(intersectionRect, pixelAspect);
]=])
set(old_debug_camera [=[
            if (workload.debuggerCamera.enabled && (proj.type == Projection::Type::Perspective) && (workload.debuggerCamera.sceneIndex == sceneIndex)) {
]=])
set(new_debug_camera [=[
            const bool debuggerCamera = workload.debuggerCamera.enabled && (proj.type == Projection::Type::Perspective)
                && (workload.debuggerCamera.sceneIndex == sceneIndex);
            if (debuggerCamera) {
]=])
set(old_combined [=[
            viewProjMatrix = hlslpp::mul(viewMatrix, projMatrix);

            interop::float4x4 &prevViewProjTransform = drawData.prevViewProjTransforms[proj.transformsIndex];
            prevViewProjTransform = hlslpp::mul(prevViewTransform, prevProjTransform);
]=])
set(new_combined [=[
            // The RSP's combined matrix retains depth and offsets that can be
            // lost when RT64 splits it into separate view and projection matrices.
            adjustProjectionMatrix(viewProjMatrix, projRatioScale);
            interop::float4x4 &prevViewProjTransform = drawData.prevViewProjTransforms[proj.transformsIndex];
            prevViewProjTransform = viewProjMatrix;

            const bool interpolateCamera = rigidBody && (rigidBody->lerpTranslation || rigidBody->lerpRotation
                || rigidBody->lerpScale || rigidBody->lerpSkew || rigidBody->lerpPerspective);
            if (debuggerCamera || (interpolateCamera && p.curFrameWeight < 1.0f)) {
                viewProjMatrix = hlslpp::mul(viewMatrix, projMatrix);
            }
            if (debuggerCamera || (interpolateCamera && p.prevFrameWeight < 1.0f)) {
                prevViewProjTransform = hlslpp::mul(prevViewTransform, prevProjTransform);
            }
]=])

foreach(section include horizontal debug_camera combined)
    string(FIND "${rt64_projection}" "${old_${section}}" match_start)
    if(match_start EQUAL -1)
        message(FATAL_ERROR "Pod One's RT64 viewport patch needs to be updated for ${rt64_projection_source}")
    endif()
    string(REPLACE "${old_${section}}" "${new_${section}}" rt64_projection "${rt64_projection}")
endforeach()

podone_patch_rt64_source("${rt64_projection_source}" "${rt64_projection}")
