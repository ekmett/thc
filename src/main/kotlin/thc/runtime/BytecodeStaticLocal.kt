// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.FrameSlotKind

/** Compiler-owned initial storage, not an observed value or profile. */
internal data class BytecodeStaticLocal(val kind: FrameSlotKind) {
    init { require(kind in setOf(FrameSlotKind.Int, FrameSlotKind.Long, FrameSlotKind.Float,
        FrameSlotKind.Double, FrameSlotKind.Boolean, FrameSlotKind.Object)) }
}
