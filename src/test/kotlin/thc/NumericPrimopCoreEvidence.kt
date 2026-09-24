// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.assertEquals
import thc.runtime.BytecodeProgram
import thc.runtime.CoreRepresentations
import thc.runtime.Program

/** Direct calls in one exported binding, without following references to other bindings. */
internal object NumericPrimopCoreEvidence {
    @Suppress("UNCHECKED_CAST")
    fun assertLoadableWrappers(context: Context, module: Map<String, Any?>, names: List<String>, backend: String) {
        val bindings = linkedMapOf<String, Map<String, Any?>>()
        for (name in names) {
            // The old per-entry audit checked every wrapper's complete dependency
            // closure, including branches that the native input corpus never takes.
            val reachable = CoreModules.reachable(module, name, strictLink = true)
            for (binding in reachable["bindings"] as List<Map<String, Any?>>)
                bindings.putIfAbsent(binding["id"] as String, binding)
        }
        val wrappers = module + ("bindings" to bindings.values.toList())
        context.initialize("thc")
        context.enter()
        try {
            val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
            when (backend) {
                "ast" -> Program(language, wrappers)
                "bytecode" -> BytecodeProgram(language, wrappers)
                else -> error("Unknown numeric primop backend: $backend")
            }
        } finally {
            context.leave()
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun calls(module: Map<String, Any?>, entry: String, singleBinding: Boolean = false): List<List<Any?>> {
        val reachable = CoreModules.reachable(module, entry)
        val bindings = reachable["bindings"] as List<Map<String, Any?>>
        if (singleBinding) assertEquals(1, bindings.size, "$entry must be one self-contained guest root")
        val binding = bindings.single { it["name"] == entry }
        val found = mutableListOf<List<Any?>>()
        fun visit(value: Any?) {
            when (value) {
                is List<*> -> {
                    if (value.size >= 7 && value[0] == "app" &&
                        (value[1] as? List<*>)?.firstOrNull() == "prim") found.add(value)
                    value.forEach(::visit)
                }
                is Map<*, *> -> value.values.forEach(::visit)
            }
        }
        visit(binding["expr"])
        return found
    }

    fun assertCall(calls: List<List<Any?>>, primitive: String, arguments: List<String>, result: String, label: String) {
        val matches = calls.filter { (it[1] as List<*>).getOrNull(1) == primitive }
        assertEquals(1, matches.size, "$label must contain the primitive directly exactly once: $primitive")
        val app = matches.single()
        val actualArguments = app[2] as List<*>
        assertEquals(arguments.size, actualArguments.size, "$label/$primitive arity")
        assertEquals(List(arguments.size) { false }, app[3], "$label/$primitive value arguments")
        assertEquals(arguments.map(::listOf), actualArguments.map {
            CoreRepresentations.expression(it as List<Any?>).primReps
        }, "$label/$primitive argument representations")
        assertEquals(listOf(result), CoreRepresentations.expression(app).primReps,
            "$label/$primitive result representation")
    }
}
