/* Development only: types the console command in <save>/console.txt, live.
 *
 * The file is checked about once a second while the game runs; whatever it holds
 * is typed into the game's console (open, command, enter, close) and the file is
 * removed, so a new command is just another write from the host:
 *
 *   adb shell 'printf "player.setrace NordRaceVampire" > .../port/save/console.txt'
 *
 * Used to reproduce engine bugs on the device without a keyboard. It is not a
 * player feature and does nothing while the file is absent.
 */
#include "rt.h"
#include <unistd.h>

void platform_script_text(const char *s);

static void switch_path(char *out, size_t n) {
    snprintf(out, n, "%s/console.txt", g_save_dir);
}

static int read_command(char *cmd, size_t n) {
    char path[1100];
    switch_path(path, sizeof path);
    if (access(path, F_OK)) return 0;
    FILE *f = fopen(path, "r");
    if (!f) return 0;
    if (!fgets(cmd, (int)n, f)) cmd[0] = 0;
    fclose(f);
    for (char *p = cmd; *p; p++)
        if (*p == '\r' || *p == '\n') *p = 0;
    unlink(path);
    return cmd[0] ? 1 : 0;
}

/* platform_sdl.c: every presented frame. */
void console_cmd_tick(void) {
    static int n;
    char cmd[256];
    if (++n % 120) return;  /* every four seconds at 30 fps: the folder is on an SD card */
    if (!read_command(cmd, sizeof cmd)) return;
    LOG("consolecmd: typing %s", cmd);
    platform_script_text(cmd);
}
