// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

/** Public THC v1 queries, with ordinary C fallback symbols for native GHC. */
internal object CoreCpuAffinity {
    const val SUPPORT = "thc_cpu_affinity_v1_support"
    const val APPLIED = "thc_cpu_affinity_v1_applied"

    fun validate(expr: List<Any?>, defined: Boolean): Boolean? {
        val metadata = CoreRepresentations.metadata(expr) ?: return null
        val call = metadata["foreignCall"] as? Map<*, *> ?: return null
        val target = call["target"] as? Map<*, *> ?: return null
        val symbol = target["symbol"]
        if (symbol != SUPPORT && symbol != APPLIED) return null
        fun requireProof(valid: Boolean, detail: String) {
            if (!valid) fault("Invalid THC CPU-affinity query: $detail")
        }
        fun scalar(proof: CoreRepresentation, primitive: String?) = proof.present &&
            !proof.isAggregate && !proof.isVector && proof.kind == (if (primitive == null) CoreKind.VOID else CoreKind.LONG) &&
            proof.primReps == if (primitive == null) emptyList<String>() else listOf(primitive)
        fun tuple(proof: CoreRepresentation): Boolean {
            val fields = proof.components ?: return false
            return proof.isTuple && !proof.isSum && !proof.isVector && fields.size == 2 &&
                scalar(fields[0], null) && scalar(fields[1], "Int32Rep") && proof.primReps == listOf("Int32Rep")
        }
        fun one(value: Any?) = value == 1 || value == 1L
        val function = expr.getOrNull(1) as? List<*>
        requireProof(expr.firstOrNull() == "app" && function?.size == 3 && function[0] == "var" &&
            function[1] is String && (function[1] as String).isNotEmpty() && !defined, "unresolved foreign identifier")
        @Suppress("UNCHECKED_CAST")
        CoreBoundThreadForeign.validateHead(function as List<Any?>, defined)
        requireProof(target.keys == setOf("kind", "symbol", "unit", "isFunction") &&
            target["kind"] == "static" && target["isFunction"] == true &&
            (target["unit"] == null || target["unit"] is String),
            "static function target")
        requireProof(call.keys == setOf("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep") &&
            one(call["schema"]) && call["convention"] == "ccall" && call["safety"] == "unsafe" &&
            one(call["arity"]) && one(call["suppliedArity"]), "exact v1 C ABI")
        val arguments = expr.getOrNull(2) as? List<*>
        val declared = call["argumentReps"] as? List<*>
        requireProof(arguments?.size == 1 && declared?.size == 1 && expr.getOrNull(3) == listOf(false) &&
            scalar(CoreRepresentations.parse(declared[0]), null) &&
            scalar(CoreRepresentations.expression(arguments[0] as? List<Any?> ?: emptyList()), null), "State# argument")
        requireProof(tuple(CoreRepresentations.parse(call["resultRep"])) && tuple(CoreRepresentations.expression(expr)),
            "State#/CInt result")
        return symbol == APPLIED
    }
}

internal class CpuAffinityQuery(private val applied: Boolean, @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("CPU-affinity query requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        requireVoidCarrier(state.execute(frame))
        val threads = GuestThreads.current(this)
        FrameAccess.writeLong(frame, slots[offset], if (applied) {
            if (threads.currentIdentity().affinityApplied) 1L else 0L
        } else threads.cpuAffinity.mode.ordinal.toLong())
        return null
    }
}
