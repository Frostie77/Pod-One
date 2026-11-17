set(rt64_framebuffer_source "${RT64_DIR}/src/render/rt64_framebuffer_renderer.cpp")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${rt64_framebuffer_source}")
file(READ "${rt64_framebuffer_source}" rt64_framebuffer)

set(old_include [=[#include "rt64_framebuffer_renderer.h"]=])
set(new_include [=[
#include "rt64_framebuffer_renderer.h"
#include "podone_hud.h"
#include "podone_viewport.h"
]=])
set(old_frame [=[        const float commonHeight = float(p.targetHeight);]=])
set(new_frame [=[
        const float commonHeight = float(p.targetHeight);
        const float pixelAspect = podone_viewport::pixel_aspect(*p.curWorkload, p.fbWidth);
        const bool adjustPerspective = std::abs(scissorRatio * pixelAspect / p.aspectRatioSource - 1.0f) < SimilarityPercentage;
        const float perspectiveAspectScale = adjustPerspective ? (p.aspectRatioTarget / p.aspectRatioSource) : 1.0f;
        const bool traceHud = podone_hud::tracing(p.curWorkload->workloadId);
        // RT64 supplies a 1:1 aspect for framebuffer readback. Apply HUD
        // offsets in the presentation workload, including at the native ratio.
        const auto hudLayout = p.fbWidth == podone_hud::framebuffer_width && p.aspectRatioSource > 1.0f
            ? podone_hud::race_layout(fbPair) : podone_hud::Layout::None;
        const float hudOffset = hudLayout != podone_hud::Layout::None
            ? podone_hud::edge_offset(p.resolutionScale.x, p.resolutionScale.y) : 0.0f;
        unsigned anchoredHudRects = 0;
]=])
set(old_projection [=[            float projInvRatioScale = 1.0f / aspectRatioScale;]=])
set(new_projection [=[
            const bool perspective = proj.type == Projection::Type::Perspective;
            float projInvRatioScale = 1.0f / (perspective ? perspectiveAspectScale : aspectRatioScale);
]=])
set(old_horizontal [=[                bool horizontalRatio = !intersectionRect.isEmpty() && (intersectionRect.width(true, true) > intersectionRect.height(true, true));]=])
set(new_horizontal [=[
                bool horizontalRatio = podone_viewport::landscape(intersectionRect, perspective ? pixelAspect : 1.0f);
]=])
set(old_call [=[                        float horizontalMisalignment = 0.0f;]=])
set(new_call [=[
                        float horizontalMisalignment = 0.0f;
                        podone_hud::Anchor hudAnchor = podone_hud::Anchor::None;
]=])
set(old_rect [=[
                            // (2026-10-21: anchoring each rectangle to the left / right edge by where it sits was tried and
                            // dropped: Racer draws text one letter per rectangle, so words were torn apart. Rectangles stay
                            // centred at their real width.)
                            RenderViewport viewportRect = convertViewportRect(call.callDesc.rect, p.resolutionScale, p.fbWidth, invRatioScale, extOriginPercentage, horizontalMisalignment, call.callDesc.rectLeftOrigin, call.callDesc.rectRightOrigin);
]=])
set(new_rect [=[
                            if (hudOffset > 0.0f && regularOrigins && !tileCopiesUsed && !coversScissorWidth) {
                                hudAnchor = podone_hud::anchor(call.callDesc, hudLayout);
                                if (hudAnchor != podone_hud::Anchor::None) {
                                    invRatioScale = hudAnchor == podone_hud::Anchor::Stretch
                                        ? 1.0f : float(p.resolutionScale.y / p.resolutionScale.x);
                                    ++anchoredHudRects;
                                }
                            }
                            RenderViewport viewportRect = convertViewportRect(call.callDesc.rect, p.resolutionScale, p.fbWidth, invRatioScale, extOriginPercentage, horizontalMisalignment, call.callDesc.rectLeftOrigin, call.callDesc.rectRightOrigin);
                            podone_hud::place(viewportRect, hudAnchor, hudOffset);
                            if (regularOrigins && !tileCopiesUsed) {
                                viewportRect.y += podone_hud::banner_offset(call.callDesc, hudLayout, p.resolutionScale.y);
                            }
]=])
set(old_scissor [=[                        triangles.scissor = convertFixedRect(call.callDesc.scissorRect, p.resolutionScale, p.fbWidth, invRatioScale, extOriginPercentage, int32_t(horizontalMisalignment), call.callDesc.scissorLeftOrigin, call.callDesc.scissorRightOrigin);]=])
set(new_scissor [=[
                        const float scissorScale = hudAnchor == podone_hud::Anchor::None ? invRatioScale : 1.0f;
                        triangles.scissor = convertFixedRect(call.callDesc.scissorRect, p.resolutionScale, p.fbWidth, scissorScale, extOriginPercentage, int32_t(horizontalMisalignment), call.callDesc.scissorLeftOrigin, call.callDesc.scissorRightOrigin);
]=])
set(old_finish [=[        checkRasterScene(rasterScene);]=])
set(new_finish [=[
        if (traceHud) {
            podone_hud::trace_layout(p.curWorkload->workloadId, p.fbPairIndex, p.fbWidth, p.fbHeight,
                p.resolutionScale.x, p.resolutionScale.y, p.aspectRatioSource, p.aspectRatioTarget,
                hudLayout, hudOffset, anchoredHudRects);
        }
        checkRasterScene(rasterScene);
]=])

foreach(section include frame projection horizontal call rect scissor finish)
    string(FIND "${rt64_framebuffer}" "${old_${section}}" match_start)
    if (match_start EQUAL -1)
        message(FATAL_ERROR "Pod One's HUD patch needs to be updated for ${rt64_framebuffer_source}")
    endif()
    string(REPLACE "${old_${section}}" "${new_${section}}" rt64_framebuffer "${rt64_framebuffer}")
endforeach()

podone_patch_rt64_source("${rt64_framebuffer_source}" "${rt64_framebuffer}")
