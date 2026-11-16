set(rt64_framebuffer_source "${RT64_DIR}/src/render/rt64_framebuffer_renderer.cpp")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${rt64_framebuffer_source}")
file(READ "${rt64_framebuffer_source}" rt64_framebuffer)

set(old_include [=[#include "rt64_framebuffer_renderer.h"]=])
set(new_include [=[
#include "rt64_framebuffer_renderer.h"
#include "podone_hud.h"
]=])
set(old_frame [=[        const float commonHeight = float(p.targetHeight);]=])
set(new_frame [=[
        const float commonHeight = float(p.targetHeight);
        const float hudOffset = p.fbWidth == podone_hud::framebuffer_width && wideWidth > originalWidth
            && podone_hud::has_race_hud(fbPair) ? podone_hud::edge_offset(p.resolutionScale.x, p.resolutionScale.y) : 0.0f;
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
                                hudAnchor = podone_hud::anchor(call.callDesc);
                                if (hudAnchor != podone_hud::Anchor::None) {
                                    invRatioScale = p.resolutionScale.y / p.resolutionScale.x;
                                }
                            }
                            RenderViewport viewportRect = convertViewportRect(call.callDesc.rect, p.resolutionScale, p.fbWidth, invRatioScale, extOriginPercentage, horizontalMisalignment, call.callDesc.rectLeftOrigin, call.callDesc.rectRightOrigin);
                            podone_hud::place(viewportRect, hudAnchor, hudOffset);
]=])
set(old_scissor [=[                        triangles.scissor = convertFixedRect(call.callDesc.scissorRect, p.resolutionScale, p.fbWidth, invRatioScale, extOriginPercentage, int32_t(horizontalMisalignment), call.callDesc.scissorLeftOrigin, call.callDesc.scissorRightOrigin);]=])
set(new_scissor [=[
                        const float scissorScale = hudAnchor == podone_hud::Anchor::None ? invRatioScale : 1.0f;
                        triangles.scissor = convertFixedRect(call.callDesc.scissorRect, p.resolutionScale, p.fbWidth, scissorScale, extOriginPercentage, int32_t(horizontalMisalignment), call.callDesc.scissorLeftOrigin, call.callDesc.scissorRightOrigin);
]=])

foreach(section include frame call rect scissor)
    string(FIND "${rt64_framebuffer}" "${old_${section}}" match_start)
    if (match_start EQUAL -1)
        message(FATAL_ERROR "Pod One's HUD patch needs to be updated for ${rt64_framebuffer_source}")
    endif()
    string(REPLACE "${old_${section}}" "${new_${section}}" rt64_framebuffer "${rt64_framebuffer}")
endforeach()

podone_patch_rt64_source("${rt64_framebuffer_source}" "${rt64_framebuffer}")
