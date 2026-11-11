// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

#include <jni.h>
#include <stdint.h>

JNIEXPORT jlong JNICALL Java_JNIWeakSmoke_create(JNIEnv *env, jclass klass, jobject value) {
    (void)klass;
    return (jlong)(uintptr_t)(*env)->NewWeakGlobalRef(env, value);
}

JNIEXPORT jobject JNICALL Java_JNIWeakSmoke_read(JNIEnv *env, jclass klass, jlong handle) {
    (void)klass;
    return (*env)->NewLocalRef(env, (jweak)(uintptr_t)handle);
}

JNIEXPORT jboolean JNICALL Java_JNIWeakSmoke_present(JNIEnv *env, jclass klass, jlong handle) {
    (void)klass;
    jobject local = (*env)->NewLocalRef(env, (jweak)(uintptr_t)handle);
    if (!local) return JNI_FALSE;
    (*env)->DeleteLocalRef(env, local);
    return JNI_TRUE;
}

JNIEXPORT jobject JNICALL Java_JNIWeakSmoke_retainAndCollect(JNIEnv *env, jclass klass, jlong handle) {
    jobject local = (*env)->NewLocalRef(env, (jweak)(uintptr_t)handle);
    if (!local) return NULL;
    jmethodID collect = (*env)->GetStaticMethodID(env, klass, "collect", "()V");
    if (!collect) return NULL;
    (*env)->CallStaticVoidMethod(env, klass, collect);
    return (*env)->ExceptionCheck(env) ? NULL : local;
}

JNIEXPORT void JNICALL Java_JNIWeakSmoke_delete(JNIEnv *env, jclass klass, jlong handle) {
    (void)klass;
    (*env)->DeleteWeakGlobalRef(env, (jweak)(uintptr_t)handle);
}
