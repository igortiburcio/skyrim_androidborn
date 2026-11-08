#!/bin/sh
# Builds and runs the native C/C++ unit tests with zig (mise install zig).
# Everything is host-side: no device, no game files.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUT="$ROOT/build/tests"
mkdir -p "$OUT"

ZIG_CC=
ZIG_CXX=
if command -v zig >/dev/null 2>&1; then
    ZIG_CC="zig cc"
    ZIG_CXX="zig c++"
elif command -v mise >/dev/null 2>&1; then
    ZIG_CC="mise exec -- zig cc"
    ZIG_CXX="mise exec -- zig c++"
else
    echo "tests/run-native.sh: zig not found; run 'mise install zig'." >&2
    exit 1
fi

CFLAGS="-std=gnu11 -O1 -g -Wall -Wno-unused-function -DGAME_SKYRIM -I$ROOT/runtime -I$ROOT/tests/native"
CXXFLAGS="-std=c++17 -O1 -g -Wall -Wno-unused-function -I$ROOT/runtime -I$ROOT/tests/native"

build_c() {
    name=$1
    shift
    # The compiler command has a space in the mise case; word splitting is intended.
    $ZIG_CC $CFLAGS "$@" -o "$OUT/$name" -lm
    "$OUT/$name"
}

build_cxx() {
    name=$1
    shift
    $ZIG_CXX $CXXFLAGS "$@" -o "$OUT/$name" -lm
    "$OUT/$name"
}

build_c test_ini "$ROOT/runtime/ini.c" "$ROOT/tests/native/test_ini.c"
build_c test_fpu "$ROOT/runtime/fpu.c" "$ROOT/tests/native/test_fpu.c"
build_cxx test_touch "$ROOT/tests/native/test_touch.cpp"
build_cxx test_bc "$ROOT/tests/native/test_bc.cpp"
build_cxx test_hud "$ROOT/tests/native/test_hud.cpp"
