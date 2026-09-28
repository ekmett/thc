// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

class HandoffReferenceTest {
    private static List<Object> list(Object... values) { return Arrays.asList(values); }
    private static Map<String, Object> map(Object... values) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }
    private final Map<String, Object> integer = map("kind", "long", "primReps", list("IntRep"), "evaluated", true);
    private final Map<String, Object> data = map("kind", "data", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", false);
    private final Map<String, Object> obj = map("kind", "object", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", false);
    private final Map<String, Object> closure = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", false);
    private List<Object> v(String id) { return list("var", id); }
    private List<Object> n(long value) { return list("lit", "int", Long.toString(value)); }
    private Map<String, Object> param(String id) { return param(id, data); }
    private Map<String, Object> param(String id, Map<String, Object> rep) { return map("id", id, "name", id, "rep", rep, "lifted", rep != integer, "coercion", false); }
    private List<Object> lambda(List<Map<String, Object>> args, List<Object> body) { return lambda(args, body, data); }
    private List<Object> lambda(List<Map<String, Object>> args, List<Object> body, Map<String, Object> result) { return list("lam", args, body, map("rep", closure, "resultRep", result, "entryStrict", Collections.nCopies(args.size(), false))); }
    private Map<String, Object> bind(String id, List<Map<String, Object>> args, List<Object> body) { return bind(id, args, body, data); }
    private Map<String, Object> bind(String id, List<Map<String, Object>> args, List<Object> body, Map<String, Object> result) { return map("id", id, "name", id, "lifted", true, "expr", lambda(args, body, result)); }
    @SafeVarargs private final List<Object> call(String id, List<Object>... args) {
        var flags = new ArrayList<Boolean>();
        for (var arg : args) flags.add(!arg.isEmpty() && Objects.equals(arg.getFirst(), "var") && !Objects.equals(arg.size() > 1 ? arg.get(1) : null, "n"));
        return list("app", v(id), Arrays.asList(args), flags);
    }
    @SafeVarargs private final List<Object> prim(String id, List<Object>... args) { return list("app", list("prim", id), Arrays.asList(args), Collections.nCopies(args.length, false)); }
    @SafeVarargs private final List<Object> con(String id, List<Object>... args) { return list("app", list("con", id, args.length), Arrays.asList(args), Collections.nCopies(args.length, false), true, true); }
    private List<Object> let(List<Object> value, String id, List<Object> body) { return list("case", value, id, list(list("default", null, list(), body))); }
    private List<Object> choose(List<Object> test, List<Object> yes, List<Object> no) { return list("case", test, "test", list(list("lit", list("int", "1"), list(), yes), list("default", null, list(), no))); }
    private Map<String, Object> module(List<Map<String, Object>> bindings) {
        return map("bindings", bindings, "instrument", true, "constructors", list(
            map("id", "Box", "name", "Box", "arity", 1, "kind", "boxed", "fieldReps", list(list("IntRep")), "strictFields", list(false), "fieldLifted", list(false)),
            map("id", "Pair", "name", "Pair", "arity", 2, "kind", "boxed", "fieldReps", list(list("BoxedRep (Just Lifted)"), list("BoxedRep (Just Lifted)")), "strictFields", list(false, false), "fieldLifted", list(true, true))));
    }
    @FunctionalInterface private interface Action { void run(Language language) throws ReflectiveOperationException; }
    private void withLanguage(Action action) throws ReflectiveOperationException {
        String old = System.getProperty(HandoffKt.HANDOFF_PROPERTY); System.setProperty(HandoffKt.HANDOFF_PROPERTY, "true");
        try {
            try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("compiler.Inlining", "false").option("engine.BackgroundCompilation", "false")
                    .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
                context.initialize("thc"); context.enter();
                try { action.run(TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); }
            }
        } finally { if (old == null) System.clearProperty(HandoffKt.HANDOFF_PROPERTY); else System.setProperty(HandoffKt.HANDOFF_PROPERTY, old); }
    }
    private void compile(RootCallTarget target) throws ReflectiveOperationException {
        var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"); type.getMethod("compile", boolean.class).invoke(target, true);
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target));
    }
    private Object host(ExecutableProgram p, String name, Object... args) { return Calls.target(p.hostEntryTarget(args.length), new Object[]{p.entryValue(name), args.clone()}); }
    private void released(Language language) {
        var state = language.getHandoffState().get(); assertEquals(0, state.getArguments().getDepth()); assertNull(state.getPending()); assertEquals(0, state.getArguments().retainedReferences());
    }
    private Thunk bottom() { return new Thunk(new RootNode(null) { @Override public Object execute(VirtualFrame frame) { throw new RuntimeFault("unused reference handoff argument"); } }.getCallTarget(), null); }
    @Test void referenceResultsPreserveIdentityNestedValuesAndLazyInputs() throws ReflectiveOperationException {
        withLanguage(language -> {
            var args = new ArrayList<Map<String, Object>>(); for (String id : List.of("a", "b", "c", "ignored")) args.add(param(id));
            @SuppressWarnings("unchecked") List<Object>[] values = new List[args.size()];
            for (int i = 0; i < args.size(); i++) values[i] = v((String) args.get(i).get("id"));
            var fixtures = module(List.of(bind("box", List.of(param("n", integer)), con("Box", v("n"))), bind("select", args, v("a")),
                bind("nested", args, let(call("select", values), "first", let(call("select", v("b"), v("a"), v("c"), v("ignored")), "second", con("Pair", v("first"), v("second"))))),
                bind("objectIdentity", List.of(param("value", obj)), v("value"), obj), bind("number", List.of(param("n", integer)), prim("+#", v("n"), n(1)), integer),
                bind("objectViaLong", List.of(param("n", integer)), call("number", v("n")), obj), bind("longViaObject", List.of(param("n", integer)), call("objectViaLong", v("n")), integer)));
            for (String backend : List.of("ast", "bytecode")) {
                ExecutableProgram p = backend.equals("ast") ? new Program(language, fixtures) : new BytecodeProgram(language, fixtures);
                var a = host(p, "box", 3_000_000_017L); var b = host(p, "box", -7_000_000_003L); var c = host(p, "box", 11L); var lazy = bottom(); Object boxed = 8_000_000_019L;
                Runnable check = () -> {
                    assertSame(a, host(p, "select", a, b, c, lazy), backend);
                    var pair = (DataValue) host(p, "nested", a, b, c, lazy); assertSame(a, pair.getLayout().read(pair, 0)); assertSame(b, pair.getLayout().read(pair, 1));
                    language.getHandoffState().get().setReturnLong(-913L);
                    assertEquals(boxed, host(p, "objectIdentity", boxed), "An ordinary Long result must not read the scalar register");
                    assertEquals(3_000_000_018L, host(p, "objectViaLong", 3_000_000_017L)); assertEquals(3_000_000_018L, host(p, "longViaObject", 3_000_000_017L));
                    assertEquals(0, lazy.getState()); released(language);
                };
                for (int i = 0; i < 20; i++) check.run();
                for (String name : List.of("select", "nested", "objectIdentity", "number", "objectViaLong", "longViaObject")) compile(p.entryTarget(name)); check.run();
            }
            assertTrue(language.getHandoffState().get().getCalls() > 0);
        });
    }
    @Test void referenceTailCyclesAndCapturedPapPrefixesKeepNaturalResults() throws ReflectiveOperationException {
        withLanguage(language -> {
            var args = List.of(param("n", integer), param("a"), param("b"), param("c"), param("ignored"));
            class Builders {
                List<Object> next(String id) { return call(id, prim("-#", v("n"), n(1)), v("a"), v("b"), v("c"), v("ignored")); }
                Map<String, Object> worker(String id, List<Object> body) { return bind(id, args, choose(prim("<=#", v("n"), n(0)), v("a"), body)); }
            }
            var b = new Builders(); var inner = lambda(List.of(param("ignored"), param("n", integer)), v("captured"));
            var fixtures = module(List.of(bind("box", List.of(param("n", integer)), con("Box", v("n"))), b.worker("self", b.next("self")),
                b.worker("rootA", b.next("rootB")), b.worker("rootB", b.next("rootC")), b.worker("rootC", b.next("rootD")), b.worker("rootD", b.next("rootE")),
                b.worker("rootE", choose(prim("<=#", v("n"), n(5)), b.next("rootB"), b.next("rootC"))), bind("factory", List.of(param("captured")), inner, closure)));
            for (String backend : List.of("ast", "bytecode")) {
                ExecutableProgram p = backend.equals("ast") ? new Program(language, fixtures) : new BytecodeProgram(language, fixtures);
                var value = host(p, "box", Long.MAX_VALUE); var lazy = bottom();
                for (String name : List.of("self", "rootA")) {
                    for (long depth : new long[]{0L, 1L, 12L}) assertSame(value, host(p, name, depth, value, value, value, lazy));
                    compile(p.entryTarget(name)); assertSame(value, host(p, name, 15_000L, value, value, value, lazy)); released(language);
                }
                var worker = (Closure) host(p, "factory", value); var pap = worker.pap(new Object[]{lazy});
                assertSame(value, Calls.target(p.hostEntryTarget(1), new Object[]{pap, new Object[]{3_000_000_017L}})); compile(worker.target);
                assertSame(value, Calls.target(p.hostEntryTarget(1), new Object[]{pap, new Object[]{3_000_000_017L}}));
                assertEquals(1, pap.supplied.length); assertSame(lazy, pap.supplied[0]); assertEquals(0, lazy.getState()); released(language);
            }
            assertTrue(language.getHandoffState().get().getTailTransfers() > 0);
        });
    }
    private static final class RememberReference extends Expr {
        private final int slot; private final List<MaterializedFrame> saved;
        boolean compiledBeforeDeopt; final Object boxedResult = 8_000_000_019L;
        RememberReference(int slot, List<MaterializedFrame> saved) { this.slot = slot; this.saved = saved; }
        @Override public Object execute(VirtualFrame frame) {
            compiledBeforeDeopt = CompilerDirectives.inCompiledCode(); saved.add(frame.materialize()); var value = FrameAccess.read(frame, slot);
            if (Objects.equals(value, "deopt")) CompilerDirectives.transferToInterpreterAndInvalidate();
            if (Objects.equals(value, "fail")) throw new RuntimeFault("reference handoff failure");
            return Objects.equals(value, "boxed") ? boxedResult : value;
        }
    }
    private static final class InvokeWorker extends GuestRoot {
        @Child private DirectCallerNode caller;
        InvokeWorker(RootCallTarget target) { super(null, new FrameLayout().build()); caller = new DirectCallerNode(target, new Metrics(false)); }
        @Override public long bloom(VirtualFrame frame) { return 0L; }
        @Override public Object execute(VirtualFrame frame) { return caller.call(frame, frame.getArguments(), false); }
    }
    @Test void rejectedPacketsClearReferencesBeforeBodyEntryAndAllowReuse() throws ReflectiveOperationException {
        withLanguage(language -> {
            var reference = new CoreRepresentation(CoreKind.OBJECT, true, true, List.of("BoxedRep (Just Lifted)"), null, null, null, null, null);
            var number = new CoreRepresentation(CoreKind.LONG, true, true, List.of("IntRep"), null, null, null, null, null);
            var layout = new FrameLayout(); int slot = layout.bind("value"); var entry = Objects.requireNonNull(HandoffEntry.create(language, layout, List.of(reference, number), reference, false));
            var saved = new ArrayList<MaterializedFrame>(); var body = new RememberReference(slot, saved); body.setRepresentation(reference);
            var root = new FunctionRoot(language, layout.build(), "rejected packet", null, new int[0], new int[]{slot}, new int[]{0}, body, new Metrics(false), new CoreRepresentation[]{reference}, reference, null,
                new boolean[]{false, false}, entry, null, new int[0], null, false, new int[0][], false, FunctionRootRole.FUNCTION, false);
            var caller = new InvokeWorker(root.getCallTarget()); var original = new Object();
            // The final primitive copy fails after the reference field has been written.
            assertThrows(RuntimeFault.class, () -> Calls.target(caller.getCallTarget(), new Object[]{0L, original, "not a Long"})); assertTrue(saved.isEmpty()); released(language);
            assertSame(original, Calls.target(caller.getCallTarget(), new Object[]{0L, original, 7L})); released(language);
            var capturedLayout = new FrameLayout(); int capturedSlot = capturedLayout.bind("capture");
            var capturedEntry = Objects.requireNonNull(HandoffEntry.create(language, capturedLayout, List.of(), reference, true));
            var capturedBody = new RememberReference(capturedSlot, saved); capturedBody.setRepresentation(reference); var captures = new CaptureLayout(language, new boolean[]{false});
            var capturedRoot = new FunctionRoot(language, capturedLayout.build(), "rejected environment", captures, new int[]{capturedSlot}, new int[0], new int[0], capturedBody, new Metrics(false), new CoreRepresentation[0], reference, null,
                new boolean[0], capturedEntry, null, new int[0], null, false, new int[0][], false, FunctionRootRole.FUNCTION, false);
            var capturedCaller = new InvokeWorker(capturedRoot.getCallTarget());
            // The entry owns the loan when capture validation fails after snapshotting.
            assertThrows(RuntimeFault.class, () -> Calls.target(capturedCaller.getCallTarget(), new Object[]{0L, new Object()})); assertEquals(1, saved.size()); released(language);
            var value = new Object();
            assertSame(value, Calls.target(capturedCaller.getCallTarget(), new Object[]{0L, captures.captureValues(new Object[]{value})})); released(language);
            assertSame(original, Calls.target(caller.getCallTarget(), new Object[]{0L, original, 9L})); released(language);
        });
    }
    @Test void referenceSnapshotsSurviveReleaseFailureDeoptimizationAndReuse() throws ReflectiveOperationException {
        withLanguage(language -> {
            var proof = new CoreRepresentation(CoreKind.OBJECT, true, true, List.of("BoxedRep (Just Lifted)"), null, null, null, null, null);
            var layout = new FrameLayout(); int slot = layout.bind("value"); var entry = Objects.requireNonNull(HandoffEntry.create(language, layout, List.of(proof), proof, false));
            var saved = new ArrayList<MaterializedFrame>(); var body = new RememberReference(slot, saved); body.setRepresentation(proof);
            var root = new FunctionRoot(language, layout.build(), "reference snapshots", null, new int[0], new int[]{slot}, new int[]{0}, body, new Metrics(false), new CoreRepresentation[]{proof}, proof, null,
                new boolean[]{false}, entry, null, new int[0], null, false, new int[0][], false, FunctionRootRole.FUNCTION, false);
            var caller = new InvokeWorker(root.getCallTarget()); var original = new Object();
            assertSame(original, Calls.target(caller.getCallTarget(), new Object[]{0L, original}));
            assertThrows(RuntimeFault.class, () -> Calls.target(caller.getCallTarget(), new Object[]{0L, "fail"})); released(language); compile(root.getCallTarget());
            assertEquals("deopt", Calls.target(caller.getCallTarget(), new Object[]{0L, "deopt"})); assertTrue(body.compiledBeforeDeopt);
            var replacement = new Object(); assertSame(replacement, Calls.target(caller.getCallTarget(), new Object[]{0L, replacement}));
            for (var frame : saved) assertEquals(0, frame.getArguments().length);
            assertSame(original, saved.get(0).getObject(entry.getSnapshotSlots()[1]));
            assertEquals("fail", saved.get(1).getObject(entry.getSnapshotSlots()[1])); assertEquals("deopt", saved.get(2).getObject(entry.getSnapshotSlots()[1]));
            language.getHandoffState().get().setReturnLong(-913L);
            assertSame(body.boxedResult, Calls.target(caller.getCallTarget(), new Object[]{0L, "boxed"})); released(language);
        });
    }
}
