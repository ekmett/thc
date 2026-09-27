// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.frame.VirtualFrame

/** The installed bytestring-0.12.2.0 unsigned byte sort declaration. */
internal object CoreByteStringSort {
    private val scalarKeys = setOf("kind", "primReps", "evaluated")
    private val tupleKeys = scalarKeys + setOf("aggregate", "components")
    private val descriptorKeys = setOf("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep")
    private val arguments = listOf("AddrRep", "Word64Rep", null)
    private fun check(valid: Boolean, detail: String) {
        if (!valid) fault("Invalid original ByteString sort call: $detail")
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
    private fun result(raw: Any?, declared: Boolean = false): Boolean {
        val value = raw as? Map<*, *> ?: return false
        val fields = value["components"] as? List<*> ?: return false
        return value.keys == tupleKeys && value["aggregate"] == "unboxed-tuple" && value["kind"] == "unknown" &&
            value["primReps"] == emptyList<String>() && value["evaluated"] is Boolean &&
            (!declared || value["evaluated"] == false) && fields.size == 1 && scalar(fields[0], null, true)
    }
    fun validate(metadata: Any?, operands: List<*>, flags: List<*>, resultProof: Any?): Boolean {
        val meta = metadata as? Map<*, *> ?: return false
        val call = meta["foreignCall"] as? Map<*, *> ?: return false
        val target = call["target"] as? Map<*, *> ?: return false
        if (target["symbol"] != "fps_sort") return false
        check(call.keys == descriptorKeys && exact(call["schema"], 1), "descriptor schema")
        check(target.keys == setOf("kind", "symbol", "unit", "isFunction") && target["kind"] == "static" &&
            target["isFunction"] == true && isOriginalByteStringUnit(target["unit"]), "pinned original ByteString target")
        check(call["convention"] == "ccall" && call["safety"] == "unsafe" &&
            exact(call["arity"], 3) && exact(call["suppliedArity"], 3), "convention, safety or arity")
        val declared = call["argumentReps"] as? List<*>
        check(declared?.size == 3 && operands.size == 3 && flags == listOf(false, false, false) &&
            arguments.indices.all { scalar(declared[it], arguments[it], false) && scalar(operands[it], arguments[it]) },
            "Addr#/Word64#/State# arguments")
        check(result(call["resultRep"], true) && result(meta["rep"]) && result(resultProof), "singleton-State tuple result")
        return true
    }
    fun validateHead(function: List<Any?>, defined: Boolean) {
        check(function.size == 3 && function[0] == "var" && function[1] is String &&
            (function[1] as String).isNotEmpty() && !defined &&
            scalar(CoreRepresentations.metadata(function)?.get("rep"), "BoxedRep (Just Lifted)", true),
            "unresolved original FCallId required")
    }
    fun validateOperand(index: Int, lowered: CoreRepresentation, stored: CoreRepresentation?) {
        val primitive = arguments[index]
        check(lowered.present && !lowered.isAggregate && !lowered.isVector &&
            lowered.kind.name.lowercase() == kind(primitive) && lowered.primReps == listOfNotNull(primitive), "lowered operand $index")
        if (stored != null && stored.present) check(!stored.isAggregate && !stored.isVector &&
            stored.kind.name.lowercase() in setOf(kind(primitive), "unknown") &&
            (stored.primReps == null || stored.primReps == listOfNotNull(primitive)), "stored operand $index")
    }
}

internal object ByteStringSort {
    /** Equivalent to fps_sort's unsigned-byte qsort, preserving the caller's allocation.
     * Keep the variable-size scan outside partial evaluation, like other native leaves. */
    @JvmStatic @TruffleBoundary
    fun sort(address: ManagedAddress, count: Long) = address.withNativeBorrow {
        if (address !== ManagedAddress.nullAddress() || count != 0L) address.requireByteRegion(count, true)
        if (count <= 1L) return@withNativeBorrow
        val frequencies = LongArray(256)
        var position = 0L
        while (position < count) frequencies[address.readWord8(position++).toInt()]++
        position = 0L
        for (byte in frequencies.indices) {
            var remaining = frequencies[byte]
            while (remaining-- > 0L) address.writeWord8(position++, byte.toLong())
        }
    }
}

internal class ByteStringSortExpression(@field:Children private var operands: Array<Expr>, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("ByteString sort requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val address = operands[0].executeRequiredAddress(frame)
        val count = operands[1].executeRequiredLong(frame)
        requireVoidCarrier(operands[2].execute(frame))
        ByteStringSort.sort(address, count)
        return null
    }
}
