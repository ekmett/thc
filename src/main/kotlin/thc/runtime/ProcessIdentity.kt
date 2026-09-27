// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.nodes.Node
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout
import thc.Language

/** Live libc process identity; cached handles never cache the returned identity. */
internal object ProcessIdentity {
    @JvmStatic @TruffleBoundary fun query(node: Node, operation: OriginalStdioOp): Long {
        if (!operation.processIdentity) fault("Invalid original process identity operation")
        val state = Language.currentState(node)
        if (!state.env.isNativeAccessAllowed) fault("Original process identity requires native access")
        // The host probe also asserts signed 32-bit pid_t and unsigned 32-bit uid_t.
        StdioHostAbi.load()
        val previous = state.threads.enterForeign()
        try {
            return if (operation == OriginalStdioOp.GET_PID) (Libc.getpid.invokeExact() as Int).toLong()
                else (Libc.geteuid.invokeExact() as Int).toLong() and 0xffff_ffffL
        } finally { state.threads.leaveForeign(previous) }
    }

    private object Libc {
        private val linker = Linker.nativeLinker()
        private val signature = FunctionDescriptor.of(ValueLayout.JAVA_INT)
        val getpid = linker.downcallHandle(linker.defaultLookup().find("getpid").orElseThrow(), signature)
        val geteuid = linker.downcallHandle(linker.defaultLookup().find("geteuid").orElseThrow(), signature)
    }
}
