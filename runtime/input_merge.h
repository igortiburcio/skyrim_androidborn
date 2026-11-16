/* Pure gamepad state helpers shared by runtime/input.c and the host tests.
 *
 * The XInput shim reports one pad state per player slot. On Android the first
 * controller SDL opens takes slot 0, but Skyrim only reads player 0, so every
 * connected controller is merged into that slot: the pad the player picks up
 * works no matter which one Android enumerates first. */
#pragma once
#include <stdint.h>
#include <string.h>

/* One XInput gamepad report: the same fields as XINPUT_STATE. */
typedef struct {
    uint16_t buttons;
    uint8_t lt, rt;
    int16_t lx, ly, rx, ry;
} RTPadState;

/* Squared stick length, widened so two full-throw axes cannot overflow. */
static inline int64_t rt_pad_mag2(int16_t x, int16_t y) {
    return (int64_t)x * x + (int64_t)y * y;
}

/* Swaps A/B and X/Y, leaving the D-pad, Start/Back, sticks and shoulders. */
static inline uint16_t rt_pad_nintendo(uint16_t b) {
    return (uint16_t)((b & 0x0FFF) | ((b & 0x1000) << 1) | ((b & 0x2000) >> 1) |
                      ((b & 0x4000) << 1) | ((b & 0x8000) >> 1));
}

/* Adds one pad to another: buttons are ORed, triggers take the deeper pull and
 * a stick is taken as a pair, from the pad pushing it furthest. Taking the axes
 * one by one would turn two straight pushes into a diagonal. */
static inline void rt_pad_merge(RTPadState *acc, const RTPadState *one) {
    acc->buttons |= one->buttons;
    if (one->lt > acc->lt) acc->lt = one->lt;
    if (one->rt > acc->rt) acc->rt = one->rt;
    if (rt_pad_mag2(one->lx, one->ly) > rt_pad_mag2(acc->lx, acc->ly)) {
        acc->lx = one->lx;
        acc->ly = one->ly;
    }
    if (rt_pad_mag2(one->rx, one->ry) > rt_pad_mag2(acc->rx, acc->ry)) {
        acc->rx = one->rx;
        acc->ry = one->ry;
    }
}

/* True when two reports carry the same state (a new packet is only needed
 * when something the game can read actually changed). */
static inline int rt_pad_same(const RTPadState *a, const RTPadState *b) {
    return a->buttons == b->buttons && a->lt == b->lt && a->rt == b->rt &&
           a->lx == b->lx && a->ly == b->ly && a->rx == b->rx && a->ry == b->ry;
}
