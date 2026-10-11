// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

#if defined(__linux__) && !defined(_GNU_SOURCE)
#define _GNU_SOURCE 1
#endif
#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#else
#include <dlfcn.h>
#endif
#include <jni.h>
#include "thc_vm_Weak.h"
#include "thc_vm_Candidate.h"

#if defined(_WIN32)
static FARPROC vm_symbol(char const *name) {
  HMODULE module = GetModuleHandleW(L"jvm.dll");
  return module ? GetProcAddress(module, name) : NULL;
}
#else
static void *vm_symbol(char const *name) {
  return dlsym(RTLD_DEFAULT, name);
}
#endif

static jlong (JNICALL *weak_create)(JNIEnv *, jclass, jobject, jobject, jobject);
static jobject (JNICALL *weak_deref)(JNIEnv *, jclass, jlong);
static jobject (JNICALL *weak_take)(JNIEnv *, jclass, jlongArray);
static jobject (JNICALL *weak_finalize)(JNIEnv *, jclass, jlong);
static void (JNICALL *weak_complete)(JNIEnv *, jclass, jlong);
static jlong (JNICALL *collections)(JNIEnv *, jclass, jint);

static jlong (JNICALL *candidate_arm)(JNIEnv *, jclass, jobject, jlong);
static jobject (JNICALL *candidate_poll)(JNIEnv *, jclass, jlong, jlong);
static jobject (JNICALL *candidate_disarm)(JNIEnv *, jclass, jlong, jlong);
static void (JNICALL *candidate_complete)(JNIEnv *, jclass, jlong, jlong);
static jlong (JNICALL *candidate_epoch)(JNIEnv *, jclass);

static int candidate_available(JNIEnv *env) {
  if (candidate_arm && candidate_poll && candidate_disarm && candidate_complete && candidate_epoch) return 1;
  jclass error = (*env)->FindClass(env, "java/lang/UnsatisfiedLinkError");
  if (error) (*env)->ThrowNew(env, error, "thc-vm Candidate requires a JVM exporting the Jam candidate hooks");
  return 0;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
  (void)reserved;
  JNIEnv *env = NULL;
  if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8) != JNI_OK) return JNI_ERR;

  // Resolve at load time so a stock JVM reports a Java linkage error instead
  // of aborting on a missing lazy-bound JVM symbol at the first guest call.
  weak_create = (jlong (JNICALL *)(JNIEnv *, jclass, jobject, jobject, jobject))
    vm_symbol("JVM_THCWeakCreate");
  weak_deref = (jobject (JNICALL *)(JNIEnv *, jclass, jlong))
    vm_symbol("JVM_THCWeakDeref");
  weak_take = (jobject (JNICALL *)(JNIEnv *, jclass, jlongArray))
    vm_symbol("JVM_THCWeakTake");
  weak_finalize = (jobject (JNICALL *)(JNIEnv *, jclass, jlong))
    vm_symbol("JVM_THCWeakFinalize");
  weak_complete = (void (JNICALL *)(JNIEnv *, jclass, jlong))
    vm_symbol("JVM_THCWeakComplete");
  collections = (jlong (JNICALL *)(JNIEnv *, jclass, jint))
    vm_symbol("JVM_THCCollections");
  if (!weak_create || !weak_deref || !weak_take || !weak_finalize || !weak_complete || !collections) {
    jclass error = (*env)->FindClass(env, "java/lang/UnsatisfiedLinkError");
    if (error) (*env)->ThrowNew(env, error, "thc-vm requires a JVM exporting the Jam weak hooks");
    return JNI_ERR;
  }
  // Candidate is optional: older Jam JVMs must still load the Weak bridge.
  candidate_arm = (jlong (JNICALL *)(JNIEnv *, jclass, jobject, jlong))
    vm_symbol("JVM_THCCandidateArm");
  candidate_poll = (jobject (JNICALL *)(JNIEnv *, jclass, jlong, jlong))
    vm_symbol("JVM_THCCandidatePoll");
  candidate_disarm = (jobject (JNICALL *)(JNIEnv *, jclass, jlong, jlong))
    vm_symbol("JVM_THCCandidateDisarm");
  candidate_complete = (void (JNICALL *)(JNIEnv *, jclass, jlong, jlong))
    vm_symbol("JVM_THCCandidateComplete");
  candidate_epoch = (jlong (JNICALL *)(JNIEnv *, jclass))
    vm_symbol("JVM_THCCandidateEpoch");
  return JNI_VERSION_1_8;
}

JNIEXPORT void JNICALL Java_thc_vm_Weak_checkAvailable(JNIEnv *env, jclass klass) {
  (void)collections(env, klass, 2);
}

JNIEXPORT jlong JNICALL Java_thc_vm_Weak_create(JNIEnv *env, jclass klass,
                                               jobject key, jobject value, jobject finalizer) {
  return weak_create(env, klass, key, value, finalizer);
}

JNIEXPORT jobject JNICALL Java_thc_vm_Weak_deref(JNIEnv *env, jclass klass, jlong token) {
  return weak_deref(env, klass, token);
}

JNIEXPORT jobject JNICALL Java_thc_vm_Weak_take(JNIEnv *env, jclass klass, jlongArray token_out) {
  return weak_take(env, klass, token_out);
}

JNIEXPORT jobject JNICALL Java_thc_vm_Weak_finalizeNow(JNIEnv *env, jclass klass, jlong token) {
  return weak_finalize(env, klass, token);
}

JNIEXPORT void JNICALL Java_thc_vm_Weak_complete(JNIEnv *env, jclass klass, jlong token) {
  weak_complete(env, klass, token);
}

JNIEXPORT jlong JNICALL Java_thc_vm_Candidate_arm(JNIEnv *env, jclass klass,
                                                jobject owner, jlong wait_generation) {
  if (!candidate_available(env)) return 0;
  return candidate_arm(env, klass, owner, wait_generation);
}

JNIEXPORT jobject JNICALL Java_thc_vm_Candidate_poll(JNIEnv *env, jclass klass,
                                                   jlong ticket, jlong wait_generation) {
  if (!candidate_available(env)) return NULL;
  return candidate_poll(env, klass, ticket, wait_generation);
}

JNIEXPORT jobject JNICALL Java_thc_vm_Candidate_disarm(JNIEnv *env, jclass klass,
                                                     jlong ticket, jlong wait_generation) {
  if (!candidate_available(env)) return NULL;
  return candidate_disarm(env, klass, ticket, wait_generation);
}

JNIEXPORT void JNICALL Java_thc_vm_Candidate_complete(JNIEnv *env, jclass klass,
                                                    jlong ticket, jlong wait_generation) {
  if (candidate_available(env)) candidate_complete(env, klass, ticket, wait_generation);
}

JNIEXPORT jlong JNICALL Java_thc_vm_Candidate_epoch(JNIEnv *env, jclass klass) {
  if (!candidate_available(env)) return 0;
  return candidate_epoch(env, klass);
}
