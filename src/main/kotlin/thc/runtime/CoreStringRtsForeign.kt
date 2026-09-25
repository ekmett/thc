// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

/** Only the two exact original GHC 9.14.1 Posix FCallIds, not a libc dispatcher. */
internal enum class StringRtsOp(val symbol: String, val arguments: List<String?>) {
    STRLEN("strlen", listOf("AddrRep", null)),
    THREADED("rts_isThreaded", listOf(null))
}

internal object CoreStringRtsForeign {
    private val scalarKeys = setOf("kind", "primReps", "evaluated")
    private val tupleKeys = scalarKeys + setOf("aggregate", "components")
    private val descriptorKeys = setOf("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep")

    private fun requireProof(valid: Boolean, detail: String) {
        if (!valid) fault("Invalid original Posix string/RTS call: $detail")
    }
    private fun exact(value: Any?, expected: Int): Boolean =
        (value is Int || value is Long) && (value as Number).toLong() == expected.toLong()
    private fun kind(rep: String?): String = when (rep) {
        null -> "void"; "AddrRep" -> "address"; "BoxedRep (Just Lifted)" -> "closure"; else -> "long"
    }
    private fun scalar(raw: Any?, rep: String?, evaluated: Boolean? = null): Boolean {
        val value = raw as? Map<*, *> ?: return false
        return value.keys == scalarKeys && value["kind"] == kind(rep) &&
            value["primReps"] == listOfNotNull(rep) && value["evaluated"] is Boolean &&
            (evaluated == null || value["evaluated"] == evaluated)
    }
    private fun result(raw: Any?, declared: Boolean = false): Boolean {
        val value = raw as? Map<*, *> ?: return false
        val fields = value["components"] as? List<*> ?: return false
        return value.keys == tupleKeys && value["kind"] == "unknown" &&
            value["aggregate"] == "unboxed-tuple" && value["primReps"] == listOf("IntRep") &&
            value["evaluated"] is Boolean && (!declared || value["evaluated"] == false) &&
            fields.size == 2 && scalar(fields[0], null, true) && scalar(fields[1], "IntRep", true)
    }

    fun validateHead(function: List<Any?>, defined: Boolean) {
        requireProof(function.size == 3 && function[0] == "var" && function[1] is String &&
            (function[1] as String).isNotEmpty() && !defined &&
            scalar(CoreRepresentations.metadata(function)?.get("rep"), "BoxedRep (Just Lifted)", true),
            "unresolved original foreign variable required")
    }

    fun validateHeads(value: Any?) {
        when (value) {
            is Map<*, *> -> value.values.forEach(::validateHeads)
            is List<*> -> {
                if (value.firstOrNull() == "app") {
                    val descriptor = (value.getOrNull(6) as? Map<*, *>)?.get("foreignCall") as? Map<*, *>
                    val target = descriptor?.get("target") as? Map<*, *>
                    if (StringRtsOp.entries.any { it.symbol == target?.get("symbol") })
                        validateHead(value.getOrNull(1) as? List<Any?>
                            ?: fault("Invalid original Posix string/RTS call: missing head"), false)
                }
                value.forEach(::validateHeads)
            }
        }
    }

    fun validateOperand(operation: StringRtsOp, index: Int, lowered: CoreRepresentation, stored: CoreRepresentation?) {
        val rep = operation.arguments[index]
        requireProof(lowered.present && !lowered.isAggregate && !lowered.isVector &&
            lowered.kind.name.lowercase() == kind(rep) && lowered.primReps == listOfNotNull(rep),
            "lowered operand $index")
        if (stored != null && stored.present)
            requireProof(!stored.isAggregate && !stored.isVector &&
                stored.kind.name.lowercase() in setOf(kind(rep), "unknown") &&
                (stored.primReps == null || stored.primReps == listOfNotNull(rep)), "stored operand $index")
    }

    fun validate(metadata: Any?, arguments: List<*>, flags: List<*>, resultProof: Any?): StringRtsOp? {
        val meta = metadata as? Map<*, *> ?: return null
        val descriptor = meta["foreignCall"] as? Map<*, *> ?: return null
        val target = descriptor["target"] as? Map<*, *> ?: return null
        val operation = StringRtsOp.entries.firstOrNull { it.symbol == target["symbol"] } ?: return null
        requireProof(descriptor.keys == descriptorKeys && exact(descriptor["schema"], 1), "descriptor schema")
        requireProof(target.keys == setOf("kind", "symbol", "unit", "isFunction") &&
            target["kind"] == "static" && target["unit"] == "ghc-internal" && target["isFunction"] == true,
            "exact installed GHC target")
        requireProof(descriptor["convention"] == "ccall" && descriptor["safety"] == "unsafe" &&
            exact(descriptor["arity"], operation.arguments.size) &&
            exact(descriptor["suppliedArity"], operation.arguments.size), "convention, safety or arity")
        val declared = descriptor["argumentReps"] as? List<*>
        requireProof(declared?.size == operation.arguments.size && operation.arguments.indices.all {
            scalar(declared[it], operation.arguments[it], false)
        } && arguments.size == operation.arguments.size && operation.arguments.indices.all {
            scalar(arguments[it], operation.arguments[it])
        } && flags == List(operation.arguments.size) { false }, "argument representations and flags")
        requireProof(result(descriptor["resultRep"], true) && result(meta["rep"]) && result(resultProof),
            "State#/Int# tuple result")
        return operation
    }
}

internal class StringRtsExpression(private val operation: StringRtsOp,
    @field:Children private var operands: Array<Expr>, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("Original Posix call requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val address = if (operation == StringRtsOp.STRLEN) operands[0].executeRequiredAddress(frame) else null
        requireVoidCarrier(operands.last().execute(frame))
        FrameAccess.writeLong(frame, slots[offset], if (address == null) 0L else address.cStringLength())
        return null
    }
}
