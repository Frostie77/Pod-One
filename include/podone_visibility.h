#pragma once

#ifdef __ANDROID__
// Extend each axis of the visibility frustum by 6.25% for camera motion
// between the guest's visibility test and presentation.
#define PODONE_CULL_AXIS_SCALE (16.0f / 17.0f)
#define PODONE_CAMERA_RANGE_SCALE 2.0f

#ifdef __cplusplus
extern "C" {
#endif
void podone_set_visibility_aspect(float target_over_source);
float podone_cull_x_scale(void);
#ifdef __cplusplus
}
#endif
#else
#define PODONE_CULL_AXIS_SCALE 1.0f
#define PODONE_CAMERA_RANGE_SCALE 1.0f
static inline float podone_cull_x_scale(void) { return 1.0f; }
#endif
