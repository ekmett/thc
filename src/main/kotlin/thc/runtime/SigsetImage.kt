// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import thc.Json

/** Only caller-owned sigset_t image operations, never a host signal mask. */
internal class SigsetImage private constructor(val size: Long, private val clearBytes: List<Long>,
    private val signalBits: List<Long>, private val invalidErrno: Long) {
    fun apply(operation: OriginalStdioOp, address: ManagedAddress, signal: Long, stdio: ManagedStdio): Long {
        if (!operation.sigset) fault("Invalid sigset image operation")
        if (signal != signal.toInt().toLong()) fault("Original sigaddset requires a canonical signed CInt")
        fun checked(): Long {
            address.requireByteRegion(size, true)
            if (operation == OriginalStdioOp.SIGEMPTYSET) {
                for (offset in clearBytes) address.writeWord8(offset, 0)
                return 0
            }
            val bit = if (signal in 1L..signalBits.size.toLong()) signalBits[signal.toInt() - 1] else -1L
            if (bit < 0) { stdio.nativeError(invalidErrno); return -1 }
            val offset = bit / 8
            address.writeWord8(offset, address.readWord8(offset) or (1L shl (bit.toInt() % 8)))
            return 0
        }
        val owner = address.cbitsOwner()
        return if (owner == null) checked() else synchronized(owner) { checked() }
    }

    companion object {
        internal fun parse(raw: Any?, system: String, arch: String): SigsetImage {
            fun requireAbi(value: Boolean) { if (!value) fault("Invalid original Linux sigset image ABI") }
            fun integer(value: Any?): Long {
                requireAbi(value is Long || value is Int)
                return (value as Number).toLong()
            }
            val doc = raw as? Map<*, *> ?: fault("Missing sigset image ABI")
            val architecture = if (arch == "amd64") "x86_64" else arch
            requireAbi(doc.keys == setOf("schema", "system", "architecture", "target", "sourceSha256", "sigset") &&
                integer(doc["schema"]) == 1L && system == "Linux" && architecture == "x86_64" &&
                doc["system"] == system && doc["architecture"] == architecture && doc["target"] == "x86_64-unknown-linux-gnu" &&
                (doc["sourceSha256"] as? String)?.matches(Regex("[0-9a-f]{64}")) == true)
            val layout = doc["sigset"] as? Map<*, *> ?: fault("Missing sigset image layout")
            requireAbi(layout.keys == setOf("size", "alignment", "invalidErrno", "clearBytes", "signalBits"))
            val size = integer(layout["size"])
            val errno = integer(layout["invalidErrno"])
            requireAbi(size in 1L..4096L && integer(layout["alignment"]) == 8L && size % 8 == 0L &&
                errno in 1L..Int.MAX_VALUE.toLong())
            val clear = (layout["clearBytes"] as? List<*>)?.map(::integer) ?: fault("Missing sigset clear-byte table")
            val bits = (layout["signalBits"] as? List<*>)?.map(::integer) ?: fault("Missing sigset signal-bit table")
            val valid = bits.filter { it >= 0 }
            requireAbi(clear.isNotEmpty() && clear.size <= size && clear == clear.distinct().sorted() &&
                clear.all { it in 0 until size } && bits.size in 1..128 && valid.isNotEmpty() &&
                valid.size == valid.distinct().size && bits.all { it == -1L || it in 0 until size * 8 } &&
                valid.all { it / 8 in clear })
            return SigsetImage(size, clear, bits, errno)
        }
        private val host by lazy {
            val stream = SigsetImage::class.java.getResourceAsStream("/thc/native/sigset-abi.json")
                ?: fault("Missing sigset image ABI probe")
            stream.use { parse(Json.parse(it.reader().readText()), System.getProperty("os.name"), System.getProperty("os.arch")) }
        }
        @JvmStatic @TruffleBoundary fun execute(operation: OriginalStdioOp, address: ManagedAddress,
            signal: Long, stdio: ManagedStdio): Long = host.apply(operation, address, signal, stdio)
    }
}
