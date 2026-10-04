/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

/*
 * The JNI face of kitejs_quickjs.h, for the JVM and Android: one native method per C function,
 * on io.github.yuroyami.kitejs.quickjs.bridge.JniNatives, and the four host callbacks routed to
 * the static methods of JniHost.
 */

#include <jni.h>
#include <stdlib.h>

#include "../kitejs_quickjs.h"

#define NATIVE(name) Java_io_github_yuroyami_kitejs_quickjs_bridge_JniNatives_##name
#define ENGINE(e) ((KiteEngine *)(intptr_t)(e))

static JavaVM *vm;
static jclass host;
static jmethodID host_call;
static jmethodID host_interrupt;
static jmethodID host_now;
static jmethodID host_tz_offset;

static JNIEnv *env_here(void)
{
    JNIEnv *env = NULL;
    (*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6);
    return env;
}

/* A Java exception here would unwind through C, so it is cleared and reported as a failure. */
static int failed(JNIEnv *env)
{
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return 1;
    }
    return 0;
}

static int32_t cb_call(int32_t engine, int32_t fn, int32_t argc)
{
    JNIEnv *env = env_here();
    jint result = (*env)->CallStaticIntMethod(env, host, host_call, engine, fn, argc);
    return failed(env) ? -1 : result;
}

static int32_t cb_interrupt(int32_t engine)
{
    JNIEnv *env = env_here();
    jint result = (*env)->CallStaticIntMethod(env, host, host_interrupt, engine);
    return failed(env) ? 2 : result;
}

static double cb_now(int32_t engine)
{
    JNIEnv *env = env_here();
    jdouble result = (*env)->CallStaticDoubleMethod(env, host, host_now, engine);
    return failed(env) ? 0 : result;
}

static int32_t cb_tz_offset(int32_t engine, double time)
{
    JNIEnv *env = env_here();
    jint result = (*env)->CallStaticIntMethod(env, host, host_tz_offset, engine, time);
    return failed(env) ? 0 : result;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *jvm, void *reserved)
{
    vm = jvm;
    JNIEnv *env = env_here();
    if (!env)
        return JNI_ERR;
    jclass local = (*env)->FindClass(env, "io/github/yuroyami/kitejs/quickjs/bridge/JniHost");
    if (!local)
        return JNI_ERR;
    host = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    host_call = (*env)->GetStaticMethodID(env, host, "call", "(III)I");
    host_interrupt = (*env)->GetStaticMethodID(env, host, "interrupt", "(I)I");
    host_now = (*env)->GetStaticMethodID(env, host, "now", "(I)D");
    host_tz_offset = (*env)->GetStaticMethodID(env, host, "timeZoneOffset", "(ID)I");
    if (!host_call || !host_interrupt || !host_now || !host_tz_offset)
        return JNI_ERR;
    kite_set_host(cb_call, cb_interrupt, cb_now, cb_tz_offset);
    return JNI_VERSION_1_6;
}

/* ---- Strings ------------------------------------------------------------------------------- */

static jstring to_java(JNIEnv *env, const uint16_t *chars, int32_t length)
{
    return (*env)->NewString(env, (const jchar *)chars, length);
}

/* ---- Engines ------------------------------------------------------------------------------- */

JNIEXPORT jlong JNICALL NATIVE(newEngine)(JNIEnv *env, jclass cls, jint id, jdouble memory, jdouble stack, jint options)
{
    return (jlong)(intptr_t)kite_new(id, memory, stack, options);
}

JNIEXPORT void JNICALL NATIVE(free)(JNIEnv *env, jclass cls, jlong e)
{
    kite_free(ENGINE(e));
}

JNIEXPORT void JNICALL NATIVE(enter)(JNIEnv *env, jclass cls, jlong e)
{
    kite_enter(ENGINE(e));
}

JNIEXPORT void JNICALL NATIVE(setBudget)(JNIEnv *env, jclass cls, jlong e, jdouble budget)
{
    kite_set_budget(ENGINE(e), budget);
}

JNIEXPORT void JNICALL NATIVE(askHost)(JNIEnv *env, jclass cls, jlong e, jboolean ask)
{
    kite_ask_host(ENGINE(e), ask);
}

JNIEXPORT jint JNICALL NATIVE(stopReason)(JNIEnv *env, jclass cls, jlong e)
{
    return kite_stop_reason(ENGINE(e));
}

JNIEXPORT jstring JNICALL NATIVE(version)(JNIEnv *env, jclass cls)
{
    return (*env)->NewStringUTF(env, kite_version());
}

/* ---- Values -------------------------------------------------------------------------------- */

JNIEXPORT jint JNICALL NATIVE(type)(JNIEnv *env, jclass cls, jlong e, jint h)
{
    return kite_type(ENGINE(e), h);
}

JNIEXPORT jdouble JNICALL NATIVE(number)(JNIEnv *env, jclass cls, jlong e, jint h)
{
    return kite_number(ENGINE(e), h);
}

JNIEXPORT jstring JNICALL NATIVE(string)(JNIEnv *env, jclass cls, jlong e, jint h)
{
    KiteEngine *engine = ENGINE(e);
    const uint16_t *chars = kite_string(engine, h);
    if (!chars)
        return NULL;
    jstring s = to_java(env, chars, kite_string_length(engine));
    kite_string_free(engine);
    return s;
}

JNIEXPORT jdouble JNICALL NATIVE(identity)(JNIEnv *env, jclass cls, jlong e, jint h)
{
    return kite_identity(ENGINE(e), h);
}

JNIEXPORT jboolean JNICALL NATIVE(isPromise)(JNIEnv *env, jclass cls, jlong e, jint h)
{
    return kite_is_promise(ENGINE(e), h) != 0;
}

JNIEXPORT jint JNICALL NATIVE(dup)(JNIEnv *env, jclass cls, jlong e, jint h)
{
    return kite_dup(ENGINE(e), h);
}

JNIEXPORT void JNICALL NATIVE(release)(JNIEnv *env, jclass cls, jlong e, jint h)
{
    kite_release(ENGINE(e), h);
}

JNIEXPORT jint JNICALL NATIVE(newNumber)(JNIEnv *env, jclass cls, jlong e, jdouble d)
{
    return kite_new_number(ENGINE(e), d);
}

JNIEXPORT jint JNICALL NATIVE(newString)(JNIEnv *env, jclass cls, jlong e, jstring s)
{
    jsize length = (*env)->GetStringLength(env, s);
    const jchar *chars = (*env)->GetStringChars(env, s, NULL);
    jint h = kite_new_string(ENGINE(e), (const uint16_t *)chars, length);
    (*env)->ReleaseStringChars(env, s, chars);
    return h;
}

JNIEXPORT jint JNICALL NATIVE(newObject)(JNIEnv *env, jclass cls, jlong e)
{
    return kite_new_object(ENGINE(e));
}

JNIEXPORT jint JNICALL NATIVE(newArray)(JNIEnv *env, jclass cls, jlong e)
{
    return kite_new_array(ENGINE(e));
}

JNIEXPORT void JNICALL NATIVE(arrayPush)(JNIEnv *env, jclass cls, jlong e, jint array, jint value)
{
    kite_array_push(ENGINE(e), array, value);
}

JNIEXPORT void JNICALL NATIVE(objectPut)(JNIEnv *env, jclass cls, jlong e, jint obj, jstring key, jint value)
{
    jsize length = (*env)->GetStringLength(env, key);
    const jchar *chars = (*env)->GetStringChars(env, key, NULL);
    kite_object_put(ENGINE(e), obj, (const uint16_t *)chars, length, value);
    (*env)->ReleaseStringChars(env, key, chars);
}

JNIEXPORT jint JNICALL NATIVE(arrayLength)(JNIEnv *env, jclass cls, jlong e, jint array)
{
    return kite_array_length(ENGINE(e), array);
}

JNIEXPORT jint JNICALL NATIVE(arrayGet)(JNIEnv *env, jclass cls, jlong e, jint array, jint index)
{
    return kite_array_get(ENGINE(e), array, index);
}

JNIEXPORT jint JNICALL NATIVE(global)(JNIEnv *env, jclass cls, jlong e)
{
    return kite_global(ENGINE(e));
}

/* ---- Running ------------------------------------------------------------------------------- */

JNIEXPORT jint JNICALL NATIVE(compile)(JNIEnv *env, jclass cls, jlong e, jstring source, jstring file)
{
    jsize source_length = (*env)->GetStringLength(env, source);
    jsize file_length = (*env)->GetStringLength(env, file);
    const jchar *source_chars = (*env)->GetStringChars(env, source, NULL);
    const jchar *file_chars = (*env)->GetStringChars(env, file, NULL);
    jint h = kite_compile(ENGINE(e), (const uint16_t *)source_chars, source_length,
                          (const uint16_t *)file_chars, file_length);
    (*env)->ReleaseStringChars(env, source, source_chars);
    (*env)->ReleaseStringChars(env, file, file_chars);
    return h;
}

JNIEXPORT jint JNICALL NATIVE(run)(JNIEnv *env, jclass cls, jlong e, jint compiled)
{
    return kite_run(ENGINE(e), compiled);
}

JNIEXPORT void JNICALL NATIVE(pushArg)(JNIEnv *env, jclass cls, jlong e, jint h)
{
    kite_push_arg(ENGINE(e), h);
}

JNIEXPORT jint JNICALL NATIVE(call)(JNIEnv *env, jclass cls, jlong e, jint fn, jint self)
{
    return kite_call(ENGINE(e), fn, self);
}

JNIEXPORT jint JNICALL NATIVE(construct)(JNIEnv *env, jclass cls, jlong e, jint fn)
{
    return kite_construct(ENGINE(e), fn);
}

JNIEXPORT jint JNICALL NATIVE(drain)(JNIEnv *env, jclass cls, jlong e)
{
    return kite_drain(ENGINE(e));
}

JNIEXPORT void JNICALL NATIVE(discardJobs)(JNIEnv *env, jclass cls, jlong e)
{
    kite_discard_jobs(ENGINE(e));
}

JNIEXPORT jint JNICALL NATIVE(exception)(JNIEnv *env, jclass cls, jlong e)
{
    return kite_exception(ENGINE(e));
}

JNIEXPORT jint JNICALL NATIVE(newFunction)(JNIEnv *env, jclass cls, jlong e, jint fn, jstring name, jint arity)
{
    jsize length = (*env)->GetStringLength(env, name);
    const jchar *chars = (*env)->GetStringChars(env, name, NULL);
    jint h = kite_new_function(ENGINE(e), fn, (const uint16_t *)chars, length, arity);
    (*env)->ReleaseStringChars(env, name, chars);
    return h;
}

JNIEXPORT jint JNICALL NATIVE(deadFunction)(JNIEnv *env, jclass cls, jlong e)
{
    return kite_dead_function(ENGINE(e));
}

JNIEXPORT jint JNICALL NATIVE(cbThis)(JNIEnv *env, jclass cls, jlong e)
{
    return kite_cb_this(ENGINE(e));
}

JNIEXPORT jint JNICALL NATIVE(cbArg)(JNIEnv *env, jclass cls, jlong e, jint index)
{
    return kite_cb_arg(ENGINE(e), index);
}
