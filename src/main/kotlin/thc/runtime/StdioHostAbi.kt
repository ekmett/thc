// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

/** Actual C errno constants from the build host, not the private service categories. */
internal class StdioHostAbi private constructor(private val errors: Map<String, Long>, private val seek: Map<String, Long>) {
    fun notTerminal(): Long = errors.getValue("ENOTTY")
    fun notSeekable(): Long = errors.getValue("ESPIPE")
    fun seekConstant(operation: OriginalStdioOp): Long = seek.getValue(when (operation) {
        OriginalStdioOp.SEEK_SET -> "SEEK_SET"
        OriginalStdioOp.SEEK_CUR -> "SEEK_CUR"
        OriginalStdioOp.SEEK_END -> "SEEK_END"
        else -> throw RuntimeFault("Invalid original seek constant operation")
    })
    /** C whence values are independent of the private managed 0/1/2 modes. */
    fun seekMode(whence: Long): Long? = when (whence) {
        seek.getValue("SEEK_SET") -> 0L
        seek.getValue("SEEK_CUR") -> 1L
        seek.getValue("SEEK_END") -> 2L
        else -> null
    }
    fun error(kind: Long): Long = errors.getValue(when (kind) {
        1L -> "ENOENT"
        2L -> "EACCES"
        3L -> "EEXIST"
        4L -> "EBADF"
        5L -> "EINVAL"
        7L -> "ENOTSUP"
        8L -> "EBUSY"
        9L -> "EISDIR"
        else -> "EIO"
    })

    companion object {
        private val widths = mapOf("charBits" to 8L, "pointer" to 8L, "int" to 4L, "bool" to 1L, "size" to 8L, "ssize" to 8L)
        private val errorNames = setOf("ENOENT", "EACCES", "EEXIST", "EBADF", "EINVAL", "EIO", "ENOTSUP", "EBUSY", "EISDIR", "ENOTTY", "ESPIPE")
        private val seekNames = setOf("SEEK_SET", "SEEK_CUR", "SEEK_END")
        private fun exactInteger(value: Any?): Long? = if (value is Int || value is Long) (value as Number).toLong() else null
        private fun architecture(value: String): String = when (value.lowercase()) {
            "arm64" -> "aarch64"
            "amd64" -> "x86_64"
            else -> value.lowercase()
        }
        fun parse(value: Any?, system: String, arch: String): StdioHostAbi {
            fun requireAbi(condition: Boolean, detail: String) {
                if (!condition) throw RuntimeFault("Invalid original stdio host ABI: $detail")
            }
            val manifest = value as? Map<*, *> ?: throw RuntimeFault("Missing original stdio host ABI")
            requireAbi(exactInteger(manifest["schema"]) == 1L, "schema")
            val hostArch = architecture(arch)
            requireAbi(system in setOf("Linux", "Darwin") && hostArch in setOf("x86_64", "aarch64"), "unsupported runtime platform")
            requireAbi(manifest["system"] == system && (manifest["architecture"] as? String)?.let(::architecture) == hostArch, "platform mismatch")
            val target = (manifest["target"] as? String)?.split('-') ?: emptyList()
            requireAbi(target.size >= 3 && architecture(target[0]) == hostArch &&
                (if (system == "Linux") target.drop(2) == listOf("linux", "gnu") else target[2].startsWith("darwin")), "compiler target")
            val sizes = manifest["widths"] as? Map<*, *>
            requireAbi(sizes != null && sizes.keys == widths.keys && widths.all { (key, size) -> exactInteger(sizes[key]) == size }, "LP64 widths")
            val rawErrors = manifest["errno"] as? Map<*, *>
            requireAbi(rawErrors != null && rawErrors.keys == errorNames, "errno fields")
            val errors = errorNames.associateWith { name ->
                val number = exactInteger(rawErrors!![name])
                requireAbi(number != null && number in 1L..Int.MAX_VALUE.toLong(), "CInt errno $name")
                number!!
            }
            val rawSeek = manifest["seek"] as? Map<*, *>
            requireAbi(rawSeek != null && rawSeek.keys == seekNames, "seek fields")
            val seek = seekNames.associateWith { name ->
                val number = exactInteger(rawSeek!![name])
                requireAbi(number != null && number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong(), "CInt $name")
                number!!
            }
            requireAbi(seek.values.toSet().size == seekNames.size, "distinct seek constants")
            return StdioHostAbi(errors, seek)
        }
        fun load(): StdioHostAbi {
            val document = StdioHostAbi::class.java.getResourceAsStream("/thc/native/stdio-host-abi.json")?.use {
                thc.Json.parse(it.reader().readText())
            } ?: throw RuntimeFault("Missing generated original stdio host ABI")
            val system = System.getProperty("os.name").let { if (it.startsWith("Mac")) "Darwin" else it }
            return parse(document, system, System.getProperty("os.arch"))
        }
    }
}
