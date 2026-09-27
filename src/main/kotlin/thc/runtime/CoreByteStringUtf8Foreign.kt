// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.CompilerDirectives

/** Exact installed pointer declarations; ByteArray# variants are not admitted. */
internal object CoreByteStringUtf8Foreign {
    private val argumentsReps = listOf("AddrRep", "Word64Rep", null)
    private val scalarKeys = setOf("kind", "primReps", "evaluated")
    private val tupleKeys = scalarKeys + setOf("aggregate", "components")
    private val descriptorKeys = setOf("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep")
    private fun requireProof(valid: Boolean, detail: String) {
        if (!valid) fault("Invalid original UTF-8 validation call: $detail")
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
    fun validate(metadata: Any?, arguments: List<*>, flags: List<*>, resultProof: Any?): Boolean? {
        val meta = metadata as? Map<*, *> ?: return null
        val call = meta["foreignCall"] as? Map<*, *> ?: return null
        val target = call["target"] as? Map<*, *> ?: return null
        val unit = target["unit"]
        if (target["symbol"] != "bytestring_is_valid_utf8") return null
        requireProof(call.keys == descriptorKeys && exact(call["schema"], 1), "descriptor schema")
        requireProof(target.keys == setOf("kind", "symbol", "unit", "isFunction") &&
            target["kind"] == "static" && target["isFunction"] == true &&
            isOriginalByteStringUnit(unit), "supported installed target")
        requireProof(call["convention"] == "ccall" && call["safety"] in setOf("safe", "unsafe") &&
            exact(call["arity"], 3) && exact(call["suppliedArity"], 3), "convention, safety or arity")
        val declared = call["argumentReps"] as? List<*>
        requireProof(declared?.size == 3 && argumentsReps.indices.all {
            scalar(declared[it], argumentsReps[it], false) } && arguments.size == 3 &&
            argumentsReps.indices.all { scalar(arguments[it], argumentsReps[it]) } &&
            flags == listOf(false, false, false), "address, CInt/CSize and State# operands")
        requireProof(result(call["resultRep"], "Int32Rep", true) && result(meta["rep"], "Int32Rep") &&
            result(resultProof, "Int32Rep"), "State#/result tuple")
        return call["safety"] == "safe"
    }
    fun validateHead(function: List<Any?>, defined: Boolean) {
        requireProof(function.size == 3 && function[0] == "var" && function[1] is String &&
            (function[1] as String).isNotEmpty() && !defined &&
            scalar(CoreRepresentations.metadata(function)?.get("rep"), "BoxedRep (Just Lifted)", true),
            "unresolved original FCallId required")
    }
    fun validateOperand(index: Int, lowered: CoreRepresentation, stored: CoreRepresentation?) {
        val rep = argumentsReps[index]
        requireProof(lowered.present && !lowered.isAggregate && !lowered.isVector &&
            lowered.kind.name.lowercase() == kind(rep) && lowered.primReps == listOfNotNull(rep), "lowered operand $index")
        if (stored != null && stored.present) requireProof(!stored.isAggregate && !stored.isVector &&
            stored.kind.name.lowercase() in setOf(kind(rep), "unknown") &&
            (stored.primReps == null || stored.primReps == listOfNotNull(rep)), "stored operand $index")
    }
}

internal class ByteStringUtf8Expression(private val safe: Boolean,
    @field:Children private var operands: Array<Expr>, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    private object ResumeCompleted : AstResumeStep {
        override fun resume(frame: VirtualFrame, input: Any?): Any? {
            if (input !== Unit) fault("Invalid completed UTF-8 validation continuation")
            return null
        }
    }
    override fun execute(frame: VirtualFrame): Nothing = fault("UTF-8 validation requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val address = operands[0].executeRequiredAddress(frame)
        val length = operands[1].executeRequiredLong(frame)
        requireVoidCarrier(operands[2].execute(frame))
        // A safe declaration admits an async poll only after C has returned and
        // its result is saved. Resumption must never invoke the foreign call again.
        FrameAccess.writeInt(frame, slots[offset], ManagedByteStringUtf8.validate(address, length).toInt())
        if (safe && AstControl.enabled(this)) {
            val compiled = CompilerDirectives.inCompiledCode()
            GuestThreads.pollCurrent(this, false)?.let { request ->
                request.compiledCapture = compiled
                throw AstCapture(request, SynchronousMasking.current(this)).append(ResumeCompleted)
            }
        }
        return null
    }
}
