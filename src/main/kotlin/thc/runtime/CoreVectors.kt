package thc.runtime

/** Vector identity is one VecRep; two spill lanes never turn it into a tuple. */
internal data class CoreVector(val lanes: Int, val element: String) {
    companion object {
        val INT64X2 = CoreVector(2, "Int64ElemRep")
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
            if (vector != INT64X2) throw UnsupportedCore("Unsupported Core vector representation: $vector")
            return vector
        }
    }
}

internal object CoreVectors {
    val proof = CoreRepresentation(CoreKind.VECTOR, true, true, listOf("VecRep 2 Int64ElemRep"), vector = CoreVector.INT64X2)
    private val lane = CoreRepresentation(CoreKind.LONG, true, true, listOf("Int64Rep"))
    val unpacked = CoreRepresentation(CoreKind.UNKNOWN, true, true, listOf("Int64Rep", "Int64Rep"), listOf(lane, lane))
    val operations = setOf("packInt64X2#", "unpackInt64X2#", "broadcastInt64X2#", "plusInt64X2#", "minusInt64X2#", "negateInt64X2#")
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
    fun validate(name: String, arguments: List<CoreRepresentation>, result: CoreRepresentation) {
        val expected = when (name) {
            "packInt64X2#" -> listOf(unpacked)
            "unpackInt64X2#", "negateInt64X2#" -> listOf(proof)
            "broadcastInt64X2#" -> listOf(lane)
            "plusInt64X2#", "minusInt64X2#" -> listOf(proof, proof)
            else -> throw UnsupportedCore("Unsupported vector primitive $name")
        }
        if (arguments.size != expected.size) throw RuntimeFault("Vector primitive arity mismatch: $name")
        arguments.indices.forEach { i ->
            if (!arguments[i].present || !TupleShape.compatible(expected[i], arguments[i]) || expected[i].vector != arguments[i].vector)
                throw RuntimeFault("Vector primitive argument representation mismatch: $name argument $i")
        }
        val expectedResult = if (name == "unpackInt64X2#") unpacked else proof
        if (!result.present || !TupleShape.compatible(expectedResult, result) || expectedResult.vector != result.vector)
            throw RuntimeFault("Vector primitive result representation mismatch: $name")
    }
}
