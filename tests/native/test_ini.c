/* INI parsing used by GetPrivateProfileStringA/Int and the settings writes. */
#define _GNU_SOURCE
#include "ini.h"
#include "harness.h"
#include <unistd.h>

static char g_path[64];

static void write_ini(const char *text) {
    FILE *f = fopen(g_path, "wb");
    if (!f) {
        perror(g_path);
        exit(2);
    }
    fputs(text, f);
    fclose(f);
}

TEST(parses_sections_keys_and_values) {
    write_ini("[Display]\r\n"
              "iSize W = 1920\r\n"
              "iSize H=1080\r\n"
              "key=a=b\r\n"
              "Empty=\r\n"
              "\r\n"
              "[Grass]\r\n"
              "bAllowCreateGrass=1\r\n"
              "[Empty]\r\n");

    IniFile f = {0};
    snprintf(f.path, sizeof f.path, "%s", g_path);
    CHECK_INT(ini_parse(&f), 1);

    CHECK_STR(ini_find(&f, "display", "isize w"), "1920");
    CHECK_STR(ini_find(&f, "Display", "iSize H"), "1080");
    CHECK_STR(ini_find(&f, "Display", "key"), "a=b");
    CHECK_STR(ini_find(&f, "Display", "Empty"), "");
    CHECK_STR(ini_find(&f, "Grass", "bAllowCreateGrass"), "1");
    CHECK(ini_find(&f, "Display", "missing") == NULL);
    CHECK(ini_find(&f, "Missing", "iSize W") == NULL);

    int empty_section = 0;
    for (int i = 0; i < f.n; i++) {
        if (!f.e[i].key && !strcmp(f.e[i].sec, "Empty")) empty_section = 1;
    }
    CHECK_INT(empty_section, 1);

    ini_free(&f);
    CHECK_INT(f.n, 0);
    CHECK(f.e == NULL);
}

TEST(comments_and_blank_lines_are_ignored) {
    write_ini("; semicolon comment\r\n"
              "# hash comment\r\n"
              "\r\n"
              "[Display] trailing text\r\n"
              "iSize W=1\r\n");

    IniFile f = {0};
    snprintf(f.path, sizeof f.path, "%s", g_path);
    ini_parse(&f);

    CHECK_STR(ini_find(&f, "Display", "iSize W"), "1");
    CHECK(ini_find(&f, "; semicolon comment", "x") == NULL);

    ini_free(&f);
}

TEST(parsing_twice_replaces_the_previous_document) {
    IniFile f = {0};
    snprintf(f.path, sizeof f.path, "%s", g_path);

    write_ini("[A]\r\nk=1\r\n");
    ini_parse(&f);
    CHECK_INT(f.n, 2);
    CHECK_STR(ini_find(&f, "A", "k"), "1");

    write_ini("[B]\r\nk=2\r\n");
    ini_parse(&f);
    CHECK_INT(f.n, 2);
    CHECK(ini_find(&f, "A", "k") == NULL);
    CHECK_STR(ini_find(&f, "B", "k"), "2");

    ini_free(&f);
}

TEST(missing_file_is_an_empty_document) {
    IniFile f = {0};
    snprintf(f.path, sizeof f.path, "%s", g_path);
    remove(g_path);

    CHECK_INT(ini_parse(&f), 0);
    CHECK_INT(f.n, 0);
    CHECK(ini_find(&f, "A", "k") == NULL);

    ini_free(&f);
}

TEST(keys_before_a_section_are_empty_section_entries) {
    IniFile f = {0};
    snprintf(f.path, sizeof f.path, "%s", g_path);
    write_ini("k=1\r\n[S]\r\nk=2\r\n");
    ini_parse(&f);

    CHECK_STR(ini_find(&f, "", "k"), "1");
    CHECK_STR(ini_find(&f, "S", "k"), "2");

    ini_free(&f);
}

int main(void) {
    snprintf(g_path, sizeof g_path, "/tmp/skyrim-ini-%ld.ini", (long)getpid());
    RUN(parses_sections_keys_and_values);
    RUN(comments_and_blank_lines_are_ignored);
    RUN(parsing_twice_replaces_the_previous_document);
    RUN(missing_file_is_an_empty_document);
    RUN(keys_before_a_section_are_empty_section_entries);
    remove(g_path);
    return th_report("ini");
}
