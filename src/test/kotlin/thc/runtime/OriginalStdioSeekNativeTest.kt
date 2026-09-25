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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap

/** Installed GHC c_lseek metadata and private-file positions, including ESPIPE. */
class OriginalStdioSeekNativeTest {
    @TempDir lateinit var directory: Path
    private val root = File(System.getProperty("thc.projectRoot"))
    private val fixture = File(root, "build/original-stdio-seek")
    private val names = listOf("originalSeek", "originalSeekErrno")
    private val scenarios = listOf("set", "cur", "end", "beyond", "wide", "negative", "bad-whence", "invalid", "pipe", "eof", "tell", "closed")
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

    @Test fun originalSeekMatchesNativeAndCompiledTargets() {
        val manifest = json(File(fixture, "manifest.json"))
        assertEquals(1L, manifest["schema"]); assertEquals("9.14.1", manifest["ghc"])
        assertEquals(names, manifest["entries"]); assertEquals(24L, manifest["nativeRows"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf(
            "compiler/test-fixtures/OriginalStdioSeekAudit.hs",
            "compiler/test-fixtures/OriginalStdioSeekAuditNative.hs",
            "test/haskell-fixtures/OriginalStdioSeekFixtures.hs", "scripts/core_original_foreign.py"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf(
            "build/original-stdio-seek/oracle.json",
            "build/original-stdio-seek/pre/core/OriginalStdioSeekAudit.json",
            "build/original-stdio-seek/post/core/OriginalStdioSeekAudit.json"), "build/original-stdio-seek/")
        val oracle = Json.parse(File(fixture, "oracle.json").readText()) as List<Map<String, Any?>>
        assertEquals(names.flatMap { name -> scenarios.map { name to it } },
            oracle.map { it["entry"] to it["scenario"] })
        val abi = StdioHostAbi.load()
        assertEquals(listOf(OriginalStdioOp.SEEK_SET, OriginalStdioOp.SEEK_CUR, OriginalStdioOp.SEEK_END)
            .associate { it.name to abi.seekConstant(it) }, manifest["nativeConstants"])
        val expectedPosition = mapOf("set" to 2L, "cur" to 5L, "end" to 4L, "beyond" to 10L,
            "wide" to 8589934597L, "eof" to 6L, "tell" to 3L)
        for (row in oracle) {
            val scenario = row["scenario"] as String
            val expected = if (row["entry"] == "originalSeek") expectedPosition[scenario] ?: -1L
                else when (scenario) {
                    "negative", "bad-whence" -> abi.error(5)
                    "invalid", "closed" -> abi.error(4)
                    "pipe" -> abi.notSeekable()
                    else -> -2L
                }
            assertEquals(expected, row["result"], "$row")
            assertEquals("", row["stdoutHex"]); assertEquals("", row["stderrHex"])
        }
        for (stage in listOf("pre", "post")) {
            val module = CoreModules.merge(listOf("OriginalStdioSeekAudit", "THC.InterfaceClosure")
                .map { json(File(fixture, "$stage/core/$it.json")) })
            val bindings = module["bindings"] as List<Map<String, Any?>>
            for (name in names) {
                val owner = "main:OriginalStdioSeekAudit.$name"
                val binding = bindings.single { it["id"] == owner }
                val calls = OriginalStdioChecks.foreignCalls(binding["expr"])
                assertEquals(if (name.endsWith("Errno")) 5 else 4, calls.size)
                val symbols = calls.map { app ->
                    val head = app[1] as List<Any?>
                    CoreOriginalStdio.validateHead(head, false)
                    val reps = (app[2] as List<*>).map { ((it as List<*>).last() as Map<*, *>)["rep"] }
                    CoreOriginalStdio.validate(app[6], reps, app[3] as List<*>, (app[6] as Map<*, *>)["rep"])!!.symbol
                }
                assertEquals((listOf(OriginalStdioOp.SEEK.symbol, OriginalStdioOp.SEEK_SET.symbol,
                    OriginalStdioOp.SEEK_CUR.symbol, OriginalStdioOp.SEEK_END.symbol) +
                    if (name.endsWith("Errno")) listOf("__hscore_get_errno") else emptyList()).sorted(), symbols.sorted())
                OriginalStdioChecks.audit(json(File(fixture, "$stage/$name.audit.json")), owner, symbols)
            }
            for (backend in listOf("ast", "bytecode")) for (inlining in listOf(false, true)) {
                val output = ByteArrayOutputStream(); val errors = ByteArrayOutputStream()
                Context.newBuilder("thc").allowIO(IOAccess.ALL).`in`(ByteArrayInputStream(byteArrayOf()))
                    .out(output).err(errors).allowExperimentalOptions(true)
                    .option("compiler.Inlining", inlining.toString())
                    .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                    .option("engine.CompilationFailureAction", "Throw").build().use { context ->
                        context.initialize("thc"); context.enter()
                        try {
                            val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                            for (name in names) {
                                val linked = CoreModules.reachable(module, name) + ("instrument" to true)
                                val program: ExecutableProgram = if (backend == "ast") Program(language, linked)
                                    else BytecodeProgram(language, linked)
                                val entry = program.entryTarget(name)
                                fun exercise(compiled: Boolean) {
                                    for (scenario in scenarios) {
                                        val row = oracle.single { it["entry"] == name && it["scenario"] == scenario }
                                        val privateFile = directory.resolve("$stage-$backend-$name-$scenario-$compiled")
                                        Files.writeString(privateFile, "abcdef")
                                        val files = Language.currentState().files
                                        val stdio = Language.currentState().stdio
                                        val fd = when (scenario) {
                                            "invalid" -> -1L
                                            "pipe" -> 0L // The embedding input is also nonseekable.
                                            else -> files.open(ManagedAddress.fromByteArray(privateFile.toString().toByteArray() + byteArrayOf(0)), 0L)
                                                .also { assertTrue(it >= 3L) }
                                        }
                                        if (scenario == "closed") assertEquals(0L, files.close(fd))
                                        if (scenario in listOf("cur", "tell")) assertEquals(3L, files.seek(fd, 3L, 0L))
                                        // A successful constant lookup/seek must not erase an old C errno.
                                        assertEquals(-1L, stdio.close(-1L))
                                        assertEquals(abi.error(4), stdio.errno())
                                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                                        assertEquals(row["result"], Calls.target(entry, arrayOf(0L, fd, row["displacement"], row["whence"])),
                                            "$stage/$backend/$inlining/$name/$scenario")
                                        if (scenario in expectedPosition.keys) assertEquals(abi.error(4), stdio.errno())
                                        if (fd >= 3L && scenario != "closed") {
                                            assertEquals(6L, files.size(fd))
                                            assertEquals("abcdef", Files.readString(privateFile))
                                            assertEquals(0L, files.close(fd))
                                        }
                                        if (compiled) {
                                            assertEquals(before + 2, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                                            targets(entry).forEach(::valid)
                                        }
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
