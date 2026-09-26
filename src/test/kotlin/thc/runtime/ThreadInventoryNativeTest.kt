// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import thc.CoreModules
import thc.EntryValue
import thc.Json
import thc.Language
import java.io.File
import java.lang.ref.Reference
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import thc.runtime.ThreadInventoryCoreEvidence.Companion.install
import thc.runtime.ThreadInventoryCoreEvidence.Companion.interpretedCalls
import thc.runtime.ThreadInventoryCoreEvidence.Companion.rawCompile
import thc.runtime.ThreadInventoryCoreEvidence.Companion.released
import thc.runtime.ThreadInventoryCoreEvidence.Companion.restoreBoundary
import thc.runtime.ThreadInventoryCoreEvidence.Companion.targets
import thc.runtime.ThreadInventoryCoreEvidence.Companion.valid

@Timeout(180)
class ThreadInventoryNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/thread-inventory")
    private val entries = listOf("selfInventory", "boundQuery", "snapshotSize", "forkSnapshot",
        "lazyFork", "forkMasks", "selfKilledStatus", "parkedFork")

    /** Fixed source calls, independent of scheduling and of measured counters.
     * awaitStatus retries through one local join, not additional guest entries. */
    private fun forkCalls(module: Map<String, Any?>, entry: String): Pair<Long, Int> {
        val proof = ArrayCoreEvidence(module, entry)
        fun binding(name: String) = proof.bindings.single { it["name"] == name }
        fun lambdas(name: String) = proof.guestLambdas(binding(name)["expr"]).size
        fun references(name: String, target: String) = proof.globalReferences(binding(name)["expr"])
            .count { it == binding(target)["id"] }
        val polling = binding("awaitStatus")["expr"] as List<*>
        assertEquals("lam", polling[0])
        assertEquals(listOf("tid", "wanted", "fuel", "s"),
            (polling[1] as List<*>).map { (it as Map<*, *>)["name"] })
        assertEquals(emptyList<String>(), proof.globalReferences(polling), "Polling cannot add global calls")
        val joins = proof.nodes(polling).filter { it.firstOrNull() == "let" }.flatMap { it[2] as List<*> }
        assertEquals(1, joins.size)
        val join = joins.single() as Map<*, *>
        assertEquals(3L, join["joinValueArity"])
        assertEquals("wait", join["name"])
        assertEquals(2, proof.nodes(polling).count { it.firstOrNull() == "lam" },
            "Only the global entry and non-root join prefix occur in awaitStatus")
        assertEquals(1, references(if (entry == "forkMasks") "forkMask" else entry, "awaitStatus"))
        return when (entry) {
            "forkMasks" -> {
                assertEquals(1, lambdas(entry)); assertEquals(2, lambdas("forkMask"))
                assertEquals(3, references(entry, "forkMask"))
                (1L + 3L * (2L + 1L)) to 4 // public + three (forkMask, child, awaitStatus)
            }
            "parkedFork" -> {
                assertEquals(2, lambdas(entry))
                (2L + 1L) to 3 // public, blocked child, awaitStatus
            }
            "selfKilledStatus" -> {
                assertEquals(1, lambdas(entry))
                val fork = proof.nodes(binding(entry)["expr"]).single {
                    it.firstOrNull() == "app" && (it.getOrNull(1) as? List<*>)?.take(2) == listOf("prim", "fork#")
                }
                val action = (fork[2] as List<*>)[0] as List<*>
                assertEquals("var", action[0])
                val child = proof.allBindings.getValue(action[1] as String)
                assertEquals(1, proof.guestLambdas(child["expr"]).size)
                (1L + 1L + 1L) to 3 // public, floated child, awaitStatus
            }
            "lazyFork" -> {
                assertEquals(2, lambdas(entry)); assertEquals(1, lambdas("makeAction"))
                assertEquals(2, lambdas("actionHead"))
                assertEquals(1, references(entry, "makeAction"))
                assertEquals(1, references("makeAction", "actionHead"))
                val constructor = (binding("makeAction")["expr"] as List<*>)[2] as List<*>
                assertEquals("app", constructor[0])
                assertEquals("con", (constructor[1] as List<*>)[0])
                assertEquals(listOf(true), constructor[3], "Action stores exactly one lazy action head")
                val delayed = (constructor[2] as List<*>).single() as List<*>
                assertEquals("app", delayed[0])
                assertEquals(listOf("var", binding("actionHead")["id"]), (delayed[1] as List<*>).take(2))
                // Pre-Tidy's GHC arity metadata calls this a PAP; the actual
                // exported head has four arguments and returns its State# lambda
                // only after the blocking prefix. Check that body, not the flag.
                assertEquals(4, (delayed[2] as List<*>).size)
                assertEquals(listOf(false, false, false, false), delayed[3])
                assertEquals(listOf(4, 1), proof.guestLambdas(binding("actionHead")["expr"])
                    .map { (it[1] as List<*>).size })
                // The published Box 42 is a shared CAF, forced during setup.
                // Its cached target stays in the graph but is not a per-call entry.
                assertEquals(1, proof.bindings.count { binding ->
                    val expression = binding["expr"] as List<*>
                    expression[0] == "app" && (expression[1] as List<*>)[0] == "con" &&
                        (expression[2] as List<*>).singleOrNull().let {
                            (it as? List<*>)?.take(3) == listOf("lit", "int", "42")
                        }
                })
                val headNodes = proof.nodes(binding("actionHead")["expr"])
                val publications = headNodes.filter {
                    it.firstOrNull() == "app" && (it.getOrNull(1) as? List<*>)?.take(2) == listOf("prim", "putMVar#")
                }
                assertEquals(2, publications.size)
                val readiness = (publications[0][2] as List<*>)[1] as List<*>
                assertEquals(listOf(false, true, false), publications[0][3])
                assertEquals("case", readiness[0], "Identity comparison is a second lazy message thunk")
                assertEquals(emptyList<String>(), proof.globalReferences(readiness))
                assertEquals(1, proof.nodes(readiness).count { it.take(2) == listOf("prim", "reallyUnsafePtrEquality#") })
                val result = headNodes.single {
                    it.firstOrNull() == "app" && (it.getOrNull(1) as? List<*>)?.take(2) ==
                        listOf("con", "ghc-internal:GHC.Internal.Types.(#,#)")
                }
                val discarded = (result[2] as List<*>)[1] as List<*>
                assertEquals("var", discarded[0])
                val bottom = proof.allBindings.getValue(discarded[1] as String)["expr"] as List<*>
                assertEquals(listOf("prim", "raise#"), (bottom[1] as List<*>).take(2))
                // Two public/state roots, makeAction, lazy head thunk,
                // head/child roots, readiness message thunk, awaitStatus.
                // The ninth cached root is the already-forced published CAF.
                (2L + 1L + 1L + 2L + 1L + 1L) to 9
            }
            else -> error("No fork call-count proof for $entry")
        }
    }
    private fun forkTargets(program: ExecutableProgram, module: Map<String, Any?>): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        // A floated child is a global value passed to fork#, not a direct call
        // retained by its parent. Seed genuine function bindings without forcing CAFs.
        return (module["bindings"] as List<*>).map { it as Map<*, *> }.filter {
            (it["expr"] as List<*>)[0] == "lam"
        }.flatMap { targets(program.entryTarget(it["id"] as String)) }.filter { seen.add(it) }
    }

    private fun joinCompletedChildren(registry: GuestThreads) {
        for (id in registry.snapshot().filterIsInstance<GuestThreadId>().filter { it.forked }) {
            id.carrier.get()?.let { carrier ->
                carrier.join(5000)
                assertFalse(carrier.isAlive, "Completion signal must be followed by actual carrier termination")
            }
        }
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
        assertEquals(listOf(10L, 0L, 111L, 1L, 42L, 210L, 17L, 1L), manifest["native"])
        assertEquals(listOf("10", "0", "111", "1", "42", "210", "17", "1"), File(directory, "oracle.txt").readLines())
        for (stage in listOf("pre", "post")) for (entry in entries) {
            val audit = Json.parse(File(directory, "$stage/$entry-audit.json").readText()) as Map<*, *>
            assertEquals(true, audit["accepted"])
            assertEquals(emptyList<Any>(), audit["issues"])
            assertEquals(emptyList<Any>(), audit["missingGlobals"])
            val names = (audit["primitives"] as List<*>).map { (it as Map<*, *>)["name"] }.toSet()
            val required = when (entry) {
                "boundQuery" -> "isCurrentThreadBound#"
                "selfInventory", "snapshotSize", "forkSnapshot" -> "listThreads#"
                else -> "fork#"
            }
            assertTrue(required in names)
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
                    "snapshotSize" to 1L, "forkSnapshot" to 111L, "lazyFork" to 42L,
                    "forkMasks" to 210L, "selfKilledStatus" to 17L)) {
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
                        val proof = if (entry in entries.take(4)) ThreadInventoryCoreEvidence(module, entry) else null
                        val fixedProof = if (proof == null) forkCalls(module, entry) else null
                        val registry = Language.currentState(null).threads
                        registry.enterCurrent()
                        try {
                            repeat(3) {
                                assertEquals(expected, function.execute(0L).asLong(), "$stage/$backend/$entry")
                                joinCompletedChildren(registry)
                                released(language)
                            }
                            val retained = registry.snapshot()
                            val population = retained.size + if (entry == "forkSnapshot") 1 else 0
                            val active = if (proof != null) targets(program.entryTarget(entry)) else forkTargets(program, linked)
                            if (proof != null) proof.assertRoots(active)
                            else assertEquals(fixedProof!!.second, active.size,
                                "$entry source-derived roots, including child, head thunk and already-forced CAF: ${active.map { it.rootNode.name }}")
                            fun count() = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            val before = count()
                            val calls = interpretedCalls(active)
                            install(active)
                            assertTrue(function.invokeMember("compile").asBoolean())
                            assertEquals(before, count(), "Installation executes no guest roots")
                            assertEquals(calls, interpretedCalls(active), "No interpreted settling call")
                            assertEquals(expected + 1L, function.execute(1L).asLong(), "$stage/$backend/$entry first installed observation")
                            joinCompletedChildren(registry)
                            val delta = count() - before
                            println("THREAD_INVENTORY_ENTRIES $stage/$backend/$entry population=$population delta=$delta")
                            assertEquals(fixedProof?.first ?: proof!!.compiledCalls(population), delta,
                                "$stage/$backend/$entry every original guest invocation enters installed code")
                            assertEquals(calls, interpretedCalls(active), "No guest root silently interpreted")
                            assertEquals(active, if (proof != null) targets(program.entryTarget(entry)) else forkTargets(program, linked))
                            for (target in active) assertTrue(valid(target), "$stage/$backend/$entry first observation retired ${target.rootNode.name}")
                            assertEquals(0L, program.diagnostics()["unsupportedTraps"])
                            Reference.reachabilityFence(retained)
                        } finally {
                            released(language)
                            registry.leaveCurrent()
                        }
                    } finally { context.leave() }
                }
            }
    }

    @Test fun managedBlockedChildrenAreCancelledByContextCloseAndAstRejectsExternalDelivery() {
        provenance()
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) {
            val context = context()
            val carriers = mutableListOf<Thread>()
            lateinit var registry: GuestThreads
            try {
                context.initialize("thc"); context.enter()
                try {
                    @Suppress("UNCHECKED_CAST")
                    val module = Json.parse(File(directory, "$stage/core/ThreadInventory.json").readText()) as Map<String, Any?>
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val linked = CoreModules.reachable(module, "parkedFork") + ("instrument" to true)
                    val program: ExecutableProgram = if (backend == "ast") Program(language, linked)
                        else BytecodeProgram(language, linked, true)
                    val function = context.asValue(EntryValue(program, "parkedFork", 1))
                    assertEquals(1L, function.execute(0L).asLong())
                    val expectedCalls = forkCalls(module, "parkedFork").first
                    val active = forkTargets(program, linked)
                    assertEquals(3, active.size)
                    val calls = interpretedCalls(active)
                    install(active)
                    assertTrue(function.invokeMember("compile").asBoolean())
                    val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    assertEquals(2L, function.execute(1L).asLong())
                    assertEquals(expectedCalls, (program.diagnostics().getValue("compiledEntries") as Number).toLong() - before)
                    assertEquals(calls, interpretedCalls(active))
                    active.forEach { assertTrue(valid(it)) }
                    assertTrue(valid(program.entryTarget("parkedFork")))
                    registry = Language.currentState(null).threads
                    registry.enterCurrent()
                    try {
                        val self = registry.currentIdentity()
                        val children = registry.snapshot().filterIsInstance<GuestThreadId>().filter { it !== self }
                        assertEquals(2, children.size)
                        for (child in children) {
                            assertEquals(GuestThreadStatus.MVAR, registry.status(child))
                            val carrier = child.carrier.get()!!
                            carriers += carrier
                            assertTrue(carrier.isAlive)
                            assertNotSame(Thread.currentThread(), carrier)
                            if (backend == "ast") {
                                assertThrows(UnsupportedCore::class.java) { registry.send(child, "unsupported cancellation") }
                                assertEquals(GuestThreadStatus.MVAR, registry.status(child))
                            }
                        }
                    } finally { registry.leaveCurrent() }
                    assertEquals(0L, program.diagnostics()["unsupportedTraps"])
                } finally { context.leave() }
            } finally { context.close(true) }
            for (carrier in carriers) {
                carrier.join(5000)
                assertFalse(carrier.isAlive, "$stage/$backend context close must stop its actual managed children")
            }
            assertThrows(RuntimeFault::class.java) { registry.snapshot() }
        }
    }

    @Test fun retiredBoundaryNegativeControlRejectsMissingCompiledEntries() {
        provenance()
        for (stage in listOf("pre", "post")) for (backendName in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                @Suppress("UNCHECKED_CAST")
                val module = Json.parse(File(directory, "$stage/core/ThreadInventory.json").readText()) as Map<String, Any?>
                val proof = ThreadInventoryCoreEvidence(module, "selfInventory")
                assertEquals(4L, proof.compiledCalls(1), "Public, state, and occurrences at indices zero and one")
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                assertEquals(java.lang.Boolean.getBoolean(HANDOFF_PROPERTY), language.handoffLayouts.enabled)
                println("THREAD_INVENTORY_BOUNDARY_HANDOFF=${language.handoffLayouts.enabled}")
                val linked = CoreModules.reachable(module, "selfInventory") + ("instrument" to true)
                val program: ExecutableProgram = if (backendName == "ast") Program(language, linked) else BytecodeProgram(language, linked, true)
                val entry = program.entryTarget("selfInventory")
                val registry = Language.currentState(null).threads
                registry.enterCurrent()
                try {
                    repeat(3) { assertEquals(10L, Calls.target(entry, arrayOf(0L, 0L))); released(language) }
                    val active = targets(entry)
                    proof.assertRoots(active)
                    assertEquals(1, registry.snapshot().size)
                    val type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
                    val jvmci = Class.forName("jdk.vm.ci.runtime.JVMCI").getMethod("getRuntime").invoke(null)
                    val backend = Class.forName("jdk.vm.ci.runtime.JVMCIRuntime").getMethod("getHostJVMCIBackend").invoke(jvmci)
                    val meta = Class.forName("jdk.vm.ci.runtime.JVMCIBackend").getMethod("getMetaAccess").invoke(backend)
                    val boundary = Class.forName("jdk.vm.ci.meta.MetaAccessProvider")
                        .getMethod("lookupJavaMethod", java.lang.reflect.Executable::class.java)
                        .invoke(meta, type.getDeclaredMethod("callBoundary", Array<Any?>::class.java))
                    val reprofile = Class.forName("jdk.vm.ci.meta.ResolvedJavaMethod").getMethod("reprofile")
                    val hasCode = Class.forName("jdk.vm.ci.hotspot.HotSpotResolvedJavaMethod").getMethod("hasCompiledCode")
                    fun count() = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    fun retained() {
                        assertEquals(active, targets(entry))
                        active.forEach { assertTrue(valid(it), "Retained ${it.rootNode.name}") }
                        released(language)
                    }
                    install(active)
                    try {
                        reprofile.invoke(boundary)
                        assertEquals(false, hasCode.invoke(boundary))
                        active.forEach(::rawCompile)
                        assertEquals(false, hasCode.invoke(boundary), "Raw installation leaves the boundary retired")
                        val before = count()
                        val calls = interpretedCalls(active)
                        assertEquals(11L, Calls.target(entry, arrayOf(0L, 1L)))
                        val negative = count() - before
                        val interpreted = interpretedCalls(active).mapIndexed { i, value -> value - calls[i] }
                        val sourceCalls = mapOf("lambda token" to 1, "lambda s" to 1,
                            "lambda wanted, threads, i" to 2)
                        assertEquals(proof.labels, sourceCalls.keys)
                        println("THREAD_INVENTORY_BOUNDARY $stage/$backendName negative=$negative interpreted=" +
                            active.mapIndexed { i, target -> target.rootNode.name to interpreted[i] }.toMap())
                        // Reprofiling the shared JVM boundary always bypasses the
                        // outer entry, but previously compiled Java call sites can
                        // bypass inner entries too. Account for the actual paths;
                        // do not assume that only one of the four calls interpreted.
                        assertEquals(1, interpreted[active.indexOf(entry)], "The outer entry deliberately bypasses code")
                        active.forEachIndexed { i, target ->
                            assertTrue(interpreted[i] in 0..sourceCalls.getValue(target.rootNode.name),
                                "Interpreted calls fit the original root: ${target.rootNode.name}")
                        }
                        assertEquals(proof.compiledCalls(1), negative + interpreted.sum(),
                            "Every source call is accounted for as compiled or interpreted")
                        assertTrue(negative < proof.compiledCalls(1), "The deliberate bypass misses required compiled entries")
                        assertThrows(AssertionError::class.java) { assertEquals(proof.compiledCalls(1), negative) }
                        retained()

                        reprofile.invoke(boundary)
                        assertEquals(false, hasCode.invoke(boundary))
                        val beforeInstall = count()
                        val callsBeforeInstall = interpretedCalls(active)
                        install(active)
                        assertEquals(true, hasCode.invoke(boundary))
                        assertEquals(beforeInstall, count(), "Restoration executes no guest code")
                        assertEquals(callsBeforeInstall, interpretedCalls(active), "No settling call")
                        assertEquals(12L, Calls.target(entry, arrayOf(0L, 2L)))
                        println("THREAD_INVENTORY_BOUNDARY $stage/$backendName positive=${count() - beforeInstall}")
                        assertEquals(proof.compiledCalls(1), count() - beforeInstall)
                        assertEquals(callsBeforeInstall, interpretedCalls(active))
                        retained()
                    } finally { restoreBoundary(entry) }
                } finally { released(language); registry.leaveCurrent() }
            } finally { context.leave() }
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
