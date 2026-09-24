// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import java.util.concurrent.atomic.AtomicInteger

/** In-process proof control. Exported Core has no way to construct or arm this. */
internal class BytecodeCheckpoint {
    @Volatile var armed = false
    val visits = AtomicInteger()
    val compiledVisits = AtomicInteger()
}
