/* BC (DXT1/3/5) block decoding: colours, alpha and edge clipping. */
#include "bc_decode.h"
#include "harness.h"

static void color_block(uint8_t *b, uint16_t c0, uint16_t c1, uint32_t idx) {
    b[0] = (uint8_t)(c0 & 0xff);
    b[1] = (uint8_t)(c0 >> 8);
    b[2] = (uint8_t)(c1 & 0xff);
    b[3] = (uint8_t)(c1 >> 8);
    b[4] = (uint8_t)(idx & 0xff);
    b[5] = (uint8_t)((idx >> 8) & 0xff);
    b[6] = (uint8_t)((idx >> 16) & 0xff);
    b[7] = (uint8_t)((idx >> 24) & 0xff);
}

static uint8_t pixel(const uint8_t *out, int pitch, int x, int y, int channel) {
    return out[y * pitch + 4 * x + channel];
}

TEST(rgb565_endpoints) {
    uint8_t c[3];
    bc::rgb565(0xFFFF, c);
    CHECK_INT(c[0], 255);
    CHECK_INT(c[1], 255);
    CHECK_INT(c[2], 255);

    bc::rgb565(0xF800, c);  /* red */
    CHECK_INT(c[0], 255);
    CHECK_INT(c[1], 0);
    CHECK_INT(c[2], 0);

    bc::rgb565(0x07E0, c);  /* green */
    CHECK_INT(c[0], 0);
    CHECK_INT(c[1], 255);
    CHECK_INT(c[2], 0);

    bc::rgb565(0x001F, c);  /* blue */
    CHECK_INT(c[0], 0);
    CHECK_INT(c[1], 0);
    CHECK_INT(c[2], 255);
}

TEST(dxt1_solid_white) {
    uint8_t block[8] = {0}, out[16 * 4];
    memset(out, 0xAB, sizeof out);
    color_block(block, 0xFFFF, 0xFFFF, 0);

    bc::decode_block(bc::kDxt1, block, out, 16, 4, 4);

    for (int y = 0; y < 4; y++) {
        for (int x = 0; x < 4; x++) {
            CHECK_INT(pixel(out, 16, x, y, 0), 255);
            CHECK_INT(pixel(out, 16, x, y, 1), 255);
            CHECK_INT(pixel(out, 16, x, y, 2), 255);
            CHECK_INT(pixel(out, 16, x, y, 3), 255);
        }
    }
}

TEST(dxt1_indices_pick_colours) {
    uint8_t block[8] = {0}, out[16 * 4];
    memset(out, 0xAB, sizeof out);
    /* c0 > c1: index 1 is c1 (black), 2 and 3 are interpolated. */
    color_block(block, 0xFFFF, 0x0000, 1 | (2 << 2) | (3 << 4));

    bc::decode_block(bc::kDxt1, block, out, 16, 4, 4);

    CHECK_INT(pixel(out, 16, 0, 0, 0), 0);
    CHECK_INT(pixel(out, 16, 0, 0, 3), 255);
    CHECK_INT(pixel(out, 16, 1, 0, 0), 170);
    CHECK_INT(pixel(out, 16, 2, 0, 0), 85);
    CHECK_INT(pixel(out, 16, 3, 0, 0), 255);
}

TEST(dxt1_transparent_mode) {
    uint8_t block[8] = {0}, out[16 * 4];
    memset(out, 0xAB, sizeof out);
    /* c0 <= c1 selects the punch-through alpha mode; index 3 is transparent. */
    color_block(block, 0x0000, 0xFFFF, 3);

    bc::decode_block(bc::kDxt1, block, out, 16, 4, 4);

    CHECK_INT(pixel(out, 16, 0, 0, 0), 0);
    CHECK_INT(pixel(out, 16, 0, 0, 1), 0);
    CHECK_INT(pixel(out, 16, 0, 0, 2), 0);
    CHECK_INT(pixel(out, 16, 0, 0, 3), 0);
    /* index 0 stays opaque white */
    CHECK_INT(pixel(out, 16, 1, 0, 3), 255);
}

TEST(dxt3_uses_explicit_alpha) {
    uint8_t block[16] = {0}, out[16 * 4];
    memset(out, 0xAB, sizeof out);
    block[0] = 0x0F;  /* pixel 0 = 15, pixel 1 = 0 */
    block[1] = 0xFF;  /* pixels 2 and 3 = 15 */
    color_block(block + 8, 0xFFFF, 0xFFFF, 0);

    bc::decode_block(bc::kDxt3, block, out, 16, 4, 4);

    CHECK_INT(pixel(out, 16, 0, 0, 3), 255);
    CHECK_INT(pixel(out, 16, 1, 0, 3), 0);
    CHECK_INT(pixel(out, 16, 2, 0, 3), 255);
}

TEST(dxt5_interpolates_alpha) {
    uint8_t block[16] = {0}, out[16 * 4];
    memset(out, 0xAB, sizeof out);
    block[0] = 255;  /* a0 > a1: six interpolated values */
    block[1] = 0;
    uint32_t bits = 2 | (7u << 3);  /* pixel 0 = a2, pixel 1 = a7 */
    for (int i = 0; i < 6; i++) block[2 + i] = (uint8_t)(bits >> (8 * i));
    color_block(block + 8, 0xFFFF, 0xFFFF, 0);

    bc::decode_block(bc::kDxt5, block, out, 16, 4, 4);

    CHECK_INT(pixel(out, 16, 0, 0, 3), 218);
    CHECK_INT(pixel(out, 16, 1, 0, 3), 36);
    CHECK_INT(pixel(out, 16, 2, 0, 3), 255);
}

TEST(dxt5_six_alpha_mode) {
    uint8_t block[16] = {0}, out[16 * 4];
    memset(out, 0xAB, sizeof out);
    block[0] = 0;  /* a0 <= a1: 0 and 255 are the extra values */
    block[1] = 255;
    uint32_t bits = 6 | (7u << 3);  /* pixel 0 = transparent, pixel 1 = opaque */
    for (int i = 0; i < 6; i++) block[2 + i] = (uint8_t)(bits >> (8 * i));
    color_block(block + 8, 0xFFFF, 0xFFFF, 0);

    bc::decode_block(bc::kDxt5, block, out, 16, 4, 4);

    CHECK_INT(pixel(out, 16, 0, 0, 3), 0);
    CHECK_INT(pixel(out, 16, 1, 0, 3), 255);
}

TEST(clips_to_the_requested_rectangle) {
    uint8_t block[8] = {0}, out[16 * 4];
    memset(out, 0xAB, sizeof out);
    color_block(block, 0xFFFF, 0xFFFF, 0);

    bc::decode_block(bc::kDxt1, block, out, 16, 2, 2);

    CHECK_INT(pixel(out, 16, 1, 1, 0), 255);       /* written */
    CHECK_INT(pixel(out, 16, 2, 0, 0), 0xAB);      /* clipped right */
    CHECK_INT(pixel(out, 16, 0, 2, 0), 0xAB);      /* clipped below */
}

TEST(dxt_detection_and_block_size) {
    CHECK(bc::is_dxt(bc::kDxt1));
    CHECK(bc::is_dxt(bc::kDxt2));
    CHECK(bc::is_dxt(bc::kDxt3));
    CHECK(bc::is_dxt(bc::kDxt4));
    CHECK(bc::is_dxt(bc::kDxt5));
    CHECK(!bc::is_dxt(0x12345678u));
    CHECK_INT(bc::block_bytes(bc::kDxt1), 8);
    CHECK_INT(bc::block_bytes(bc::kDxt5), 16);
}

int main(void) {
    RUN(rgb565_endpoints);
    RUN(dxt1_solid_white);
    RUN(dxt1_indices_pick_colours);
    RUN(dxt1_transparent_mode);
    RUN(dxt3_uses_explicit_alpha);
    RUN(dxt5_interpolates_alpha);
    RUN(dxt5_six_alpha_mode);
    RUN(clips_to_the_requested_rectangle);
    RUN(dxt_detection_and_block_size);
    return th_report("bc_decode");
}
