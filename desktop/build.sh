#!/bin/sh
# Builds the Skyrim desktop test binary (build/lb) on Linux or WSL2.
# Needs: clang cmake ninja-build make libsdl2-dev libvulkan-dev
# libavcodec-dev libavutil-dev (+ mesa-vulkan-drivers to run).
# JOBS: parallel compiles (each generated file needs ~1-2 GB of RAM).
set -e
cd "$(dirname "$0")/.."
JOBS=${JOBS:-4}
FFMPEG=${FFMPEG:-/usr}
if [ -z "${FFMPEG_LIB:-}" ]; then
    if command -v pkg-config >/dev/null 2>&1; then
        FFMPEG_LIB=$(pkg-config --variable=libdir libavcodec 2>/dev/null || true)
    fi
    [ -n "${FFMPEG_LIB:-}" ] || FFMPEG_LIB=/usr/lib/x86_64-linux-gnu
fi
cmake -S desktop -B desktop/build -G Ninja -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ -DCMAKE_BUILD_TYPE=Release
ninja -C desktop/build -j"$JOBS"
make -j"$JOBS" build/lb DXVK="$PWD/android/app/jni/dxvk" DXVK_LIB="$PWD/desktop/build/dxvk" \
    FFMPEG="$FFMPEG" FFMPEG_LIB="$FFMPEG_LIB"
