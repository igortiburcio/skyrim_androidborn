#!/bin/sh
# Runs the automated tests. The launcher and Steam client suites run on the
# JVM (no device); the native and Python suites are picked up when present.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

export JAVA_HOME=${JAVA_HOME:-$HOME/.local/share/mise/installs/java/temurin-17.0.21+9}
export ANDROID_HOME=${ANDROID_HOME:-$HOME/.local/share/mise/installs/android-sdk/23.0}
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
NDK=${ANDROIDBORN_NDK:-29.0.14206865}

if [ ! -x "$JAVA_HOME/bin/java" ]; then
    echo "tools/test.sh: JDK 17 not found in $JAVA_HOME (set JAVA_HOME)." >&2
    exit 1
fi

cd "$ROOT/android"
bash ./gradlew --console=plain :steam:test :app:testDebugUnitTest \
    -PndkVersion="$NDK" "$@"

if [ -x "$ROOT/tests/run-native.sh" ]; then
    "$ROOT/tests/run-native.sh"
fi

if [ -x "$ROOT/tests/run-python.sh" ]; then
    "$ROOT/tests/run-python.sh"
fi
