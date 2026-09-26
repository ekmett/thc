// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

#include <jni.h>
#include <stdint.h>

int heap_alias(unsigned char *base, unsigned char *slice, int offset, int value) {
    if (slice != base + offset) return -1;
    slice[0] = (unsigned char)value;
    return base[offset];
}

JNIEXPORT jlong JNICALL Java_HeapPinProbe_criticalAlias(
        JNIEnv *env, jclass klass, jbyteArray first, jbyteArray second,
        jint offset, jint value) {
    (void)klass;
    jboolean copied_first = JNI_FALSE, copied_second = JNI_FALSE;
    jbyte *a = (*env)->GetPrimitiveArrayCritical(env, first, &copied_first);
    if (!a) return -1;
    jbyte *b = (*env)->GetPrimitiveArrayCritical(env, second, &copied_second);
    if (!b) {
        (*env)->ReleasePrimitiveArrayCritical(env, first, a, JNI_ABORT);
        return -1;
    }
    b[offset] = (jbyte)value;
    jlong result = ((unsigned char *)a)[offset]
        | (a == b ? 256 : 0)
        | (copied_first ? 512 : 0)
        | (copied_second ? 1024 : 0);
    (*env)->ReleasePrimitiveArrayCritical(env, second, b, 0);
    (*env)->ReleasePrimitiveArrayCritical(env, first, a, 0);
    return result;
}

JNIEXPORT jlong JNICALL Java_HeapPinProbe_addressSnapshot(
        JNIEnv *env, jclass klass, jbyteArray bytes) {
    (void)klass;
    jbyte *p = (*env)->GetPrimitiveArrayCritical(env, bytes, NULL);
    if (!p) return 0;
    jlong bits = (jlong)(uintptr_t)p;
    (*env)->ReleasePrimitiveArrayCritical(env, bytes, p, JNI_ABORT);
    return bits;
}
