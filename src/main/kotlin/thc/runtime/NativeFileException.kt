// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import java.io.IOException

/** Captured host errno, not a guessed managed error category. */
class NativeFileException internal constructor(operation: String, val errno: Int) :
    IOException("Native file $operation failed (errno $errno)") {
    init { require(errno > 0) { "Invalid native errno" } }
}
