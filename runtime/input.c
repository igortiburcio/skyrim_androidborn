/* Input: XInput 1.3 shims backed by SDL game controllers, plus a virtual
 * pad fed by the keyboard (desktop) and the touch overlay (Android). */
#include "rt.h"
#include "input_merge.h"
#include <SDL2/SDL.h>

static SDL_GameController *g_pads[4];
static SDL_JoystickID g_pad_id[4];   /* instance id of the pad in each slot */
static RTPadState g_virt;
static uint32_t g_packet;
static int g_nintendo;  /* swap A/B and X/Y on physical controllers */

void input_set_nintendo(int on) { g_nintendo = on; g_packet++; }

/* Skip Cutscenes: story cutscenes are levels of their own. While one is
 * playing or starting, pad 0 taps Start, which skips it, and A, which also
 * gets past the "press any key" screen of the level loading next. */
static int g_skip_cutscenes;
void input_set_skip_cutscenes(int on) { g_skip_cutscenes = on; }
/* The level being played: world (0x960894) +0x12C; level +0x64 flags, where
 * 0x20 intro, 0x40 cutscene/midtro and 0x80 outro mark cutscene levels. */
static int in_cutscene_level(void) {
#ifndef GAME_BATMAN
    return 0;  /* no cutscene skip for Marvel */
#endif
    uint32_t w = M32(0x960894), lv = w >= 0x10000 ? M32(w + 0x12C) : 0;
    if (lv < 0x10000) lv = M32(0xACA89C);  /* none yet: the one being started */
    return lv >= 0x10000 && (M8(lv + 0x64) & 0xE0);
}
static uint16_t skip_tap(void) {
    if (!g_skip_cutscenes || !in_cutscene_level()) return 0;
    uint32_t t = SDL_GetTicks() % 300;  /* Start at 0-60 ms, A at 150-210 ms */
    return t < 60 ? 0x10 : (t >= 150 && t < 210) ? 0x1000 : 0;
}

static const struct { SDL_Keycode key; uint16_t bit; } k_keymap[] = {
    {SDLK_UP, 0x1}, {SDLK_DOWN, 0x2}, {SDLK_LEFT, 0x4}, {SDLK_RIGHT, 0x8},
    {SDLK_RETURN, 0x10}, {SDLK_ESCAPE, 0x20}, {SDLK_SPACE, 0x1000}, {SDLK_LCTRL, 0x2000},
    {SDLK_LSHIFT, 0x4000}, {SDLK_TAB, 0x8000}, {SDLK_q, 0x100}, {SDLK_e, 0x200},
};

void input_set_virtual(uint16_t buttons, int16_t lx, int16_t ly, int16_t rx, int16_t ry, uint8_t lt, uint8_t rt) {
    g_virt.buttons = buttons;
    g_virt.lx = lx; g_virt.ly = ly; g_virt.rx = rx; g_virt.ry = ry;
    g_virt.lt = lt; g_virt.rt = rt;
    g_packet++;
}

/* Opens joystick `index` in the first free slot. A pad that is already open is
 * closed again, so an add event and the startup scan cannot open it twice. */
static void open_pad(int index) {
    if (!SDL_IsGameController(index)) return;  /* no gamepad mapping: nothing to read */
    SDL_GameController *p = SDL_GameControllerOpen(index);
    if (!p) {
        LOG("input: joystick %d left alone (%s)", index, SDL_GetError());
        return;
    }
    SDL_JoystickID id = SDL_JoystickInstanceID(SDL_GameControllerGetJoystick(p));
    for (int i = 0; i < 4; i++)
        if (g_pads[i] && g_pad_id[i] == id) { SDL_GameControllerClose(p); return; }
    for (int i = 0; i < 4; i++)
        if (!g_pads[i]) {
            g_pads[i] = p;
            g_pad_id[i] = id;
            LOG("input: pad %d = %s", i, SDL_GameControllerName(p));
            return;
        }
    LOG("input: no free slot for %s", SDL_GameControllerName(p));
    SDL_GameControllerClose(p);
}

/* Opens the pads that were already attached before the event pump started
 * (Android also sees a controller connected before launch this way). */
void input_scan_pads(void) {
    int n = SDL_NumJoysticks();
    for (int i = 0; i < n; i++) {
        LOG("input: joystick %d = %s%s", i, SDL_JoystickNameForIndex(i),
            SDL_IsGameController(i) ? "" : " (no gamepad mapping)");
        open_pad(i);
    }
}

void input_handle_event(const SDL_Event *e) {
    static int scanned;
    if (!scanned) { scanned = 1; input_scan_pads(); }
    switch (e->type) {
    case SDL_CONTROLLERDEVICEADDED:
        open_pad(e->cdevice.which);
        break;
    case SDL_CONTROLLERDEVICEREMOVED:
        for (int i = 0; i < 4; i++)
            if (g_pads[i] && g_pad_id[i] == e->cdevice.which) {
                SDL_GameControllerClose(g_pads[i]);
                g_pads[i] = NULL;
            }
        break;
    case SDL_KEYDOWN:
    case SDL_KEYUP:
        for (unsigned i = 0; i < sizeof k_keymap / sizeof k_keymap[0]; i++)
            if (k_keymap[i].key == e->key.keysym.sym) {
                if (e->type == SDL_KEYDOWN) g_virt.buttons |= k_keymap[i].bit;
                else g_virt.buttons &= (uint16_t)~k_keymap[i].bit;
                g_packet++;
            }
        {
            /* WASD = left stick; tracked from events so scripted keys work too */
            static int held[4];
            static const SDL_Keycode wasd[4] = {SDLK_w, SDLK_a, SDLK_s, SDLK_d};
            for (int i = 0; i < 4; i++)
                if (e->key.keysym.sym == wasd[i]) held[i] = e->type == SDL_KEYDOWN;
            g_virt.lx = (int16_t)((held[3] ? 32767 : 0) - (held[1] ? 32767 : 0));
            g_virt.ly = (int16_t)((held[0] ? 32767 : 0) - (held[2] ? 32767 : 0));
            g_packet++;
        }
        break;
    }
}

/* Reads one physical pad into an XInput-shaped report. */
static void read_pad(SDL_GameController *p, RTPadState *s) {
    static const struct { SDL_GameControllerButton b; uint16_t bit; } map[] = {
        {SDL_CONTROLLER_BUTTON_DPAD_UP, 0x1}, {SDL_CONTROLLER_BUTTON_DPAD_DOWN, 0x2},
        {SDL_CONTROLLER_BUTTON_DPAD_LEFT, 0x4}, {SDL_CONTROLLER_BUTTON_DPAD_RIGHT, 0x8},
        {SDL_CONTROLLER_BUTTON_START, 0x10}, {SDL_CONTROLLER_BUTTON_BACK, 0x20},
        {SDL_CONTROLLER_BUTTON_LEFTSTICK, 0x40}, {SDL_CONTROLLER_BUTTON_RIGHTSTICK, 0x80},
        {SDL_CONTROLLER_BUTTON_LEFTSHOULDER, 0x100}, {SDL_CONTROLLER_BUTTON_RIGHTSHOULDER, 0x200},
        {SDL_CONTROLLER_BUTTON_A, 0x1000}, {SDL_CONTROLLER_BUTTON_B, 0x2000},
        {SDL_CONTROLLER_BUTTON_X, 0x4000}, {SDL_CONTROLLER_BUTTON_Y, 0x8000},
    };
    memset(s, 0, sizeof *s);
    for (unsigned i = 0; i < sizeof map / sizeof map[0]; i++)
        if (SDL_GameControllerGetButton(p, map[i].b)) s->buttons |= map[i].bit;
    if (g_nintendo) s->buttons = rt_pad_nintendo(s->buttons);
    s->lt = (uint8_t)(SDL_GameControllerGetAxis(p, SDL_CONTROLLER_AXIS_TRIGGERLEFT) >> 7);
    s->rt = (uint8_t)(SDL_GameControllerGetAxis(p, SDL_CONTROLLER_AXIS_TRIGGERRIGHT) >> 7);
    s->lx = SDL_GameControllerGetAxis(p, SDL_CONTROLLER_AXIS_LEFTX);
    s->ly = (int16_t)~SDL_GameControllerGetAxis(p, SDL_CONTROLLER_AXIS_LEFTY);
    s->rx = SDL_GameControllerGetAxis(p, SDL_CONTROLLER_AXIS_RIGHTX);
    s->ry = (int16_t)~SDL_GameControllerGetAxis(p, SDL_CONTROLLER_AXIS_RIGHTY);
}

static void fill_state(int idx, uint32_t g) {
    RTPadState s = {0};
    if (idx == 0) {
#ifdef GAME_SKYRIM
        /* Skyrim reads XInput player 0 only and the port always reports it as
         * connected (the keyboard and the touch overlay live there). Merge
         * every pad into that slot: the integrated controls and a docked
         * controller both drive the game, whichever SDL opened first. */
        for (int i = 0; i < 4; i++)
            if (g_pads[i]) {
                RTPadState one;
                read_pad(g_pads[i], &one);
                rt_pad_merge(&s, &one);
            }
#else
        if (g_pads[0]) read_pad(g_pads[0], &s);
#endif
        s.buttons |= g_virt.buttons | skip_tap();
        if (g_virt.lt) s.lt = g_virt.lt;
        if (g_virt.rt) s.rt = g_virt.rt;
        if (g_virt.lx || g_virt.ly) { s.lx = g_virt.lx; s.ly = g_virt.ly; }
        if (g_virt.rx || g_virt.ry) { s.rx = g_virt.rx; s.ry = g_virt.ry; }
    } else if (idx < 4 && g_pads[idx]) {
        read_pad(g_pads[idx], &s);
    }
    {   /* a new packet number whenever the reported state changes */
        static RTPadState last[4];
        static int have_last[4];
        if (!have_last[idx & 3] || !rt_pad_same(&last[idx & 3], &s)) {
            last[idx & 3] = s;
            have_last[idx & 3] = 1;
            g_packet++;
        }
    }
    M32(g) = g_packet;
    M16(g + 4) = s.buttons;
    M8(g + 6) = s.lt;
    M8(g + 7) = s.rt;
    M16(g + 8) = (uint16_t)s.lx;
    M16(g + 10) = (uint16_t)s.ly;
    M16(g + 12) = (uint16_t)s.rx;
    M16(g + 14) = (uint16_t)s.ry;
}

static int connected(uint32_t i) { return i == 0 || (i < 4 && g_pads[i]); }

SHIM(XInputGetState) {
    uint32_t i = ARG(0);
    if (!connected(i)) RET(1167, 2);
    {   /* says which player slots the game actually reads, and what is there */
        static uint8_t seen[4];
        if (i < 4 && !seen[i]) {
            seen[i] = 1;
            LOG("input: game reads XInput pad %u (%s)", i, g_pads[i] ? "controller open" : "virtual only");
        }
    }
    fill_state((int)i, ARG(1));
    RET(0, 2);
}
SHIM(XInputSetState) {
    uint32_t i = ARG(0);
    if (!connected(i)) RET(1167, 2);
    if (i < 4 && g_pads[i])
        SDL_GameControllerRumble(g_pads[i], M16(ARG(1)), M16(ARG(1) + 2), 200);
    RET(0, 2);
}
SHIM(XInputGetCapabilities) {
    uint32_t i = ARG(0);
    if (!connected(i)) RET(1167, 3);
    uint32_t g = ARG(2);
    memset(MEM + g, 0, 20);
    M8(g) = 1;       /* XINPUT_DEVTYPE_GAMEPAD */
    M8(g + 1) = 1;   /* XINPUT_DEVSUBTYPE_GAMEPAD */
    M16(g + 4) = 0xF3FF;
    M8(g + 6) = 0xFF; M8(g + 7) = 0xFF;
    M16(g + 8) = 0xFFC0; M16(g + 10) = 0xFFC0; M16(g + 12) = 0xFFC0; M16(g + 14) = 0xFFC0;
    M16(g + 16) = 0xFF; M16(g + 18) = 0xFF;
    RET(0, 3);
}

/* Pads the game is told about: pad 0 (touch overlay, keyboard, first SDL
 * controller) always, plus any further SDL controllers. */
int input_pad_count(void) {
    int n = 1;
    while (n < 4 && g_pads[n]) n++;
    return n;
}

/* Replaces the game's IsXInputDevice (0x51E280, cdecl, GUID *guidProduct),
 * which asks WMI for "IG_" device ids. Our pads use the 360 pad's product id. */
#ifdef GAME_BATMAN
void f_51E280(CPU *c) {
    RETC(M32(ARG(0)) == 0x028E045Eu);
}
#endif

static const ShimDef defs[] = {
    {"XINPUT1_3.dll", "#2", shim_XInputGetState},
    {"XINPUT1_3.dll", "#3", shim_XInputSetState},
    {"XINPUT1_3.dll", "#4", shim_XInputGetCapabilities},
    {"XINPUT1_3.dll", "XInputGetState", shim_XInputGetState},
    {"XINPUT1_3.dll", "XInputSetState", shim_XInputSetState},
    {"XINPUT1_3.dll", "XInputGetCapabilities", shim_XInputGetCapabilities},
    {NULL, NULL, NULL}
};

void input_init(void) { rt_register_shims(defs); }
