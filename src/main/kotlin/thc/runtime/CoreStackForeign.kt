// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

/** Only the original local clone FCall; no native stack layout or remote clone ABI. */
internal object CoreStackForeign {
    const val CLONE = "stg_cloneMyStackzh"
    private val scalarKeys = setOf("kind", "primReps", "evaluated")
    private val tupleKeys = scalarKeys + setOf("aggregate", "components")
    private val descriptorKeys = setOf("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep")
    private val snapshotReps = listOf("BoxedRep (Just Unlifted)")

    private fun requireProof(condition: Boolean, detail: String) {
        if (!condition) throw RuntimeFault("Invalid original stack clone: $detail")
    }
    private fun one(value: Any?) = (value is Int || value is Long) && (value as Number).toLong() == 1L
    private fun scalar(raw: Any?, kind: String, reps: List<String>, evaluated: Boolean? = null): Boolean {
        val value = raw as? Map<*, *> ?: return false
        return value.keys == scalarKeys && value["kind"] == kind && value["primReps"] == reps &&
            value["evaluated"] is Boolean && (evaluated == null || value["evaluated"] == evaluated)
    }
    private fun result(raw: Any?, declared: Boolean = false): Boolean {
        val value = raw as? Map<*, *> ?: return false
        val components = value["components"] as? List<*> ?: return false
        return value.keys == tupleKeys && value["kind"] == "unknown" && value["aggregate"] == "unboxed-tuple" &&
            value["primReps"] == snapshotReps && value["evaluated"] is Boolean &&
            (!declared || value["evaluated"] == false) && components.size == 2 &&
            scalar(components[0], "void", emptyList(), true) && scalar(components[1], "object", snapshotReps, true)
    }
    fun validateHead(function: List<Any?>, defined: Boolean) {
        requireProof(function.size == 3 && function[0] == "var" && function[1] is String &&
            (function[1] as String).isNotEmpty() && !defined &&
            scalar(CoreRepresentations.metadata(function)?.get("rep"), "closure", listOf("BoxedRep (Just Lifted)"), true),
            "unresolved declared foreign variable required")
    }
    /** Before generic analyses cast variable IDs, reject malformed recognized heads. */
    fun validateHeads(value: Any?) {
        when (value) {
            is Map<*, *> -> value.values.forEach(::validateHeads)
            is List<*> -> {
                if (value.firstOrNull() == "app") {
                    val meta = value.getOrNull(6) as? Map<*, *>
                    val descriptor = meta?.get("foreignCall") as? Map<*, *>
                    if ((descriptor?.get("target") as? Map<*, *>)?.get("symbol") == CLONE)
                        validateHead(value.getOrNull(1) as? List<Any?>
                            ?: throw RuntimeFault("Invalid original stack clone: missing variable head"), false)
                }
                value.forEach(::validateHeads)
            }
        }
    }
    fun validate(metadata: Any?, argumentReps: List<*>, flags: List<*>): Boolean {
        val meta = metadata as? Map<*, *> ?: return false
        val descriptor = meta["foreignCall"] as? Map<*, *> ?: return false
        val target = descriptor["target"] as? Map<*, *> ?: return false
        if (target["symbol"] != CLONE) return false
        requireProof(descriptor.keys == descriptorKeys && one(descriptor["schema"]), "descriptor schema")
        requireProof(target.keys == setOf("kind", "symbol", "unit", "isFunction") &&
            target["kind"] == "static" && target["unit"] == "ghc-internal" && target["isFunction"] == true,
            "static ghc-internal function target")
        requireProof(descriptor["convention"] == "prim" && descriptor["safety"] == "safe", "calling convention/safety")
        requireProof(one(descriptor["arity"]) && one(descriptor["suppliedArity"]), "saturated arity")
        val declared = descriptor["argumentReps"] as? List<*>
        requireProof(declared?.size == 1 && scalar(declared[0], "void", emptyList(), false), "declared State argument")
        requireProof(argumentReps.size == 1 && scalar(argumentReps[0], "void", emptyList()), "actual State argument")
        requireProof(flags == listOf(false), "unlifted State flag")
        requireProof(result(descriptor["resultRep"], true) && result(meta["rep"]), "State/snapshot tuple result")
        return true
    }
    /** Occurrence metadata may not relabel an existing scalar or boxed binding as State. */
    fun validateState(proof: CoreRepresentation) {
        requireProof(proof.present && proof.kind == CoreKind.VOID && proof.primReps == emptyList<String>() &&
            !proof.isAggregate && !proof.isVector, "lowered State argument")
    }
    fun validateBinding(proof: CoreRepresentation?) {
        if (proof != null && proof.present) {
            requireProof(!proof.isAggregate && !proof.isVector &&
                proof.kind in setOf(CoreKind.VOID, CoreKind.UNKNOWN) &&
                (proof.primReps == null || proof.primReps == emptyList<String>()), "stored State argument")
        }
    }
}
