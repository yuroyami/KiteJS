/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

/*
 * Fails each allocation that kite_new makes, one at a time, and checks that kite_new then answers
 * NULL or a usable engine, and that kite_free frees either without a bad read. run.sh builds
 * kitejs_quickjs.c with its malloc, calloc and realloc routed here, under AddressSanitizer.
 */

#include <stdio.h>
#include <stdlib.h>

#include "../kitejs_quickjs.h"

/* The allocation that fails next, counted from 0, or -1 for none. */
static long fail_at = -1;
static long count;

static int fails(void)
{
    return fail_at >= 0 && count++ == fail_at;
}

void *test_malloc(size_t size) { return fails() ? NULL : malloc(size); }
void *test_calloc(size_t n, size_t size) { return fails() ? NULL : calloc(n, size); }
void *test_realloc(void *ptr, size_t size) { return fails() ? NULL : realloc(ptr, size); }

static int32_t host_call(int32_t engine, int32_t fn, int32_t argc) { return -1; }
static int32_t host_interrupt(int32_t engine) { return 0; }
static double host_now(int32_t engine) { return 0; }
static int32_t host_tz_offset(int32_t engine, double time) { return 0; }

int main(void)
{
    kite_set_host(host_call, host_interrupt, host_now, host_tz_offset);

    /* A run with no failure counts the allocations of kite_new. */
    fail_at = 1L << 40;
    count = 0;
    KiteEngine *first = kite_new(1, 0, 0, 0);
    long total = count;
    fail_at = -1;
    if (!first) {
        fprintf(stderr, "kite_new failed with no allocation failing\n");
        return 1;
    }
    kite_free(first);

    int failed = 0;
    for (long i = 0; i < total; i++) {
        fail_at = i;
        count = 0;
        KiteEngine *e = kite_new(1, 0, 0, 0);
        fail_at = -1;
        if (e)
            kite_free(e);
        else
            failed++;
    }
    printf("%ld allocations, %d of the failures made kite_new answer NULL\n", total, failed);
    if (failed == 0) {
        fprintf(stderr, "no failed allocation reached the failure path of kite_new\n");
        return 1;
    }
    return 0;
}
