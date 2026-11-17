#pragma once

#include "common/rt64_plume.h"
#include <memory>

namespace RT64 {
    struct RenderTarget;
    struct RenderWorker;
    struct Workload;
}

namespace podone_geometry {
    class GpuCapture {
    public:
        GpuCapture(RT64::RenderWorker& worker, const RT64::Workload& workload, float frame_weight, float aspect_scale);
        void before_draw(const RT64::RenderTarget& depth, unsigned pair);
        void after_draw(const RT64::RenderTarget& depth, unsigned pair);
        void capture_color(const RT64::RenderTarget& color, unsigned pair, uint32_t address);
        void finish();

    private:
        void copy_depth(const RT64::RenderTarget& depth, unsigned layer);

        RT64::RenderWorker& worker;
        uint64_t workload_id = 0;
        uint32_t vertex_count = 0;
        float frame_weight;
        float aspect_scale;
        std::unique_ptr<RenderBuffer> vertices;
        std::unique_ptr<RenderBuffer> depth_samples;
        std::unique_ptr<RenderBuffer> color_samples;
        uint32_t depth_width = 0;
        uint32_t depth_height = 0;
        float depth_scale_x = 0.0f;
        float depth_scale_y = 0.0f;
        unsigned depth_pair = 0;
        bool depth_after = false;
        uint32_t color_width = 0;
        uint32_t color_height = 0;
        uint32_t color_address = 0;
        unsigned color_pair = 0;
        unsigned color_channel_bytes = 0;
    };
}
