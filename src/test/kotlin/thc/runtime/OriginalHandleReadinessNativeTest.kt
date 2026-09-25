// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.Instruction
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.io.IOAccess
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import thc.CoreModules
import thc.Json
import thc.Language
import thc.NativeIO
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** The FCall is GHC's installed c_isatty declaration, not a synthetic alias. */
class OriginalHandleReadinessNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/original-handle-readiness")
    @Suppress("UNCHECKED_CAST")
    private fun json(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun targets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val result = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val body = target.rootNode
            val nodes = if (body is BytecodeRoot) listOf(body) + body.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() }
                else listOf(body)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val callee = call.currentCallTarget as? RootCallTarget ?: continue
                if (callee.rootNode is GuestRoot) visit(callee)
            }
            result.add(target)
        }
        visit(entry)
        return result
    }

    @Test @EnabledOnOs(OS.LINUX)
    @EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
    fun privatePtyUsesOriginalIsattyOnItsOwnedNativeDescriptorAfterCompilation() {
        val oracle = ProcessBuilder(File(directory, "native/oracle").absolutePath, "--hold-pty").start()
        try {
            val announced = CompletableFuture.supplyAsync { oracle.inputStream.bufferedReader().readLine() }
                .get(15, TimeUnit.SECONDS)?.split('\t') ?: fail("PTY oracle ended before announcing its path")
            assertEquals(2, announced.size)
            assertTrue(announced[0].startsWith("/dev/pts/"), "Expected a private Linux PTY")
            assertEquals("1", announced[1], "Original GHC c_isatty must see the live slave")
            val path = ManagedAddress.fromByteArray(announced[0].toByteArray() + byteArrayOf(0))
            for (stage in listOf("pre", "post")) {
                val prefix = "build/original-handle-readiness/$stage"
                val module = CoreModules.merge(listOf("OriginalHandleReadinessAudit", "THC.InterfaceClosure")
                    .map { json("$prefix/core/$it.json") })
                for (backend in listOf("ast", "bytecode")) {
                    NativeIO.createContext(emptySet()).use { context ->
                        context.enter()
                        try {
                            val state = Language.currentState()
                            val fd = state.files.openOriginal(path, 0x100, 0) // Linux O_NOCTTY | O_RDONLY.
                            assertTrue(fd >= 3, "$stage/$backend: open the private PTY")
                            try {
                                val linked = CoreModules.reachable(module, "originalIsTerminal") + ("instrument" to true)
                                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                                val program = if (backend == "ast") Program(language, linked)
                                    else BytecodeProgram(language, linked)
                                val target = program.entryTarget("originalIsTerminal")
                                repeat(3) { assertEquals(1L, Calls.target(target, arrayOf(0L, fd))) }
                                val active = targets(target)
                                assertEquals(2, active.size, "Entry and original runRW local lambda")
                                active.forEach {
                                    it.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(it, true)
                                    valid(it)
                                }
                                val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                                assertEquals(1L, Calls.target(target, arrayOf(0L, fd)))
                                assertEquals(before + 2, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                                assertEquals(active, targets(target))
                                active.forEach(::valid)
                                val alias = state.files.duplicate(fd)
                                assertTrue(alias >= 3)
                                assertEquals(0L, state.files.close(fd))
                                assertEquals(1L, state.stdio.isTerminal(alias), "The last owned alias retains the PTY")
                                assertEquals(0L, state.files.close(alias))
                                assertEquals(0L, state.stdio.isTerminal(alias))
                                assertEquals(StdioHostAbi.load().error(4), state.stdio.errno())
                            } finally { state.files.close(fd) }
                        } finally { context.leave() }
                    }
                }
            }
        } finally {
            oracle.outputStream.write('\n'.code)
            oracle.outputStream.flush()
            if (!oracle.waitFor(5, TimeUnit.SECONDS)) {
                oracle.destroyForcibly()
                assertTrue(oracle.waitFor(5, TimeUnit.SECONDS), "PTY oracle did not terminate")
            }
            assertEquals(0, oracle.exitValue(), oracle.errorStream.bufferedReader().readText())
        }
    }

    @Test fun originalIsattyAndErrnoMatchNativeBeforeAndAfterExplicitCompilation() {
        val manifest = json("build/original-handle-readiness/manifest.json")
        assertEquals(1L, manifest["schema"])
        assertEquals(3L, manifest["nativeRows"])
        val inputs = manifest["inputHashes"] as Map<String, String>
        OriginalStdioChecks.hashes(root, inputs, setOf(
            "compiler/test-fixtures/OriginalHandleReadinessAudit.hs",
            "compiler/test-fixtures/OriginalHandleReadinessNative.hs",
            "test/haskell-fixtures/OriginalHandleReadinessFixtures.hs", "scripts/core_original_foreign.py"))
        val artifacts = manifest["artifactHashes"] as Map<String, String>
        OriginalStdioChecks.hashes(root, artifacts, setOf(
            "build/original-handle-readiness/oracle.json",
            "build/original-handle-readiness/pre/core/OriginalHandleReadinessAudit.json",
            "build/original-handle-readiness/post/core/OriginalHandleReadinessAudit.json"),
            "build/original-handle-readiness/")
        val rows = Json.parse(File(directory, "oracle.json").readText()) as List<Map<String, Number>>
        assertEquals(listOf(-1, 1, 2), rows.map { it.getValue("fd").toInt() })
        val abi = StdioHostAbi.load()
        for (row in rows) {
            assertEquals(0, row.getValue("result").toInt())
            assertEquals(if (row.getValue("fd").toInt() < 0) abi.error(4) else abi.notTerminal(),
                row.getValue("errno").toLong())
        }
        for (stage in listOf("pre", "post")) {
            val prefix = "build/original-handle-readiness/$stage"
            val module = CoreModules.merge(listOf("OriginalHandleReadinessAudit", "THC.InterfaceClosure")
                .map { json("$prefix/core/$it.json") })
            for (name in listOf("originalIsTerminal", "originalIsTerminalErrno")) {
                val audit = json("$prefix/$name.audit.json")
                assertEquals(true, audit["accepted"]); assertEquals(emptyList<Any?>(), audit["issues"])
                assertEquals(emptyList<Any?>(), audit["missingGlobals"])
                // runRW# exports as an immediately applied local State# lambda.
                // It is a second instrumented root, not another top-level binding.
                val binding = (module["bindings"] as List<Map<String, Any?>>).single { it["name"] == name }
                val entry = binding["expr"] as List<*>
                assertEquals("lam", entry[0])
                val application = entry[2] as List<*>
                assertEquals("app", application[0])
                val local = application[1] as List<*>
                assertEquals("lam", local[0])
                val formal = (local[1] as List<Map<String, Any?>>).single()
                assertEquals("State# RealWorld", formal["type"])
                assertEquals(false, formal["lifted"])
                assertEquals(mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true), formal["rep"])
                assertEquals("void", ((application[2] as List<*>).single() as List<*>)[0])
            }
            for (backend in listOf("ast", "bytecode")) {
                val output = ByteArrayOutputStream(); val errors = ByteArrayOutputStream()
                Context.newBuilder("thc").allowIO(IOAccess.NONE).out(output).err(errors)
                    .allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
                    .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
                    .build().use { context ->
                        context.initialize("thc"); context.enter()
                        try {
                            val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                            val programs = listOf("originalIsTerminal", "originalIsTerminalErrno").associateWith { name ->
                                val linked = CoreModules.reachable(module, name) + ("instrument" to true)
                                if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                            }
                            val targets = programs.mapValues { (name, program) -> program.entryTarget(name) }
                            var active = emptyMap<String, List<RootCallTarget>>()
                            fun exercise(compiled: Boolean) {
                                for (row in rows) for (name in targets.keys) {
                                    val program = programs.getValue(name); val target = targets.getValue(name)
                                    val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                                    val actual = Calls.target(target, arrayOf(0L, row.getValue("fd").toLong()))
                                    val expected = if (name == "originalIsTerminal") row.getValue("result") else row.getValue("errno")
                                    assertEquals(expected.toLong(), actual, "$stage/$backend/$name/$row")
                                    if (compiled) {
                                        assertEquals(before + 2, (program.diagnostics().getValue("compiledEntries") as Number).toLong(),
                                            "$stage/$backend/$name/$row: entry plus runRW local lambda")
                                        assertEquals(active.getValue(name), targets(target), "First-installed target identities must remain unchanged")
                                        active.getValue(name).forEach(::valid)
                                    }
                                }
                                assertEquals(0, output.size()); assertEquals(0, errors.size())
                                val handoff = language.handoffState.get()
                                assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
                                assertEquals(0, handoff.results.retainedReferences())
                            }
                            exercise(false)
                            active = targets.mapValues { (_, target) -> targets(target).also {
                                assertEquals(2, it.size, "$stage/$backend: exactly entry and runRW local lambda")
                            } }
                            for (target in active.values.flatten()) {
                                target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                                valid(target)
                            }
                            exercise(true)
                        } finally { context.leave() }
                    }
            }
        }
    }
}
