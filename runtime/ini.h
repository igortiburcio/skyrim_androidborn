/* Parsed .ini files, shared by the kernel32 profile shims and tests/native. */
#pragma once
#include <sys/types.h>
#include <time.h>

typedef struct { char *sec, *key, *val; } IniEnt;

typedef struct IniFile {
    char path[1024];
    time_t mtime;
    off_t size;
    IniEnt *e;
    int n, cap;
    struct IniFile *next;
} IniFile;

void ini_free(IniFile *f);
void ini_add(IniFile *f, const char *sec, const char *key, const char *val);
/* Parses f->path into f, replacing what was there. Returns 1 when it opened. */
int ini_parse(IniFile *f);
/* Case-insensitive lookup; NULL when the key is not there. */
const char *ini_find(IniFile *f, const char *sec, const char *key);
