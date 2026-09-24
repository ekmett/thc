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

class BoxedLexicalProofTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private fun proof(levity: String, kind: String = "object", evaluated: Boolean = false) =
        mapOf("kind" to kind, "primReps" to listOf(if (levity == "Nothing") "BoxedRep Nothing" else "BoxedRep (Just $levity)"),
            "evaluated" to evaluated)
    private fun variable(id: String, rep: Map<String, Any?>?) =
        listOf("var", id) + if (rep == null) emptyList() else listOf(mapOf("rep" to rep))
    private fun module(stored: Map<String, Any?>?, occurrence: Map<String, Any?>?, caseBinder: Boolean = false): Map<String, Any?> {
        val binder = mutableMapOf<String, Any?>("id" to "x", "name" to "x", "lifted" to true)
        if (stored != null) binder["rep"] = stored
        val body = if (!caseBinder) variable("x", occurrence) else listOf("case", variable("x", stored), "b",
            listOf(listOf("default", null, emptyList<String>(), variable("b", occurrence))),
            mapOf("rep" to occurrence, "binder" to mapOf("id" to "b", "rep" to occurrence)))
        return mapOf("schema" to 1, "ghc" to "9.14.1", "module" to "BoxedLexicalProof",
            "constructors" to emptyList<Any?>(), "bindings" to listOf(mapOf("id" to "entry", "name" to "entry",
                "arity" to 1, "lifted" to true, "expr" to listOf("lam", listOf(binder), body))))
    }
    private fun program(language: Language, module: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun visit(action: (Language, String) -> Unit) {
        for (backend in listOf("ast", "bytecode")) Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw").build().use { context ->
                context.initialize("thc"); context.enter()
                try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null), backend) }
                finally { context.leave() }
            }
    }
    @Test fun contradictoryBoxedOccurrencesAndCaseBindersFailBothLowerers() = visit { language, backend ->
        for ((stored, occurrence) in listOf(proof("Lifted", "data") to proof("Unlifted"),
                proof("Unlifted", "data") to proof("Lifted")))
            for (caseBinder in listOf(false, true)) for (diagnostic in listOf(false, true)) {
                val error = assertThrows(RuntimeFault::class.java) {
                    program(language, module(stored, occurrence, caseBinder) + ("diagnosticUnsupported" to diagnostic), backend)
                }
                assertTrue(error.message.orEmpty().contains("Conflicting Core boxed levity proofs"), error.message)
            }
    }
    @Test fun unknownLevityClassAndEvaluatednessRefineIndependently() {
        val unknown = CoreRepresentations.parse(proof("Nothing"))
        for (levity in listOf("Lifted", "Unlifted")) for (kind in listOf("object", "data", "closure")) {
            val exact = CoreRepresentations.parse(proof(levity, kind))
            for (merged in listOf(exact.refine(unknown), unknown.refine(exact),
                    exact.refine(CoreRepresentation.UNKNOWN), CoreRepresentation.UNKNOWN.refine(exact))) {
                assertEquals(exact.primReps, merged.primReps)
                assertEquals(exact.kind, merged.kind)
                assertFalse(merged.evaluated, "Known levity does not manufacture evaluatedness")
                assertThrows(RuntimeFault::class.java) {
                    merged.refine(CoreRepresentations.parse(proof(if (levity == "Lifted") "Unlifted" else "Lifted")))
                }
            }
            val evaluated = exact.refine(CoreRepresentations.parse(proof("Nothing", evaluated = true)))
            assertEquals(exact.primReps, evaluated.primReps)
            assertTrue(evaluated.evaluated)
            for (otherKind in listOf("object", "data", "closure")) {
                val other = CoreRepresentations.parse(proof(levity, otherKind))
                val merged = exact.refine(other)
                assertEquals(if (other.kind == CoreKind.OBJECT) exact.kind else other.kind, merged.kind)
                assertEquals(exact.primReps, merged.primReps)
                assertFalse(merged.evaluated)
            }
        }
        visit { language, backend ->
            val exact = proof("Lifted")
            val legacy = mapOf("kind" to "unknown", "primReps" to null, "evaluated" to false)
            for ((stored, occurrence) in listOf(null to exact, exact to null, legacy to exact, exact to legacy,
                    proof("Nothing") to exact, exact to proof("Nothing"), exact to exact)) {
                val program = program(language, module(stored, occurrence), backend)
                val token = Any()
                assertSame(token, Calls.target(program.hostEntryTarget(1), arrayOf(program.entryValue("entry"), arrayOf(token))))
            }
        }
    }
    @Test fun genuineBoxedNewtypeCastsPreserveLevityAndLazyFieldsInCompiledCode() = visit { language, backend ->
        for (stage in listOf("core", "cbv-post-core")) {
            val input = Json.parse(File(root, "build/$stage/CBVCoercionAudit.json").readText()) as Map<String, Any?>
            val bindings = (input["bindings"] as List<Map<String, Any?>>).associateBy { it["name"] }
            for ((name, levity) in listOf("wrapSpine" to "Lifted", "unwrapSpine" to "Lifted",
                    "wrapProduct" to "Unlifted", "unwrapProduct" to "Unlifted")) {
                val lambda = bindings.getValue(name)["expr"] as List<Any?>
                val binder = CoreRepresentations.binder((lambda[1] as List<Map<String, Any?>>).single())
                val body = lambda[2] as List<Any?>
                assertEquals("var", body[0], "Genuine newtype casts must erase to the lexical value")
                val occurrence = CoreRepresentations.expression(body)
                assertEquals(listOf("BoxedRep (Just $levity)"), binder.primReps)
                assertEquals(binder.primReps, occurrence.primReps)
                // GHC may cast the entire function, leaving the inner lambda
                // at its source representation. Both class proofs are retained.
                assertEquals(if (name.startsWith("unwrap")) CoreKind.OBJECT else CoreKind.DATA, binder.kind)
            }
            for (name in listOf("boxedCastEntry", "unliftedBoxedCastEntry")) {
                val program = program(language, CoreModules.reachable(input, name), backend)
                val host = program.hostEntryTarget(1); val entry = program.entryValue(name)
                val values = listOf(Long.MIN_VALUE, -4097L, -1L, 0L, 1L, 4097L, Long.MAX_VALUE)
                fun check(x: Long) = assertEquals(x, Calls.target(host, arrayOf(entry, arrayOf(x))))
                values.forEach(::check)
                val target = program.entryTarget(name)
                val targetClass = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
                targetClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                assertEquals(true, targetClass.getMethod("isValidLastTier").invoke(target))
                for (x in values.asReversed()) {
                    val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    check(x)
                    // Opaque helpers contribute entries too after guest inlining.
                    assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before)
                    assertEquals(true, targetClass.getMethod("isValidLastTier").invoke(target))
                }
                assertEquals(true, targetClass.getMethod("isValidLastTier").invoke(target))
                assertEquals(0L, (program.diagnostics().getValue("blackholes") as Number).toLong())
            }
        }
    }
}
