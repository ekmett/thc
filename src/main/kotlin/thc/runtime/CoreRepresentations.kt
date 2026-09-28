// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

/** Exact Core runtime kinds are independent of evidence that a lifted value is already evaluated. */
internal enum class CoreKind { LONG, FLOAT, DOUBLE, ADDRESS, VOID, DATA, CLOSURE, OBJECT, VECTOR, UNKNOWN }

internal data class CoreRepresentation(
    val kind: CoreKind,
    val evaluated: Boolean = false,
    val present: Boolean = false,
    val primReps: List<String>? = null,
    val components: List<CoreRepresentation>? = null,
    val vector: CoreVector? = null,
    val alternatives: List<CoreRepresentation>? = null,
    val tagSlot: Int? = null,
    val alternativeSlots: List<List<Int>>? = null
) {
    // Preserve the original exported kind/PrimRep proof. Exact lowered PrimRep,
    // not the broad exported "long" category, selects the computational carrier.
    val narrowInteger: NarrowInteger? = if (kind == CoreKind.LONG)
        NarrowInteger.fromRep(primReps?.singleOrNull()) else null
    val isInt: Boolean get() = narrowInteger != null
    // Compute from immutable load-time metadata, not via ArrayList.equals in
    // a frame read. The latter has a concurrent-modification exception path
    // that can force a VirtualFrame to escape during partial evaluation.
    val isEvaluatedUnliftedObject: Boolean = evaluated && kind == CoreKind.OBJECT &&
        primReps == listOf("BoxedRep (Just Unlifted)")
    val isVector: Boolean get() = vector != null
    val isTuple: Boolean get() = components != null
    val isSum: Boolean get() = alternatives != null
    val isAggregate: Boolean get() = isTuple || isSum
    /** A vector remains one logical value and one raw reference in typed transport. */
    val isTypedTransport: Boolean get() = isAggregate || isVector
    // Like the evaluated-object proof above, this is derived from owned load-time
    // metadata. Parsing copies input lists; copy/refine construct a fresh proof.
    val isEmptyTuple: Boolean = present && kind == CoreKind.UNKNOWN && components?.isEmpty() == true && primReps?.isEmpty() == true
    val isLong: Boolean get() = kind == CoreKind.LONG && !isInt
    val isFloat: Boolean get() = kind == CoreKind.FLOAT
    val isDouble: Boolean get() = kind == CoreKind.DOUBLE
    val isEvaluatedReference: Boolean get() = evaluated &&
        (kind == CoreKind.DATA || kind == CoreKind.CLOSURE || kind == CoreKind.ADDRESS)
    /** A lifted type alone cannot exclude a thunk; a stored WHNF proof can. */
    fun referenceCarrier(): Class<*>? = if (!evaluated) null else when (kind) {
        CoreKind.DATA -> DataValue::class.java
        CoreKind.CLOSURE -> Closure::class.java
        CoreKind.ADDRESS -> ManagedAddress::class.java
        else -> null
    }
    fun refine(other: CoreRepresentation): CoreRepresentation {
        // GHC checks scalar types. Lowered Int and Long are different carriers;
        // names within either carrier do not impose another runtime type check.
        if (kind == CoreKind.LONG && other.kind == CoreKind.LONG &&
            primReps != null && other.primReps != null && isInt != other.isInt)
            throw RuntimeFault("Conflicting Core integral carriers")
        val boxed = exactBoxedRep()
        val otherBoxed = other.exactBoxedRep()
        if (boxed != null && otherBoxed != null && boxed != otherBoxed)
            throw RuntimeFault("Conflicting Core boxed levity proofs: $boxed and $otherBoxed")
        if ((isVector || other.isVector) && present && other.present && vector != other.vector)
            throw RuntimeFault("Conflicting Core vector representation proofs")
        if (isAggregate && other.isAggregate && !TupleShape.compatible(this, other))
            throw RuntimeFault("Conflicting logical aggregate representation proofs")
        if (isAggregate && !other.isAggregate && (other.kind != CoreKind.UNKNOWN || other.primReps != null) ||
            other.isAggregate && !isAggregate && (kind != CoreKind.UNKNOWN || primReps != null))
            throw RuntimeFault("Conflicting scalar and aggregate representation proofs")
        val merged = when {
            kind == CoreKind.UNKNOWN -> other.kind
            other.kind == CoreKind.UNKNOWN || kind == other.kind -> kind
            kind in setOf(CoreKind.OBJECT, CoreKind.DATA, CoreKind.CLOSURE) &&
                other.kind in setOf(CoreKind.OBJECT, CoreKind.DATA, CoreKind.CLOSURE) ->
                if (other.kind == CoreKind.OBJECT) kind else other.kind
            else -> throw RuntimeFault("Conflicting Core representation proofs: $kind and ${other.kind}")
        }
        // Unknown boxed levity refines either way; it cannot erase an existing
        // exact proof and thereby hide a later contradiction. Object class and
        // evaluatedness continue to refine independently of liftedness.
        val mergedReps = if (boxed != null && other.primReps == listOf("BoxedRep Nothing")) primReps
            else other.primReps ?: primReps
        return CoreRepresentation(merged, evaluated || other.evaluated,
            present || other.present, mergedReps, other.components ?: components, other.vector ?: vector,
            other.alternatives ?: alternatives, other.tagSlot ?: tagSlot, other.alternativeSlots ?: alternativeSlots)
    }
    private fun exactBoxedRep(): String? = if (present &&
        kind in setOf(CoreKind.OBJECT, CoreKind.DATA, CoreKind.CLOSURE))
        primReps?.singleOrNull()?.takeIf { it == "BoxedRep (Just Lifted)" || it == "BoxedRep (Just Unlifted)" }
        else null
    companion object { val UNKNOWN = CoreRepresentation(CoreKind.UNKNOWN) }
}

internal data class CoreJoinDefinition(val binding: Map<String, Any?>, val id: String,
    val parameters: List<Map<String, Any?>>, val body: List<Any?>, val result: CoreRepresentation)

/** Join annotations refer to retained value/coercion arguments, never GHC's pre-erasure arity. */
internal object CoreJoins {
    fun definitions(group: List<Map<String, Any?>>): List<CoreJoinDefinition>? {
        val arities = group.map(CoreRepresentations::joinArity)
        if (arities.all { it == null }) return null
        if (arities.any { it == null }) throw UnsupportedCore("Mixed local join and ordinary binding group")
        return group.mapIndexed { index, binding ->
            val arity = arities[index]!!
            val rhs = binding["expr"] as? List<Any?> ?: throw RuntimeFault("Join binding has no expression")
            val parameters: List<Map<String, Any?>>
            val body: List<Any?>
            if (arity == 0) { parameters = emptyList(); body = rhs }
            else {
                if (rhs.firstOrNull() != "lam") throw RuntimeFault("Join value arity exceeds its lambda prefix")
                val all = rhs[1] as List<Map<String, Any?>>
                if (arity > all.size) throw RuntimeFault("Join value arity exceeds its lambda prefix")
                if (arity == all.size) {
                    val result = CoreRepresentations.joinResult(binding)
                    TupleShape.requireCompatible(result, CoreRepresentations.lambdaResult(rhs))
                }
                parameters = all.take(arity)
                body = if (arity == all.size) rhs[2] as List<Any?> else {
                    val meta = CoreRepresentations.metadata(rhs)?.toMutableMap() ?: linkedMapOf()
                    binding["joinResultRep"]?.let { meta["rep"] = it }
                    if (meta.containsKey("entryStrict")) meta["entryStrict"] = CoreEntries.lambda(rhs).drop(arity)
                    listOf("lam", all.drop(arity), rhs[2], meta)
                }
            }
            // Binary sums already have an exact typed tag/payload layout. A
            // local join returns into that layout without storing a sum value
            // in its binder or introducing a closure/call boundary.
            CoreJoinDefinition(binding, binding["id"] as String, parameters, body, CoreRepresentations.joinResult(binding))
        }
    }

    fun validate(group: List<Map<String, Any?>>, body: List<Any?>, recursive: Boolean) {
        val joinsInGroup = definitions(group) ?: return
        val arities = joinsInGroup.associate { it.id to it.parameters.size }
        fun visit(expr: List<Any?>, tail: Boolean, shadowed: Set<String>) {
            CoreStateApplications.inline(expr)?.let { visit(it, tail, shadowed); return }
            fun isJoin(id: String) = id in arities && id !in shadowed
            when (expr.firstOrNull()) {
                "var" -> {
                    val id = expr[1] as String
                    if (isJoin(id) && (!tail || arities.getValue(id) != 0))
                        throw RuntimeFault("Local join escapes or is not exactly saturated: $id")
                }
                "app" -> {
                    val function = expr[1] as List<Any?>
                    val args = expr[2] as List<List<Any?>>
                    val id = if (function.firstOrNull() == "var") function[1] as String else null
                    if (id != null && isJoin(id)) {
                        if (!tail || args.size != arities.getValue(id))
                            throw RuntimeFault("Local join must be exactly saturated in tail position: $id")
                    } else visit(function, false, shadowed)
                    args.forEach { visit(it, false, shadowed) }
                }
                "lam" -> {
                    val ids = (expr[1] as List<Map<String, Any?>>).map { it["id"] as String }
                    visit(expr[2] as List<Any?>, false, shadowed + ids)
                }
                "let" -> {
                    val nested = expr[2] as List<Map<String, Any?>>
                    val ids = nested.map { it["id"] as String }.toSet()
                    val rhsScope = if (expr[1] == true) shadowed + ids else shadowed
                    val joins = definitions(nested)
                    if (joins != null) joins.forEach {
                        visit(it.body, tail, rhsScope + it.parameters.map { p -> p["id"] as String })
                    } else nested.forEach { visit(it["expr"] as List<Any?>, false, rhsScope) }
                    visit(expr[3] as List<Any?>, tail, shadowed + ids)
                }
                "case" -> {
                    visit(expr[1] as List<Any?>, false, shadowed)
                    (expr[3] as List<List<Any?>>).forEach {
                        visit(it[3] as List<Any?>, tail, shadowed + (expr[2] as String) + (it[2] as List<String>))
                    }
                }
            }
        }
        val ids = arities.keys
        joinsInGroup.forEach {
            visit(it.body, true, (if (recursive) emptySet() else ids) + it.parameters.map { p -> p["id"] as String })
        }
        visit(body, true, emptySet())
    }
}
