// Face tint (FaceGen) texture recognition and inspection, free of D3D headers
// so tests/native can compile it on the host.
//
// Skyrim builds the player's face tint at runtime: a 512x1024 (or 1024x2048
// with the high-resolution pack) DXT texture in a system/managed pool. The
// engine re-creates it when an actor's race changes (vampirism), and that is
// the moment the vanilla "dark face" shows up. tex_watch.cpp uses these
// helpers to log, dump and (opt-in) repair such a texture; nothing here talks
// to the game.
#pragma once
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include "bc_decode.h"

namespace texwatch {

inline constexpr uint32_t kPoolDefault = 0;
inline constexpr uint32_t kPoolManaged = 1;
inline constexpr uint32_t kPoolSysmem = 2;

// Face tints are 1:2 maps. The game builds 512x1024, the high-resolution pack
// 1024x2048 and a low texture-quality setting 256x512 (seen on the Retroid
// Pocket G2), so the width is not fixed; the full mip chain is what tells a
// face map from an ordinary texture.
inline bool is_face_tint(uint32_t w, uint32_t h, uint32_t fmt, uint32_t pool, uint32_t levels) {
    if (pool == kPoolDefault) return false;
    if (!bc::is_dxt(fmt)) return false;
    if (levels < 6) return false;
    return w >= 128 && h == w * 2;
}

// Bytes in level 0 of a compressed texture (rows padded to whole blocks).
inline size_t level_size(uint32_t w, uint32_t h, uint32_t fmt) {
    return (size_t)((w + 3) / 4) * ((h + 3) / 4) * bc::block_bytes(fmt);
}

// True when nothing was ever written: an all-zero block also decodes to black,
// and the engine's failed face-tint generation leaves the map zeroed.
inline bool is_blank(const uint8_t *p, size_t n) {
    for (size_t i = 0; i < n; i++)
        if (p[i]) return false;
    return true;
}

// FNV-1a, to compare the contents of two writes in the log.
inline uint64_t checksum(const uint8_t *p, size_t n) {
    uint64_t h = 14695981039346656037ull;
    for (size_t i = 0; i < n; i++) {
        h ^= p[i];
        h *= 1099511628211ull;
    }
    return h;
}

// The repair is on unless the player turned it off. The Support button's
// facefix.txt wins over an older nofacefix.txt, so tapping the button always
// turns it on.
inline bool repair_enabled(bool facefix, bool nofacefix) {
    return facefix || !nofacefix;
}

// A one-mip DDS header for a block-compressed level, so the dump opens in any
// DDS viewer.
inline void dds_header(uint8_t out[128], uint32_t w, uint32_t h, uint32_t fourcc, size_t linear_size) {
    memset(out, 0, 128);
    memcpy(out, "DDS ", 4);
    auto put = [out](size_t off, uint32_t v) { memcpy(out + off, &v, 4); };
    put(4, 124);                            // dwSize
    put(8, 0x00081007u);                    // CAPS | HEIGHT | WIDTH | PIXELFORMAT | LINEARSIZE
    put(12, h);                             // dwHeight
    put(16, w);                             // dwWidth
    put(20, (uint32_t)linear_size);         // dwPitchOrLinearSize
    put(24, 0);                             // dwDepth
    put(28, 1);                             // dwMipMapCount
    put(76, 32);                            // ddspf.dwSize
    put(80, 0x4u);                          // DDPF_FOURCC
    put(84, fourcc);                        // ddspf.dwFourCC
    put(108, 0x1000u);                      // DDSCAPS_TEXTURE
}

} // namespace texwatch
