package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.frame.VirtualFrame

/** The family is a compile-time proof, not a guess from the scalar reference carrier. */
internal object CoreEnums {
    fun validate(expression: List<Any?>, operand: CoreRepresentation,
                 constructors: Map<String, Map<String, Any?>>): List<String> {
        fun bad(message: String): Nothing = throw RuntimeFault("tagToEnum#: $message")
        // Special primitive lowering bypasses ordinary function compilation;
        // still validate any supplied function-node representation record.
        @Suppress("UNCHECKED_CAST")
        val function = expression.getOrNull(1) as? List<Any?> ?: bad("Missing primitive function")
        CoreRepresentations.expression(function)
        val args = expression.getOrNull(2) as? List<*> ?: bad("Missing operands")
        if (args.size != 1 || expression.getOrNull(3) != listOf(false)) bad("Exactly one unlifted Int# operand required")
        if (!operand.present || operand.kind != CoreKind.LONG || operand.primReps != listOf("IntRep") || operand.isTuple || operand.isVector)
            bad("Exact IntRep operand required")
        val result = CoreRepresentations.expression(expression)
        if (!result.present || result.kind != CoreKind.DATA || result.primReps != listOf("BoxedRep (Just Lifted)") || result.isTuple || result.isVector)
            bad("Exact lifted data result required")
        val metadata = expression.getOrNull(6) as? Map<*, *> ?: bad("Missing application metadata")
        val family = metadata["enumFamily"] as? Map<*, *> ?: bad("Missing concrete enum family")
        if (family.keys != setOf("typeConstructor", "constructors") || (family["typeConstructor"] as? String).isNullOrEmpty())
            bad("Malformed enum family")
        val ids = family["constructors"] as? List<*> ?: bad("Missing ordered constructors")
        if (ids.isEmpty() || ids.any { it !is String || it.isEmpty() } || ids.distinct().size != ids.size)
            bad("Invalid ordered constructors")
        return ids.mapIndexed { index, value ->
            val id = value as String
            val con = constructors[id] ?: bad("Missing family constructor $id")
            val tag = con["tag"]
            if (con["enumFamily"] != family || con["kind"] != "boxed" || !exactInteger(con["arity"], 0) || !exactInteger(tag, index + 1))
                bad("Contradictory enum constructor $id")
            for (key in listOf("fieldReps", "fieldTypes", "fieldLifted", "strictFields"))
                if (con[key] != emptyList<Any?>()) bad("Non-nullary enum constructor $id")
            id
        }
    }
    private fun exactInteger(value: Any?, expected: Int): Boolean =
        (value is Long && value == expected.toLong()) || (value is Int && value == expected)
}

/** Selection reuses the same class-owned nullary values as ordinary construction. */
class EnumFamily(@field:CompilationFinal(dimensions = 1) private val values: Array<DataValue>) {
    fun select(tag: Long): DataValue {
        if (tag < 0 || tag >= values.size.toLong()) {
            CompilerDirectives.transferToInterpreterAndInvalidate()
            throw RuntimeFault("tagToEnum#: tag out of range: $tag (family size ${values.size})")
        }
        return values[tag.toInt()]
    }
}

internal class TagToEnum(private val family: EnumFamily, @field:Child private var operand: Expr) : Expr() {
    init { representation = CoreRepresentation(CoreKind.DATA, evaluated = true) }
    override fun execute(frame: VirtualFrame): DataValue = executeDataValue(frame)
    override fun executeDataValue(frame: VirtualFrame): DataValue = family.select(operand.executeRequiredLong(frame))
}
