// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/** Permanent ownership without a separate strong collection of class keys. */
public final class ClassOwnedLayouts {
    public static final String CLASS_OWNED_LAYOUTS_PROPERTY = "thc.classOwnedLayouts";
    public static final ClassOwnedLayouts INSTANCE = new ClassOwnedLayouts();
    private ClassOwnedLayouts() {}
    private static final class Owner {
        private Object token;
        private volatile DataLayout descriptor;
        synchronized boolean reserve(Object candidate) {
            if (token == null) token = candidate;
            return token == candidate;
        }
        synchronized void publish(Object candidate, DataLayout layout) {
            if (token != candidate || descriptor != null && descriptor != layout)
                { com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate(); throw new RuntimeFault("Invalid permanent constructor class ownership"); }
            // Publish only after all immutable fields and cached values initialize.
            descriptor = layout;
        }
        DataLayout resolve() {
            DataLayout layout = descriptor;
            if (layout == null) { com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate(); throw new RuntimeFault("Unpublished constructor class ownership"); }
            return layout;
        }
    }
    // Composition keeps ClassValue.remove inaccessible to callers.
    private static final ClassValue<Owner> owners = new ClassValue<>() {
        @Override protected Owner computeValue(Class<?> type) { return new Owner(); }
    };
    @TruffleBoundary public static boolean reserve(Class<?> carrier, Object token) { return owners.get(carrier).reserve(token); }
    @TruffleBoundary public static void publish(Class<?> carrier, Object token, DataLayout layout) { owners.get(carrier).publish(token, layout); }
    @TruffleBoundary public static DataLayout resolve(Class<?> carrier) { return owners.get(carrier).resolve(); }
}
