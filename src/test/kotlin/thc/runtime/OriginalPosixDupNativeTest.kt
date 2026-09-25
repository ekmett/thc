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

/** Native fd numbers and context fd numbers are separate namespaces. Compare
 * their allocation roles and observable shared-file behavior, never pass a host fd to THC. */
class OriginalPosixDupNativeTest {
    @TempDir lateinit var directory: Path
    private val root = File(System.getProperty("thc.projectRoot"))
    private val fixture = File(root, "build/original-posix-dup")
    private val requests = linkedMapOf(
        "originalDup" to listOf("shared", "close-source", "append", "lowest0", "lowest1", "lowest2", "invalid", "closed"),
        "originalDupErrno" to listOf("invalid", "closed"),
        "originalDup2" to listOf("replace", "self", "alias", "invalid", "closed", "bad-target"),
        "originalDup2Errno" to listOf("invalid", "closed", "bad-target"))
    private fun json(file: File) = Json.parse(file.readText()) as Map<String, Any?>
    private fun valid(target: RootCallTarget) = assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun targets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val found = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val body = target.rootNode
            val nodes = if (body is BytecodeRoot) listOf(body) + body.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() } else listOf(body)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val callee = call.currentCallTarget as? RootCallTarget ?: continue
                if (callee.rootNode is GuestRoot) visit(callee)
            }
            found.add(target)
        }
        visit(entry); return found
    }
    private fun address(bytes: ByteArray) = ManagedAddress.fromByteArray(bytes)
    private fun path(file: Path) = address(file.toString().toByteArray() + byteArrayOf(0))

    @Test fun originalDuplicationMatchesNativeAndEveryFirstInstalledTarget() {
        val manifest = json(File(fixture, "manifest.json"))
        assertEquals(1L, manifest["schema"]); assertEquals("9.14.1", manifest["ghc"])
        assertEquals(requests.keys.toList(), manifest["entries"]); assertEquals(19L, manifest["nativeRows"])
        assertEquals(true, manifest["strictAccepted"]); assertEquals(false, manifest["runtimeVerified"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf(
            "compiler/test-fixtures/OriginalPosixDupAudit.hs", "compiler/test-fixtures/OriginalPosixDupAuditNative.hs",
            "test/haskell-fixtures/OriginalPosixDupFixtures.hs", "test/haskell-fixtures/Main.hs", "thc.cabal",
            "test/haskell-fixtures/FixtureSupport.hs", "scripts/audit-core.py", "scripts/core_original_foreign.py",
            "scripts/core-capabilities.json"))
        val labels = listOf("ghc-version", "ghc-info", "native-build", "pre-export", "post-export") +
            (0..18).map { "native-$it" } + listOf("pre", "post").flatMap { stage -> requests.keys.map { "$stage-audit-$it" } }
        val artifacts = setOf("oracle.json", "native/oracle") +
            (0..18).flatMap { index -> listOf("txt", "private", "other").map { "results/$index.$it" } } +
            labels.flatMap { label -> listOf("stdout", "stderr", "command.json").map { "logs/$label.$it" } } +
            listOf("pre", "post").flatMap { stage -> listOf("$stage/core/OriginalPosixDupAudit.json", "$stage/core/THC.InterfaceClosure.json") +
                requests.keys.map { "$stage/$it.audit.json" } }
        assertEquals(167, artifacts.size)
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], artifacts.mapTo(mutableSetOf()) { "build/original-posix-dup/$it" }, "build/original-posix-dup/")
        assertEquals(167, (manifest["artifactHashes"] as Map<*, *>).size)
        val oracle = Json.parse(File(fixture, "oracle.json").readText()) as List<Map<String, Any?>>
        assertEquals(requests.flatMap { (name, scenarios) -> scenarios.map { name to it } }, oracle.map { it["entry"] to it["scenario"] })
        val ebadf = StdioHostAbi.load().error(4)
        for ((index, row) in oracle.withIndex()) {
            val name = row["entry"] as String; val scenario = row["scenario"] as String
            val failed = scenario in listOf("invalid", "closed", "bad-target")
            val two = name.startsWith("originalDup2")
            if (failed) assertEquals(if (name.endsWith("Errno")) ebadf else -1L, row["result"])
            else if (two) assertEquals(row["target"], row["result"])
            else {
                assertTrue((row["result"] as Long) >= 0)
                assertNotEquals(row["source"], row["result"])
                if (scenario.startsWith("lowest")) assertEquals(scenario.last().digitToInt().toLong(), row["result"])
            }
            assertEquals(ebadf, row["errno"])
            assertEquals(if (failed) { if (two && scenario != "bad-target") 2L else -1L }
                else if (scenario == "append") 7L else 3L, row["position"])
            assertEquals(if (failed) { if (two && scenario != "bad-target") 97L else -1L }
                else if (scenario == "append") -1L else 99L, row["byte"])
            assertEquals((97L..102L).toList() + if (scenario == "append") listOf(90L) else emptyList(), row["contents"])
            for (stream in listOf("stdout", "stderr")) {
                assertEquals("", row["${stream}Hex"])
                assertArrayEquals(byteArrayOf(), File(fixture, "logs/native-$index.$stream").readBytes())
            }
            assertEquals(0L, json(File(fixture, "logs/native-$index.command.json"))["exit"])
            val scalars = listOf("result", "source", "target", "position", "byte", "errno").joinToString(",") { row[it].toString() }
            val bytes = (row["contents"] as List<*>).joinToString(",", "[", "]")
            assertEquals("($scalars,$bytes)\n", File(fixture, "results/$index.txt").readText())
            assertArrayEquals((row["contents"] as List<Long>).map { it.toByte() }.toByteArray(), File(fixture, "results/$index.private").readBytes())
            assertEquals("target", File(fixture, "results/$index.other").readText())
        }
        for (stage in listOf("pre", "post")) {
            val module = CoreModules.merge(listOf("OriginalPosixDupAudit", "THC.InterfaceClosure").map { json(File(fixture, "$stage/core/$it.json")) })
            val bindings = module["bindings"] as List<Map<String, Any?>>
            for (name in requests.keys) {
                val owner = "main:OriginalPosixDupAudit.$name"
                val calls = OriginalStdioChecks.foreignCalls(bindings.single { it["id"] == owner }["expr"])
                val symbols = calls.map { call ->
                    CoreOriginalStdio.validateHead(call[1] as List<Any?>, false)
                    val reps = (call[2] as List<*>).map { ((it as List<*>).last() as Map<*, *>)["rep"] }
                    CoreOriginalStdio.validate(call[6], reps, call[3] as List<*>, (call[6] as Map<*, *>)["rep"])!!.symbol
                }
                assertEquals((listOf(if (name.startsWith("originalDup2")) "dup2" else "dup") +
                    if (name.endsWith("Errno")) listOf("__hscore_get_errno") else emptyList()).sorted(), symbols.sorted())
                OriginalStdioChecks.audit(json(File(fixture, "$stage/$name.audit.json")), owner, symbols)
            }
            for (backend in listOf("ast", "bytecode")) for (inlining in listOf(false, true)) {
                val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
                Context.newBuilder("thc").allowIO(IOAccess.ALL).`in`(ByteArrayInputStream(byteArrayOf())).out(out).err(err)
                    .allowExperimentalOptions(true).option("compiler.Inlining", inlining.toString())
                    .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                    .option("engine.CompilationFailureAction", "Throw").build().use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        val files = Language.currentState().files; val stdio = Language.currentState().stdio
                        for ((name, scenarios) in requests) {
                            val linked = CoreModules.reachable(module, name) + ("instrument" to true)
                            val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                            val entry = program.entryTarget(name)
                            fun exercise(compiled: Boolean) {
                                for (scenario in scenarios) {
                                    val row = oracle.single { it["entry"] == name && it["scenario"] == scenario }
                                    val file = directory.resolve("$stage-$backend-$inlining-$name-$scenario-$compiled")
                                    val otherFile = directory.resolve(file.fileName.toString() + ".other")
                                    Files.writeString(file, "abcdef"); Files.writeString(otherFile, "target")
                                    val source = files.open(path(file), if (scenario == "append") 2 else 3)
                                    val other = files.open(path(otherFile), 3)
                                    assertTrue(source >= 3); assertTrue(other >= 3)
                                    assertEquals(2L, files.seek(source, 2, 0)); assertEquals(1L, files.seek(other, 1, 0))
                                    val target = when (scenario) { "self" -> source; "alias" -> files.duplicate(source); "bad-target" -> -1L; else -> other }
                                    val backup = if (scenario.startsWith("lowest")) {
                                        val wanted = scenario.last().digitToInt().toLong()
                                        val saved = files.duplicate(wanted); assertEquals(0L, files.close(wanted)); wanted to saved
                                    } else null
                                    if (scenario == "closed") assertEquals(0L, files.close(source))
                                    val fd = if (scenario == "invalid") -1L else source
                                    val failed = scenario in listOf("invalid", "closed", "bad-target")
                                    val two = name.startsWith("originalDup2")
                                    assertEquals(-1L, stdio.close(-1))
                                    val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                                    val result = Calls.target(entry, if (two) arrayOf(0L, fd, target) else arrayOf(0L, fd)) as Long
                                    assertEquals(row["errno"], stdio.errno())
                                    if (failed) assertEquals(row["result"], result)
                                    else if (two) assertEquals(target, result)
                                    else { assertTrue(result >= 0); assertNotEquals(source, result); if (backup != null) assertEquals(backup.first, result) }
                                    if (compiled) {
                                        assertEquals(before + 2, (program.diagnostics().getValue("compiledEntries") as Number).toLong(), "$stage/$backend/$name/$scenario")
                                        targets(entry).forEach(::valid)
                                    }
                                    val alias = if (two) target else result
                                    if (scenario == "close-source") assertEquals(0L, files.close(source))
                                    var position = -1L; var byte = -1L
                                    if (!failed || (two && scenario != "bad-target")) {
                                        if (scenario == "append") {
                                            assertEquals(0L, files.seek(alias, 0, 0)); assertEquals(1L, files.write(alias, address(byteArrayOf(90)), 1))
                                        } else {
                                            val value = ByteArray(1); assertEquals(1L, files.read(alias, address(value), 1)); byte = value[0].toLong()
                                        }
                                        position = files.seek(alias, 0, 1)
                                    }
                                    assertEquals(row["position"], position); assertEquals(row["byte"], byte)
                                    if (backup != null) assertEquals(backup.first, files.duplicateTo(backup.second, backup.first))
                                    (listOf(source, other, target) + (if (failed) emptyList() else listOf(alias)) + listOfNotNull(backup?.second))
                                        .distinct().filter { it >= 3 }.forEach { files.close(it) }
                                    assertEquals(row["contents"], Files.readAllBytes(file).map { it.toLong() })
                                    assertEquals("target", Files.readString(otherFile))
                                }
                            }
                            exercise(false)
                            val active = targets(entry); assertEquals(2, active.size, "Original entry and local runRW State lambda")
                            active.forEach { it.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(it, true); valid(it) }
                            exercise(true); assertEquals(active, targets(entry)); active.forEach(::valid)
                            assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                        }
                        assertEquals(0, out.size()); assertEquals(0, err.size())
                        val handoff = language.handoffState.get()
                        assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
                        assertEquals(0, handoff.arguments.retainedReferences()); assertEquals(0, handoff.results.retainedReferences())
                    } finally { context.leave() }
                }
            }
        }
    }
}
