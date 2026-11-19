#include <adrenotools/driver.h>
#include "plume_vulkan.h"
#include <vulkan/vulkan_android.h>
#include <SDL.h>
#include <SDL_syswm.h>
#include <SDL_vulkan.h>
#include <android/log.h>

#include <atomic>
#include <dlfcn.h>
#include <filesystem>
#include <fstream>
#include <string>

namespace {
    PFN_vkGetInstanceProcAddr instance_proc = nullptr;
    std::atomic<bool> using_custom{false};

    VkResult initialize_custom_loader() {
        volkInitializeCustom(instance_proc);
        return VK_SUCCESS;
    }

    void log_device(const VkPhysicalDeviceProperties& device) {
        __android_log_print(ANDROID_LOG_INFO, "PodOneVulkan",
                "Vulkan: %s (vendor %#x, driver %#x, custom loader %s)",
                device.deviceName, device.vendorID, device.driverVersion,
                using_custom.load() ? "on" : "off");
    }

    bool safe_file_name(const std::string& value) {
        return !value.empty() && value.size() <= 128 && value != "." && value != ".."
            && value.find_first_not_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._+-")
                == std::string::npos;
    }
}

void podone_configure_vulkan(const std::filesystem::path& directory,
                             const char* hook_dir, const char* cache_dir) {
    plume::SetVulkanPhysicalDeviceObserver(log_device);
    std::ifstream selection(directory / "selected");
    std::string package, library;
    if (!std::getline(selection, package) || !std::getline(selection, library)
            || !safe_file_name(package) || !safe_file_name(library)
            || !package.starts_with("driver-") || !library.ends_with(".so")) return;

    std::error_code error;
    const std::filesystem::path package_dir = directory / package;
    if (!std::filesystem::is_regular_file(package_dir / library, error)) return;
    const std::string driver_dir = package_dir.string() + "/";
    const std::string scratch_dir = std::string(cache_dir) + "/";

    // The handle stays live for the process lifetime, including on a system-driver fallback.
    void* loader = adrenotools_open_libvulkan(RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM,
            scratch_dir.c_str(), hook_dir, driver_dir.c_str(), library.c_str(), nullptr, nullptr);
    if (loader != nullptr)
        instance_proc = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(loader, "vkGetInstanceProcAddr"));
    if (instance_proc == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, "PodOneVulkan",
                "Could not open %s; using system Vulkan", library.c_str());
        return;
    }

    using_custom.store(true);
    plume::SetVulkanLoaderInitializeCallback(initialize_custom_loader);
    __android_log_print(ANDROID_LOG_INFO, "PodOneVulkan", "Selected driver library %s", library.c_str());
}

bool podone_vulkan_fallback() {
    if (!using_custom.exchange(false)) return false;
    plume::SetVulkanLoaderInitializeCallback(nullptr);
    __android_log_print(ANDROID_LOG_WARN, "PodOneVulkan",
            "Custom Vulkan setup failed; retrying with system Vulkan");
    return true;
}

extern "C" SDL_bool __real_SDL_Vulkan_CreateSurface(SDL_Window*, VkInstance, VkSurfaceKHR*);
extern "C" SDL_bool __wrap_SDL_Vulkan_CreateSurface(SDL_Window* window, VkInstance instance,
                                                   VkSurfaceKHR* surface) {
    if (!using_custom.load()) return __real_SDL_Vulkan_CreateSurface(window, instance, surface);

    // SDL's normal implementation asks its stock loader for the surface function.
    // With a custom instance, resolve it from the same loader that created the instance.
    SDL_SysWMinfo info{};
    SDL_VERSION(&info.version);
    if (!SDL_GetWindowWMInfo(window, &info) || info.info.android.window == nullptr) return SDL_FALSE;

    auto create_surface = reinterpret_cast<PFN_vkCreateAndroidSurfaceKHR>(
            instance_proc(instance, "vkCreateAndroidSurfaceKHR"));
    if (create_surface == nullptr) {
        SDL_SetError("The selected driver does not support Android Vulkan surfaces");
        return SDL_FALSE;
    }

    VkAndroidSurfaceCreateInfoKHR create_info{};
    create_info.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    create_info.window = info.info.android.window;
    VkResult result = create_surface(instance, &create_info, nullptr, surface);
    if (result == VK_SUCCESS) return SDL_TRUE;

    SDL_SetError("The selected Vulkan driver could not create the Android surface (%d)", int(result));
    return SDL_FALSE;
}
