/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

/*
 * The small C surface KiteJS drives QuickJS through. Kotlin never sees a JSValue: every value it
 * holds is an int handle into a table the engine keeps, so the same calls work from JNI, from
 * Kotlin/Native and from WebAssembly. Strings cross as UTF-16, the form Kotlin keeps them in.
 *
 * Anything the C side needs from the host goes through four callbacks: calling a host function,
 * asking whether to stop, the clock and the time zone. Native and JNI builds set them once with
 * kite_set_host; the WebAssembly build imports them from the "kitejs" module.
 */

#ifndef KITEJS_QUICKJS_H
#define KITEJS_QUICKJS_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct KiteEngine KiteEngine;

/* The handles every engine has from the start, which are never freed. */
#define KITE_UNDEFINED 0
#define KITE_NULL 1
#define KITE_TRUE 2
#define KITE_FALSE 3

/* What kite_type answers. */
#define KITE_TYPE_UNDEFINED 0
#define KITE_TYPE_NULL 1
#define KITE_TYPE_BOOLEAN 2
#define KITE_TYPE_NUMBER 3
#define KITE_TYPE_BIGINT 4
#define KITE_TYPE_STRING 5
#define KITE_TYPE_SYMBOL 6
#define KITE_TYPE_ARRAY 7
#define KITE_TYPE_FUNCTION 8
#define KITE_TYPE_OBJECT 9

/* Why the engine stopped a script, as kite_stop_reason answers. */
#define KITE_STOP_NONE 0
#define KITE_STOP_BUDGET 1
#define KITE_STOP_HOOK 2
#define KITE_STOP_HOOK_THREW 3
#define KITE_STOP_MEMORY 4

/* Options for kite_new. */
#define KITE_OPT_CLOCK 1      /* Date.now() asks the host */
#define KITE_OPT_TIME_ZONE 2  /* local time asks the host */
#define KITE_OPT_INTERRUPT 4  /* the host is asked now and then whether to stop */

/*
 * Calls host function [fn] with [argc] arguments, read with kite_cb_this and kite_cb_arg before
 * anything else runs. Answers a handle the engine takes over, or -1 when the host threw: then
 * the script stops with an error no catch sees.
 */
typedef int32_t (*kite_host_call_fn)(int32_t engine, int32_t fn, int32_t argc);
/* 0 to go on, 1 to stop, 2 when the host threw while deciding. */
typedef int32_t (*kite_host_interrupt_fn)(int32_t engine);
/* Milliseconds since the epoch. */
typedef double (*kite_host_now_fn)(int32_t engine);
/* Minutes local time is behind UTC at [time] ms since the epoch. */
typedef int32_t (*kite_host_tz_offset_fn)(int32_t engine, double time);

#if !defined(__wasm__)
void kite_set_host(kite_host_call_fn call, kite_host_interrupt_fn interrupt,
                   kite_host_now_fn now, kite_host_tz_offset_fn tz_offset);
#endif

/* ---- Engines --------------------------------------------------------------------------------- */

/* A new engine, or null when QuickJS could not start. [id] is what the callbacks are handed. */
KiteEngine *kite_new(int32_t id, double memory_limit, double stack_size, int32_t options);
void kite_free(KiteEngine *e);
/* Call before the outermost call into an engine, so stack checks start from where the host is. */
void kite_enter(KiteEngine *e);
/* How much budget the next call has; zero or less means no limit. */
void kite_set_budget(KiteEngine *e, double budget);
/* "QuickJS-ng 0.17.0" and so on. */
const char *kite_version(void);
/* Whether the interrupt callback is asked; KITE_OPT_INTERRUPT turns it on from the start. */
void kite_ask_host(KiteEngine *e, int32_t ask);
int32_t kite_stop_reason(KiteEngine *e);

/* ---- Values ---------------------------------------------------------------------------------- */

int32_t kite_type(KiteEngine *e, int32_t h);
double kite_number(KiteEngine *e, int32_t h);
/* The string a value converts to, as UTF-16; its length is kite_string_length. Free it with
 * kite_string_free before asking for another. Null when the conversion threw. */
const uint16_t *kite_string(KiteEngine *e, int32_t h);
int32_t kite_string_length(KiteEngine *e);
void kite_string_free(KiteEngine *e);
/* The object's address, which is its identity while the handle lives. */
double kite_identity(KiteEngine *e, int32_t h);
int32_t kite_is_promise(KiteEngine *e, int32_t h);
int32_t kite_dup(KiteEngine *e, int32_t h);
void kite_release(KiteEngine *e, int32_t h);

int32_t kite_new_number(KiteEngine *e, double d);
int32_t kite_new_string(KiteEngine *e, const uint16_t *chars, int32_t length);
int32_t kite_new_object(KiteEngine *e);
int32_t kite_new_array(KiteEngine *e);
/* Appends to an array made by kite_new_array, running nothing. */
void kite_array_push(KiteEngine *e, int32_t array, int32_t value);
/* Sets a plain data property on an object made by kite_new_object, running nothing. */
void kite_object_put(KiteEngine *e, int32_t obj, const uint16_t *key, int32_t key_length, int32_t value);
/* An element of an array the engine made itself, such as Object.keys answers. */
int32_t kite_array_length(KiteEngine *e, int32_t array);
int32_t kite_array_get(KiteEngine *e, int32_t array, int32_t index);
int32_t kite_global(KiteEngine *e);

/* ---- Running --------------------------------------------------------------------------------- */

/* These answer a handle, or -1 when something was thrown; kite_exception then hands it over. */

/* Compiles global code without running it. */
int32_t kite_compile(KiteEngine *e, const uint16_t *source, int32_t length,
                     const uint16_t *file, int32_t file_length);
/* Runs what kite_compile made; it can run again. */
int32_t kite_run(KiteEngine *e, int32_t compiled);
/* Arguments for the next kite_call or kite_construct, pushed in order. */
void kite_push_arg(KiteEngine *e, int32_t h);
int32_t kite_call(KiteEngine *e, int32_t fn, int32_t self);
int32_t kite_construct(KiteEngine *e, int32_t fn);
/* Runs pending jobs until there are none (0) or one fails (-1). */
int32_t kite_drain(KiteEngine *e);
void kite_discard_jobs(KiteEngine *e);
int32_t kite_exception(KiteEngine *e);

/* A host function: calling it calls the host with [fn]. */
int32_t kite_new_function(KiteEngine *e, int32_t fn, const uint16_t *name, int32_t name_length, int32_t arity);
/* A host function QuickJS has collected, so the host can forget it, or -1 when there is none. */
int32_t kite_dead_function(KiteEngine *e);
/* Inside a host call: `this` and the arguments, as fresh handles. */
int32_t kite_cb_this(KiteEngine *e);
int32_t kite_cb_arg(KiteEngine *e, int32_t index);

/* ---- Memory for the WebAssembly build -------------------------------------------------------- */

void *kite_alloc(int32_t size);
void kite_dealloc(void *ptr);

#ifdef __cplusplus
}
#endif

#endif
