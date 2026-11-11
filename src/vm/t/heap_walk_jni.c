// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

#include <jni.h>
#include <jvmti.h>
#include <stdio.h>

static jint JNICALL count_entry(jlong class_tag, jlong size, jlong *tag, jint length, void *data) {
  (void)class_tag; (void)size; (void)tag; (void)length;
  ++*(jlong *)data;
  return 0;
}

static jvmtiIterationControl JNICALL count_legacy(jlong class_tag, jlong size, jlong *tag, void *data) {
  (void)class_tag; (void)size; (void)tag;
  ++*(jlong *)data;
  return JVMTI_ITERATION_CONTINUE;
}

JNIEXPORT jlong JNICALL Java_JvmHeapWalkSmoke_jvmtiCount(JNIEnv *env, jclass ignored,
                                                        jclass type, jboolean legacy) {
  (void)ignored;
  JavaVM *vm;
  jvmtiEnv *jvmti;
  if ((*env)->GetJavaVM(env, &vm) != JNI_OK ||
      (*vm)->GetEnv(vm, (void **)&jvmti, JVMTI_VERSION_1_2) != JNI_OK) {
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/AssertionError"), "JVMTI unavailable");
    return 0;
  }
  jvmtiCapabilities capabilities = {0};
  capabilities.can_tag_objects = 1;
  jvmtiError error = (*jvmti)->AddCapabilities(jvmti, &capabilities);
  jlong count = 0;
  if (error == JVMTI_ERROR_NONE) {
    if (legacy) {
      error = (*jvmti)->IterateOverInstancesOfClass(jvmti, type, JVMTI_HEAP_OBJECT_EITHER, count_legacy, &count);
    } else {
      jvmtiHeapCallbacks callbacks = {0};
      callbacks.heap_iteration_callback = count_entry;
      error = (*jvmti)->IterateThroughHeap(jvmti, 0, type, &callbacks, &count);
    }
  }
  jvmtiError disposed = (*jvmti)->DisposeEnvironment(jvmti);
  if (error == JVMTI_ERROR_NONE) error = disposed;
  if (error != JVMTI_ERROR_NONE) {
    char message[80];
    snprintf(message, sizeof(message), "JVMTI heap iteration failed: %d", (int)error);
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/AssertionError"), message);
  }
  return count;
}
