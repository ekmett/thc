// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
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
import org.junit.jupiter.api.io.TempDir
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap

/** The installed GHC c_close FCall retires a context descriptor on both backends. */
class OriginalStdioCloseNativeTest {
    @TempDir lateinit var directory: Path
    private val root = File(System.getProperty("thc.projectRoot"))
    private val fixture = File(root, "build/original-stdio-close")
    private val names = listOf("originalClose", "originalCloseErrno")
    private fun json(path: File) = Json.parse(path.readText()) as Map<String, Any?>
    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun targets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val found = mutableListOf<RootCallTarget>()
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
            found.add(target)
        }
        visit(entry)
        return found
    }

    @Test fun originalCloseAndErrnoMatchNativeBeforeAndAfterCompilation() {
        val manifest = json(File(fixture, "manifest.json"))
        assertEquals(1L, manifest["schema"]); assertEquals("9.14.1", manifest["ghc"])
        assertEquals(names, manifest["entries"]); assertEquals(4L, manifest["nativeRows"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf(
            "compiler/test-fixtures/OriginalStdioCloseAudit.hs",
            "compiler/test-fixtures/OriginalStdioCloseAuditNative.hs",
            "test/haskell-fixtures/OriginalStdioCloseFixtures.hs", "scripts/core_original_foreign.py"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf(
            "build/original-stdio-close/oracle.json",
            "build/original-stdio-close/pre/core/OriginalStdioCloseAudit.json",
            "build/original-stdio-close/post/core/OriginalStdioCloseAudit.json"), "build/original-stdio-close/")
        val oracle = Json.parse(File(fixture, "oracle.json").readText()) as List<Map<String, Any?>>
        assertEquals(names.flatMap { name -> listOf("valid", "invalid").map { name to it } },
            oracle.map { it["entry"] to it["scenario"] })
        val badFd = StdioHostAbi.load().error(4)
        for (row in oracle) {
            val expected = when (row["entry"] to row["scenario"]) {
                "originalClose" to "valid" -> 0L
                "originalClose" to "invalid" -> -1L
                "originalCloseErrno" to "valid" -> -2L
                "originalCloseErrno" to "invalid" -> badFd
                else -> fail("unknown native observation: $row")
            }
            assertEquals(expected, row["result"])
            assertEquals("", row["stdoutHex"]); assertEquals("", row["stderrHex"])
        }
        for (stage in listOf("pre", "post")) {
            val module = CoreModules.merge(listOf("OriginalStdioCloseAudit", "THC.InterfaceClosure")
                .map { json(File(fixture, "$stage/core/$it.json")) })
            val bindings = module["bindings"] as List<Map<String, Any?>>
            for (name in names) {
                val binding = bindings.single { it["id"] == "main:OriginalStdioCloseAudit.$name" }
                val calls = OriginalStdioChecks.foreignCalls(binding["expr"])
                assertEquals(if (name.endsWith("Errno")) 2 else 1, calls.size)
                assertEquals(listOf("close") + if (name.endsWith("Errno")) listOf("__hscore_get_errno") else emptyList(),
                    calls.map { app ->
                        val head = app[1] as List<Any?>
                        CoreOriginalStdio.validateHead(head, false)
                        val reps = (app[2] as List<*>).map { ((it as List<*>).last() as Map<*, *>)["rep"] }
                        CoreOriginalStdio.validate(app[6], reps, app[3] as List<*>, (app[6] as Map<*, *>)["rep"])!!.symbol
                    })
                OriginalStdioChecks.audit(json(File(fixture, "$stage/$name.audit.json")),
                    "main:OriginalStdioCloseAudit.$name", calls.map {
                        (((it[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as Map<*, *>)["symbol"] as String
                    })
            }
            for (backend in listOf("ast", "bytecode")) {
                val output = ByteArrayOutputStream(); val errors = ByteArrayOutputStream()
                Context.newBuilder("thc").allowIO(IOAccess.ALL).out(output).err(errors)
                    .allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
                    .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
                    .build().use { context ->
                        context.initialize("thc"); context.enter()
                        try {
                            val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                            for (name in names) {
                                val linked = CoreModules.reachable(module, name) + ("instrument" to true)
                                val program: ExecutableProgram = if (backend == "ast") Program(language, linked)
                                    else BytecodeProgram(language, linked)
                                val entry = program.entryTarget(name)
                                fun exercise(compiled: Boolean) {
                                    for (scenario in listOf("valid", "invalid")) {
                                        val privateFile = directory.resolve("$stage-$backend-$name-$scenario-${if (compiled) "compiled" else "warm"}")
                                        Files.writeString(privateFile, "keep")
                                        val files = Language.currentState().files
                                        val fd = if (scenario == "valid") {
                                            files.open(ManagedAddress.fromByteArray(privateFile.toString().toByteArray() + byteArrayOf(0)), 0L)
                                                .also { assertTrue(it >= 3L) }
                                        } else -1L
                                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                                        val actual = Calls.target(entry, arrayOf(0L, fd))
                                        val expected = oracle.single { it["entry"] == name && it["scenario"] == scenario }["result"]
                                        assertEquals(expected, actual, "$stage/$backend/$name/$scenario")
                                        if (scenario == "valid") {
                                            assertEquals(-1L, files.size(fd), "the original close retired the managed descriptor")
                                            assertEquals("keep", Files.readString(privateFile))
                                        }
                                        if (compiled) assertEquals(before + 2,
                                            (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                                    }
                                }
                                exercise(false)
                                val active = targets(entry)
                                assertEquals(2, active.size, "entry and runRW State lambda")
                                active.forEach { it.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(it, true); valid(it) }
                                exercise(true)
                                assertEquals(active, targets(entry)); active.forEach(::valid)
                                assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                            }
                            assertEquals(0, output.size()); assertEquals(0, errors.size())
                            val handoff = language.handoffState.get()
                            assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
                            assertEquals(0, handoff.results.retainedReferences())
                        } finally { context.leave() }
                    }
            }
        }
    }
}
