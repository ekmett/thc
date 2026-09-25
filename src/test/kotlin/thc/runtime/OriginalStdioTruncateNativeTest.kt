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

/** Installed GHC c_ftruncate metadata and private-file sizes and bytes. */
class OriginalStdioTruncateNativeTest {
    @TempDir lateinit var directory: Path
    private val root = File(System.getProperty("thc.projectRoot"))
    private val fixture = File(root, "build/original-stdio-truncate")
    private val names = listOf("originalTruncate", "originalTruncateErrno")
    private val scenarios = listOf("shrink", "same", "extend", "negative", "readonly", "invalid", "pipe")
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

    @Test fun originalTruncateMatchesNativeAndCompiledTargets() {
        val manifest = json(File(fixture, "manifest.json"))
        assertEquals(1L, manifest["schema"]); assertEquals("9.14.1", manifest["ghc"])
        assertEquals(names, manifest["entries"]); assertEquals(14L, manifest["nativeRows"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf(
            "compiler/test-fixtures/OriginalStdioTruncateAudit.hs",
            "compiler/test-fixtures/OriginalStdioTruncateAuditNative.hs",
            "test/haskell-fixtures/OriginalStdioTruncateFixtures.hs", "scripts/core_original_foreign.py"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf(
            "build/original-stdio-truncate/oracle.json",
            "build/original-stdio-truncate/pre/core/OriginalStdioTruncateAudit.json",
            "build/original-stdio-truncate/post/core/OriginalStdioTruncateAudit.json"), "build/original-stdio-truncate/")
        val oracle = Json.parse(File(fixture, "oracle.json").readText()) as List<Map<String, Any?>>
        assertEquals(names.flatMap { name -> scenarios.map { name to it } },
            oracle.map { it["entry"] to it["scenario"] })
        val abi = StdioHostAbi.load()
        val expectedSize = mapOf("shrink" to 3L, "same" to 6L, "extend" to 9L,
            "negative" to 6L, "readonly" to 6L, "invalid" to -1L, "pipe" to -1L)
        for (row in oracle) {
            val scenario = row["scenario"] as String
            val expected = if (row["entry"] == "originalTruncate")
                if (scenario in listOf("shrink", "same", "extend")) 0L else -1L
                else when (scenario) {
                    "negative", "readonly", "pipe" -> abi.error(5)
                    "invalid" -> abi.error(4)
                    else -> -2L
                }
            assertEquals(expected, row["result"], "$row")
            assertEquals(expectedSize[scenario], row["size"], "$row")
            assertEquals(if (scenario in listOf("invalid", "pipe")) -1L else 4L, row["position"], "$row")
            assertEquals("", row["stdoutHex"]); assertEquals("", row["stderrHex"])
        }
        for (stage in listOf("pre", "post")) {
            val module = CoreModules.merge(listOf("OriginalStdioTruncateAudit", "THC.InterfaceClosure")
                .map { json(File(fixture, "$stage/core/$it.json")) })
            val bindings = module["bindings"] as List<Map<String, Any?>>
            for (name in names) {
                val owner = "main:OriginalStdioTruncateAudit.$name"
                val binding = bindings.single { it["id"] == owner }
                val calls = OriginalStdioChecks.foreignCalls(binding["expr"])
                assertEquals(if (name.endsWith("Errno")) 2 else 1, calls.size)
                val symbols = calls.map { app ->
                    val head = app[1] as List<Any?>
                    CoreOriginalStdio.validateHead(head, false)
                    val reps = (app[2] as List<*>).map { ((it as List<*>).last() as Map<*, *>)["rep"] }
                    CoreOriginalStdio.validate(app[6], reps, app[3] as List<*>, (app[6] as Map<*, *>)["rep"])!!.symbol
                }
                assertEquals(listOf(OriginalStdioOp.TRUNCATE.symbol) +
                    if (name.endsWith("Errno")) listOf("__hscore_get_errno") else emptyList(), symbols)
                OriginalStdioChecks.audit(json(File(fixture, "$stage/$name.audit.json")), owner, symbols)
            }
            for (backend in listOf("ast", "bytecode")) {
                val output = ByteArrayOutputStream(); val errors = ByteArrayOutputStream()
                Context.newBuilder("thc").allowIO(IOAccess.ALL).`in`(ByteArrayInputStream(byteArrayOf()))
                    .out(output).err(errors).allowExperimentalOptions(true)
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
                                        val fd = when (scenario) {
                                            "invalid" -> -1L
                                            "pipe" -> 0L // The embedding input is nonseekable.
                                            else -> files.open(ManagedAddress.fromByteArray(privateFile.toString().toByteArray() + byteArrayOf(0)),
                                                if (scenario == "readonly") 0L else 3L)
                                                .also { assertTrue(it >= 3L) }
                                        }
                                        if (fd >= 3L) assertEquals(4L, files.seek(fd, 4L, 0L))
                                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                                        assertEquals(row["result"], Calls.target(entry, arrayOf(0L, fd, row["length"])),
                                            "$stage/$backend/$name/$scenario")
                                        if (fd >= 3L) {
                                            assertEquals(4L, files.seek(fd, 0L, 1L), "ftruncate preserves the open-file position")
                                            assertEquals(expectedSize[scenario], files.size(fd))
                                            val expectedBytes = when (scenario) {
                                                "shrink" -> "abc".toByteArray()
                                                "extend" -> "abcdef".toByteArray() + byteArrayOf(0, 0, 0)
                                                else -> "abcdef".toByteArray()
                                            }
                                            assertArrayEquals(expectedBytes, Files.readAllBytes(privateFile))
                                            assertEquals(0L, files.close(fd))
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
