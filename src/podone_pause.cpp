// Android frontend pause. The VI scheduler stops dispatching guest frame work;
// the guest's libultra clock also excludes the time spent in the frontend.
#include <cstdint>
#include <mutex>

#include "ultramodern/ultramodern.hpp"

extern "C" uint32_t __real_osGetCount();
extern "C" uint64_t __real_osGetTime();
extern "C" void __real_osSetTime(uint64_t time);

namespace {
    std::mutex guest_clock;
    bool game_paused = false;
    uint32_t paused_count = 0;
    uint32_t excluded_count = 0;
    uint64_t paused_time = 0;
    uint64_t excluded_time = 0;
}

void podone_pause_guest(bool paused) {
    const std::scoped_lock lock{guest_clock};
    if (paused == game_paused) return;

    if (paused) {
        ultramodern::set_vi_scheduler_paused(true);
        paused_count = __real_osGetCount();
        paused_time = __real_osGetTime();
    } else {
        excluded_count += __real_osGetCount() - paused_count;
        excluded_time += __real_osGetTime() - paused_time;
        ultramodern::set_vi_scheduler_paused(false);
    }
    game_paused = paused;
}

extern "C" uint32_t __wrap_osGetCount() {
    const std::scoped_lock lock{guest_clock};
    return (game_paused ? paused_count : __real_osGetCount()) - excluded_count;
}

extern "C" uint64_t __wrap_osGetTime() {
    const std::scoped_lock lock{guest_clock};
    return (game_paused ? paused_time : __real_osGetTime()) - excluded_time;
}

extern "C" void __wrap_osSetTime(uint64_t time) {
    const std::scoped_lock lock{guest_clock};
    __real_osSetTime(time + excluded_time);
    if (game_paused) paused_time = __real_osGetTime();
}
