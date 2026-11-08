/* INI parsing used by the kernel32 profile shims. Kept free of the guest CPU
 * so tests/native can compile it on the host. */
#define _GNU_SOURCE
#include "ini.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

static char *trim(char *s) {
    while (*s == ' ' || *s == '\t') s++;
    char *e = s + strlen(s);
    while (e > s && (e[-1] == ' ' || e[-1] == '\t' || e[-1] == '\r' || e[-1] == '\n')) *--e = 0;
    return s;
}

void ini_free(IniFile *f) {
    for (int i = 0; i < f->n; i++) { free(f->e[i].sec); free(f->e[i].key); free(f->e[i].val); }
    free(f->e);
    f->e = NULL;
    f->n = f->cap = 0;
}

void ini_add(IniFile *f, const char *sec, const char *key, const char *val) {
    if (f->n == f->cap) {
        f->cap = f->cap ? f->cap * 2 : 64;
        f->e = realloc(f->e, sizeof(IniEnt) * f->cap);
    }
    f->e[f->n++] = (IniEnt){strdup(sec), key ? strdup(key) : NULL, val ? strdup(val) : NULL};
}

/* Section header entries have key == NULL, so empty sections are kept. */
int ini_parse(IniFile *f) {
    ini_free(f);
    FILE *fp = fopen(f->path, "rb");
    if (!fp) return 0;
    char line[4096], sec[256] = "";
    while (fgets(line, sizeof line, fp)) {
        char *s = trim(line);
        if (!*s || *s == ';' || *s == '#') continue;
        if (*s == '[') {
            char *e = strchr(s, ']');
            if (e) *e = 0;
            snprintf(sec, sizeof sec, "%s", trim(s + 1));
            ini_add(f, sec, NULL, NULL);
            continue;
        }
        char *eq = strchr(s, '=');
        if (!eq) continue;
        *eq = 0;
        ini_add(f, sec, trim(s), trim(eq + 1));
    }
    fclose(fp);
    return 1;
}

const char *ini_find(IniFile *f, const char *sec, const char *key) {
    for (int i = 0; i < f->n; i++)
        if (f->e[i].key && !strcasecmp(f->e[i].sec, sec) && !strcasecmp(f->e[i].key, key)) return f->e[i].val;
    return NULL;
}
