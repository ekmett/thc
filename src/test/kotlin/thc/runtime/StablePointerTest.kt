// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.EntryValue
import thc.Json
import thc.Language
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StablePointerTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/stable-pointers")
    private val entries = listOf("stableComposite", "lazyStable", "sharedEventManagerStore", "sharedSignalHandlerStore")

    private fun json(file: File) = Json.parse(file.readText()) as Map<String, Any?>
    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun context() = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.SingleTierCompilationThreshold", "10000")
        .option("engine.CompilationFailureAction", "Throw")
        .option("compiler.CompilationTimeout", "30").build()

    @Test fun nativeOracleAndInstalledAstBytecodePreserveIdentityAndLazyReferents() {
        val manifest = json(File(directory, "manifest.json"))
        assertEquals(1L, manifest["schema"])
        assertEquals("9.14.1", manifest["ghc"])
        assertEquals(entries, manifest["entries"])
        for (key in listOf("inputHashes", "artifactHashes"))
            for ((path, expected) in manifest[key] as Map<String, String>)
                assertEquals(expected, digest(File(root, path)), "Stale StablePtr fixture: $path")
        val rows = File(directory, "oracle.tsv").readLines().map { it.split('\t') }.groupBy { it[0] }
        assertEquals(entries.toSet(), rows.keys)
        assertEquals((manifest["nativeRows"] as Number).toInt(), rows.values.sumOf { it.size })
        for ((stage, paths) in manifest["stages"] as Map<String, List<String>>) {
            val genuine = CoreModules.merge(paths.map { json(File(root, it)) })
            val sharedPaths = (manifest["sharedStages"] as Map<String, List<String>>).getValue(stage)
            val synthetic = CoreModules.merge(sharedPaths.map { json(File(root, it)) })
            for (name in entries) {
                val shared = name.startsWith("shared")
                val merged = if (shared) synthetic else genuine
                val audit = json(File(directory, "$stage/$name.audit.json"))
                assertEquals(true, audit["accepted"], "$stage/$name")
                assertEquals(emptyList<Any>(), audit["issues"])
                assertEquals(emptyList<Any>(), audit["missingGlobals"])
                val primitives = (audit["primitives"] as List<Map<String, Any?>>).map { it["name"] }.toSet()
                assertTrue(primitives.containsAll(if (shared) setOf("makeStablePtr#") else
                    setOf("makeStablePtr#", "deRefStablePtr#") +
                        if (name == "stableComposite") setOf("eqStablePtr#") else setOf("touch#")))
                assertEquals(setOf("hs_free_stable_ptr") + (if (shared) setOf(if (name == "sharedEventManagerStore")
                    SharedCAFStore.EVENT_MANAGER.symbol else SharedCAFStore.SIGNAL_HANDLER.symbol) else emptySet<String>()),
                    (audit["foreignCalls"] as List<Map<String, Any?>>).map { it["symbol"] }.toSet())
                val cases = rows.getValue(name).map { it[1].toLong() to it[2].toLong() }
                for ((input, native) in cases)
                    assertEquals(input + when (name) {
                        "stableComposite" -> 48L
                        "lazyStable" -> 73L
                        else -> 0L
                    }, native,
                        "Native $name($input)")
                for (backend in listOf("ast", "bytecode")) context().use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        val program = if (backend == "ast") Program(language,
                            CoreModules.reachable(merged, name) + ("instrument" to true))
                        else BytecodeProgram(language, CoreModules.reachable(merged, name) + ("instrument" to true))
                        val host = program.hostEntryTarget(1)
                        val function = context.asValue(EntryValue(program, name, 1))
                        for ((input, native) in cases) assertEquals(native, function.execute(input).asLong(),
                            "$stage/$backend/$name($input)")
                        assertTrue(function.invokeMember("compile").asBoolean(), "$stage/$backend/$name install")
                        for ((input, native) in cases.asReversed()) {
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            assertEquals(native, function.execute(input).asLong(), "$stage/$backend/$name($input) compiled")
                            assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before,
                                "$stage/$backend/$name($input) entered compiled code")
                            assertEquals(true, host.javaClass.getMethod("isValidLastTier").invoke(host))
                        }
                        assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                        assertEquals(0, language.handoffState.get().results.depth)
                    } finally { context.leave() }
                }
            }
        }
    }

    @Test fun opaqueHandlesAreContextOwnedAndNeverBecomeMemoryAddresses() {
        val firstContext = context()
        firstContext.initialize("thc"); firstContext.enter()
        val registry = Language.currentState().stablePointers
        val referent = Any()
        val first = registry.make(referent)
        val second = registry.make(referent)
        try {
            assertSame(referent, registry.dereference(first))
            assertTrue(registry.equal(first, first))
            assertFalse(registry.equal(first, second))
            assertTrue(first.sameLocation(first))
            assertFalse(first.sameLocation(second))
            assertFalse(first.sameLocation(ManagedAddress.nullAddress()))
            assertNotEquals(0L, first.stableHandle()!!.id)
            assertNotEquals(first.stableHandle()!!.id, second.stableHandle()!!.id)
            assertThrows(RuntimeFault::class.java) { first.plus(0) }
            assertThrows(RuntimeFault::class.java) { first.readWord8(0) }
            assertThrows(RuntimeFault::class.java) { first.rawBacking() }
            assertThrows(RuntimeFault::class.java) { first.compareWithinAllocation(first) }
            assertThrows(RuntimeFault::class.java) { registry.make(null) }
        } finally { firstContext.leave() }
        context().use { other ->
            other.initialize("thc"); other.enter()
            try {
                val foreign = Language.currentState().stablePointers
                assertThrows(RuntimeFault::class.java) { foreign.dereference(first) }
                assertThrows(RuntimeFault::class.java) { foreign.equal(first, first) }
                assertThrows(RuntimeFault::class.java) { first.sameLocation(first) }
            } finally { other.leave() }
        }
        firstContext.enter()
        try {
            registry.free(first)
            assertThrows(RuntimeFault::class.java) { registry.dereference(first) }
            assertThrows(RuntimeFault::class.java) { first.sameLocation(first) }
            val third = registry.make(referent)
            assertTrue(third.stableHandle()!!.id > second.stableHandle()!!.id)
            registry.free(second); registry.free(third)
        } finally { firstContext.leave(); firstContext.close() }
        assertThrows(RuntimeFault::class.java) { registry.dereference(first) }
    }

    @Test fun rtsSharedCAFSlotsInstallOneLiveHandleAndReleaseRootsAtContextClose() {
        val registry = StablePointers()
        val foreign = StablePointers()
        val event = SharedCAFStore.EVENT_MANAGER
        val signal = SharedCAFStore.SIGNAL_HANDLER
        val none = ManagedAddress.nullAddress()
        val first = registry.make(Any())
        val second = registry.make(Any())
        assertSame(none, registry.getOrSetSharedCAF(event, none))
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val workers = Executors.newFixedThreadPool(2)
        val outcomes = arrayOfNulls<ManagedAddress>(2)
        try {
            listOf(first, second).forEachIndexed { index, candidate ->
                workers.submit {
                    start.await()
                    try { outcomes[index] = registry.getOrSetSharedCAF(event, candidate) }
                    finally { done.countDown() }
                }
            }
            start.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            val winner = registry.getOrSetSharedCAF(event, none)
            val firstWon = registry.equal(winner, first)
            assertTrue(firstWon || registry.equal(winner, second))
            assertTrue(outcomes.all { registry.equal(it!!, winner) })
            registry.free(if (firstWon) second else first)
            val otherStore = registry.make(Any())
            assertSame(none, registry.getOrSetSharedCAF(signal, none))
            assertTrue(registry.equal(registry.getOrSetSharedCAF(signal, otherStore), otherStore))
            assertFalse(registry.equal(winner, otherStore))
            val stale = registry.make(Any())
            registry.free(stale)
            assertThrows(RuntimeFault::class.java) { registry.getOrSetSharedCAF(event, stale) }
            assertThrows(RuntimeFault::class.java) {
                registry.getOrSetSharedCAF(event, ManagedAddress.fromByteArray(byteArrayOf(1)))
            }
            assertThrows(RuntimeFault::class.java) { registry.getOrSetSharedCAF(event, foreign.make(Any())) }
            assertThrows(RuntimeFault::class.java) { registry.free(winner) }
            assertTrue(registry.equal(registry.getOrSetSharedCAF(event, none), winner))
            registry.close()
            assertThrows(RuntimeFault::class.java) { registry.dereference(winner) }
            assertThrows(RuntimeFault::class.java) { registry.getOrSetSharedCAF(event, none) }
        } finally {
            workers.shutdownNow()
            foreign.close()
            registry.close()
        }
    }

    @Test fun installedStablePointerAbiRejectsRetypedAndRelabeledCalls() {
        fun nodes(value: Any?): List<List<Any?>> = when (value) {
            is Map<*, *> -> value.values.flatMap(::nodes)
            is List<*> -> listOf(value) + value.flatMap(::nodes)
            else -> emptyList()
        }
        for (stage in listOf("pre", "post")) {
            val core = json(File(directory, "$stage/core/StablePointerAudit.json"))
            val applications = nodes(core["bindings"]).filter { it.firstOrNull() == "app" }
            val free = applications.first { app ->
                val call = (app.getOrNull(6) as? Map<*, *>)?.get("foreignCall") as? Map<*, *>
                (call?.get("target") as? Map<*, *>)?.get("symbol") == "hs_free_stable_ptr"
            }
            val metadata = free[6] as Map<String, Any?>
            val arguments = (free[2] as List<List<Any?>>).map { CoreRepresentations.metadata(it)?.get("rep") }
            val flags = free[3] as List<*>
            assertTrue(CoreStablePointers.validate(metadata, arguments, flags, metadata["rep"]))
            val descriptor = metadata.getValue("foreignCall") as Map<String, Any?>
            assertThrows(RuntimeFault::class.java) {
                CoreStablePointers.validate(metadata + ("foreignCall" to (descriptor + ("safety" to "safe"))),
                    arguments, flags, metadata["rep"])
            }
            assertThrows(RuntimeFault::class.java) {
                CoreStablePointers.validate(metadata, arguments.reversed(), flags, metadata["rep"])
            }
            val make = applications.first { (it[1] as? List<*>)?.take(2) == listOf("prim", "makeStablePtr#") }
            val operation = StablePointerOp.MAKE
            val input = (make[2] as List<List<Any?>>).map(CoreRepresentations::expression)
            val output = CoreRepresentations.expression(make)
            operation.validate(input, make[3] as List<*>, output)
            assertThrows(RuntimeFault::class.java) { operation.validate(input, listOf(false, false), output) }
            val synthetic = json(File(directory, "$stage/synthetic/SharedCAFNative.json"))
            val shared = nodes(synthetic["bindings"]).filter { app ->
                val call = (app.getOrNull(6) as? Map<*, *>)?.get("foreignCall") as? Map<*, *>
                SharedCAFStore.named((call?.get("target") as? Map<*, *>)?.get("symbol")) != null
            }
            assertEquals(2, shared.map { ((it[6] as Map<*, *>)["foreignCall"] as Map<*, *>)
                .let { call -> (call["target"] as Map<*, *>)["symbol"] } }.toSet().size)
            for (app in shared) {
                val proof = app[6] as Map<String, Any?>
                val operands = (app[2] as List<List<Any?>>).map { CoreRepresentations.metadata(it)?.get("rep") }
                val call = proof.getValue("foreignCall") as Map<String, Any?>
                assertNotNull(CoreSharedCAFStores.validate(proof, operands, app[3] as List<*>, proof["rep"]))
                assertThrows(RuntimeFault::class.java) {
                    CoreSharedCAFStores.validate(proof + ("foreignCall" to (call + ("safety" to "safe"))),
                        operands, app[3] as List<*>, proof["rep"])
                }
                val target = call.getValue("target") as Map<String, Any?>
                assertThrows(RuntimeFault::class.java) {
                    CoreSharedCAFStores.validate(proof + ("foreignCall" to (call + ("target" to
                        (target + ("unit" to "base"))))), operands, app[3] as List<*>, proof["rep"])
                }
            }
        }
    }
}
