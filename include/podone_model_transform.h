#pragma once

#include "hle/rt64_rigid_body.h"
#include "hle/rt64_transform_group.h"

namespace podone_model {
    inline bool use_raw_w(const RT64::TransformGroup &group) {
        return group.matrixId == G_EX_ID_AUTO && group.decompose
            && group.perspectiveInterpolation == G_EX_COMPONENT_AUTO;
    }

    inline void update_pose(RT64::RigidBody &body, const hlslpp::float4x4 &matrix, const RT64::TransformGroup &group) {
        if (use_raw_w(group) && !RT64::isMatrixAffine(matrix)) {
            // Racer can supply a combined MVP. Its recovered model matrix carries
            // a homogeneous column which depends on the projection and rounding.
            auto pose = matrix;
            pose[0].w = pose[1].w = pose[2].w = 0.0f;
            pose[3].w = 1.0f;
            body.updateDecomposition(pose, true);
        }
        else {
            body.updateDecomposition(matrix, group.decompose);
        }
    }

    inline hlslpp::float4x4 sample(const RT64::RigidBody &body, const hlslpp::float4x4 &previous,
            const hlslpp::float4x4 &current, const RT64::TransformGroup &group, float weight) {
        if (weight == 1.0f || hlslpp::all(previous == current)) {
            return current;
        }

        auto result = body.lerp(weight, previous, current, true);
        if (use_raw_w(group) && (!RT64::isMatrixAffine(previous) || !RT64::isMatrixAffine(current))) {
            // Keep each vertex's homogeneous W on the line between the two
            // original samples, independent of interpolated rotation/translation.
            for (unsigned row = 0; row < 4; ++row) {
                result[row].w = hlslpp::lerp(previous[row].w, current[row].w, weight);
            }
        }
        return result;
    }
}
