// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

/** Exact original ghc-internal memory-copy FCallIds, not an arbitrary libc symbol bridge. */
internal object CoreMemmoveForeign : CoreMemoryCopyForeign("memmove")
internal object CoreMemcpyForeign : CoreMemoryCopyForeign("memcpy")

internal sealed class CoreMemoryCopyForeign(private val symbol: String) {
    private val scalarKeys = setOf("kind", "primReps", "evaluated")
    private val tupleKeys = scalarKeys + setOf("aggregate", "components")
    private val descriptorKeys = setOf("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep")
    private val argumentReps = listOf("AddrRep", "AddrRep", "Word64Rep", null)

    private fun requireProof(valid: Boolean, detail: String) {
        if (!valid) fault("Invalid original $symbol call: $detail")
    }
    private fun exactInteger(value: Any?, expected: Int): Boolean =
        (value is Int || value is Long) && (value as Number).toLong() == expected.toLong()
    private fun kind(rep: String?): String = when (rep) {
        null -> "void"; "AddrRep" -> "address"; else -> "long"
    }
    private fun scalar(raw: Any?, rep: String?, evaluated: Boolean? = null): Boolean {
        val proof = raw as? Map<*, *> ?: return false
        return proof.keys == scalarKeys && proof["kind"] == kind(rep) &&
            proof["primReps"] == listOfNotNull(rep) && proof["evaluated"] is Boolean &&
            (evaluated == null || proof["evaluated"] == evaluated)
    }
    private fun result(raw: Any?, declared: Boolean = false): Boolean {
        val proof = raw as? Map<*, *> ?: return false
        val fields = proof["components"] as? List<*> ?: return false
        return proof.keys == tupleKeys && proof["kind"] == "unknown" &&
            proof["aggregate"] == "unboxed-tuple" && proof["primReps"] == listOf("AddrRep") &&
            proof["evaluated"] is Boolean && (!declared || proof["evaluated"] == false) &&
            fields.size == 2 && scalar(fields[0], null, true) && scalar(fields[1], "AddrRep", true)
    }

    fun validateHead(function: List<Any?>, defined: Boolean) {
        val proof = CoreRepresentations.metadata(function)?.get("rep") as? Map<*, *>
        requireProof(function.size == 3 && function[0] == "var" && function[1] is String &&
            (function[1] as String).isNotEmpty() && !defined && proof?.keys == scalarKeys &&
            proof["kind"] == "closure" && proof["primReps"] == listOf("BoxedRep (Just Lifted)") &&
            proof["evaluated"] == true, "unresolved original foreign variable required")
    }

    fun validateHeads(value: Any?) {
        when (value) {
            is Map<*, *> -> value.values.forEach(::validateHeads)
            is List<*> -> {
                if (value.firstOrNull() == "app") {
                    val target = ((value.getOrNull(6) as? Map<*, *>)?.get("foreignCall") as? Map<*, *>)?.get("target") as? Map<*, *>
                    if (target?.get("symbol") == symbol)
                        validateHead(value.getOrNull(1) as? List<Any?>
                            ?: fault("Invalid original $symbol call: missing variable head"), false)
                }
                value.forEach(::validateHeads)
            }
        }
    }

    fun validateOperand(index: Int, lowered: CoreRepresentation, stored: CoreRepresentation?) {
        val rep = argumentReps[index]
        requireProof(lowered.present && !lowered.isAggregate && !lowered.isVector &&
            lowered.kind.name.lowercase() == kind(rep) && lowered.primReps == listOfNotNull(rep),
            "lowered operand $index")
        if (stored != null && stored.present)
            requireProof(!stored.isAggregate && !stored.isVector &&
                stored.kind.name.lowercase() in setOf(kind(rep), "unknown") &&
                (stored.primReps == null || stored.primReps == listOfNotNull(rep)), "stored operand $index")
    }

    fun validate(metadata: Any?, arguments: List<*>, flags: List<*>, resultProof: Any?): Boolean {
        val meta = metadata as? Map<*, *> ?: return false
        val descriptor = meta["foreignCall"] as? Map<*, *> ?: return false
        val target = descriptor["target"] as? Map<*, *> ?: return false
        if (target["symbol"] != symbol) return false
        requireProof(descriptor.keys == descriptorKeys && exactInteger(descriptor["schema"], 1), "descriptor schema")
        requireProof(target.keys == setOf("kind", "symbol", "unit", "isFunction") && target["kind"] == "static" &&
            target["unit"] == "ghc-internal" && target["isFunction"] == true, "exact installed GHC target")
        requireProof(descriptor["convention"] == "ccall" && descriptor["safety"] == "unsafe" &&
            exactInteger(descriptor["arity"], 4) && exactInteger(descriptor["suppliedArity"], 4), "convention, safety or arity")
        val declared = descriptor["argumentReps"] as? List<*>
        requireProof(declared?.size == 4 && argumentReps.indices.all { scalar(declared[it], argumentReps[it], false) } &&
            arguments.size == 4 && argumentReps.indices.all { scalar(arguments[it], argumentReps[it]) } &&
            flags == listOf(false, false, false, false), "argument representations and flags")
        requireProof(result(descriptor["resultRep"], true) && result(meta["rep"]) && result(resultProof),
            "State#/Addr# tuple result")
        return true
    }
}

internal class MemmoveExpression(@field:Children private var operands: Array<Expr>, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("memmove requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val destination = operands[0].executeRequiredAddress(frame)
        val source = operands[1].executeRequiredAddress(frame)
        val count = operands[2].executeRequiredLong(frame)
        requireVoidCarrier(operands[3].execute(frame))
        FrameAccess.writeObject(frame, slots[offset], source.moveTo(destination, count))
        return null
    }
}

internal class MemcpyExpression(@field:Children private var operands: Array<Expr>, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("memcpy requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val destination = operands[0].executeRequiredAddress(frame)
        val source = operands[1].executeRequiredAddress(frame)
        val count = operands[2].executeRequiredLong(frame)
        requireVoidCarrier(operands[3].execute(frame))
        source.copyNonOverlappingTo(destination, count)
        FrameAccess.writeObject(frame, slots[offset], destination)
        return null
    }
}
