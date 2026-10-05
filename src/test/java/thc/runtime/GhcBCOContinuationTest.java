// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

/** Actual MVar interruptions through the shared BCO root and both Core backends. */
@Timeout(60)
class GhcBCOContinuationTest {
    private static List<Object> list(Object... values) { return Arrays.asList(values); }
    private static Map<String, Object> map(Object... fields) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]);
        return result;
    }
    private static final Map<String, Object> VOID = map("kind", "void", "primReps", list(), "evaluated", true);
    private static final Map<String, Object> REF = map("kind", "object", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", false);
    private static final Map<String, Object> CELL = map("kind", "object", "primReps", list("BoxedRep (Just Unlifted)"), "evaluated", true);
    private static final Map<String, Object> PAIR = map("kind", "unknown", "aggregate", "unboxed-tuple",
        "primReps", REF.get("primReps"), "components", list(VOID, REF), "evaluated", false);
    private static Map<String, Object> arg(String id, Map<String, Object> rep) { return map("id", id, "rep", rep, "lifted", rep == REF); }
    private static List<Object> v(String id, Map<String, Object> rep) { return list("var", id, map("rep", rep)); }
    private static List<Object> take(String id) {
        return list("app", list("prim", "takeMVar#"), list(v(id, CELL), list("void", map("rep", VOID))),
            list(false, false), false, false, map("rep", PAIR));
    }
    private static Map<String, Object> waitingModule() {
        var body = list("case", take("cell"), "pair", list(list("data", "Pair", list("s", "value"), v("value", REF),
            map("binders", list(arg("s", VOID), arg("value", REF))))), map("rep", REF, "binder", arg("pair", PAIR)));
        body = list("case", take("prefix"), "before", list(list("default", null, list(), body)),
            map("rep", REF, "binder", arg("before", PAIR)));
        return map("instrument", true, "constructors", list(map("id", "Pair", "name", "Pair", "kind", "unboxed-tuple", "arity", 2)),
            "bindings", list(map("id", "wait", "name", "wait", "lifted", true,
                "expr", list("lam", list(arg("prefix", CELL), arg("cell", CELL)), body, map("resultRep", REF)))));
    }
    private static Context context() { return context(0); }
    private static Context context(int sparkCapacity) { return context(sparkCapacity, null); }
    private static Context context(int sparkCapacity, String hosting) {
        var builder = Context.newBuilder("thc").allowCreateThread(sparkCapacity != 0).allowExperimentalOptions(true)
            .option("thc.SparkQueueCapacity", Integer.toString(sparkCapacity)).option("engine.WarnInterpreterOnly", "false")
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false");
        if (hosting != null) builder.option("thc.ThreadHosting", hosting);
        return builder.build();
    }
    private static byte[] words(long... words) {
        byte[] result = new byte[words.length * 8];
        for (int i = 0; i < words.length; i++) ManagedByteArray.writeInt(result, i, words[i]);
        return result;
    }
    private static Closure bco(Language language, int arity, long bits, Object[] pointers, int... instructions) {
        byte[] code = new byte[instructions.length * 2];
        for (int i = 0; i < instructions.length; i++) ManagedByteArray.writeInt16(code, i, instructions[i]);
        return GhcBCO.create(new Node() {}, language, new Metrics(false), code, words(0, 1), pointers,
            arity, arity == 0 ? words(0) : words(arity, bits), Unit.INSTANCE);
    }
    private static Closure caseBco(Language language, int arity, long[] bitmap, long[] literals, Object[] pointers, int... instructions) {
        byte[] code = new byte[instructions.length * 2];
        for (int i = 0; i < instructions.length; i++) ManagedByteArray.writeInt16(code, i, instructions[i]);
        return GhcBCO.create(new Node() {}, language, new Metrics(false), code, words(literals), pointers, arity, words(bitmap), Unit.INSTANCE);
    }
    private static SavedGuestContinuation saved(Object value) {
        return Objects.requireNonNull(SavedGuestContinuations.savedGuestContinuation(
            value instanceof TailYield tail ? tail.getContinuation() : value instanceof AstTailYield tail ? tail.getContinuation() : value));
    }
    private static SavedGuestContinuation interrupt(Context context, Language.State owner, ManagedMVar blocked,
            java.util.concurrent.Callable<Object> action) throws Exception {
        var result = new CompletableFuture<SavedGuestContinuation>();
        var identity = new AtomicReference<GuestThreadId>();
        var worker = new Thread(() -> {
            context.enter(); owner.getThreads().enterCurrent(null, false, true, null);
            try {
                identity.set(owner.getThreads().currentIdentity());
                SynchronousMasking.set(null, MaskingState.MASKED_INTERRUPTIBLE);
                var cut = saved(action.call());
                assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(null));
                assertSame(StackAnnotationState.EMPTY, StackAnnotations.current(null));
                cut.asyncRequest().acknowledge(); result.complete(cut);
            } catch (Throwable failure) { result.completeExceptionally(failure); }
            finally { owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); context.leave(); }
        }, "bco-interrupt");
        worker.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (blocked.pendingCounts().getTakers() != 1 && !result.isDone() && System.nanoTime() < deadline) Thread.sleep(1);
            if (result.isCompletedExceptionally()) result.get(1, TimeUnit.SECONDS);
            assertEquals(1, blocked.pendingCounts().getTakers());
            owner.getThreads().send(Objects.requireNonNull(identity.get()), "cut");
            return result.get(10, TimeUnit.SECONDS);
        } finally { if (!result.isDone()) context.close(true); worker.join(5000); assertFalse(worker.isAlive()); }
    }
    private static Thunk updatingApplication(Language language, String backend, ManagedMVar prefix, ManagedMVar cell) {
        ExecutableProgram program = backend.equals("ast") ? new Program(language, waitingModule(), false)
            : new BytecodeProgram(language, waitingModule(), false);
        var waiting = ((Closure) program.entryValue("wait")).pap(new Object[]{prefix});
        // Native GHC ALLOC_AP/PUSH_G/MKAP/RETURN_P capture both the PAP and its argument.
        var body = bco(language, 2, 0, new Object[0], 2,1,31,2,2,38,3,2,58);
        var allocator = bco(language, 0, 0, new Object[]{waiting, cell, body},
            39,2,11,1,11,0,11,2,42,3,2,60);
        return (Thunk) allocator.target.call(0L);
    }
    private static void awaitSpeculativeWait(ManagedMVar cell) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (cell.pendingCounts().getTakers() != 1 && System.nanoTime() < deadline) Thread.sleep(1);
        assertEquals(1, cell.pendingCounts().getTakers(), "Updating BCO starts its real work before demand");
    }
    private static <T> T admitted(GuestThreads threads, java.util.concurrent.Callable<T> action) throws Exception {
        if (threads.needsHosting()) return threads.hostEntry(null, () -> admitted(threads, action));
        threads.enterCurrent(null, false, true, null);
        try { return action.call(); }
        finally { threads.leaveCurrent(); }
    }
    @ParameterizedTest @CsvSource({"ast,platform", "bytecode,platform", "ast,loom", "bytecode,loom"})
    void sparkedUpdatingApplicationsShareWorkAndResumeCancelledWorkersWithoutReplay(String backend, String hosting) throws Exception {
        for (boolean cancel : new boolean[]{false, true}) try (var context = context(1, hosting)) {
            context.initialize("thc"); context.enter();
            var cell = new ManagedMVar(); var answer = new Object();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var owner = Language.currentState(); var threads = owner.getThreads();
                var prefix = new ManagedMVar(); var once = new Object(); assertTrue(prefix.tryPut(once));
                var thunk = updatingApplication(language, backend, prefix, cell);
                var force = new RootNode(language) {
                    @Child private Force force = new Force(new Metrics(false), true);
                    @Override public Object execute(VirtualFrame frame) { return force.execute(frame, frame.getArguments()[0]); }
                }.getCallTarget();
                owner.getSparks().hint(new Node() {}, thunk);
                awaitSpeculativeWait(cell); assertTrue(prefix.isEmpty(), "The prefix effect completed speculatively");
                if (cancel) {
                    var worker = thunk.getOwner(); assertNotNull(worker);
                    assertEquals(threads.isLoom(), worker.isVirtual());
                    assertEquals(hosting.equals("loom"), worker.isVirtual(), "BCO spark worker uses the requested hosting");
                    var request = admitted(threads, () -> {
                        for (var candidate : threads.snapshot())
                            if (candidate instanceof GuestThreadId id && id.getCarrier().get() == worker)
                                return threads.send(id, "stop speculative BCO worker");
                        throw new AssertionError("BCO spark worker has no registered guest identity");
                    });
                    worker.join(5000); assertFalse(worker.isAlive(), "Cooperative BCO worker cancellation completes");
                    assertEquals(AsyncRequestState.ACKNOWLEDGED, request.getState());
                }
                // A replay would consume this marker; resumption must retain it while consuming only cell.
                var replayTrap = new Object(); assertTrue(prefix.tryPut(replayTrap)); assertTrue(cell.tryPut(answer));
                assertSame(answer, admitted(threads, () -> force.call(thunk)));
                assertSame(answer, admitted(threads, () -> force.call(thunk)), "Demand shares the original BCO update");
                assertTrue(cell.isEmpty()); assertSame(replayTrap, prefix.tryTake().getValue(), "The prefix effect must not replay");
                ThreadInventoryCoreEvidence.released(language);
            } finally { cell.tryPut(answer); context.leave(); }
        }
    }
    @ParameterizedTest @CsvSource({"ast,false", "bytecode,false", "ast,true", "bytecode,true"})
    void capturedApplicationsKeepRepeatedCutsUpdatesAndPendingApply(String backend, boolean updating) throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            Language language; Language.State owner; Closure code, second; RootCallTarget resume; Object application;
            var prefix1 = new ManagedMVar(); var prefix2 = new ManagedMVar();
            var cell1 = new ManagedMVar(); var cell2 = new ManagedMVar();
            assertTrue(prefix1.tryPut("once")); assertTrue(prefix2.tryPut("once"));
            try {
                language = TruffleLanguage.LanguageReference.create(Language.class).get(null); owner = Language.currentState();
                ExecutableProgram program = backend.equals("ast") ? new Program(language, waitingModule(), true) : new BytecodeProgram(language, waitingModule(), true);
                var waiting = (Closure) program.entryValue("wait");
                var first = waiting.pap(new Object[]{prefix1}); second = waiting.pap(new Object[]{prefix2});
                var body = bco(language, 2, 0, new Object[0], 2,1,31,2,2,38,3,2,58);
                var allocator = updating
                    ? bco(language, 0, 0, new Object[]{first, cell1, body}, 39,2,11,1,11,0,11,2,42,3,2,60)
                    : bco(language, 0, 0, new Object[]{first, body}, 41,1,1,11,0,11,1,43,2,1,60);
                application = allocator.target.call(0L);
                code = updating
                    ? bco(language, 0, 0, new Object[]{application, cell2}, 11,1,31,11,0,58)
                    : bco(language, 0, 0, new Object[]{application, cell1, cell2}, 11,2,11,1,32,11,0,58);
                resume = new RootNode(language) {
                    @Child private Force force = new Force(new Metrics(false), true);
                    @Override public Object execute(VirtualFrame frame) { return force.drainStack((SavedGuestContinuation) frame.getArguments()[0]); }
                }.getCallTarget();
            } finally { context.leave(); }
            var cut = interrupt(context, owner, cell1, () -> code.target.call(0L));
            assertTrue(prefix1.isEmpty()); assertFalse(prefix2.isEmpty());
            if (updating) assertEquals(5, ((Thunk) application).getState());
            try (var foreign = context()) {
                foreign.initialize("thc"); foreign.enter();
                try { assertThrows(RuntimeFault.class, () -> cut.continueWith(Unit.INSTANCE)); }
                finally { foreign.leave(); }
            }
            var again = interrupt(context, owner, cell1, () -> resume.call(cut));
            assertNotSame(cut.asyncRequest(), again.asyncRequest());
            assertTrue(prefix1.isEmpty()); assertFalse(prefix2.isEmpty());
            assertTrue(cell1.tryPut(second));
            var outer = interrupt(context, owner, cell2, () -> resume.call(again));
            assertTrue(prefix2.isEmpty()); assertTrue(cell1.isEmpty());
            var answer = new Object(); assertTrue(cell2.tryPut(answer));
            context.enter(); try {
                assertSame(answer, resume.call(outer));
                assertThrows(RuntimeFault.class, () -> outer.continueWith(Unit.INSTANCE));
                assertTrue(cell2.isEmpty());
                if (updating) {
                    var thunk = (Thunk) application;
                    assertEquals(2, thunk.getState()); assertNull(thunk.getTarget()); assertNull(thunk.getEnvironment());
                    assertSame(second, thunk.getValue());
                    var forceAgain = bco(language, 0, 0, new Object[]{thunk}, 11,0,58);
                    assertSame(second, forceAgain.target.call(0L));
                }
                ThreadInventoryCoreEvidence.released(language);
            } finally { context.leave(); }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void overapplicationKeepsBothArgumentsAndRepeatedChildCuts(String backend) throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            Language language; Language.State owner; Closure code, first, second; RootCallTarget resume;
            var prefix1 = new ManagedMVar(); var prefix2 = new ManagedMVar(); var prefix3 = new ManagedMVar(); var prefix4 = new ManagedMVar();
            var cell1 = new ManagedMVar(); var cell2 = new ManagedMVar(); var cell3 = new ManagedMVar(); var cell4 = new ManagedMVar();
            Thunk returned; Closure lastFunction;
            assertTrue(prefix1.tryPut("once")); assertTrue(prefix2.tryPut("once")); assertTrue(prefix3.tryPut("once")); assertTrue(prefix4.tryPut("once"));
            try {
                language = TruffleLanguage.LanguageReference.create(Language.class).get(null); owner = Language.currentState();
                ExecutableProgram program = backend.equals("ast") ? new Program(language, waitingModule(), true) : new BytecodeProgram(language, waitingModule(), true);
                var function = (Closure) program.entryValue("wait"); first = function.pap(new Object[]{prefix1}); second = function.pap(new Object[]{prefix2});
                // A second Apply frame remains under the overapplied call and its forced result.
                code = bco(language, 0, 0, new Object[]{first, cell1, cell2, cell4}, 11, 3, 31, 11, 2, 11, 1, 32, 11, 0, 58);
                var lastCode = bco(language, 0, 0, new Object[]{function.pap(new Object[]{prefix3}), cell3}, 11, 1, 31, 11, 0, 58);
                returned = GhcBCO.updating(new Node() {}, lastCode);
                lastFunction = function.pap(new Object[]{prefix4});
                resume = new RootNode(language) {
                    @Child private Force force = new Force(new Metrics(false), true);
                    @Override public Object execute(VirtualFrame frame) { return force.drainStack((SavedGuestContinuation) frame.getArguments()[0]); }
                }.getCallTarget();
            } finally { context.leave(); }
            var cut = interrupt(context, owner, cell1, () -> code.target.call(0L));
            assertTrue(prefix1.isEmpty());
            // Interrupt the same child again, before supplying its result. Its prefix must not replay.
            var again = interrupt(context, owner, cell1, () -> resume.call(cut));
            assertTrue(cell1.tryPut(second));
            var last = interrupt(context, owner, cell2, () -> resume.call(again));
            assertTrue(prefix2.isEmpty());
            assertTrue(cell2.tryPut(returned));
            var forced = interrupt(context, owner, cell3, () -> resume.call(last));
            assertTrue(prefix3.isEmpty()); assertEquals(5, returned.getState());
            assertTrue(cell3.tryPut(lastFunction));
            var outer = interrupt(context, owner, cell4, () -> resume.call(forced));
            assertTrue(prefix4.isEmpty());
            var answer = new Object(); assertTrue(cell4.tryPut(answer));
            context.enter(); try {
                assertSame(answer, resume.call(outer));
                assertTrue(cell1.isEmpty()); assertTrue(cell2.isEmpty()); assertTrue(cell3.isEmpty()); assertTrue(cell4.isEmpty());
                assertEquals(2, returned.getState()); assertNull(returned.getTarget());
                ThreadInventoryCoreEvidence.released(language);
            } finally { context.leave(); }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void constructionResumesItsOperandWithoutRepeatingEarlierEffects(String backend) throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            Language language; Language.State owner; RootCallTarget target, resume;
            var prefix = new ManagedMVar(); var cell = new ManagedMVar(); assertTrue(prefix.tryPut("once"));
            var marker = new Object();
            try {
                language = TruffleLanguage.LanguageReference.create(Language.class).get(null); owner = Language.currentState();
                var module = waitingModule();
                var binding = (Map<String, Object>) ((List<?>) module.get("bindings")).getFirst();
                var lambda = (List<Object>) binding.get("expr");
                var number = map("kind", "long", "primReps", list("IntRep"), "evaluated", true);
                var arguments = list(lambda.get(2), v("literals", CELL), v("pointers", CELL),
                    list("lit", "int", "0", map("rep", number)), v("bitmap", CELL), list("void", map("rep", VOID)));
                var create = list("app", list("prim", "newBCO#"), arguments, list(false, false, false, false, false, false),
                    false, false, map("rep", PAIR));
                lambda.set(1, list(arg("prefix", CELL), arg("cell", CELL), arg("literals", CELL), arg("pointers", CELL), arg("bitmap", CELL)));
                lambda.set(2, list("case", create, "created", list(list("data", "Pair", list("s", "value"), v("value", REF),
                    map("binders", list(arg("s", VOID), arg("value", REF))))), map("rep", REF, "binder", arg("created", PAIR))));
                ExecutableProgram program = backend.equals("ast") ? new Program(language, module, true) : new BytecodeProgram(language, module, true);
                target = program.entryTarget("wait");
                resume = new RootNode(language) {
                    @Child private Force force = new Force(new Metrics(false), true);
                    @Override public Object execute(VirtualFrame frame) { return force.drainStack((SavedGuestContinuation) frame.getArguments()[0]); }
                }.getCallTarget();
            } finally { context.leave(); }
            var cut = interrupt(context, owner, cell, () -> target.call(0L, prefix, cell, words(), new Object[]{marker}, words(0)));
            assertTrue(prefix.isEmpty());
            byte[] instructions = new byte[6];
            ManagedByteArray.writeInt16(instructions, 0, 11); ManagedByteArray.writeInt16(instructions, 1, 0); ManagedByteArray.writeInt16(instructions, 2, 58);
            assertTrue(cell.tryPut(instructions));
            context.enter(); try {
                SynchronousMasking.set(null, MaskingState.MASKED_INTERRUPTIBLE);
                var created = assertInstanceOf(Closure.class, resume.call(cut));
                assertSame(marker, created.target.call(0L)); assertTrue(cell.isEmpty());
                ThreadInventoryCoreEvidence.released(language);
            } finally { context.leave(); }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void initialThunkForceAndFinalTailTargetKeepTheBcoSuffix(String backend) throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            Language language; Language.State owner; Closure code; RootCallTarget resume; Thunk thunk;
            var prefix = new ManagedMVar(); var cell = new ManagedMVar(); assertTrue(prefix.tryPut("once"));
            try {
                language = TruffleLanguage.LanguageReference.create(Language.class).get(null); owner = Language.currentState();
                ExecutableProgram program = backend.equals("ast") ? new Program(language, waitingModule(), true) : new BytecodeProgram(language, waitingModule(), true);
                var target = program.entryTarget("wait");
                var tail = new GuestRoot(language, FrameDescriptor.newBuilder().build()) {
                    @Override public long bloom(VirtualFrame frame) { return 0L; }
                    @Override public Object execute(VirtualFrame frame) { throw new TailCall(target, new Object[]{0L, prefix, cell}); }
                };
                var initial = bco(language, 0, 0, new Object[]{new Closure(null, 1, tail.getCallTarget()), "ignored"}, 11, 1, 31, 11, 0, 58);
                thunk = GhcBCO.updating(new Node() {}, initial);
                code = bco(language, 0, 0, new Object[]{thunk}, 11, 0, 58);
                resume = new RootNode(language) {
                    @Child private Force force = new Force(new Metrics(false), true);
                    @Override public Object execute(VirtualFrame frame) { return force.drainStack((SavedGuestContinuation) frame.getArguments()[0]); }
                }.getCallTarget();
            } finally { context.leave(); }
            var cut = interrupt(context, owner, cell, () -> code.target.call(0L));
            assertEquals(5, thunk.getState()); assertTrue(prefix.isEmpty());
            try (var foreign = context()) {
                foreign.initialize("thc"); foreign.enter();
                try { assertThrows(RuntimeFault.class, () -> cut.continueWith(Unit.INSTANCE)); }
                finally { foreign.leave(); }
            }
            var answer = new Object(); assertTrue(cell.tryPut(answer));
            context.enter(); try {
                assertSame(answer, resume.call(cut)); assertSame(answer, code.target.call(0L));
                assertThrows(RuntimeFault.class, () -> cut.continueWith(Unit.INSTANCE));
                assertEquals(2, thunk.getState()); assertNull(thunk.getTarget()); assertNull(thunk.getEnvironment());
                ThreadInventoryCoreEvidence.released(language);
            } finally { context.leave(); }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void strictArgumentSuspensionKeepsTheUnenteredDirectAndMegamorphicCall(String backend) throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            Language language; Language.State owner; Closure waiting, code; RootCallTarget resume;
            var pointers = new Object[2]; var entered = new int[4]; var targets = new RootCallTarget[4];
            try {
                language = TruffleLanguage.LanguageReference.create(Language.class).get(null); owner = Language.currentState();
                ExecutableProgram program = backend.equals("ast") ? new Program(language, waitingModule(), true) : new BytecodeProgram(language, waitingModule(), true);
                waiting = (Closure) program.entryValue("wait");
                for (int i = 0; i < 4; i++) {
                    int index = i;
                    var identity = new GuestRoot(language, FrameDescriptor.newBuilder().build()) {
                        @Override public long bloom(VirtualFrame frame) { return 0L; }
                        @Override public Object execute(VirtualFrame frame) { entered[index]++; return frame.getArguments()[1]; }
                    };
                    identity.configureEntry(new boolean[]{true}, false); targets[i] = identity.getCallTarget();
                }
                code = bco(language, 0, 0, pointers, 11, 1, 31, 11, 0, 58);
                resume = new RootNode(language) {
                    @Child private Force force = new Force(new Metrics(false), true);
                    @Override public Object execute(VirtualFrame frame) { return force.drainStack((SavedGuestContinuation) frame.getArguments()[0]); }
                }.getCallTarget();
            } finally { context.leave(); }
            for (int i = 0; i < 4; i++) {
                pointers[0] = new Closure(null, 1, targets[i]);
                var answer = new Object();
                if (i == 1 || i == 2) {
                    pointers[1] = answer;
                    context.enter(); try { assertSame(answer, code.target.call(0L)); } finally { context.leave(); }
                } else {
                    var prefix = new ManagedMVar(); var blocked = new ManagedMVar(); assertTrue(prefix.tryPut("once"));
                    context.enter(); try {
                        var argumentCode = bco(language, 0, 0, new Object[]{waiting.pap(new Object[]{prefix}), blocked}, 11, 1, 31, 11, 0, 58);
                        pointers[1] = GhcBCO.updating(new Node() {}, argumentCode);
                    } finally { context.leave(); }
                    var cut = interrupt(context, owner, blocked, () -> code.target.call(0L));
                    assertEquals(0, entered[i], "callee entry must wait for the strict argument");
                    assertTrue(prefix.isEmpty()); assertTrue(blocked.tryPut(answer));
                    context.enter(); try { assertSame(answer, resume.call(cut)); ThreadInventoryCoreEvidence.released(language); }
                    finally { context.leave(); }
                }
                assertEquals(1, entered[i], "the callee executes exactly once after argument completion");
            }
        }
    }
    @ParameterizedTest @CsvSource({"ast,false", "bytecode,false", "ast,true", "bytecode,true"})
    void instructionLoopHasARealInterruptibleGuestCut(String backend, boolean packed) throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            Language.State owner; Closure code; RootCallTarget target, resume;
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); owner = Language.currentState();
                // No calls or allocation instructions can provide a guest poll.
                code = packed
                    // The countdown runs above an unaligned live byte. Completion widens that exact byte.
                    ? caseBco(language, 1, new long[]{1,1}, new long[]{0,-1,165,2_000_000}, new Object[0],
                        22,2,25,3,1,47,0,10,55,16,25,1,1,90,55,5,21,20,19,8,15,38,1,3,61)
                    : bco(language, 1, 1, new Object[0], 47, 0, 4, 61, 25, 1, 1, 2, 1, 91, 38, 1, 1, 55, 0);
                var number = map("kind", "long", "primReps", list("IntRep"), "evaluated", true);
                var closure = map("kind", "closure", "primReps", REF.get("primReps"), "evaluated", true);
                var call = list("app", v("code", closure), list(v("n", number)), list(false), false, false, map("rep", number));
                var module = map("bindings", list(map("id", "run", "name", "run", "lifted", true,
                    "expr", list("lam", list(map("id", "code", "rep", closure, "lifted", true), arg("n", number)), call, map("resultRep", number)))));
                ExecutableProgram program = backend.equals("ast") ? new Program(language, module, true) : new BytecodeProgram(language, module, true);
                target = program.entryTarget("run");
                resume = new RootNode(language) {
                    @Child private Force force = new Force(new Metrics(false), true);
                    @Override public Object execute(VirtualFrame frame) { return force.drainStack((SavedGuestContinuation) frame.getArguments()[0]); }
                }.getCallTarget();
            } finally { context.leave(); }
            var result = new CompletableFuture<SavedGuestContinuation>();
            var identity = new AtomicReference<GuestThreadId>();
            var worker = new Thread(() -> {
                context.enter(); owner.getThreads().enterCurrent(null, false, true, null);
                try {
                    identity.set(owner.getThreads().currentIdentity());
                    var cut = saved(target.call(0L, code, 2_000_000L));
                    cut.asyncRequest().acknowledge(); result.complete(cut);
                } catch (Throwable failure) { result.completeExceptionally(failure); }
                finally { owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); context.leave(); }
            }, "bco-loop");
            worker.start();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                boolean inLoop = false;
                while (!inLoop && !result.isDone() && System.nanoTime() < deadline) {
                    for (var frame : worker.getStackTrace()) if (frame.getClassName().equals(GhcBCORoot.class.getName())) inLoop = true;
                    if (!inLoop) Thread.sleep(1);
                }
                assertTrue(inLoop, "interrupt an executing BCO, not an entry queued before invocation");
                owner.getThreads().send(Objects.requireNonNull(identity.get()), "stop");
                var cut = result.get(10, TimeUnit.SECONDS);
                assertNotNull(cut.asyncRequest());
                worker.join(5000); assertFalse(worker.isAlive());
                context.enter(); try {
                    assertEquals(packed ? 165L : 0L, resume.call(cut), "the saved instruction stream and byte-addressed operand stack complete normally");
                } finally { context.leave(); }
            } finally { if (!result.isDone()) context.close(true); worker.join(5000); assertFalse(worker.isAlive()); }
        }
    }
    @ParameterizedTest @CsvSource({"ast,false,false", "bytecode,false,false", "ast,true,false", "bytecode,true,false", "ast,true,true", "bytecode,true,true"})
    void caseFramesKeepTheirStackAcrossRepeatedCutsAndAnOuterApply(String backend, boolean tuple, boolean overapply) throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            Language language; Language.State owner; Closure code; RootCallTarget resume; Thunk shared;
            var prefixes = new ManagedMVar[]{new ManagedMVar(), new ManagedMVar(), new ManagedMVar()};
            var cells = new ManagedMVar[]{new ManagedMVar(), new ManagedMVar(), new ManagedMVar()};
            for (var prefix : prefixes) assertTrue(prefix.tryPut("once"));
            Closure last;
            try {
                language = TruffleLanguage.LanguageReference.create(Language.class).get(null); owner = Language.currentState();
                ExecutableProgram program = backend.equals("ast") ? new Program(language, waitingModule(), true) : new BytecodeProgram(language, waitingModule(), true);
                Closure wait = (Closure) program.entryValue("wait");
                Closure first = wait.pap(new Object[]{prefixes[0]}), second = wait.pap(new Object[]{prefixes[1]});
                last = wait.pap(new Object[]{prefixes[2]});
                Closure initial;
                if (tuple) {
                    var layout = caseBco(language, 0, new long[]{3,5}, new long[0], new Object[0], 38,0,1,69);
                    var cont = caseBco(language, 0, new long[]{2,1}, new long[0], new Object[]{second,cells[1]},
                        38,0,3,38,2,4,38,1,1,38,0,1,11,1,31,11,0,58);
                    var pack = caseBco(language, 0, new long[]{1,0}, new long[]{3,7}, new Object[]{layout},
                        38,0,1,38,1,2,38,1,1,25,1,1,2,1,38,2,1,25,0,1,11,0,69);
                    var worker = caseBco(language, 1, new long[]{1,0}, new long[0], new Object[]{pack,first},
                        13,0,2,2,31,11,1,58);
                    if (overapply) worker = caseBco(language, 1, new long[]{1,0}, new long[0], new Object[]{worker}, 38,0,1,11,0,60);
                    initial = caseBco(language, 0, new long[]{0}, new long[]{3}, new Object[]{cont,layout,cells[0],worker,cells[2]},
                        overapply ? new int[]{11,4,31,70,0,0,1,11,2,11,2,32,11,3,58} : new int[]{11,4,31,70,0,0,1,11,2,31,11,3,58});
                } else {
                    var cont = caseBco(language, 0, new long[]{0}, new long[0], new Object[]{second,cells[1]},
                        38,0,1,38,1,2,38,0,1,11,1,31,11,0,58);
                    initial = caseBco(language, 0, new long[]{0}, new long[0], new Object[]{cont,first,cells[0],cells[2]},
                        11,3,31,13,0,11,2,31,11,1,58);
                }
                shared = GhcBCO.updating(new Node() {}, initial);
                code = bco(language, 0, 0, new Object[]{shared}, 11,0,58);
                resume = new RootNode(language) {
                    @Child private Force force = new Force(new Metrics(false), true);
                    @Override public Object execute(VirtualFrame frame) { return force.drainStack((SavedGuestContinuation) frame.getArguments()[0]); }
                }.getCallTarget();
            } finally { context.leave(); }
            var cut = interrupt(context, owner, cells[0], () -> code.target.call(0L));
            assertTrue(prefixes[0].isEmpty()); assertFalse(prefixes[1].isEmpty()); assertEquals(5, shared.getState());
            try (var foreign = context()) {
                foreign.initialize("thc"); foreign.enter();
                try { assertThrows(RuntimeFault.class, () -> cut.continueWith(Unit.INSTANCE)); }
                finally { foreign.leave(); }
            }
            var again = interrupt(context, owner, cells[0], () -> resume.call(cut));
            assertNotSame(cut.asyncRequest(), again.asyncRequest());
            assertTrue(cells[0].tryPut(new Object()));
            var selected = interrupt(context, owner, cells[1], () -> resume.call(again));
            assertTrue(prefixes[1].isEmpty()); assertFalse(prefixes[2].isEmpty());
            var selectedAgain = interrupt(context, owner, cells[1], () -> resume.call(selected));
            assertNotSame(selected.asyncRequest(), selectedAgain.asyncRequest());
            assertTrue(cells[1].tryPut(last));
            var applied = interrupt(context, owner, cells[2], () -> resume.call(selectedAgain));
            assertTrue(prefixes[2].isEmpty());
            var answer = new Object(); assertTrue(cells[2].tryPut(answer));
            context.enter(); try {
                assertSame(answer, resume.call(applied)); assertSame(answer, code.target.call(0L));
                assertThrows(RuntimeFault.class, () -> cut.continueWith(Unit.INSTANCE));
                assertEquals(2, shared.getState()); assertNull(shared.getTarget()); assertNull(shared.getEnvironment());
                for (var cell : cells) assertTrue(cell.isEmpty());
                for (var prefix : prefixes) assertTrue(prefix.isEmpty());
                ThreadInventoryCoreEvidence.released(language);
            } finally { context.leave(); }
        }
    }
    @Test void nestedBcoCallsSpillAndCompleteTheirPendingApplications() {
        try (var context = context()) {
            context.initialize("thc"); context.enter(); try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                Closure function = bco(language, 1, 0, new Object[0], 58);
                for (int i = 0; i < 256; i++) function = bco(language, 1, 0, new Object[]{function}, 2, 0, 38, 1, 1, 31, 11, 0, 58);
                var answer = new Object(); assertSame(answer, function.target.call(0L, answer));
                assertTrue(AstStacks.astStackScope(null).getSpills() > 0, "actual BCO activations must be bounded");
                assertEquals(0, AstStacks.astStackScope(null).getDepth());
                ThreadInventoryCoreEvidence.released(language);
            } finally { context.leave(); }
        }
    }
}
