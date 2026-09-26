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
class ThreadStatusNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/thread-status")
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

    @Test fun nativeStatusesMatchAndFirstInstalledObservationUsesTypedTupleInBothBackends() {
        val manifest = Json.parse(File(directory, "manifest.json").readText()) as Map<*, *>
        for (kind in listOf("inputHashes", "artifactHashes")) for ((path, expected) in manifest[kind] as Map<*, *>) {
            val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path as String).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, actual, "Stale thread status fixture: $path")
        }
        assertEquals(listOf("0", "0", "16", "17", "1", "14"), File(directory, "oracle.txt").readLines())
        val cases = listOf(Triple("selfStatus", null, 0L), Triple("maskedStatus", null, 0L),
            Triple("finishedStatus", null, 16L), Triple("diedStatus", null, 17L),
            Triple("blockedStatus", 0L, 1L), Triple("blockedStatus", 1L, 14L))
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode"))
            for ((entry, reader, expected) in cases) {
                Context.newBuilder("thc").allowExperimentalOptions(true).allowCreateThread(true)
                    .option("compiler.Inlining", "false")
                    .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                    .option("engine.SingleTierCompilationThreshold", "10000000")
                    .option("engine.CompilationFailureAction", "Throw").build().use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val module = Json.parse(File(directory, "$stage/core/ThreadStatusAudit.json").readText()) as Map<String, Any?>
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        val linked = CoreModules.reachable(module, entry) + ("instrument" to true)
                        val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked, true)
                        val function = context.asValue(EntryValue(program, entry, if (reader == null) 1 else 2))
                        fun execute(token: Long) = if (reader == null) function.execute(token).asLong()
                            else function.execute(reader, token).asLong()
                        repeat(3) { assertEquals(expected, execute(0L), "$stage/$backend/$entry") }
                        val active = targets(program.entryTarget(entry))
                        val observe = program.entryTarget("${module.getValue("unit")}:ThreadStatusAudit.observe")
                        val identity = (observe.rootNode as GuestRoot).coreIdentity
                        assertNotNull(identity, "The exported observe binding has a Core identity")
                        // AST lambda labels describe arguments, not their global
                        // binding. Splitting also permits distinct targets for the
                        // same binding, so follow the retained Core identity.
                        val observations = active.filter { (it.rootNode as GuestRoot).coreIdentity == identity }
                        assertTrue(observations.isNotEmpty(), "The actual guest graph includes $identity: " +
                            active.map { (it.rootNode as GuestRoot).coreIdentity ?: it.rootNode.name })
                        for (target in active) {
                            target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                            assertTrue(valid(target))
                        }
                        assertTrue(function.invokeMember("compile").asBoolean())
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        assertEquals(expected + 1L, execute(1L), "$stage/$backend/$entry first installed observation")
                        assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() - before >= 2,
                            "Entry and retained threadStatus# body enter installed code")
                        for (target in observations)
                            assertTrue(valid(target), "First observation preserves its compiled primitive body")
                        assertEquals(0L, program.diagnostics()["unsupportedTraps"])
                    } finally { context.leave() }
                }
            }
    }
}
