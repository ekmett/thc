// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.nodes.Node
import thc.Language

/** Small target services behind a versioned private scalar ABI. Stable Haskell
 * modules expose typed records; hazardous controls stay in Unsafe internals. */
internal object RuntimeServices {
    @JvmStatic @TruffleBoundary
    fun query(node: Node, backend: Int, selector: Int, index: Long, detail: Long): Long {
        val state = Language.currentState(node)
        return when (selector) {
            in 0..7 -> {
                if (index != 0L || selector < 5 && detail != 0L) fault("Invalid runtime information index")
                when (selector) {
                    0 -> 1L // THC, rather than the native-GHC compatibility implementation.
                    1 -> backend.toLong() // Actual lowering backend, not a context default.
                    2 -> if (state.env.isNativeAccessAllowed) 1L else 0L
                    3 -> if (state.env.isCreateThreadAllowed) 1L else 0L
                    4 -> state.threads.cpuAffinity.count.toLong()
                    5 -> node.rootNode?.languageInfo?.version?.let { RuntimeServiceStatus.text(it, detail) }
                        ?: RuntimeServiceStatus.UNAVAILABLE
                    else -> try {
                        System.getProperty(if (selector == 6) "java.version" else "java.vm.name")
                            ?.let { RuntimeServiceStatus.text(it, detail) } ?: RuntimeServiceStatus.UNAVAILABLE
                    } catch (_: SecurityException) { RuntimeServiceStatus.DENIED }
                }
            }
            in 100..199 -> RuntimeThreadServices.query(state, selector, index, detail)
            in 200..299 -> RuntimeMemoryServices.query(state, selector, index, detail)
            in 300..399 -> RuntimeGCServices.query(state, selector, index, detail)
            in 400..499 -> state.runtimeJit.query(selector, index, detail)
            in 500..599 -> state.runtimeTrace.query(selector, index, detail)
            else -> fault("Unknown runtime service query $selector")
        }
    }

    @JvmStatic @TruffleBoundary
    fun control(node: Node, selector: Int, setting: Long): Long {
        val state = Language.currentState(node)
        return when (selector) {
            400 -> state.runtimeJit.control(selector, setting)
            500 -> state.runtimeTrace.control(selector, setting)
            else -> fault("Unknown runtime service control $selector")
        }
    }

    @JvmStatic @TruffleBoundary
    fun trace(node: Node, operation: Int, token: Long, bytes: ManagedAddress, length: Long): Long {
        val state = Language.currentState(node)
        val previous = state.threads.enterForeign()
        return try { state.runtimeTrace.emit(operation, token, bytes, length) }
        finally { state.threads.leaveForeign(previous) }
    }
}
