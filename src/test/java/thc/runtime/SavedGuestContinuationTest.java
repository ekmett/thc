// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import com.oracle.truffle.runtime.OptimizedTruffleRuntime;
import java.util.Arrays;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.ArrayDeque;
import java.lang.foreign.Arena;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thc.Language;
import thc.Main;
import static org.junit.jupiter.api.Assertions.*;

class SavedGuestContinuationTest {
    private static final class LifetimeOwner extends GuestRoot {
        LifetimeOwner(Language language) { super(language, com.oracle.truffle.api.frame.FrameDescriptor.newBuilder().build()); }
        @Override public Object execute(VirtualFrame frame) { throw new AssertionError("Only the saved activation is entered"); }
        @Override public long bloom(VirtualFrame frame) { return 0L; }
    }
    private static final class LifetimeDriver extends RootNode {
        @Child private Force force = new Force(new Metrics(false));
        LifetimeDriver(Language language) { super(language); }
        @Override public Object execute(VirtualFrame frame) {
            return force.drainStack((SavedGuestContinuation) frame.getArguments()[0]);
        }
    }
    private static final class LifetimeObservation {
        WeakReference<Object> produced;
    }
    private static AstResumeStep ownedMemory(Arena arena) {
        var memory = arena.allocateFrom(JAVA_INT, 123);
        return new AstResumeStep() {
            @Override public Object resume(VirtualFrame frame, Object input) {
                try { return memory.get(JAVA_INT, 0); }
                finally { arena.close(); }
            }
            @Override public void discard() { arena.close(); }
        };
    }
    @Test void exceptionalUnwindDiscardsPendingOwnedSteps() {
        var arena = Arena.ofShared();
        try {
            var steps = new ArrayDeque<AstResumeStep>();
            steps.add((frame, input) -> { throw RuntimeFault.fault("terminal suffix failure"); });
            steps.add(ownedMemory(arena));
            assertThrows(RuntimeFault.class, () -> AstContinuations.resumeAstSteps(null, steps, Unit.INSTANCE));
            assertFalse(arena.scope().isAlive(), "Unwinding dropped a native resource owner without discard");
        } finally { if (arena.scope().isAlive()) arena.close(); }
    }
    @Test void failedDiscardPreservesPrimaryFailureAndCleansRemainingOwners() {
        var first = Arena.ofShared(); var last = Arena.ofShared();
        try {
            var primary = RuntimeFault.fault("original guest failure");
            var cleanup = RuntimeFault.fault("failed native cleanup");
            var steps = new ArrayDeque<AstResumeStep>();
            steps.add((frame, input) -> { throw primary; });
            steps.add(new AstResumeStep() {
                @Override public Object resume(VirtualFrame frame, Object input) { return fail("Discarded work ran"); }
                @Override public void discard() { first.close(); throw cleanup; }
            });
            steps.add(ownedMemory(last));
            var failure = assertThrows(RuntimeFault.class, () -> AstContinuations.resumeAstSteps(null, steps, Unit.INSTANCE));
            assertFalse(first.scope().isAlive()); assertFalse(last.scope().isAlive());
            assertSame(primary, failure);
            assertArrayEquals(new Throwable[]{cleanup}, failure.getSuppressed());
        } finally { if (first.scope().isAlive()) first.close(); if (last.scope().isAlive()) last.close(); }
    }
    @Test void failedAsyncDiscardSettlesDeliveryAndRetainsOriginalPayload() {
        try (var context = Main.executionContext()) {
            context.initialize("thc"); context.enter();
            var threads = Language.currentState().getThreads();
            threads.enterCurrent(MaskingState.UNMASKED, true, true, null);
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (boolean caught : new boolean[]{false, true}) {
                    var arena = Arena.ofShared();
                    try {
                        var payload = new Object(); var cleanup = RuntimeFault.fault("failed discarded async cleanup");
                        var request = threads.send(threads.currentId(), payload);
                        assertSame(request, threads.poll(null, true));
                        assertEquals(AsyncRequestState.CLAIMED, request.getState());
                        var owner = new LifetimeOwner(language);
                        var frame = Truffle.getRuntime().createMaterializedFrame(new Object[]{0L}, owner.getFrameDescriptor());
                        var saved = new AstContinuation(owner, request, MaskingState.UNMASKED, frame, List.of(new AstResumeStep() {
                            @Override public Object resume(VirtualFrame activation, Object input) { return fail("Discarded async work ran"); }
                            @Override public void discard() { arena.close(); throw cleanup; }
                        }), StackAnnotationState.EMPTY);
                        var failure = assertThrows(GuestException.class, () -> {
                            if (caught) AsyncContinuations.deliverIfCaught(saved, true, null);
                            else AsyncContinuations.publicResult(saved, null);
                        });
                        assertSame(payload, failure.getPayload());
                        assertArrayEquals(new Throwable[]{cleanup}, failure.getSuppressed());
                        assertEquals(AsyncRequestState.ACKNOWLEDGED, request.getState());
                        assertEquals(AsyncRequestState.ACKNOWLEDGED, request.await(null));
                        assertFalse(arena.scope().isAlive());
                    } finally { if (arena.scope().isAlive()) arena.close(); }
                }
            } finally { threads.leaveCurrent(); context.leave(); }
        }
    }
    @Test void recaptureTransfersOwnershipBeforeResumeOrDiscard() {
        try (var context = Main.executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (boolean discard : new boolean[]{false, true}) {
                    var arena = Arena.ofShared();
                    try {
                        var owner = new LifetimeOwner(language);
                        var frame = Truffle.getRuntime().createMaterializedFrame(new Object[]{0L}, owner.getFrameDescriptor());
                        var steps = new ArrayDeque<AstResumeStep>();
                        steps.add((saved, input) -> { throw new AstCapture(AstStackSpill.INSTANCE, MaskingState.UNMASKED); });
                        steps.add(ownedMemory(arena));
                        var cut = assertThrows(AstCapture.class, () -> AstContinuations.resumeAstSteps(frame, steps, Unit.INSTANCE));
                        assertTrue(arena.scope().isAlive(), "Recapture discarded transferred work");
                        cut.enclose(pending -> (saved, input) -> AstContinuations.resumeAstSteps(saved, pending, input));
                        var saved = cut.freeze(owner, frame);
                        cut.discard();
                        assertTrue(arena.scope().isAlive(), "Freezing left duplicate ownership on the capture");
                        if (discard) {
                            saved.discard(); saved.discard();
                            assertThrows(RuntimeFault.class, () -> saved.continueWith(Unit.INSTANCE));
                        } else assertEquals(123, saved.continueWith(Unit.INSTANCE));
                        assertFalse(arena.scope().isAlive(), "Terminal scope retained its native resource");
                    } finally { if (arena.scope().isAlive()) arena.close(); }
                }
            } finally { context.leave(); }
        }
    }
    @Test void discardingAWaiterPreservesItsRetainedSharedChild() {
        try (var context = Main.executionContext()) {
            context.initialize("thc"); context.enter();
            var arena = Arena.ofShared();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var owner = new LifetimeOwner(language);
                var frame = Truffle.getRuntime().createMaterializedFrame(new Object[]{0L}, owner.getFrameDescriptor());
                var child = new AstContinuation(owner, AstStackSpill.INSTANCE, MaskingState.UNMASKED, frame,
                    List.of(ownedMemory(arena)), StackAnnotationState.EMPTY);
                var segment = new CallSegment(child);
                var parked = new CallSegmentSuspended(segment, null, null, true);
                var caller = AstControl.captureChild(owner, parked).freeze(owner, frame);
                caller.discard();
                assertTrue(arena.scope().isAlive(), "Discarding a waiter disposed its retained child");
                assertEquals(123, Calls.target(new LifetimeDriver(language).getCallTarget(),
                    new Object[]{new AstStackContinuation(owner, parked)}));
                assertFalse(arena.scope().isAlive());
            } finally { if (arena.scope().isAlive()) arena.close(); context.leave(); }
        }
    }
    private static WeakReference<Object> deadWitness() { return new WeakReference<>(new Object()); }
    private static boolean collect(WeakReference<Object> reference) {
        long deadline = System.nanoTime() + 3_000_000_000L;
        do {
            System.gc();
            if (reference.refersTo(null)) return true;
            try { Thread.sleep(10); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
        } while (System.nanoTime() < deadline);
        return reference.refersTo(null);
    }
    private static Object observeReleasedChild(LifetimeObservation observation) {
        Object live = new Object();
        var liveReference = new WeakReference<>(live);
        var witness = deadWitness();
        try {
            boolean collected = collect(observation.produced);
            assertTrue(witness.refersTo(null), "Independent dead key must establish collector progress");
            assertTrue(liveReference.refersTo(live), "A Java-held key must remain live");
            assertTrue(collected, "A discarded child result must die during the unrelated suffix");
            return Unit.INSTANCE;
        } finally { Reference.reachabilityFence(live); }
    }
    private static AstContinuation lifetimeActivation(Language language, LifetimeObservation observation, int scopes) {
        var owner = new LifetimeOwner(language);
        var childFrame = Truffle.getRuntime().createMaterializedFrame(new Object[]{0L}, owner.getFrameDescriptor());
        var child = new AstContinuation(owner, AstStackSpill.INSTANCE, MaskingState.UNMASKED, childFrame,
            List.of((frame, input) -> {
                assertSame(Unit.INSTANCE, input);
                Object key = new Object();
                observation.produced = new WeakReference<>(key);
                return key;
            }), StackAnnotationState.EMPTY);
        var suspended = new CallSegmentSuspended(new CallSegment(child), null, null, true);
        var cut = AstControl.captureChild(owner, suspended)
            .append((frame, input) -> Unit.INSTANCE)
            .append((frame, input) -> { assertSame(Unit.INSTANCE, input); return observeReleasedChild(observation); });
        for (int i = 0; i < scopes; i++)
            cut.enclose(steps -> (frame, input) -> AstContinuations.resumeAstSteps(frame, steps, input));
        return cut.freeze(owner, Truffle.getRuntime().createMaterializedFrame(new Object[]{0L}, owner.getFrameDescriptor()));
    }
    @ParameterizedTest @ValueSource(ints = {0, 2})
    void consumedChildDoesNotOwnItsDiscardedResultDuringSuffix(int scopes) {
        try (var context = Main.executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var observation = new LifetimeObservation();
                // The factory returns before the child creates its key; the driver
                // legitimately retains the parent activation during the suffix.
                assertSame(Unit.INSTANCE, Calls.target(new LifetimeDriver(language).getCallTarget(),
                    new Object[]{lifetimeActivation(language, observation, scopes)}));
            } finally { context.leave(); }
        }
    }

    @Test void compiledBytecodeResumeRestoresTransactionFromSavedLocals() {
        try (var context = Main.executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var stm = Language.currentState().stm;
                for (boolean transactional : new boolean[]{false, true}) {
                    var savedTransaction = transactional ? stm.begin() : null;
                    var slot = new LocalAccessor[1];
                    var metrics = new Metrics(true);
                    var marker = new Object();
                    var root = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                        b.beginRoot();
                        var transaction = b.createLocal("saved transaction", FrameSlotKind.Object);
                        slot[0] = LocalAccessor.constantOf(transaction);
                        b.beginStaticStoreObject(transaction); b.emitCurrentTransaction(); b.endStaticStoreObject();
                        b.beginYield(); b.beginRecordContinuationOwner(); b.emitLoadConstant(marker); b.endRecordContinuationOwner(); b.endYield();
                        b.emitEnterRoot(metrics);
                        b.beginReturn(); b.emitCurrentTransaction(); b.endReturn();
                        b.endRoot();
                    }).getNode(0);
                    root.configureAsync(true); root.configureStackDriver(metrics);
                    root.configureStackTransaction(slot[0]);
                    var saved = assertInstanceOf(ContinuationResult.class, Calls.target(root.getCallTarget(), new Object[]{0L}));
                    assertSame(marker, saved.getResult());
                    var target = (OptimizedCallTarget) saved.getContinuationRootNode().getCallTarget();
                    target.compile(true); target.waitForCompilation(); assertTrue(target.isValidLastTier());
                    ((OptimizedTruffleRuntime) Truffle.getRuntime()).bypassedInstalledCode(target);
                    var ambient = stm.begin();
                    try {
                        assertSame(savedTransaction, saved.continueWith(Unit.INSTANCE));
                        // Later cold operations may deoptimize; the installed entry must
                        // still read its transaction from the saved frame, not operand locals.
                        assertSame(root, saved.getContinuationRootNode().getSourceRootNode());
                        assertSame(ambient, stm.currentTransaction());
                        assertEquals(0, AstStacks.astStackScope(root).getDepth());
                        assertFalse(AstStacks.astStackScope(root).getDriving());
                    } finally {
                        stm.retire(ambient);
                        if (savedTransaction != null) stm.retire(savedTransaction);
                        stm.restore(null);
                    }
                }
            } finally { context.leave(); }
        }
    }

    private static ContinuationResult rawToken(Language language) { return rawToken(language, Unit.INSTANCE); }
    private static ContinuationResult rawToken(Language language, Object marker) {
        var root = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
            b.beginRoot(); b.beginReturn();
            b.beginYield(); b.beginRecordContinuationOwner(); b.emitLoadConstant(marker); b.endRecordContinuationOwner(); b.endYield();
            b.endReturn(); b.endRoot();
        }).getNode(0);
        return assertInstanceOf(ContinuationResult.class, Calls.target(root.getCallTarget(), new Object[]{0L}));
    }
    @Test void bytecodeAliasesShareTerminalResumeAndDiscard() {
        try (var context = Main.executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (boolean discard : new boolean[]{false, true}) {
                    var token = rawToken(language);
                    var first = SavedGuestContinuations.savedGuestContinuation(token);
                    var alias = SavedGuestContinuations.savedGuestContinuation(token);
                    assertSame(token, first.getIdentity()); assertSame(token, alias.getIdentity());
                    if (discard) first.discard(); else assertEquals(41L, first.continueWith(41L));
                    alias.discard(); alias.discard();
                    assertThrows(RuntimeFault.class, () -> alias.continueWith(42L));
                }
            } finally { context.leave(); }
        }
    }
    @Test void bytecodeDistinctTokensAndCopiedFramesRemainIndependent() {
        try (var context = Main.executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var first = rawToken(language);
                var sameFrame = ContinuationResult.create(first.getContinuationRootNode(), first.getFrame(), first.getResult());
                var copied = ContinuationResult.create(first.getContinuationRootNode(),
                    DelimitedContinuations.copyContinuationFrame(first.getFrame()), first.getResult());
                assertNotSame(first, sameFrame); assertSame(first.getFrame(), sameFrame.getFrame());
                SavedGuestContinuations.savedGuestContinuation(first).discard();
                assertEquals(17L, SavedGuestContinuations.savedGuestContinuation(sameFrame).continueWith(17L));
                assertEquals(23L, SavedGuestContinuations.savedGuestContinuation(copied).continueWith(23L));
            } finally { context.leave(); }
        }
    }
    @Test void bytecodeAliasRacesEnterOneSuffixAcrossThreads() throws Exception {
        try (var context = Main.executionContext()) {
            context.initialize("thc");
            try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                for (boolean discard : new boolean[]{false, true}) {
                    context.enter();
                    ContinuationResult token;
                    try { token = rawToken(TruffleLanguage.LanguageReference.create(Language.class).get(null)); }
                    finally { context.leave(); }
                    var start = new java.util.concurrent.CountDownLatch(1);
                    var resumer = executor.submit(() -> {
                        context.enter();
                        try {
                            var alias = SavedGuestContinuations.savedGuestContinuation(token); start.await();
                            try { assertEquals(31L, alias.continueWith(31L)); return true; }
                            catch (RuntimeFault terminal) { return false; }
                        } finally { context.leave(); }
                    });
                    var competitor = executor.submit(() -> {
                        context.enter();
                        try {
                            var alias = SavedGuestContinuations.savedGuestContinuation(token); start.await();
                            if (discard) { alias.discard(); return false; }
                            try { assertEquals(31L, alias.continueWith(31L)); return true; }
                            catch (RuntimeFault terminal) { return false; }
                        } finally { context.leave(); }
                    });
                    start.countDown();
                    int entered = (resumer.get(10, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0) +
                        (competitor.get(10, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0);
                    if (discard) assertTrue(entered <= 1); else assertEquals(1, entered);
                    context.enter();
                    try {
                        var alias = SavedGuestContinuations.savedGuestContinuation(token);
                        assertThrows(RuntimeFault.class, () -> alias.continueWith(31L)); alias.discard();
                    } finally { context.leave(); }
                }
            }
        }
    }
    @Test void bytecodeGuestFailureCannotReopenItsClaim() {
        try (var context = Main.executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var payload = new Object();
                var root = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                    b.beginRoot(); b.beginYield(); b.beginRecordContinuationOwner(); b.emitLoadConstant(Unit.INSTANCE);
                    b.endRecordContinuationOwner(); b.endYield();
                    b.beginReturn(); b.beginRaise(false); b.emitLoadConstant(payload); b.endRaise(); b.endReturn(); b.endRoot();
                }).getNode(0);
                var token = assertInstanceOf(ContinuationResult.class, Calls.target(root.getCallTarget(), new Object[]{0L}));
                var first = SavedGuestContinuations.savedGuestContinuation(token);
                assertSame(payload, assertThrows(GuestException.class, () -> first.continueWith(Unit.INSTANCE)).getPayload());
                var alias = SavedGuestContinuations.savedGuestContinuation(token);
                assertThrows(RuntimeFault.class, () -> alias.continueWith(Unit.INSTANCE)); alias.discard();
            } finally { context.leave(); }
        }
    }
    @Test void bytecodeMissingProvenanceRejectsWithoutAdoption() {
        try (var context = Main.executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                // Raw upstream ABI remains usable; adaptation must not adopt this unstamped token.
                var root = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                    b.beginRoot(); b.beginReturn(); b.beginYield(); b.emitLoadConstant(Unit.INSTANCE);
                    b.endYield(); b.endReturn(); b.endRoot();
                }).getNode(0);
                var token = assertInstanceOf(ContinuationResult.class, Calls.target(root.getCallTarget(), new Object[]{0L}));
                var alias = SavedGuestContinuations.savedGuestContinuation(token);
                assertThrows(RuntimeFault.class, () -> alias.continueWith(41L));
                assertThrows(RuntimeFault.class, alias::discard);
                assertEquals(41L, token.continueWith(41L));
            } finally { context.leave(); }
        }
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void wrongContextRejectionPreservesBytecodeTokenAndOuterOwners(boolean sharedEngine) {
        try (var engine = org.graalvm.polyglot.Engine.newBuilder().allowExperimentalOptions(true)
                .option("engine.Compilation", "false").build();
             var otherEngine = sharedEngine ? null : org.graalvm.polyglot.Engine.newBuilder().allowExperimentalOptions(true)
                .option("engine.Compilation", "false").build();
             var owner = org.graalvm.polyglot.Context.newBuilder("thc").engine(engine).build();
             var stranger = org.graalvm.polyglot.Context.newBuilder("thc").engine(sharedEngine ? engine : otherEngine).build()) {
            owner.initialize("thc"); stranger.initialize("thc");
            var tokens = new ContinuationResult[2]; var boundaries = new Object[2];
            Driver driver;
            owner.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                driver = new Driver(language);
                for (int i = 0; i < tokens.length; i++) {
                    tokens[i] = rawToken(language);
                    if (i == 0) {
                        var thunk = new Thunk(((RootNode) tokens[i].getContinuationRootNode().getSourceRootNode()).getCallTarget(), null);
                        thunk.setTarget(null); thunk.setValue(tokens[i]); thunk.setState(5); boundaries[i] = thunk;
                    } else boundaries[i] = new CallSegment(tokens[i]);
                }
            } finally { owner.leave(); }
            stranger.enter();
            try {
                var rejectingDriver = sharedEngine ? driver : new Driver(TruffleLanguage.LanguageReference.create(Language.class).get(null));
                for (int i = 0; i < tokens.length; i++) {
                    Object boundary = boundaries[i];
                    var alias = SavedGuestContinuations.savedGuestContinuation(tokens[i]);
                    assertThrows(RuntimeFault.class, () -> alias.continueWith(Unit.INSTANCE));
                    assertThrows(RuntimeFault.class, alias::discard);
                    assertThrows(RuntimeFault.class, () -> rejectingDriver.force(boundary));
                    if (boundary instanceof Thunk thunk) {
                        assertEquals(5, thunk.getState()); assertSame(tokens[i], thunk.getValue()); assertNull(thunk.getOwner());
                    } else {
                        var segment = (CallSegment) boundary;
                        assertEquals(5, segment.getState()); assertSame(tokens[i], segment.getValue()); assertNull(segment.getOwner());
                    }
                }
            } finally { stranger.leave(); }
            owner.enter();
            try {
                for (int i = 0; i < tokens.length; i++) {
                    assertSame(Unit.INSTANCE, driver.force(boundaries[i]));
                    var alias = SavedGuestContinuations.savedGuestContinuation(tokens[i]);
                    assertThrows(RuntimeFault.class, () -> alias.continueWith(Unit.INSTANCE));
                }
            } finally { owner.leave(); }
        }
    }

    @Test void wrongContextCannotRouteABytecodeMVarWaitThroughOuterOwners() throws Exception {
        try (var engine = org.graalvm.polyglot.Engine.newBuilder().allowExperimentalOptions(true)
                .option("engine.Compilation", "false").build();
             var owner = org.graalvm.polyglot.Context.newBuilder("thc").engine(engine).build();
             var stranger = org.graalvm.polyglot.Context.newBuilder("thc").engine(engine).build();
             var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            owner.initialize("thc"); stranger.initialize("thc");
            for (int route = 0; route < 5; route++) {
                Driver driver; ContinuationResult token; Object boundary; ManagedMVar.Request request;
                PendingWait pending; GuestThreads threads; var cell = new ManagedMVar();
                owner.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    driver = new Driver(language); driver.getCallTarget();
                    threads = Language.currentState().getThreads();
                    threads.enterCurrent(null, true, true, null); threads.installSuspensionBoundary(new GuestWakePort());
                    request = new ManagedMVar.Request(cell, ManagedMVar.Operation.TAKE, null, driver);
                    pending = assertThrows(PendingWait.class, () -> ManagedMVar.awaitAt(request, driver));
                    var root = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                        b.beginRoot(); b.beginYield(); b.beginRecordContinuationOwner(); b.emitLoadConstant(pending);
                        b.endRecordContinuationOwner(); b.endYield();
                        b.beginResumePendingWait(); b.emitLoadConstant(pending); b.endResumePendingWait();
                        b.beginReturn(); b.emitLoadConstant(73L); b.endReturn(); b.endRoot();
                    }).getNode(0);
                    token = assertInstanceOf(ContinuationResult.class, Calls.target(root.getCallTarget(), new Object[]{0L}));
                    var child = new Thunk(root.getCallTarget(), null);
                    child.setTarget(null); child.setValue(token); child.setState(5);
                    if (route == 0) boundary = child;
                    else if (route == 1) boundary = new CallSegment(token);
                    else if (route == 2) {
                        var source = new LifetimeOwner(language);
                        var frame = Truffle.getRuntime().createMaterializedFrame(new Object[]{0L}, source.getFrameDescriptor());
                        var ast = new AstContinuation(source, new ThunkSuspended(child), MaskingState.UNMASKED,
                            frame, List.of((activation, input) -> ((ChildResume) input).takeValue()), StackAnnotationState.EMPTY);
                        boundary = parked(source.getCallTarget(), ast);
                    } else {
                        var wait = new Force.DriverWait(driver.force, root, new CallSegment(token), pending, false, new Metrics(false));
                        boundary = route == 3 ? wait : parked(root.getCallTarget(), wait);
                    }
                } finally { owner.leave(); }
                try {
                    final Object parked = boundary;
                    var rejected = executor.submit(() -> {
                        stranger.enter();
                        var foreignThreads = Language.currentState().getThreads(); foreignThreads.enterCurrent(null, true, true, null);
                        try {
                            return assertThrows(RuntimeFault.class, () -> {
                                if (parked instanceof SavedGuestContinuation saved) saved.continueWith(Unit.INSTANCE);
                                else driver.force(parked);
                            });
                        } finally { foreignThreads.leaveCurrent(); stranger.leave(); }
                    });
                    try { assertTrue(rejected.get(5, java.util.concurrent.TimeUnit.SECONDS).getMessage().contains("execution context")); }
                    finally { rejected.cancel(true); }
                    assertEquals(ManagedMVar.RequestState.PENDING, request.getState()); assertTrue(request.isQueued());
                    assertFalse(pending.abandoned());
                    if (boundary instanceof Thunk thunk) { assertEquals(5, thunk.getState()); assertNull(thunk.getOwner()); }
                    else if (boundary instanceof CallSegment segment) { assertEquals(5, segment.getState()); assertSame(token, segment.getValue()); assertNull(segment.getOwner()); }
                    owner.enter();
                    try {
                        assertTrue(cell.tryPut(73L));
                        assertEquals(73L, boundary instanceof SavedGuestContinuation saved ? saved.continueWith(Unit.INSTANCE) : driver.force(boundary));
                        assertEquals(ManagedMVar.RequestState.COMMITTED, request.getState()); assertFalse(request.isQueued());
                        var alias = SavedGuestContinuations.savedGuestContinuation(token);
                        assertThrows(RuntimeFault.class, () -> alias.continueWith(Unit.INSTANCE));
                    } finally { owner.leave(); }
                } finally {
                    owner.enter(); try { request.cancel(); threads.leaveCurrent(); } finally { owner.leave(); }
                }
            }
        }
    }

    @Test void rejectedCapturedRequestKeepsPendingStateAndParkedParent() {
        try (var engine = org.graalvm.polyglot.Engine.newBuilder().allowExperimentalOptions(true)
                .option("engine.Compilation", "false").build();
             var owner = org.graalvm.polyglot.Context.newBuilder("thc").engine(engine).build();
             var stranger = org.graalvm.polyglot.Context.newBuilder("thc").engine(engine).build()) {
            owner.initialize("thc"); stranger.initialize("thc");
            Driver driver; CallSegment parent; ContinuationResult token; CapturedAsyncRequest request;
            owner.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                driver = new Driver(language); token = rawToken(language); parent = new CallSegment(token);
                request = new CapturedAsyncRequest(Language.currentState().getCapturedAsyncRequests(), parent,
                    new CallSegment(rawToken(language)), Unit.INSTANCE);
            } finally { owner.leave(); }
            stranger.enter();
            try {
                assertThrows(RuntimeFault.class, () -> driver.deliver(request));
                assertEquals(CapturedRequestState.PENDING, request.getState());
                assertEquals(5, parent.getState()); assertSame(token, parent.getValue()); assertNull(parent.getOwner());
            } finally { stranger.leave(); }
            owner.enter();
            try {
                assertSame(Unit.INSTANCE, driver.force(parent));
                // A valid-context stale-parent failure still settles the pending request.
                assertThrows(RuntimeFault.class, () -> driver.deliver(request));
                assertEquals(CapturedRequestState.FAILED, request.getState());
            } finally { owner.leave(); }
        }
    }

    private static final class Driver extends RootNode {
        @Child private Force force = new Force(new Metrics(false));
        Driver() { super(null); }
        Driver(Language language) { super(language); }
        @Override public Object execute(VirtualFrame frame) { return frame.getArguments()[0] instanceof CallSegment segment ? force.executeInitialization(segment) : force.execute(frame, frame.getArguments()[0]); }
        Object force(Object boundary) { return Calls.target(getCallTarget(), new Object[]{boundary}); }
        Object deliver(CapturedAsyncRequest request) { return force.deliverAtCapturedIOHandler(request); }
    }
    private static final class Saved implements SavedGuestContinuation {
        private final Object savedRoot, savedYield;
        private final Function<Object, Object> resume;
        int resumes, inspections;
        Saved(Object savedRoot, Object savedYield, Function<Object, Object> resume) {
            this.savedRoot = savedRoot; this.savedYield = savedYield; this.resume = resume;
        }
        @Override public Object getSourceRoot() { return savedRoot; }
        @Override public Object getYielded() { inspections++; return savedYield; }
        @Override public Object getIdentity() { return this; }
        @Override public Object continueWith(Object input) { resumes++; return resume.apply(input); }
    }
    private Thunk parked(RootCallTarget target, SavedGuestContinuation saved) {
        var thunk = new Thunk(target, null);
        thunk.setTarget(null); thunk.setEnvironment(null); thunk.setValue(saved); thunk.setState(5);
        return thunk;
    }
    private static final class CompletionProbe extends Expr {
        int compiled;
        CompletionProbe() {
            setRepresentation(new CoreRepresentation(CoreKind.OBJECT, true, true, List.of("BoxedRep (Just Unlifted)"), null, null, null, null, null));
        }
        @Override public Object execute(VirtualFrame frame) {
            if (CompilerDirectives.inCompiledCode()) compiled++;
            return complete(frame.getArguments()[1]);
        }
        Object complete(Object value) { return complete(value, null, null); }
        Object complete(Object value, RootCallTarget target) { return complete(value, target, null); }
        Object complete(Object value, RootCallTarget target, TupleShape shape) { return AstControl.complete(this, value, target, shape); }
    }
    private FunctionRoot root(Language language, CompletionProbe probe, boolean enabled) {
        return new FunctionRoot(language, new FrameLayout().build(), "completion control", null,
            new int[0], new int[0], new int[0], probe, new Metrics(false), new CoreRepresentation[0], probe.getRepresentation(),
            probe.getCoreSourceLocation(), new boolean[0], null, null, new int[0], null, enabled, new int[0][], false,
            FunctionRootRole.FUNCTION, false);
    }
    @Test void ordinaryCompletionKeepsItsFirstInstalledIdentityAndColdProofChecks() throws Exception {
        try (var context = Main.executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var probe = new CompletionProbe(); var root = root(language, probe, true); var target = root.getCallTarget();
                target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                target.getClass().getMethod("waitForCompilation").invoke(target);
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                var marker = new Object();
                assertSame(marker, Calls.target(target, new Object[]{0L, marker}));
                assertEquals(1, probe.compiled, "The first call must enter the original installed guest code");
                assertSame(target, root.getCallTarget());
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                for (var ordinary : Arrays.asList(null, thc.runtime.Unit.INSTANCE, 17L, 23, marker, new Object[]{marker}))
                    assertSame(ordinary, probe.complete(ordinary));
                var ambient = SynchronousMasking.current(probe);
                SynchronousMasking.set(probe, MaskingState.MASKED_INTERRUPTIBLE);
                try {
                    var saved = new Saved(root, thc.runtime.Unit.INSTANCE, input -> fail("Completion must not resume the child"));
                    var cut = assertThrows(AstCapture.class, () -> probe.complete(saved, target));
                    var parked = (CallSegmentSuspended) cut.getYielded();
                    assertSame(saved, parked.getSegment().getValue());
                    assertEquals(5, parked.getSegment().getState());
                    assertEquals(MaskingState.MASKED_INTERRUPTIBLE, parked.getSegment().getCallerMask());
                    assertEquals(MaskingState.MASKED_INTERRUPTIBLE, cut.getLogicalMask());
                    assertEquals(0, saved.resumes);
                    assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(probe));
                    var other = root(language, new CompletionProbe(), true).getCallTarget();
                    assertThrows(RuntimeFault.class, () -> probe.complete(saved, other));
                    assertThrows(RuntimeFault.class, () -> probe.complete(new Saved(new Object(), thc.runtime.Unit.INSTANCE, input -> null)));
                    assertThrows(RuntimeFault.class, () -> probe.complete(new Saved(root, new Object(), input -> null)));
                    var scalar = new CoreRepresentation(CoreKind.LONG, true, true, List.of("IntRep"), null, null, null, null, null);
                    var shape = new TupleShape(new CoreRepresentation(CoreKind.UNKNOWN, true, true, List.of("IntRep"), List.of(scalar), null, null, null, null), language);
                    assertThrows(RuntimeFault.class, () -> probe.complete(saved, target, shape));
                    assertEquals(0, saved.resumes);
                    var disabled = new CompletionProbe(); root(language, disabled, false).getCallTarget();
                    assertSame(saved, disabled.complete(saved, other, shape), "A nonresumable root must preserve its existing completion policy");
                } finally { SynchronousMasking.set(probe, ambient); }
            } finally { context.leave(); }
        }
    }
    @Test void completedDependencyChainsInspectOnlyLinearSavedEdges() {
        try (var context = Main.executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var target = new RootNode(null) {
                    @Override public Object execute(VirtualFrame frame) { return fail("Original body replayed"); }
                }.getCallTarget();
                for (int route = 0; route < 3; route++) for (int length : new int[]{64, 128}) {
                    var saved = new java.util.ArrayList<Saved>();
                    var boundaries = new java.util.ArrayList<Object>();
                    Object boundary = null;
                    for (int index = 0; index < length; index++) {
                        Object signal = boundary instanceof Thunk thunk ? new ThunkSuspended(thunk) :
                            boundary instanceof CallSegment segment ? new CallSegmentSuspended(segment) : Unit.INSTANCE;
                        long answer = index + 1L;
                        var continuation = new Saved(target.getRootNode(), signal, input -> {
                            if (answer == 1L) assertSame(Unit.INSTANCE, input);
                            else {
                                var child = assertInstanceOf(ChildResume.class, input);
                                assertNull(child.getFailure()); assertEquals(answer - 1L, child.takeValue());
                            }
                            return answer;
                        });
                        boundary = route == 0 || route == 2 && index % 2 == 0
                            ? parked(target, continuation) : new CallSegment(continuation);
                        saved.add(continuation); boundaries.add(boundary);
                    }
                    for (var continuation : saved) continuation.inspections = 0;
                    var driver = new Driver();
                    assertEquals((long) length, driver.force(boundary));
                    int inspections = saved.stream().mapToInt(continuation -> continuation.inspections).sum();
                    assertTrue(inspections <= 24 * length,
                        "Whole resume must inspect linearly: " + inspections + " yielded reads for " + length + " boundaries, route " + route);
                    for (int index = 0; index < length; index++) {
                        assertEquals(1, saved.get(index).resumes);
                        assertEquals(2, boundaries.get(index) instanceof Thunk thunk ? thunk.getState() : ((CallSegment) boundaries.get(index)).getState());
                    }
                    assertEquals((long) length, driver.force(boundary));
                    assertEquals(inspections, saved.stream().mapToInt(continuation -> continuation.inspections).sum());
                }
            } finally { context.leave(); }
        }
    }

    @Test void dependencyCycleRejectsBeforeAnyBoundaryChangesOwnership() {
        try (var context = Main.executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var target = new RootNode(null) {
                    @Override public Object execute(VirtualFrame frame) { return fail("Original body replayed"); }
                }.getCallTarget();
                var first = new Thunk(target, null); var second = new Thunk(target, null);
                var firstSaved = new Saved(target.getRootNode(), new ThunkSuspended(second), input -> fail("Cyclic suffix ran"));
                var secondSaved = new Saved(target.getRootNode(), new ThunkSuspended(first), input -> fail("Cyclic suffix ran"));
                first.setValue(firstSaved); first.setState(5); second.setValue(secondSaved); second.setState(5);
                var failure = assertThrows(RuntimeFault.class, () -> new Driver().force(first));
                assertTrue(failure.getMessage().contains("dependency cycle"), failure.getMessage());
                assertEquals(5, first.getState()); assertEquals(5, second.getState());
                assertSame(firstSaved, first.getValue()); assertSame(secondSaved, second.getValue());
                assertNull(first.getOwner()); assertNull(second.getOwner());
                assertEquals(0, firstSaved.resumes); assertEquals(0, secondSaved.resumes);
            } finally { context.leave(); }
        }
    }

    @Test void coldRecordResumesSharedChildBeforeParentAndPublishesOnce() {
        try (var context = Main.executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var original = new RootNode(null) {
                    @Override public Object execute(VirtualFrame frame) { return fail("Original body replayed"); }
                }.getCallTarget();
                var childSaved = new Saved(original.getRootNode(), thc.runtime.Unit.INSTANCE, input -> 42L);
                var child = parked(original, childSaved);
                var parentSaved = new Saved(original.getRootNode(), new ThunkSuspended(child), input -> {
                    var completed = (ChildResume) input;
                    assertNull(completed.getFailure()); assertEquals(42L, completed.getValue()); return 43L;
                });
                var parent = parked(original, parentSaved); var driver = new Driver();
                assertEquals(43L, driver.force(parent));
                assertEquals(2, parent.getState()); assertEquals(2, child.getState());
                assertEquals(1, parentSaved.resumes); assertEquals(1, childSaved.resumes);
                assertEquals(43L, driver.force(parent)); assertEquals(1, parentSaved.resumes);
            } finally { context.leave(); }
        }
    }
}
