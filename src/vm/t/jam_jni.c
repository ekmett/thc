// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

#include <jni.h>

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
static void (JNICALL *JVM_JamCollect)(JNIEnv *, jclass, jboolean, jboolean);
static jlong (JNICALL *JVM_JamCollections)(JNIEnv *, jclass, jint);
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
  (void)vm; (void)reserved;
  HMODULE module = GetModuleHandleW(L"jvm.dll");
  if (!module) return JNI_ERR;
  JVM_JamCollect = (void (JNICALL *)(JNIEnv *, jclass, jboolean, jboolean))GetProcAddress(module, "JVM_JamCollect");
  JVM_JamCollections = (jlong (JNICALL *)(JNIEnv *, jclass, jint))GetProcAddress(module, "JVM_JamCollections");
  return JVM_JamCollect && JVM_JamCollections ? JNI_VERSION_1_8 : JNI_ERR;
}
#else
extern void JNICALL JVM_JamCollect(JNIEnv *, jclass, jboolean, jboolean);
extern jlong JNICALL JVM_JamCollections(JNIEnv *, jclass, jint);
#endif

JNIEXPORT void JNICALL Java_JamWeak_minor(JNIEnv *env, jclass klass, jboolean promote) {
  JVM_JamCollect(env, klass, JNI_TRUE, promote);
}

JNIEXPORT jlong JNICALL Java_JamWeak_collections(JNIEnv *env, jclass klass, jint kind) {
  return JVM_JamCollections(env, klass, kind);
}
