#!/bin/sh
# Builds the custom app from source (runtime + our launcher). This does not
# touch any published APK: no re-signing.
#
# The wallpaper is a fan image kept outside the repo: it is copied into
# assets/backdrop.jpg only for the build and removed afterwards. Set
# ANDROIDBORN_WALLPAPER to use another image; without one the launcher falls back
# to the painted Nordic night.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
WALL=${ANDROIDBORN_WALLPAPER:-$HOME/.local/share/skyrim-androidborn/wallpaper.jpg}
ASSET="$ROOT/android/app/src/main/assets/backdrop.jpg"
OUT=${1:-"$ROOT/Skyrim-Androidborn.apk"}

export JAVA_HOME=${JAVA_HOME:-$HOME/.local/share/mise/installs/java/temurin-17.0.21+9}
export ANDROID_HOME=${ANDROID_HOME:-$HOME/.local/share/mise/installs/android-sdk/23.0}
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
NDK=${ANDROIDBORN_NDK:-29.0.14206865}

cleanup() { rm -f "$ASSET"; }
trap cleanup EXIT INT TERM

if [ -f "$WALL" ]; then
    cp "$WALL" "$ASSET"
    echo "wallpaper: $WALL"
fi

cd "$ROOT/android"
bash ./gradlew :app:assembleDebug -PndkVersion="$NDK"
cp app/build/outputs/apk/debug/app-debug.apk "$OUT"
echo "done: $OUT"
