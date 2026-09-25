// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.Node
import thc.Language

/** Exact GHC.Internal.TopHandler.runMainIO1 foreign declaration. Signal setup is separate. */
internal object CoreMainThreadForeign {
    private const val SYMBOL = "rts_setMainThread"
    private val scalarKeys = setOf("kind", "primReps", "evaluated")
    private val tupleKeys = scalarKeys + setOf("aggregate", "components")
    private val descriptorKeys = setOf("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep")
    private val weakReps = listOf("BoxedRep (Just Unlifted)")

    private fun requireProof(valid: Boolean, detail: String) {
        if (!valid) fault("Invalid original main-thread call: $detail")
    }
    private fun two(value: Any?) = (value is Int || value is Long) && (value as Number).toLong() == 2L
    private fun one(value: Any?) = (value is Int || value is Long) && (value as Number).toLong() == 1L
    private fun scalar(value: Any?, kind: String, reps: List<String>, declared: Boolean = false): Boolean {
        val proof = value as? Map<*, *> ?: return false
        return proof.keys == scalarKeys && proof["kind"] == kind && proof["primReps"] == reps &&
            proof["evaluated"] is Boolean && (!declared || proof["evaluated"] == false)
    }
    private fun result(value: Any?, declared: Boolean = false): Boolean {
        val proof = value as? Map<*, *> ?: return false
        val fields = proof["components"] as? List<*> ?: return false
        return proof.keys == tupleKeys && proof["kind"] == "unknown" && proof["aggregate"] == "unboxed-tuple" &&
            proof["primReps"] == emptyList<String>() && proof["evaluated"] is Boolean &&
            (!declared || proof["evaluated"] == false) && fields.size == 1 &&
            scalar(fields[0], "void", emptyList()) && (fields[0] as Map<*, *>)["evaluated"] == true
    }

    fun validateHead(function: List<Any?>, defined: Boolean) {
        val proof = CoreRepresentations.metadata(function)?.get("rep")
        requireProof(function.size == 3 && function[0] == "var" && function[1] is String &&
            (function[1] as String).isNotEmpty() && !defined &&
            scalar(proof, "closure", listOf("BoxedRep (Just Lifted)")) && (proof as Map<*, *>)["evaluated"] == true,
            "unresolved declared foreign variable required")
    }

    fun validateHeads(value: Any?) {
        when (value) {
            is Map<*, *> -> value.values.forEach(::validateHeads)
            is List<*> -> {
                if (value.firstOrNull() == "app") {
                    val target = ((value.getOrNull(6) as? Map<*, *>)?.get("foreignCall") as? Map<*, *>)?.get("target") as? Map<*, *>
                    if (target?.get("symbol") == SYMBOL) validateHead(value.getOrNull(1) as? List<Any?>
                        ?: fault("Invalid original main-thread call: missing variable head"), false)
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
            two(descriptor["arity"]) && two(descriptor["suppliedArity"]), "convention, safety or arity")
        val declared = descriptor["argumentReps"] as? List<*>
        requireProof(declared?.size == 2 && scalar(declared[0], "object", weakReps, true) &&
            scalar(declared[1], "void", emptyList(), true) && arguments.size == 2 &&
            scalar(arguments[0], "object", weakReps) && scalar(arguments[1], "void", emptyList()) &&
            flags == listOf(false, false), "Weak# ThreadId and State# arguments")
        requireProof(result(descriptor["resultRep"], true) && result(meta["rep"]) && result(resultProof),
            "single-State# tuple result")
        return true
    }

    fun validateOperand(index: Int, lowered: CoreRepresentation, stored: CoreRepresentation?) {
        val kind = if (index == 0) CoreKind.OBJECT else CoreKind.VOID
        val reps = if (index == 0) weakReps else emptyList()
        requireProof(lowered.present && !lowered.isAggregate && !lowered.isVector &&
            lowered.kind == kind && lowered.primReps == reps, "lowered argument $index")
        if (stored != null && stored.present) requireProof(!stored.isAggregate && !stored.isVector &&
            stored.kind in setOf(kind, CoreKind.UNKNOWN) && (stored.primReps == null || stored.primReps == reps),
            "stored argument $index")
    }

    @JvmStatic fun register(node: Node, weak: Any?) {
        val context = Language.currentState(node)
        val key = context.weaks.mainThreadKey(weak, context.threads)
        context.threads.registerMainThread(key)
    }
}

internal class RegisterMainThread(@field:Child private var weak: Expr, @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Main-thread registration requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val handle = weak.execute(frame)
        requireVoidCarrier(state.execute(frame))
        CoreMainThreadForeign.register(this, handle)
        return null
    }
}
