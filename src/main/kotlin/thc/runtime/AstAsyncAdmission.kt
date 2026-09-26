// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

/** Construction-time suspension effects for the execute route actually used by a parent.
 * Unknown forms default to MAY_SUSPEND; a known mask never proves a child safe. */
internal object AstAsyncAdmission {
    private enum class Route { TUPLE, LONG, OTHER }
    private enum class Effect { NO_SUSPEND, CAPTURED, MAY_SUSPEND }

    fun validate(bindings: List<Map<String, Any?>>) {
        for (binding in bindings) {
            val expression = binding["expr"] as? List<*>
                ?: throw RuntimeFault("AST async binding has no expression: ${binding["id"]}")
            val effect = when (expression.firstOrNull()) {
                "lam" -> lambda(expression)
                "lit", "void" -> Effect.NO_SUSPEND
                else -> Effect.MAY_SUSPEND // Global initializers have no captured entry frame.
            }
            if (effect == Effect.MAY_SUSPEND)
                throw RuntimeFault("AST async capture is not complete for ${binding["id"]}")
        }
    }

    private fun lambda(expression: List<*>): Effect {
        val arguments = expression.getOrNull(1) as? List<*> ?: return Effect.MAY_SUSPEND
        val body = expression.getOrNull(2) as? List<*> ?: return Effect.MAY_SUSPEND
        // EntryArguments may force strict formals before FunctionRoot can capture.
        // A tuple formal may pass through a loan that is released before resume.
        val formals = LinkedHashMap<String, Map<String, Any?>>()
        if (CoreEntries.lambda(expression).any { it } || arguments.any {
                val binder = it as? Map<String, Any?> ?: return Effect.MAY_SUSPEND
                val id = binder["id"] as? String ?: return Effect.MAY_SUSPEND
                formals[id] = binder
                CoreRepresentations.binder(binder).isAggregate
            }) return Effect.MAY_SUSPEND
        val declared = CoreRepresentations.lambdaResult(expression)
        val actual = CoreRepresentations.expression(body as List<Any?>)
        val effective = try { actual.refine(declared) }
        catch (_: RuntimeFault) { return Effect.MAY_SUSPEND }
        val route = when {
            effective.isTuple -> Route.TUPLE
            (if (declared.kind == CoreKind.UNKNOWN) actual.kind else declared.kind) == CoreKind.LONG -> Route.LONG
            else -> Route.OTHER
        }
        return child(body, route, declared, formals)
    }

    private fun child(expression: List<*>, route: Route, declared: CoreRepresentation,
                      formals: Map<String, Map<String, Any?>>): Effect = when (expression.firstOrNull()) {
        "lit", "void" -> Effect.NO_SUSPEND
        "lam" -> if (lambda(expression) == Effect.MAY_SUSPEND) Effect.MAY_SUSPEND else Effect.NO_SUSPEND
        "app" -> {
            val head = expression.getOrNull(1) as? List<*>
            val arguments = expression.getOrNull(2) as? List<*>
            val flags = expression.getOrNull(3) as? List<*>
            val name = if (head?.firstOrNull() == "prim") head.getOrNull(1) else null
            if (name == "annotateStack#" && route == Route.TUPLE && arguments?.size == 3 &&
                flags == listOf(true, true, false) &&
                rawOperand(arguments[0] as? List<*>, false, formals) &&
                rawOperand(arguments[2] as? List<*>, true, formals) &&
                (rawOperand(arguments[1] as? List<*>, false, formals) ||
                    (arguments[1] as? List<*>)?.let { it.firstOrNull() == "lam" && lambda(it) != Effect.MAY_SUSPEND } == true))
                Effect.CAPTURED else {
                val capturedRoute = when (name) {
                    "takeMVar#", "readMVar#" -> Route.TUPLE
                    "putMVar#" -> Route.OTHER
                    "waitRead#", "waitWrite#" -> if (declared.kind == CoreKind.VOID &&
                        declared.primReps == emptyList<String>()) Route.OTHER else null
                    "killThread#" -> if (declared.kind == CoreKind.VOID &&
                        declared.primReps == emptyList<String>()) Route.OTHER else null
                    "yield#" -> if (declared.kind == CoreKind.VOID && declared.primReps == emptyList<String>())
                        Route.OTHER else null
                    else -> null
                }
                // Unlifted operands pass through Evaluate. Their lexical carrier,
                // not an occurrence's claimed evaluatedness, must rule out Force.
                if (capturedRoute == route && arguments != null && flags?.size == arguments.size &&
                    flags.all { it is Boolean } &&
                    arguments.indices.all { index -> rawOperand(arguments[index] as? List<*>,
                        flags[index] == false, formals) })
                    Effect.CAPTURED else Effect.MAY_SUSPEND
            }
        }
        "case" -> {
            val scrutinee = expression.getOrNull(1) as? List<*>
            val alternative = (expression.getOrNull(3) as? List<*>)?.singleOrNull() as? List<*>
            val body = alternative?.getOrNull(3) as? List<*>
            // TupleCase.executeLong saves its suffix; its other typed methods do not.
            // The branch must itself be non-suspending, including Evaluate's demand.
            if (route != Route.LONG || declared.kind != CoreKind.LONG || declared.primReps != listOf("IntRep") ||
                scrutinee == null || CoreRepresentations.expression(scrutinee as List<Any?>).isTuple != true ||
                child(scrutinee, Route.TUPLE, CoreRepresentation.UNKNOWN, formals) != Effect.CAPTURED ||
                alternative?.firstOrNull() != "data" || body?.firstOrNull() != "lit" ||
                body.getOrNull(1) != "int" || !CoreRepresentations.expression(body as List<Any?>).isLong ||
                child(body, Route.LONG, declared, formals) != Effect.NO_SUSPEND)
                Effect.MAY_SUSPEND else Effect.CAPTURED
        }
        else -> Effect.MAY_SUSPEND
    }

    private fun rawOperand(expression: List<*>?, demanded: Boolean,
                           formals: Map<String, Map<String, Any?>>): Boolean = when (expression?.firstOrNull()) {
        "lit", "void" -> true
        "var" -> if (!demanded) true else {
            val formal = formals[expression.getOrNull(1)]
            formal != null && formal["lifted"] == false && CoreRepresentations.binder(formal).evaluated
        }
        else -> false
    }
}
