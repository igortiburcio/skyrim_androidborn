// Layout and stick/camera math for the touch overlay. Kept free of SDL and
// Direct3D so tests/native can compile it on the host; touch.cpp supplies the
// screen aspect and does the event and drawing work.
#pragma once
#include <cmath>
#include <cstdint>

namespace touch {

// Layout in units of screen height, anchored to a corner (ax, ay in {0,1}).
// label is drawn inside the disc (null: a symbol is drawn instead).
struct Button {
    const char *name; uint16_t bit; float ax, ay, dx, dy, r; uint32_t color; uint8_t trig;
    const char *label; uint32_t text;
};

inline const Button kButtons[] = {
    {"A", 0x1000, 1, 1, -0.20f, -0.14f, 0.075f, 0xFF40C040, 0, "A", 0xF0FFFFFF},
    {"B", 0x2000, 1, 1, -0.08f, -0.27f, 0.075f, 0xFFE04040, 0, "B", 0xF0FFFFFF},
    {"X", 0x4000, 1, 1, -0.32f, -0.27f, 0.075f, 0xFF4080F0, 0, "X", 0xF0FFFFFF},
    {"Y", 0x8000, 1, 1, -0.20f, -0.40f, 0.075f, 0xFFF0D040, 0, "Y", 0xF0FFFFFF},
    {"LB", 0x0100, 0, 0, 0.12f, 0.30f, 0.06f, 0xFFB0B0B0, 0, "LB", 0xF0FFFFFF},
    {"RB", 0x0200, 1, 0, -0.12f, 0.30f, 0.06f, 0xFFB0B0B0, 0, "RB", 0xF0FFFFFF},
    {"LT", 0, 0, 0, 0.12f, 0.42f, 0.06f, 0xFFB0B0B0, 1, "LT", 0xF0FFFFFF},
    {"RT", 0, 1, 0, -0.12f, 0.42f, 0.06f, 0xFFB0B0B0, 2, "RT", 0xF0FFFFFF},
    {"Back", 0x0020, 0, 0, 0.36f, 0.07f, 0.045f, 0xFFD0D0D0, 0, nullptr, 0xFF101010},
    {"Start", 0x0010, 1, 0, -0.36f, 0.07f, 0.045f, 0xFFFFFFFF, 0, nullptr, 0xFF101010},
};
inline constexpr int kButtonCount = (int)(sizeof(kButtons) / sizeof(kButtons[0]));

inline constexpr float kStickX = 0.24f, kStickY = -0.26f, kStickR = 0.17f; // from bottom-left
inline constexpr float kLookR = 0.15f;                                    // fixed stick base radius
inline constexpr float kLookX = 0.62f, kLookY = 0.70f;                    // fixed stick base centre
// Drag camera: pending is in stick units; a drag adds travel * kLookGain and
// each frame emits up to a full stick and keeps kLookDecay of the rest, so the
// camera stops shortly after the finger does. The game's own deadzone is
// compensated at the output, otherwise short drags would do nothing.
inline constexpr float kLookGain = 60.0f;      // stick units per screen height of travel
inline constexpr float kLookDecay = 0.65f;     // pending kept each frame
inline constexpr float kLookDeadzone = 0.27f;  // the game's right-stick deadzone, cancelled at the output
inline constexpr float kLookMin = 0.02f;       // below this the camera is still

// Centre of a button in normalised screen coordinates (0..1 both axes).
inline void button_pos(const Button &b, float aspect, float *x, float *y) {
    *x = b.ax + b.dx / aspect;
    *y = b.ay + b.dy;
}

inline void stick_pos(float aspect, float *x, float *y) {
    *x = kStickX / aspect;
    *y = 1 + kStickY;
}

inline int hit_button(float x, float y, float aspect) {
    for (int i = 0; i < kButtonCount; i++) {
        float bx, by;
        button_pos(kButtons[i], aspect, &bx, &by);
        float dx = (x - bx) * aspect, dy = y - by, r = kButtons[i].r * 1.3f;
        if (dx * dx + dy * dy < r * r) return i;
    }
    return -1;
}

// Left stick: finger offset from the base, clamped to the unit circle.
inline void move_stick(float fx, float fy, float aspect, float *sx, float *sy) {
    float cx, cy;
    stick_pos(aspect, &cx, &cy);
    *sx = (fx - cx) * aspect / kStickR;
    *sy = (fy - cy) / kStickR;
    float l = std::sqrt(*sx * *sx + *sy * *sy);
    if (l > 1) { *sx /= l; *sy /= l; }
}

// Fixed-base camera stick: up is +1 (the game's forward).
inline void look_stick(float fx, float fy, float aspect, float *rx, float *ry) {
    *rx = (fx - kLookX) * aspect / kLookR;
    *ry = -(fy - kLookY) / kLookR;
    float l = std::sqrt(*rx * *rx + *ry * *ry);
    if (l > 1) { *rx /= l; *ry /= l; }
}

// One drag-camera frame: emit at most a full stick from the pending travel,
// keep kLookDecay of the rest, and cancel the game's deadzone.
inline void camera_step(float *pending_x, float *pending_y, float *out_x, float *out_y) {
    float rx = *pending_x, ry = *pending_y;
    rx = rx > 1 ? 1 : rx < -1 ? -1 : rx;
    ry = ry > 1 ? 1 : ry < -1 ? -1 : ry;
    *pending_x = (*pending_x - rx) * kLookDecay;
    *pending_y = (*pending_y - ry) * kLookDecay;
    if (std::fabs(*pending_x) < 0.01f) *pending_x = 0;
    if (std::fabs(*pending_y) < 0.01f) *pending_y = 0;
    float l = std::sqrt(rx * rx + ry * ry);
    if (l > kLookMin) {
        float m = kLookDeadzone + (1 - kLookDeadzone) * (l > 1 ? 1 : l);
        rx = rx / l * m;
        ry = ry / l * m;
    } else {
        rx = ry = 0;
    }
    *out_x = rx;
    *out_y = ry;
}

} // namespace touch
