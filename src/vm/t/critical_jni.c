// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
#if !defined(_WIN32) && !defined(_POSIX_C_SOURCE)
#define _POSIX_C_SOURCE 200809L
#endif
#include <jni.h>
#include <stdatomic.h>
#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#else
#include <time.h>
#endif

static atomic_int entered;
JNIEXPORT jboolean JNICALL Java_Critical_entered(JNIEnv *env, jclass klass) {
  (void)env; (void)klass;
  return atomic_load(&entered) != 0;
}
JNIEXPORT void JNICALL Java_Critical_hold(JNIEnv *env, jclass klass, jintArray array) {
  (void)klass;
  jint *data = (*env)->GetPrimitiveArrayCritical(env, array, 0);
  if (!data) return;
  atomic_store(&entered, 1);
#if defined(_WIN32)
  Sleep(200);
#else
  struct timespec delay = {0, 200000000};
  nanosleep(&delay, 0);
#endif
  ++data[0];
  (*env)->ReleasePrimitiveArrayCritical(env, array, data, 0);
}
