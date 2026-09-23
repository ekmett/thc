package thc.runtime

/** Vector identity is one VecRep; typed spill lanes never turn it into a tuple. */
internal data class CoreVector(val lanes: Int, val element: String) {
    companion object {
        val INT64X2 = CoreVector(2, "Int64ElemRep")
        val INT32X4 = CoreVector(4, "Int32ElemRep")
        val INT16X8 = CoreVector(8, "Int16ElemRep")
        val FLOATX4 = CoreVector(4, "FloatElemRep")
        val DOUBLEX2 = CoreVector(2, "DoubleElemRep")
        fun parse(raw: Any?, kind: CoreKind, reps: List<String>?, components: List<CoreRepresentation>?): CoreVector? {
            if (kind != CoreKind.VECTOR) {
                if (raw != null || components == null && reps?.any { it.startsWith("VecRep ") } == true)
                    throw RuntimeFault("Vector representation lacks exact vector metadata")
                return null
            }
            val record = raw as? Map<*, *> ?: throw RuntimeFault("Missing Core vector shape")
            val lanes = record["lanes"] as? Number ?: throw RuntimeFault("Invalid vector lane count")
            val element = record["element"] as? String ?: throw RuntimeFault("Invalid vector element kind")
            if (lanes.toDouble() != lanes.toInt().toDouble() || components != null)
                throw RuntimeFault("Invalid Core vector shape")
            val vector = CoreVector(lanes.toInt(), element)
            if (reps != listOf("VecRep ${vector.lanes} $element")) throw RuntimeFault("Vector shape disagrees with primitive representation")
            if (vector != INT64X2 && vector != INT32X4 && vector != INT16X8 && vector != FLOATX4 && vector != DOUBLEX2) throw UnsupportedCore("Unsupported Core vector representation: $vector")
            return vector
        }
    }
}

internal object CoreVectors {
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
    val proofFloat = CoreRepresentation(CoreKind.VECTOR, true, true, listOf("VecRep 4 FloatElemRep"), vector = CoreVector.FLOATX4)
    private val laneFloat = CoreRepresentation(CoreKind.FLOAT, true, true, listOf("FloatRep"))
    val unpackedFloat = CoreRepresentation(CoreKind.UNKNOWN, true, true, List(4) { "FloatRep" }, List(4) { laneFloat })
    val operationsFloat = setOf("packFloatX4#", "unpackFloatX4#", "broadcastFloatX4#", "plusFloatX4#", "minusFloatX4#", "timesFloatX4#")
    val proofDouble = CoreRepresentation(CoreKind.VECTOR, true, true, listOf("VecRep 2 DoubleElemRep"), vector = CoreVector.DOUBLEX2)
    private val laneDouble = CoreRepresentation(CoreKind.DOUBLE, true, true, listOf("DoubleRep"))
    val unpackedDouble = CoreRepresentation(CoreKind.UNKNOWN, true, true, List(2) { "DoubleRep" }, List(2) { laneDouble })
    val operationsDouble = setOf("packDoubleX2#", "unpackDoubleX2#", "broadcastDoubleX2#", "plusDoubleX2#", "minusDoubleX2#", "timesDoubleX2#")
    val operations32 = setOf("packInt32X4#", "unpackInt32X4#", "broadcastInt32X4#", "plusInt32X4#", "minusInt32X4#", "negateInt32X4#")
    val operations = setOf("packInt64X2#", "unpackInt64X2#", "broadcastInt64X2#", "plusInt64X2#", "minusInt64X2#", "negateInt64X2#") + operations32 + operations16 + operationsFloat + operationsDouble
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
        val expected = when (name) {
            "packInt64X2#" -> listOf(unpacked)
            "unpackInt64X2#", "negateInt64X2#" -> listOf(proof)
            "broadcastInt64X2#" -> listOf(lane)
            "plusInt64X2#", "minusInt64X2#" -> listOf(proof, proof)
            "packInt32X4#" -> listOf(unpacked32)
            "unpackInt32X4#", "negateInt32X4#" -> listOf(proof32)
            "broadcastInt32X4#" -> listOf(lane32)
            "plusInt32X4#", "minusInt32X4#" -> listOf(proof32, proof32)
            "packInt16X8#" -> listOf(unpacked16)
            "unpackInt16X8#", "negateInt16X8#" -> listOf(proof16)
            "broadcastInt16X8#" -> listOf(lane16)
            "plusInt16X8#", "minusInt16X8#", "timesInt16X8#" -> listOf(proof16, proof16)
            "packDoubleX2#" -> listOf(unpackedDouble)
            "unpackDoubleX2#" -> listOf(proofDouble)
            "broadcastDoubleX2#" -> listOf(laneDouble)
            "plusDoubleX2#", "minusDoubleX2#", "timesDoubleX2#" -> listOf(proofDouble, proofDouble)
            "packFloatX4#" -> listOf(unpackedFloat)
            "unpackFloatX4#" -> listOf(proofFloat)
            "broadcastFloatX4#" -> listOf(laneFloat)
            "plusFloatX4#", "minusFloatX4#", "timesFloatX4#" -> listOf(proofFloat, proofFloat)
            else -> throw UnsupportedCore("Unsupported vector primitive $name")
        }
        if (arguments.size != expected.size) throw RuntimeFault("Vector primitive arity mismatch: $name")
        arguments.indices.forEach { i ->
            if (!exact(expected[i], arguments[i]))
                throw RuntimeFault("Vector primitive argument representation mismatch: $name argument $i")
        }
        val expectedResult = when (name) {
            "unpackInt64X2#" -> unpacked
            "unpackInt32X4#" -> unpacked32
            "unpackInt16X8#" -> unpacked16
            in operations16 -> proof16
            "unpackDoubleX2#" -> unpackedDouble
            in operationsDouble -> proofDouble
            "unpackFloatX4#" -> unpackedFloat
            in operationsFloat -> proofFloat
            in operations32 -> proof32
            else -> proof
        }
        if (!exact(expectedResult, result))
            throw RuntimeFault("Vector primitive result representation mismatch: $name")
    }
    fun validateFlags(flags: List<*>) {
        if (flags.any { it != false }) throw RuntimeFault("Vector primitive operands must be unlifted")
    }
    fun argumentProof(expression: List<Any?>): CoreRepresentation =
        if (expression.firstOrNull() == "lit" && expression.getOrNull(1) in listOf("int16", "word16", "int32", "word32"))
            CoreRepresentations.narrowLiteralProof(expression)
        else CoreRepresentations.expression(expression)
}
