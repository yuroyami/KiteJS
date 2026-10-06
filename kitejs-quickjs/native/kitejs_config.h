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

/* On WebAssembly, QuickJS's frames live on two stacks: a shadow stack in linear memory, which the
 * link fixes and QuickJS's check measures, and the host's own stack, which the check cannot see
 * and which is about 1 MiB in V8. The host's stack must outlast the check, or a deep recursion
 * kills the host instead of throwing a RangeError. KITEJS_WASM_STACK_LIMIT caps the limit to keep
 * it so. A recursive function that keeps its locals in registers uses no shadow stack, so the
 * check would never see it recurse: KITEJS_FRAME() makes it hold some, and KITEJS_PARSER_FRAME()
 * a little for each of the parser's functions, several of which make one level of nesting. A call
 * into the host runs the host's frames on the host's stack alone, so KITEJS_HOST_FRAME() holds
 * shadow stack for them.
 * The sizes come from the depth V8 reaches cold, before it optimises; the build reads the stack
 * size from here. */
#if defined(__wasm__)
#define KITEJS_WASM_STACK_SIZE (1 * 1024 * 1024)
#define KITEJS_WASM_STACK_LIMIT (256 * 1024)
#define KITEJS_WASM_FRAME_RESERVE 128
#define KITEJS_WASM_PARSER_FRAME_RESERVE 48
#define KITEJS_WASM_HOST_FRAME_RESERVE 4096
#define KITEJS_RESERVE(n) volatile unsigned char kitejs_frame[n]; \
    __asm__ volatile("" : : "r"(kitejs_frame) : "memory")
#define KITEJS_FRAME() KITEJS_RESERVE(KITEJS_WASM_FRAME_RESERVE)
#define KITEJS_PARSER_FRAME() KITEJS_RESERVE(KITEJS_WASM_PARSER_FRAME_RESERVE)
#define KITEJS_HOST_FRAME() KITEJS_RESERVE(KITEJS_WASM_HOST_FRAME_RESERVE)
#else
#define KITEJS_FRAME() do { } while (0)
#define KITEJS_PARSER_FRAME() do { } while (0)
#define KITEJS_HOST_FRAME() do { } while (0)
#endif

#endif
