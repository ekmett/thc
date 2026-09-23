package thc.runtime

/** Vector identity is one VecRep; typed spill lanes never turn it into a tuple. */
internal data class CoreVector(val lanes: Int, val element: String) {
    companion object {
        val INT64X2 = CoreVector(2, "Int64ElemRep")
        val INT32X4 = CoreVector(4, "Int32ElemRep")
        val FLOATX4 = CoreVector(4, "FloatElemRep")
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
            if (vector != INT64X2 && vector != INT32X4 && vector != FLOATX4) throw UnsupportedCore("Unsupported Core vector representation: $vector")
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
    val proofFloat = CoreRepresentation(CoreKind.VECTOR, true, true, listOf("VecRep 4 FloatElemRep"), vector = CoreVector.FLOATX4)
    private val laneFloat = CoreRepresentation(CoreKind.FLOAT, true, true, listOf("FloatRep"))
    val unpackedFloat = CoreRepresentation(CoreKind.UNKNOWN, true, true, List(4) { "FloatRep" }, List(4) { laneFloat })
    val operationsFloat = setOf("packFloatX4#", "unpackFloatX4#", "broadcastFloatX4#", "plusFloatX4#", "minusFloatX4#", "timesFloatX4#")
    val operations32 = setOf("packInt32X4#", "unpackInt32X4#", "broadcastInt32X4#", "plusInt32X4#", "minusInt32X4#", "negateInt32X4#")
    val operations = setOf("packInt64X2#", "unpackInt64X2#", "broadcastInt64X2#", "plusInt64X2#", "minusInt64X2#", "negateInt64X2#") + operations32 + operationsFloat
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
            "unpackFloatX4#" -> unpackedFloat
            in operationsFloat -> proofFloat
            in operations32 -> proof32
            else -> proof
        }
        if (!exact(expectedResult, result))
            throw RuntimeFault("Vector primitive result representation mismatch: $name")
    }
}
