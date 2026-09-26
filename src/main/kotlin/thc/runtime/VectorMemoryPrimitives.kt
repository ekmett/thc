// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")

package thc.runtime

import jdk.incubator.vector.IntVector
import jdk.incubator.vector.FloatVector
import jdk.incubator.vector.DoubleVector

import com.oracle.truffle.api.frame.VirtualFrame

/** Closed local memory families with exact vector representation proofs. */
internal enum class VectorMemoryFamily {
    INT32, WORD32, FLOAT32, DOUBLE64;
    val vectorProof: CoreRepresentation get() = when (this) {
        INT32 -> CoreVectors.proof32
        WORD32 -> CoreVectors.proofWord32
        FLOAT32 -> CoreVectors.proofFloat
        DOUBLE64 -> CoreVectors.proofDouble
    }
}

/** These are local intrinsics, not vector function or aggregate ABIs. */
internal enum class VectorByteArrayOp(val primitive: String, val scalarOffset: Boolean,
    val family: VectorMemoryFamily = VectorMemoryFamily.INT32) {
    INDEX("indexInt32X4Array#", false),
    INDEX_SCALAR("indexInt32ArrayAsInt32X4#", true),
    READ("readInt32X4Array#", false),
    READ_SCALAR("readInt32ArrayAsInt32X4#", true),
    WRITE("writeInt32X4Array#", false),
    WRITE_SCALAR("writeInt32ArrayAsInt32X4#", true),
    INDEX_WORD("indexWord32X4Array#", false, VectorMemoryFamily.WORD32),
    INDEX_WORD_SCALAR("indexWord32ArrayAsWord32X4#", true, VectorMemoryFamily.WORD32),
    READ_WORD("readWord32X4Array#", false, VectorMemoryFamily.WORD32),
    READ_WORD_SCALAR("readWord32ArrayAsWord32X4#", true, VectorMemoryFamily.WORD32),
    WRITE_WORD("writeWord32X4Array#", false, VectorMemoryFamily.WORD32),
    WRITE_WORD_SCALAR("writeWord32ArrayAsWord32X4#", true, VectorMemoryFamily.WORD32),
    INDEX_FLOAT("indexFloatX4Array#", false, VectorMemoryFamily.FLOAT32),
    INDEX_FLOAT_SCALAR("indexFloatArrayAsFloatX4#", true, VectorMemoryFamily.FLOAT32),
    READ_FLOAT("readFloatX4Array#", false, VectorMemoryFamily.FLOAT32),
    READ_FLOAT_SCALAR("readFloatArrayAsFloatX4#", true, VectorMemoryFamily.FLOAT32),
    WRITE_FLOAT("writeFloatX4Array#", false, VectorMemoryFamily.FLOAT32),
    WRITE_FLOAT_SCALAR("writeFloatArrayAsFloatX4#", true, VectorMemoryFamily.FLOAT32),
    INDEX_DOUBLE("indexDoubleX2Array#", false, VectorMemoryFamily.DOUBLE64),
    INDEX_DOUBLE_SCALAR("indexDoubleArrayAsDoubleX2#", true, VectorMemoryFamily.DOUBLE64),
    READ_DOUBLE("readDoubleX2Array#", false, VectorMemoryFamily.DOUBLE64),
    READ_DOUBLE_SCALAR("readDoubleArrayAsDoubleX2#", true, VectorMemoryFamily.DOUBLE64),
    WRITE_DOUBLE("writeDoubleX2Array#", false, VectorMemoryFamily.DOUBLE64),
    WRITE_DOUBLE_SCALAR("writeDoubleArrayAsDoubleX2#", true, VectorMemoryFamily.DOUBLE64);

    val isRead: Boolean get() = this == READ || this == READ_SCALAR || this == READ_WORD || this == READ_WORD_SCALAR ||
        this == READ_FLOAT || this == READ_FLOAT_SCALAR || this == READ_DOUBLE || this == READ_DOUBLE_SCALAR
    val isWrite: Boolean get() = this == WRITE || this == WRITE_SCALAR || this == WRITE_WORD || this == WRITE_WORD_SCALAR ||
        this == WRITE_FLOAT || this == WRITE_FLOAT_SCALAR || this == WRITE_DOUBLE || this == WRITE_DOUBLE_SCALAR
    val isIndex: Boolean get() = this == INDEX || this == INDEX_SCALAR || this == INDEX_WORD || this == INDEX_WORD_SCALAR ||
        this == INDEX_FLOAT || this == INDEX_FLOAT_SCALAR || this == INDEX_DOUBLE || this == INDEX_DOUBLE_SCALAR
    val vectorProof: CoreRepresentation get() = family.vectorProof
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
            exactInteger(value["lanes"], proof.vector!!.lanes.toLong()) && value["element"] == proof.vector.element
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

/** The read form is constructed only by the validated immediate-case lowering.
 * It publishes a dense local vector, never a State/vector tuple value. */
internal class VectorByteArrayExpression(private val operation: VectorByteArrayOp,
    @field:Children private var arguments: Array<Expr>) : Expr() {
    init { representation = if (operation.isWrite) CoreVectorMemory.stateProof else operation.vectorProof }
    override fun execute(frame: VirtualFrame): Any {
        val array = arguments[0].execute(frame)
        val index = arguments[1].executeRequiredLong(frame)
        if (operation.isWrite) {
            // A subject enum when creates a mutable switch-map array load that
            // prevents partial evaluation from selecting this node's family.
            when {
                operation.family === VectorMemoryFamily.INT32 -> {
                    val value = CoreVectors.requireInt(arguments[2].execute(frame), IntVector.SPECIES_128)
                    ManagedByteArray.requireState(arguments[3].execute(frame))
                    ManagedByteArray.writeInt32VectorGuest(array, index, value, operation.scalarOffset)
                }
                operation.family === VectorMemoryFamily.WORD32 -> {
                    val value = CoreVectors.requireInt(arguments[2].execute(frame), IntVector.SPECIES_128)
                    ManagedByteArray.requireState(arguments[3].execute(frame))
                    ManagedByteArray.writeWord32VectorGuest(array, index, value, operation.scalarOffset)
                }
                operation.family === VectorMemoryFamily.FLOAT32 -> {
                    val value = CoreVectors.requireFloat(arguments[2].execute(frame), FloatVector.SPECIES_128)
                    ManagedByteArray.requireState(arguments[3].execute(frame))
                    ManagedByteArray.writeFloatVectorGuest(array, index, value, operation.scalarOffset)
                }
                operation.family === VectorMemoryFamily.DOUBLE64 -> {
                    val value = CoreVectors.requireDouble(arguments[2].execute(frame), DoubleVector.SPECIES_128)
                    ManagedByteArray.requireState(arguments[3].execute(frame))
                    ManagedByteArray.writeDoubleVectorGuest(array, index, value, operation.scalarOffset)
                }
                else -> fault("Unsupported local vector memory family")
            }
            return Unit
        }
        if (operation.isRead) ManagedByteArray.requireState(arguments[2].execute(frame))
        // Return each public carrier directly; a value-producing when joins at the inaccessible AbstractVector.
        when {
            operation.family === VectorMemoryFamily.INT32 -> return ManagedByteArray.readInt32VectorGuest(array, index, operation.scalarOffset)
            operation.family === VectorMemoryFamily.WORD32 -> return ManagedByteArray.readWord32VectorGuest(array, index, operation.scalarOffset)
            operation.family === VectorMemoryFamily.FLOAT32 -> return ManagedByteArray.readFloatVectorGuest(array, index, operation.scalarOffset)
            operation.family === VectorMemoryFamily.DOUBLE64 -> return ManagedByteArray.readDoubleVectorGuest(array, index, operation.scalarOffset)
            else -> fault("Unsupported local vector memory family")
        }
    }
}
