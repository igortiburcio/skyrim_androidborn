#!/bin/sh
# Builds the release APK from source (runtime + launcher). Same wallpaper
# handling as build_custom.sh, but with the Gradle release build type.
#
# The wallpaper is a fan image kept outside the repo: it is copied into
# assets/backdrop.jpg only for the build and removed afterwards. Set
# ANDROIDBORN_WALLPAPER to use another image; without one the launcher falls back
# to the painted Nordic night.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
WALL=${ANDROIDBORN_WALLPAPER:-$HOME/.local/share/skyrim-androidborn/wallpaper.jpg}
ASSET="$ROOT/android/app/src/main/assets/backdrop.jpg"
OUT=${1:-"$ROOT/Skyrim-Androidborn-release.apk"}

export JAVA_HOME=${JAVA_HOME:-$HOME/.local/share/mise/installs/java/temurin-17.0.21+9}
export ANDROID_HOME=${ANDROID_HOME:-$HOME/.local/share/mise/installs/android-sdk/23.0}
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
NDK=${ANDROIDBORN_NDK:-29.0.14206865}

# The release key lives outside the repository; android/keystore.properties is
# a local symlink to it. Without it Gradle produces an unsigned APK, so stop
# here instead of publishing something Android will refuse to install.
KEYPROPS="$ROOT/android/keystore.properties"
if [ ! -f "$KEYPROPS" ]; then
    echo "build_release.sh: missing $KEYPROPS (release key). See README 'Signing'." >&2
    exit 1
fi

cleanup() { rm -f "$ASSET"; }
trap cleanup EXIT INT TERM

if [ -f "$WALL" ]; then
    cp "$WALL" "$ASSET"
    echo "wallpaper: $WALL"
fi

cd "$ROOT/android"
bash ./gradlew :app:assembleRelease -PndkVersion="$NDK"
cp app/build/outputs/apk/release/app-release.apk "$OUT"

# Prove the APK carries the release certificate before it is published, and
# write the checksum the launcher's updater checks.
APKSIGNER=$(ls "$ANDROID_HOME"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)
if [ -z "$APKSIGNER" ] || [ ! -x "$APKSIGNER" ]; then
    echo "build_release.sh: apksigner not found under $ANDROID_HOME/build-tools" >&2
    exit 1
fi
"$APKSIGNER" verify --print-certs "$OUT"
(cd "$(dirname "$OUT")" && sha256sum "$(basename "$OUT")" > SHA256SUMS)
cat "$(dirname "$OUT")/SHA256SUMS"
echo "done: $OUT"
