/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

/*
 * The platform half of jni.h, for every desktop platform the JNI library is cross-compiled for
 * from one machine. A JDK ships only its own platform's jni_md.h; jni.h itself is the same
 * everywhere, so the build takes it from whichever JDK it runs on and this file from here.
 */

#ifndef KITEJS_JNI_MD_H
#define KITEJS_JNI_MD_H

#if defined(_WIN32)
#define JNIEXPORT __declspec(dllexport)
#define JNIIMPORT __declspec(dllimport)
#define JNICALL __stdcall
typedef long jint;
typedef __int64 jlong;
#else
#define JNIEXPORT __attribute__((visibility("default")))
#define JNIIMPORT __attribute__((visibility("default")))
#define JNICALL
typedef int jint;
#if defined(_LP64)
typedef long jlong;
#else
typedef long long jlong;
#endif
#endif

typedef signed char jbyte;

#endif
