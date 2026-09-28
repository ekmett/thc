// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;

/** Cache Object-ABI boxes at tail sites without unboxing the cached result. */
public abstract class BloomValue extends Node {
    public abstract Object execute(long mask);
    @Specialization(guards = "mask == cachedMask", limit = "3")
    protected Object cached(long mask, @Cached("mask") long cachedMask,
                            @Cached(value = "box(cachedMask)", neverDefault = true) Object value) { return value; }
    @Specialization(replaces = "cached") protected Object generic(long mask) { return mask; }
    @TruffleBoundary public static Object box(long mask) { return mask; }
}
