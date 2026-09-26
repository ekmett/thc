// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import jdk.incubator.vector.*

/** Vector identity is one VecRep, independent of its physical runtime value. */
internal data class CoreVector(val lanes: Int, val element: String) {
    companion object {
        val INT64X2 = CoreVector(2, "Int64ElemRep")
        val INT32X4 = CoreVector(4, "Int32ElemRep")
        val INT16X8 = CoreVector(8, "Int16ElemRep")
        val INT8X16 = CoreVector(16, "Int8ElemRep")
        val WORD8X16 = CoreVector(16, "Word8ElemRep")
        val WORD16X8 = CoreVector(8, "Word16ElemRep")
        val WORD32X4 = CoreVector(4, "Word32ElemRep")
        val FLOATX4 = CoreVector(4, "FloatElemRep")
        val DOUBLEX2 = CoreVector(2, "DoubleElemRep")
        fun parse(raw: Any?, kind: CoreKind, reps: List<String>?, aggregate: Boolean): CoreVector? {
            if (kind != CoreKind.VECTOR) {
                // The exporter records a sole physical VecRep on a logical
                // tuple as well (for example (# State#, FloatX4# #)). Check that
                // redundant physical annotation without turning the tuple into
                // a scalar vector; its recursive fields are validated separately.
                if (aggregate && raw != null) {
                    parse(raw, CoreKind.VECTOR, reps, false)
                    return null
                }
                if (raw != null || !aggregate && reps?.any { it.startsWith("VecRep ") } == true)
                    throw RuntimeFault("Vector representation lacks exact vector metadata")
                return null
            }
            val record = raw as? Map<*, *> ?: throw RuntimeFault("Missing Core vector shape")
            val lanes = record["lanes"] as? Number ?: throw RuntimeFault("Invalid vector lane count")
            if (lanes !is Int && lanes !is Long) throw RuntimeFault("Invalid vector lane count")
            val element = record["element"] as? String ?: throw RuntimeFault("Invalid vector element kind")
            if (lanes.toDouble() != lanes.toInt().toDouble() || aggregate)
                throw RuntimeFault("Invalid Core vector shape")
            val vector = CoreVector(lanes.toInt(), element)
            if (reps != listOf("VecRep ${vector.lanes} $element")) throw RuntimeFault("Vector shape disagrees with primitive representation")
            if (vector != INT64X2 && vector != INT32X4 && vector != INT16X8 && vector != INT8X16 && vector != WORD8X16 && vector != WORD16X8 && vector != WORD32X4 && vector != FLOATX4 && vector != DOUBLEX2 && !GeneratedVectors.supports(vector)) throw UnsupportedCore("Unsupported Core vector representation: $vector")
            return vector
        }
    }
}

internal object CoreVectors {
    // Generic value boundaries check physical species; signedness stays in the exact VecRep.
    // Expose the species' exact class to the compiler without naming its inaccessible implementation.
    @JvmStatic fun requireByte(raw: Any?, species: VectorSpecies<Byte>): ByteVector {
        val value = raw as? ByteVector ?: fault("Expected ByteVector")
        if (value.species() != species) fault("Unexpected vector species")
        return CompilerDirectives.castExact(value, species.vectorType()) as ByteVector
    }
    @JvmStatic fun requireShort(raw: Any?, species: VectorSpecies<Short>): ShortVector {
        val value = raw as? ShortVector ?: fault("Expected ShortVector")
        if (value.species() != species) fault("Unexpected vector species")
        return CompilerDirectives.castExact(value, species.vectorType()) as ShortVector
    }
    @JvmStatic fun requireInt(raw: Any?, species: VectorSpecies<Int>): IntVector {
        val value = raw as? IntVector ?: fault("Expected IntVector")
        if (value.species() != species) fault("Unexpected vector species")
        return CompilerDirectives.castExact(value, species.vectorType()) as IntVector
    }
    @JvmStatic fun requireLong(raw: Any?, species: VectorSpecies<Long>): LongVector {
        val value = raw as? LongVector ?: fault("Expected LongVector")
        if (value.species() != species) fault("Unexpected vector species")
        return CompilerDirectives.castExact(value, species.vectorType()) as LongVector
    }
    @JvmStatic fun requireFloat(raw: Any?, species: VectorSpecies<Float>): FloatVector {
        val value = raw as? FloatVector ?: fault("Expected FloatVector")
        if (value.species() != species) fault("Unexpected vector species")
        return CompilerDirectives.castExact(value, species.vectorType()) as FloatVector
    }
    @JvmStatic fun requireDouble(raw: Any?, species: VectorSpecies<Double>): DoubleVector {
        val value = raw as? DoubleVector ?: fault("Expected DoubleVector")
        if (value.species() != species) fault("Unexpected vector species")
        return CompilerDirectives.castExact(value, species.vectorType()) as DoubleVector
    }
    @JvmStatic fun laneIndex(index: Long, lanes: Int): Int {
        if (index < 0L || index >= lanes.toLong()) fault("Invalid vector lane index")
        return index.toInt()
    }

    /** GHC's shuffle instruction requires a literal tuple of concatenated lane indices. */
    fun shuffleIndices(raw: List<Any?>, lanes: Int): IntArray {
        val constructor = raw.getOrNull(1) as? List<*>
        val fields = raw.getOrNull(2) as? List<*>
        if (raw.firstOrNull() != "app" || constructor?.firstOrNull() != "con" ||
            raw.getOrNull(5) != true || fields?.size != lanes)
            throw UnsupportedCore("Vector shuffle requires a literal index tuple")
        return IntArray(lanes) { lane ->
            val literal = fields[lane] as? List<*>
            val value = if (literal?.firstOrNull() == "lit" && literal.getOrNull(1) == "int")
                (literal.getOrNull(2) as? String)?.toLongOrNull() else null
            if (value == null || value < 0L || value >= 2L * lanes)
                throw UnsupportedCore("Vector shuffle indices must be literals in 0 until ${2 * lanes}")
            value.toInt()
        }
    }

    val proof = CoreRepresentation(CoreKind.VECTOR, true, true, listOf("VecRep 2 Int64ElemRep"), vector = CoreVector.INT64X2)
    private val lane = CoreRepresentation(CoreKind.LONG, true, true, listOf("Int64Rep"))
    val unpacked = CoreRepresentation(CoreKind.UNKNOWN, true, true, listOf("Int64Rep", "Int64Rep"), listOf(lane, lane))
    val proof32 = CoreRepresentation(CoreKind.VECTOR, true, true, listOf("VecRep 4 Int32ElemRep"), vector = CoreVector.INT32X4)
    private val lane32 = CoreRepresentation(CoreKind.LONG, true, true, listOf("Int32Rep"))
    val unpacked32 = CoreRepresentation(CoreKind.UNKNOWN, true, true, List(4) { "Int32Rep" }, List(4) { lane32 })
    val proof16 = CoreRepresentation(CoreKind.VECTOR, true, true, listOf("VecRep 8 Int16ElemRep"), vector = CoreVector.INT16X8)
    private val lane16 = CoreRepresentation(CoreKind.LONG, true, true, listOf("Int16Rep"))
    val unpacked16 = CoreRepresentation(CoreKind.UNKNOWN, true, true, List(8) { "Int16Rep" }, List(8) { lane16 })
    val operations16 = setOf("packInt16X8#", "unpackInt16X8#", "broadcastInt16X8#", "plusInt16X8#", "minusInt16X8#", "negateInt16X8#", "timesInt16X8#")
    val proof8 = CoreRepresentation(CoreKind.VECTOR, true, true, listOf("VecRep 16 Int8ElemRep"), vector = CoreVector.INT8X16)
    private val lane8 = CoreRepresentation(CoreKind.LONG, true, true, listOf("Int8Rep"))
    val unpacked8 = CoreRepresentation(CoreKind.UNKNOWN, true, true, List(16) { "Int8Rep" }, List(16) { lane8 })
    val operations8 = setOf("packInt8X16#", "unpackInt8X16#", "broadcastInt8X16#", "plusInt8X16#", "minusInt8X16#", "negateInt8X16#", "timesInt8X16#")
    val proofWord8 = CoreRepresentation(CoreKind.VECTOR, true, true, listOf("VecRep 16 Word8ElemRep"), vector = CoreVector.WORD8X16)
    private val laneWord8 = CoreRepresentation(CoreKind.LONG, true, true, listOf("Word8Rep"))
    val unpackedWord8 = CoreRepresentation(CoreKind.UNKNOWN, true, true, List(16) { "Word8Rep" }, List(16) { laneWord8 })
    val operationsWord8 = setOf("packWord8X16#", "unpackWord8X16#", "broadcastWord8X16#", "plusWord8X16#", "minusWord8X16#", "timesWord8X16#")
    val proofWord16 = CoreRepresentation(CoreKind.VECTOR, true, true, listOf("VecRep 8 Word16ElemRep"), vector = CoreVector.WORD16X8)
    private val laneWord16 = CoreRepresentation(CoreKind.LONG, true, true, listOf("Word16Rep"))
    val unpackedWord16 = CoreRepresentation(CoreKind.UNKNOWN, true, true, List(8) { "Word16Rep" }, List(8) { laneWord16 })
    val operationsWord16 = setOf("packWord16X8#", "unpackWord16X8#", "broadcastWord16X8#", "plusWord16X8#", "minusWord16X8#", "timesWord16X8#")
    val proofWord32 = CoreRepresentation(CoreKind.VECTOR, true, true, listOf("VecRep 4 Word32ElemRep"), vector = CoreVector.WORD32X4)
    private val laneWord32 = CoreRepresentation(CoreKind.LONG, true, true, listOf("Word32Rep"))
    val unpackedWord32 = CoreRepresentation(CoreKind.UNKNOWN, true, true, List(4) { "Word32Rep" }, List(4) { laneWord32 })
    val operationsWord32 = setOf("packWord32X4#", "unpackWord32X4#", "broadcastWord32X4#", "plusWord32X4#", "minusWord32X4#", "timesWord32X4#")
    val proofFloat = CoreRepresentation(CoreKind.VECTOR, true, true, listOf("VecRep 4 FloatElemRep"), vector = CoreVector.FLOATX4)
    private val laneFloat = CoreRepresentation(CoreKind.FLOAT, true, true, listOf("FloatRep"))
    val unpackedFloat = CoreRepresentation(CoreKind.UNKNOWN, true, true, List(4) { "FloatRep" }, List(4) { laneFloat })
    val fusedFloat = listOf("fmaddFloatX4#", "fmsubFloatX4#", "fnmaddFloatX4#", "fnmsubFloatX4#")
    val fusedFloat8 = listOf("fmaddFloatX8#", "fmsubFloatX8#", "fnmaddFloatX8#", "fnmsubFloatX8#")
    val fusedFloat16 = listOf("fmaddFloatX16#", "fmsubFloatX16#", "fnmaddFloatX16#", "fnmsubFloatX16#")
    val operationsFloat = setOf("packFloatX4#", "unpackFloatX4#", "broadcastFloatX4#", "plusFloatX4#", "minusFloatX4#", "timesFloatX4#") + fusedFloat
    val proofDouble = CoreRepresentation(CoreKind.VECTOR, true, true, listOf("VecRep 2 DoubleElemRep"), vector = CoreVector.DOUBLEX2)
    private val laneDouble = CoreRepresentation(CoreKind.DOUBLE, true, true, listOf("DoubleRep"))
    val unpackedDouble = CoreRepresentation(CoreKind.UNKNOWN, true, true, List(2) { "DoubleRep" }, List(2) { laneDouble })
    val fusedDouble = listOf("fmaddDoubleX2#", "fmsubDoubleX2#", "fnmaddDoubleX2#", "fnmsubDoubleX2#")
    val fusedDouble4 = listOf("fmaddDoubleX4#", "fmsubDoubleX4#", "fnmaddDoubleX4#", "fnmsubDoubleX4#")
    val fusedDouble8 = listOf("fmaddDoubleX8#", "fmsubDoubleX8#", "fnmaddDoubleX8#", "fnmsubDoubleX8#")
    val operationsDouble = setOf("packDoubleX2#", "unpackDoubleX2#", "broadcastDoubleX2#", "plusDoubleX2#", "minusDoubleX2#", "timesDoubleX2#") + fusedDouble
    val operations32 = setOf("packInt32X4#", "unpackInt32X4#", "broadcastInt32X4#", "plusInt32X4#", "minusInt32X4#", "negateInt32X4#", "timesInt32X4#")
    val operations = setOf("packInt64X2#", "unpackInt64X2#", "broadcastInt64X2#", "plusInt64X2#", "minusInt64X2#", "negateInt64X2#") + operations32 + operations16 + operations8 + operationsWord8 + operationsWord16 + operationsWord32 + operationsFloat + operationsDouble + GeneratedVectors.operations + fusedFloat8 + fusedFloat16 + fusedDouble4 + fusedDouble8
    fun requireVariableProof(binding: CoreRepresentation?, occurrence: CoreRepresentation) {
        if (occurrence.isVector && binding?.vector != occurrence.vector)
            throw RuntimeFault("Vector occurrence lacks a matching lexical binder proof")
    }
    /** A local case must retain vector identity even when its result annotation is absent. */
    fun caseResult(alternatives: List<CoreRepresentation>): CoreRepresentation? {
        val vector = alternatives.firstOrNull { it.isVector } ?: return null
        if (alternatives.any { it.vector != vector.vector })
            throw RuntimeFault("Conflicting vector case result representations")
        return vector.copy(evaluated = alternatives.all { it.evaluated })
    }
    private fun exact(expected: CoreRepresentation, actual: CoreRepresentation): Boolean =
        actual.present && expected.kind == actual.kind && expected.vector == actual.vector &&
            TupleShape.compatible(expected, actual) && (expected.components == null ||
                expected.components.indices.all { exact(expected.components[it], actual.components!![it]) })
    fun validate(name: String, arguments: List<CoreRepresentation>, result: CoreRepresentation) {
        if (name in GeneratedVectors.operations) return GeneratedVectors.validate(name, arguments, result)
        val expected = when (name) {
            "packInt64X2#" -> listOf(unpacked)
            "unpackInt64X2#", "negateInt64X2#" -> listOf(proof)
            "broadcastInt64X2#" -> listOf(lane)
            "plusInt64X2#", "minusInt64X2#" -> listOf(proof, proof)
            "packInt32X4#" -> listOf(unpacked32)
            "unpackInt32X4#", "negateInt32X4#" -> listOf(proof32)
            "broadcastInt32X4#" -> listOf(lane32)
            "plusInt32X4#", "minusInt32X4#", "timesInt32X4#" -> listOf(proof32, proof32)
            "packInt16X8#" -> listOf(unpacked16)
            "unpackInt16X8#", "negateInt16X8#" -> listOf(proof16)
            "broadcastInt16X8#" -> listOf(lane16)
            "plusInt16X8#", "minusInt16X8#", "timesInt16X8#" -> listOf(proof16, proof16)
            "packInt8X16#" -> listOf(unpacked8)
            "unpackInt8X16#", "negateInt8X16#" -> listOf(proof8)
            "broadcastInt8X16#" -> listOf(lane8)
            "plusInt8X16#", "minusInt8X16#", "timesInt8X16#" -> listOf(proof8, proof8)
            "packWord8X16#" -> listOf(unpackedWord8)
            "unpackWord8X16#" -> listOf(proofWord8)
            "broadcastWord8X16#" -> listOf(laneWord8)
            "plusWord8X16#", "minusWord8X16#", "timesWord8X16#" -> listOf(proofWord8, proofWord8)
            "packWord16X8#" -> listOf(unpackedWord16)
            "unpackWord16X8#" -> listOf(proofWord16)
            "broadcastWord16X8#" -> listOf(laneWord16)
            "plusWord16X8#", "minusWord16X8#", "timesWord16X8#" -> listOf(proofWord16, proofWord16)
            "packWord32X4#" -> listOf(unpackedWord32)
            "unpackWord32X4#" -> listOf(proofWord32)
            "broadcastWord32X4#" -> listOf(laneWord32)
            "plusWord32X4#", "minusWord32X4#", "timesWord32X4#" -> listOf(proofWord32, proofWord32)
            "packDoubleX2#" -> listOf(unpackedDouble)
            "unpackDoubleX2#" -> listOf(proofDouble)
            "broadcastDoubleX2#" -> listOf(laneDouble)
            "plusDoubleX2#", "minusDoubleX2#", "timesDoubleX2#" -> listOf(proofDouble, proofDouble)
            "packFloatX4#" -> listOf(unpackedFloat)
            "unpackFloatX4#" -> listOf(proofFloat)
            "broadcastFloatX4#" -> listOf(laneFloat)
            "plusFloatX4#", "minusFloatX4#", "timesFloatX4#" -> listOf(proofFloat, proofFloat)
            in fusedFloat8 -> List(3) { GeneratedVectors.proofFloatX8 }
            in fusedFloat16 -> List(3) { GeneratedVectors.proofFloatX16 }
            in fusedDouble4 -> List(3) { GeneratedVectors.proofDoubleX4 }
            in fusedDouble8 -> List(3) { GeneratedVectors.proofDoubleX8 }
            in fusedFloat -> listOf(proofFloat, proofFloat, proofFloat)
            in fusedDouble -> listOf(proofDouble, proofDouble, proofDouble)
            else -> throw UnsupportedCore("Unsupported vector primitive $name")
        }
        val expectedResult = when (name) {
            "unpackInt64X2#" -> unpacked
            "unpackInt32X4#" -> unpacked32
            "unpackInt16X8#" -> unpacked16
            in operations16 -> proof16
            "unpackInt8X16#" -> unpacked8
            in operations8 -> proof8
            "unpackWord8X16#" -> unpackedWord8
            in operationsWord8 -> proofWord8
            "unpackWord16X8#" -> unpackedWord16
            in operationsWord16 -> proofWord16
            "unpackWord32X4#" -> unpackedWord32
            in operationsWord32 -> proofWord32
            "unpackDoubleX2#" -> unpackedDouble
            in operationsDouble -> proofDouble
            "unpackFloatX4#" -> unpackedFloat
            in fusedFloat8 -> GeneratedVectors.proofFloatX8
            in fusedFloat16 -> GeneratedVectors.proofFloatX16
            in fusedDouble4 -> GeneratedVectors.proofDoubleX4
            in fusedDouble8 -> GeneratedVectors.proofDoubleX8
            in operationsFloat -> proofFloat
            in operations32 -> proof32
            else -> proof
        }
        validateSignature(name, arguments, result, expected, expectedResult)
    }
    fun validateSignature(name: String, arguments: List<CoreRepresentation>, result: CoreRepresentation,
                          expected: List<CoreRepresentation>, expectedResult: CoreRepresentation) {
        if (arguments.size != expected.size) throw RuntimeFault("Vector primitive arity mismatch: $name")
        arguments.indices.forEach { i ->
            if (!exact(expected[i], arguments[i]))
                throw RuntimeFault("Vector primitive argument representation mismatch: $name argument $i")
        }
        if (!exact(expectedResult, result))
            throw RuntimeFault("Vector primitive result representation mismatch: $name")
    }
    fun validateFlags(flags: List<*>) {
        if (flags.any { it != false }) throw RuntimeFault("Vector primitive operands must be unlifted")
    }
    fun argumentProof(expression: List<Any?>): CoreRepresentation =
        if (expression.firstOrNull() == "lit" && expression.getOrNull(1) in listOf("int8", "word8", "int16", "word16", "int32", "word32"))
            CoreRepresentations.narrowLiteralProof(expression)
        else CoreRepresentations.expression(expression)
}
