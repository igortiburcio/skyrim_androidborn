/* Touch overlay layout, stick clamping and the drag-camera decay. */
#include "touch_math.h"
#include "harness.h"

static const float A = 16.0f / 9.0f;

TEST(button_layout_anchors_to_the_corner) {
    float x, y;
    touch::button_pos(touch::kButtons[0], A, &x, &y);  /* A button */
    CHECK_NEAR(x, 0.8875, 1e-3);
    CHECK_NEAR(y, 0.86, 1e-6);

    touch::button_pos(touch::kButtons[9], A, &x, &y);  /* Start */
    CHECK_NEAR(x, 1.0f - 0.36f / A, 1e-6);
    CHECK_NEAR(y, 0.07, 1e-6);
}

TEST(hit_test_finds_the_button_under_the_finger) {
    float x, y;

    touch::button_pos(touch::kButtons[0], A, &x, &y);
    CHECK_INT(touch::hit_button(x, y, A), 0);

    touch::button_pos(touch::kButtons[1], A, &x, &y);
    CHECK_INT(touch::hit_button(x, y, A), 1);

    touch::button_pos(touch::kButtons[7], A, &x, &y);  /* RT */
    CHECK_INT(touch::hit_button(x, y, A), 7);

    CHECK_INT(touch::hit_button(0.5f, 0.5f, A), -1);
}

TEST(hit_test_has_a_small_margin_around_the_disc) {
    float x, y;
    touch::button_pos(touch::kButtons[0], A, &x, &y);
    CHECK_INT(touch::hit_button(x + touch::kButtons[0].r * 1.2f / A, y, A), 0);
    CHECK_INT(touch::hit_button(x + touch::kButtons[0].r * 1.6f / A, y, A), -1);
}

TEST(stick_base_is_where_the_thumb_starts) {
    float x, y;
    touch::stick_pos(A, &x, &y);
    CHECK_NEAR(x, 0.24f / A, 1e-6);
    CHECK_NEAR(y, 0.74f, 1e-6);
}

TEST(move_stick_centres_and_clamps) {
    float cx, cy, sx, sy;
    touch::stick_pos(A, &cx, &cy);

    touch::move_stick(cx, cy, A, &sx, &sy);
    CHECK_NEAR(sx, 0.0, 1e-6);
    CHECK_NEAR(sy, 0.0, 1e-6);

    touch::move_stick(cx + 0.5f, cy, A, &sx, &sy);
    CHECK_NEAR(sx, 1.0, 1e-5);
    CHECK_NEAR(sy, 0.0, 1e-6);

    touch::move_stick(cx, cy + 0.085f, A, &sx, &sy);
    CHECK_NEAR(sx, 0.0, 1e-6);
    CHECK_NEAR(sy, 0.5, 1e-5);

    touch::move_stick(cx + 0.5f, cy + 0.5f, A, &sx, &sy);
    CHECK_NEAR(sx * sx + sy * sy, 1.0, 1e-5);
}

TEST(look_stick_up_is_forward) {
    float sx, sy;

    touch::look_stick(touch::kLookX, touch::kLookY, A, &sx, &sy);
    CHECK_NEAR(sx, 0.0, 1e-6);
    CHECK_NEAR(sy, 0.0, 1e-6);

    /* finger above the base = look up = +1 for the game */
    touch::look_stick(touch::kLookX, touch::kLookY - 0.075f, A, &sx, &sy);
    CHECK_NEAR(sy, 0.5, 1e-5);

    touch::look_stick(touch::kLookX, touch::kLookY + 0.075f, A, &sx, &sy);
    CHECK_NEAR(sy, -0.5, 1e-5);

    touch::look_stick(touch::kLookX + 0.075f, touch::kLookY, A, &sx, &sy);
    CHECK_NEAR(sx, 0.075f * A / touch::kLookR, 1e-5);
    CHECK_NEAR(sy, 0.0, 1e-6);
}

TEST(look_stick_clamps_to_the_unit_circle) {
    float sx, sy;
    touch::look_stick(touch::kLookX + 1.0f, touch::kLookY, A, &sx, &sy);
    CHECK_NEAR(sx * sx + sy * sy, 1.0, 1e-5);
}

TEST(camera_step_emits_one_stick_and_keeps_the_rest) {
    float px = 3.0f, py = 0.0f, rx, ry;

    touch::camera_step(&px, &py, &rx, &ry);
    CHECK_NEAR(rx, 1.0, 1e-6);
    CHECK_NEAR(ry, 0.0, 1e-6);
    CHECK_NEAR(px, 2.0f * touch::kLookDecay, 1e-6);
    CHECK_NEAR(py, 0.0, 1e-6);

    touch::camera_step(&px, &py, &rx, &ry);
    CHECK_NEAR(rx, 1.0, 1e-6);
    CHECK_NEAR(px, 0.3f * touch::kLookDecay, 1e-6);

    touch::camera_step(&px, &py, &rx, &ry);
    CHECK_NEAR(rx, 0.27 + 0.73 * 0.195, 1e-5);
    CHECK_NEAR(px, 0.0, 1e-6);

    touch::camera_step(&px, &py, &rx, &ry);
    CHECK_NEAR(rx, 0.0, 1e-6);
    CHECK_NEAR(px, 0.0, 1e-6);
}

TEST(camera_step_compensates_the_game_deadzone) {
    float px = 0.5f, py = 0.0f, rx, ry;

    touch::camera_step(&px, &py, &rx, &ry);

    CHECK_NEAR(rx, 0.27 + 0.73 * 0.5, 1e-5);
    CHECK_NEAR(ry, 0.0, 1e-6);
    CHECK_NEAR(px, 0.0, 1e-6);
}

TEST(camera_step_diagonal_keeps_the_direction) {
    float px = 0.5f, py = 0.5f, rx, ry;

    touch::camera_step(&px, &py, &rx, &ry);

    double m = 0.27 + 0.73 * (0.5 * 1.41421356);
    CHECK_NEAR(rx, 0.5 * 1.41421356 * m, 1e-5);
    CHECK_NEAR(rx, ry, 1e-6);
}

TEST(camera_step_below_the_minimum_stops) {
    float px = 0.01f, py = 0.0f, rx, ry;

    touch::camera_step(&px, &py, &rx, &ry);

    CHECK_NEAR(rx, 0.0, 1e-6);
    CHECK_NEAR(ry, 0.0, 1e-6);
    CHECK_NEAR(px, 0.0, 1e-6);
}

TEST(camera_step_keeps_the_sign) {
    float px = -0.5f, py = 0.0f, rx, ry;
    touch::camera_step(&px, &py, &rx, &ry);
    CHECK(rx < 0);
    CHECK_NEAR(ry, 0.0, 1e-6);
}

TEST(button_table_shape) {
    CHECK_INT(touch::kButtonCount, 10);
    CHECK_STR(touch::kButtons[0].name, "A");
    CHECK_STR(touch::kButtons[9].name, "Start");
    CHECK_INT(touch::kButtons[6].trig, 1);  /* LT */
    CHECK_INT(touch::kButtons[7].trig, 2);  /* RT */
}

int main(void) {
    RUN(button_layout_anchors_to_the_corner);
    RUN(hit_test_finds_the_button_under_the_finger);
    RUN(hit_test_has_a_small_margin_around_the_disc);
    RUN(stick_base_is_where_the_thumb_starts);
    RUN(move_stick_centres_and_clamps);
    RUN(look_stick_up_is_forward);
    RUN(look_stick_clamps_to_the_unit_circle);
    RUN(camera_step_emits_one_stick_and_keeps_the_rest);
    RUN(camera_step_compensates_the_game_deadzone);
    RUN(camera_step_diagonal_keeps_the_direction);
    RUN(camera_step_below_the_minimum_stops);
    RUN(camera_step_keeps_the_sign);
    RUN(button_table_shape);
    return th_report("touch_math");
}
