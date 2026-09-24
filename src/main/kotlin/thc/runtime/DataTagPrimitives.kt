// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.ExplodeLoop

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
        if (!operand.present || operand.kind != CoreKind.LONG || operand.primReps != listOf("IntRep") || operand.isAggregate || operand.isVector)
            bad("Exact IntRep operand required")
        val result = CoreRepresentations.expression(expression)
        if (!result.present || result.kind != CoreKind.DATA || result.primReps != listOf("BoxedRep (Just Lifted)") || result.isAggregate || result.isVector)
            bad("Exact lifted data result required")
        val metadata = expression.getOrNull(6) as? Map<*, *> ?: bad("Missing application metadata")
        val family = metadata["enumFamily"] as? Map<*, *> ?: bad("Missing concrete enum family")
        if (family.keys != setOf("typeConstructor", "constructors") || (family["typeConstructor"] as? String).isNullOrEmpty())
            bad("Malformed enum family")
        val ids = family["constructors"] as? List<*> ?: bad("Missing ordered constructors")
        if (ids.isEmpty() || ids.any { it !is String || it.isEmpty() } || ids.distinct().size != ids.size)
            bad("Invalid ordered constructors")
        // A supplied record cannot name this same nominal family while
        // contradicting or extending its complete ordered constructor list.
        for ((id, con) in constructors) {
            val declared = con["enumFamily"] as? Map<*, *> ?: continue
            if (declared["typeConstructor"] == family["typeConstructor"] && (declared != family || id !in ids))
                bad("Contradictory family record $id")
        }
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
