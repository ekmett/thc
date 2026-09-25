// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

/** Original GHC capability query only: THC does not implement forkOS/bound foreign TLS. */
internal object CoreBoundThreadForeign {
    private const val SYMBOL = "rtsSupportsBoundThreads"
    private val scalarKeys = setOf("kind", "primReps", "evaluated")
    private val tupleKeys = scalarKeys + setOf("aggregate", "components")
    private val descriptorKeys = setOf("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep")

    private fun requireProof(valid: Boolean, detail: String) {
        if (!valid) fault("Invalid original bound-thread support query: $detail")
    }
    private fun one(value: Any?) = (value is Int || value is Long) && (value as Number).toLong() == 1L
    private fun scalar(value: Any?, kind: String, reps: List<String>, evaluated: Boolean? = null): Boolean {
        val proof = value as? Map<*, *> ?: return false
        return proof.keys == scalarKeys && proof["kind"] == kind && proof["primReps"] == reps &&
            proof["evaluated"] is Boolean && (evaluated == null || proof["evaluated"] == evaluated)
    }
    private fun result(value: Any?, declared: Boolean = false): Boolean {
        val proof = value as? Map<*, *> ?: return false
        val fields = proof["components"] as? List<*> ?: return false
        return proof.keys == tupleKeys && proof["kind"] == "unknown" && proof["aggregate"] == "unboxed-tuple" &&
            proof["primReps"] == listOf("IntRep") && proof["evaluated"] is Boolean &&
            (!declared || proof["evaluated"] == false) && fields.size == 2 &&
            scalar(fields[0], "void", emptyList(), true) && scalar(fields[1], "long", listOf("IntRep"), true)
    }

    fun validateHead(function: List<Any?>, defined: Boolean) {
        requireProof(function.size == 3 && function[0] == "var" && function[1] is String &&
            (function[1] as String).isNotEmpty() && !defined &&
            scalar(CoreRepresentations.metadata(function)?.get("rep"), "closure", listOf("BoxedRep (Just Lifted)"), true),
            "unresolved original foreign variable required")
    }

    fun validateHeads(value: Any?) {
        when (value) {
            is Map<*, *> -> value.values.forEach(::validateHeads)
            is List<*> -> {
                if (value.firstOrNull() == "app") {
                    val target = ((value.getOrNull(6) as? Map<*, *>)?.get("foreignCall") as? Map<*, *>)?.get("target") as? Map<*, *>
                    if (target?.get("symbol") == SYMBOL) validateHead(value.getOrNull(1) as? List<Any?>
                        ?: fault("Invalid original bound-thread support query: missing variable head"), false)
                }
                value.forEach(::validateHeads)
            }
        }
    }

    fun validate(metadata: Any?, arguments: List<*>, flags: List<*>, resultProof: Any?): Boolean {
        val meta = metadata as? Map<*, *> ?: return false
        val descriptor = meta["foreignCall"] as? Map<*, *> ?: return false
        val target = descriptor["target"] as? Map<*, *> ?: return false
        if (target["symbol"] != SYMBOL) return false
        requireProof(descriptor.keys == descriptorKeys && one(descriptor["schema"]), "descriptor schema")
        requireProof(target.keys == setOf("kind", "symbol", "unit", "isFunction") && target["kind"] == "static" &&
            target["unit"] == "ghc-internal" && target["isFunction"] == true, "exact installed GHC target")
        requireProof(descriptor["convention"] == "ccall" && descriptor["safety"] == "unsafe" &&
            one(descriptor["arity"]) && one(descriptor["suppliedArity"]), "convention, safety or arity")
        val declared = descriptor["argumentReps"] as? List<*>
        requireProof(declared?.size == 1 && scalar(declared[0], "void", emptyList(), false) &&
            arguments.size == 1 && scalar(arguments[0], "void", emptyList()) && flags == listOf(false), "State# argument")
        requireProof(result(descriptor["resultRep"], true) && result(meta["rep"]) && result(resultProof),
            "State#/Int# tuple result (HsBool is StgInt, not CInt)")
        return true
    }

    fun validateOperand(lowered: CoreRepresentation, stored: CoreRepresentation?) {
        requireProof(lowered.present && !lowered.isAggregate && !lowered.isVector &&
            lowered.kind == CoreKind.VOID && lowered.primReps == emptyList<String>(), "lowered State#")
        if (stored != null && stored.present) requireProof(!stored.isAggregate && !stored.isVector &&
            stored.kind in setOf(CoreKind.VOID, CoreKind.UNKNOWN) &&
            (stored.primReps == null || stored.primReps == emptyList<String>()), "stored State#")
    }
}

internal class BoundThreadSupport(@field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Bound-thread support query requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        requireVoidCarrier(state.execute(frame))
        // A negative capability, not a current-thread query or a host RTS query.
        FrameAccess.writeLong(frame, slots[offset], 0L)
        return null
    }
}
