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
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout

@EnabledOnOs(OS.LINUX, OS.MAC)
class OriginalProcessIdentityTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val prefix = "build/original-process-identity"
    private val entries = linkedMapOf("originalGetPid" to "getpid", "originalGetEuid" to "geteuid")
    private fun json(path: String) = Json.parse(File(root, path).readText())
    private fun context(native: Boolean = true) = Context.newBuilder("thc").allowIO(IOAccess.NONE)
        .allowNativeAccess(native).out(ByteArrayOutputStream()).err(ByteArrayOutputStream())
        .allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()
    private fun <T> entered(context: Context, action: (Language) -> T): T {
        context.initialize("thc"); context.enter()
        return try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
        finally { context.leave() }
    }
    private fun source(stage: String) = CoreModules.merge(listOf("OriginalProcessIdentityAudit", "THC.InterfaceClosure")
        .map { json("$prefix/$stage/core/$it.json") as Map<String, Any?> })
    private fun program(language: Language, backend: String, module: Map<String, Any?>): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun original(module: Map<String, Any?>, symbol: String): List<Any?> =
        OriginalStdioChecks.foreignCalls(module).single {
            (((it[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as Map<*, *>)["symbol"] == symbol
        }
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.arguments.depth); assertEquals(0, state.arguments.retainedReferences())
        assertEquals(0, state.results.depth); assertEquals(0, state.results.retainedReferences())
        assertNull(state.pending)
    }
    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target))

    /** Each exported runRW State lambda is checked and immediately inlined. */
    private fun checkConsumer(module: Map<String, Any?>, name: String) {
        val binding = (module["bindings"] as List<Map<String, Any?>>).single { it["name"] == name }
        assertEquals(1L, binding["arity"])
        val body = binding["expr"] as List<*>
        assertEquals("lam", body[0]); assertEquals(2, OriginalStdioChecks.nodes(body).count { it.firstOrNull() == "lam" })
        val run = body[2] as List<*>
        assertEquals("app", run[0]); assertEquals(listOf(false), run[3])
        val lambda = run[1] as List<*>
        assertEquals("lam", lambda[0])
        val state = (lambda[1] as List<Map<String, Any?>>).single()
        assertEquals("State# RealWorld", state["type"])
        assertEquals(OriginalStdioFixtures.scalar(null), state["rep"])
        assertEquals(false, state["lifted"]); assertEquals(false, state["coercion"])
        assertEquals("void", ((run[2] as List<*>).single() as List<*>)[0])
    }
    private fun fixture() {
        val manifest = json("$prefix/manifest.json") as Map<String, Any?>
        assertEquals(1L, manifest["schema"]); assertEquals("9.14.1", manifest["ghc"])
        assertEquals(true, manifest["strictAccepted"]); assertEquals(entries.keys.toList(), manifest["entries"])
        assertTrue(isOriginalUnixUnit(manifest["unixUnit"]))
        for (stage in listOf("pre", "post"))
            assertEquals(manifest["unixUnit"], (((original(source(stage), "geteuid")[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as Map<*, *>)["unit"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf(
            "compiler/test-fixtures/OriginalProcessIdentityAudit.hs", "compiler/test-fixtures/OriginalProcessIdentityNative.hs",
            "test/haskell-fixtures/OriginalStdioFixtures.hs", "scripts/core_original_foreign.py",
            "src/main/java/thc/runtime/ProcessIdentity.java"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf("$prefix/oracle.json") +
            listOf("pre", "post").flatMap { stage -> listOf("$prefix/$stage/core/OriginalProcessIdentityAudit.json",
                "$prefix/$stage/core/THC.InterfaceClosure.json") + entries.keys.map { "$prefix/$stage/$it.audit.json" } }, "$prefix/")
        val oracle = json("$prefix/oracle.json") as Map<String, Long>
        assertEquals(setOf("pid", "parentPid", "euid"), oracle.keys)
        assertTrue(oracle.getValue("pid") in 1L..Int.MAX_VALUE.toLong())
        assertTrue(oracle.getValue("parentPid") in 0L..Int.MAX_VALUE.toLong())
        assertNotEquals(oracle["pid"], oracle["parentPid"])
        assertTrue(oracle.getValue("euid") in 0L..0xffff_ffffL)
        // The native fixture ran in its own process. Its PID is never used as
        // the expected identity of this JVM or encoded in guest Core.
    }

    @Test fun originalLiveQueriesMatchThisProcessOnFirstCompiledEntries() {
        fixture()
        val linker = Linker.nativeLinker()
        val effectiveUid = linker.downcallHandle(linker.defaultLookup().find("geteuid").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT))
        for (stage in listOf("pre", "post")) {
            val module = source(stage)
            assertEquals(2, OriginalStdioChecks.foreignCalls(module).size)
            for ((name, symbol) in entries) {
                checkConsumer(module, name)
                OriginalStdioChecks.audit(json("$prefix/$stage/$name.audit.json") as Map<String, Any?>,
                    "main:OriginalProcessIdentityAudit.$name", listOf(symbol))
                for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
                    val raw = program(language, backend, OriginalStdioChecks.rawModule(original(module, symbol), module))
                    val consumer = program(language, backend, CoreModules.reachable(module, name) + ("instrument" to true))
                    val rawTarget = raw.entryTarget("entry"); val consumerTarget = consumer.entryTarget(name)
                    val stdio = Language.currentState().stdio
                    fun expected(): Long = if (symbol == "getpid") ProcessHandle.current().pid()
                        else Integer.toUnsignedLong(effectiveUid.invokeExact() as Int)
                    fun exercise(compiled: Boolean) {
                        for (sentinel in listOf(-1L, 0L, 17L)) {
                            stdio.setErrno(sentinel)
                            for ((program, target, arguments) in listOf(Triple(raw, rawTarget, arrayOf<Any?>(0L, Unit)),
                                Triple(consumer, consumerTarget, arrayOf<Any?>(0L, 0L)))) {
                                val identity = expected()
                                val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                                assertEquals(identity, Calls.target(target, arguments), "$stage/$backend/$name")
                                assertEquals(sentinel, stdio.errno()); released(language)
                                if (compiled) {
                                    assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                                    valid(target)
                                }
                            }
                        }
                    }
                    exercise(false)
                    for (target in listOf(rawTarget, consumerTarget)) {
                        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true); valid(target)
                    }
                    exercise(true)
                } }
            }
        }
    }

    @Test fun stateAndNativePermissionAreCheckedBeforeQueryWithoutErrnoMutation() {
        val module = source("pre")
        for (backend in listOf("ast", "bytecode")) for (native in listOf(false, true)) context(native).use { context ->
            entered(context) { language ->
                val stdio = Language.currentState().stdio
                for (symbol in entries.values) {
                    val target = program(language, backend, OriginalStdioChecks.rawModule(original(module, symbol), module)).entryTarget("entry")
                    for (invalid in listOf(null, 0L, true, ManagedAddress.nullAddress())) {
                        stdio.setErrno(-23L)
                        val failure = assertThrows(RuntimeFault::class.java) { Calls.target(target, arrayOf(0L, invalid)) }
                        assertTrue(failure.message.orEmpty().contains("zero-width scalar carrier") ||
                            invalid == null && failure.message == "Uninitialized local binding", failure.message)
                        assertEquals(-23L, stdio.errno()); released(language)
                    }
                    if (!native) {
                        val failure = assertThrows(RuntimeFault::class.java) { Calls.target(target, arrayOf(0L, Unit)) }
                        assertTrue(failure.message.orEmpty().contains("requires native access"), failure.message)
                        assertEquals(-23L, stdio.errno()); released(language)
                    }
                }
            }
        }
    }

    @Test fun exactOwnersRepresentationsAndStoredStateCannotBeForged() {
        val module = source("pre")
        for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
            for ((name, symbol) in entries) {
                val call = original(module, symbol)
                fun rejects(change: (MutableList<Any?>, MutableMap<String, Any?>) -> Unit) {
                    val changed = Json.parse(Json.stringify(OriginalStdioChecks.rawModule(call, module))) as MutableMap<String, Any?>
                    val app = OriginalStdioChecks.foreignCalls(changed).single() as MutableList<Any?>
                    val descriptor = (app[6] as Map<*, *>)["foreignCall"] as MutableMap<String, Any?>
                    change(app, descriptor)
                    assertThrows(RuntimeFault::class.java) { program(language, backend, changed) }
                }
                for (unit in listOf("main", "ghc-internal-9.1401.0-inplace", "unix-2.8.7.0-inplace",
                    "unix-2.8.8.0-ABCD", "unix-2.8.8.0-nothex",
                    if (symbol == "getpid") "unix-2.8.8.0-inplace" else "ghc-internal"))
                    rejects { _, descriptor -> (descriptor["target"] as MutableMap<String, Any?>)["unit"] = unit }
                for (unit in listOf("unix-2.8.8.0-inplace", "unix-2.8.8.0-460b")) {
                    val installed = Json.parse(Json.stringify(call)) as MutableList<Any?>
                    (((installed[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as MutableMap<String, Any?>)["unit"] = unit
                    val changed = OriginalStdioChecks.rawModule(installed, module)
                    if (symbol == "geteuid") program(language, backend, changed)
                    else assertThrows(RuntimeFault::class.java) { program(language, backend, changed) }
                }
                for ((key, value) in listOf("schema" to true, "convention" to "capi", "safety" to "safe",
                    "arity" to 2L, "suppliedArity" to 0L)) rejects { _, descriptor -> descriptor[key] = value }
                for (rep in listOf("IntRep", "WordRep", if (symbol == "getpid") "Word32Rep" else "Int32Rep"))
                    rejects { app, descriptor ->
                        val metadata = app[6] as MutableMap<String, Any?>
                        for (result in listOf(metadata["rep"], descriptor["resultRep"])) {
                            val proof = result as MutableMap<String, Any?>
                            proof["primReps"] = listOf(rep)
                            (proof["components"] as MutableList<Any?>)[1] = OriginalStdioFixtures.scalar(rep)
                        }
                    }
                rejects { app, _ -> app[1] = listOf("var", "p0", mapOf("rep" to OriginalStdioFixtures.closure())) }
                rejects { app, _ -> (app[2] as MutableList<Any?>)[0] = listOf("lit", "int", "1",
                    mapOf("rep" to OriginalStdioFixtures.scalar(null))) }
                assertThrows(RuntimeFault::class.java, { program(language, backend,
                    OriginalStdioChecks.rawModule(call, module, storedMutation = 0)) }, name)
            }
        } }
    }
}
