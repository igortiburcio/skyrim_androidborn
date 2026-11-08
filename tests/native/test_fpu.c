/* x87 helpers: 80-bit load/store, fxam classification and fprem flags. */
#include "rt.h"
#include "harness.h"

uint8_t *MEM;
static uint8_t g_mem[4096] __attribute__((aligned(16)));

static CPU *cpu_new(void) {
    static CPU c;
    memset(&c, 0, sizeof c);
    c.top = 0;
    return &c;
}

TEST(f80_roundtrip) {
    static const double values[] = {1.0, -1.0, 3.141592653589793, -2.5e-8, 1e-30, 1e300,
                                    -12345.6789};
    for (int i = 0; i < (int)(sizeof values / sizeof values[0]); i++) {
        double v = values[i];
        uint32_t at = (uint32_t)(16 * i);
        f80_store(at, v);
        CHECK_NEAR(f80_load(at), v, fabs(v) * 1e-15);
    }
}

TEST(f80_zero_keeps_the_sign) {
    f80_store(0, 0.0);
    double z = f80_load(0);
    CHECK(z == 0.0);
    CHECK(!signbit(z));

    f80_store(16, -0.0);
    double nz = f80_load(16);
    CHECK(nz == 0.0);
    CHECK(signbit(nz));
}

TEST(f80_infinity_and_nan) {
    f80_store(0, INFINITY);
    CHECK(isinf(f80_load(0)));
    CHECK(f80_load(0) > 0);

    f80_store(16, -INFINITY);
    CHECK(isinf(f80_load(16)));
    CHECK(f80_load(16) < 0);

    f80_store(32, NAN);
    CHECK(isnan(f80_load(32)));
}

TEST(fxam_classifies) {
    CPU *c = cpu_new();

    c->st[0] = 0.0;
    fxam(c);
    CHECK_INT(c->c3, 1);
    CHECK_INT(c->c2, 0);
    CHECK_INT(c->c0, 0);
    CHECK_INT(c->c1, 0);

    c->st[0] = -0.0;
    fxam(c);
    CHECK_INT(c->c1, 1);
    CHECK_INT(c->c3, 1);

    c->st[0] = 1.0;
    fxam(c);
    CHECK_INT(c->c3, 0);
    CHECK_INT(c->c2, 1);
    CHECK_INT(c->c0, 0);

    c->st[0] = INFINITY;
    fxam(c);
    CHECK_INT(c->c3, 0);
    CHECK_INT(c->c2, 1);
    CHECK_INT(c->c0, 1);

    c->st[0] = NAN;
    fxam(c);
    CHECK_INT(c->c3, 0);
    CHECK_INT(c->c2, 0);
    CHECK_INT(c->c0, 1);

    c->st[0] = 1e-320;  /* subnormal double */
    fxam(c);
    CHECK_INT(c->c3, 1);
    CHECK_INT(c->c2, 1);
    CHECK_INT(c->c0, 0);
}

TEST(fprem_truncating_sets_the_quotient_flags) {
    CPU *c = cpu_new();
    c->st[0] = 7.5;
    c->st[1] = 2.0;

    fprem(c, 0);

    CHECK_NEAR(c->st[0], 1.5, 1e-12);
    CHECK_INT(c->c2, 0);
    CHECK_INT(c->c0, 0);  /* quotient 3: bit 2 */
    CHECK_INT(c->c3, 1);  /* bit 1 */
    CHECK_INT(c->c1, 1);  /* bit 0 */
}

TEST(fprem_ieee_rounds_to_nearest) {
    CPU *c = cpu_new();
    c->st[0] = 7.5;
    c->st[1] = 2.0;

    fprem(c, 1);

    CHECK_NEAR(c->st[0], -0.5, 1e-12);
    CHECK_INT(c->c0, 1);  /* quotient 4: bit 2 */
    CHECK_INT(c->c3, 0);
    CHECK_INT(c->c1, 0);
}

int main(void) {
    MEM = g_mem;
    RUN(f80_roundtrip);
    RUN(f80_zero_keeps_the_sign);
    RUN(f80_infinity_and_nan);
    RUN(fxam_classifies);
    RUN(fprem_truncating_sets_the_quotient_flags);
    RUN(fprem_ieee_rounds_to_nearest);
    return th_report("fpu");
}
