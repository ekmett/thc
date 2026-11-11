// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
package com.oracle.svm.core.jam;

import java.util.function.Predicate;
import org.graalvm.nativeimage.StackValue;
import org.graalvm.nativeimage.c.type.CLongPointer;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.shared.Uninterruptible;
import com.oracle.svm.shared.util.ReflectionUtil;

/** The public Runnable pump is unchanged; registrations belong to the current isolate. */
@TargetClass(className = "thc.vm.Weak", onlyWith = {JamGC.Enabled.class, THCWeakPresent.class})
final class Target_thc_vm_Weak {
    @Substitute private static void loadNativeLibrary() { }
    @Substitute public static void checkAvailable() { }

    @Substitute
    public static long create(Object key, Object value, Runnable finalizer) {
        if (key == null) throw new NullPointerException("weak key");
        long token = THCWeakSupport.create(key, value, finalizer);
        if (token == 0) throw new OutOfMemoryError("Jam weak registration metadata exhausted");
        return token;
    }

    @Substitute
    @Uninterruptible(reason = "Materialize the conditional value as a strong Java result before a safepoint.")
    public static Object deref(long token) {
        JamHeap heap = JamHeap.get();
        heap.lock().lockNoTransition();
        try { return heap.decode(JamNative.weakValue(heap.nativeHeap(), token)); }
        finally { heap.lock().unlock(); }
    }

    @Substitute
    public static Runnable take(long[] tokenOut) {
        if (tokenOut == null) throw new NullPointerException("tokenOut");
        if (tokenOut.length == 0) throw new IllegalArgumentException("tokenOut must have an element");
        return THCWeakSupport.take(tokenOut);
    }

    @Substitute
    @Uninterruptible(reason = "Retirement and finalizer claim are one serialized operation.")
    public static Runnable finalizeNow(long token) {
        JamHeap heap = JamHeap.get();
        heap.lock().lockNoTransition();
        try { return (Runnable) heap.decode(JamNative.weakFinalize(heap.nativeHeap(), token)); }
        finally { heap.lock().unlock(); }
    }

    @Substitute
    @Uninterruptible(reason = "Release the registry root under the heap lock.")
    public static void complete(long token) {
        JamHeap heap = JamHeap.get();
        heap.lock().lockNoTransition();
        try { JamNative.weakComplete(heap.nativeHeap(), token); }
        finally { heap.lock().unlock(); }
    }
}

final class THCWeakPresent implements Predicate<String> {
    @Override public boolean test(String name) {
        return ReflectionUtil.lookupClass(true, name, Thread.currentThread().getContextClassLoader()) != null;
    }
}

final class THCWeakSupport {
    @Uninterruptible(reason = "Encode all conditional references under the heap lock before a safepoint.")
    static long create(Object key, Object value, Runnable finalizer) {
        JamHeap heap = JamHeap.get();
        heap.lock().lockNoTransition();
        try {
            return JamNative.weakCreate(heap.nativeHeap(), heap.encode(key), heap.encode(value), heap.encode(finalizer));
        } finally {
            heap.lock().unlock();
        }
    }

    @Uninterruptible(reason = "The finalizer is rooted in the registry throughout the atomic claim.")
    static Runnable take(long[] tokenOut) {
        JamHeap heap = JamHeap.get();
        CLongPointer token = StackValue.get(Long.BYTES);
        heap.lock().lockNoTransition();
        try {
            int at = JamNative.weakTake(heap.nativeHeap(), token);
            tokenOut[0] = token.read();
            return (Runnable) heap.decode(at);
        } finally { heap.lock().unlock(); }
    }

}
