// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import thc.Json
import java.nio.ByteOrder

/** The supported original Linux GNU LP64 stat image ABI, not a host pointer or
 * a simulated fstat. Images are supplied by the caller; descriptor metadata is
 * deliberately not synthesized from paths or stale open-time permissions. */
internal class PosixStat private constructor(val size: Long, private val fields: Map<String, Field>,
    private val types: Map<String, Long>) {
    private data class Field(val offset: Long, val width: Int)

    fun field(name: String, address: ManagedAddress): Long {
        val field = fields.getValue(name)
        address.requireRange(field.offset, field.width.toLong())
        // readWord8 preserves managed pointer-cell protection, unlike rawBacking.
        var value = 0L
        val little = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
        for (index in 0 until field.width) {
            val shift = (if (little) index else field.width - 1 - index) * 8
            value = value or (address.readWord8(field.offset + index) shl shift)
        }
        return value
    }

    fun isType(name: String, mode: Long): Long {
        if (mode != (mode and 0xffffffffL)) fault("Original stat predicate requires canonical Word32# mode")
        return if ((mode and types.getValue("mask")) == types.getValue(name)) 1L else 0L
    }

    companion object {
        private fun requireAbi(condition: Boolean, detail: String) {
            if (!condition) throw RuntimeFault("Unsupported original stat ABI: $detail")
        }
        private fun integer(value: Any?): Long {
            requireAbi(value is Int || value is Long, "exact integer required")
            return (value as Number).toLong()
        }
        private fun architecture(value: String) = if (value == "amd64") "x86_64" else if (value == "arm64") "aarch64" else value
        internal fun parse(raw: Any?, system: String, arch: String): PosixStat {
            val document = raw as? Map<*, *> ?: throw RuntimeFault("Unsupported original stat ABI: manifest")
            val architecture = architecture(arch)
            requireAbi(integer(document["schema"]) == 1L && system == "Linux" &&
                architecture in setOf("x86_64", "aarch64") && document["system"] == system &&
                document["architecture"] == architecture && document["target"] == "$architecture-unknown-linux-gnu",
                "native Linux GNU LP64 target required")
            val probe = document["stat"] as? Map<*, *> ?: throw RuntimeFault("Unsupported original stat ABI: stat layout")
            requireAbi(probe.keys == setOf("size", "alignment", "fields", "types"), "layout fields")
            val size = integer(probe["size"])
            val alignment = integer(probe["alignment"])
            requireAbi(size in 1L..4096L && alignment == 8L && size % alignment == 0L, "size/alignment")
            val rawFields = probe["fields"] as? Map<*, *> ?: throw RuntimeFault("Unsupported original stat ABI: fields")
            val widths = mapOf("st_dev" to 8, "st_ino" to 8, "st_mode" to 4, "st_size" to 8)
            requireAbi(rawFields.keys == widths.keys, "exact member set")
            val fields = widths.mapValues { (name, width) ->
                val field = rawFields[name] as? Map<*, *> ?: throw RuntimeFault("Unsupported original stat ABI: member")
                requireAbi(field.keys == setOf("offset", "width") && integer(field["width"]) == width.toLong(), "member width")
                val offset = integer(field["offset"])
                requireAbi(offset >= 0 && offset <= size - width && offset % width == 0L, "member offset")
                Field(offset, width)
            }
            val bytes = fields.values.flatMap { field -> (field.offset until field.offset + field.width).toList() }
            requireAbi(bytes.toSet().size == bytes.size, "overlapping members")
            val rawTypes = probe["types"] as? Map<*, *> ?: throw RuntimeFault("Unsupported original stat ABI: types")
            val names = setOf("mask", "regular", "character", "block", "directory", "fifo", "socket")
            requireAbi(rawTypes.keys == names, "exact file type set")
            val types = names.associateWith { integer(rawTypes[it]) }
            requireAbi(types.values.all { it in 1L..0xffffffffL } && types.values.toSet().size == types.size &&
                types.filterKeys { it != "mask" }.values.all { it and types.getValue("mask") == it }, "file type masks")
            return PosixStat(size, fields, types)
        }
        private val host by lazy {
            val resource = PosixStat::class.java.getResourceAsStream("/thc/native/posix-stat-abi.json")
                ?: throw RuntimeFault("Missing native stat ABI probe")
            resource.use { parse(Json.parse(it.reader().readText()), System.getProperty("os.name"), System.getProperty("os.arch")) }
        }
        @JvmStatic @TruffleBoundary fun execute(operation: OriginalStdioOp, address: ManagedAddress, mode: Long): Long {
            val abi = host
            return if (operation == OriginalStdioOp.SIZEOF_STAT) abi.size
            else if (operation == OriginalStdioOp.ST_DEV) abi.field("st_dev", address)
            else if (operation == OriginalStdioOp.ST_INO) abi.field("st_ino", address)
            else if (operation == OriginalStdioOp.ST_MODE) abi.field("st_mode", address)
            else if (operation == OriginalStdioOp.ST_SIZE) abi.field("st_size", address)
            else if (operation == OriginalStdioOp.IS_REG) abi.isType("regular", mode)
            else if (operation == OriginalStdioOp.IS_CHR) abi.isType("character", mode)
            else if (operation == OriginalStdioOp.IS_BLK) abi.isType("block", mode)
            else if (operation == OriginalStdioOp.IS_DIR) abi.isType("directory", mode)
            else if (operation == OriginalStdioOp.IS_FIFO) abi.isType("fifo", mode)
            else if (operation == OriginalStdioOp.IS_SOCK) abi.isType("socket", mode)
            else fault("Invalid original stat operation")
        }
    }
}
