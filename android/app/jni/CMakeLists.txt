cmake_minimum_required(VERSION 3.18)
project(skyrim C CXX)
set(CMAKE_CXX_STANDARD 17)
# Each recompiled file needs 1-2 GB of RAM to compile; cap parallel jobs.
set(LB_GEN_JOBS 3 CACHE STRING "parallel compiles of recompiled code")
set_property(GLOBAL APPEND PROPERTY JOB_POOLS gen=${LB_GEN_JOBS})

# libadrenotools (BSD-2-Clause) loads a custom Vulkan driver (Turnip / Mr.
# Purple) without root: it opens the system libvulkan loader in an isolated
# linker namespace with a dlopen hook, so the loader picks the custom ICD.
# It also builds the hook libraries the loader needs (libmain_hook.so,
# libhook_impl.so, libfile_redirect_hook.so, libgsl_alloc_hook.so); Android
# extracts them to nativeLibraryDir because of useLegacyPackaging.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
add_subdirectory(third_party/libadrenotools)

set(SDL_SHARED ON CACHE BOOL "" FORCE)
set(SDL_STATIC OFF CACHE BOOL "" FORCE)
set(SDL_TEST OFF CACHE BOOL "" FORCE)
add_subdirectory(SDL2)
add_subdirectory(dxvk)
add_subdirectory(game)
