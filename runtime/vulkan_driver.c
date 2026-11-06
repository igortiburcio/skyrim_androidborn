/* Custom Vulkan driver (Turnip / Mr. Purple) through libadrenotools.
 *
 * SkyrimActivity receives driver_dir and driver_lib as Intent extras and turns
 * them into LB_DRIVER_DIR / LB_DRIVER_LIB, plus LB_HOOK_DIR (the APK's
 * nativeLibraryDir, where Gradle extracted libmain_hook.so and friends) and
 * LB_DRIVER_TMP (a writable cache dir). libadrenotools opens the system
 * libvulkan loader in a namespace where its android_dlopen_ext /
 * android_load_sphal_library hooks redirect the vendor driver to the custom
 * one, and also installs the GSL allocation hook. The returned handle is a
 * normal libvulkan handle, so dxvk keeps working; we only hand it our
 * vkGetInstanceProcAddr function so every instance and device is created
 * through the custom driver. */
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <adrenotools/driver.h>

#include "rt.h"

/* Defined in libdxvk_d3d9.so (vulkan_loader.cpp). */
extern void skyrim_set_vulkan_loader(void *fn);

void skyrim_port_init_vulkan_driver(void) {
    const char *dir = getenv("LB_DRIVER_DIR");
    const char *lib = getenv("LB_DRIVER_LIB");
    const char *hook = getenv("LB_HOOK_DIR");
    const char *tmp = getenv("LB_DRIVER_TMP");

    if (!dir || !*dir || !lib || !*lib) return;
    if (!hook || !*hook) {
        LOG("Port: custom driver skipped: LB_HOOK_DIR is not set");
        return;
    }

    /* adrenotools concatenates customDriverDir + customDriverName, so the
     * directory must end in a slash. */
    char dirBuf[1024];
    snprintf(dirBuf, sizeof dirBuf, "%s%s", dir, dir[strlen(dir) - 1] == '/' ? "" : "/");

    void *handle = adrenotools_open_libvulkan(RTLD_NOW,
            ADRENOTOOLS_DRIVER_CUSTOM,
            tmp, hook, dirBuf, lib, NULL, NULL);
    if (!handle) {
        LOG("Port: custom driver failed to load (%s%s)", dirBuf, lib);
        return;
    }

    void *fn = dlsym(handle, "vkGetInstanceProcAddr");
    if (!fn) {
        LOG("Port: custom driver has no vkGetInstanceProcAddr (%s%s)", dirBuf, lib);
        return;
    }

    skyrim_set_vulkan_loader(fn);
    LOG("Port: GPU driver %s%s", dirBuf, lib);
    LOG("Port: custom Vulkan driver %s%s", dirBuf, lib);
}
