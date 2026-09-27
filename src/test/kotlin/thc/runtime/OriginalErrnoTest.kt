// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.io.IOAccess
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

@EnabledOnOs(OS.LINUX, OS.MAC)
class OriginalErrnoTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val prefix = "build/original-errno"
    private val values = listOf(Int.MIN_VALUE.toLong(), -1L, 0L, 1L, Int.MAX_VALUE.toLong())
    private fun json(path: String) = Json.parse(File(root, path).readText())
    private fun context() = Context.newBuilder("thc").allowIO(IOAccess.NONE)
        .out(ByteArrayOutputStream()).err(ByteArrayOutputStream()).allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").build()
    private fun <T> entered(context: Context, action: (Language) -> T): T {
        context.initialize("thc"); context.enter()
        return try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
        finally { context.leave() }
    }
    private fun program(language: Language, backend: String, module: Map<String, Any?>): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun source(stage: String): Map<String, Any?> = CoreModules.merge(
        listOf("OriginalErrnoAudit", "THC.InterfaceClosure").map {
            json("$prefix/$stage/core/$it.json") as Map<String, Any?>
        })
    private fun original(module: Map<String, Any?>, symbol: String): List<Any?> =
        OriginalStdioChecks.foreignCalls(module).single {
            (((it[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as Map<*, *>)["symbol"] == symbol
        }
    /** The checked immediate State lambda lowers into the only consumer root. */
    private fun checkConsumer(module: Map<String, Any?>) {
        val binding = (module["bindings"] as List<Map<String, Any?>>).single { it["name"] == "originalResetErrno" }
        assertEquals(1L, binding["arity"])
        val body = binding["expr"] as List<*>
        assertEquals("lam", body[0])
        assertEquals(2, OriginalStdioChecks.nodes(body).count { it.firstOrNull() == "lam" })
        val run = body[2] as List<*>
        assertEquals("app", run[0]); assertEquals(listOf(false), run[3])
        val lambda = run[1] as List<*>
        assertEquals("lam", lambda[0])
        val state = (lambda[1] as List<Map<String, Any?>>).single()
        assertEquals("State# RealWorld", state["type"])
        assertEquals(OriginalStdioFixtures.scalar(null), state["rep"])
        assertEquals(false, state["lifted"]); assertEquals(false, state["coercion"])
        val argument = (run[2] as List<List<*>>).single()
        assertEquals("void", argument[0])
    }
    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.arguments.depth); assertEquals(0, state.arguments.retainedReferences())
        assertEquals(0, state.results.depth); assertEquals(0, state.results.retainedReferences())
        assertNull(state.pending)
    }
    private fun fixture(): List<Map<String, Any?>> {
        val manifest = json("$prefix/manifest.json") as Map<String, Any?>
        assertEquals(1L, manifest["schema"]); assertEquals("9.14.1", manifest["ghc"])
        assertEquals(true, manifest["strictAccepted"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf(
            "compiler/test-fixtures/OriginalErrnoAudit.hs", "compiler/test-fixtures/OriginalErrnoNative.hs",
            "test/haskell-fixtures/OriginalStdioFixtures.hs", "scripts/core_original_foreign.py"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf("$prefix/oracle.json") +
            listOf("pre", "post").flatMap { listOf("$prefix/$it/core/OriginalErrnoAudit.json",
                "$prefix/$it/core/THC.InterfaceClosure.json", "$prefix/$it/originalResetErrno.audit.json") }, "$prefix/")
        val rows = json("$prefix/oracle.json") as List<Map<String, Any?>>
        assertEquals(values, rows.map { it["value"] })
        for (row in rows) {
            val value = row["value"]
            assertEquals(value, row["roundTrip"]); assertEquals(0L, row["successResult"])
            assertEquals(value, row["successErrno"]); assertEquals(-1L, row["failureResult"])
            assertEquals(StdioHostAbi.load().error(4), row["failureErrno"])
            assertEquals(0L, row["resetErrno"])
        }
        return rows
    }

    @Test fun genuineSetterAndGetterMatchNativeOnFirstCompiledCalls() {
        val rows = fixture()
        for (stage in listOf("pre", "post")) {
            val source = source(stage)
            checkConsumer(source)
            val setter = original(source, "__hscore_set_errno")
            val getter = original(source, "__hscore_get_errno")
            assertEquals(2, OriginalStdioChecks.foreignCalls(source).size)
            OriginalStdioChecks.audit(json("$prefix/$stage/originalResetErrno.audit.json") as Map<String, Any?>,
                "main:OriginalErrnoAudit.originalResetErrno", listOf("__hscore_set_errno", "__hscore_get_errno"))
            for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
                val stdio = Language.currentState().stdio
                val set = program(language, backend, OriginalStdioChecks.rawModule(setter, source))
                val get = program(language, backend, OriginalStdioChecks.rawModule(getter, source))
                val reset = program(language, backend, CoreModules.reachable(source, "originalResetErrno") + ("instrument" to true))
                val setTarget = set.entryTarget("entry"); val getTarget = get.entryTarget("entry")
                val resetTarget = reset.entryTarget("originalResetErrno")
                val targets = listOf(setTarget, getTarget, resetTarget)
                fun invoke(program: ExecutableProgram, target: RootCallTarget, args: Array<Any?>, compiled: Boolean): Any? {
                    val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    val result = Calls.target(target, args)
                    if (compiled) {
                        assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong(),
                            "$stage/$backend enters the one scalar consumer root")
                        valid(target)
                    }
                    released(language)
                    return result
                }
                fun exercise(compiled: Boolean) {
                    for (row in rows) {
                        assertEquals(0L, invoke(set, setTarget, arrayOf(0L, row["value"], Unit), compiled))
                        assertEquals(row["roundTrip"], invoke(get, getTarget, arrayOf(0L, Unit), compiled))
                        assertEquals(row["successResult"], stdio.write(1L, ManagedAddress.fromByteArray(byteArrayOf()), 0L))
                        assertEquals(row["successErrno"], invoke(get, getTarget, arrayOf(0L, Unit), compiled))
                        assertEquals(row["failureResult"], stdio.write(-1L, ManagedAddress.fromByteArray(byteArrayOf()), 0L))
                        assertEquals(row["failureErrno"], invoke(get, getTarget, arrayOf(0L, Unit), compiled))
                        assertEquals(row["resetErrno"], invoke(reset, resetTarget, arrayOf(0L, 0L), compiled))
                        assertEquals(0L, invoke(get, getTarget, arrayOf(0L, Unit), compiled))
                    }
                }
                exercise(false)
                targets.forEach { it.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(it, true); valid(it) }
                exercise(true)
            } }
        }
    }

    @Test fun invalidCarriersAndProofsRejectBeforeChangingErrno() {
        val source = source("pre")
        val setter = original(source, "__hscore_set_errno")
        for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
            val stdio = Language.currentState().stdio
            val target = program(language, backend, OriginalStdioChecks.rawModule(setter, source)).entryTarget("entry")
            for (args in listOf(arrayOf<Any?>(0L, 1L, 9L), arrayOf<Any?>(0L, 1L, null), arrayOf<Any?>(0L, null, Unit),
                arrayOf<Any?>(0L, true, Unit), arrayOf<Any?>(0L, ManagedAddress.nullAddress(), Unit),
                arrayOf<Any?>(0L, Int.MAX_VALUE.toLong() + 1, Unit), arrayOf<Any?>(0L, Int.MIN_VALUE.toLong() - 1, Unit),
                arrayOf<Any?>(0L, Long.MIN_VALUE, Unit), arrayOf<Any?>(0L, Long.MAX_VALUE, Unit))) {
                stdio.setErrno(-17L)
                assertThrows(Throwable::class.java) { Calls.target(target, args) }
                assertEquals(-17L, stdio.errno()); released(language)
            }
            for (index in 0..1) assertThrows(RuntimeFault::class.java) {
                program(language, backend, OriginalStdioChecks.rawModule(setter, source, storedMutation = index))
            }
            val scalarModule = OriginalStdioChecks.rawModule(setter, source)
            val scalarState = OriginalStdioChecks.foreignCalls(scalarModule).single() as MutableList<Any?>
            val metadata = scalarState[6] as MutableMap<String, Any?>
            metadata["rep"] = OriginalStdioFixtures.scalar(null)
            (metadata["foreignCall"] as MutableMap<String, Any?>)["resultRep"] = OriginalStdioFixtures.scalar(null, false)
            assertThrows(RuntimeFault::class.java) {
                program(language, backend, scalarModule)
            }
            for (lowered in listOf(false, true)) {
                val module = Json.parse(Json.stringify(OriginalStdioChecks.rawModule(setter, source))) as MutableMap<String, Any?>
                val call = OriginalStdioChecks.foreignCalls(module).single() as MutableList<Any?>
                if (lowered) (call[2] as MutableList<Any?>)[1] =
                    listOf("lit", "int", "1", mapOf("rep" to OriginalStdioFixtures.scalar(null)))
                else call[1] = listOf("var", "p0", mapOf("rep" to OriginalStdioFixtures.closure()))
                assertThrows(RuntimeFault::class.java) { program(language, backend, module) }
            }
        } }
    }

    @Test fun errnoIsIsolatedByContextAndCarrierAndSurvivesReentry() {
        context().use { first -> context().use { second ->
            entered(first) { Language.currentState().stdio.setErrno(-3L) }
            entered(second) {
                val stdio = Language.currentState().stdio
                assertEquals(0L, stdio.errno()); stdio.setErrno(7L)
            }
            Executors.newSingleThreadExecutor().use { worker ->
                worker.submit {
                    entered(first) {
                        val stdio = Language.currentState().stdio
                        assertEquals(0L, stdio.errno()); stdio.setErrno(Int.MIN_VALUE.toLong())
                    }
                }.get()
                entered(first) { assertEquals(-3L, Language.currentState().stdio.errno()) }
                worker.submit {
                    entered(first) { assertEquals(Int.MIN_VALUE.toLong(), Language.currentState().stdio.errno()) }
                }.get()
            }
            entered(second) { assertEquals(7L, Language.currentState().stdio.errno()) }
            entered(first) {
                val stdio = Language.currentState().stdio
                assertEquals(-3L, stdio.errno()); stdio.setErrno(0L); assertEquals(0L, stdio.errno())
                stdio.setErrno(-1L); stdio.nativeError(0L); assertEquals(-1L, stdio.errno())
            }
        } }
    }
}
