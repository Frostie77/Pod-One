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

foreach(section include horizontal)
    string(FIND "${rt64_projection}" "${old_${section}}" match_start)
    if(match_start EQUAL -1)
        message(FATAL_ERROR "Pod One's RT64 viewport patch needs to be updated for ${rt64_projection_source}")
    endif()
    string(REPLACE "${old_${section}}" "${new_${section}}" rt64_projection "${rt64_projection}")
endforeach()

podone_patch_rt64_source("${rt64_projection_source}" "${rt64_projection}")
