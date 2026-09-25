// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.Instruction
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import thc.CoreModules
import thc.EntryValue
import thc.Json
import thc.Language
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

@Timeout(180)
class ThreadLabelNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/thread-label")
    private fun valid(target: RootCallTarget) = target.javaClass.getMethod("isValidLastTier").invoke(target) == true
    private fun targets(entry: RootCallTarget): List<RootCallTarget> {
        val found = mutableListOf<RootCallTarget>()
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val node = target.rootNode
            val nodes = if (node is BytecodeRoot) listOf(node) + node.bytecodeNode.instructions
                .flatMap { it.arguments }.filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }
                .mapNotNull { it.asCachedNode() } else listOf(node)
            nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }
                .mapNotNull { it.currentCallTarget as? RootCallTarget }
                .filter { it.rootNode is GuestRoot }.forEach(::visit)
            found += target
        }
        visit(entry)
        return found
    }

    @Test fun nativeLabelsMatchAndFirstInstalledSetterGetterUseExactTupleInBothBackends() {
        val manifest = Json.parse(File(directory, "manifest.json").readText()) as Map<*, *>
        for (kind in listOf("inputHashes", "artifactHashes")) for ((path, expected) in manifest[kind] as Map<*, *>) {
            val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path as String).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, actual, "Stale thread label fixture: $path")
        }
        fun checksum(seed: Long) = listOf(206L, 187L, 0L, seed and 127L).fold(4L) { acc, byte -> acc * 31 + byte }
        fun expected(entry: String, seed: Long): Long = when (entry) {
            "emptyLabel" -> 0L
            "overwriteLabel" -> checksum(seed + 1L)
            else -> checksum(seed)
        }
        val cases = listOf("selfLabel", "overwriteLabel", "emptyLabel", "deadLabel", "deadOverwrite")
        assertEquals(listOf(0L, 65L, 127L).flatMap { seed -> cases.map { expected(it, seed).toString() } },
            File(directory, "oracle.txt").readLines())
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode"))
            for (entry in cases) {
                if (backend == "ast" && entry.startsWith("dead")) continue // fork# is bytecode-only.
                Context.newBuilder("thc").allowExperimentalOptions(true).allowCreateThread(true)
                    .option("compiler.Inlining", "false")
                    .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                    .option("engine.SingleTierCompilationThreshold", "10000000")
                    .option("engine.CompilationFailureAction", "Throw").build().use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val module = Json.parse(File(directory, "$stage/core/ThreadLabelAudit.json").readText()) as Map<String, Any?>
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        val linked = CoreModules.reachable(module, entry) + ("instrument" to true)
                        val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked, true)
                        val function = context.asValue(EntryValue(program, entry, 1))
                        fun execute(token: Long) = function.execute(token).asLong()
                        repeat(3) { assertEquals(expected(entry, 0L), execute(0L), "$stage/$backend/$entry") }
                        val active = targets(program.entryTarget(entry))
                        val observe = program.entryTarget("${module.getValue("unit")}:ThreadLabelAudit.observe")
                        val identity = (observe.rootNode as GuestRoot).coreIdentity
                        assertNotNull(identity, "The exported observe binding has a Core identity")
                        // AST lambda labels describe arguments, not their global
                        // binding. Splitting also permits distinct targets for the
                        // same binding, so follow the retained Core identity.
                        val observations = active.filter { (it.rootNode as GuestRoot).coreIdentity == identity }
                        assertTrue(observations.isNotEmpty(), "The actual guest graph includes $identity: " +
                            active.map { (it.rootNode as GuestRoot).coreIdentity ?: it.rootNode.name })
                        val setters = if (entry.startsWith("dead")) emptyList() else {
                            val writer = program.entryTarget("${module.getValue("unit")}:ThreadLabelAudit.setLabel")
                            val writerId = (writer.rootNode as GuestRoot).coreIdentity
                            assertNotNull(writerId)
                            active.filter { (it.rootNode as GuestRoot).coreIdentity == writerId }.also {
                                assertTrue(it.isNotEmpty(), "The retained setter is actually called")
                            }
                        }
                        for (target in active) {
                            target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                            assertTrue(valid(target))
                        }
                        assertTrue(function.invokeMember("compile").asBoolean())
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        assertEquals(expected(entry, 65L), execute(65L), "$stage/$backend/$entry first installed observation")
                        assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() - before >= 2,
                            "Entry and retained threadLabel# body enter installed code")
                        for (target in observations + setters)
                            assertTrue(valid(target), "First observation preserves its compiled primitive body")
                        assertEquals(0L, program.diagnostics()["unsupportedTraps"])
                    } finally { context.leave() }
                }
            }
    }
}
