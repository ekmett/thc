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

    private static List<ExecutableProgram> programs(Language language) {
        var integer = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
        var closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
        Map<String, Object> module = Map.of("instrument", true, "bindings", List.of(Map.of("id", "identity", "name", "identity",
            "lifted", true, "expr", List.of("lam", List.of(Map.of("id", "n", "lifted", false, "rep", integer)),
                List.of("var", "n", Map.of("rep", integer)), Map.of("rep", closure, "resultRep", integer)))));
        return List.of(new Program(language, module, false, false), new BytecodeProgram(language, module, false));
    }

    private static Object call(ExecutableProgram program, long value) {
        return Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue("identity"), new Object[]{value}});
    }

    private static long count(RuntimeJitServices service, int selector) { return service.query(selector, 0, 0); }

    @Test void realCompilationsAndInvalidationsAreAttributedToTheirContextWithSharedEngine() throws Throwable {
        try (var engine = engine(); var first = context(engine); var second = context(engine)) {
            var firstLanguage = entered(first, RuntimeJitServicesTest::language);
            var secondLanguage = entered(second, RuntimeJitServicesTest::language);
            assertNotSame(firstLanguage, secondLanguage, "EXCLUSIVE language identity is the attribution boundary");
            try (var firstService = new RuntimeJitServices(firstLanguage); var secondService = new RuntimeJitServices(secondLanguage)) {
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
                var service = new RuntimeJitServices(language());
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
                var owner = language();
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
                try (var service = new RuntimeJitServices(owner)) {
                    assertEquals(0L, service.control(400, 1));
                    var deoptimizing = (OptimizedCallTarget) new RootNode(owner) {
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

                    var rejected = (OptimizedCallTarget) new RootNode(owner) {
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
                try (var service = new RuntimeJitServices(language())) {
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
