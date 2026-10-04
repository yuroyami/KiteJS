/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

#include <stdlib.h>
#include <string.h>

#include "kitejs_quickjs.h"
#include "quickjs/quickjs.h"

/* How many interrupt checks the budget counts one poll of the handler as. Kept in step with
 * JS_INTERRUPT_COUNTER_INIT, which the build sets. */
#ifndef JS_INTERRUPT_COUNTER_INIT
#define JS_INTERRUPT_COUNTER_INIT 10000
#endif

struct KiteEngine {
    int32_t id;
    JSRuntime *rt;
    JSContext *ctx;

    /* The handle table: a value per slot, and a free list threaded through `next`. */
    JSValue *slots;
    int32_t *next;
    int32_t capacity;
    int32_t free_head;

    /* Arguments pushed for the next call. */
    JSValue *args;
    int32_t argc;
    int32_t args_capacity;

    /* The host call in progress, for kite_cb_this and kite_cb_arg. */
    JSValueConst cb_this;
    JSValueConst *cb_argv;
    int32_t cb_argc;

    /* The last string handed out. */
    const uint16_t *string;
    int32_t string_length;

    /* Host functions QuickJS has collected, for the host to forget. */
    JSClassID host_class;
    int32_t *dead;
    int32_t dead_count;
    int32_t dead_capacity;

    double budget;      /* what is left, when has_budget */
    int has_budget;
    int ask_host;
    int32_t stop_reason;
    /* The runtime's out-of-memory count when the outermost call began. */
    uint32_t out_of_memory;
};

#if defined(__wasm__)
__attribute__((import_module("kitejs"), import_name("call")))
int32_t kite_host_call(int32_t engine, int32_t fn, int32_t argc);
__attribute__((import_module("kitejs"), import_name("interrupt")))
int32_t kite_host_interrupt(int32_t engine);
__attribute__((import_module("kitejs"), import_name("now")))
double kite_host_now(int32_t engine);
__attribute__((import_module("kitejs"), import_name("tz_offset")))
int32_t kite_host_tz_offset(int32_t engine, double time);
#else
static kite_host_call_fn kite_host_call;
static kite_host_interrupt_fn kite_host_interrupt;
static kite_host_now_fn kite_host_now;
static kite_host_tz_offset_fn kite_host_tz_offset;

void kite_set_host(kite_host_call_fn call, kite_host_interrupt_fn interrupt,
                   kite_host_now_fn now, kite_host_tz_offset_fn tz_offset)
{
    kite_host_call = call;
    kite_host_interrupt = interrupt;
    kite_host_now = now;
    kite_host_tz_offset = tz_offset;
}
#endif

static KiteEngine *engine_of(JSContext *ctx)
{
    return (KiteEngine *)JS_GetContextOpaque(ctx);
}

/* ---- The handle table ---------------------------------------------------------------------- */

#define FIXED_HANDLES 4

static int grow(KiteEngine *e)
{
    int32_t capacity = e->capacity * 2;
    JSValue *slots = realloc(e->slots, sizeof(JSValue) * capacity);
    if (!slots)
        return -1;
    e->slots = slots;
    int32_t *next = realloc(e->next, sizeof(int32_t) * capacity);
    if (!next)
        return -1;
    e->next = next;
    for (int32_t i = capacity - 1; i >= e->capacity; i--) {
        e->slots[i] = JS_UNDEFINED;
        e->next[i] = e->free_head;
        e->free_head = i;
    }
    e->capacity = capacity;
    return 0;
}

/* Takes [v] over and answers its handle, or -1 when there is no memory left for one. */
static int32_t keep(KiteEngine *e, JSValue v)
{
    if (e->free_head < 0 && grow(e) < 0) {
        JS_FreeValue(e->ctx, v);
        return -1;
    }
    int32_t h = e->free_head;
    e->free_head = e->next[h];
    e->next[h] = -2;
    e->slots[h] = v;
    return h;
}

static JSValueConst at(KiteEngine *e, int32_t h)
{
    return e->slots[h];
}

void kite_release(KiteEngine *e, int32_t h)
{
    if (h < FIXED_HANDLES || h >= e->capacity || e->next[h] != -2)
        return;
    JS_FreeValue(e->ctx, e->slots[h]);
    e->slots[h] = JS_UNDEFINED;
    e->next[h] = e->free_head;
    e->free_head = h;
}

int32_t kite_dup(KiteEngine *e, int32_t h)
{
    return keep(e, JS_DupValue(e->ctx, at(e, h)));
}

/* ---- Callbacks from QuickJS ---------------------------------------------------------------- */

/* Out of memory ends the call even when a script caught it, as it can: it may be caught as null. */
static int ran_out_of_memory(KiteEngine *e)
{
    if (e->stop_reason == KITE_STOP_NONE && JS_KiteOutOfMemoryCount(e->rt) != e->out_of_memory)
        e->stop_reason = KITE_STOP_MEMORY;
    return e->stop_reason == KITE_STOP_MEMORY;
}

static int interrupt_handler(JSRuntime *rt, void *opaque)
{
    KiteEngine *e = opaque;
    if (ran_out_of_memory(e) || e->stop_reason != KITE_STOP_NONE)
        return 1;
    if (e->has_budget) {
        e->budget -= JS_INTERRUPT_COUNTER_INIT;
        if (e->budget <= 0) {
            e->stop_reason = KITE_STOP_BUDGET;
            return 1;
        }
    }
    if (e->ask_host) {
        int32_t answer = kite_host_interrupt(e->id);
        if (answer != 0) {
            e->stop_reason = answer == 1 ? KITE_STOP_HOOK : KITE_STOP_HOOK_THREW;
            return 1;
        }
    }
    return 0;
}

static int64_t now_hook(JSContext *ctx)
{
    return (int64_t)kite_host_now(engine_of(ctx)->id);
}

static int tz_offset_hook(JSContext *ctx, int64_t time)
{
    return kite_host_tz_offset(engine_of(ctx)->id, (double)time);
}

/* Throws an error no catch in the script sees, for a host that threw. */
static JSValue throw_uncatchable(JSContext *ctx, const char *message)
{
    JSValue error = JS_NewInternalError(ctx, "%s", message);
    JS_SetUncatchableError(ctx, error);
    return JS_Throw(ctx, error);
}

/* Each host function carries one of these, so the host hears when it is collected. */
static void host_token_finalizer(JSRuntime *rt, JSValueConst val)
{
    KiteEngine *e = JS_GetRuntimeOpaque(rt);
    int32_t fn = (int32_t)(intptr_t)JS_GetOpaque(val, e->host_class) - 1;
    if (fn < 0)
        return;
    if (e->dead_count == e->dead_capacity) {
        int32_t capacity = e->dead_capacity ? e->dead_capacity * 2 : 16;
        int32_t *dead = realloc(e->dead, sizeof(int32_t) * capacity);
        if (!dead)
            return;
        e->dead = dead;
        e->dead_capacity = capacity;
    }
    e->dead[e->dead_count++] = fn;
}

static JSValue host_function(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv,
                             int magic, JSValueConst *data)
{
    KiteEngine *e = engine_of(ctx);
    JSValueConst saved_this = e->cb_this;
    JSValueConst *saved_argv = e->cb_argv;
    int32_t saved_argc = e->cb_argc;
    e->cb_this = this_val;
    e->cb_argv = argv;
    e->cb_argc = argc;
    int32_t fn = (int32_t)(intptr_t)JS_GetOpaque(data[0], e->host_class) - 1;
    int32_t result = kite_host_call(e->id, fn, argc);
    e->cb_this = saved_this;
    e->cb_argv = saved_argv;
    e->cb_argc = saved_argc;
    if (result < 0)
        return throw_uncatchable(ctx, "the host threw");
    JSValue v = JS_DupValue(ctx, at(e, result));
    kite_release(e, result);
    return v;
}

/* ---- Engines ------------------------------------------------------------------------------- */

KiteEngine *kite_new(int32_t id, double memory_limit, double stack_size, int32_t options)
{
    KiteEngine *e = calloc(1, sizeof(KiteEngine));
    if (!e)
        return NULL;
    e->id = id;
    e->rt = JS_NewRuntime();
    if (!e->rt)
        goto fail;
    if (memory_limit > 0)
        JS_SetMemoryLimit(e->rt, (size_t)memory_limit);
    if (stack_size > 0)
        JS_SetMaxStackSize(e->rt, (size_t)stack_size);
    e->ctx = JS_NewContext(e->rt);
    if (!e->ctx)
        goto fail;
    JS_SetContextOpaque(e->ctx, e);
    JS_SetRuntimeOpaque(e->rt, e);
    JSClassDef host_token = { .class_name = "HostFunction", .finalizer = host_token_finalizer };
    JS_NewClassID(e->rt, &e->host_class);
    if (JS_NewClass(e->rt, e->host_class, &host_token) < 0)
        goto fail;
    JS_SetInterruptHandler(e->rt, interrupt_handler, e);
    JS_KiteSetDateHooks(e->rt, (options & KITE_OPT_CLOCK) ? now_hook : NULL,
                        (options & KITE_OPT_TIME_ZONE) ? tz_offset_hook : NULL);
    e->ask_host = (options & KITE_OPT_INTERRUPT) != 0;

    e->capacity = 64;
    e->slots = malloc(sizeof(JSValue) * e->capacity);
    e->next = malloc(sizeof(int32_t) * e->capacity);
    if (!e->slots || !e->next)
        goto fail;
    e->free_head = -1;
    for (int32_t i = e->capacity - 1; i >= FIXED_HANDLES; i--) {
        e->slots[i] = JS_UNDEFINED;
        e->next[i] = e->free_head;
        e->free_head = i;
    }
    e->slots[KITE_UNDEFINED] = JS_UNDEFINED;
    e->slots[KITE_NULL] = JS_NULL;
    e->slots[KITE_TRUE] = JS_TRUE;
    e->slots[KITE_FALSE] = JS_FALSE;
    for (int32_t i = 0; i < FIXED_HANDLES; i++)
        e->next[i] = -3;
    return e;

fail:
    kite_free(e);
    return NULL;
}

void kite_free(KiteEngine *e)
{
    if (!e)
        return;
    kite_string_free(e);
    if (e->ctx) {
        for (int32_t i = 0; i < e->argc; i++)
            JS_FreeValue(e->ctx, e->args[i]);
        for (int32_t i = FIXED_HANDLES; i < e->capacity; i++) {
            if (e->next[i] == -2)
                JS_FreeValue(e->ctx, e->slots[i]);
        }
        JS_KiteDiscardPendingJobs(e->rt);
        JS_FreeContext(e->ctx);
    }
    if (e->rt)
        JS_FreeRuntime(e->rt);
    free(e->args);
    free(e->dead);
    free(e->slots);
    free(e->next);
    free(e);
}

void kite_enter(KiteEngine *e)
{
    JS_UpdateStackTop(e->rt);
    e->stop_reason = KITE_STOP_NONE;
    e->out_of_memory = JS_KiteOutOfMemoryCount(e->rt);
}

void kite_set_budget(KiteEngine *e, double budget)
{
    e->has_budget = budget > 0;
    e->budget = budget;
}

void kite_ask_host(KiteEngine *e, int32_t ask)
{
    e->ask_host = ask != 0;
}

int32_t kite_stop_reason(KiteEngine *e)
{
    ran_out_of_memory(e);
    return e->stop_reason;
}

/* ---- Values -------------------------------------------------------------------------------- */

int32_t kite_type(KiteEngine *e, int32_t h)
{
    JSValueConst v = at(e, h);
    switch (JS_VALUE_GET_NORM_TAG(v)) {
    case JS_TAG_UNDEFINED:
        return KITE_TYPE_UNDEFINED;
    case JS_TAG_NULL:
        return KITE_TYPE_NULL;
    case JS_TAG_BOOL:
        return KITE_TYPE_BOOLEAN;
    case JS_TAG_INT:
    case JS_TAG_FLOAT64:
        return KITE_TYPE_NUMBER;
    case JS_TAG_SHORT_BIG_INT:
    case JS_TAG_BIG_INT:
        return KITE_TYPE_BIGINT;
    case JS_TAG_STRING:
        return KITE_TYPE_STRING;
    case JS_TAG_SYMBOL:
        return KITE_TYPE_SYMBOL;
    case JS_TAG_OBJECT:
        if (JS_IsArray(v))
            return KITE_TYPE_ARRAY;
        if (JS_IsFunction(e->ctx, v))
            return KITE_TYPE_FUNCTION;
        return KITE_TYPE_OBJECT;
    default:
        /* What a script cannot see, such as the bytecode kite_compile makes. */
        return KITE_TYPE_OBJECT;
    }
}

double kite_number(KiteEngine *e, int32_t h)
{
    JSValueConst v = at(e, h);
    if (JS_VALUE_GET_TAG(v) == JS_TAG_BOOL)
        return JS_VALUE_GET_BOOL(v) ? 1 : 0;
    double d = 0;
    JS_ToFloat64(e->ctx, &d, v);
    return d;
}

const uint16_t *kite_string(KiteEngine *e, int32_t h)
{
    kite_string_free(e);
    size_t length = 0;
    e->string = JS_ToCStringLenUTF16(e->ctx, &length, at(e, h));
    e->string_length = e->string ? (int32_t)length : 0;
    return e->string;
}

int32_t kite_string_length(KiteEngine *e)
{
    return e->string_length;
}

void kite_string_free(KiteEngine *e)
{
    if (e->string) {
        JS_FreeCStringUTF16(e->ctx, e->string);
        e->string = NULL;
        e->string_length = 0;
    }
}

double kite_identity(KiteEngine *e, int32_t h)
{
    JSValueConst v = at(e, h);
    if (!JS_VALUE_HAS_REF_COUNT(v))
        return 0;
    return (double)(uintptr_t)JS_VALUE_GET_PTR(v);
}

int32_t kite_is_promise(KiteEngine *e, int32_t h)
{
    return JS_IsPromise(at(e, h));
}

int32_t kite_new_number(KiteEngine *e, double d)
{
    return keep(e, JS_NewFloat64(e->ctx, d));
}

int32_t kite_new_string(KiteEngine *e, const uint16_t *chars, int32_t length)
{
    JSValue s = JS_NewStringUTF16(e->ctx, chars, length);
    if (JS_IsException(s))
        return -1;
    return keep(e, s);
}

int32_t kite_new_object(KiteEngine *e)
{
    return keep(e, JS_NewObject(e->ctx));
}

int32_t kite_new_array(KiteEngine *e)
{
    return keep(e, JS_NewArray(e->ctx));
}

void kite_array_push(KiteEngine *e, int32_t array, int32_t value)
{
    int64_t length = 0;
    JS_GetLength(e->ctx, at(e, array), &length);
    JS_SetPropertyInt64(e->ctx, at(e, array), length, JS_DupValue(e->ctx, at(e, value)));
}

void kite_object_put(KiteEngine *e, int32_t obj, const uint16_t *key, int32_t key_length, int32_t value)
{
    JSValue name = JS_NewStringUTF16(e->ctx, key, key_length);
    JSAtom atom = JS_ValueToAtom(e->ctx, name);
    JS_FreeValue(e->ctx, name);
    JS_DefinePropertyValue(e->ctx, at(e, obj), atom, JS_DupValue(e->ctx, at(e, value)), JS_PROP_C_W_E);
    JS_FreeAtom(e->ctx, atom);
}

int32_t kite_array_length(KiteEngine *e, int32_t array)
{
    int64_t length = 0;
    JS_GetLength(e->ctx, at(e, array), &length);
    return (int32_t)length;
}

int32_t kite_array_get(KiteEngine *e, int32_t array, int32_t index)
{
    return keep(e, JS_GetPropertyUint32(e->ctx, at(e, array), (uint32_t)index));
}

int32_t kite_global(KiteEngine *e)
{
    return keep(e, JS_GetGlobalObject(e->ctx));
}

/* ---- Running ------------------------------------------------------------------------------- */

static int32_t answer(KiteEngine *e, JSValue v)
{
    if (JS_IsException(v))
        return -1;
    return keep(e, v);
}

int32_t kite_compile(KiteEngine *e, const uint16_t *source, int32_t length,
                     const uint16_t *file, int32_t file_length)
{
    JSContext *ctx = e->ctx;
    JSValue text = JS_NewStringUTF16(ctx, source, length);
    if (JS_IsException(text))
        return -1;
    JSValue name = JS_NewStringUTF16(ctx, file, file_length);
    if (JS_IsException(name)) {
        JS_FreeValue(ctx, text);
        return -1;
    }
    size_t utf8_length;
    const char *utf8 = JS_ToCStringLen2(ctx, &utf8_length, text, true);
    const char *file_utf8 = JS_ToCString(ctx, name);
    JS_FreeValue(ctx, text);
    JS_FreeValue(ctx, name);
    if (!utf8 || !file_utf8) {
        JS_FreeCString(ctx, utf8);
        JS_FreeCString(ctx, file_utf8);
        return -1;
    }
    JSValue compiled = JS_Eval(ctx, utf8, utf8_length, file_utf8,
                               JS_EVAL_TYPE_GLOBAL | JS_EVAL_FLAG_COMPILE_ONLY);
    JS_FreeCString(ctx, utf8);
    JS_FreeCString(ctx, file_utf8);
    return answer(e, compiled);
}

int32_t kite_run(KiteEngine *e, int32_t compiled)
{
    return answer(e, JS_EvalFunction(e->ctx, JS_DupValue(e->ctx, at(e, compiled))));
}

void kite_push_arg(KiteEngine *e, int32_t h)
{
    if (e->argc == e->args_capacity) {
        int32_t capacity = e->args_capacity ? e->args_capacity * 2 : 8;
        JSValue *args = realloc(e->args, sizeof(JSValue) * capacity);
        if (!args)
            return;
        e->args = args;
        e->args_capacity = capacity;
    }
    e->args[e->argc++] = JS_DupValue(e->ctx, at(e, h));
}

/* Takes the pushed arguments, leaving the list empty for a call they might make. */
static int32_t take_args(KiteEngine *e, JSValue **argv)
{
    int32_t argc = e->argc;
    *argv = NULL;
    if (argc > 0) {
        *argv = malloc(sizeof(JSValue) * argc);
        if (!*argv)
            return -1;
        memcpy(*argv, e->args, sizeof(JSValue) * argc);
    }
    e->argc = 0;
    return argc;
}

static void free_args(KiteEngine *e, JSValue *argv, int32_t argc)
{
    for (int32_t i = 0; i < argc; i++)
        JS_FreeValue(e->ctx, argv[i]);
    free(argv);
}

int32_t kite_call(KiteEngine *e, int32_t fn, int32_t self)
{
    JSValue *argv;
    int32_t argc = take_args(e, &argv);
    if (argc < 0) {
        JS_ThrowOutOfMemory(e->ctx);
        return -1;
    }
    JSValue result = JS_Call(e->ctx, at(e, fn), at(e, self), argc, (JSValueConst *)argv);
    free_args(e, argv, argc);
    return answer(e, result);
}

int32_t kite_construct(KiteEngine *e, int32_t fn)
{
    JSValue *argv;
    int32_t argc = take_args(e, &argv);
    if (argc < 0) {
        JS_ThrowOutOfMemory(e->ctx);
        return -1;
    }
    JSValue result = JS_CallConstructor(e->ctx, at(e, fn), argc, (JSValueConst *)argv);
    free_args(e, argv, argc);
    return answer(e, result);
}

int32_t kite_drain(KiteEngine *e)
{
    JSContext *ctx;
    for (;;) {
        int r = JS_ExecutePendingJob(e->rt, &ctx);
        if (r == 0)
            return 0;
        if (r < 0)
            return -1;
    }
}

void kite_discard_jobs(KiteEngine *e)
{
    JS_KiteDiscardPendingJobs(e->rt);
}

int32_t kite_exception(KiteEngine *e)
{
    JSValue error = JS_GetException(e->ctx);
    /* The host has it now; a script that sees it again may catch it. */
    JS_ClearUncatchableError(e->ctx, error);
    return keep(e, error);
}

int32_t kite_new_function(KiteEngine *e, int32_t fn, const uint16_t *name, int32_t name_length, int32_t arity)
{
    JSContext *ctx = e->ctx;
    JSValue text = JS_NewStringUTF16(ctx, name, name_length);
    if (JS_IsException(text))
        return -1;
    const char *utf8 = JS_ToCString(ctx, text);
    JS_FreeValue(ctx, text);
    if (!utf8)
        return -1;
    JSValue token = JS_NewObjectClass(ctx, e->host_class);
    if (JS_IsException(token)) {
        JS_FreeCString(ctx, utf8);
        return -1;
    }
    JS_SetOpaque(token, (void *)(intptr_t)(fn + 1));
    JSValue f = JS_NewCFunctionData2(ctx, host_function, utf8, arity, 0, 1, (JSValueConst *)&token);
    JS_FreeValue(ctx, token);
    JS_FreeCString(ctx, utf8);
    return answer(e, f);
}

int32_t kite_dead_function(KiteEngine *e)
{
    if (e->dead_count == 0)
        return -1;
    return e->dead[--e->dead_count];
}

int32_t kite_cb_this(KiteEngine *e)
{
    return keep(e, JS_DupValue(e->ctx, e->cb_this));
}

int32_t kite_cb_arg(KiteEngine *e, int32_t index)
{
    if (index < 0 || index >= e->cb_argc)
        return KITE_UNDEFINED;
    return keep(e, JS_DupValue(e->ctx, e->cb_argv[index]));
}

/* ---- Memory -------------------------------------------------------------------------------- */

void *kite_alloc(int32_t size)
{
    return malloc(size > 0 ? (size_t)size : 1);
}

void kite_dealloc(void *ptr)
{
    free(ptr);
}

const char *kite_version(void)
{
    static char version[32];
    if (!version[0]) {
        const char *v = JS_GetVersion();
        strcpy(version, "QuickJS-ng ");
        strncat(version, v, sizeof(version) - strlen(version) - 1);
    }
    return version;
}
