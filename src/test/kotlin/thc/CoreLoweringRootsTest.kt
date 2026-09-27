// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.runtime.BytecodeProgram
import thc.runtime.Calls
import thc.runtime.Closure
import thc.runtime.ExecutableProgram
import thc.runtime.Program

/** A top-level binding is not a one-root promise: its nested closure has a
 * separate lexical environment and executable target, reused across calls. */
class CoreLoweringRootsTest {
    @Test fun nestedClosureRootsAreNecessaryAndAreNotRecreatedPerInvocationOrAsyncCheckpoint() {
        val integer = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
        val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
        fun binder(id: String) = mapOf("id" to id, "name" to id, "lifted" to false, "rep" to integer)
        fun variable(id: String) = listOf("var", id, mapOf("rep" to integer))
        val add = listOf("app", listOf("prim", "+#"), listOf(variable("outer"), variable("inner")),
            listOf(false, false), false, false, mapOf("rep" to integer))
        val inner = listOf("lam", listOf(binder("inner")), add, mapOf("rep" to closure, "resultRep" to integer))
        val outer = listOf("lam", listOf(binder("outer")), inner, mapOf("rep" to closure, "resultRep" to closure))
        val binding = mapOf("id" to "entry", "name" to "entry", "arity" to 1, "lifted" to true, "rep" to closure, "expr" to outer)
        val module = mapOf("schema" to 1, "ghc" to "9.14.1", "module" to "Nested", "constructors" to emptyList<Any>(),
            "bindings" to listOf(binding))
        for (backend in listOf("ast", "bytecode")) for (async in listOf(false, true))
            Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val program: ExecutableProgram = if (backend == "bytecode") BytecodeProgram(language, module, async)
                        else Program(language, module, async)
                    val roots = program.diagnostics()["loweredRootCount"]
                    if (backend == "bytecode") assertEquals(3L, (roots as Number).toLong(),
                        "One legacy module initializer and two lexical function roots; async cuts are not new roots")
                    val entry = program.entryValue("entry")
                    val host = program.hostEntryTarget(1)
                    var nestedTarget: RootCallTarget? = null
                    for ((left, right) in listOf(40L to 2L, Long.MAX_VALUE to 1L, -4L to 9L)) {
                        val nested = Calls.target(host, arrayOf(entry, arrayOf<Any?>(left))) as Closure
                        if (nestedTarget == null) nestedTarget = nested.target else assertSame(nestedTarget, nested.target)
                        assertEquals(left + right, Calls.target(host, arrayOf(nested, arrayOf<Any?>(right))))
                        assertEquals(roots, program.diagnostics()["loweredRootCount"], "Closure instances reuse lowered targets")
                    }
                } finally { context.leave() }
            }
    }
}
