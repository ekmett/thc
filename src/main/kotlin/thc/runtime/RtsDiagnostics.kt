// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.nodes.Node
import thc.Language
import java.io.IOException

/** Backend diagnostics, not a claim about native GHC TSO allocation or RTS flags. */
internal object RtsDiagnostics {
    /** Context stderr records, not the GHC binary eventlog format or RTS flags. */
    @JvmStatic @TruffleBoundary
    fun trace(node: Node?, operation: TraceOp, address: ManagedAddress, count: Long) {
        val context = Language.currentState(node)
        val previous = context.threads.enterForeign()
        try {
            val bytes = if (operation == TraceOp.BINARY) {
                if (count < 0 || count > Int.MAX_VALUE) fault("Invalid binary trace length")
                if (count == 0L) byteArrayOf() else address.withNativeBorrow {
                    address.requireByteRegion(count)
                    ByteArray(count.toInt()) { address.readWord8(it.toLong()).toByte() }
                }
            } else address.withNativeBorrow { cstring(address) }
            val output = context.env.err()
            synchronized(output) {
                try {
                    output.write("[thc trace ${operation.label}] ".toByteArray(Charsets.US_ASCII))
                    for (byte in bytes) {
                        val value = byte.toInt() and 255
                        when {
                            operation == TraceOp.BINARY -> {
                                output.write(HEX[value ushr 4].code); output.write(HEX[value and 15].code)
                            }
                            value == 92 -> { output.write(92); output.write(92) }
                            value < 32 || value == 127 -> {
                                output.write(92); output.write(120)
                                output.write(HEX[value ushr 4].code); output.write(HEX[value and 15].code)
                            }
                            else -> output.write(value)
                        }
                    }
                    output.write(10)
                } catch (_: IOException) { /* Like the existing void RTS diagnostic hooks. */ }
                try { output.flush() } catch (_: IOException) { /* No guest IO exception. */ }
            }
        } finally { context.threads.leaveForeign(previous) }
    }

    @JvmStatic @TruffleBoundary
    fun report(node: Node?, operation: RtsDiagnosticOp, first: Any?, second: Any?) {
        val context = Language.currentState(node)
        val previous = context.threads.enterForeign()
        try {
            val message = when (operation) {
                RtsDiagnosticOp.STACK -> {
                    val thread = first as? GuestThreadId ?: fault("Stack overflow report requires ThreadId#")
                    if (thread.owner !== context.threads) fault("ThreadId# belongs to another guest context")
                    "Stack space overflow (THC guest Java thread ${thread.javaId}; JVM stack limit unavailable)."
                        .toByteArray(Charsets.US_ASCII)
                }
                RtsDiagnosticOp.HEAP ->
                    "Heap exhausted; JVM maximum heap size is ${Runtime.getRuntime().maxMemory()} bytes."
                        .toByteArray(Charsets.US_ASCII)
                RtsDiagnosticOp.ERROR, RtsDiagnosticOp.DEBUG -> {
                    val format = first as? ManagedAddress ?: fault("${operation.symbol} requires a format Addr#")
                    val text = second as? ManagedAddress ?: fault("${operation.symbol} requires a message Addr#")
                    format.withNativeBorrows(text) {
                        // Debug.Trace supplies "%s\n"; Conc.Sync and TopHandler
                        // supply "%s". Other printf formats need a separate ABI.
                        val expected = if (operation == RtsDiagnosticOp.DEBUG) byteArrayOf(37, 115, 10)
                            else byteArrayOf(37, 115)
                        if (!cstring(format).contentEquals(expected))
                            fault("${operation.symbol} supports only its original CString format")
                        cstring(text)
                    }
                }
            }
            // debugBelch2 has no prefix or implicit newline: its admitted format
            // supplies one LF. errorBelch2 appends one LF itself and has no prefix
            // when the embedding has not registered a program name. In both cases
            // preserve message bytes and emit exactly that one trailing LF.
            // Stream errors are not reported by the original void hooks.
            val output = context.env.err()
            synchronized(output) {
                try { output.write(message); output.write(10) }
                catch (_: IOException) { /* Native diagnostic hooks return void on a stream error. */ }
                try { output.flush() } catch (_: IOException) { /* Same void hook contract. */ }
            }
        } finally { context.threads.leaveForeign(previous) }
    }

    private fun cstring(address: ManagedAddress): ByteArray {
        val available = address.availableBytes()
        var length = 0L
        while (length < available && address.readWord8(length) != 0L) length++
        if (length == available) fault("Unterminated diagnostic CString")
        if (length > Int.MAX_VALUE) fault("Diagnostic CString exceeds managed array capacity")
        address.requireByteRegion(length + 1)
        return ByteArray(length.toInt()) { address.readWord8(it.toLong()).toByte() }
    }
    private const val HEX = "0123456789abcdef"
}
