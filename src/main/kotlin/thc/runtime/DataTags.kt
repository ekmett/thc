package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.ExplodeLoop

/** A concrete GHC family, retained before type erasure, is required by both variants. */
internal object CoreDataTags {
    val operations = setOf("dataToTagSmall#", "dataToTagLarge#")

    fun validate(expression: List<Any?>, operand: CoreRepresentation,
                 constructors: Map<String, Map<String, Any?>>): List<String> {
        fun bad(message: String): Nothing = throw RuntimeFault("dataToTag: $message")
        @Suppress("UNCHECKED_CAST")
        val function = expression.getOrNull(1) as? List<Any?> ?: bad("Missing primitive function")
        CoreRepresentations.expression(function)
        val name = function.getOrNull(1)
        if (name !in operations) bad("Unknown primitive variant")
        val args = expression.getOrNull(2) as? List<*> ?: bad("Missing operand")
        if (args.size != 1) bad("Exactly one data operand required")
        val lifted = when (operand.primReps) {
            listOf("BoxedRep (Just Lifted)") -> true
            listOf("BoxedRep (Just Unlifted)") -> false
            else -> bad("Exact known boxed levity required")
        }
        if (!operand.present || operand.kind != CoreKind.DATA || operand.isAggregate || operand.isVector)
            bad("Exact algebraic data operand required")
        if (expression.getOrNull(3) != listOf(lifted)) bad("Operand levity mismatch")
        val result = CoreRepresentations.expression(expression)
        if (!result.present || result.kind != CoreKind.LONG || result.primReps != listOf("IntRep") || result.isAggregate || result.isVector)
            bad("Exact IntRep result required")
        val metadata = expression.getOrNull(6) as? Map<*, *> ?: bad("Missing application metadata")
        val family = metadata["dataToTagFamily"] as? Map<*, *> ?: bad("Missing concrete family")
        if (family.keys != setOf("typeConstructor", "constructors", "smallFamilyLimit", "smallFamily") ||
            (family["typeConstructor"] as? String).isNullOrEmpty()) bad("Malformed family")
        val ids = family["constructors"] as? List<*> ?: bad("Missing ordered constructors")
        if (ids.isEmpty() || ids.any { it !is String || it.isEmpty() } || ids.distinct().size != ids.size)
            bad("Invalid ordered constructors")
        // This runtime's pinned GHC targets are 64-bit. The exporter records
        // mAX_PTR_TAG and isSmallFamily from that target, never guesses from tags.
        if (!exactInteger(family["smallFamilyLimit"], 7) || family["smallFamily"] != (ids.size <= 7))
            bad("Invalid pinned target small-family proof")
        if ((name == "dataToTagSmall#") != (family["smallFamily"] == true))
            bad("Primitive variant does not match family size")
        for ((id, con) in constructors) {
            val declared = con["dataToTagFamily"] as? Map<*, *> ?: continue
            if (declared["typeConstructor"] == family["typeConstructor"] && (declared != family || id !in ids))
                bad("Contradictory supplied family record $id")
        }
        return ids.mapIndexed { index, value ->
            val id = value as String
            val con = constructors[id] ?: bad("Missing family constructor $id")
            val arity = con["arity"]
            if (con["dataToTagFamily"] != family || con["kind"] != "boxed" ||
                !(arity is Int && arity >= 0 || arity is Long && arity in 0..Int.MAX_VALUE.toLong()) ||
                !exactInteger(con["tag"], index + 1)) bad("Contradictory constructor $id")
            CoreFields(con)
            id
        }
    }

    private fun exactInteger(value: Any?, expected: Int): Boolean =
        value is Long && value == expected.toLong() || value is Int && value == expected
}

/** Expected class-owned layouts identify tags without touching any payload field. */
class DataTagFamily(@field:CompilationFinal(dimensions = 1) private val layouts: Array<DataLayout>) {
    @ExplodeLoop
    fun tag(value: DataValue): Long {
        for (index in layouts.indices) if (layouts[index].matches(value)) return index.toLong()
        CompilerDirectives.transferToInterpreterAndInvalidate()
        throw RuntimeFault("dataToTag: constructor does not belong to the proven family")
    }
}

internal class DataToTag(private val family: DataTagFamily, @field:Child private var operand: Expr) : Expr() {
    init { representation = CoreRepresentation(CoreKind.LONG, evaluated = true, primReps = listOf("IntRep")) }
    override fun execute(frame: VirtualFrame): Long = executeLong(frame)
    override fun executeLong(frame: VirtualFrame): Long = family.tag(operand.executeRequiredDataValue(frame))
}
