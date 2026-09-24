// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import java.nio.ByteOrder
import java.util.Collections

/** Target-derived ABI of the original GHC stack and InfoProv sources. */
class TargetLayout private constructor(
    val compilerId: String,
    val compilerAbi: String,
    val platform: String,
    val way: String,
    val wordBytes: Int,
    val endianness: String,
    val tablesNextToCode: Boolean,
    private val values: Map<String, Int>,
) {
    fun offset(name: String): Int = values[name] ?: error("Unknown GHC target layout field: $name")

    override fun equals(other: Any?): Boolean = other is TargetLayout &&
        compilerId == other.compilerId && compilerAbi == other.compilerAbi &&
        platform == other.platform && way == other.way &&
        wordBytes == other.wordBytes && endianness == other.endianness &&
        tablesNextToCode == other.tablesNextToCode && values == other.values

    override fun hashCode(): Int = listOf(compilerId, compilerAbi, platform, way,
        wordBytes, endianness, tablesNextToCode, values).hashCode()

    fun document(): Map<String, Any> = mapOf(
        "format" to "thc-target-layout", "schema" to 1,
        "compiler" to mapOf("id" to compilerId, "abi" to compilerAbi,
            "platform" to platform, "way" to way),
        "layout" to (values + mapOf("schema" to 1, "profiled" to false,
            "wordBytes" to wordBytes, "endianness" to endianness,
            "tablesNextToCode" to tablesNextToCode,
            "targetPlatform" to platform)),
    )

    companion object {
        // Exact current Wired.moduleSources HSC inventory. Bundle tests derive
        // receipts from the producer catalog so additions cannot drift silently.
        private val generatedSourcePaths = setOf(
            "GHC/Internal/Heap/Constants.hsc",
            "GHC/Internal/Heap/InfoTable/Types.hsc",
            "GHC/Internal/Heap/InfoTable.hsc",
            "GHC/Internal/Stack/Constants.hsc",
            "GHC/Internal/InfoProv/Types.hsc",
            "GHC/Internal/Stack/CCS.hsc",
            "GHC/Internal/ExecutionStack/Internal.hsc",
        )
        private val numbers = setOf(
            "infoTableBytes", "infoTablePtrsOffset", "infoTablePtrsBytes",
            "infoTableNptrsOffset", "infoTableNptrsBytes", "infoTableTypeOffset",
            "infoTableTypeBytes", "infoTableSrtOffset", "infoTableSrtBytes",
            "infoProvEntBytes", "infoProvBytes", "infoProvEntInfoOffset",
            "infoProvEntProvOffset", "infoProvNameOffset", "infoProvDescOffset",
            "infoProvDescBytes", "infoProvTyDescOffset", "infoProvLabelOffset",
            "infoProvUnitOffset", "infoProvModuleOffset", "infoProvFileOffset",
            "infoProvSpanOffset", "closureRetBco", "closureRetSmall",
            "closureRetBig", "closureRetFun", "closureUpdateFrame",
            "closureCatchFrame", "closureUnderflowFrame", "closureStopFrame",
            "closureStack", "closureAtomicallyFrame", "closureCatchRetryFrame",
            "closureCatchStmFrame", "closureAnnFrame", "stackHeaderBytes",
            "stackCatchHandlerBytes", "stackCatchFrameBytes", "stackCatchStmCodeBytes",
            "stackCatchStmHandlerBytes", "stackCatchStmFrameBytes", "stackUpdateeBytes",
            "stackUpdateFrameBytes", "stackAtomicallyCodeBytes", "stackAtomicallyResultBytes",
            "stackAtomicallyFrameBytes", "stackCatchRetryAltCodeBytes",
            "stackCatchRetryFirstCodeBytes", "stackCatchRetryAltBytes",
            "stackCatchRetryFrameBytes", "stackRetFunSizeBytes", "stackRetFunFunBytes",
            "stackRetFunPayloadBytes", "stackRetFunFrameBytes", "stackAnnPayloadBytes",
            "stackAnnFrameBytes", "stackClosurePayloadBytes",
        )
        private val ordinals = mapOf(
            "closureRetBco" to 29, "closureRetSmall" to 30,
            "closureRetBig" to 31, "closureRetFun" to 32,
            "closureUpdateFrame" to 33, "closureCatchFrame" to 34,
            "closureUnderflowFrame" to 35, "closureStopFrame" to 36,
            "closureStack" to 53, "closureAtomicallyFrame" to 55,
            "closureCatchRetryFrame" to 56, "closureCatchStmFrame" to 57,
            "closureAnnFrame" to 65,
        )

        private fun hostPlatform(): String {
            val arch = when (System.getProperty("os.arch").lowercase()) {
                "aarch64", "arm64" -> "aarch64"
                "x86_64", "amd64" -> "x86_64"
                else -> error("Unsupported GHC target architecture: ${System.getProperty("os.arch")}")
            }
            val os = when {
                System.getProperty("os.name").startsWith("Mac") -> "osx"
                System.getProperty("os.name").startsWith("Linux") -> "linux"
                else -> error("Unsupported GHC target OS: ${System.getProperty("os.name")}")
            }
            return "$arch-$os"
        }

        private fun int(value: Any?, field: String): Int {
            require(value is Int || value is Long) { "Invalid GHC target layout field: $field" }
            val long = (value as Number).toLong()
            require(long in 0..Int.MAX_VALUE.toLong()) { "Out-of-range GHC target layout field: $field" }
            return long.toInt()
        }

        private fun fromParts(compiler: Map<*, *>, layout: Map<*, *>): TargetLayout {
            require(compiler.keys == setOf("id", "abi", "platform", "way")) {
                "Incomplete GHC target compiler identity"
            }
            val id = compiler["id"] as? String
            val abi = compiler["abi"] as? String
            val platform = compiler["platform"] as? String
            val way = compiler["way"] as? String
            require(id == "ghc-9.14.1" && !abi.isNullOrBlank() &&
                abi.matches(Regex("[A-Za-z0-9._+-]+")) && platform == hostPlatform() &&
                way == "dynamic-nonprofiling") { "GHC target identity or way differs from this runtime" }
            require(layout.keys == numbers + setOf("schema", "profiled", "wordBytes", "endianness",
                "targetPlatform", "tablesNextToCode") && int(layout["schema"], "schema") == 1 &&
                layout["profiled"] == false && layout["targetPlatform"] == platform) {
                "Incomplete or profiled GHC target layout"
            }
            val tablesNextToCode = layout["tablesNextToCode"] as? Boolean
                ?: error("Missing GHC tables-next-to-code setting")
            val word = int(layout["wordBytes"], "wordBytes")
            val endian = layout["endianness"] as? String
            val nativeEndian = if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) "little" else "big"
            require(word == Long.SIZE_BYTES && endian == nativeEndian) {
                "GHC word width or endianness differs from this runtime"
            }
            val values = numbers.associateWith { int(layout[it], it) }
            fun v(name: String) = values.getValue(name)
            fun field(struct: String, offset: String, width: Int) {
                require(width > 0 && v(offset) <= v(struct) - width) {
                    "GHC target layout $offset exceeds $struct"
                }
            }
            for ((offset, width) in listOf(
                "infoTablePtrsOffset" to "infoTablePtrsBytes",
                "infoTableNptrsOffset" to "infoTableNptrsBytes",
                "infoTableTypeOffset" to "infoTableTypeBytes",
                "infoTableSrtOffset" to "infoTableSrtBytes",
            )) field("infoTableBytes", offset, v(width))
            field("infoProvEntBytes", "infoProvEntInfoOffset", word)
            field("infoProvEntBytes", "infoProvEntProvOffset", v("infoProvBytes"))
            for (offset in listOf("infoProvNameOffset", "infoProvTyDescOffset",
                "infoProvLabelOffset", "infoProvUnitOffset", "infoProvModuleOffset",
                "infoProvFileOffset", "infoProvSpanOffset"))
                field("infoProvBytes", offset, word)
            field("infoProvBytes", "infoProvDescOffset", v("infoProvDescBytes"))
            for ((frame, offsets) in listOf(
                "stackCatchFrameBytes" to listOf("stackCatchHandlerBytes"),
                "stackCatchStmFrameBytes" to listOf("stackCatchStmCodeBytes", "stackCatchStmHandlerBytes"),
                "stackUpdateFrameBytes" to listOf("stackUpdateeBytes"),
                "stackAtomicallyFrameBytes" to listOf("stackAtomicallyCodeBytes", "stackAtomicallyResultBytes"),
                "stackCatchRetryFrameBytes" to listOf("stackCatchRetryAltCodeBytes",
                    "stackCatchRetryFirstCodeBytes", "stackCatchRetryAltBytes"),
                "stackAnnFrameBytes" to listOf("stackAnnPayloadBytes"),
                "stackRetFunFrameBytes" to listOf("stackRetFunSizeBytes", "stackRetFunFunBytes"),
            )) {
                require(v(frame) >= v("stackHeaderBytes")) { "Invalid GHC stack frame size: $frame" }
                offsets.forEach { field(frame, it, word) }
            }
            require(v("stackRetFunPayloadBytes") <= v("stackRetFunFrameBytes") &&
                v("stackClosurePayloadBytes") >= v("stackHeaderBytes")) {
                "Invalid GHC stack payload offset"
            }
            require(ordinals.all { (name, expected) -> v(name) == expected }) {
                "GHC closure ordinals differ from 9.14.1"
            }
            return TargetLayout(id, abi, platform, way, word, endian, tablesNextToCode,
                Collections.unmodifiableMap(LinkedHashMap(values)))
        }

        fun fromDocument(value: Any?): TargetLayout {
            val record = value as? Map<*, *> ?: error("Invalid target layout document")
            require(record.keys == setOf("format", "schema", "compiler", "layout") &&
                record["format"] == "thc-target-layout" && int(record["schema"], "schema") == 1) {
                "Invalid target layout document"
            }
            return fromParts(record["compiler"] as? Map<*, *> ?: error("Missing target compiler"),
                record["layout"] as? Map<*, *> ?: error("Missing target layout"))
        }

        fun fromReceipts(index: Map<*, *>, inputs: Map<*, *>): TargetLayout? {
            val layout = index["targetLayout"]
            val receipt = inputs["targetLayout"]
            if (layout == null && receipt == null) return null
            require(layout != null && layout == receipt &&
                index["generatedSources"] == inputs["generatedSources"]) {
                "Wired target layout and generated-source receipts differ"
            }
            val generated = index["generatedSources"] as? List<*>
                ?: error("Missing generated GHC source receipts")
            require(generated.size == generatedSourcePaths.size && generated.all { item ->
                val source = item as? Map<*, *> ?: return@all false
                val path = source["path"] as? String
                val sha = source["sha256"] as? String
                source.keys == setOf("path", "sha256") && path in generatedSourcePaths &&
                    sha?.matches(Regex("[0-9a-f]{64}")) == true
            } && generated.map { (it as Map<*, *>)["path"] }.toSet() == generatedSourcePaths) {
                "Invalid generated GHC source receipts"
            }
            return fromParts(inputs["compiler"] as? Map<*, *> ?: error("Missing target compiler"),
                layout as? Map<*, *> ?: error("Invalid GHC target layout"))
        }
    }
}
