// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.frame.VirtualFrame

/** Original GHC RTS declarations, translated to the JVM rather than fabricated RTS counters. */
internal enum class GcForeignOp(val symbol: String, val result: String?, val safety: String = "safe") {
    ENABLED("getRTSStatsEnabled", "IntRep"),
    STATS("getRTSStats", null),
    MINOR("performGC", null),
    MAJOR("performMajorGC", null),
    BLOCKING("performBlockingMajorGC", null),
    MONOTONIC("getMonotonicNSec", "Word64Rep", "unsafe");

    val arguments: List<String?> get() = if (this == STATS) listOf("AddrRep", null) else listOf(null)

    @TruffleBoundary fun invoke(): Long = when (this) {
        ENABLED -> 0L
        STATS -> fault("GHC RTS statistics are unavailable on the JVM; getRTSStatsEnabled is false")
        MONOTONIC -> System.nanoTime()
        else -> { System.gc(); 0L } // Advisory; JVM flags/collector decide when and what to collect.
    }
}

internal object CoreGcForeign {
    private val scalarKeys = setOf("kind", "primReps", "evaluated")
    private val tupleKeys = scalarKeys + setOf("aggregate", "components")
    private val descriptorKeys = setOf("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep")
    private fun requireProof(valid: Boolean, detail: String) {
        if (!valid) fault("Invalid original GC/clock call: $detail")
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
    private fun result(raw: Any?, rep: String?, declared: Boolean = false): Boolean {
        val proof = raw as? Map<*, *> ?: return false
        val fields = proof["components"] as? List<*> ?: return false
        return proof.keys == tupleKeys && proof["kind"] == "unknown" && proof["aggregate"] == "unboxed-tuple" &&
            proof["primReps"] == listOfNotNull(rep) && proof["evaluated"] is Boolean &&
            (!declared || proof["evaluated"] == false) && fields.size == (if (rep == null) 1 else 2) &&
            scalar(fields[0], null, true) && (rep == null || scalar(fields[1], rep, true))
    }
    fun validate(metadata: Any?, arguments: List<*>, flags: List<*>, resultProof: Any?): GcForeignOp? {
        val meta = metadata as? Map<*, *> ?: return null
        val call = meta["foreignCall"] as? Map<*, *> ?: return null
        val target = call["target"] as? Map<*, *> ?: return null
        val op = GcForeignOp.entries.firstOrNull { it.symbol == target["symbol"] } ?: return null
        requireProof(call.keys == descriptorKeys && exact(call["schema"], 1), "descriptor schema")
        requireProof(target.keys == setOf("kind", "symbol", "unit", "isFunction") &&
            target["kind"] == "static" && target["unit"] == "ghc-internal" && target["isFunction"] == true,
            "exact installed target")
        requireProof(call["convention"] == "ccall" && call["safety"] == op.safety &&
            exact(call["arity"], op.arguments.size) && exact(call["suppliedArity"], op.arguments.size),
            "convention, safety or arity")
        val declared = call["argumentReps"] as? List<*>
        requireProof(declared?.size == op.arguments.size && arguments.size == op.arguments.size &&
            op.arguments.indices.all { scalar(declared!![it], op.arguments[it], false) &&
                scalar(arguments[it], op.arguments[it]) } &&
            flags == List(op.arguments.size) { false }, "address/State# operands")
        requireProof(result(call["resultRep"], op.result, true) && result(meta["rep"], op.result) &&
            result(resultProof, op.result), "State/result tuple")
        return op
    }
    fun validateHead(function: List<Any?>, defined: Boolean) {
        requireProof(function.size == 3 && function[0] == "var" && function[1] is String &&
            (function[1] as String).isNotEmpty() && !defined &&
            scalar(CoreRepresentations.metadata(function)?.get("rep"), "BoxedRep (Just Lifted)", true),
            "unresolved original FCallId required")
    }
    fun validateOperand(op: GcForeignOp, index: Int, lowered: CoreRepresentation, stored: CoreRepresentation?) {
        val rep = op.arguments[index]
        requireProof(lowered.present && !lowered.isAggregate && !lowered.isVector &&
            lowered.kind.name.lowercase() == kind(rep) && lowered.primReps == listOfNotNull(rep), "lowered operand $index")
        if (stored != null && stored.present) requireProof(!stored.isAggregate && !stored.isVector &&
            stored.kind.name.lowercase() in setOf(kind(rep), "unknown") &&
            (stored.primReps == null || stored.primReps == listOfNotNull(rep)), "stored operand $index")
    }
}

internal class GcForeignExpression(private val op: GcForeignOp,
    @field:Children private var operands: Array<Expr>, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    private object ResumeCompleted : AstResumeStep {
        override fun resume(frame: VirtualFrame, input: Any?): Any? {
            if (input !== Unit) fault("Invalid completed GC/clock continuation")
            return null
        }
    }
    override fun execute(frame: VirtualFrame): Nothing = fault("GC/clock call requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        if (op == GcForeignOp.STATS) operands[0].executeRequiredAddress(frame)
        requireVoidCarrier(operands.last().execute(frame))
        val value = op.invoke()
        if (op.result != null) FrameAccess.writeLong(frame, slots[offset], value)
        if (op.safety == "safe" && AstControl.enabled(this)) {
            val compiled = CompilerDirectives.inCompiledCode()
            GuestThreads.pollCurrent(this, false)?.let { request ->
                request.compiledCapture = compiled
                throw AstCapture(request, SynchronousMasking.current(this)).append(ResumeCompleted)
            }
        }
        return null
    }
}
