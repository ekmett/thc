// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

#include <jni.h>

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
static void (JNICALL *JVM_THCCollect)(JNIEnv *, jclass, jboolean, jboolean);
static jlong (JNICALL *JVM_THCCollections)(JNIEnv *, jclass, jint);
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
  (void)vm; (void)reserved;
  HMODULE module = GetModuleHandleW(L"jvm.dll");
  if (!module) return JNI_ERR;
  JVM_THCCollect = (void (JNICALL *)(JNIEnv *, jclass, jboolean, jboolean))GetProcAddress(module, "JVM_THCCollect");
  JVM_THCCollections = (jlong (JNICALL *)(JNIEnv *, jclass, jint))GetProcAddress(module, "JVM_THCCollections");
  return JVM_THCCollect && JVM_THCCollections ? JNI_VERSION_1_8 : JNI_ERR;
}
#else
extern void JNICALL JVM_THCCollect(JNIEnv *, jclass, jboolean, jboolean);
extern jlong JNICALL JVM_THCCollections(JNIEnv *, jclass, jint);
#endif

JNIEXPORT void JNICALL Java_THCWeak_minor(JNIEnv *env, jclass klass, jboolean promote) {
  JVM_THCCollect(env, klass, JNI_TRUE, promote);
}

JNIEXPORT jlong JNICALL Java_THCWeak_collections(JNIEnv *env, jclass klass, jint kind) {
  return JVM_THCCollections(env, klass, kind);
}
