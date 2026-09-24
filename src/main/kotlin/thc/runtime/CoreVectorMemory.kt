@file:Suppress("UNCHECKED_CAST")
package thc.runtime

/** These are local intrinsics, not vector function or aggregate ABIs. */
internal enum class VectorByteArrayOp(val primitive: String, val scalarOffset: Boolean) {
    INDEX("indexInt32X4Array#", false),
    INDEX_SCALAR("indexInt32ArrayAsInt32X4#", true),
    READ("readInt32X4Array#", false),
    READ_SCALAR("readInt32ArrayAsInt32X4#", true),
    WRITE("writeInt32X4Array#", false),
    WRITE_SCALAR("writeInt32ArrayAsInt32X4#", true);

    val isRead: Boolean get() = this == READ || this == READ_SCALAR
    val isWrite: Boolean get() = this == WRITE || this == WRITE_SCALAR
    val isIndex: Boolean get() = this == INDEX || this == INDEX_SCALAR
    internal fun validateArguments(actual: List<CoreRepresentation>, flags: List<*>) {
        val expected = listOf(CoreVectorMemory.arrayProof, CoreVectorMemory.indexProof) + when {
            isRead -> listOf(CoreVectorMemory.stateProof)
            isWrite -> listOf(CoreVectors.proof32, CoreVectorMemory.stateProof)
            else -> emptyList()
        }
        if (actual.size != expected.size || flags != List(expected.size) { false } ||
            actual.indices.any { !CoreVectorMemory.exact(expected[it], actual[it]) })
            throw RuntimeFault("Vector ByteArray primitive argument representation mismatch: $primitive")
    }
    fun validate(actual: List<CoreRepresentation>, flags: List<*>, result: CoreRepresentation) {
        if (isRead) throw UnsupportedCore("Vector ByteArray read requires an immediate exact case")
        validateArguments(actual, flags)
        if (!CoreVectorMemory.exact(if (isWrite) CoreVectorMemory.stateProof else CoreVectors.proof32, result))
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
    private fun vectorAnnotation(raw: Any?): Boolean {
        val value = raw as? Map<*, *> ?: return false
        val lanes = value["lanes"] as? Number ?: return false
        return lanes.toDouble() == 4.0 && value["element"] == "Int32ElemRep"
    }
    /** The pinned exporter annotates this aggregate with its sole physical VecRep.
     * Validate the original map without admitting it to generic CoreVector.parse. */
    private fun readResult(raw: Any?, binder: Boolean) {
        val value = raw as? Map<*, *> ?: throw RuntimeFault("Missing local vector read result proof")
        val components = value["components"] as? List<*>
        requireProof(value["kind"] == "unknown" && value["aggregate"] == "unboxed-tuple" &&
            value["primReps"] == CoreVectors.proof32.primReps && vectorAnnotation(value["vector"]) &&
            value["evaluated"] is Boolean && (!binder || value["evaluated"] == true) && components?.size == 2,
            "expected exact (# State#, Int32X4# #) proof")
        requireProof(exact(stateProof, CoreRepresentations.parse(components!![0])) &&
            exact(CoreVectors.proof32, CoreRepresentations.parse(components[1])), "result components")
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
        readResult((app.getOrNull(6) as? Map<*, *>)?.get("rep"), false)
        val whole = expr.getOrNull(2) as? String ?: throw RuntimeFault("Missing local vector read case binder")
        val metadata = expr.getOrNull(4) as? Map<*, *> ?: throw RuntimeFault("Missing local vector read metadata")
        val binder = metadata["binder"] as? Map<*, *> ?: throw RuntimeFault("Missing local vector read binder metadata")
        requireProof(binder["id"] == whole && binder["lifted"] == false && "joinValueArity" !in binder,
            "whole-tuple binder identity/levity")
        readResult(binder["rep"], true)
        val alternatives = expr.getOrNull(3) as? List<*> ?: throw RuntimeFault("Missing local vector read alternatives")
        requireProof(alternatives.size == 1, "requires one tuple alternative")
        val alternative = alternatives.single() as? List<*> ?: throw RuntimeFault("Invalid local vector read alternative")
        val constructor = (alternative.getOrNull(1) as? String)?.let(constructors::get)
        requireProof(alternative.firstOrNull() == "data" && constructor?.get("kind") == "unboxed-tuple" &&
            (constructor?.get("arity") as? Number)?.toDouble() == 2.0, "requires a registered tuple2 constructor")
        val ids = alternative.getOrNull(2) as? List<*> ?: throw RuntimeFault("Missing local vector read pattern ids")
        requireProof(ids.size == 2 && ids.all { it is String } && ids.distinct().size == 2 && whole !in ids,
            "pattern binder identities")
        val records = (alternative.getOrNull(4) as? Map<*, *>)?.get("binders") as? List<*>
        requireProof(records?.size == 2, "missing ordered pattern metadata")
        listOf(stateProof, CoreVectors.proof32).forEachIndexed { i, expected ->
            val record = records!![i] as? Map<*, *> ?: throw RuntimeFault("Invalid local vector read pattern metadata")
            requireProof(record["id"] == ids[i] && record["lifted"] == false && "joinValueArity" !in record &&
                exact(expected, CoreRepresentations.parse(record["rep"])) &&
                (record["rep"] as? Map<*, *>)?.get("evaluated") == true, "pattern binder representation")
        }
        val body = alternative.getOrNull(3) as? List<Any?> ?: throw RuntimeFault("Missing local vector read continuation")
        requireProof(body.isNotEmpty(), "empty continuation")
        requireProof(!termUses(body, whole), "whole tuple binder escapes")
        return VectorReadCase(operation, arguments, ids[0] as String, ids[1] as String, body)
    }
}
