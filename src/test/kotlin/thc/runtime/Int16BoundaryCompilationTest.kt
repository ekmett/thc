// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.Isolated

/** Separate from the native corpus so its original method order is unchanged. */
@Isolated("Retires the JVM-wide Truffle call-boundary stub")
@Execution(ExecutionMode.SAME_THREAD)
class Int16BoundaryCompilationTest {
    @Test fun compilationRestoresRetiredBoundaryBeforeFirstTwoRootCall() =
        Int16ArrayNativeTest().checkRetiredBoundaryBeforeFirstTwoRootCall()
}
