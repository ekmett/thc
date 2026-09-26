// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.ContinuationResult
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Timeout(45)
class ThreadSchedulingTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/thread-scheduling")
    private val entries = listOf("emptySpark", "lazyPar", "lazySpark", "sparkValue", "currentCounter", "negativeCounter",
        "pinnedFork", "otherCounter", "timedDelay")
    private fun context() = Context.newBuilder("thc").allowExperimentalOptions(true).allowCreateThread(true)
        .option("compiler.Inlining", "false").option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.SingleTierCompilationThreshold", "10000000")
        .option("engine.CompilationFailureAction", "Throw").build()
    private fun valid(target: RootCallTarget) = target.javaClass.getMethod("isValidLastTier").invoke(target) == true
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertTrue(valid(target))
    }

    private fun delayModule(): Map<String, Any?> {
        val long = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
        val state = mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true)
        val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
        val parameters = listOf(mapOf("id" to "duration", "lifted" to false, "rep" to long),
            mapOf("id" to "s", "lifted" to false, "rep" to state))
        val call = listOf("app", listOf("prim", "delay#"),
            listOf(listOf("var", "duration", mapOf("rep" to long)), listOf("var", "s", mapOf("rep" to state))),
            listOf(false, false), false, false, mapOf("rep" to state))
        return mapOf("schema" to 1, "ghc" to "9.14.1", "module" to "Test.Delay",
            "constructors" to emptyList<Any>(), "instrument" to true,
            "bindings" to listOf(mapOf("id" to "wait", "name" to "wait", "arity" to 2, "lifted" to true,
                "rep" to closure, "expr" to listOf("lam", parameters, call, mapOf("rep" to closure, "resultRep" to state)))))
    }

    private fun provenance() {
        val manifest = Json.parse(File(directory, "manifest.json").readText()) as Map<String, Any?>
        assertEquals(entries, manifest["entries"])
        assertEquals(listOf("pre", "post"), manifest["stages"])
        assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 1L, 11L, 11L, 2000L), manifest["native"])
        assertEquals(listOf("1", "1", "1", "1", "1", "1", "11", "11", "2000"), File(directory, "oracle.txt").readLines())
        for ((path, expected) in (manifest["inputHashes"] as Map<String, String>) +
            (manifest["artifactHashes"] as Map<String, String>)) {
            val hash = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, hash, "Stale scheduling fixture: $path")
        }
        val operations = mutableSetOf<String>()
        for (stage in listOf("pre", "post")) for (entry in entries) {
            val audit = Json.parse(File(directory, "$stage/$entry-audit.json").readText()) as Map<String, Any?>
            assertEquals(true, audit["accepted"])
            assertEquals(emptyList<Any>(), audit["issues"])
            assertEquals(emptyList<Any>(), audit["missingGlobals"])
            operations += (audit["primitives"] as List<Map<String, Any?>>).map { it["name"] as String }
        }
        assertTrue(operations.containsAll(listOf("par#", "spark#", "getSpark#", "numSparks#", "forkOn#", "delay#",
            "setThreadAllocationCounter#", "setOtherThreadAllocationCounter#")))
    }

    @Test fun nativeExamplesAndFirstCompiledStraightLineEntriesAgreeOnBothBackends() {
        provenance()
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode"))
            for (entry in entries) context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val module = Json.parse(File(directory, "$stage/core/ThreadScheduling.json").readText()) as Map<String, Any?>
                    val linked = CoreModules.reachable(module, entry) + ("instrument" to true)
                    val program: ExecutableProgram = if (backend == "ast") Program(language, linked)
                        else BytecodeProgram(language, linked, entry in setOf("pinnedFork", "otherCounter"))
                    val function = context.asValue(EntryValue(program, entry, 1))
                    val argument = when (entry) { "currentCounter" -> 4096L; "timedDelay" -> 2000L; else -> 0L }
                    val expected = when (entry) { "pinnedFork", "otherCounter" -> 11L; "timedDelay" -> 2000L; else -> 1L }
                    val threads = Language.currentState().threads
                    threads.enterCurrent()
                    try {
                        assertEquals(expected, function.execute(argument).asLong(), "$stage/$backend/$entry")
                        if (entry !in setOf("pinnedFork", "otherCounter") && stage == "pre") {
                            val target = program.entryTarget(entry)
                            compile(target)
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            assertEquals(expected, function.execute(argument).asLong(), "$backend/$entry first installed call")
                            assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                            assertTrue(valid(target), "$backend/$entry retained its first installation")
                        }
                        for (identity in threads.snapshot().filterIsInstance<GuestThreadId>().filter { it.forked }) {
                            identity.carrier.get()?.let { carrier -> carrier.join(5000); assertFalse(carrier.isAlive) }
                            assertEquals(GuestThreadStatus.FINISHED, threads.status(identity))
                        }
                        assertEquals(0, language.handoffState.get().arguments.depth)
                        assertEquals(0, language.handoffState.get().results.depth)
                    } finally { threads.leaveCurrent() }
                } finally { context.leave() }
            }
    }

    @Test fun realAllocationCountersResetAndExcludeOutsideEntryWork() {
        val threads = GuestThreads(ThreadLocal.withInitial { MaskingState.UNMASKED }) { }
        val foreign = GuestThreads(ThreadLocal.withInitial { MaskingState.UNMASKED }) { }
        threads.enterCurrent()
        val identity = threads.currentIdentity()
        try {
            assertTrue(threads.allocationCounter() <= 0L)
            threads.setAllocationCounter(10_000_000L)
            val before = threads.allocationCounter()
            val bytes = ByteArray(128 * 1024)
            val after = threads.allocationCounter()
            assertTrue(before - after >= bytes.size)
            Reference.reachabilityFence(bytes)
            threads.setAllocationCounter(-17L)
            assertTrue(threads.allocationCounter() <= -17L)
            foreign.enterCurrent()
            try {
                assertThrows(RuntimeFault::class.java) { threads.setAllocationCounter(123L, foreign.currentIdentity()) }
            } finally { foreign.leaveCurrent(); foreign.close() }
        } finally { threads.leaveCurrent() }
        val saved = identity.allocationRemaining
        val outside = ByteArray(4 * 1024 * 1024)
        threads.enterCurrent()
        try {
            assertTrue(threads.allocationCounter() > saved - 1024 * 1024, "Host allocation outside an entry is not charged")
            Reference.reachabilityFence(outside)
        } finally { threads.leaveCurrent(); threads.close() }
    }

    @Test fun anotherLiveThreadReceivesItsOwnCounterAndLogicalCapability() {
        val threads = GuestThreads(ThreadLocal.withInitial { MaskingState.UNMASKED }) { }
        val ready = CountDownLatch(1)
        val release = CountDownLatch(1)
        val target = AtomicReference<GuestThreadId>()
        val remaining = AtomicReference<Long>()
        val failure = AtomicReference<Throwable>()
        threads.enterCurrent()
        val child = Thread {
            threads.enterCurrent(forked = true, capability = 7L)
            try {
                target.set(threads.currentIdentity()); ready.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                remaining.set(threads.allocationCounter())
            } catch (error: Throwable) { failure.set(error) }
            finally { threads.leaveCurrent() }
        }
        try {
            child.start(); assertTrue(ready.await(5, TimeUnit.SECONDS))
            assertTrue(target.get().capabilityLocked)
            assertEquals(0L, target.get().capability)
            threads.setAllocationCounter(1_000_000L, target.get())
            release.countDown(); child.join(5000); assertFalse(child.isAlive)
            failure.get()?.let { throw AssertionError("counter child failed", it) }
            assertTrue(remaining.get() in 900_000L..1_000_000L)
            assertTrue(threads.allocationCounter() <= 0L, "The parent's counter was not reset")
        } finally { release.countDown(); child.join(5000); threads.leaveCurrent(); threads.close() }
    }

    @Test fun unavailableAccountingCannotBreakGuestThreadCleanup() {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val threads = GuestThreads(ThreadLocal.withInitial { MaskingState.UNMASKED }) { }
        threads.enterCurrent()
        val identity = threads.currentIdentity()
        var entered = true
        try {
            bean.isThreadAllocatedMemoryEnabled = false
            assertThrows(RuntimeFault::class.java) { threads.allocationCounter() }
            assertDoesNotThrow { threads.leaveCurrent() }
            entered = false
            assertThrows(RuntimeFault::class.java) { threads.currentIdentity() }
            assertTrue(identity.allocationUnavailable)
        } finally {
            bean.isThreadAllocatedMemoryEnabled = true
            if (entered) { threads.leaveCurrent(); threads.close() }
        }
        threads.enterCurrent()
        try {
            assertThrows(RuntimeFault::class.java) { threads.allocationCounter() }
            threads.setAllocationCounter(1_000_000L)
            assertTrue(threads.allocationCounter() in 900_000L..1_000_000L)
        } finally { threads.leaveCurrent(); threads.close() }
    }

    @Test fun nonpositiveAndTimedDelaysKeepFirstInstalledEntries() {
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val program: ExecutableProgram = if (backend == "ast") Program(language, delayModule())
                    else BytecodeProgram(language, delayModule())
                val target = program.entryTarget("wait")
                for (duration in listOf(Long.MIN_VALUE, -1L, 0L, 2000L))
                    assertSame(Unit, Calls.target(target, arrayOf(0L, duration, Unit)))
                compile(target)
                val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                val start = System.nanoTime()
                assertSame(Unit, Calls.target(target, arrayOf(0L, 10_000L, Unit)))
                assertTrue(System.nanoTime() - start >= 10_000_000L)
                assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                assertTrue(valid(target))
            } finally { context.leave() }
        }
    }

    @Test fun compiledDelayCapturesOnceAndResumesItsOriginalDeadline() {
        val context = context()
        val worker = Executors.newSingleThreadExecutor()
        try {
            context.initialize("thc"); context.enter()
            val state: Language.State
            val program: BytecodeProgram
            val target: RootCallTarget
            try {
                state = Language.currentState()
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                program = BytecodeProgram(language, delayModule(), true)
                target = program.entryTarget("wait")
                assertSame(Unit, Calls.target(target, arrayOf(0L, 0L, Unit)))
                compile(target)
            } finally { context.leave() }
            val identity = AtomicReference<GuestThreadId>()
            fun start(duration: Long, mask: MaskingState) = worker.submit<Any> {
                context.enter(); state.threads.enterCurrent(mask)
                try {
                    identity.set(state.threads.currentIdentity())
                    val answer = Calls.target(target, arrayOf(0L, duration, Unit))
                    if (answer is ContinuationResult) AsyncContinuations.request(answer)?.acknowledge()
                    else {
                        state.maskingState.set(MaskingState.UNMASKED)
                        state.threads.poll(target.rootNode, true)?.acknowledge()
                    }
                    answer
                } finally { state.threads.leaveCurrent(); context.leave() }
            }
            fun awaitBlocked(future: java.util.concurrent.Future<*>) {
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (identity.get()?.status != GuestThreadStatus.DELAY && !future.isDone && System.nanoTime() < until)
                    Thread.sleep(1)
                if (future.isDone) future.get()
                assertEquals(GuestThreadStatus.DELAY, identity.get()?.status)
            }
            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
            val first = start(300_000L, MaskingState.MASKED_INTERRUPTIBLE)
            awaitBlocked(first)
            val request = state.threads.send(identity.get().javaId, "wake delay")
            val saved = first.get(5, TimeUnit.SECONDS) as ContinuationResult
            assertSame(request, AsyncContinuations.request(saved))
            assertTrue(request.compiledCapture)
            assertEquals(AsyncRequestState.ACKNOWLEDGED, request.state)
            assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
            Thread.sleep(400)
            context.enter()
            try {
                val resumed = System.nanoTime()
                assertSame(Unit, saved.continueWith(Unit))
                assertTrue(System.nanoTime() - resumed < 250_000_000L, "Resume must not restart the original duration")
            } finally { context.leave() }

            identity.set(null)
            val huge = start(Long.MAX_VALUE, MaskingState.UNMASKED)
            awaitBlocked(huge) // Saturation must not turn a huge positive delay into an immediate return.
            val hugeRequest = state.threads.send(identity.get().javaId, "cancel huge delay")
            assertSame(hugeRequest, AsyncContinuations.request(huge.get(5, TimeUnit.SECONDS) as ContinuationResult))

            identity.set(null)
            val maskedStart = System.nanoTime()
            val masked = start(150_000L, MaskingState.MASKED_UNINTERRUPTIBLE)
            awaitBlocked(masked)
            val pending = state.threads.send(identity.get().javaId, "masked delay")
            assertEquals(AsyncRequestState.PENDING, pending.state)
            assertSame(Unit, masked.get(5, TimeUnit.SECONDS))
            assertTrue(System.nanoTime() - maskedStart >= 150_000_000L)
            assertEquals(AsyncRequestState.ACKNOWLEDGED, pending.state)
        } finally {
            context.close(true); worker.shutdownNow()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
