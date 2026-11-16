/* Host tests for the pure gamepad helpers in runtime/input_merge.h.
 * They cover the rules the XInput shim relies on when every controller is
 * merged into player 0, and the Nintendo button swap. */
#include "harness.h"
#include "input_merge.h"

TEST(merge_ors_buttons_and_takes_the_deeper_trigger) {
    RTPadState a = {0}, b = {0};
    a.buttons = 0x1000;  /* A */
    b.buttons = 0x0001;  /* D-pad up */
    a.lt = 100;
    b.lt = 20;
    b.rt = 200;
    rt_pad_merge(&a, &b);
    CHECK_INT(a.buttons, 0x1001);
    CHECK_INT(a.lt, 100);
    CHECK_INT(a.rt, 200);
}

TEST(merge_takes_the_stick_pushed_furthest_as_a_pair) {
    RTPadState a = {0}, b = {0};
    a.lx = 32767;   /* built-in controls pushed fully right */
    b.lx = -16000;  /* docked pad pushed half left */
    rt_pad_merge(&a, &b);
    CHECK_INT(a.lx, 32767);
    CHECK_INT(a.ly, 0);

    RTPadState c = {0}, d = {0};
    c.lx = 16000;
    d.lx = -12000;
    d.ly = 12000;  /* diagonal is longer than the straight push */
    rt_pad_merge(&c, &d);
    CHECK_INT(c.lx, -12000);
    CHECK_INT(c.ly, 12000);
}

TEST(merge_keeps_the_two_sticks_independent) {
    RTPadState a = {0}, b = {0};
    a.lx = 20000;
    b.rx = -20000;
    b.ry = 8000;
    rt_pad_merge(&a, &b);
    CHECK_INT(a.lx, 20000);
    CHECK_INT(a.rx, -20000);
    CHECK_INT(a.ry, 8000);
}

TEST(merge_ignores_an_idle_pad) {
    RTPadState a = {0}, idle = {0};
    a.buttons = 0x10;
    a.lx = -25000;
    a.rt = 180;
    rt_pad_merge(&a, &idle);
    CHECK_INT(a.buttons, 0x10);
    CHECK_INT(a.lx, -25000);
    CHECK_INT(a.rt, 180);
}

TEST(nintendo_swap_only_swaps_the_face_buttons) {
    /* D-pad, Start, Back, sticks and shoulders keep their bits. */
    CHECK_INT(rt_pad_nintendo(0x03FF), 0x03FF);
    CHECK_INT(rt_pad_nintendo(0x1000), 0x2000);  /* A -> B */
    CHECK_INT(rt_pad_nintendo(0x2000), 0x1000);  /* B -> A */
    CHECK_INT(rt_pad_nintendo(0x4000), 0x8000);  /* X -> Y */
    CHECK_INT(rt_pad_nintendo(0x8000), 0x4000);  /* Y -> X */
    CHECK_INT(rt_pad_nintendo(0xF3FF), 0xF3FF);  /* everything at once */
}

TEST(packet_is_new_when_anything_readable_changed) {
    RTPadState a = {0}, b = {0};
    CHECK(rt_pad_same(&a, &b));
    b.lx = 1;
    CHECK(!rt_pad_same(&a, &b));
    b.lx = 0;
    CHECK(rt_pad_same(&a, &b));
    b.rt = 7;
    CHECK(!rt_pad_same(&a, &b));
    b.rt = 0;
    b.buttons = 0x2;
    CHECK(!rt_pad_same(&a, &b));
}

int main(void) {
    RUN(merge_ors_buttons_and_takes_the_deeper_trigger);
    RUN(merge_takes_the_stick_pushed_furthest_as_a_pair);
    RUN(merge_keeps_the_two_sticks_independent);
    RUN(merge_ignores_an_idle_pad);
    RUN(nintendo_swap_only_swaps_the_face_buttons);
    RUN(packet_is_new_when_anything_readable_changed);
    return th_report("test_input");
}
