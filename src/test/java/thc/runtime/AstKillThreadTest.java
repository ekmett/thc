// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeUtil;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import kotlin.Unit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

public class AstKillThreadTest {
    private final Map<String, Object> stateRep = Map.of("kind", "void", "primReps", List.of(), "evaluated", true);
    private final Map<String, Object> threadRep = Map.of("kind", "object", "primReps", List.of("BoxedRep (Just Unlifted)"), "evaluated", true);
    private final Map<String, Object> payloadRep = Map.of("kind", "data", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", false);
    private static Map<String, Object> formal(String id, Map<String, Object> rep, boolean lifted) {
        return Map.of("id", id, "name", id, "lifted", lifted, "coercion", false, "rep", rep);
    }
    private Map<String, Object> directKillModule() {
        var formals = List.of(formal("target", threadRep, false), formal("payload", payloadRep, true), formal("state", stateRep, false));
        var arguments = List.of(List.of("var", "target", Map.of("rep", threadRep)), List.of("var", "payload", Map.of("rep", payloadRep)), List.of("var", "state", Map.of("rep", stateRep)));
        var body = List.of("app", List.of("prim", "killThread#"), arguments, List.of(false, true, false), false, false, Map.of("rep", stateRep));
        return Map.of("bindings", List.of(Map.of("id", "direct", "name", "direct", "lifted", true,
            "expr", List.of("lam", formals, body, Map.of("resultRep", stateRep, "entryStrict", List.of(false, false, false))))), "instrument", true);
    }
    private static void awaitStatus(GuestThreads threads, GuestThreadId id, GuestThreadStatus wanted) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (threads.status(id) != wanted && System.nanoTime() < deadline) Thread.sleep(1);
        assertEquals(wanted, threads.status(id));
    }
    @Test void nonresumableSelfDeliveryPreservesLazyPayloadAndRejectsExternalTargetsBeforeEnqueue() throws Exception {
        for (var backend : List.of("ast", "bytecode")) {
            try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).allowCreateThread(true)
                    .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                    .option("engine.Splitting", "false").option("engine.CompilationFailureAction", "Throw").build()) {
                context.initialize("thc"); context.enter();
                final GuestThreads threads; final RootCallTarget target; final Thunk payload; var forced = new AtomicInteger();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); threads = Language.currentState().getThreads();
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, directKillModule(), false) : new BytecodeProgram(language, directKillModule(), false);
                    target = program.entryTarget("direct");
                    payload = new Thunk(new GuestRoot(language, new FrameLayout().build()) {
                        @Override public long bloom(VirtualFrame frame) { return 0L; }
                        @Override public Object execute(VirtualFrame frame) { forced.incrementAndGet(); throw new AssertionError("killThread# forced its lifted payload"); }
                    }.getCallTarget(), null);
                } finally { context.leave(); }
                var ready = new CompletableFuture<GuestThreadId>(); var finished = new CompletableFuture<Unit>(); var release = new CountDownLatch(1);
                var receiver = new Thread(() -> {
                    context.enter();
                    try {
                        // Rejection must come from the uncaptured sender before any enqueue.
                        threads.enterCurrent(MaskingState.MASKED_UNINTERRUPTIBLE, false, true, null);
                        try {
                            ready.complete(threads.currentIdentity()); assertTrue(release.await(60, TimeUnit.SECONDS));
                            SynchronousMasking.set(target.getRootNode(), MaskingState.UNMASKED);
                            assertNull(threads.poll(target.getRootNode()), backend + " rejected send was queued"); finished.complete(Unit.INSTANCE);
                        } finally { threads.leaveCurrent(); }
                    } catch (Throwable failure) { finished.completeExceptionally(failure); } finally { context.leave(); }
                });
                receiver.setDaemon(true); receiver.start();
                try {
                    var external = ready.get(10, TimeUnit.SECONDS); context.enter();
                    try {
                        threads.enterCurrent(null, false, false, null);
                        try {
                            var self = threads.currentIdentity();
                            for (boolean compiled : new boolean[]{false, true}) {
                                if (compiled) {
                                    target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                                    // Restore the stub without executing a settling call.
                                    var runtime = Truffle.getRuntime(); runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target);
                                }
                                for (var mask : MaskingState.values()) {
                                    SynchronousMasking.set(target.getRootNode(), mask);
                                    var delivered = assertThrows(AsyncDelivery.class, () -> Calls.target(target, new Object[]{0L, self, payload, Unit.INSTANCE}));
                                    var request = delivered.getRequest(); assertSame(Thread.currentThread(), request.getTarget());
                                    assertEquals(self.getLogicalId(), request.getTargetId()); assertTrue(request.getForceSelf()); assertSame(payload, request.getPayload());
                                    assertEquals(AsyncRequestState.CLAIMED, request.getState());
                                    if (compiled) assertTrue(request.compiledCapture, backend + " first installed self-delivery");
                                    assertSame(payload, BytecodeRoot.RequireCaughtIOFailure.payload(delivered)); assertEquals(AsyncRequestState.ACKNOWLEDGED, request.getState());
                                    assertNull(threads.poll(target.getRootNode())); assertEquals(mask, SynchronousMasking.current(target.getRootNode())); assertEquals(0, payload.getState()); assertEquals(0, forced.get());
                                }
                                SynchronousMasking.set(target.getRootNode(), MaskingState.UNMASKED);
                                var rejected = assertThrows(UnsupportedCore.class, () -> Calls.target(target, new Object[]{0L, external, payload, Unit.INSTANCE}));
                                assertTrue(rejected.getMessage().contains("captured sender continuation")); assertEquals(0, payload.getState()); assertEquals(0, forced.get());
                            }
                        } finally { SynchronousMasking.set(target.getRootNode(), MaskingState.UNMASKED); threads.leaveCurrent(); }
                    } finally { context.leave(); }
                    release.countDown(); finished.get(10, TimeUnit.SECONDS);
                } finally { release.countDown(); receiver.join(5000); assertFalse(receiver.isAlive()); }
            }
        }
    }
    @Test void completedExternalSendCapturesAnIncomingRequestWithoutResending() throws Exception {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).allowCreateThread(true).build()) {
            context.initialize("thc"); context.enter(); final GuestThreads threads; final Program program;
            try { var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); threads = Language.currentState().getThreads(); program = new Program(language, directKillModule(), true); }
            finally { context.leave(); }
            var root = (GuestRoot) program.entryTarget("direct").getRootNode(); var kills = NodeUtil.findAllNodeInstances(root, KillThread.class); assertEquals(1, kills.size()); var kill = kills.getFirst();
            var ready = new CompletableFuture<GuestThreadId>(); var done = new CompletableFuture<Unit>(); var release = new CountDownLatch(1); var deliveries = new AtomicInteger();
            var receiver = new Thread(() -> {
                context.enter();
                try {
                    threads.enterCurrent();
                    try {
                        ready.complete(threads.currentIdentity()); assertTrue(release.await(10, TimeUnit.SECONDS));
                        var request = Objects.requireNonNull(threads.poll(root), "The outbound request was not queued"); assertEquals("outbound", request.getPayload());
                        deliveries.incrementAndGet(); request.acknowledge(); done.complete(Unit.INSTANCE);
                    } finally { threads.leaveCurrent(); }
                } catch (Throwable failure) { done.completeExceptionally(failure); } finally { context.leave(); }
            }); receiver.setDaemon(true); receiver.start();
            try {
                var targetId = ready.get(10, TimeUnit.SECONDS); context.enter();
                try {
                    threads.enterCurrent();
                    try {
                        var senderId = threads.currentIdentity(); var sent = GuestThreadOps.beginKill(kill, targetId, "outbound"); release.countDown(); GuestThreadOps.finishKill(kill, sent);
                        done.get(10, TimeUnit.SECONDS); assertEquals(AsyncRequestState.ACKNOWLEDGED, sent.getState());
                        // Queue after ACK, making the post-send poll the first claim point.
                        var incomingReady = new CompletableFuture<AsyncRequest>();
                        var interruptor = new Thread(() -> { try { incomingReady.complete(threads.send(senderId, "after ACK")); } catch (Throwable failure) { incomingReady.completeExceptionally(failure); } });
                        interruptor.start(); var incoming = incomingReady.get(10, TimeUnit.SECONDS); interruptor.join(5000); assertFalse(interruptor.isAlive());
                        var cut = assertThrows(AstCapture.class, () -> kill.finish(sent)); assertSame(incoming, cut.getYielded()); incoming.acknowledge();
                        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], root.getFrameDescriptor()); var saved = cut.freeze(root, frame.materialize());
                        assertSame(Unit.INSTANCE, saved.continueWith(Unit.INSTANCE)); assertEquals(AsyncRequestState.ACKNOWLEDGED, sent.getState());
                        assertEquals(1, deliveries.get(), "The completed outgoing send must not be replayed"); assertThrows(RuntimeFault.class, () -> saved.continueWith(Unit.INSTANCE));
                    } finally { threads.leaveCurrent(); }
                } finally { context.leave(); }
            } finally { release.countDown(); receiver.join(5000); if (receiver.isAlive()) { context.close(true); receiver.join(5000); } }
        }
    }
    @Test void uncapturedExternalSendRejectsBeforeEnqueueAndCapturedSenderResumesTheSameRequestTwice() throws Exception {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).allowCreateThread(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.Splitting", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter(); final GuestThreads threads; final Program captured; final Program plain;
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); threads = Language.currentState().getThreads();
                captured = new Program(language, directKillModule(), true); plain = new Program(language, directKillModule());
            } finally { context.leave(); }
            var targetReady = new CompletableFuture<GuestThreadId>(); var targetDone = new CompletableFuture<Unit>(); var releaseTarget = new CountDownLatch(1); var deliveries = new AtomicInteger();
            var receiver = new Thread(() -> {
                context.enter();
                try {
                    threads.enterCurrent(MaskingState.MASKED_UNINTERRUPTIBLE, false, true, null);
                    try {
                        targetReady.complete(threads.currentIdentity()); assertTrue(releaseTarget.await(10, TimeUnit.SECONDS)); Language.currentState().getMaskingState().set(MaskingState.UNMASKED);
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                        while (deliveries.get() == 0 && System.nanoTime() < deadline) {
                            var request = threads.poll(plain.entryTarget("direct").getRootNode());
                            if (request != null) { assertEquals("outbound", request.getPayload()); deliveries.incrementAndGet(); request.acknowledge(); } else Thread.sleep(1);
                        }
                        assertEquals(1, deliveries.get()); targetDone.complete(Unit.INSTANCE);
                    } finally { threads.leaveCurrent(); }
                } catch (Throwable failure) { targetDone.completeExceptionally(failure); } finally { context.leave(); }
            }); receiver.setDaemon(true); receiver.start();
            try {
                var targetId = targetReady.get(10, TimeUnit.SECONDS); context.enter();
                try {
                    threads.enterCurrent();
                    try {
                        var unsupported = assertThrows(UnsupportedCore.class, () -> Calls.target(plain.entryTarget("direct"), new Object[]{0L, targetId, "outbound", Unit.INSTANCE}));
                        assertTrue(unsupported.getMessage().contains("captured sender continuation"));
                    } finally { threads.leaveCurrent(); }
                } finally { context.leave(); }
                assertEquals(0, deliveries.get()); context.enter();
                try {
                    long self = threads.enterCurrent(); var selfId = threads.currentIdentity();
                    try {
                        for (int i = 0; i < 5; i++) {
                            var continuation = (AstContinuation) Calls.target(captured.entryTarget("direct"), new Object[]{0L, selfId, "warm", Unit.INSTANCE});
                            var request = Objects.requireNonNull(continuation.asyncRequest(), "Self throw did not retain its request"); assertEquals("warm", request.getPayload());
                            request.acknowledge(); assertSame(Unit.INSTANCE, continuation.continueWith(Unit.INSTANCE));
                        }
                        assertEquals(self, selfId.getLogicalId()); var target = captured.entryTarget("direct"); target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                    } finally { threads.leaveCurrent(); }
                } finally { context.leave(); }
                var senderReady = new CompletableFuture<GuestThreadId>(); var firstCut = new CompletableFuture<AstContinuation>();
                var sender = new Thread(() -> {
                    context.enter();
                    try {
                        threads.enterCurrent();
                        try {
                            senderReady.complete(threads.currentIdentity()); var cut = (AstContinuation) Calls.target(captured.entryTarget("direct"), new Object[]{0L, targetId, "outbound", Unit.INSTANCE});
                            var incoming = (AsyncRequest) cut.getYielded(); assertEquals("first", incoming.getPayload()); assertTrue(incoming.compiledCapture, "The first interruption entered compiled AST");
                            incoming.acknowledge(); firstCut.complete(cut);
                        } finally { threads.leaveCurrent(); }
                    } catch (Throwable failure) { firstCut.completeExceptionally(failure); } finally { context.leave(); }
                });
                long before = ((Number) captured.diagnostics().get("compiledEntries")).longValue(); sender.setDaemon(true); sender.start();
                var senderId = senderReady.get(10, TimeUnit.SECONDS); awaitStatus(threads, senderId, GuestThreadStatus.THROW_TO); threads.send(senderId, "first");
                var once = firstCut.get(10, TimeUnit.SECONDS); sender.join(5000); assertFalse(sender.isAlive());
                assertEquals(before + 1, ((Number) captured.diagnostics().get("compiledEntries")).longValue()); assertEquals(0, deliveries.get(), "The interrupted outbound request must be paused");
                context.enter();
                try {
                    threads.enterCurrent();
                    try {
                        var resumer = threads.currentIdentity(); var second = new CompletableFuture<AsyncRequest>();
                        var interruptor = new Thread(() -> {
                            try { awaitStatus(threads, resumer, GuestThreadStatus.THROW_TO); second.complete(threads.send(resumer, "second")); }
                            catch (Throwable failure) { second.completeExceptionally(failure); }
                        }); interruptor.start();
                        var twice = (AstContinuation) once.continueWith(Unit.INSTANCE); var incoming = second.get(10, TimeUnit.SECONDS); assertSame(incoming, twice.getYielded());
                        incoming.acknowledge(); interruptor.join(5000); assertFalse(interruptor.isAlive()); assertEquals(0, deliveries.get()); releaseTarget.countDown();
                        assertSame(Unit.INSTANCE, twice.continueWith(Unit.INSTANCE)); targetDone.get(10, TimeUnit.SECONDS); assertEquals(1, deliveries.get(), "Resumption must requeue one outbound request");
                        assertThrows(RuntimeFault.class, () -> once.continueWith(Unit.INSTANCE)); assertThrows(RuntimeFault.class, () -> twice.continueWith(Unit.INSTANCE));
                    } finally { threads.leaveCurrent(); }
                } finally { context.leave(); }
            } finally { releaseTarget.countDown(); receiver.join(5000); if (receiver.isAlive()) { context.close(true); receiver.join(5000); } }
        }
    }
}
