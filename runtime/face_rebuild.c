/* One-shot face rebuild for a save that already carries a dark face (1.1.3).
 *
 * The lost colours live inside the save, so the engine has to build the head
 * again from the character's own data. It does exactly that when it leaves the
 * race menu, so the port asks the console for ShowRaceMenu after a save is
 * loaded: the player confirms the menu without changing anything and the head
 * (and its tint) is rebuilt from the character's data.
 *
 * Nothing is typed by hand, no race is changed and vampirism is not touched:
 * the menu already shows the character's own race, and the player is the one
 * who confirms it.
 *
 * Support -> Repair character face writes <save>/facerebuild.txt, which is
 * removed once the menu has been asked for, so it never repeats on its own.
 *
 * A save can take a minute to load (the game folder is often on an SD card) and
 * the console ignores keys while the loading screen is up, so the sequence is
 * tried again after a while. A new face tint (the rebuild) stops the retries.
 */
#include "rt.h"
#include <unistd.h>

void platform_script_text(const char *s);
int tex_watch_creates(void);

/* 30 fps: first try 50 s after the save file opens, the second 60 s later. */
#define FIRST_TRY_FRAMES 1500
#define RETRY_FRAMES 1800
#define ATTEMPTS 2

static int g_state = -1;  /* -1 not read yet, 0 off/done, 1 waiting */
static int g_wait, g_tries, g_seen;

static void switch_path(char *out, size_t n) {
    snprintf(out, n, "%s/facerebuild.txt", g_save_dir);
}

/* kernel32.c: the engine opened a save for reading. */
void face_rebuild_save_opened(void) {
    if (g_state < 0) {
        char path[1100];
        switch_path(path, sizeof path);
        g_state = access(path, F_OK) == 0 ? 1 : 0;
        if (g_state) LOG("facerebuild: a save is loading; the race menu will open once, confirm it unchanged");
    }
    if (g_state == 1 && !g_wait) {
        g_wait = FIRST_TRY_FRAMES;
        g_seen = tex_watch_creates();
    }
}

/* platform_sdl.c: called on every presented frame. */
void face_rebuild_tick(void) {
    if (g_state != 1) return;
    if (!g_wait) return;
    if (--g_wait > 0) return;
    if (g_tries && tex_watch_creates() != g_seen) {
        /* The menu built faces after the last try: it is open, stop asking. */
        LOG("facerebuild: the race menu is open; not asking again");
        g_state = 0;
        return;
    }
    if (g_tries >= ATTEMPTS) {
        LOG("facerebuild: the race menu did not take; tap Repair character face and load the save again");
        g_state = 0;
        return;
    }
    g_tries++;
    g_wait = RETRY_FRAMES;
    char path[1100];
    switch_path(path, sizeof path);
    unlink(path);  /* the player asked once; do not repeat on the next load */
    LOG("facerebuild: asking the game for the race menu (try %d); confirm it without changing the race", g_tries);
    platform_script_text("ShowRaceMenu");
}
