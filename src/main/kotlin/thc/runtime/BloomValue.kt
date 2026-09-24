// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.dsl.Cached
import com.oracle.truffle.api.dsl.Specialization
import com.oracle.truffle.api.nodes.Node

/** A few ancestry masks recur at each tail site; cache their Object-ABI boxes locally. */
internal abstract class BloomValue : Node() {
    // Keep the boxed result typed as Any throughout, including the specialization.
    // Returning Long would unbox the cached value and box it again at packet[0].
    abstract fun execute(mask: Long): Any

    @Specialization(guards = ["mask == cachedMask"], limit = "3")
    fun cached(mask: Long,
               @Cached("mask") cachedMask: Long,
               @Cached(value = "box(cachedMask)", neverDefault = true) value: Any): Any = value

    // A polymorphic site loses the allocation optimization, never mask bits.
    @Specialization(replaces = ["cached"])
    fun generic(mask: Long): Any = mask

    companion object {
        /** DSL cache initializers run after transfer to the interpreter. */
        @JvmStatic @TruffleBoundary fun box(mask: Long): Any = mask
    }
}
