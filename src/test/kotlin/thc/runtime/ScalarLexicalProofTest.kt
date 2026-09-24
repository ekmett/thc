// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File

class ScalarLexicalProofTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private fun proof(rep: String) = mapOf("kind" to "long", "primReps" to listOf(rep), "evaluated" to true)
    private fun context() = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").build()
    private fun program(language: Language, module: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun module(stored: Map<String, Any?>?, occurrence: Map<String, Any?>?, body: List<Any?>? = null): Map<String, Any?> {
        val binder = mutableMapOf<String, Any?>("id" to "x", "name" to "x", "lifted" to false)
        if (stored != null) binder["rep"] = stored
        val variable = listOf("var", "x") + if (occurrence == null) emptyList() else listOf(mapOf("rep" to occurrence))
        val primitive = listOf("app", listOf("prim", "plusInt8#"), listOf(variable, variable), listOf(false, false),
            false, false, mapOf("rep" to proof("Int8Rep")))
        return mapOf("schema" to 1, "ghc" to "9.14.1", "module" to "ScalarLexicalProof", "constructors" to emptyList<Any?>(),
            "bindings" to listOf(mapOf("id" to "entry", "name" to "entry", "arity" to 1, "lifted" to true,
                "expr" to listOf("lam", listOf(binder), body ?: primitive))))
    }
    private fun visit(action: (Language, String) -> Unit) {
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null), backend) }
            finally { context.leave() }
        }
    }
    @Test fun tupleAndScalarArithmeticRejectContradictoryLexicalProofs() = visit { language, backend ->
        for (diagnostic in listOf(false, true)) {
            val tupleModule = Json.parse(File(root, "build/tuple-arithmetic/pre-core/TupleArithmeticAudit.json").readText()) as Map<String, Any?>
            val reached = CoreModules.reachable(tupleModule, "quotRemInt")
            val lambda = ((reached["bindings"] as List<Map<String, Any?>>).single()["expr"] as List<Any?>)
            val x = (lambda[1] as List<MutableMap<String, Any?>>)[0]
            x["rep"] = proof("WordRep")
            for (input in listOf(reached, module(proof("Word8Rep"), proof("Int8Rep")))) {
                val error = assertThrows(RuntimeFault::class.java) {
                    program(language, input + ("diagnosticUnsupported" to diagnostic), backend)
                }
                assertTrue(error.message.orEmpty().contains("Conflicting Core scalar representation proofs"), error.message)
            }
        }
    }
    @Test fun absentUnknownAndMatchingScalarProofsRemainCompatible() = visit { language, backend ->
        val exact = proof("Int8Rep")
        val unknown = mapOf("kind" to "unknown", "primReps" to null, "evaluated" to false)
        for ((stored, occurrence) in listOf(null to exact, unknown to exact, exact to null, exact to unknown, exact to exact)) {
            val program = program(language, module(stored, occurrence), backend)
            for (x in listOf(-128L, -1L, 0L, 1L, 127L))
                assertEquals((x * 2).toByte().toLong(), Calls.target(program.hostEntryTarget(1), arrayOf(program.entryValue("entry"), arrayOf(x))))
        }
        // The inner let is a different value, despite reusing the source identifier.
        val word = proof("WordRep")
        val binding = mapOf("id" to "x", "name" to "x", "lifted" to false, "rep" to word,
            "expr" to listOf("lit", "word", "18446744073709551615", mapOf("rep" to word)))
        val body = listOf("let", false, listOf(binding), listOf("var", "x", mapOf("rep" to word)), mapOf("rep" to word))
        val program = program(language, module(proof("IntRep"), null, body), backend)
        assertEquals(-1L, Calls.target(program.hostEntryTarget(1), arrayOf(program.entryValue("entry"), arrayOf(1L))))
    }
    @Test fun genuineUnliftedNewtypeCastsKeepTheirScalarRepresentation() = visit { language, backend ->
        val module = Json.parse(File(root, "build/core/CBVCoercionAudit.json").readText()) as Map<String, Any?>
        for (name in listOf("wrapRaw", "unwrapRaw")) {
            val lambda = (module["bindings"] as List<Map<String, Any?>>).single { it["name"] == name }["expr"] as List<Any?>
            val binder = (lambda[1] as List<Map<String, Any?>>).single()
            assertEquals(listOf("IntRep"), (binder["rep"] as Map<String, Any?>)["primReps"])
            val body = lambda[2] as List<Any?>
            assertEquals("var", body[0], "The genuine newtype cast must erase to a variable")
            assertEquals(listOf("IntRep"), CoreRepresentations.expression(body).primReps)
        }
        val program = program(language, CoreModules.reachable(module, "scalarCastEntry"), backend)
        for (x in listOf(Long.MIN_VALUE, -4097L, -1L, 0L, 1L, 4097L, Long.MAX_VALUE))
            assertEquals(x, Calls.target(program.hostEntryTarget(1), arrayOf(program.entryValue("scalarCastEntry"), arrayOf(x))))
    }
}
