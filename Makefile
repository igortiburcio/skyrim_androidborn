CC = clang
CXX = clang++
OPT ?= -O1
DXVK ?= android/app/jni/dxvk
DXVK_LIB ?= desktop/build/dxvk
# Desktop FFmpeg 6 (libavcodec 60) headers and x86_64 libraries. The tree
# carries the Android arm64 build; on a Linux host pass FFMPEG=/usr (system
# headers) plus FFMPEG_LIB=/usr/lib/x86_64-linux-gnu, or point both at a
# matching conda/system prefix.
FFMPEG ?= android/app/jni/third_party/ffmpeg
FFMPEG_LIB ?= $(FFMPEG)/lib
GAME ?= -DGAME_SKYRIM
CFLAGS = $(GAME) -std=gnu11 $(OPT) -g0 -fno-strict-aliasing -fwrapv -Iruntime -Igen -w
RTFLAGS = $(GAME) -std=gnu11 -O2 -g -fno-strict-aliasing -fwrapv -Iruntime -Igen -Wall -Wno-unused-function -I$(FFMPEG)/include $(shell sdl2-config --cflags)
CXXFLAGS = $(GAME) -std=c++17 -O2 -g -fno-strict-aliasing -Iruntime -Igen -I$(DXVK)/include/native/directx \
	-I$(DXVK)/include/native/windows -DDXVK_NATIVE -Wno-everything $(shell sdl2-config --cflags)
GEN_SRC = $(wildcard gen/*.c)
GEN_OBJ = $(patsubst gen/%.c,build/gen/%.o,$(GEN_SRC))
RT_SRC = $(wildcard runtime/*.c)
RT_OBJ = $(patsubst runtime/%.c,build/rt/%.o,$(RT_SRC))
CXX_SRC = $(wildcard runtime/*.cpp)
CXX_OBJ = $(patsubst runtime/%.cpp,build/rt/%.o,$(CXX_SRC))

build/lb: $(GEN_OBJ) $(RT_OBJ) $(CXX_OBJ)
	$(CXX) -rdynamic -o $@ $^ -lm -lpthread -Wl,--allow-shlib-undefined \
		$(shell sdl2-config --libs) -L/usr/lib/x86_64-linux-gnu \
		-L$(FFMPEG_LIB) -lavcodec -lavutil -Wl,-rpath,$(abspath $(FFMPEG_LIB)) \
		-L$(DXVK_LIB) -ldxvk_d3d9 -Wl,-rpath,$(DXVK_LIB)

build/gen/%.o: gen/%.c
	@mkdir -p build/gen
	$(CC) $(CFLAGS) -c $< -o $@

build/rt/%.o: runtime/%.c runtime/*.h
	@mkdir -p build/rt
	$(CC) $(RTFLAGS) -c $< -o $@

build/rt/%.o: runtime/%.cpp runtime/*.h
	@mkdir -p build/rt
	$(CXX) $(CXXFLAGS) -c $< -o $@

gen: $(GEN_OBJ)
.PHONY: gen
