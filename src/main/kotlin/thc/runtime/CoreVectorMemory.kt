@file:Suppress("UNCHECKED_CAST")
package thc.runtime

/** These are local intrinsics, not vector function or aggregate ABIs. */
internal enum class VectorByteArrayOp(val primitive: String, val scalarOffset: Boolean, val unsigned: Boolean = false) {
    INDEX("indexInt32X4Array#", false),
    INDEX_SCALAR("indexInt32ArrayAsInt32X4#", true),
    READ("readInt32X4Array#", false),
    READ_SCALAR("readInt32ArrayAsInt32X4#", true),
    WRITE("writeInt32X4Array#", false),
    WRITE_SCALAR("writeInt32ArrayAsInt32X4#", true),
    INDEX_WORD("indexWord32X4Array#", false, true),
    INDEX_WORD_SCALAR("indexWord32ArrayAsWord32X4#", true, true),
    READ_WORD("readWord32X4Array#", false, true),
    READ_WORD_SCALAR("readWord32ArrayAsWord32X4#", true, true),
    WRITE_WORD("writeWord32X4Array#", false, true),
    WRITE_WORD_SCALAR("writeWord32ArrayAsWord32X4#", true, true);

    val isRead: Boolean get() = this == READ || this == READ_SCALAR || this == READ_WORD || this == READ_WORD_SCALAR
    val isWrite: Boolean get() = this == WRITE || this == WRITE_SCALAR || this == WRITE_WORD || this == WRITE_WORD_SCALAR
    val isIndex: Boolean get() = this == INDEX || this == INDEX_SCALAR || this == INDEX_WORD || this == INDEX_WORD_SCALAR
    val vectorProof: CoreRepresentation get() = if (unsigned) CoreVectors.proofWord32 else CoreVectors.proof32
    internal fun validateArguments(actual: List<CoreRepresentation>, flags: List<*>) {
        val expected = listOf(CoreVectorMemory.arrayProof, CoreVectorMemory.indexProof) + when {
            isRead -> listOf(CoreVectorMemory.stateProof)
            isWrite -> listOf(vectorProof, CoreVectorMemory.stateProof)
            else -> emptyList()
        }
        if (actual.size != expected.size || flags != List(expected.size) { false } ||
            actual.indices.any { !CoreVectorMemory.exact(expected[it], actual[it]) })
            throw RuntimeFault("Vector ByteArray primitive argument representation mismatch: $primitive")
    }
    fun validate(actual: List<CoreRepresentation>, flags: List<*>, result: CoreRepresentation) {
        if (isRead) throw UnsupportedCore("Vector ByteArray read requires an immediate exact case")
        validateArguments(actual, flags)
        if (!CoreVectorMemory.exact(if (isWrite) CoreVectorMemory.stateProof else vectorProof, result))
            throw RuntimeFault("Vector ByteArray primitive result representation mismatch: $primitive")
    }
    companion object {
        fun named(name: String): VectorByteArrayOp? = entries.firstOrNull { it.primitive == name }
    }
}

internal data class VectorReadCase(val operation: VectorByteArrayOp, val arguments: List<List<Any?>>,
    val stateBinder: String, val vectorBinder: String, val body: List<Any?>)

internal object CoreVectorMemory {
    val stateProof = CoreRepresentation(CoreKind.VOID, true, true, emptyList())
    internal val arrayProof = CoreRepresentation(CoreKind.OBJECT, true, true, listOf("BoxedRep (Just Unlifted)"))
    internal val indexProof = CoreRepresentation(CoreKind.LONG, true, true, listOf("IntRep"))
    internal fun exact(expected: CoreRepresentation, actual: CoreRepresentation): Boolean =
        actual.present && !actual.isTuple && actual.kind == expected.kind &&
            actual.primReps == expected.primReps && actual.vector == expected.vector

    private fun requireProof(condition: Boolean, detail: String) {
        if (!condition) throw RuntimeFault("Invalid local vector read case: $detail")
    }
    private fun exactInteger(value: Any?, expected: Long): Boolean =
        (value is Int || value is Long) && (value as Number).toLong() == expected
    private fun vectorAnnotation(raw: Any?, proof: CoreRepresentation): Boolean {
        val value = raw as? Map<*, *> ?: return false
        return value.keys == setOf("lanes", "element") &&
            exactInteger(value["lanes"], 4) && value["element"] == proof.vector!!.element
    }
    /** Direct memory sites also need the original shape, before generic parsing
     * normalizes numeric lane counts. This does not change generic vector policy. */
    fun validateDirectAnnotations(operation: VectorByteArrayOp, arguments: List<List<Any?>>, result: Any?) {
        if (operation.isRead) throw UnsupportedCore("Vector ByteArray read requires an immediate exact case")
        val raw = if (operation.isWrite) arguments.getOrNull(2)?.let { CoreRepresentations.metadata(it)?.get("rep") }
            else result
        if (!vectorAnnotation((raw as? Map<*, *>)?.get("vector"), operation.vectorProof))
            throw RuntimeFault("Vector ByteArray primitive requires exact raw vector annotation: ${operation.primitive}")
    }
    /** The pinned exporter annotates this aggregate with its sole physical VecRep.
     * Validate the original map without admitting it to generic CoreVector.parse. */
    private fun readResult(raw: Any?, binder: Boolean, proof: CoreRepresentation) {
        val value = raw as? Map<*, *> ?: throw RuntimeFault("Missing local vector read result proof")
        val components = value["components"] as? List<*>
        requireProof(value["kind"] == "unknown" && value["aggregate"] == "unboxed-tuple" &&
            value["primReps"] == proof.primReps && vectorAnnotation(value["vector"], proof) &&
            value["evaluated"] is Boolean && (!binder || value["evaluated"] == true) && components?.size == 2,
            "expected exact State/vector proof for ${proof.vector}")
        // Check the raw vector shape before generic parsing normalizes its lane count.
        requireProof(vectorAnnotation((components!![1] as? Map<*, *>)?.get("vector"), proof) &&
            exact(stateProof, CoreRepresentations.parse(components[0])) &&
            exact(proof, CoreRepresentations.parse(components[1])), "result components")
    }
    private fun termUses(value: Any?, id: String): Boolean = when (value) {
        is List<*> -> value.firstOrNull() == "var" && value.getOrNull(1) == id || value.any { termUses(it, id) }
        is Map<*, *> -> value.values.any { termUses(it, id) }
        else -> false
    }
    fun readCase(expr: List<Any?>, constructors: Map<String, Map<String, Any?>>): VectorReadCase? {
        if (expr.firstOrNull() != "case") return null
        val app = expr.getOrNull(1) as? List<*> ?: return null
        if (app.firstOrNull() != "app") return null
        val function = app.getOrNull(1) as? List<*> ?: return null
        if (function.firstOrNull() != "prim") return null
        val operation = (function.getOrNull(1) as? String)?.let(VectorByteArrayOp::named) ?: return null
        if (!operation.isRead) return null
        val arguments = (app.getOrNull(2) as? List<*>)?.map {
            it as? List<Any?> ?: throw RuntimeFault("Invalid local vector read argument")
        } ?: throw RuntimeFault("Missing local vector read arguments")
        val flags = app.getOrNull(3) as? List<*> ?: throw RuntimeFault("Missing local vector read flags")
        operation.validateArguments(arguments.map(CoreRepresentations::expression), flags)
        readResult((app.getOrNull(6) as? Map<*, *>)?.get("rep"), false, operation.vectorProof)
        val whole = expr.getOrNull(2) as? String ?: throw RuntimeFault("Missing local vector read case binder")
        val metadata = expr.getOrNull(4) as? Map<*, *> ?: throw RuntimeFault("Missing local vector read metadata")
        val binder = metadata["binder"] as? Map<*, *> ?: throw RuntimeFault("Missing local vector read binder metadata")
        requireProof(binder["id"] == whole && binder["lifted"] == false && binder["coercion"] == false && "joinValueArity" !in binder,
            "whole-tuple binder identity/levity")
        readResult(binder["rep"], true, operation.vectorProof)
        val alternatives = expr.getOrNull(3) as? List<*> ?: throw RuntimeFault("Missing local vector read alternatives")
        requireProof(alternatives.size == 1, "requires one tuple alternative")
        val alternative = alternatives.single() as? List<*> ?: throw RuntimeFault("Invalid local vector read alternative")
        val constructor = (alternative.getOrNull(1) as? String)?.let(constructors::get)
        requireProof(alternative.firstOrNull() == "data" && constructor?.get("kind") == "unboxed-tuple" &&
            exactInteger(constructor?.get("arity"), 2), "requires a registered tuple2 constructor")
        val ids = alternative.getOrNull(2) as? List<*> ?: throw RuntimeFault("Missing local vector read pattern ids")
        requireProof(ids.size == 2 && ids.all { it is String } && ids.distinct().size == 2 && whole !in ids,
            "pattern binder identities")
        val records = (alternative.getOrNull(4) as? Map<*, *>)?.get("binders") as? List<*>
        requireProof(records?.size == 2, "missing ordered pattern metadata")
        listOf(stateProof, operation.vectorProof).forEachIndexed { i, expected ->
            val record = records!![i] as? Map<*, *> ?: throw RuntimeFault("Invalid local vector read pattern metadata")
            requireProof(record["id"] == ids[i] && record["lifted"] == false && record["coercion"] == false && "joinValueArity" !in record &&
                (!expected.isVector || vectorAnnotation((record["rep"] as? Map<*, *>)?.get("vector"), expected)) &&
                exact(expected, CoreRepresentations.parse(record["rep"])) &&
                (record["rep"] as? Map<*, *>)?.get("evaluated") == true, "pattern binder representation")
        }
        val body = alternative.getOrNull(3) as? List<Any?> ?: throw RuntimeFault("Missing local vector read continuation")
        requireProof(body.isNotEmpty(), "empty continuation")
        requireProof(!termUses(body, whole), "whole tuple binder escapes")
        return VectorReadCase(operation, arguments, ids[0] as String, ids[1] as String, body)
    }
}
