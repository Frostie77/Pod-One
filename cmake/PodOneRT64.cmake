function(podone_patch_rt64_source source contents)
    get_filename_component(filename "${source}" NAME)
    get_filename_component(source_dir "${source}" DIRECTORY)
    set(patched_source "${CMAKE_CURRENT_BINARY_DIR}/podone/${filename}")
    file(CONFIGURE OUTPUT "${patched_source}" CONTENT "@contents@" @ONLY)
    get_target_property(rt64_sources rt64 SOURCES)
    if(NOT source IN_LIST rt64_sources)
        message(FATAL_ERROR "Pod One could not replace ${source} in the RT64 target")
    endif()
    list(REMOVE_ITEM rt64_sources "${source}")
    list(APPEND rt64_sources "${patched_source}")
    set_property(TARGET rt64 PROPERTY SOURCES ${rt64_sources})
    set_property(SOURCE "${patched_source}" TARGET_DIRECTORY rt64 APPEND PROPERTY INCLUDE_DIRECTORIES
        "${source_dir}" "${CMAKE_SOURCE_DIR}/include")
endfunction()

# Correct framebuffer coverage for triangles crossing the camera plane.
set(rt64_rsp_source "${RT64_DIR}/src/hle/rt64_rsp.cpp")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${rt64_rsp_source}")
file(READ "${rt64_rsp_source}" rt64_rsp)

set(old_bounds [=[
        bool visibleTri = true;
        const bool usesCulling = geometryMode & cullBothMask;
        if (usesCulling) {
]=])
set(new_bounds [=[
        bool crossesCameraPlane = false;
        if (projType == Projection::Type::Perspective) {
            const auto &transformed = workload.drawData.posTransformed;
            const float aw = transformed[globalIndices[0]].w;
            const float bw = transformed[globalIndices[1]].w;
            const float cw = transformed[globalIndices[2]].w;
            crossesCameraPlane = std::min({ aw, bw, cw }) <= 0.0f && std::max({ aw, bw, cw }) > 0.0f;
        }

        bool visibleTri = true;
        const bool usesCulling = geometryMode & cullBothMask;
        if (usesCulling && !crossesCameraPlane) {
]=])
set(old_rect [=[
            FixedRect drawRect;
            for (int i = 0; i < 3; i++) {
]=])
set(new_rect [=[
            // The bounds and winding of these divided vertices do not describe
            // the clipped polygon. Use conservative coverage and let the GPU clip it.
            FixedRect drawRect;
            if (crossesCameraPlane) drawRect = scissorRect;
            for (int i = 0; i < 3 && !crossesCameraPlane; i++) {
]=])

foreach(section bounds rect)
    string(FIND "${rt64_rsp}" "${old_${section}}" match_start)
    if (match_start EQUAL -1)
        message(FATAL_ERROR "Pod One's RT64 triangle coverage patch needs to be updated for ${rt64_rsp_source}")
    endif()
    string(REPLACE "${old_${section}}" "${new_${section}}" rt64_rsp "${rt64_rsp}")
endforeach()

podone_patch_rt64_source("${rt64_rsp_source}" "${rt64_rsp}")
include("${CMAKE_CURRENT_LIST_DIR}/PodOneHud.cmake")
include("${CMAKE_CURRENT_LIST_DIR}/PodOneViewport.cmake")
include("${CMAKE_CURRENT_LIST_DIR}/PodOneRefresh.cmake")
