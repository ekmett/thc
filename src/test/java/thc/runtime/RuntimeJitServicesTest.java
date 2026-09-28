// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import com.oracle.truffle.runtime.OptimizedTruffleRuntime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.ThrowingSupplier;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class RuntimeJitServicesTest {
    private static Engine engine() { return engine("Throw"); }
    private static Engine engine(String failureAction) {
        return Engine.newBuilder().allowExperimentalOptions(true)
            .option("compiler.Inlining", "false").option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false").option("engine.SingleTierCompilationThreshold", "10000000")
            .option("engine.CompilationFailureAction", failureAction).build();
    }

    private static Context context(Engine engine) { return Context.newBuilder("thc").engine(engine).build(); }

    private static <T> T entered(Context context, ThrowingSupplier<T> action) throws Throwable {
        context.initialize("thc");
        context.enter();
        try { return action.get(); } finally { context.leave(); }
    }

    private static Language language() { return TruffleLanguage.LanguageReference.create(Language.class).get(null); }

    private static Map<String, Object> module() {
        var integer = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
        var closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
        return Map.of("instrument", true, "bindings", List.of(Map.of("id", "identity", "name", "identity",
            "lifted", true, "expr", List.of("lam", List.of(Map.of("id", "n", "lifted", false, "rep", integer)),
                List.of("var", "n", Map.of("rep", integer)), Map.of("rep", closure, "resultRep", integer)))));
    }
    private static List<ExecutableProgram> programs(Language language) {
        return List.of(new Program(language, module(), false, false), new BytecodeProgram(language, module(), false));
    }

    private static Object call(ExecutableProgram program, long value) {
        return Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue("identity"), new Object[]{value}});
    }

    private static long count(RuntimeJitServices service, int selector) { return service.query(selector, 0, 0); }

    private static final class OneIteration extends com.oracle.truffle.api.nodes.Node implements com.oracle.truffle.api.nodes.RepeatingNode {
        int effects;
        @Override public boolean executeRepeating(VirtualFrame frame) { effects++; return false; }
    }

    private static final class OsrOwnerRoot extends ContextRoot {
        @Child com.oracle.truffle.runtime.OptimizedOSRLoopNode loop;
        OsrOwnerRoot(Language language) {
            super(language, null);
            loop = (com.oracle.truffle.runtime.OptimizedOSRLoopNode) Truffle.getRuntime().createLoopNode(new OneIteration());
        }
        @Override public Object execute(VirtualFrame frame) { loop.execute(frame); return 47L; }
    }

    @Test void actualOsrEventsBelongToTheOriginalRootsContext() throws Throwable {
        try (var engine = engine(); var first = context(engine); var second = context(engine)) {
            var firstState = entered(first, Language::currentState);
            var secondState = entered(second, Language::currentState);
            try (var firstService = new RuntimeJitServices(firstState); var secondService = new RuntimeJitServices(secondState)) {
                assertEquals(0L, firstService.control(400, 1)); assertEquals(0L, secondService.control(400, 1));
                entered(first, () -> {
                    var root = new OsrOwnerRoot(language());
                    root.getCallTarget();
                    root.loop.forceOSR();
                    var target = root.loop.getCompiledOSRLoop();
                    assertNotNull(target); assertTrue(target.isValidLastTier());
                    assertSame(root, ((com.oracle.truffle.runtime.BaseOSRRootNode) target.getRootNode()).getSourceRootNode());
                    assertEquals(0, ((OneIteration) root.loop.getRepeatingNode()).effects);
                    assertEquals(1L, count(firstService, 401)); assertEquals(1L, count(firstService, 402));
                    assertTrue(target.invalidate("OSR telemetry control"));
                    assertEquals(1L, count(firstService, 404));
                    for (int selector = 401; selector <= 406; selector++) assertEquals(0L, count(secondService, selector));
                    return null;
                });
            }
        }
    }

    @Test void generatedCloneAndSourceReplayKeepOwnershipWithoutEnteringAContext() throws Throwable {
        try (var engine = engine(); var context = context(engine)) {
            var state = entered(context, Language::currentState);
            var sourceReplays = new java.util.concurrent.atomic.AtomicInteger();
            var root = entered(context, () -> BytecodeRootGen.create(language(), com.oracle.truffle.api.bytecode.BytecodeConfig.DEFAULT, b -> {
                if (b.isParsingSources()) {
                    sourceReplays.incrementAndGet();
                    b.beginSource(com.oracle.truffle.api.source.Source.newBuilder("thc", "47", "owner-control").build());
                    b.beginSourceSection(0, 2);
                }
                b.beginRoot(); b.beginReturn(); b.emitLoadConstant(47L); b.endReturn(); b.endRoot();
                if (b.isParsingSources()) { b.endSourceSection(); b.endSource(); }
            }).getNode(0));
            try (var service = new RuntimeJitServices(state); var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                assertEquals(0L, service.control(400, 1));
                var clone = worker.submit(() -> {
                    var method = root.getClass().getDeclaredMethod("cloneUninitialized");
                    method.setAccessible(true);
                    var copied = (BytecodeRoot) method.invoke(root);
                    root.getBytecodeNode().ensureSourceInformation();
                    return copied;
                }).get(30, java.util.concurrent.TimeUnit.SECONDS);
                assertNotSame(root, clone); assertSame(root.compilationOwner(), clone.compilationOwner());
                assertEquals(1, sourceReplays.get());
                com.oracle.truffle.api.CallTarget target = clone.getCallTarget();
                // Source-bearing target initialization belongs to the context. The
                // worker above cloned and replayed sources without entering it.
                entered(context, () -> { ((OptimizedCallTarget) target).compile(true); return null; });
                assertTrue(((OptimizedCallTarget) target).isValidLastTier());
                assertEquals(1L, count(service, 401)); assertEquals(1L, count(service, 402));
                entered(context, () -> {
                    assertEquals(47L, Calls.target(clone.getCallTarget(), new Object[]{0L}));
                    return null;
                });
            }
        }
    }

    @Test void generatedContinuationEventsBelongToItsSourceRootsContext() throws Throwable {
        try (var engine = engine(); var first = context(engine); var second = context(engine)) {
            var firstState = entered(first, Language::currentState);
            var secondState = entered(second, Language::currentState);
            try (var firstService = new RuntimeJitServices(firstState); var secondService = new RuntimeJitServices(secondState)) {
                assertEquals(0L, firstService.control(400, 1)); assertEquals(0L, secondService.control(400, 1));
                entered(first, () -> {
                    var root = BytecodeRootGen.create(language(), com.oracle.truffle.api.bytecode.BytecodeConfig.DEFAULT, b -> {
                        b.beginRoot(); b.beginYield(); b.emitLoadConstant(Unit.INSTANCE); b.endYield();
                        b.beginReturn(); b.emitLoadConstant(47L); b.endReturn(); b.endRoot();
                    }).getNode(0);
                    var firstCut = (com.oracle.truffle.api.bytecode.ContinuationResult) Calls.target(root.getCallTarget(), new Object[]{0L});
                    var continuation = firstCut.getContinuationRootNode();
                    assertSame(root, continuation.getSourceRootNode());
                    assertEquals(47L, firstCut.continueWith(Unit.INSTANCE));
                    var fresh = (com.oracle.truffle.api.bytecode.ContinuationResult) Calls.target(root.getCallTarget(), new Object[]{0L});
                    assertSame(continuation, fresh.getContinuationRootNode());
                    var target = (OptimizedCallTarget) continuation.getCallTarget();
                    target.compile(true); assertTrue(target.isValidLastTier());
                    assertEquals(1L, count(firstService, 401)); assertEquals(1L, count(firstService, 402));
                    ((OptimizedTruffleRuntime) Truffle.getRuntime()).bypassedInstalledCode(target);
                    assertEquals(47L, fresh.continueWith(Unit.INSTANCE));
                    assertTrue(target.isValidLastTier());
                    assertTrue(target.invalidate("generated continuation telemetry control"));
                    assertEquals(1L, count(firstService, 404));
                    for (int selector = 401; selector <= 406; selector++) assertEquals(0L, count(secondService, selector));
                    return null;
                });
            }
        }
    }

    @Test void reusableTargetsRetainNoContextTokenAndChargeNeitherContext() throws Throwable {
        try (var engine = engine(); var first = context(engine); var second = context(engine)) {
            var firstState = entered(first, Language::currentState);
            var secondState = entered(second, Language::currentState);
            try (var firstService = new RuntimeJitServices(firstState); var secondService = new RuntimeJitServices(secondState)) {
                assertEquals(0L, firstService.control(400, 1)); assertEquals(0L, secondService.control(400, 1));
                var code = entered(first, () -> Program.prepareCode(language(), module(), List.of("identity")));
                com.oracle.truffle.api.CallTarget target = entered(first, () -> {
                    var program = code.newInstance(language());
                    var entry = (Closure) program.entryValue("identity");
                    assertNull(((ContextRoot) entry.target.getRootNode()).compilationOwner());
                    // One declared first-owner call prepares ordinary JIT profiles, not AOT training.
                    assertEquals(17L, Calls.target(entry.target, new Object[]{0L, entry.environment, 17L}));
                    var compiled = (OptimizedCallTarget) entry.target;
                    compiled.compile(true); assertTrue(compiled.isValidLastTier());
                    return (com.oracle.truffle.api.CallTarget) compiled;
                });
                first.close();
                entered(second, () -> {
                    var fresh = code.newInstance(language());
                    var entry = (Closure) fresh.entryValue("identity");
                    var compiled = (OptimizedCallTarget) target;
                    assertSame(target, entry.target);
                    assertTrue(compiled.isValidLastTier());
                    ((OptimizedTruffleRuntime) Truffle.getRuntime()).bypassedInstalledCode(compiled);
                    assertEquals(29L, Calls.target(target, new Object[]{0L, entry.environment, 29L}));
                    assertTrue(compiled.isValidLastTier(), "Fresh owner's first call preserves the shared installed target");
                    assertEquals(1L, ((Number) fresh.diagnostics().get("compiledEntries")).longValue());
                    assertTrue(compiled.invalidate("shared target telemetry control"));
                    return null;
                });
                for (int selector = 401; selector <= 406; selector++) {
                    assertEquals(0L, count(firstService, selector), "shared code is not charged to its preparation context");
                    assertEquals(0L, count(secondService, selector), "shared code is not charged to its latest caller");
                }
            }
        }
    }

    @Test void clonedRootKeepsItsOwnerWhenCompiledWithoutAnEnteredContext() throws Throwable {
        try (var engine = engine(); var first = context(engine); var second = context(engine)) {
            var firstState = entered(first, Language::currentState);
            var secondState = entered(second, Language::currentState);
            try (var firstService = new RuntimeJitServices(firstState); var secondService = new RuntimeJitServices(secondState)) {
                assertEquals(0L, firstService.control(400, 1)); assertEquals(0L, secondService.control(400, 1));
                var clone = (OptimizedCallTarget) entered(first, () -> {
                    var original = new ContextRoot(language(), com.oracle.truffle.api.frame.FrameDescriptor.newBuilder().build()) {
                        @Override public Object execute(VirtualFrame frame) { return 31L; }
                    };
                    var copied = com.oracle.truffle.api.nodes.NodeUtil.cloneNode(original);
                    assertNotSame(original, copied);
                    assertSame(original.compilationOwner(), copied.compilationOwner());
                    var target = (OptimizedCallTarget) copied.getCallTarget();
                    assertEquals(31L, target.call());
                    return (com.oracle.truffle.api.CallTarget) target;
                });
                // No context is entered here, just as on an asynchronous compiler thread.
                clone.compile(true); assertTrue(clone.isValidLastTier());
                assertEquals(1L, count(firstService, 401)); assertEquals(1L, count(firstService, 402));
                assertTrue(clone.invalidate("cloned owner control"));
                assertEquals(1L, count(firstService, 404));
                for (int selector = 401; selector <= 406; selector++) assertEquals(0L, count(secondService, selector));
            }
        }
    }

    @Test void realCompilationsAndInvalidationsAreAttributedToTheirContextWithSharedEngine() throws Throwable {
        try (var engine = engine(); var first = context(engine); var second = context(engine)) {
            var firstLanguage = entered(first, RuntimeJitServicesTest::language);
            var secondLanguage = entered(second, RuntimeJitServicesTest::language);
            assertSame(firstLanguage, secondLanguage, "Shared language identity must not merge context event ownership");
            try (var firstService = entered(first, () -> new RuntimeJitServices(Language.currentState()));
                 var secondService = entered(second, () -> new RuntimeJitServices(Language.currentState()))) {
                assertEquals(0L, count(firstService, 400));
                assertEquals(RuntimeServiceStatus.DISABLED, count(firstService, 401));
                assertEquals(0L, firstService.control(400, 1));
                assertEquals(0L, firstService.control(400, 1), "Repeated enable must not duplicate callbacks");
                assertEquals(0L, secondService.control(400, 1));
                entered(first, () -> {
                    for (var program : programs(firstLanguage)) {
                        assertEquals(37L, call(program, 37L));
                        var target = (OptimizedCallTarget) program.entryTarget("identity");
                        long started = count(firstService, 401);
                        long succeeded = count(firstService, 402);
                        target.compile(true);
                        assertTrue(target.isValidLastTier());
                        assertEquals(started + 1, count(firstService, 401));
                        assertEquals(succeeded + 1, count(firstService, 402));
                        long entries = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                        assertEquals(43L, call(program, 43L), "First compiled call retains semantics");
                        assertEquals(entries + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue());
                        assertTrue(target.isValidLastTier());
                        long invalidations = count(firstService, 404);
                        assertTrue(target.invalidate("RuntimeJitServicesTest explicit test invalidation"));
                        assertEquals(invalidations + 1, count(firstService, 404));
                    }
                    return null;
                });
                for (int selector = 401; selector <= 406; selector++)
                    assertEquals(0L, count(secondService, selector), "No first-context event in second");
                var firstCounts = IntStream.rangeClosed(401, 406).mapToObj(selector -> count(firstService, selector)).toList();
                entered(second, () -> {
                    var program = programs(secondLanguage).getFirst();
                    assertEquals(19L, call(program, 19L));
                    var target = (OptimizedCallTarget) program.entryTarget("identity");
                    target.compile(true);
                    assertTrue(target.isValidLastTier());
                    assertEquals(1L, count(secondService, 401));
                    assertEquals(1L, count(secondService, 402));
                    return null;
                });
                assertEquals(firstCounts, IntStream.rangeClosed(401, 406).mapToObj(selector -> count(firstService, selector)).toList(),
                    "No second-context event in first");
                // Host roots without a language are never counted as THC compilation.
                var unrelated = (OptimizedCallTarget) RootNode.createConstantNode(71).getCallTarget();
                assertEquals(71, unrelated.call());
                unrelated.compile(true);
                ((OptimizedTruffleRuntime) Truffle.getRuntime()).waitForCompilation(unrelated, 30_000);
                assertTrue(unrelated.isValidLastTier());
                assertEquals(firstCounts, IntStream.rangeClosed(401, 406).mapToObj(selector -> count(firstService, selector)).toList());
            }
        }
    }

    @Test void disabledWindowsAreExcludedAndClosePermanentlyRejectsQueriesAndControls() throws Throwable {
        try (var engine = engine(); var context = context(engine)) {
            entered(context, () -> {
                var service = new RuntimeJitServices(Language.currentState());
                try (service) {
                    var program = programs(language()).getFirst();
                    assertEquals(3L, call(program, 3L));
                    var target = (OptimizedCallTarget) program.entryTarget("identity");
                    target.compile(true);
                    assertTrue(target.isValidLastTier());
                    assertEquals(0L, service.control(400, 1));
                    assertEquals(0L, count(service, 401), "No retrospective fabricated history");
                    assertTrue(target.invalidate("first observed invalidation"));
                    assertEquals(1L, count(service, 404));
                    target.compile(true);
                    assertTrue(target.isValidLastTier());
                    assertEquals(1L, count(service, 402));
                    assertEquals(0L, service.control(400, 0));
                    assertEquals(0L, count(service, 400));
                    for (int selector = 401; selector <= 406; selector++) assertEquals(RuntimeServiceStatus.DISABLED, count(service, selector));
                    assertTrue(target.invalidate("disabled invalidation"));
                    target.compile(true);
                    assertTrue(target.isValidLastTier());
                    assertEquals(0L, service.control(400, 1));
                    assertEquals(1L, count(service, 401));
                    assertEquals(1L, count(service, 402));
                    assertEquals(1L, count(service, 404));
                    service.close();
                    service.close();
                    for (int selector = 400; selector <= 406; selector++) assertEquals(RuntimeServiceStatus.UNAVAILABLE, count(service, selector));
                    assertEquals(RuntimeServiceStatus.UNAVAILABLE, service.control(400, 1));
                    assertTrue(target.invalidate("closed observer cannot consume late callbacks"));
                }
                return null;
            });
        }
    }

    @Test void unavailableProvidersAndMalformedRequestsAreNotMisreportedAsCounters() throws Throwable {
        try (var engine = engine(); var context = context(engine)) {
            entered(context, () -> {
                var owner = Language.currentState();
                try (var service = new RuntimeJitServices(owner, () -> null)) {
                    for (int selector = 400; selector <= 406; selector++) assertEquals(RuntimeServiceStatus.UNSUPPORTED, count(service, selector));
                    assertEquals(RuntimeServiceStatus.UNSUPPORTED, service.control(400, 1));
                    assertThrows(RuntimeFault.class, () -> service.query(407, 0, 0));
                    assertThrows(RuntimeFault.class, () -> service.query(400, 1, 0));
                    assertThrows(RuntimeFault.class, () -> service.query(400, 0, -1));
                    assertThrows(RuntimeFault.class, () -> service.control(401, 0));
                    assertThrows(RuntimeFault.class, () -> service.control(400, 2));
                }
                try (var service = new RuntimeJitServices(owner, () -> { throw new SecurityException("test denial"); })) {
                    assertEquals(RuntimeServiceStatus.DENIED, count(service, 400));
                    assertEquals(RuntimeServiceStatus.DENIED, service.control(400, 1));
                }
                try (var service = new RuntimeJitServices(owner, () -> { throw new AssertionError("Do not hide VM failure"); })) {
                    assertThrows(AssertionError.class, () -> count(service, 400));
                }
                return null;
            });
        }
    }

    @Test void callbackAccountingDoesNotInventEventsForVmRetirement() throws Throwable {
        try (var engine = engine("Silent"); var context = context(engine)) {
            entered(context, () -> {
                var owner = language();
                try (var service = new RuntimeJitServices(Language.currentState())) {
                    assertEquals(0L, service.control(400, 1));
                    var deoptimizing = (OptimizedCallTarget) new ContextRoot(owner, com.oracle.truffle.api.frame.FrameDescriptor.newBuilder().build()) {
                        @Override public Object execute(VirtualFrame frame) {
                            if ((Boolean) frame.getArguments()[0]) CompilerDirectives.transferToInterpreterAndInvalidate();
                            return CompilerDirectives.inCompiledCode() ? 17L : 19L;
                        }
                    }.getCallTarget();
                    assertEquals(19L, deoptimizing.call(false));
                    assertEquals(19L, deoptimizing.call(true));
                    deoptimizing.compile(true);
                    assertTrue(deoptimizing.isValidLastTier());
                    ((OptimizedTruffleRuntime) Truffle.getRuntime()).bypassedInstalledCode(deoptimizing);
                    assertEquals(17L, deoptimizing.call(false), "First compiled call");
                    assertTrue(deoptimizing.isValidLastTier());
                    assertEquals(0L, count(service, 405));
                    assertEquals(19L, deoptimizing.call(true));
                    assertFalse(deoptimizing.isValid());
                    // This pinned-runtime VM retirement emits neither callback.
                    // The event counters must stay truthful rather than synthesize
                    // an invalidation/deoptimization from a changed isValid flag.
                    assertEquals(0L, count(service, 404));
                    assertEquals(0L, count(service, 405));
                    // Independently test the listener's deoptimization event path,
                    // explicitly a provider control, not a claim the VM sent one.
                    ((OptimizedTruffleRuntime) Truffle.getRuntime()).getListener()
                        .onCompilationDeoptimized(deoptimizing, null, "controlled telemetry callback");
                    assertEquals(1L, count(service, 405));

                    var rejected = (OptimizedCallTarget) new ContextRoot(owner, com.oracle.truffle.api.frame.FrameDescriptor.newBuilder().build()) {
                        @Override public Object execute(VirtualFrame frame) {
                            CompilerAsserts.neverPartOfCompilation("intentional JIT telemetry test bailout");
                            return 23L;
                        }
                    }.getCallTarget();
                    assertEquals(23L, rejected.call());
                    rejected.compile(true);
                    assertFalse(rejected.isValid());
                    assertTrue(count(service, 403) > 0L, "Actual compiler failure callback");
                    assertTrue(count(service, 406) > 0L, "Actual compilation queue callbacks");
                    assertEquals(23L, rejected.call(), "Diagnostics do not prevent interpretation after a bailout");
                }
                return null;
            });
        }
    }

    @Test void concurrentQueriesAndControlDoNotDuplicateRegistrationOrReviveClosedService() throws Throwable {
        try (var engine = engine(); var context = context(engine)) {
            entered(context, () -> {
                try (var service = new RuntimeJitServices(Language.currentState())) {
                    var start = new CountDownLatch(1);
                    var failure = new AtomicReference<Throwable>();
                    var threads = new ArrayList<Thread>();
                    for (int index = 0; index < 4; index++) threads.add(Thread.ofPlatform().unstarted(() -> {
                        try {
                            start.await();
                            for (int repeat = 0; repeat < 100; repeat++) {
                                assertEquals(0L, service.control(400, 1));
                                long enabled = count(service, 400);
                                assertTrue(enabled >= 0L && enabled <= 1L);
                                long started = count(service, 401);
                                assertTrue(started == 0L || started == RuntimeServiceStatus.DISABLED);
                                assertEquals(0L, service.control(400, 0));
                            }
                        } catch (Throwable caught) { failure.compareAndSet(null, caught); }
                    }));
                    threads.forEach(Thread::start);
                    start.countDown();
                    for (var thread : threads) thread.join();
                    if (failure.get() != null) throw failure.get();
                    assertEquals(0L, service.control(400, 1));
                    var program = programs(language()).getFirst();
                    assertEquals(5L, call(program, 5L));
                    ((OptimizedCallTarget) program.entryTarget("identity")).compile(true);
                    assertEquals(1L, count(service, 401));
                    assertEquals(1L, count(service, 402));
                }
                return null;
            });
        }
    }
}
