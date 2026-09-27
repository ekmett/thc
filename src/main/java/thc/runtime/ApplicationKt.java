// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;

public final class ApplicationKt {
    private ApplicationKt() {}
    public static Object[] getNO_PAP_ARGUMENTS() { return Closure.NO_PAP_ARGUMENTS; }
    public static Closure requireClosure(Object value) {
        if (value instanceof Closure closure) return closure;
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeFault("Application of a non-function");
    }
    static Object[] appendWithHeader(int skip, Object[] prefix, int prefixSize, Object[] arguments, int count) {
        Object[] packet = new Object[skip + prefixSize + count];
        System.arraycopy(prefix, 0, packet, skip, prefixSize);
        System.arraycopy(arguments, 0, packet, skip + prefixSize, count);
        return packet;
    }
}
