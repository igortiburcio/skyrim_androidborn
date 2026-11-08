// BC (DXT1-5) block decoding, free of D3D headers so tests/native can compile
// it on the host. d3d9_bcemu.cpp wraps these with its D3DFORMAT values.
#pragma once
#include <stdint.h>
#include <string.h>

namespace bc {

// Values of D3DFMT_DXT1..D3DFMT_DXT5 ('DXT1'..'DXT5').
inline constexpr uint32_t kDxt1 = 0x31545844u;
inline constexpr uint32_t kDxt2 = 0x32545844u;
inline constexpr uint32_t kDxt3 = 0x33545844u;
inline constexpr uint32_t kDxt4 = 0x34545844u;
inline constexpr uint32_t kDxt5 = 0x35545844u;

inline bool is_dxt(uint32_t f) {
    return f == kDxt1 || f == kDxt2 || f == kDxt3 || f == kDxt4 || f == kDxt5;
}

inline uint32_t block_bytes(uint32_t f) { return f == kDxt1 ? 8 : 16; }

inline void rgb565(uint16_t v, uint8_t *o) {
    o[0] = (uint8_t)(((v >> 11) & 31) * 255 / 31);
    o[1] = (uint8_t)(((v >> 5) & 63) * 255 / 63);
    o[2] = (uint8_t)((v & 31) * 255 / 31);
}

// Decodes one 4x4 block into BGRA rows of `out` (pitch in bytes), clipped to w x h.
inline void decode_block(uint32_t f, const uint8_t *b, uint8_t *out, int pitch, int w, int h) {
    uint8_t alpha[16];
    for (int i = 0; i < 16; i++) alpha[i] = 255;
    const uint8_t *cb = b;
    if (f == kDxt2 || f == kDxt3) {
        for (int i = 0; i < 16; i++) alpha[i] = (uint8_t)(((b[i / 2] >> (4 * (i & 1))) & 15) * 17);
        cb = b + 8;
    } else if (f == kDxt4 || f == kDxt5) {
        uint8_t a[8];
        a[0] = b[0]; a[1] = b[1];
        if (a[0] > a[1]) for (int i = 1; i < 7; i++) a[i + 1] = (uint8_t)(((7 - i) * a[0] + i * a[1]) / 7);
        else {
            for (int i = 1; i < 5; i++) a[i + 1] = (uint8_t)(((5 - i) * a[0] + i * a[1]) / 5);
            a[6] = 0; a[7] = 255;
        }
        uint64_t bits = 0;
        for (int i = 0; i < 6; i++) bits |= (uint64_t)b[2 + i] << (8 * i);
        for (int i = 0; i < 16; i++) alpha[i] = a[(bits >> (3 * i)) & 7];
        cb = b + 8;
    }
    uint16_t c0 = (uint16_t)(cb[0] | cb[1] << 8), c1 = (uint16_t)(cb[2] | cb[3] << 8);
    uint8_t col[4][4]; // r g b a
    rgb565(c0, col[0]); rgb565(c1, col[1]);
    col[0][3] = col[1][3] = col[2][3] = col[3][3] = 255;
    if (c0 > c1 || f != kDxt1) {
        for (int k = 0; k < 3; k++) {
            col[2][k] = (uint8_t)((2 * col[0][k] + col[1][k]) / 3);
            col[3][k] = (uint8_t)((col[0][k] + 2 * col[1][k]) / 3);
        }
    } else {
        for (int k = 0; k < 3; k++) { col[2][k] = (uint8_t)((col[0][k] + col[1][k]) / 2); col[3][k] = 0; }
        col[3][3] = 0;
    }
    uint32_t idx = (uint32_t)(cb[4] | cb[5] << 8 | cb[6] << 16 | (uint32_t)cb[7] << 24);
    for (int y = 0; y < h && y < 4; y++) {
        uint8_t *row = out + y * pitch;
        for (int x = 0; x < w && x < 4; x++) {
            int i = y * 4 + x;
            const uint8_t *cc = col[(idx >> (2 * i)) & 3];
            row[4 * x + 0] = cc[2];
            row[4 * x + 1] = cc[1];
            row[4 * x + 2] = cc[0];
            row[4 * x + 3] = (uint8_t)(cc[3] * alpha[i] / 255);
        }
    }
}

} // namespace bc
