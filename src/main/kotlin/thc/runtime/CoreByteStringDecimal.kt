// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

internal enum class ByteStringDecimalOp(val symbol: String, val addressResult: Boolean) {
    SIGNED("_hs_bytestring_long_long_int_dec", true),
    PADDED18("_hs_bytestring_long_long_int_dec_padded18", false);
    val arguments: List<String?> = listOf("Int64Rep", "AddrRep", null)
}

/** The two original bytestring-0.12.2.0 signed decimal declarations. */
internal object CoreByteStringDecimal {
    private val scalarKeys = setOf("kind", "primReps", "evaluated")
    private val tupleKeys = scalarKeys + setOf("aggregate", "components")
    private val descriptorKeys = setOf("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep")
    private fun check(valid: Boolean, detail: String) {
        if (!valid) fault("Invalid original ByteString decimal call: $detail")
    }
    private fun exact(value: Any?, n: Int) = (value is Int || value is Long) && (value as Number).toLong() == n.toLong()
    private fun kind(rep: String?) = when (rep) {
        null -> "void"; "AddrRep" -> "address"; "BoxedRep (Just Lifted)" -> "closure"; else -> "long"
    }
    private fun scalar(raw: Any?, primitive: String?, evaluated: Boolean? = null): Boolean {
        val value = raw as? Map<*, *> ?: return false
        return value.keys == scalarKeys && value["kind"] == kind(primitive) &&
            value["primReps"] == listOfNotNull(primitive) && value["evaluated"] is Boolean &&
            (evaluated == null || value["evaluated"] == evaluated)
    }
    private fun result(raw: Any?, operation: ByteStringDecimalOp, declared: Boolean = false): Boolean {
        val value = raw as? Map<*, *> ?: return false
        val fields = value["components"] as? List<*> ?: return false
        return value.keys == tupleKeys && value["aggregate"] == "unboxed-tuple" && value["kind"] == "unknown" &&
            value["primReps"] == (if (operation.addressResult) listOf("AddrRep") else emptyList<String>()) &&
            value["evaluated"] is Boolean && (!declared || value["evaluated"] == false) &&
            fields.size == (if (operation.addressResult) 2 else 1) && scalar(fields[0], null, true) &&
            (!operation.addressResult || scalar(fields[1], "AddrRep", true))
    }
    fun validate(metadata: Any?, arguments: List<*>, flags: List<*>, resultProof: Any?): ByteStringDecimalOp? {
        val meta = metadata as? Map<*, *> ?: return null
        val call = meta["foreignCall"] as? Map<*, *> ?: return null
        val target = call["target"] as? Map<*, *> ?: return null
        val operation = ByteStringDecimalOp.entries.firstOrNull { it.symbol == target["symbol"] } ?: return null
        check(call.keys == descriptorKeys && exact(call["schema"], 1), "descriptor schema")
        check(target.keys == setOf("kind", "symbol", "unit", "isFunction") && target["kind"] == "static" &&
            target["isFunction"] == true && isOriginalByteStringUnit(target["unit"]), "pinned original ByteString target")
        check(call["convention"] == "ccall" && call["safety"] == "unsafe" &&
            exact(call["arity"], 3) && exact(call["suppliedArity"], 3), "convention, safety or arity")
        val declared = call["argumentReps"] as? List<*>
        check(declared?.size == 3 && arguments.size == 3 && flags == listOf(false, false, false) &&
            operation.arguments.indices.all { scalar(declared[it], operation.arguments[it], false) &&
                scalar(arguments[it], operation.arguments[it]) }, "Int64#/Addr#/State# arguments")
        check(result(call["resultRep"], operation, true) && result(meta["rep"], operation) && result(resultProof, operation),
            "State#/address or singleton-State tuple result")
        return operation
    }
    fun validateHead(function: List<Any?>, defined: Boolean) {
        check(function.size == 3 && function[0] == "var" && function[1] is String &&
            (function[1] as String).isNotEmpty() && !defined &&
            scalar(CoreRepresentations.metadata(function)?.get("rep"), "BoxedRep (Just Lifted)", true),
            "unresolved original FCallId required")
    }
    fun validateOperand(operation: ByteStringDecimalOp, index: Int, lowered: CoreRepresentation, stored: CoreRepresentation?) {
        val primitive = operation.arguments[index]
        check(lowered.present && !lowered.isAggregate && !lowered.isVector &&
            lowered.kind.name.lowercase() == kind(primitive) && lowered.primReps == listOfNotNull(primitive), "lowered operand $index")
        if (stored != null && stored.present) check(!stored.isAggregate && !stored.isVector &&
            stored.kind.name.lowercase() in setOf(kind(primitive), "unknown") &&
            (stored.primReps == null || stored.primReps == listOfNotNull(primitive)), "stored operand $index")
    }
}

/** Writes into caller-owned storage, without a terminator or ownership transfer. */
internal object ByteStringDecimal {
    @JvmStatic fun signed(value: Long, destination: ManagedAddress): ManagedAddress = destination.withNativeBorrow {
        // Stay nonpositive so Long.MIN_VALUE never needs an overflowing negation.
        var remaining = if (value > 0) -value else value
        var probe = remaining
        var width = if (value < 0) 2L else 1L
        while (probe <= -10) { width++; probe /= 10 }
        destination.requireByteRegion(width, true)
        if (value < 0) destination.writeWord8(0, 45)
        var position = width
        do {
            destination.writeWord8(--position, 48 - remaining % 10)
            remaining /= 10
        } while (remaining != 0L)
        destination.plus(width)
    }

    @JvmStatic fun padded18(value: Long, destination: ManagedAddress) {
        if (value < 0 || value >= 1_000_000_000_000_000_000L)
            fault("ByteString padded18 requires 0 <= value < 10^18")
        destination.withNativeBorrow {
            destination.requireByteRegion(18, true)
            var remaining = value
            var position = 18L
            while (position > 0) {
                destination.writeWord8(--position, 48 + remaining % 10)
                remaining /= 10
            }
        }
    }
}

internal class ByteStringDecimalExpression(private val operation: ByteStringDecimalOp,
    @field:Children private var operands: Array<Expr>, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("ByteString decimal call requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val value = operands[0].executeRequiredLong(frame)
        val address = operands[1].executeRequiredAddress(frame)
        requireVoidCarrier(operands[2].execute(frame))
        if (operation == ByteStringDecimalOp.SIGNED)
            FrameAccess.writeObject(frame, slots[offset], ByteStringDecimal.signed(value, address))
        else ByteStringDecimal.padded18(value, address)
        return null
    }
}
