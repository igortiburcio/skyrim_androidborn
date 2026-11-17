/* Face tint watching: which textures count, sizes, and the .dds dump header. */
#include "tex_watch.h"
#include "harness.h"

using namespace texwatch;

static const uint32_t kDxt1 = 0x31545844u;
static const uint32_t kDxt5 = 0x35545844u;
static const uint32_t kManaged = 1;
static const uint32_t kDefault = 0;

TEST(face_tint_signature) {
    /* the player's generated tint: 512x1024 DXT5, managed, full mip chain */
    CHECK(is_face_tint(512, 1024, kDxt5, kManaged, 11));
    /* the high-resolution pack's tints */
    CHECK(is_face_tint(1024, 2048, kDxt5, kManaged, 12));
    /* low texture quality halves it (fanless handhelds run this) */
    CHECK(is_face_tint(256, 512, kDxt5, kManaged, 10));
    /* a texture-quality setting can drop two mips */
    CHECK(is_face_tint(512, 1024, kDxt5, 2 /* sysmem */, 9));
    /* other shapes and pools are ordinary art */
    CHECK(!is_face_tint(1024, 1024, kDxt5, kManaged, 11));
    CHECK(!is_face_tint(64, 128, kDxt5, kManaged, 8));
    CHECK(!is_face_tint(512, 2048, kDxt5, kManaged, 11));
    CHECK(!is_face_tint(512, 1024, kDxt5, kDefault, 11));
    CHECK(!is_face_tint(512, 1024, 21 /* A8R8G8B8 */, kManaged, 11));
    CHECK(!is_face_tint(512, 1024, kDxt5, kManaged, 4));
    CHECK(!is_face_tint(0, 0, kDxt5, kManaged, 11));
}

TEST(level0_size) {
    /* ceil(w/4) * ceil(h/4) * block bytes */
    CHECK_INT(level_size(512, 1024, kDxt5), 128 * 256 * 16);
    CHECK_INT(level_size(512, 1024, kDxt1), 128 * 256 * 8);
    CHECK_INT(level_size(1024, 2048, kDxt5), 256 * 512 * 16);
    /* sizes that are not multiples of the block are padded, never truncated */
    CHECK_INT(level_size(6, 10, kDxt1), 2 * 3 * 8);
}

TEST(blank_detection) {
    uint8_t buf[16] = {0};
    CHECK(is_blank(buf, sizeof buf));
    buf[15] = 1;
    CHECK(!is_blank(buf, sizeof buf));
    /* a zero-length write is trivially blank and must not read past the end */
    CHECK(is_blank(buf, 0));
}

TEST(checksum_is_stable_and_sensitive) {
    CHECK(checksum((const uint8_t *)"", 0) == 0xcbf29ce484222325ull);
    CHECK(checksum((const uint8_t *)"a", 1) == 0xaf63dc4c8601ec8cull);
    const uint8_t a[4] = {1, 2, 3, 4};
    uint8_t b[4] = {1, 2, 3, 4};
    CHECK(checksum(a, 4) == checksum(b, 4));
    b[3] = 5;
    CHECK(checksum(a, 4) != checksum(b, 4));
}

TEST(repair_default) {
    /* on unless the player turned it off; the Support button wins */
    CHECK(repair_enabled(false, false));
    CHECK(repair_enabled(true, false));
    CHECK(repair_enabled(true, true));
    CHECK(!repair_enabled(false, true));
}

TEST(dds_header_fields) {
    uint8_t h[128];
    dds_header(h, 512, 1024, kDxt5, level_size(512, 1024, kDxt5));
    uint32_t v;
    CHECK(memcmp(h, "DDS ", 4) == 0);
    memcpy(&v, h + 4, 4);
    CHECK_INT(v, 124);
    memcpy(&v, h + 8, 4);
    CHECK_INT(v, 0x00081007u);
    memcpy(&v, h + 12, 4);
    CHECK_INT(v, 1024);                         /* height before width */
    memcpy(&v, h + 16, 4);
    CHECK_INT(v, 512);
    memcpy(&v, h + 20, 4);
    CHECK_INT(v, level_size(512, 1024, kDxt5)); /* dwPitchOrLinearSize */
    memcpy(&v, h + 28, 4);
    CHECK_INT(v, 1);                            /* one level dumped */
    memcpy(&v, h + 76, 4);
    CHECK_INT(v, 32);
    memcpy(&v, h + 80, 4);
    CHECK_INT(v, 4);                            /* DDPF_FOURCC */
    memcpy(&v, h + 84, 4);
    CHECK_INT(v, 0x35545844u);                  /* 'DXT5' */
    memcpy(&v, h + 108, 4);
    CHECK_INT(v, 0x1000);                       /* DDSCAPS_TEXTURE */
}

int main(void) {
    RUN(face_tint_signature);
    RUN(level0_size);
    RUN(blank_detection);
    RUN(checksum_is_stable_and_sensitive);
    RUN(repair_default);
    RUN(dds_header_fields);
    return th_report("test_texwatch");
}
