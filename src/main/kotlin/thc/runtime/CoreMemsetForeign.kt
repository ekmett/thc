// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

/** The pinned ByteString CInt/CSize memset declaration, with checked writes. */
internal object CoreMemsetForeign {
    private val arguments = listOf("AddrRep", "Int32Rep", "Word64Rep", null)
    private val scalarKeys = setOf("kind", "primReps", "evaluated")
    private val tupleKeys = scalarKeys + setOf("aggregate", "components")
    private val descriptorKeys = setOf("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep")
    private fun requireProof(valid: Boolean, detail: String) {
        if (!valid) fault("Invalid original memset call: $detail")
    }
    private fun exact(value: Any?, n: Int) = (value is Int || value is Long) && (value as Number).toLong() == n.toLong()
    private fun kind(rep: String?) = when (rep) {
        null -> "void"; "AddrRep" -> "address"; "BoxedRep (Just Lifted)" -> "closure"; else -> "long"
    }
    private fun scalar(raw: Any?, rep: String?, evaluated: Boolean? = null): Boolean {
        val proof = raw as? Map<*, *> ?: return false
        return proof.keys == scalarKeys && proof["kind"] == kind(rep) &&
            proof["primReps"] == listOfNotNull(rep) && proof["evaluated"] is Boolean &&
            (evaluated == null || proof["evaluated"] == evaluated)
    }
    private fun result(raw: Any?, rep: String, declared: Boolean = false): Boolean {
        val proof = raw as? Map<*, *> ?: return false
        val fields = proof["components"] as? List<*> ?: return false
        return proof.keys == tupleKeys && proof["kind"] == "unknown" && proof["aggregate"] == "unboxed-tuple" &&
            proof["primReps"] == listOf(rep) && proof["evaluated"] is Boolean &&
            (!declared || proof["evaluated"] == false) && fields.size == 2 &&
            scalar(fields[0], null, true) && scalar(fields[1], rep, true)
    }
    fun validate(metadata: Any?, operands: List<*>, flags: List<*>, resultProof: Any?): Boolean {
        val meta = metadata as? Map<*, *> ?: return false
        val call = meta["foreignCall"] as? Map<*, *> ?: return false
        val target = call["target"] as? Map<*, *> ?: return false
        val unit = target["unit"]
        if (target["symbol"] != "memset") return false
        requireProof(call.keys == descriptorKeys && exact(call["schema"], 1), "descriptor schema")
        requireProof(target.keys == setOf("kind", "symbol", "unit", "isFunction") &&
            target["kind"] == "static" && target["isFunction"] == true &&
            isOriginalByteStringUnit(unit), "supported installed target")
        requireProof(call["convention"] == "ccall" && call["safety"] == "unsafe" &&
            exact(call["arity"], 4) && exact(call["suppliedArity"], 4), "convention, safety or arity")
        val declared = call["argumentReps"] as? List<*>
        requireProof(declared?.size == 4 && arguments.indices.all {
            scalar(declared[it], arguments[it], false) } && operands.size == 4 &&
            arguments.indices.all { scalar(operands[it], arguments[it]) } &&
            flags == listOf(false, false, false, false), "address, CInt/CSize and State# operands")
        requireProof(result(call["resultRep"], "AddrRep", true) && result(meta["rep"], "AddrRep") &&
            result(resultProof, "AddrRep"), "State#/result tuple")
        return true
    }
    fun validateHead(function: List<Any?>, defined: Boolean) {
        requireProof(function.size == 3 && function[0] == "var" && function[1] is String &&
            (function[1] as String).isNotEmpty() && !defined &&
            scalar(CoreRepresentations.metadata(function)?.get("rep"), "BoxedRep (Just Lifted)", true),
            "unresolved original FCallId required")
    }
    fun validateOperand(index: Int, lowered: CoreRepresentation, stored: CoreRepresentation?) {
        val rep = arguments[index]
        requireProof(lowered.present && !lowered.isAggregate && !lowered.isVector &&
            lowered.kind.name.lowercase() == kind(rep) && lowered.primReps == listOfNotNull(rep), "lowered operand $index")
        if (stored != null && stored.present) requireProof(!stored.isAggregate && !stored.isVector &&
            stored.kind.name.lowercase() in setOf(kind(rep), "unknown") &&
            (stored.primReps == null || stored.primReps == listOfNotNull(rep)), "stored operand $index")
    }
}

internal class MemsetExpression(@field:Children private var operands: Array<Expr>, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("memset requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val destination = operands[0].executeRequiredAddress(frame)
        val value = operands[1].executeRequiredInt(frame).toLong()
        val count = operands[2].executeRequiredLong(frame)
        requireVoidCarrier(operands[3].execute(frame))
        destination.fill(count, value)
        FrameAccess.writeObject(frame, slots[offset], destination)
        return null
    }
}
