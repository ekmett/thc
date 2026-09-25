// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import java.nio.ByteOrder
import thc.Json

/** Actual header layout for caller-owned managed images, not terminal state or
 * a native pointer. No file descriptor or host terminal is observed here. */
internal class TermiosImage private constructor(val size: Long, private val lflagOffset: Long,
    private val ccOffset: Long, private val constants: Map<String, Long>) {
    private fun <T> image(address: ManagedAddress, writable: Boolean = false, body: () -> T): T {
        fun checked(): T { address.requireByteRegion(size, writable); return body() }
        val allocation = address.cbitsOwner()
        return if (allocation == null) checked() else synchronized(allocation) { checked() }
    }
    fun lflag(address: ManagedAddress): Long = image(address) {
        var result = 0L
        for (index in 0..3) result = result or (address.readWord8(lflagOffset + index) shl shift(index))
        result
    }
    fun poke(address: ManagedAddress, value: Long) {
        if (value !in 0L..0xffffffffL) fault("Original termios setter requires canonical Word32#")
        image(address, true) {
            for (index in 0..3) address.writeWord8(lflagOffset + index, (value ushr shift(index)) and 255L)
        }
    }
    fun cc(address: ManagedAddress): ManagedAddress = image(address) { address.plus(ccOffset) }
    fun constant(operation: OriginalStdioOp): Long = if (operation == OriginalStdioOp.SIZEOF_TERMIOS) size
        else constants.getValue(if (operation == OriginalStdioOp.ECHO) "echo"
        else if (operation == OriginalStdioOp.ICANON) "icanon"
        else if (operation == OriginalStdioOp.VMIN) "vmin"
        else if (operation == OriginalStdioOp.VTIME) "vtime"
        else if (operation == OriginalStdioOp.TCSANOW) "tcsanow" else fault("Invalid termios constant"))
    private fun shift(index: Int) = (if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) index else 3 - index) * 8

    companion object {
        internal fun parse(raw: Any?, system: String, arch: String): TermiosImage {
            fun requireAbi(condition: Boolean) { if (!condition) fault("Invalid original Linux termios ABI") }
            fun integer(value: Any?): Long {
                requireAbi(value is Long || value is Int)
                return (value as Number).toLong()
            }
            val doc = raw as? Map<*, *> ?: fault("Missing termios ABI")
            requireAbi(doc.keys == setOf("schema", "system", "architecture", "target", "sourceSha256", "termios") &&
                (doc["sourceSha256"] as? String)?.matches(Regex("[0-9a-f]{64}")) == true)
            val architecture = if (arch == "amd64") "x86_64" else arch
            requireAbi(integer(doc["schema"]) == 1L && system == "Linux" && architecture == "x86_64" &&
                doc["system"] == system && doc["architecture"] == architecture && doc["target"] == "x86_64-unknown-linux-gnu")
            val layout = doc["termios"] as? Map<*, *> ?: fault("Missing termios layout")
            val names = setOf("size", "alignment", "lflagOffset", "lflagBytes", "ccOffset", "ccBytes", "ccCount",
                "echo", "icanon", "vmin", "vtime", "tcsanow")
            requireAbi(layout.keys == names)
            val values = names.associateWith { integer(layout[it]) }
            val size = values.getValue("size"); val lflag = values.getValue("lflagOffset")
            val cc = values.getValue("ccOffset"); val count = values.getValue("ccCount")
            requireAbi(size in 4L..4096L && values.getValue("alignment") == 4L && size % 4 == 0L &&
                values.getValue("lflagBytes") == 4L && values.getValue("ccBytes") == 1L &&
                lflag >= 0 && lflag <= size - 4 && lflag % 4 == 0L && count in 1L..size &&
                cc >= 0 && cc <= size - count && (lflag + 4 <= cc || cc + count <= lflag))
            val constants = values.filterKeys { it in setOf("echo", "icanon", "vmin", "vtime", "tcsanow") }
            requireAbi(constants.values.all { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() } &&
                constants.getValue("echo") > 0 && constants.getValue("icanon") > 0 &&
                constants.getValue("vmin") in 0 until count && constants.getValue("vtime") in 0 until count &&
                constants.getValue("vmin") != constants.getValue("vtime"))
            return TermiosImage(size, lflag, cc, constants)
        }
        private val host by lazy {
            val stream = TermiosImage::class.java.getResourceAsStream("/thc/native/termios-abi.json") ?: fault("Missing termios ABI probe")
            stream.use { parse(Json.parse(it.reader().readText()), System.getProperty("os.name"), System.getProperty("os.arch")) }
        }
        @JvmStatic @TruffleBoundary fun scalar(operation: OriginalStdioOp, address: ManagedAddress, value: Long): Long {
            if (operation == OriginalStdioOp.LFLAG) return host.lflag(address)
            if (operation == OriginalStdioOp.POKE_LFLAG) { host.poke(address, value); return 0L }
            return host.constant(operation)
        }
        @JvmStatic @TruffleBoundary fun pointer(address: ManagedAddress): ManagedAddress = host.cc(address)
    }
}
