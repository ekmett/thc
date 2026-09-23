@file:Suppress("UNCHECKED_CAST")
package thc.runtime

/** Exact Core runtime kinds are independent of evidence that a lifted value is already evaluated. */
internal enum class CoreKind { LONG, FLOAT, DOUBLE, ADDRESS, VOID, DATA, CLOSURE, OBJECT, UNKNOWN }

internal data class CoreRepresentation(
    val kind: CoreKind,
    val evaluated: Boolean = false,
    val present: Boolean = false,
    val primReps: List<String>? = null,
    val components: List<CoreRepresentation>? = null
) {
    val isTuple: Boolean get() = components != null
    val isLong: Boolean get() = kind == CoreKind.LONG
    val isFloat: Boolean get() = kind == CoreKind.FLOAT
    val isDouble: Boolean get() = kind == CoreKind.DOUBLE
    val isEvaluatedReference: Boolean get() = evaluated &&
        (kind == CoreKind.DATA || kind == CoreKind.CLOSURE || kind == CoreKind.ADDRESS)
    /** A lifted type alone cannot exclude a thunk; a stored WHNF proof can. */
    fun referenceCarrier(): Class<*>? = if (!evaluated) null else when (kind) {
        CoreKind.DATA -> DataValue::class.java
        CoreKind.CLOSURE -> Closure::class.java
        CoreKind.ADDRESS -> LiteralAddress::class.java
        else -> null
    }
    fun refine(other: CoreRepresentation): CoreRepresentation {
        if (isTuple && other.isTuple && !TupleShape.compatible(this, other))
            throw RuntimeFault("Conflicting logical tuple representation proofs")
        if (isTuple && other.present && !other.isTuple && other.kind != CoreKind.UNKNOWN ||
            other.isTuple && present && !isTuple && kind != CoreKind.UNKNOWN)
            throw RuntimeFault("Conflicting scalar and tuple representation proofs")
        val merged = when {
            kind == CoreKind.UNKNOWN -> other.kind
            other.kind == CoreKind.UNKNOWN || kind == other.kind -> kind
            kind in setOf(CoreKind.OBJECT, CoreKind.DATA, CoreKind.CLOSURE) &&
                other.kind in setOf(CoreKind.OBJECT, CoreKind.DATA, CoreKind.CLOSURE) ->
                if (other.kind == CoreKind.OBJECT) kind else other.kind
            else -> throw RuntimeFault("Conflicting Core representation proofs: $kind and ${other.kind}")
        }
        return CoreRepresentation(merged, evaluated || other.evaluated,
            present || other.present, other.primReps ?: primReps, other.components ?: components)
    }
    companion object { val UNKNOWN = CoreRepresentation(CoreKind.UNKNOWN) }
}

internal object CoreRepresentations {
    private val longs = setOf("IntRep", "WordRep", "Int8Rep", "Word8Rep", "Int16Rep", "Word16Rep",
        "Int32Rep", "Word32Rep", "Int64Rep", "Word64Rep")
    /** Validate every retained proof, including cold branches and unused binders. */
    fun validateAggregates(bindings: List<Map<String, Any?>>) {
        fun visit(value: Any?) {
            when (value) {
                is Map<*, *> -> value.forEach { (key, child) ->
                    if (key in setOf("rep", "resultRep", "joinResultRep") && child is Map<*, *>) parse(child)
                    visit(child)
                }
                is List<*> -> value.forEach(::visit)
            }
        }
        visit(bindings)
    }
    fun requireScalar(proof: CoreRepresentation, boundary: String) {
        if (proof.isTuple) throw UnsupportedCore("Unsupported Core aggregate representation: unboxed-tuple ($boundary)")
    }
    fun parse(value: Any?): CoreRepresentation {
        if (value == null) return CoreRepresentation.UNKNOWN
        val map = value as? Map<String, Any?> ?: throw RuntimeFault("Invalid Core representation metadata")
        val components = when (val aggregate = map["aggregate"]) {
            null -> null
            "unboxed-sum" -> throw UnsupportedCore("Unsupported Core aggregate representation: unboxed-sum")
            "unboxed-tuple" -> (map["components"] as? List<*>)?.map(::parse)
                ?: throw UnsupportedCore("Unsupported Core aggregate representation: unboxed-tuple lacks exact components")
            else -> throw RuntimeFault("Invalid Core aggregate representation: $aggregate")
        }
        val kind = when (map["kind"]) {
            "long" -> CoreKind.LONG; "address" -> CoreKind.ADDRESS; "void" -> CoreKind.VOID
            "float" -> CoreKind.FLOAT; "double" -> CoreKind.DOUBLE
            "data" -> CoreKind.DATA; "closure" -> CoreKind.CLOSURE; "object" -> CoreKind.OBJECT
            "unknown" -> CoreKind.UNKNOWN
            else -> throw RuntimeFault("Unknown Core representation kind ${map["kind"]}")
        }
        val reps = map["primReps"]?.let { values ->
            (values as? List<*>)?.map { it as? String ?: throw RuntimeFault("Invalid Core primitive representation") }
                ?: throw RuntimeFault("Invalid Core primitive representations")
        }
        if (kind == CoreKind.LONG && (reps?.size != 1 || reps[0] !in longs))
            throw RuntimeFault("Core Long proof lacks a supported primitive representation")
        if (kind == CoreKind.ADDRESS && reps != listOf("AddrRep"))
            throw RuntimeFault("Core address proof lacks AddrRep")
        if (kind == CoreKind.FLOAT && reps != listOf("FloatRep"))
            throw RuntimeFault("Core Float proof lacks FloatRep")
        if (kind == CoreKind.DOUBLE && reps != listOf("DoubleRep"))
            throw RuntimeFault("Core Double proof lacks DoubleRep")
        if (kind == CoreKind.VOID && reps != emptyList<String>())
            throw RuntimeFault("Core void proof has payload registers")
        if (kind in setOf(CoreKind.DATA, CoreKind.CLOSURE, CoreKind.OBJECT) &&
            (reps?.size != 1 || reps[0] !in setOf("BoxedRep (Just Lifted)", "BoxedRep (Just Unlifted)", "BoxedRep Nothing")))
            throw RuntimeFault("Core reference proof lacks a single boxed representation")
        val evaluated = map["evaluated"] as? Boolean ?: throw RuntimeFault("Missing Core evaluatedness proof")
        val proof = CoreRepresentation(kind, evaluated, true, reps, components)
        if (components != null) TupleShape.validate(proof)
        return proof
    }
    fun binder(binding: Map<String, Any?>): CoreRepresentation = parse(binding["rep"])
    fun metadata(expr: List<Any?>): Map<String, Any?>? {
        val index = when (expr.firstOrNull()) {
            "var", "prim" -> 2; "lit", "lam", "con" -> 3; "app" -> 6
            "let", "case" -> 4; "void" -> 1; else -> return null
        }
        return expr.getOrNull(index) as? Map<String, Any?>
    }
    fun expression(expr: List<Any?>): CoreRepresentation = parse(metadata(expr)?.get("rep"))
    fun lambdaResult(expr: List<Any?>): CoreRepresentation = parse(metadata(expr)?.get("resultRep"))
    fun caseBinder(expr: List<Any?>): CoreRepresentation =
        (metadata(expr)?.get("binder") as? Map<String, Any?>)?.let(::binder) ?: CoreRepresentation.UNKNOWN
    fun alternativeBinders(alt: List<Any?>): List<Map<String, Any?>> =
        (alt.getOrNull(4) as? Map<String, Any?>)?.get("binders") as? List<Map<String, Any?>> ?: emptyList()
    fun joinArity(binding: Map<String, Any?>): Int? {
        val value = binding["joinValueArity"] ?: return null
        val number = value as? Number ?: throw RuntimeFault("Invalid join value arity")
        val result = number.toInt()
        if (result < 0 || number.toDouble() != result.toDouble()) throw RuntimeFault("Invalid join value arity")
        return result
    }
    fun joinResult(binding: Map<String, Any?>): CoreRepresentation = parse(binding["joinResultRep"])
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
                parameters = all.take(arity)
                body = if (arity == all.size) rhs[2] as List<Any?> else {
                    val meta = CoreRepresentations.metadata(rhs)?.toMutableMap() ?: linkedMapOf()
                    binding["joinResultRep"]?.let { meta["rep"] = it }
                    if (meta.containsKey("entryStrict")) meta["entryStrict"] = CoreEntries.lambda(rhs).drop(arity)
                    listOf("lam", all.drop(arity), rhs[2], meta)
                }
            }
            CoreJoinDefinition(binding, binding["id"] as String, parameters, body, CoreRepresentations.joinResult(binding))
        }
    }

    fun validate(group: List<Map<String, Any?>>, body: List<Any?>, recursive: Boolean) {
        val joinsInGroup = definitions(group) ?: return
        val arities = joinsInGroup.associate { it.id to it.parameters.size }
        fun visit(expr: List<Any?>, tail: Boolean, shadowed: Set<String>) {
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
