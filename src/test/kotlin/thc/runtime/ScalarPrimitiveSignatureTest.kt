@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File

class ScalarPrimitiveSignatureTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val unknown = mapOf("kind" to "unknown", "primReps" to null, "evaluated" to false)
    private fun proof(rep: String) = mutableMapOf<String, Any?>("kind" to "long", "primReps" to listOf(rep), "evaluated" to true)
    private fun module(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
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
    @Test fun genuineScalarApplicationsRejectConsistentlyForgedArgumentsAndResults() = visit { language, backend ->
        // Arithmetic, comparison (IntRep result), and a rep-changing conversion.
        for (name in listOf("plusInt64", "ltWord64", "int64ToWord64"))
            for (variant in listOf("arguments", "result", "omittedOccurrence", "unknownOccurrence"))
                for (diagnostic in listOf(false, true)) {
                    val input = CoreModules.reachable(module("build/explicit64-primops/core/Explicit64PrimopsAudit.json"), name)
                    val lambda = ((input["bindings"] as List<Map<String, Any?>>).single()["expr"] as List<Any?>)
                    val body = lambda[2] as MutableList<Any?>
                    assertEquals("app", body[0])
                    fun replace(rep: MutableMap<String, Any?>) {
                        rep["primReps"] = listOf(if (rep["primReps"] == listOf("Word64Rep")) "Int64Rep" else "Word64Rep")
                    }
                    if (variant == "result") {
                        replace((lambda[3] as Map<String, Any?>)["resultRep"] as MutableMap<String, Any?>)
                        replace((body[6] as Map<String, Any?>)["rep"] as MutableMap<String, Any?>)
                    } else {
                        val parameter = (lambda[1] as List<Map<String, Any?>>)[0]
                        replace(parameter["rep"] as MutableMap<String, Any?>)
                        val argument = (body[2] as List<MutableList<Any?>>)[0]
                        assertEquals(listOf("var", parameter["id"]), argument.take(2))
                        when (variant) {
                            "arguments" -> replace((argument[2] as Map<String, Any?>)["rep"] as MutableMap<String, Any?>)
                            "omittedOccurrence" -> argument.removeAt(2)
                            else -> (argument[2] as MutableMap<String, Any?>)["rep"] = unknown
                        }
                    }
                    val error = assertThrows(RuntimeFault::class.java) {
                        program(language, input + ("diagnosticUnsupported" to diagnostic), backend)
                    }
                    assertTrue(error.message.orEmpty().contains("Primitive representation mismatch: $name#"), error.message)
                }
    }
    @Test fun missingUnknownAndCorrectLegacyMetadataRemainCompatible() = visit { language, backend ->
        for (rep in listOf(null, unknown, proof("Int64Rep"))) {
            val binder = mutableMapOf<String, Any?>("id" to "x", "lifted" to false)
            if (rep != null) binder["rep"] = rep
            val variable = listOf("var", "x") + if (rep == null) emptyList() else listOf(mapOf("rep" to rep))
            fun app(left: List<Any?>, right: List<Any?>): List<Any?> =
                listOf("app", listOf("prim", "plusInt64#"), listOf(left, right), listOf(false, false)) +
                    if (rep == null) emptyList() else listOf(false, false, mapOf("rep" to rep))
            // Unknown metadata on a primitive still establishes an intrinsic Long
            // carrier, but must not turn that carrier into an exact register proof.
            val body = app(app(variable, variable), variable)
            val input = mapOf("schema" to 1, "ghc" to "9.14.1", "module" to "LegacyScalarSignature",
                "constructors" to emptyList<Any?>(), "bindings" to listOf(mapOf("id" to "entry", "name" to "entry",
                    "arity" to 1, "lifted" to true, "expr" to listOf("lam", listOf(binder), body))))
            val program = program(language, input, backend)
            for (x in listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE))
                assertEquals(x + x + x, Calls.target(program.hostEntryTarget(1), arrayOf(program.entryValue("entry"), arrayOf(x))))
        }
    }
    private fun compile(target: RootCallTarget) {
        val type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        type.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target))
    }
    @Test fun nativeNewtypeCastsAroundScalarArithmeticAndConversionKeepCompiledEntries() = visit { language, backend ->
        for (stage in listOf("core", "cbv-post-core")) for (name in listOf("addRaw", "rawToWord")) {
            val input = CoreModules.reachable(module("build/$stage/CBVCoercionAudit.json"), name)
            val program = program(language, input, backend)
            val arity = if (name == "addRaw") 2 else 1
            val host = program.hostEntryTarget(arity); val entry = program.entryValue(name)
            val values = listOf(Long.MIN_VALUE, -4097L, -1L, 0L, 1L, 4097L, Long.MAX_VALUE)
            fun check(x: Long) = assertEquals(if (arity == 2) x + 1 else x,
                Calls.target(host, arrayOf(entry, if (arity == 2) arrayOf(x, 1L) else arrayOf(x))))
            values.forEach(::check)
            val target = program.entryTarget(name); compile(target)
            for (x in values.asReversed()) {
                val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                check(x)
                assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
            }
            assertEquals(true, Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget").getMethod("isValidLastTier").invoke(target))
        }
    }
}
