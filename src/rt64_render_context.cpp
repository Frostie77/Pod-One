// RT64 Render Context for Star Wars Episode I: Racer
// Bridges ultramodern's RendererContext with the RT64 renderer

// NOMINMAX must be set globally via CMake (target_compile_definitions)
// Include RT64 first to avoid Windows.h macro conflicts
#include "hle/rt64_application.h"

#include <SDL.h>
#include <algorithm>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#if defined(__ANDROID__)
#include <sys/system_properties.h>
void podone_set_fill_screen(bool);
bool podone_vulkan_fallback();
#endif

#include "ultramodern/renderer_context.hpp"
#include "librecomp/game.hpp"

// Static buffers for RT64 Core (RSP memory, not used in HLE mode but required)
static uint8_t s_dmem[0x1000];
static uint8_t s_imem[0x1000];

// DPC registers (dummy storage - RT64 HLE doesn't use these directly)
static uint32_t s_dpc_start = 0;
static uint32_t s_dpc_end = 0;
static uint32_t s_dpc_current = 0;
static uint32_t s_dpc_status = 0;
static uint32_t s_dpc_clock = 0;
static uint32_t s_dpc_bufbusy = 0;
static uint32_t s_dpc_pipebusy = 0;
static uint32_t s_dpc_tmem = 0;
static uint32_t s_mi_intr = 0;

static void dummy_check_interrupts() {
    // No-op: ultramodern handles interrupts
}

// Publish one complete configuration from the UI thread at a time.
struct VideoRequest {
    int aspect = -1;
    int filter = -1;
    int resolution = -1;
    int msaa = 0;
    int downsample = 0;
    int refresh = 0;
};
static std::mutex g_video_request_mutex;
static VideoRequest g_video_request;
static std::atomic<bool> g_video_dirty{false};
static std::atomic<float> g_game_fps{0.0f};
static std::atomic<float> g_dl_rate{0.0f};

#if defined(__ANDROID__)
#include <jni.h>
extern "C" JNIEXPORT void JNICALL
Java_com_sp00nznet_swep1racer_SWE1RacerActivity_nativeSetVideo(JNIEnv*, jclass, jint aspect, jint filter, jint resolution, jint msaa, jint downsample, jint refresh) {
    const std::scoped_lock lock{g_video_request_mutex};
    g_video_request = {aspect, filter, resolution, msaa, downsample, refresh};
    g_video_dirty.store(true);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_sp00nznet_swep1racer_SWE1RacerActivity_nativeGameFps(JNIEnv*, jclass) {
    return g_game_fps.load();
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_sp00nznet_swep1racer_SWE1RacerActivity_nativeDisplayListRate(JNIEnv*, jclass) {
    return g_dl_rate.load();
}
#endif

class SWE1RRenderContext : public ultramodern::renderer::RendererContext {
public:
    SWE1RRenderContext(uint8_t* rdram, ultramodern::renderer::WindowHandle window_handle, bool developer_mode)
        : rdram_(rdram), window_handle_(window_handle), developer_mode_(developer_mode)
    {
        setup_result = ultramodern::renderer::SetupResult::Success;
#if defined(__ANDROID__)
        chosen_api = ultramodern::renderer::GraphicsApi::Vulkan;
#else
        chosen_api = ultramodern::renderer::GraphicsApi::D3D12;
#endif

        // Get VI register pointers from ultramodern
        vi_regs_ = ultramodern::renderer::get_vi_regs();

        // Set up the RT64 Core structure
        RT64::Application::Core core{};
#if defined(_WIN32)
        core.window = window_handle.window;
#else
        core.window = window_handle;
#endif
        core.RDRAM = rdram;
        core.DMEM = s_dmem;
        core.IMEM = s_imem;

        // ROM header (first 0x40 bytes)
        auto rom = recomp::get_rom();
        if (rom.size() >= 0x40) {
            rom_header_.assign(rom.begin(), rom.begin() + 0x40);
            core.HEADER = rom_header_.data();
        } else {
            rom_header_.resize(0x40, 0);
            core.HEADER = rom_header_.data();
        }

        // MI/DPC registers (dummy)
        core.MI_INTR_REG = &s_mi_intr;
        core.DPC_START_REG = &s_dpc_start;
        core.DPC_END_REG = &s_dpc_end;
        core.DPC_CURRENT_REG = &s_dpc_current;
        core.DPC_STATUS_REG = &s_dpc_status;
        core.DPC_CLOCK_REG = &s_dpc_clock;
        core.DPC_BUFBUSY_REG = &s_dpc_bufbusy;
        core.DPC_PIPEBUSY_REG = &s_dpc_pipebusy;
        core.DPC_TMEM_REG = &s_dpc_tmem;

        // VI registers - point into ultramodern's ViRegs struct
        core.VI_STATUS_REG = &vi_regs_->VI_STATUS_REG;
        core.VI_ORIGIN_REG = &vi_regs_->VI_ORIGIN_REG;
        core.VI_WIDTH_REG = &vi_regs_->VI_WIDTH_REG;
        core.VI_INTR_REG = &vi_regs_->VI_INTR_REG;
        core.VI_V_CURRENT_LINE_REG = &vi_regs_->VI_V_CURRENT_LINE_REG;
        core.VI_TIMING_REG = &vi_regs_->VI_TIMING_REG;
        core.VI_V_SYNC_REG = &vi_regs_->VI_V_SYNC_REG;
        core.VI_H_SYNC_REG = &vi_regs_->VI_H_SYNC_REG;
        core.VI_LEAP_REG = &vi_regs_->VI_LEAP_REG;
        core.VI_H_START_REG = &vi_regs_->VI_H_START_REG;
        core.VI_V_START_REG = &vi_regs_->VI_V_START_REG;
        core.VI_V_BURST_REG = &vi_regs_->VI_V_BURST_REG;
        core.VI_X_SCALE_REG = &vi_regs_->VI_X_SCALE_REG;
        core.VI_Y_SCALE_REG = &vi_regs_->VI_Y_SCALE_REG;

        core.checkInterrupts = dummy_check_interrupts;

        // Configure RT64 application
        RT64::ApplicationConfiguration app_config;
        app_config.appId = "swe1racer";
        app_config.detectDataPath = true;
        app_config.useConfigurationFile = true;

        // Create the RT64 Application
        printf("[SWE1R] Creating RT64 application...\n");
        app_ = std::make_unique<RT64::Application>(core, app_config);

        // Run setup (creates graphics device, swap chain, shader caches, etc.)
#if defined(_WIN32)
        auto result = app_->setup(window_handle.thread_id);
#else
        auto result = app_->setup(SDL_ThreadID());
#if defined(__ANDROID__)
        if (result != RT64::Application::SetupResult::Success && podone_vulkan_fallback()) {
            app_.reset();
            app_ = std::make_unique<RT64::Application>(core, app_config);
            result = app_->setup(SDL_ThreadID());
        }
#endif
#endif

        switch (result) {
        case RT64::Application::SetupResult::Success:
            printf("[SWE1R] RT64 initialized successfully\n");
#if defined(__ANDROID__)
            // Android 2026-10-20 (Claude): ubershader-only mode was a workaround for Mali shader-compiler crashes.
            // 2026-10-21: those crashes were our own heap overflow (16K EEPROM, MTE found it), so specialized shaders
            // are back by default (textures were garbled with the ubershader). `adb shell setprop debug.swe1r.uber 1`
            // forces the ubershader again for A/B tests (needs the RACER PATCH in rt64_state.cpp).
            {
                char prop[PROP_VALUE_MAX] = {};
                __system_property_get("debug.swe1r.uber", prop);
                const bool uber = (prop[0] == '1');
                app_->workloadQueue->ubershadersOnly = uber;
                printf("[SWE1R] RT64 %s\n", uber ? "ubershaders only (debug.swe1r.uber=1)" : "specialized shaders on");
            }
#endif
            is_valid_ = true;
            break;
        case RT64::Application::SetupResult::DynamicLibrariesNotFound:
            fprintf(stderr, "[SWE1R] RT64: Dynamic libraries not found\n");
            setup_result = ultramodern::renderer::SetupResult::DynamicLibrariesNotFound;
            break;
        case RT64::Application::SetupResult::InvalidGraphicsAPI:
            fprintf(stderr, "[SWE1R] RT64: Invalid graphics API\n");
            setup_result = ultramodern::renderer::SetupResult::InvalidGraphicsAPI;
            break;
        case RT64::Application::SetupResult::GraphicsAPINotFound:
            fprintf(stderr, "[SWE1R] RT64: Graphics API not found\n");
            setup_result = ultramodern::renderer::SetupResult::GraphicsAPINotFound;
            break;
        case RT64::Application::SetupResult::GraphicsDeviceNotFound:
            fprintf(stderr, "[SWE1R] RT64: Graphics device not found\n");
            setup_result = ultramodern::renderer::SetupResult::GraphicsDeviceNotFound;
            break;
        }
    }

    ~SWE1RRenderContext() override {
        if (app_) {
            printf("[SWE1R] Destroying RT64 application\n");
            app_.reset();
        }
    }

    bool valid() override { return is_valid_; }

    bool update_config(const ultramodern::renderer::GraphicsConfig& old_config,
                       const ultramodern::renderer::GraphicsConfig& new_config) override {
        // TODO: forward config changes to RT64
        return true;
    }

    void enable_instant_present() override {
        // RT64 doesn't have a separate instant present mode
    }

    void send_dl(const OSTask* task) override {
        if (!app_ || !is_valid_) return;
        apply_pending_video_settings();

        dl_count_++;
        if (dl_count_ <= 5 || (dl_count_ % 60 == 0)) {
            printf("[SWE1R] send_dl #%u: data_ptr=0x%08X ucode=0x%08X ucode_data=0x%08X type=%u\n",
                   dl_count_, (uint32_t)task->t.data_ptr, (uint32_t)task->t.ucode,
                   (uint32_t)task->t.ucode_data, task->t.type);
        }

        // Tell RT64's HLE interpreter which microcode/GBI to use BEFORE parsing
        // the display list. Without this the interpreter has no GBI command map
        // and crashes in processDisplayLists. ucode/ucode_data/data_ptr are MIPS
        // virtual addresses; mask to the RDRAM physical offset RT64 indexes by.
        uint32_t ucode      = (uint32_t)task->t.ucode      & 0x3FFFFFF;
        uint32_t ucode_data = (uint32_t)task->t.ucode_data & 0x3FFFFFF;
        uint32_t dl_address = (uint32_t)task->t.data_ptr   & 0x3FFFFFF;
        if (dl_count_ <= 3) {
            dump_dl(dl_address);
        }
        // Android 2026-10-20 (Claude), from Goemon64Recomp-Android (ogdanimal) / Zelda64Recomp send_dl: reset the HLE
        // RSP state (segment table, vertex buffer, matrix stack) before every task. Without it state from the previous
        // display list leaks in and RT64 reads garbage commands ("unknown opCode 0x3F", setVertex assert).
        app_->state->rsp->reset();
        app_->interpreter->loadUCodeGBI(ucode, ucode_data, true);
        app_->processDisplayLists(rdram_, dl_address, 0, true);
    }

    // [dl] log lines: the first commands of a top-level display list (w0 w1 per line, F3DEX2 opcode names for the
    // common ones). Follows nothing - a 0xDE (G_DL) line shows the address it would branch to. For racer.cmd grep "[dl]".
    void dump_dl(uint32_t addr) {
        static const struct { uint8_t op; const char* name; } names[] = {
            {0x01, "VTX"}, {0x05, "TRI1"}, {0x06, "TRI2"}, {0x07, "QUAD"}, {0xD7, "TEXTURE"}, {0xD9, "GEOMETRYMODE"},
            {0xDA, "MTX"}, {0xDB, "MOVEWORD"}, {0xDC, "MOVEMEM"}, {0xDE, "DL"}, {0xDF, "ENDDL"}, {0xE2, "SETOTHERMODE_L"},
            {0xE3, "SETOTHERMODE_H"}, {0xE4, "TEXRECT"}, {0xE6, "LOADSYNC"}, {0xE7, "PIPESYNC"}, {0xE8, "TILESYNC"},
            {0xE9, "FULLSYNC"}, {0xED, "SETSCISSOR"}, {0xF5, "SETTILE"}, {0xF6, "FILLRECT"}, {0xF7, "SETFILLCOLOR"},
            {0xFA, "SETPRIMCOLOR"}, {0xFC, "SETCOMBINE"}, {0xFD, "SETTIMG"}, {0xFE, "SETZIMG"}, {0xFF, "SETCIMG"},
        };
        printf("[dl] list #%u at 0x%08X\n", dl_count_, addr);
        for (uint32_t i = 0; i < 96; i++) {
            uint32_t a = (addr + i * 8) & 0x3FFFFFF;
            uint32_t w0 = *(uint32_t*)(rdram_ + a);
            uint32_t w1 = *(uint32_t*)(rdram_ + a + 4);
            uint8_t op = (uint8_t)(w0 >> 24);
            const char* name = "?";
            for (const auto& n : names) {
                if (n.op == op) { name = n.name; break; }
            }
            printf("[dl] %3u %08X: %08X %08X %s\n", i, 0x80000000u | a, w0, w1, name);
            if (op == 0xDF) break;
        }
    }

    void send_dummy_workload(uint32_t fb_address) override {
        // The current game path submits display lists for rendered frames.
        // Keep the runtime callback implemented until framebuffer-only work is needed.
        (void)fb_address;
    }

    void update_screen() override {
        if (!app_ || !is_valid_) return;

        screen_count_++;
        if (screen_count_ <= 5 || (screen_count_ % 60 == 0)) {
            // VI registers RT64 presents from: ORIGIN 0 / WIDTH 0 / X_SCALE 0 = the game's framebuffer never
            // reached the VI (black screen even while display lists flow).
            printf("[SWE1R] update_screen #%u vi: status=0x%X origin=0x%X width=%u hstart=0x%X vstart=0x%X xscale=0x%X yscale=0x%X\n",
                   screen_count_, vi_regs_->VI_STATUS_REG, vi_regs_->VI_ORIGIN_REG, vi_regs_->VI_WIDTH_REG,
                   vi_regs_->VI_H_START_REG, vi_regs_->VI_V_START_REG, vi_regs_->VI_X_SCALE_REG, vi_regs_->VI_Y_SCALE_REG);
        }

        apply_pending_video_settings();

        // Game frame rate: a finished frame moves the VI origin to another framebuffer.
        {
            const uint32_t origin = vi_regs_->VI_ORIGIN_REG;
            if (origin != last_origin_) {
                last_origin_ = origin;
                fps_frames_++;
            }
            const uint64_t now = SDL_GetTicks64();
            if (now - fps_window_start_ >= 1000) {
                const float seconds = float(now - fps_window_start_) / 1000.0f;
                g_game_fps.store(fps_frames_ / seconds);
                g_dl_rate.store((dl_count_ - fps_dl_start_) / seconds);
                printf("[fps] game %.1f  lists %.1f\n", fps_frames_ / seconds, (dl_count_ - fps_dl_start_) / seconds);
                fps_frames_ = 0;
                fps_dl_start_ = dl_count_;
                fps_window_start_ = now;
            }
        }

        app_->updateScreen();
    }

    // The graphics thread is the producer for both queues. Drain the work
    // already submitted before it publishes any frames at the new size.
    void reset_render_targets() {
        auto &workloads = *app_->workloadQueue;
        auto &presents = *app_->presentQueue;
        workloads.waitForWorkloadId(app_->state->workloadId);
        presents.waitForPresentId(app_->state->presentId);
        // The IDs are signalled after the first frame. The thread locks also
        // cover any remaining interpolated frames using the old targets.
        const std::scoped_lock queue_threads(workloads.threadMutex, presents.threadMutex);

        auto &resources = *app_->sharedQueueResources;
        const std::scoped_lock targets(resources.managerMutex, resources.interpolatedMutex);
        workloads.renderFramebufferManager->destroyAll();
        resources.renderTargetManager.destroyAll();
        resources.framebufferManager.destroyAllTileCopies();
        resources.interpolatedColorTargets.clear();
        // A VI update can present the last workload before another one is submitted.
        // Keep one base frame so PresentQueue advances its present ID even here.
        for (auto &counters : resources.interpolatedFrames) counters = {.count = 1};
    }

    void apply_pending_video_settings() {
        if (!g_video_dirty.exchange(false)) return;
        VideoRequest request;
        {
            const std::scoped_lock lock{g_video_request_mutex};
            request = g_video_request;
        }
        apply_video_settings(request);
    }

    void apply_video_settings(const VideoRequest &request) {
        const auto &previous = app_->userConfig;
        auto cfg = previous;
        int aspect = request.aspect;
        const int filter = request.filter, resolution = request.resolution;
        if (aspect == 0) {
            cfg.aspectRatio = RT64::UserConfiguration::AspectRatio::Original;
        } else if (aspect == 5) {
            // Fill the device's available viewport using native expanded 3D,
            // instead of stretching a fixed aspect-ratio framebuffer.
            cfg.aspectRatio = RT64::UserConfiguration::AspectRatio::Expand;
        } else if (aspect >= 1) {
            static const double targets[] = { 0.0, 16.0 / 9.0, 16.0 / 10.0, 3.0 / 2.0, 21.0 / 9.0 };
            aspect = (aspect > 4) ? 4 : aspect;
            cfg.aspectRatio = RT64::UserConfiguration::AspectRatio::Manual;
            cfg.aspectTarget = targets[aspect];
        }
        if (filter == 0) {
            cfg.filtering = RT64::UserConfiguration::Filtering::Nearest;
        } else if (filter == 1) {
            cfg.filtering = RT64::UserConfiguration::Filtering::AntiAliasedPixelScaling;
        }
        if (resolution == 0) {
            cfg.resolution = RT64::UserConfiguration::Resolution::Original;
        } else if (resolution == 1 || resolution == 2) {
            cfg.resolution = RT64::UserConfiguration::Resolution::Manual;
            cfg.resolutionMultiplier = (resolution == 1) ? 2.0 : 3.0;
        } else if (resolution == 3) {
            cfg.resolution = RT64::UserConfiguration::Resolution::WindowIntegerScale;
        }
        const int msaa = std::clamp(request.msaa, 0, 2);
        cfg.antialiasing = msaa == 0 ? RT64::UserConfiguration::Antialiasing::None
            : msaa == 1 ? RT64::UserConfiguration::Antialiasing::MSAA2X
                        : RT64::UserConfiguration::Antialiasing::MSAA4X;
        cfg.downsampleMultiplier = std::clamp(request.downsample + 1, 1, 3);
        const int refresh = std::clamp(request.refresh, 0, 2);
        cfg.refreshRate = refresh == 0 ? RT64::UserConfiguration::RefreshRate::Original
            : refresh == 1 ? RT64::UserConfiguration::RefreshRate::Display
                           : RT64::UserConfiguration::RefreshRate::Manual;
        cfg.refreshRateTarget = 60;
        const bool rebuild_targets = cfg.aspectRatio != previous.aspectRatio
                || cfg.aspectTarget != previous.aspectTarget
                || cfg.resolution != previous.resolution
                || cfg.resolutionMultiplier != previous.resolutionMultiplier
                || cfg.downsampleMultiplier != previous.downsampleMultiplier
                || cfg.antialiasing != previous.antialiasing;
        if (rebuild_targets) reset_render_targets();

#if defined(__ANDROID__)
        podone_set_fill_screen(aspect == 5);
#endif
        app_->userConfig = cfg;
        app_->updateUserConfig(false);
        printf("[SWE1R] video: aspect %d filter %d scale %d msaa %d downsample %d refresh %d\n",
               aspect, filter, resolution, msaa, cfg.downsampleMultiplier, refresh);
    }

    void shutdown() override {
        printf("[SWE1R] Render context shutdown\n");
        if (app_) {
            app_.reset();
        }
        is_valid_ = false;
    }

    uint32_t get_display_framerate() const override {
        return 60; // TODO: query from RT64/display
    }

    float get_resolution_scale() const override {
        return 1.0f;
    }

private:
    uint8_t* rdram_;
    ultramodern::renderer::WindowHandle window_handle_;
    bool developer_mode_;
    bool is_valid_ = false;
    ultramodern::renderer::ViRegs* vi_regs_ = nullptr;
    std::unique_ptr<RT64::Application> app_;
    std::vector<uint8_t> rom_header_;
    uint32_t dl_count_ = 0;
    uint32_t screen_count_ = 0;
    uint32_t last_origin_ = 0;        // FPS counter (update_screen)
    uint32_t fps_frames_ = 0;
    uint32_t fps_dl_start_ = 0;       // dl_count_ at the start of the window
    uint64_t fps_window_start_ = 0;
};

// Factory function
std::unique_ptr<ultramodern::renderer::RendererContext> create_swe1r_render_context(
    uint8_t* rdram, ultramodern::renderer::WindowHandle window_handle, bool developer_mode) {
    return std::make_unique<SWE1RRenderContext>(rdram, window_handle, developer_mode);
}
