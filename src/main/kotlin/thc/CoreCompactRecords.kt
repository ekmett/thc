// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.math.BigInteger
import java.util.Collections
import thc.runtime.CoreFloatingLiteral

/** Decodes only selected typed records into the existing lowering input. The
 * wire has Core-specific records, not a second generic object serialization. */
internal class CoreCompactRecords(private val file: CoreCompactFile, private val identity: String) {
    data class Origin(val container: String, val dataOffset: Long,
        val bindingOffset: Long = dataOffset, internal val debug: CoreCompactDebug? = null)
    private val debug = CoreCompactDebug(file)
    private var bindingOffset = 0L
    private fun origin(offset: Long) = Origin(identity, offset, bindingOffset, debug)
    private object Missing
    private data class Shape(val fields: Map<String, Any?>)
    private val shapes = HashMap<Long, Shape>()
    private val readingShapes = HashSet<Long>()
    private val strings = HashMap<Pair<Long, Long>, String>()

    private fun text(cursor: CoreCompactCursor): String {
        val span = cursor.unsigned() to cursor.unsigned()
        return strings.getOrPut(span) { file.string(span.first, span.second) }
    }
    private fun ordinal(cursor: CoreCompactCursor) = "\u0000compact-local:${cursor.unsigned()}"
    private fun id(cursor: CoreCompactCursor): String = when (val tag = cursor.byte()) {
        0 -> text(cursor)
        1 -> ordinal(cursor)
        else -> error("Invalid compact Core identity tag: $tag")
    }
    private fun entry(cursor: CoreCompactCursor, target: MutableMap<String, Any?>) {
        when (val tag = cursor.byte()) {
            0 -> Unit
            1 -> target["type"] = "IO ()"
            2 -> target["type"] = "State# RealWorld"
            else -> error("Invalid compact Core entry type: $tag")
        }
    }
    private inline fun <T> list(cursor: CoreCompactCursor, read: () -> T): List<T> = List(cursor.count()) { read() }
    private inline fun <T> presence(cursor: CoreCompactCursor, read: () -> T): Any? = when (val tag = cursor.byte()) {
        0 -> Missing
        1 -> null
        2 -> read()
        else -> error("Invalid compact Core presence tag: $tag")
    }
    private inline fun <T> field(cursor: CoreCompactCursor, target: MutableMap<String, Any?>, key: String, read: () -> T) {
        val value = presence(cursor, read)
        if (value !== Missing) target[key] = value
    }
    private inline fun <T> element(cursor: CoreCompactCursor, read: () -> T): Any? = presence(cursor, read).also {
        require(it !== Missing) { "Missing compact Core array element" }
    }
    private fun enum(cursor: CoreCompactCursor, values: List<String>): String = values.getOrNull(cursor.byte())
        ?: error("Invalid compact Core enumeration")
    private fun vector(cursor: CoreCompactCursor) = mapOf("lanes" to cursor.unsigned(), "element" to enum(cursor, ELEMENTS))
    private fun primitive(cursor: CoreCompactCursor): String {
        val tag = cursor.byte()
        if (tag == 16) {
            val vector = vector(cursor)
            return "VecRep ${vector["lanes"]} ${vector["element"]}"
        }
        return PRIMITIVES.getOrNull(tag) ?: error("Invalid compact Core primitive representation: $tag")
    }

    private fun shapeUse(cursor: CoreCompactCursor): Shape {
        val at = cursor.position
        return when (val tag = cursor.byte()) {
            0 -> {
                require(readingShapes.add(at)) { "Cyclic compact Core shape reference" }
                try {
                    shape(cursor, false).also { decoded ->
                        val previous = shapes.putIfAbsent(at, decoded)
                        require(previous == null || previous == decoded) { "Inconsistent compact Core shape definition" }
                    }
                } finally { readingShapes.remove(at) }
            }
            1 -> {
                val target = cursor.unsigned()
                require(target < at && target !in readingShapes) { "Forward or cyclic compact Core shape reference" }
                shapes[target] ?: file.data(target) { selected ->
                    require(selected.byte() == 0) { "Compact Core shape reference is not a definition" }
                    require(readingShapes.add(target)) { "Cyclic compact Core shape reference" }
                    try { shape(selected, false).also { shapes[target] = it } }
                    finally { readingShapes.remove(target) }
                }
            }
            else -> error("Invalid compact Core shape tag: $tag")
        }
    }
    private fun shape(cursor: CoreCompactCursor, inline: Boolean): Shape {
        val fields = linkedMapOf<String, Any?>("kind" to enum(cursor, KINDS))
        field(cursor, fields, "primReps") { list(cursor) { primitive(cursor) } }
        field(cursor, fields, "vector") { vector(cursor) }
        field(cursor, fields, "aggregate") { enum(cursor, listOf("unboxed-tuple", "unboxed-sum")) }
        for (key in listOf("components", "alternatives")) field(cursor, fields, key) {
            list(cursor) { if (inline) shape(cursor, true) else shapeUse(cursor) }
        }
        field(cursor, fields, "tagSlot") { cursor.unsigned() }
        field(cursor, fields, "alternativeSlots") { list(cursor) { list(cursor) { cursor.unsigned() } } }
        return Shape(Collections.unmodifiableMap(fields))
    }
    private fun evaluation(cursor: CoreCompactCursor, shape: Shape): Map<String, Any?> {
        val result = LinkedHashMap(shape.fields)
        field(cursor, result, "evaluated") { cursor.boolean() }
        for (key in listOf("components", "alternatives")) {
            val children = shape.fields[key] as? List<*> ?: continue
            result[key] = children.map { evaluation(cursor, it as Shape) }
        }
        return result
    }
    private fun rep(cursor: CoreCompactCursor, inline: Boolean = false) =
        evaluation(cursor, if (inline) shape(cursor, true) else shapeUse(cursor))

    internal fun representation(offset: Long): Map<String, Any?> = file.data(offset) { rep(it) }

    private fun info(cursor: CoreCompactCursor): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        field(cursor, result, "joinArity") { cursor.unsigned() }
        field(cursor, result, "cbvEligible") { cursor.boolean() }
        field(cursor, result, "cbvMarks") { list(cursor) { cursor.boolean() } }
        return result
    }
    private fun binder(cursor: CoreCompactCursor): Map<String, Any?> {
        val origin = origin(cursor.position)
        val id = ordinal(cursor)
        val result = linkedMapOf<String, Any?>("id" to id, "name" to id, "compactOrigin" to origin)
        entry(cursor, result)
        field(cursor, result, "lifted") { cursor.boolean() }
        field(cursor, result, "coercion") { cursor.boolean() }
        field(cursor, result, "rep") { rep(cursor) }
        field(cursor, result, "info") { info(cursor) }
        return result
    }
    fun binding(offset: Long): Map<String, Any?> = file.data(offset) {
        val previous = bindingOffset
        bindingOffset = offset
        try {
            binding(it).also { binding ->
                require(!(binding["id"] as String).startsWith("\u0000compact-local:")) {
                    "Compact top-level binding has local identity"
                }
            }
        } finally { bindingOffset = previous }
    }
    private fun binding(cursor: CoreCompactCursor): Map<String, Any?> {
        val origin = origin(cursor.position)
        val id = id(cursor)
        val result = linkedMapOf<String, Any?>("id" to id, "name" to id, "compactOrigin" to origin)
        entry(cursor, result)
        field(cursor, result, "lifted") { cursor.boolean() }
        result["arity"] = cursor.unsigned()
        field(cursor, result, "rep") { rep(cursor) }
        field(cursor, result, "info") { info(cursor) }
        field(cursor, result, "entryStrict") { list(cursor) { cursor.boolean() } }
        field(cursor, result, "entryStrictSource") { text(cursor) }
        field(cursor, result, "joinValueArity") { cursor.unsigned() }
        field(cursor, result, "joinResultRep") { rep(cursor) }
        result["expr"] = expression(cursor)
        return result
    }
    private fun demand(cursor: CoreCompactCursor) = mapOf("arity" to cursor.unsigned(),
        "strictArgs" to list(cursor) { cursor.boolean() })
    private fun family(cursor: CoreCompactCursor): Map<String, Any?> = mapOf("typeConstructor" to text(cursor),
        "constructors" to list(cursor) { text(cursor) })
    private fun tagFamily(cursor: CoreCompactCursor) = family(cursor) + mapOf("smallFamilyLimit" to cursor.unsigned(),
        "smallFamily" to cursor.boolean())
    private fun foreign(cursor: CoreCompactCursor): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>("schema" to cursor.unsigned())
        result["target"] = when (val tag = cursor.byte()) {
            0 -> linkedMapOf<String, Any?>("kind" to "static", "symbol" to text(cursor)).also { target ->
                field(cursor, target, "unit") { text(cursor) }
                target["isFunction"] = cursor.boolean()
            }
            1 -> mapOf("kind" to "dynamic")
            else -> error("Invalid compact Core foreign target: $tag")
        }
        result["convention"] = enum(cursor, listOf("ccall", "capi", "stdcall", "prim", "javascript"))
        result["safety"] = enum(cursor, listOf("unsafe", "safe", "interruptible"))
        result["arity"] = cursor.unsigned()
        result["suppliedArity"] = cursor.unsigned()
        result["argumentReps"] = list(cursor) { rep(cursor) }
        result["resultRep"] = rep(cursor)
        field(cursor, result, "intrinsic") { text(cursor) }
        field(cursor, result, "javascriptSource") { text(cursor) }
        return result
    }
    private fun meta(cursor: CoreCompactCursor, origin: Origin): MutableMap<String, Any?> {
        val result = linkedMapOf<String, Any?>("compactOrigin" to origin)
        field(cursor, result, "rep") { rep(cursor) }
        field(cursor, result, "resultRep") { rep(cursor) }
        field(cursor, result, "entryStrict") { list(cursor) { cursor.boolean() } }
        field(cursor, result, "entryStrictSource") { text(cursor) }
        field(cursor, result, "callDemand") { demand(cursor) }
        field(cursor, result, "foreignCall") { foreign(cursor) }
        field(cursor, result, "exceptionPayload") { mapOf("schema" to cursor.unsigned(), "type" to text(cursor)) }
        field(cursor, result, "enumFamily") { family(cursor) }
        field(cursor, result, "dataToTagFamily") { tagFamily(cursor) }
        field(cursor, result, "unsafeEqualityCase") { text(cursor) }
        return result
    }
    private fun expression(cursor: CoreCompactCursor): List<Any?> {
        val origin = origin(cursor.position)
        val tag = cursor.byte()
        require(tag in 0..8) { "Invalid compact Core expression tag: $tag" }
        val metadata = meta(cursor, origin)
        val fields: List<Any?> = when (tag) {
            0 -> listOf("var", id(cursor))
            1 -> listOf("prim", text(cursor))
            2 -> listOf("lit") + literal(cursor)
            3 -> listOf("lam", list(cursor) { binder(cursor) }, expression(cursor))
            4 -> listOf("con", text(cursor), cursor.unsigned())
            5 -> listOf("app", expression(cursor), list(cursor) { expression(cursor) },
                list(cursor) { element(cursor) { cursor.boolean() } },
                cursor.boolean(), cursor.boolean())
            6 -> listOf("let", cursor.boolean(), list(cursor) { binding(cursor) }, expression(cursor))
            7 -> {
                val scrutinee = expression(cursor)
                val binderId = ordinal(cursor)
                field(cursor, metadata, "binder") { binder(cursor).also {
                    require(it["id"] == binderId) { "Compact case binder identity disagrees" }
                } }
                listOf("case", scrutinee, binderId, list(cursor) { alternative(cursor) })
            }
            8 -> listOf("void")
            else -> error("Invalid compact Core expression tag: $tag")
        }
        return fields + metadata
    }
    private fun alternative(cursor: CoreCompactCursor): List<Any?> {
        val (kind, discriminator) = when (val tag = cursor.byte()) {
            0 -> "default" to null
            1 -> "data" to text(cursor)
            2 -> "lit" to literal(cursor)
            else -> error("Invalid compact Core alternative tag: $tag")
        }
        val binders = list(cursor) { binder(cursor) }
        return listOf(kind, discriminator, binders.map { it["id"] }, expression(cursor), mapOf("binders" to binders))
    }
    private fun literal(cursor: CoreCompactCursor): List<Any?> {
        val tag = cursor.byte()
        val kind = LITERALS.getOrNull(tag) ?: error("Invalid compact Core literal tag: $tag")
        val value: Any = when (tag) {
            0, 2, 3, 4, 5 -> cursor.signed().also {
                val width = when (tag) { 2 -> 8; 3 -> 16; 4 -> 32; else -> 64 }
                require(width == 64 || it in -(1L shl (width - 1)) until (1L shl (width - 1))) { "Out-of-range compact signed literal" }
            }.toString()
            1, 6, 7, 8, 9 -> cursor.unsignedBits().also {
                val width = when (tag) { 6 -> 8; 7 -> 16; 8 -> 32; else -> 64 }
                require(width == 64 || it >= 0 && it < (1L shl width)) { "Out-of-range compact unsigned literal" }
            }.let(java.lang.Long::toUnsignedString)
            10 -> {
                val magnitude = cursor.bytes(cursor.count())
                require(magnitude.isEmpty() || magnitude.last() != 0.toByte()) { "Noncanonical compact BigNat magnitude" }
                if (magnitude.isEmpty()) "0" else BigInteger(1, magnitude.reversedArray()).toString()
            }
            11 -> cursor.unsigned().also { require(it <= 0x10ffff) { "Invalid compact Char code point" } }.toString()
            12 -> cursor.bytes(cursor.count()).joinToString("") { "%02x".format(it) }
            13 -> CoreFloatingLiteral.Single(cursor.u32().toInt())
            14 -> CoreFloatingLiteral.Double(cursor.fixedBits())
            15 -> "0"
            16 -> primitive(cursor)
            17, 18 -> text(cursor)
            else -> error("Unreachable compact literal tag")
        }
        return listOf(kind, value)
    }

    fun constructor(cursor: CoreCompactCursor): Map<String, Any?> {
        val id = text(cursor)
        val result = linkedMapOf<String, Any?>("id" to id, "name" to id, "arity" to cursor.unsigned(),
            "tag" to cursor.unsigned(), "kind" to enum(cursor, listOf("boxed", "unboxed-tuple", "unboxed-sum", "newtype")),
            "strictFields" to list(cursor) { cursor.boolean() },
            "fieldLifted" to list(cursor) { element(cursor) { cursor.boolean() } },
            "fieldReps" to list(cursor) { element(cursor) { list(cursor) { primitive(cursor) } } },
            "fieldTypes" to list(cursor) { rep(cursor, true) })
        field(cursor, result, "sumArity") { cursor.unsigned() }
        field(cursor, result, "enumFamily") { family(cursor) }
        field(cursor, result, "dataToTagFamily") { tagFamily(cursor) }
        return result
    }

    /** Header constructors use inline shapes: admission never reads a body to
     * discover its module's identity, layout or foreign obligations. */
    fun header(): Map<String, Any?> = file.facts { cursor ->
        val result = linkedMapOf<String, Any?>("schema" to cursor.unsigned(),
            "ghc" to text(cursor), "unit" to text(cursor), "module" to text(cursor),
            "boundary" to text(cursor))
        field(cursor, result, "providedModules") { list(cursor) { text(cursor) } }
        field(cursor, result, "targetLayout") { targetLayout(cursor) }
        result["constructors"] = list(cursor) { constructor(cursor) }
        field(cursor, result, "foreign") { artifacts(cursor) }
        field(cursor, result, "foreignExceptionBridge") {
            linkedMapOf<String, Any?>("schema" to cursor.unsigned()).also { bridge ->
                for (key in listOf("unit", "module", "box", "project", "payloadType", "exceptionType"))
                    bridge[key] = text(cursor)
            }
        }
        field(cursor, result, "foreignExceptionBridgeUnit") { text(cursor) }
        for (key in listOf("foreignLink", "staticForeignImportStubs", "staticForeignImports",
            "staticForeignExports", "staticForeignExportRegistration", "packageScalarLink",
            "packageNativeLink", "packageNativeArchive")) {
            field(cursor, result, key) { error("Compact Core provenance record is not yet supported: $key") }
        }
        cursor.expectEnd()
        result
    }

    private fun targetLayout(cursor: CoreCompactCursor): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>("format" to "thc-target-layout", "schema" to cursor.unsigned())
        result["compiler"] = listOf("id", "abi", "platform", "way").associateWith { text(cursor) }
        val layout = linkedMapOf<String, Any?>("schema" to cursor.unsigned(), "profiled" to cursor.boolean(),
            "wordBytes" to cursor.unsigned(), "endianness" to enum(cursor, listOf("little", "big")),
            "targetPlatform" to text(cursor), "tablesNextToCode" to cursor.boolean())
        for (key in LAYOUT_NUMBERS) layout[key] = cursor.unsigned()
        result["layout"] = layout
        return result
    }

    private fun artifacts(cursor: CoreCompactCursor): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>("schema" to cursor.unsigned(), "execution" to text(cursor))
        field(cursor, result, "stubs") {
            linkedMapOf<String, Any?>("header" to text(cursor), "source" to text(cursor)).also { stubs ->
                for (key in listOf("initializers", "finalizers")) stubs[key] = list(cursor) {
                    mapOf("isInitializer" to cursor.boolean(), "unit" to text(cursor),
                        "module" to text(cursor), "name" to text(cursor))
                }
            }
        }
        result["files"] = list(cursor) {
            mapOf("language" to text(cursor), "source" to text(cursor), "extension" to text(cursor))
        }
        return result
    }

    companion object {
        // Wire order, independently fixed by compact-core-format.md; not a
        // host-derived layout or iteration over a runtime implementation map.
        private val LAYOUT_NUMBERS = listOf(
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
            "stackAnnFrameBytes", "stackClosurePayloadBytes")
        private val KINDS = listOf("long", "float", "double", "address", "void", "data", "closure", "object", "vector", "unknown")
        private val ELEMENTS = listOf("Int8ElemRep", "Int16ElemRep", "Int32ElemRep", "Int64ElemRep", "Word8ElemRep",
            "Word16ElemRep", "Word32ElemRep", "Word64ElemRep", "FloatElemRep", "DoubleElemRep")
        private val PRIMITIVES = listOf("IntRep", "WordRep", "Int8Rep", "Int16Rep", "Int32Rep", "Int64Rep", "Word8Rep",
            "Word16Rep", "Word32Rep", "Word64Rep", "FloatRep", "DoubleRep", "AddrRep", "BoxedRep Nothing",
            "BoxedRep (Just Lifted)", "BoxedRep (Just Unlifted)")
        private val LITERALS = listOf("int", "word", "int8", "int16", "int32", "int64", "word8", "word16", "word32",
            "word64", "bignat", "char", "string-bytes", "float", "double", "null-addr", "rubbish", "function-addr", "data-addr")
    }
}
