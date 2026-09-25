// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AstKillThreadTest {
    private val stateRep = mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true)
    private val threadRep = mapOf("kind" to "object", "primReps" to listOf("BoxedRep (Just Unlifted)"),
        "evaluated" to true)
    private val payloadRep = mapOf("kind" to "data", "primReps" to listOf("BoxedRep (Just Lifted)"),
        "evaluated" to false)

    private fun directKillModule(): Map<String, Any?> {
        fun formal(id: String, rep: Map<String, Any?>, lifted: Boolean) =
            mapOf("id" to id, "name" to id, "lifted" to lifted, "coercion" to false, "rep" to rep)
        val formals = listOf(formal("target", threadRep, false), formal("payload", payloadRep, true),
            formal("state", stateRep, false))
        val arguments = listOf("target" to threadRep, "payload" to payloadRep, "state" to stateRep)
            .map { (id, rep) -> listOf("var", id, mapOf("rep" to rep)) }
        val body = listOf("app", listOf("prim", "killThread#"), arguments,
            listOf(false, true, false), false, false, mapOf("rep" to stateRep))
        return mapOf("bindings" to listOf(mapOf("id" to "direct", "name" to "direct", "lifted" to true,
            "expr" to listOf("lam", formals, body,
                mapOf("resultRep" to stateRep, "entryStrict" to listOf(false, false, false))))),
            "instrument" to true)
    }

    private fun awaitStatus(threads: GuestThreads, id: GuestThreadId, wanted: GuestThreadStatus) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (threads.status(id) != wanted && System.nanoTime() < deadline) Thread.sleep(1)
        assertEquals(wanted, threads.status(id))
    }

    @Test fun completedExternalSendCapturesAnIncomingRequestWithoutResending() {
        Context.newBuilder("thc").allowExperimentalOptions(true).allowCreateThread(true).build().use { context ->
            context.initialize("thc")
            context.enter()
            val threads: GuestThreads
            val program: Program
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                threads = Language.currentState().threads
                program = Program(language, directKillModule(), true)
            } finally { context.leave() }

            val root = program.entryTarget("direct").rootNode as GuestRoot
            val kill = NodeUtil.findAllNodeInstances(root, KillThread::class.java).single()
            val ready = CompletableFuture<GuestThreadId>()
            val done = CompletableFuture<Unit>()
            val release = CountDownLatch(1)
            val deliveries = AtomicInteger()
            val receiver = Thread {
                context.enter()
                try {
                    threads.enterCurrent()
                    try {
                        ready.complete(threads.currentIdentity())
                        assertTrue(release.await(10, TimeUnit.SECONDS))
                        val request = threads.poll(root) ?: error("The outbound request was not queued")
                        assertEquals("outbound", request.payload)
                        deliveries.incrementAndGet()
                        request.acknowledge()
                        done.complete(Unit)
                    } finally { threads.leaveCurrent() }
                } catch (failure: Throwable) { done.completeExceptionally(failure) }
                finally { context.leave() }
            }
            receiver.isDaemon = true
            receiver.start()
            try {
                val targetId = ready.get(10, TimeUnit.SECONDS)
                context.enter()
                try {
                    threads.enterCurrent()
                    try {
                        val senderId = threads.currentIdentity()
                        val sent = GuestThreadOps.beginKill(kill, targetId, "outbound")
                        release.countDown()
                        GuestThreadOps.finishKill(kill, sent)
                        done.get(10, TimeUnit.SECONDS)
                        assertEquals(AsyncRequestState.ACKNOWLEDGED, sent.state)

                        // Queue only after ACK, so the post-send poll is the first
                        // point at which this incoming request can be claimed.
                        val incomingReady = CompletableFuture<AsyncRequest>()
                        val interruptor = Thread {
                            try { incomingReady.complete(threads.send(senderId, "after ACK")) }
                            catch (failure: Throwable) { incomingReady.completeExceptionally(failure) }
                        }
                        interruptor.start()
                        val incoming = incomingReady.get(10, TimeUnit.SECONDS)
                        interruptor.join(5000)
                        assertFalse(interruptor.isAlive)
                        val cut = assertThrows(AstCapture::class.java) { kill.finish(sent) }
                        assertSame(incoming, cut.yielded)
                        incoming.acknowledge()
                        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), root.frameDescriptor)
                        val saved = cut.freeze(root, frame.materialize())
                        assertSame(Unit, saved.continueWith(Unit))
                        assertEquals(AsyncRequestState.ACKNOWLEDGED, sent.state)
                        assertEquals(1, deliveries.get(), "The completed outgoing send must not be replayed")
                        assertThrows(RuntimeFault::class.java) { saved.continueWith(Unit) }
                    } finally { threads.leaveCurrent() }
                } finally { context.leave() }
            } finally {
                release.countDown()
                receiver.join(5000)
                if (receiver.isAlive) {
                    context.close(true)
                    receiver.join(5000)
                }
            }
        }
    }

    @Test fun uncapturedExternalSendRejectsBeforeEnqueueAndCapturedSenderResumesTheSameRequestTwice() {
        Context.newBuilder("thc").allowExperimentalOptions(true).allowCreateThread(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.Splitting", "false").option("engine.CompilationFailureAction", "Throw")
            .build().use { context ->
            context.initialize("thc"); context.enter()
            val threads: GuestThreads
            val captured: Program
            val plain: Program
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                threads = Language.currentState().threads
                captured = Program(language, directKillModule(), true)
                plain = Program(language, directKillModule())
            } finally { context.leave() }

            val targetReady = CompletableFuture<GuestThreadId>()
            val targetDone = CompletableFuture<Unit>()
            val releaseTarget = CountDownLatch(1)
            val deliveries = AtomicInteger()
            val receiver = Thread {
                context.enter()
                try {
                    threads.enterCurrent(MaskingState.MASKED_UNINTERRUPTIBLE)
                    try {
                        targetReady.complete(threads.currentIdentity())
                        assertTrue(releaseTarget.await(10, TimeUnit.SECONDS))
                        Language.currentState().maskingState.set(MaskingState.UNMASKED)
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                        while (deliveries.get() == 0 && System.nanoTime() < deadline) {
                            val request = threads.poll(plain.entryTarget("direct").rootNode)
                            if (request != null) {
                                assertEquals("outbound", request.payload)
                                deliveries.incrementAndGet()
                                request.acknowledge()
                            } else Thread.sleep(1)
                        }
                        assertEquals(1, deliveries.get())
                        targetDone.complete(Unit)
                    } finally { threads.leaveCurrent() }
                } catch (failure: Throwable) { targetDone.completeExceptionally(failure) }
                finally { context.leave() }
            }
            receiver.isDaemon = true
            receiver.start()
            try {
                val targetId = targetReady.get(10, TimeUnit.SECONDS)
                context.enter()
                try {
                    threads.enterCurrent()
                    try {
                        val unsupported = assertThrows(UnsupportedCore::class.java) {
                            Calls.target(plain.entryTarget("direct"), arrayOf(0L, targetId, "outbound", Unit))
                        }
                        assertTrue(unsupported.message!!.contains("captured sender continuation"))
                    } finally { threads.leaveCurrent() }
                } finally { context.leave() }
                assertEquals(0, deliveries.get())

                context.enter()
                try {
                    val self = threads.enterCurrent()
                    val selfId = threads.currentIdentity()
                    try {
                        repeat(5) {
                            val delivered = assertThrows(AsyncDelivery::class.java) {
                                Calls.target(captured.entryTarget("direct"), arrayOf(0L, selfId, "warm", Unit))
                            }
                            assertEquals("warm", delivered.request.payload)
                            delivered.request.acknowledge()
                        }
                        assertEquals(self, selfId.javaId)
                        val target = captured.entryTarget("direct")
                        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                    } finally { threads.leaveCurrent() }
                } finally { context.leave() }

                val senderReady = CompletableFuture<GuestThreadId>()
                val firstCut = CompletableFuture<AstContinuation>()
                val sender = Thread {
                    context.enter()
                    try {
                        threads.enterCurrent()
                        try {
                            senderReady.complete(threads.currentIdentity())
                            val cut = Calls.target(captured.entryTarget("direct"),
                                arrayOf(0L, targetId, "outbound", Unit)) as AstContinuation
                            val incoming = cut.yielded as AsyncRequest
                            assertEquals("first", incoming.payload)
                            assertTrue(incoming.compiledCapture, "The first interruption entered compiled AST")
                            incoming.acknowledge()
                            firstCut.complete(cut)
                        } finally { threads.leaveCurrent() }
                    } catch (failure: Throwable) { firstCut.completeExceptionally(failure) }
                    finally { context.leave() }
                }
                val before = (captured.diagnostics().getValue("compiledEntries") as Number).toLong()
                sender.isDaemon = true
                sender.start()
                val senderId = senderReady.get(10, TimeUnit.SECONDS)
                awaitStatus(threads, senderId, GuestThreadStatus.THROW_TO)
                threads.send(senderId, "first")
                val once = firstCut.get(10, TimeUnit.SECONDS)
                sender.join(5000)
                assertFalse(sender.isAlive)
                assertEquals(before + 1, (captured.diagnostics().getValue("compiledEntries") as Number).toLong())
                assertEquals(0, deliveries.get(), "The interrupted outbound request must be paused")

                context.enter()
                try {
                    threads.enterCurrent()
                    try {
                        val resumer = threads.currentIdentity()
                        val second = CompletableFuture<AsyncRequest>()
                        val interruptor = Thread {
                            try {
                                awaitStatus(threads, resumer, GuestThreadStatus.THROW_TO)
                                second.complete(threads.send(resumer, "second"))
                            } catch (failure: Throwable) { second.completeExceptionally(failure) }
                        }
                        interruptor.start()
                        val twice = once.continueWith(Unit) as AstContinuation
                        val incoming = second.get(10, TimeUnit.SECONDS)
                        assertSame(incoming, twice.yielded)
                        incoming.acknowledge()
                        interruptor.join(5000)
                        assertFalse(interruptor.isAlive)
                        assertEquals(0, deliveries.get())
                        releaseTarget.countDown()
                        assertSame(Unit, twice.continueWith(Unit))
                        targetDone.get(10, TimeUnit.SECONDS)
                        assertEquals(1, deliveries.get(), "Resumption must requeue one outbound request")
                        assertThrows(RuntimeFault::class.java) { once.continueWith(Unit) }
                        assertThrows(RuntimeFault::class.java) { twice.continueWith(Unit) }
                    } finally { threads.leaveCurrent() }
                } finally { context.leave() }
            } finally {
                releaseTarget.countDown()
                receiver.join(5000)
                if (receiver.isAlive) {
                    context.close(true)
                    receiver.join(5000)
                }
            }
        }
    }
}
