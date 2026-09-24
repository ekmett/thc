package thc

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.ThreadLocalAction
import com.oracle.truffle.api.TruffleSafepoint
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.runtime.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** One context, multiple real guest threads, and a single shared thunk. */
class ThreadedThunkTest {
    private class Driver(metrics: Metrics) : RootNode(null) {
        @Child private var force = Force(metrics)
        override fun execute(frame: VirtualFrame): Any? = force.execute(frame, frame.arguments[0])
        fun force(thunk: Thunk): Any? = Calls.target(callTarget, arrayOf(thunk))
    }

    private fun <T> entered(context: Context, action: () -> T): T {
        context.enter()
        try { return action() } finally { context.leave() }
    }

    @Test fun foreignThreadsWaitForOnePublicationAndTransitionOnlyOnce() {
        executionContext().use { context ->
            context.initialize("thc")
            val language = entered(context) { TruffleLanguage.LanguageReference.create(Language::class.java).get(null) }
            assertTrue(language.singleThreadedAssumption.isValid)
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val evaluations = AtomicInteger()
            val answer = Any()
            val metrics = Metrics(true)
            val (thunk, driver) = entered(context) {
                Thunk(object : RootNode(null) {
                    override fun execute(frame: VirtualFrame): Any {
                        evaluations.incrementAndGet()
                        started.countDown()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                        return answer
                    }
                }.callTarget, null) to Driver(metrics)
            }
            Executors.newFixedThreadPool(3).use { pool ->
                val owner = pool.submit<Any?> { entered(context) { driver.force(thunk) } }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                val readerStarted = CountDownLatch(2)
                val readers = List(2) { pool.submit<Any?> {
                    entered(context) { readerStarted.countDown(); driver.force(thunk) }
                } }
                assertTrue(readerStarted.await(5, TimeUnit.SECONDS))
                assertThrows(TimeoutException::class.java) { readers[0].get(50, TimeUnit.MILLISECONDS) }
                assertFalse(language.singleThreadedAssumption.isValid)
                release.countDown()
                assertSame(answer, owner.get(5, TimeUnit.SECONDS))
                readers.forEach { assertSame(answer, it.get(5, TimeUnit.SECONDS)) }
            }
            assertEquals(1, evaluations.get())
            assertEquals(2, thunk.state)
            assertNull(thunk.target)
            assertNull(thunk.environment)
            assertNull(thunk.owner)
            assertFalse(language.singleThreadedAssumption.isValid)
            assertEquals(1L, metrics.thunkEvaluations)
            assertEquals(2L, metrics.thunkHits)
            assertEquals(1L, metrics.thunkCountsSnapshot().values.single())
        }
    }

    @Test fun recursiveOwnerBlackholesButForeignFailureReadersGetSeparateGuestWrappers() {
        executionContext().use { context ->
            context.initialize("thc")
            val driver = entered(context) { Driver(Metrics(true)) }
            lateinit var recursive: Thunk
            recursive = entered(context) { Thunk(object : RootNode(null) {
                override fun execute(frame: VirtualFrame): Any? = driver.force(recursive)
            }.callTarget, null) }
            val blackhole = entered(context) { assertThrows(RuntimeFault::class.java) { driver.force(recursive) } }
            assertTrue(blackhole.message!!.contains("Blackhole"))
            assertSame(blackhole, entered(context) { assertThrows(RuntimeFault::class.java) { driver.force(recursive) } })

            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val evaluations = AtomicInteger()
            val payload = Any()
            val failed = entered(context) { Thunk(object : RootNode(null) {
                override fun execute(frame: VirtualFrame): Any {
                    evaluations.incrementAndGet()
                    started.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    throw GuestException(payload, this)
                }
            }.callTarget, null) }
            Executors.newFixedThreadPool(2).use { pool ->
                val first = pool.submit<GuestException> { entered(context) {
                    assertThrows(GuestException::class.java) { driver.force(failed) }
                } }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                val second = pool.submit<GuestException> { entered(context) {
                    assertThrows(GuestException::class.java) { driver.force(failed) }
                } }
                release.countDown()
                val a = first.get(5, TimeUnit.SECONDS)
                val b = second.get(5, TimeUnit.SECONDS)
                assertNotSame(a, b)
                assertSame(payload, a.payload)
                assertSame(payload, b.payload)
            }
            assertEquals(1, evaluations.get())
            assertEquals(3, failed.state)
            assertNull(failed.target)
        }
    }

    @Test fun unexpectedHostUnwindWakesWaiterForOneRetry() {
        executionContext().use { context ->
            context.initialize("thc")
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val evaluations = AtomicInteger()
            val (thunk, driver) = entered(context) {
                Thunk(object : RootNode(null) {
                    override fun execute(frame: VirtualFrame): Any {
                        if (evaluations.incrementAndGet() == 1) {
                            started.countDown()
                            assertTrue(release.await(5, TimeUnit.SECONDS))
                            throw IllegalStateException("retry")
                        }
                        return 42L
                    }
                }.callTarget, null) to Driver(Metrics(true))
            }
            Executors.newFixedThreadPool(2).use { pool ->
                val owner = pool.submit<IllegalStateException> { entered(context) {
                    assertThrows(IllegalStateException::class.java) { driver.force(thunk) }
                } }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                val waiter = pool.submit<Any?> { entered(context) { driver.force(thunk) } }
                release.countDown()
                assertEquals("retry", owner.get(5, TimeUnit.SECONDS).message)
                assertEquals(42L, waiter.get(5, TimeUnit.SECONDS))
            }
            assertEquals(2, evaluations.get())
            assertEquals(2, thunk.state)
            assertNull(thunk.target)
        }
    }

    @Test fun asyncDeliveryToAWaiterDoesNotChangeTheOwnersThunk() {
        executionContext().use { context ->
            context.initialize("thc")
            val state = entered(context) { TruffleLanguage.ContextReference.create(Language::class.java).get(null) }
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val evaluations = AtomicInteger()
            val (thunk, driver) = entered(context) {
                Thunk(object : RootNode(null) {
                    override fun execute(frame: VirtualFrame): Any {
                        evaluations.incrementAndGet()
                        started.countDown()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                        return 43L
                    }
                }.callTarget, null) to Driver(Metrics(true))
            }
            Executors.newFixedThreadPool(2).use { pool ->
                val owner = pool.submit<Any?> { entered(context) { driver.force(thunk) } }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                val waiterThread = AtomicReference<Thread>()
                val waiter = pool.submit<Throwable?> { entered(context) {
                    waiterThread.set(Thread.currentThread())
                    try { driver.force(thunk); null } catch (failure: Throwable) { failure }
                } }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (waiterThread.get()?.state != Thread.State.WAITING && System.nanoTime() < deadline)
                    Thread.sleep(1)
                assertEquals(Thread.State.WAITING, waiterThread.get()?.state)
                val delivered = state.env.submitThreadLocal(arrayOf(waiterThread.get()), object : ThreadLocalAction(true, false) {
                    override fun perform(access: Access) { throw AsyncThunkUnwind("waiter") }
                })
                val interruption = waiter.get(5, TimeUnit.SECONDS)
                assertTrue(interruption is AsyncThunkUnwind, "The waiter should unwind at its safepoint: $interruption")
                assertTrue(delivered.isDone)
                assertEquals(1, thunk.state, "Only the owner can publish or unwind its thunk")
                release.countDown()
                assertEquals(43L, owner.get(5, TimeUnit.SECONDS))
            }
            assertEquals(1, evaluations.get())
            assertEquals(2, thunk.state)
        }
    }

    @Test fun explicitlyCompiledAstAndBytecodeEntriesShareUpdatedCafsAcrossThreads() {
        val constructor = mapOf("id" to "Done", "name" to "Done", "arity" to 0, "tag" to 1,
            "kind" to "boxed", "strictFields" to emptyList<Boolean>(), "fieldLifted" to emptyList<Boolean>(),
            "fieldReps" to emptyList<Any>())
        val delayed = listOf("case", listOf("lit", "int", "0"), "ignored", listOf(
            listOf("default", null, emptyList<String>(), listOf("con", "Done", 0))))
        val module = mapOf("schema" to 1, "ghc" to "9.14.1", "module" to "Synthetic.ThreadedCaf",
            "instrument" to true, "constructors" to listOf(constructor), "bindings" to listOf(mapOf(
                "id" to "entry", "name" to "entry", "type" to "Synthetic", "lifted" to true,
                "arity" to 0, "expr" to delayed)))
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            context.initialize("thc")
            // Keep the context threaded before compiling. Its one-way assumption
            // must not retire this deliberately installed guest code later.
            val language = entered(context) { TruffleLanguage.LanguageReference.create(Language::class.java).get(null) }
            Executors.newFixedThreadPool(2).use { pool ->
                val bothEntered = CountDownLatch(2)
                val depart = CountDownLatch(1)
                val entrants = List(2) { pool.submit {
                    entered(context) { bothEntered.countDown(); assertTrue(depart.await(5, TimeUnit.SECONDS)) }
                } }
                assertTrue(bothEntered.await(5, TimeUnit.SECONDS))
                depart.countDown()
                entrants.forEach { it.get(5, TimeUnit.SECONDS) }
            }
            assertFalse(language.singleThreadedAssumption.isValid)
            val (program, thunk) = entered(context) {
                val p: ExecutableProgram = if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
                p to (p.entryValue("entry") as Thunk)
            }
            val entry = entered(context) { EntryValue(program, "entry", 0) }
            entered(context) {
                val warm = Thunk(thunk.target!!, thunk.environment)
                repeat(8) { Calls.target(program.hostEntryTarget(0), arrayOf(warm, emptyArray<Any?>())) }
                assertTrue(entry.invokeMember("compile", emptyArray()) as Boolean, backend)
            }
            Executors.newFixedThreadPool(2).use { pool ->
                val launch = CountDownLatch(1)
                val workers = List(2) { pool.submit<Any?> { entered(context) {
                    assertTrue(launch.await(5, TimeUnit.SECONDS))
                    Calls.target(program.hostEntryTarget(0), arrayOf(thunk, emptyArray<Any?>()))
                } } }
                launch.countDown()
                val first = workers[0].get(5, TimeUnit.SECONDS)
                assertSame(first, workers[1].get(5, TimeUnit.SECONDS), backend)
            }
            assertEquals(2, thunk.state, backend)
            assertNull(thunk.target, backend)
            assertNull(thunk.environment, backend)
            assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > 0, backend)
        }
    }

    @Test fun asyncOwnerUnwindDoesNotMemoizeOrReplayAnEffectWithoutAContinuation() {
        executionContext().use { context ->
            context.initialize("thc")
            val state = entered(context) { TruffleLanguage.ContextReference.create(Language::class.java).get(null) }
            val started = CountDownLatch(1)
            val effects = AtomicInteger()
            val (thunk, driver) = entered(context) {
                Thunk(object : RootNode(null) {
                    override fun execute(frame: VirtualFrame): Any {
                        effects.incrementAndGet()
                        started.countDown()
                        while (true) TruffleSafepoint.poll(this)
                    }
                }.callTarget, null) to Driver(Metrics(true))
            }
            Executors.newSingleThreadExecutor().use { pool ->
                val ownerThread = AtomicReference<Thread>()
                val owner = pool.submit<Throwable?> { entered(context) {
                    ownerThread.set(Thread.currentThread())
                    try { driver.force(thunk); null } catch (failure: Throwable) { failure }
                } }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                state.env.submitThreadLocal(arrayOf(ownerThread.get()), object : ThreadLocalAction(true, false) {
                    override fun perform(access: Access) { throw AsyncThunkUnwind("ThreadKilled") }
                })
                assertTrue(owner.get(5, TimeUnit.SECONDS) is AsyncThunkUnwind)
            }
            assertEquals(4, thunk.state)
            assertNotNull(thunk.target, "The unevaluated body remains available for a future continuation protocol")
            val unsupported = entered(context) { assertThrows(RuntimeFault::class.java) { driver.force(thunk) } }
            assertTrue(unsupported.message!!.contains("no resumable continuation"))
            assertEquals(1, effects.get(), "Refusing replay is necessary after an observable effect")
        }
    }
}
