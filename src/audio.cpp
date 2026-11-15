// Audio output for Star Wars Episode I: Racer (SDL2 queue).
//
// The runtime calls these three functions with the game's mixed output: interleaved stereo int16.
// N64 audio buffers come out with left/right swapped (RDRAM word order), so every pair is swapped back here,
// as Zelda64Recomp does.

#include <SDL.h>
#include <atomic>
#include <mutex>
#if defined(__ANDROID__)
#include <jni.h>
#endif
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <vector>

static std::mutex audio_mutex;
static SDL_AudioDeviceID g_audio_dev = 0;
static uint32_t g_audio_freq = 0;
static std::atomic<uint16_t> g_requested_frames{512};
static uint16_t g_attempted_frames = 0;
static uint16_t g_opened_frames = 0;

#if defined(__ANDROID__)
extern "C" JNIEXPORT void JNICALL
Java_com_sp00nznet_swep1racer_SWE1RacerActivity_nativeSetAudioBuffer(JNIEnv*, jclass, jint frames) {
    g_requested_frames.store(frames == 1024 ? 1024 : 512);
}
#endif

static bool open_audio_device(uint32_t freq, uint16_t frames) {
    SDL_AudioSpec want{};
    SDL_AudioSpec actual{};
    want.freq = (int)freq;
    want.format = AUDIO_S16SYS;
    want.channels = 2;
    want.samples = frames;
    g_audio_dev = SDL_OpenAudioDevice(nullptr, 0, &want, &actual, 0);
    if (g_audio_dev == 0) {
        printf("[SWE1R] audio: cannot open %u Hz, %u frames: %s\n",
               freq, unsigned(frames), SDL_GetError());
        return false;
    }
    g_opened_frames = frames;
    SDL_PauseAudioDevice(g_audio_dev, 0);
    printf("[SWE1R] audio: %u Hz, requested %u frames, SDL buffer %u frames\n",
           freq, unsigned(frames), unsigned(actual.samples));
    return true;
}

static void ensure_audio_config(uint32_t freq) {
    if (freq == 0) return;
    const uint16_t frames = g_requested_frames.load();
    if (freq == g_audio_freq && frames == g_attempted_frames) return;

    if (SDL_WasInit(SDL_INIT_AUDIO) == 0) SDL_InitSubSystem(SDL_INIT_AUDIO);
    const uint16_t fallback_frames = g_opened_frames;
    if (g_audio_dev != 0) {
        SDL_CloseAudioDevice(g_audio_dev);
        g_audio_dev = 0;
    }
    g_audio_freq = freq;
    g_attempted_frames = frames;
    g_opened_frames = 0;
    if (!open_audio_device(freq, frames) && fallback_frames != 0 && fallback_frames != frames)
        open_audio_device(freq, fallback_frames);
}

void swe1r_queue_samples(int16_t* samples, size_t num_samples) {
    if (samples == nullptr || num_samples < 2) return;
    const std::scoped_lock lock{audio_mutex};
    ensure_audio_config(g_audio_freq);
    if (g_audio_dev == 0) return;
    static std::vector<int16_t> buf;
    static uint32_t calls = 0;
    buf.resize(num_samples);
    int peak = 0;
    for (size_t i = 0; i + 1 < num_samples; i += 2) {
        buf[i] = samples[i + 1];
        buf[i + 1] = samples[i];
        const int a = std::abs((int)samples[i]);
        const int b = std::abs((int)samples[i + 1]);
        if (a > peak) peak = a;
        if (b > peak) peak = b;
    }
    // Do not let latency grow without bound if the game produces faster than real time.
    if (SDL_GetQueuedAudioSize(g_audio_dev) > (g_audio_freq * 4u / 8u)) {
        SDL_ClearQueuedAudio(g_audio_dev);
    }
    SDL_QueueAudio(g_audio_dev, buf.data(), (Uint32)(num_samples * sizeof(int16_t)));
    calls++;
    if (calls <= 5 || (calls % 600) == 0) {
        printf("[SWE1R] audio: queue #%u samples=%zu peak=%d\n", calls, num_samples, peak);
    }
}

size_t swe1r_get_frames_remaining() {
    const std::scoped_lock lock{audio_mutex};
    if (g_audio_dev == 0) {
        // Same value the old stub returned, so the game never stalls waiting for audio.
        return 2048;
    }
    return SDL_GetQueuedAudioSize(g_audio_dev) / (2 * sizeof(int16_t));
}

void swe1r_set_frequency(uint32_t freq) {
    const std::scoped_lock lock{audio_mutex};
    ensure_audio_config(freq);
}
