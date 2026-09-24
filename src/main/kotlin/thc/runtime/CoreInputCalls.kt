@file:Suppress("UNCHECKED_CAST")
package thc.runtime

/** Lowering-only proof checks for known function inputs. Runtime layouts cover unknown
 * higher-order targets. No scalar signature is inferred from a physical carrier. */
internal object CoreInputCalls {
    private data class Binding(val proof: CoreRepresentation, val inputs: List<CoreRepresentation>?)
    fun validate(bindings: List<Map<String, Any?>>, constructors: Map<String, Map<String, Any?>> = emptyMap()) {
        val globals = bindings.associateBy { it["id"] as String }
        fun inputs(expr: List<Any?>, scope: Map<String, Binding>, seen: Set<String> = emptySet()): List<CoreRepresentation>? = when (expr[0]) {
            "lam" -> (expr[1] as List<Map<String, Any?>>).map(CoreRepresentations::binder)
            "con" -> constructors[expr[1]]?.takeIf { (it["kind"] ?: "boxed") == "boxed" }?.let { constructor ->
                val arity = (constructor["arity"] as? Number)?.toInt() ?: throw RuntimeFault("Missing constructor arity")
                val fields = constructor["fieldTypes"] as? List<*>
                if (fields != null && fields.size != arity) throw RuntimeFault("Constructor field type count mismatch")
                fields?.map(CoreRepresentations::parse) ?: List(arity) { CoreRepresentation.UNKNOWN }
            }
            "var" -> {
                val id = expr[1] as String
                if (id in scope) scope.getValue(id).inputs
                else if (id in seen) null
                else globals[id]?.let { inputs(it["expr"] as List<Any?>, emptyMap(), seen + id) }
            }
            "app" -> inputs(expr[1] as List<Any?>, scope, seen)?.let { signature ->
                val count = (expr[2] as List<*>).size
                if (count < signature.size) signature.drop(count) else null
            }
            else -> null
        }
        fun proof(expr: List<Any?>, scope: Map<String, Binding>): CoreRepresentation {
            val occurrence = CoreRepresentations.expression(expr)
            return if (expr[0] == "var") scope[expr[1]]?.proof?.refine(occurrence) ?: occurrence else occurrence
        }
        fun visit(expr: List<Any?>, scope: Map<String, Binding>) {
            when (expr[0]) {
                "app" -> {
                    val fn = expr[1] as List<Any?>
                    val args = expr[2] as List<List<Any?>>
                    inputs(fn, scope)?.let { signature ->
                        for (i in 0 until minOf(signature.size, args.size)) {
                            val actual = proof(args[i], scope)
                            val expected = signature[i]
                            if (expected.isTuple || actual.isTuple) {
                                if (!expected.isTuple || !actual.isTuple || !TupleShape.compatible(expected, actual))
                                    throw UnsupportedCore("Missing or conflicting exact tuple argument proof")
                            } else if (expected.isAggregate || actual.isAggregate) TupleShape.requireCompatible(expected, actual, component = true)
                        }
                    }
                    visit(fn, scope); args.forEach { visit(it, scope) }
                }
                "lam" -> {
                    val parameters = expr[1] as List<Map<String, Any?>>
                    visit(expr[2] as List<Any?>, scope + parameters.associate {
                        it["id"] as String to Binding(CoreRepresentations.binder(it), null)
                    })
                }
                "let" -> {
                    val group = expr[2] as List<Map<String, Any?>>
                    val recursive = expr[1] == true
                    val shadowed = scope + group.associate { it["id"] as String to Binding(CoreRepresentations.binder(it), null) }
                    var declarations = group.associate { it["id"] as String to Binding(CoreRepresentations.binder(it), null) }
                    // An alias/PAP may precede its lambda in a recursive group. Resolve
                    // only definition-site proofs, with all outer names shadowed first.
                    repeat(if (recursive) group.size else 1) {
                        val rhsScope = if (recursive) shadowed + declarations else scope
                        declarations = group.associate { it["id"] as String to Binding(CoreRepresentations.binder(it), inputs(it["expr"] as List<Any?>, rhsScope)) }
                    }
                    val local = scope + declarations
                    group.forEach { visit(it["expr"] as List<Any?>, if (recursive) local else scope) }
                    visit(expr[3] as List<Any?>, local)
                }
                "case" -> {
                    val read = CoreVectorMemory.readCase(expr, constructors)
                    if (read != null) {
                        read.arguments.forEach { visit(it, scope) }
                        visit(read.body, scope + mapOf(
                            read.stateBinder to Binding(CoreVectorMemory.stateProof, null),
                            read.vectorBinder to Binding(CoreVectors.proof32, null)))
                        return
                    }
                    visit(expr[1] as List<Any?>, scope)
                    val meta = CoreRepresentations.metadata(expr)
                    val binder = meta?.get("binder") as? Map<String, Any?>
                    val local = scope + ((expr[2] as String) to Binding(binder?.let(CoreRepresentations::binder) ?: proof(expr[1] as List<Any?>, scope), null))
                    (expr[3] as List<List<Any?>>).forEach { alternative ->
                        val info = alternative.getOrNull(4) as? Map<String, Any?>
                        val records = (info?.get("binders") as? List<Map<String, Any?>>).orEmpty().associateBy { it["id"] }
                        visit(alternative[3] as List<Any?>, local + (alternative[2] as List<String>).associateWith {
                            Binding(records[it]?.let(CoreRepresentations::binder) ?: CoreRepresentation.UNKNOWN, null)
                        })
                    }
                }
            }
        }
        bindings.forEach { visit(it["expr"] as List<Any?>, emptyMap()) }
    }
}
