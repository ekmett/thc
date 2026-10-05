// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thc.EntryValue;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarValueTestSupport.*;

/** Prepared admission and cold AOT capture; persisted Native Image acceptance is separate. */
class PreparedAdmissionCompatibilityTest {
    private static java.util.Map<String, Object> module() {
        var proof = map("kind", "long", "primReps", list("IntRep"), "evaluated", true);
        return map("bindings", list(map("id", "identity", "name", "identity", "arity", 1, "lifted", true,
            "expr", list("lam", list(map("id", "x", "name", "x", "lifted", false, "coercion", false, "rep", proof)),
                list("var", "x", map("rep", proof)), map("resultRep", proof)))));
    }
    private static Context context(String hosting) {
        return context(hosting, null);
    }
    private static Context context(String hosting, Engine engine) {
        var builder = Context.newBuilder("thc").allowCreateThread(true).allowExperimentalOptions(true)
            .option("thc.ThreadHosting", hosting);
        if (engine == null) builder.option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw");
        else builder.engine(engine);
        return builder.build();
    }
    private static Language language() { return TruffleLanguage.LanguageReference.create(Language.class).get(null); }
    @SuppressWarnings("unchecked") private static List<OptimizedCallTarget> targets(Program.PreparedCode code) throws Exception {
        var field = Program.PreparedCode.class.getDeclaredField("targets"); field.setAccessible(true);
        return (List<OptimizedCallTarget>) field.get(code);
    }
    private static final java.util.Map<String, Object> LONG = map("kind", "long", "primReps", list("IntRep"), "evaluated", true);
    private static final java.util.Map<String, Object> VOID = map("kind", "void", "primReps", list(), "evaluated", true);
    private static final java.util.Map<String, Object> CLOSURE = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
    private static Object variable(String name, java.util.Map<String, Object> proof) { return list("var", name, map("rep", proof)); }
    private static Object call(String name) {
        return list("app", variable(name, CLOSURE), list(list("void", map("rep", VOID))), list(false), false, false, map("rep", LONG));
    }
    private static java.util.Map<String, Object> binder(String name) {
        return map("id", name, "name", name, "lifted", true, "coercion", false, "rep", CLOSURE);
    }
    private static java.util.Map<String, Object> activeModule() {
        var relay = list("app", variable("relay", CLOSURE), list(variable("child", CLOSURE)), list(false), false, false, map("rep", LONG));
        var body = list("app", list("prim", "+#"), list(call("before"), relay), list(false, false), false, false, map("rep", LONG));
        return map("instrument", true, "bindings", list(
            map("id", "parent", "name", "parent", "arity", 2, "lifted", true,
                "expr", list("lam", list(binder("before"), binder("child")), body, map("resultRep", LONG))),
            map("id", "relay", "name", "relay", "arity", 1, "lifted", true,
                "expr", list("lam", list(binder("child")), call("child"), map("resultRep", LONG)))));
    }
    private static RootCallTarget child(Language language, String name, Expr body) {
        var proof = CoreRepresentations.parse(LONG); body.setRepresentation(proof);
        var root = new FunctionRoot(language, new FrameLayout().build(), name, null, new int[0], new int[0], new int[0],
            body, new Metrics(false), new CoreRepresentation[0], proof, null, new boolean[]{false}, null,
            null, new int[0], null, true, new int[0][], false, FunctionRootRole.FUNCTION, false);
        root.configureEagerAsyncPolls(false); return root.getCallTarget();
    }
    @ParameterizedTest @ValueSource(strings = {"platform", "loom"})
    void coldPreparedCallerCapturesAdmissionWithoutRetiringTargetsOrReplayingEffects(String hosting) throws Exception {
        try (var engine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build();
                var context = context(hosting, engine); var other = context(hosting, engine)) {
            context.initialize("thc"); other.initialize("thc"); context.enter();
            try {
                var owner = Language.currentState(); var language = language(); var threads = owner.getThreads();
                var code = Program.prepareCode(language, activeModule(), List.of("parent"));
                var targets = targets(code);
                for (var target : targets) {
                    assertFalse(target.wasExecuted());
                    var root = assertInstanceOf(FunctionRoot.class, target.getRootNode());
                    assertTrue(root.getCapturesContinuations(), "Prepared roots must capture before their first publication");
                    assertFalse(root.getEagerAsyncPolls());
                    assertTrue(target.prepareForAOT()); target.compile(true);
                    assertFalse(target.wasExecuted());
                }
                code.requireInstalledCode();
                var program = code.newInstance(language);
                var parent = assertInstanceOf(Closure.class, program.entryValue("parent"));
                var prefix = new AtomicInteger(); var suffix = new AtomicInteger();
                var before = child(language, "prepared prefix", new Expr() {
                    @Override public Object execute(VirtualFrame frame) { prefix.incrementAndGet(); return 100L; }
                });
                var after = child(language, "prepared transition", new Expr() {
                    @Override public Object execute(VirtualFrame frame) {
                        suffix.incrementAndGet();
                        assertTrue(owner.getSingleGuestOriginAssumption().isValid());
                        owner.admitGuestConcurrency();
                        assertFalse(owner.getSingleGuestOriginAssumption().isValid());
                        var request = threads.send(threads.currentIdentity(), "prepared active cut");
                        assertSame(request, GuestThreads.pollCurrent(this, false));
                        throw new AstCapture(request, SynchronousMasking.current(this)).append((saved, input) -> 42L);
                    }
                });
                owner.admitGuestOrigin();
                java.util.concurrent.Callable<Void> execute = () -> {
                    threads.enterCurrent();
                    try {
                        assertFalse(GuestThreads.ordinaryPollEnabled(parent.target.getRootNode()));
                        String previous = System.getProperty("thc.requireCompiledCode");
                        System.setProperty("thc.requireCompiledCode", "true");
                        try {
                            var saved = Objects.requireNonNull(SavedGuestContinuations.savedGuestContinuation(Calls.target(parent.target,
                                new Object[]{0L, parent.environment, new Closure(null, 1, before), new Closure(null, 1, after)})));
                            code.requireInstalledCode();
                            assertTrue(GuestThreads.ordinaryPollEnabled(parent.target.getRootNode()));
                            other.enter();
                            try {
                                var failure = assertThrows(RuntimeFault.class, () -> saved.continueWith(Unit.INSTANCE));
                                assertTrue(failure.getMessage().contains("Prepared continuation belongs to another program context"),
                                    failure::getMessage);
                            } finally { other.leave(); }
                            saved.asyncRequest().acknowledge();
                            Object result = new RootNode(language) {
                                @Child private Force force = new Force(new Metrics(false), true);
                                @Override public Object execute(VirtualFrame frame) { return force.drainStack(saved, null); }
                            }.getCallTarget().call();
                            assertEquals(142L, result);
                            assertEquals(1, prefix.get()); assertEquals(1, suffix.get());
                            assertSame(parent.target, program.entryTarget("parent"));
                            assertEquals(0L, ((Number) program.diagnostics().get("loweredRootCount")).longValue());
                            assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > 0);
                            code.requireInstalledCode();
                            var handoff = language.getHandoffState().get();
                            assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth());
                            assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
                        } finally {
                            if (previous == null) System.clearProperty("thc.requireCompiledCode"); else System.setProperty("thc.requireCompiledCode", previous);
                        }
                    } finally { threads.leaveCurrent(); }
                    return null;
                };
                if (threads.needsHosting()) threads.hostEntry(parent.target.getRootNode(), execute);
                else execute.call();
            } finally { context.leave(); }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"platform", "loom"})
    void preparedMyThreadIdObservesEachCurrentContext(String hosting) throws Exception {
        var thread = map("kind", "object", "primReps", list("BoxedRep (Just Unlifted)"), "evaluated", true);
        var tuple = map("kind", "unknown", "aggregate", "unboxed-tuple", "components", list(VOID, thread),
            "primReps", list("BoxedRep (Just Unlifted)"), "evaluated", true);
        var stateBinder = map("id", "s", "name", "s", "lifted", false, "coercion", false, "rep", VOID);
        var threadBinder = map("id", "tid", "name", "tid", "lifted", false, "coercion", false, "rep", thread);
        var current = list("app", list("prim", "myThreadId#"), list(variable("s", VOID)),
            list(false), false, false, map("rep", tuple));
        var body = list("case", current, "current", list(list("data", "StateThread", list("s1", "tid"), variable("tid", thread),
            map("binders", list(with(stateBinder, "id", "s1", "name", "s1"), threadBinder)))),
            map("rep", thread, "binder", map("id", "current", "name", "current", "lifted", false, "rep", tuple)));
        var source = map("bindings", list(map("id", "currentThread", "name", "currentThread", "arity", 1, "lifted", true,
            "expr", list("lam", list(stateBinder), body, map("resultRep", thread)))),
            "constructors", list(map("id", "StateThread", "name", "StateThread", "kind", "unboxed-tuple", "arity", 2)));
        try (var engine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            final Language preparedLanguage;
            final Program.PreparedCode code;
            var previous = new GuestThreadId[1];
            try (var preparation = context(hosting, engine)) {
                preparation.initialize("thc"); preparation.enter();
                try {
                    preparedLanguage = language();
                    var threads = Language.currentState().getThreads();
                    java.util.concurrent.Callable<Program.PreparedCode> prepare = () -> {
                        threads.enterCurrent();
                        try {
                            previous[0] = threads.currentIdentity();
                            return Program.prepareCode(preparedLanguage, source, List.of("currentThread"));
                        } finally { threads.leaveCurrent(); }
                    };
                    code = threads.needsHosting() ? threads.hostEntry(null, prepare) : prepare.call();
                    for (var target : targets(code)) assertFalse(target.wasExecuted());
                } finally { preparation.leave(); }
            }
            try (var first = context(hosting, engine); var second = context(hosting, engine)) {
                first.initialize("thc"); second.initialize("thc");
                first.enter();
                try {
                    assertSame(preparedLanguage, language());
                    for (var target : targets(code)) {
                        assertFalse(target.wasExecuted());
                        assertTrue(target.prepareForAOT()); target.compile(true);
                        assertFalse(target.wasExecuted());
                    }
                    code.requireInstalledCode();
                } finally { first.leave(); }
                for (var runtime : List.of(first, second)) {
                    runtime.enter();
                    try {
                        assertSame(preparedLanguage, language());
                        var threads = Language.currentState().getThreads();
                        var program = code.newInstance(preparedLanguage);
                        var entry = assertInstanceOf(Closure.class, program.entryValue("currentThread"));
                        java.util.concurrent.Callable<GuestThreadId> execute = () -> {
                            threads.enterCurrent();
                            try {
                                var expected = threads.currentIdentity();
                                assertSame(threads, expected.getOwner());
                                assertNotSame(previous[0], expected);
                                assertSame(expected, Calls.target(entry.target, new Object[]{0L, entry.environment, Unit.INSTANCE}));
                                return expected;
                            } finally { threads.leaveCurrent(); }
                        };
                        previous[0] = threads.needsHosting() ? threads.hostEntry(entry.target.getRootNode(), execute) : execute.call();
                        code.requireInstalledCode();
                    } finally { runtime.leave(); }
                }
            }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"platform", "loom"})
    void preparedPureInstanceAcceptsDifferentPublicOrigins(String hosting) throws Exception {
        try (var context = context(hosting)) {
            context.initialize("thc"); context.enter();
            final Value entry;
            final Program.PreparedCode code;
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                code = Program.prepareCode(language, module(), List.of("identity"));
                for (var target : targets(code)) {
                    assertFalse(target.wasExecuted()); assertTrue(target.prepareForAOT()); target.compile(true);
                    assertFalse(target.wasExecuted());
                }
                code.requireInstalledCode();
                entry = context.asValue(new EntryValue(code.newInstance(language), "identity", 1));
            } finally { context.leave(); }
            assertEquals(7L, entry.execute(7L).asLong());
            code.requireInstalledCode();
            assertEquals(9L, CompletableFuture.supplyAsync(() -> entry.execute(9L).asLong()).get(10, TimeUnit.SECONDS));
            code.requireInstalledCode();
            assertEquals(11L, entry.execute(11L).asLong());
            code.requireInstalledCode();
            var diagnostics = (java.util.Map<?, ?>) Json.parse(entry.getMember("diagnostics").asString());
            assertEquals(0L, ((Number) diagnostics.get("loweredRootCount")).longValue());
            assertTrue(((Number) diagnostics.get("compiledEntries")).longValue() >= 3);
        }
    }
    @ParameterizedTest @ValueSource(strings = {"platform", "loom"})
    void preparedInstanceCanFollowDifferentOrdinaryPublicOrigins(String hosting) throws Exception {
        try (var context = context(hosting)) {
            context.initialize("thc"); context.enter();
            final Value ordinary;
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                ordinary = context.asValue(new EntryValue(new Program(language, module()), "identity", 1));
            } finally { context.leave(); }
            assertEquals(7L, ordinary.execute(7L).asLong());
            assertEquals(9L, CompletableFuture.supplyAsync(() -> ordinary.execute(9L).asLong()).get(10, TimeUnit.SECONDS));
            context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var code = Program.prepareCode(language, module(), List.of("identity"));
                var entry = context.asValue(new EntryValue(code.newInstance(language), "identity", 1));
                assertEquals(11L, entry.execute(11L).asLong());
            } finally { context.leave(); }
        }
    }
}
