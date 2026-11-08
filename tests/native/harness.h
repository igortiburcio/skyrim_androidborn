/* Tiny dependency-free test harness shared by tests/native. */
#pragma once
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int th_checks;
static int th_failures;
static const char *th_test;

#define TEST(name) static void test_##name(void)
#define RUN(name) do { th_test = #name; test_##name(); } while (0)

#define FAILF(...) do {                                                     \
    th_failures++;                                                          \
    fprintf(stderr, "FAIL %s:%d [%s] ", __FILE__, __LINE__, th_test);       \
    fprintf(stderr, __VA_ARGS__);                                           \
    fputc('\n', stderr);                                                    \
} while (0)

#define CHECK(cond) do {                                                    \
    th_checks++;                                                            \
    if (!(cond)) FAILF("%s", #cond);                                        \
} while (0)

#define CHECK_INT(got, want) do {                                           \
    th_checks++;                                                            \
    long long g_ = (long long)(got), w_ = (long long)(want);                \
    if (g_ != w_) FAILF("%s: got %lld, want %lld", #got, g_, w_);           \
} while (0)

#define CHECK_NEAR(got, want, eps) do {                                     \
    th_checks++;                                                            \
    double g_ = (double)(got), w_ = (double)(want);                         \
    if (!(fabs(g_ - w_) <= (eps)))                                          \
        FAILF("%s: got %g, want %g", #got, g_, w_);                         \
} while (0)

#define CHECK_STR(got, want) do {                                           \
    th_checks++;                                                            \
    const char *g_ = (got);                                                 \
    const char *w_ = (want);                                                \
    if (!g_ || strcmp(g_, w_))                                              \
        FAILF("%s: got %s, want %s", #got, g_ ? g_ : "(null)", w_);         \
} while (0)

static int th_report(const char *suite) {
    if (th_failures) {
        fprintf(stderr, "%s: %d of %d checks failed\n", suite, th_failures, th_checks);
        return 1;
    }
    printf("%s: %d checks passed\n", suite, th_checks);
    return 0;
}
