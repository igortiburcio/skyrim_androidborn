/* Face rebuild through the game's own race menu (Support -> Repair character
 * face).
 *
 * A save that already carries a dark face has to have the head built again from
 * the character's data, and the game does exactly that when it leaves the race
 * menu: open it, confirm without changing the race, and the colours come back.
 * The port asks the console for ShowRaceMenu after a save is loaded, so the
 * player only has to confirm it.
 *
 * The switch is <save>/facerebuild.txt, written by Support -> Repair character
 * face; <save>/nofacerebuild.txt turns it off. It is kept on purpose: a save
 * whose face only looks right while the game is running (a report of "Continue"
 * showing the dark face again) needs the rebuild on every load, and that is
 * what the switch being present means.
 *
 * A save can take a minute to load (game folder on an SD card), and the console
 * ignores keys while the loading screen is up, so the command is tried once
 * more if the game is slow.
 */
#include "rt.h"
#include <unistd.h>

void platform_script_text(const char *s);

/* 30 fps: first try 50 s after the save file opens, the second 30 s later. */
#define FIRST_TRY_FRAMES 1500
#define RETRY_FRAMES 900
#define ATTEMPTS 2

static int g_state = -1;  /* -1 not read yet, 0 off, 1 waiting */
static int g_wait, g_tries;

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
        if (g_state) LOG("facerebuild: the race menu will open once the save is loaded; confirm it unchanged");
    }
    if (g_state == 1 && !g_wait) {
        g_wait = FIRST_TRY_FRAMES;
        g_tries = 0;
    }
}

/* platform_sdl.c: every presented frame. */
void face_rebuild_tick(void) {
    if (g_state != 1 || !g_wait) return;
    if (--g_wait > 0) return;
    if (g_tries >= ATTEMPTS) {
        LOG("facerebuild: the race menu did not take; load the save again while Repair character face is on");
        g_wait = 0;
        return;
    }
    g_tries++;
    g_wait = g_tries == 1 ? RETRY_FRAMES : 0;
    LOG("facerebuild: asking the game for the race menu (try %d); confirm it without changing the race", g_tries);
    platform_script_text("ShowRaceMenu");
}
