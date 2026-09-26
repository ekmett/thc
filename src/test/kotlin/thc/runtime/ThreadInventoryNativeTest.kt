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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Timeout(180)
class ThreadInventoryNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/thread-inventory")
    private val entries = listOf("selfInventory", "boundQuery", "snapshotSize", "forkSnapshot")
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

    private fun provenance() {
        val manifest = Json.parse(File(directory, "manifest.json").readText()) as Map<*, *>
        assertEquals(1L, manifest["schema"])
        assertEquals("9.14.1", manifest["ghc"])
        assertEquals(entries, manifest["entries"])
        assertEquals(listOf("pre", "post"), manifest["stages"])
        assertEquals("unbound forkIO, threaded RTS -N2", manifest["nativeThread"])
        val sourcePaths = setOf("examples/ThreadInventory.hs", "compiler/test-fixtures/ThreadInventoryNative.hs",
            "thc.cabal", "test/haskell-fixtures/Main.hs", "test/haskell-fixtures/FixtureSupport.hs",
            "test/haskell-fixtures/ThreadInventoryFixtures.hs", "scripts/audit-core.py", "scripts/core-capabilities.json",
            "src/main/resources/thc/scalar-primop-signatures.json", "compiler/build.sh", "compiler/export.sh",
            "compiler/toolchain.sh", "compiler/plugin.py") +
            File(root, "compiler/THC").listFiles()!!.filter { it.extension == "hs" }.map { "compiler/THC/${it.name}" } +
            File(root, "scripts").listFiles()!!.filter { it.name.startsWith("core_") && it.extension == "py" }
                .map { "scripts/${it.name}" }
        val artifacts = setOf("build/thread-inventory/oracle.txt") + listOf("pre", "post").flatMap { stage ->
            (listOf("core/ThreadInventory.json") + entries.map { "$it-audit.json" }).map { "build/thread-inventory/$stage/$it" }
        }
        assertEquals(sourcePaths, (manifest["inputHashes"] as Map<*, *>).keys)
        assertEquals(artifacts, (manifest["artifactHashes"] as Map<*, *>).keys)
        for (kind in listOf("inputHashes", "artifactHashes")) for ((path, expected) in manifest[kind] as Map<*, *>) {
            val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path as String).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, actual, "Stale thread inventory fixture: $path")
        }
        assertEquals(listOf("10", "0", "111", "1"), File(directory, "oracle.txt").readLines())
        for (stage in listOf("pre", "post")) for (entry in entries) {
            val audit = Json.parse(File(directory, "$stage/$entry-audit.json").readText()) as Map<*, *>
            assertEquals(true, audit["accepted"])
            assertEquals(emptyList<Any>(), audit["issues"])
            assertEquals(emptyList<Any>(), audit["missingGlobals"])
            val names = (audit["primitives"] as List<*>).map { (it as Map<*, *>)["name"] }.toSet()
            assertTrue(if (entry == "boundQuery") "isCurrentThreadBound#" in names else "listThreads#" in names)
        }
    }

    private fun context() = Context.newBuilder("thc").allowExperimentalOptions(true).allowCreateThread(true)
        .option("compiler.Inlining", "false").option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.SingleTierCompilationThreshold", "10000000")
        .option("engine.CompilationFailureAction", "Throw").build()

    @Test fun nativeSnapshotsAndBoundnessPreserveFirstCompiledEntriesInBothBackends() {
        provenance()
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode"))
            for ((entry, expected) in listOf("selfInventory" to 10L, "boundQuery" to 0L,
                    "snapshotSize" to 1L, "forkSnapshot" to 111L)) {
                if (entry == "forkSnapshot" && backend == "ast") continue // Existing fork# admission is bytecode-only.
                context().use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val module = Json.parse(File(directory, "$stage/core/ThreadInventory.json").readText()) as Map<String, Any?>
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        assertEquals(java.lang.Boolean.getBoolean(HANDOFF_PROPERTY), language.handoffLayouts.enabled)
                        println("THREAD_INVENTORY_HANDOFF=${language.handoffLayouts.enabled}")
                        val linked = CoreModules.reachable(module, entry) + ("instrument" to true)
                        val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked, true)
                        val function = context.asValue(EntryValue(program, entry, 1))
                        repeat(3) { assertEquals(expected, function.execute(0L).asLong(), "$stage/$backend/$entry") }
                        val active = targets(program.entryTarget(entry))
                        assertTrue(active.isNotEmpty())
                        for (target in active) {
                            target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                            assertTrue(valid(target))
                        }
                        assertTrue(function.invokeMember("compile").asBoolean())
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        assertEquals(expected + 1L, function.execute(1L).asLong(), "$stage/$backend/$entry first installed observation")
                        assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() - before >= 2,
                            "Host and retained guest entry must both execute installed code")
                        assertTrue(valid(program.entryTarget(entry)), "First call must preserve the installed entry")
                        for (target in active) assertTrue(valid(target), "First observation retired ${target.rootNode.name}")
                        assertEquals(0L, program.diagnostics()["unsupportedTraps"])
                        val handoff = language.handoffState.get()
                        assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
                        assertEquals(0, handoff.arguments.retainedReferences()); assertEquals(0, handoff.results.retainedReferences())
                        assertNull(handoff.pending)
                    } finally { context.leave() }
                }
            }
    }

    @Test fun bothBackendsObserveRealConcurrentGuestEntriesButNotOtherContexts() {
        provenance()
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                @Suppress("UNCHECKED_CAST")
                val module = Json.parse(File(directory, "pre/core/ThreadInventory.json").readText()) as Map<String, Any?>
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val linked = CoreModules.reachable(module, listOf("snapshotSize", "selfInventory"))
                val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                val size = context.asValue(EntryValue(program, "snapshotSize", 1))
                val self = context.asValue(EntryValue(program, "selfInventory", 1))
                assertEquals(1L, size.execute(0L).asLong())
                val registry = Language.currentState(null).threads
                val ready = CountDownLatch(1)
                val release = CountDownLatch(1)
                val failure = AtomicReference<Throwable>()
                val worker = Thread {
                    context.enter()
                    registry.enterCurrent()
                    try {
                        assertEquals(10L, self.execute(0L).asLong())
                        ready.countDown()
                        assertTrue(release.await(20, TimeUnit.SECONDS))
                    } catch (error: Throwable) { failure.set(error); ready.countDown() }
                    finally { registry.leaveCurrent(); context.leave() }
                }
                worker.start()
                try {
                    assertTrue(ready.await(20, TimeUnit.SECONDS))
                    failure.get()?.let { throw AssertionError("concurrent guest failed", it) }
                    assertEquals(2L, size.execute(0L).asLong())
                    assertEquals(10L, self.execute(0L).asLong())
                    context().use { other ->
                        other.initialize("thc"); other.enter()
                        try {
                            val isolated = Language.currentState(null).threads
                            isolated.enterCurrent()
                            try { assertEquals(1, isolated.snapshot().size) }
                            finally { isolated.leaveCurrent() }
                        } finally { other.leave() }
                    }
                } finally { release.countDown(); worker.join(20000) }
                assertFalse(worker.isAlive)
                failure.get()?.let { throw AssertionError("concurrent guest failed", it) }
            } finally { context.leave() }
        }
    }
}
