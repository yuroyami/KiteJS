/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

/*
 * The build settings every toolchain compiles QuickJS with, passed as `-include kitejs_config.h`
 * rather than as -D flags, since Kotlin/Native's run_konan drops those, and so the native, JNI,
 * Android and WebAssembly builds cannot drift apart.
 */

#ifndef KITEJS_CONFIG_H
#define KITEJS_CONFIG_H

#ifndef NDEBUG
#define NDEBUG 1
#endif

#ifndef _GNU_SOURCE
#define _GNU_SOURCE 1
#endif

/* How often QuickJS stops to poll the interrupt handler, in its own checks. Its default of 10000
 * makes a budget coarse and a stop slow to arrive; 256 is cheap and fine-grained enough. */
#define JS_INTERRUPT_COUNTER_INIT 256

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN 1
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0601
#endif
#endif

#endif
