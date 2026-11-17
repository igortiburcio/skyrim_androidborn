/* Face rebuild through the game's own race menu (Support -> Repair character
 * face).
 *
 * A save that already carries a dark face has to have the head built again from
 * the character's data, and the game does exactly that when it leaves the race
 * menu: open it, confirm without changing the race, and the colours come back.
 * The port asks the console for ShowRaceMenu after a save is loaded, so the
 * player only has to confirm it and save.
 *
 * It is one shot: the switch <save>/facerebuild.txt is written by Support ->
 * Repair character face and removed when the menu is asked for, so later loads
 * stay quiet. Saving after confirming stores the rebuilt face; if the face comes
 * back dark, the player taps the button again.
 *
 * <save>/nofacerebuild.txt turns it off without touching the button's file.
 */
#include "rt.h"
#include <unistd.h>

void platform_script_text(const char *s);

/* 30 fps: 80 seconds after the save file opens. A save on an SD card can take
 * a minute to load, and the console ignores keys during the loading screen. */
#define WAIT_FRAMES 2400

static int g_state = -1;  /* -1 not read yet, 0 off/done, 1 waiting */
static int g_wait;

static void switch_path(const char *name, char *out, size_t n) {
    snprintf(out, n, "%s/%s", g_save_dir, name);
}

static int switch_on(const char *name) {
    char path[1100];
    switch_path(name, path, sizeof path);
    return access(path, F_OK) == 0;
}

/* kernel32.c: the engine opened a save for reading. */
void face_rebuild_save_opened(void) {
    if (g_state < 0) {
        g_state = (switch_on("facerebuild.txt") && !switch_on("nofacerebuild.txt")) ? 1 : 0;
        if (g_state) LOG("facerebuild: the race menu will open once the save is loaded; confirm it unchanged, then save");
    }
    if (g_state == 1 && !g_wait) g_wait = WAIT_FRAMES;
}

/* platform_sdl.c: every presented frame. */
void face_rebuild_tick(void) {
    if (g_state != 1 || !g_wait) return;
    if (--g_wait > 0) return;
    g_state = 0;
    char path[1100];
    switch_path("facerebuild.txt", path, sizeof path);
    unlink(path);  /* one shot: confirm the menu and save; tap again if needed */
    LOG("facerebuild: asking the game for the race menu; confirm it with X unchanged and save");
    platform_script_text("ShowRaceMenu");
}
