// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.EntryValue;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

/** Cross-backend ownership and nontrivial bloom cycles, separate from native arithmetic. */
class TypedInputProtocolTest {
    private static List<Object> list(Object... values) { return Arrays.asList(values); }
    private static Map<String, Object> map(Object... values) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }
    private final Map<String, Object> integer = map("kind", "long", "primReps", list("IntRep"), "evaluated", true);
    private final Map<String, Object> ref = map("kind", "object", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", false);
    private final Map<String, Object> closure = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
    private final Map<String, Object> pair = map("kind", "unknown", "aggregate", "unboxed-tuple", "evaluated", true, "primReps", list("IntRep", "IntRep"), "components", list(integer, integer));
    private List<Object> v(String id) { return v(id, integer); }
    private List<Object> v(String id, Map<String, Object> rep) { return list("var", id, map("rep", rep)); }
    private List<Object> n(long value) { return list("lit", "int", Long.toString(value), map("rep", integer)); }
    private Map<String, Object> arg(String id) { return arg(id, integer); }
    private Map<String, Object> arg(String id, Map<String, Object> rep) { return map("id", id, "name", id, "rep", rep, "lifted", rep.equals(ref) || rep.equals(closure)); }
    private List<Object> app(List<Object> fn, List<List<Object>> args) { return app(fn, args, integer); }
    private List<Object> app(List<Object> fn, List<List<Object>> args, Map<String, Object> rep) { return app(fn, args, rep, Collections.nCopies(args.size(), false)); }
    private List<Object> app(List<Object> fn, List<List<Object>> args, Map<String, Object> rep, List<Boolean> flags) { return list("app", fn, args, flags, false, false, map("rep", rep)); }
    private List<Object> call(String id, List<List<Object>> args) { return call(id, args, integer); }
    private List<Object> call(String id, List<List<Object>> args, Map<String, Object> rep) { return app(v(id, closure), args, rep); }
    private List<Object> prim(String name, List<Object> left, List<Object> right) { return app(list("prim", name), List.of(left, right)); }
    private List<Object> pack(List<Object> a, List<Object> b) { return app(list("con", "Pair", 2), List.of(a, b), pair); }
    private List<Object> unpack(List<Object> value, List<Object> body) { return unpack(value, body, integer); }
    private List<Object> unpack(List<Object> value, List<Object> body, Map<String, Object> result) {
        return list("case", value, "whole", list(list("data", "Pair", list("a", "b"), body, map("binders", list(arg("a"), arg("b"))))), map("rep", result, "binder", arg("whole", pair)));
    }
    private List<Object> lam(List<Map<String, Object>> args, List<Object> body) { return lam(args, body, integer); }
    private List<Object> lam(List<Map<String, Object>> args, List<Object> body, Map<String, Object> result) { return lam(args, body, result, Collections.nCopies(args.size(), false)); }
    private List<Object> lam(List<Map<String, Object>> args, List<Object> body, Map<String, Object> result, List<Boolean> strict) { return list("lam", args, body, map("rep", closure, "resultRep", result, "entryStrict", strict)); }
    private Map<String, Object> bind(String id, List<Object> body) { return map("id", id, "name", id, "rep", closure, "lifted", true, "expr", body); }
    private Map<String, Object> module(List<Map<String, Object>> bindings) { return map("bindings", bindings, "instrument", true, "constructors", list(map("id", "Pair", "name", "Pair", "kind", "unboxed-tuple", "arity", 2))); }
    @FunctionalInterface private interface Action { void run(Context context, Language language) throws ReflectiveOperationException; }
    private void withLanguage(boolean inlining, Action action) throws ReflectiveOperationException {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").option("compiler.Inlining", Boolean.toString(inlining)).build()) {
            context.initialize("thc"); context.enter();
            try { action.run(context, TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); }
        }
    }
    private ExecutableProgram program(Language language, String backend, List<Map<String, Object>> bindings) {
        return switch (backend) {
            case "ast" -> new Program(language, module(bindings));
            case "bytecode-async" -> new BytecodeProgram(language, module(bindings), true);
            default -> new BytecodeProgram(language, module(bindings));
        };
    }
    private Object run(ExecutableProgram program, String name, Object... values) { return Calls.target(program.hostEntryTarget(values.length), new Object[]{program.entryValue(name), values}); }
    private void valid(RootCallTarget target, String label) throws ReflectiveOperationException { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label + "/" + target); }
    private void clear(Language language) {
        var state = language.getHandoffState().get(); assertNull(state.getPending());
        assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getArguments().retainedReferences());
        assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getResults().retainedReferences());
    }
    private Set<RootCallTarget> active(RootCallTarget host, RootCallTarget original) {
        var result = new LinkedHashSet<RootCallTarget>();
        for (var node : NodeUtil.findAllNodeInstances(host.getRootNode(), DirectCallNode.class)) if (node.getCallTarget() == original) result.add((RootCallTarget) node.getCurrentCallTarget());
        return result;
    }
    @Test void threeTargetAndPrefixedThreeTargetCyclesReturnTypedResults() throws ReflectiveOperationException {
        for (String backend : List.of("ast", "bytecode", "bytecode-async")) for (boolean inlining : new boolean[]{true, false}) withLanguage(inlining, (context, language) -> {
            class Builders {
                Map<String, Object> worker(String id, String next) {
                    return bind(id, lam(List.of(arg("p", pair), arg("depth")), list("case", prim("<=#", v("depth"), n(0)), "done", list(
                        list("lit", list("int", "1"), list(), v("p", pair)),
                        list("default", null, list(), call(next, List.of(v("p", pair), prim("-#", v("depth"), n(1))), pair))), map("rep", pair, "binder", arg("done"))), pair));
                }
                Map<String, Object> entry(String name, String first) { return bind(name, lam(List.of(arg("x")), unpack(call(first, List.of(pack(v("x"), n(7)), n(17)), pair), prim("+#", v("a"), v("b"))))); }
            }
            var b = new Builders();
            var bindings = List.of(b.worker("a", "b"), b.worker("b", "c"), b.worker("c", "a"), b.worker("pa", "pb"), b.worker("pb", "pc"), b.worker("pc", "pd"), b.worker("pd", "pe"), b.worker("pe", "pc"), b.entry("cycle", "a"), b.entry("prefixCycle", "pa"));
            var p = program(language, backend, bindings);
            for (String name : List.of("cycle", "prefixCycle")) {
                String label = backend + "/" + name + "/inline=" + inlining;
                var fn = context.asValue(new EntryValue(p, name, 1)); var inputs = List.of(Long.MIN_VALUE, -1L, 0L, 11L, Long.MAX_VALUE);
                for (long x : inputs) { assertEquals(x + 7L, fn.execute(x).asLong(), label + "/interpreted"); clear(language); }
                assertTrue(fn.invokeMember("compile").asBoolean(), label);
                var original = p.entryTarget(name); var host = p.hostEntryTarget(1); var observed = active(host, original);
                assertTrue(!observed.isEmpty(), label + " actual host linkage");
                for (long x : inputs.reversed()) {
                    long before = (Long) p.diagnostics().get("compiledEntries");
                    assertEquals(x + 7L, fn.execute(x).asLong(), label + "/compiled/" + x);
                    assertTrue((Long) p.diagnostics().get("compiledEntries") > before, label + " missing compiled guest entry");
                    valid(original, label); valid(host, label); assertEquals(observed, active(host, original), label);
                    for (var target : observed) valid(target, label); clear(language);
                }
                assertTrue((Long) p.diagnostics().get("selfTailReentries") > 0, label);
                if (backend.equals("bytecode-async")) {
                    var path = name.equals("cycle") ? List.of("a", "b", "c") : List.of("pa", "pb", "pc", "pd", "pe");
                    var masks = new ArrayList<String>();
                    for (var id : path) masks.add(Long.toUnsignedString(((GuestRoot) p.entryTarget(id).getRootNode()).mask, 16));
                    assertEquals(0L, p.diagnostics().get("trampolineIterations"), label + " should reenter locally; masks=" + masks);
                }
            }
        });
    }
    private static final class PrefixThunk extends GuestRoot {
        private final Language language; private final boolean fail; int calls;
        PrefixThunk(Language language, boolean fail) { super(language, new FrameLayout().build()); this.language = language; this.fail = fail; configureEntry(new boolean[0], false); }
        @Override public long bloom(VirtualFrame frame) { return 0L; }
        @Override public Object execute(VirtualFrame frame) {
            var state = language.getHandoffState().get();
            if (state.getArguments().getDepth() != 0 || state.getArguments().retainedReferences() != 0) throw new IllegalStateException("Check failed.");
            calls++; if (fail) throw new GuestException("strict prefix", this); return 123L;
        }
    }
    @Test void genericStrictPrefixForcingPrecedesLoansAndNonStrictPrefixesStayLazy() throws ReflectiveOperationException {
        for (String backend : List.of("ast", "bytecode")) for (boolean strict : new boolean[]{false, true}) for (boolean fail : new boolean[]{false, true}) withLanguage(false, (context, language) -> {
            var worker = bind("worker", lam(List.of(arg("prefix", ref), arg("p", pair)), unpack(v("p", pair), prim("+#", v("a"), v("b"))), integer, List.of(strict, false)));
            var p = program(language, backend, List.of(worker, bind("make", lam(List.of(arg("prefix", ref)), app(v("worker", closure), List.of(v("prefix", ref)), closure, List.of(true)), closure))));
            var thunkRoot = new PrefixThunk(language, fail); var thunk = new Thunk(thunkRoot.getCallTarget(), null); var pap = (Closure) run(p, "make", thunk);
            var prefix = Objects.requireNonNull(pap.typedSupplied); var input = Objects.requireNonNull(((GuestRoot) pap.target.getRootNode()).getTypedInput());
            var layout = new FrameLayout(); int[] slots = {layout.bind("first"), layout.bind("second")}; var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], layout.build());
            FrameAccess.writeLong(frame, slots[0], 11L); FrameAccess.writeLong(frame, slots[1], 7L);
            var source = new AstInputSource(Objects.requireNonNull(ArgumentLayout.fromProofs(List.of(input.getLogical().proof(1)))), slots); var node = new Node() {};
            class Prepare { HandoffStorage get() { return GenericTypedInputsKt.prepareGenericInput(frame, node, pap, input, source, null, 1, 0, 1, new Force(new Metrics(false))); } }
            var prepare = new Prepare(); assertEquals(0, thunkRoot.calls); clear(language);
            if (strict && fail) assertThrows(GuestException.class, prepare::get);
            else {
                var storage = prepare.get();
                try {
                    assertTrue(storage.getLive()); assertEquals(1, storage.getInputMode()); assertEquals(1, language.getHandoffState().get().getArguments().getDepth());
                    if (strict) assertEquals(123L, input.getPacket().getObject(storage, input.getHeader())); else assertSame(thunk, input.getPacket().getObject(storage, input.getHeader()));
                    assertEquals(11L, input.getPacket().getLong(storage, input.getHeader() + 1)); assertEquals(7L, input.getPacket().getLong(storage, input.getHeader() + 2));
                } finally { GenericTypedInputsKt.releaseGenericInput(input, storage, storage.getGeneration()); }
            }
            assertEquals(strict ? 1 : 0, thunkRoot.calls); assertSame(thunk, prefix.getLayout().getObject(prefix, 0)); assertFalse(prefix.getLive()); assertEquals(0, prefix.getInputMode());
            assertEquals(11L, FrameAccess.read(frame, slots[0])); assertEquals(7L, FrameAccess.read(frame, slots[1])); clear(language);
        });
    }
    @Test void cyclicIntermediateClosureIsAppliedBeforeFinalTupleConsumptionInPicAndGenericRoutes() throws ReflectiveOperationException {
        for (String backend : List.of("ast", "bytecode")) for (boolean inlining : new boolean[]{true, false}) withLanguage(inlining, (context, language) -> {
            class Builders {
                Map<String, Object> worker(String id, String next) {
                    return bind(id, lam(List.of(arg("p", pair), arg("depth")), list("case", prim("<=#", v("depth"), n(0)), "done", list(
                        list("lit", list("int", "1"), list(), unpack(v("p", pair), lam(List.of(arg("z")), pack(prim("+#", v("a"), v("z")), v("b")), pair), closure)),
                        list("default", null, list(), call(next, List.of(v("p", pair), prim("-#", v("depth"), n(1))), closure))), map("rep", closure, "binder", arg("done"))), closure));
                }
                List<Object> body(String f) { return unpack(call(f, List.of(pack(v("x"), n(7)), n(4), n(9)), pair), prim("+#", v("a"), v("b"))); }
            }
            var b = new Builders(); var workers = new ArrayList<Map<String, Object>>();
            for (int i = 0; i <= 4; i++) workers.add(b.worker("w" + i, "loop")); workers.add(b.worker("loop", "w0"));
            workers.add(bind("direct", lam(List.of(arg("x")), b.body("w0")))); workers.add(bind("generic", lam(List.of(arg("f", closure), arg("x")), b.body("f"))));
            var p = program(language, backend, workers); var functions = new ArrayList<Object>(); for (int i = 0; i <= 4; i++) functions.add(p.entryValue("w" + i));
            var inputs = List.of(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE);
            for (String name : List.of("direct", "generic")) {
                int arity = name.equals("direct") ? 1 : 2; var fn = context.asValue(new EntryValue(p, name, arity));
                class Invoke { long call(Object f, long x) { return arity == 1 ? fn.execute(x).asLong() : (Long) run(p, name, f, x); } }
                var invoke = new Invoke();
                for (var f : functions) for (long x : inputs) { assertEquals(x + 16L, invoke.call(f, x)); clear(language); }
                assertTrue(fn.invokeMember("compile").asBoolean(), backend + "/" + name + "/inline=" + inlining);
                var original = p.entryTarget(name); var host = p.hostEntryTarget(arity); var observed = active(host, original); assertFalse(observed.isEmpty());
                for (var f : functions.reversed()) for (long x : inputs.reversed()) {
                    long before = (Long) p.diagnostics().get("compiledEntries");
                    assertEquals(x + 16L, invoke.call(f, x), backend + "/" + name + "/" + x + "/inline=" + inlining);
                    assertTrue((Long) p.diagnostics().get("compiledEntries") > before);
                    valid(original, name); valid(host, name); assertEquals(observed, active(host, original)); for (var target : observed) valid(target, name); clear(language);
                }
            }
        });
    }
    @Test void durableTypedPapPrefixSurvivesReuseAndStrictPrefixFailureBeforeLoan() throws ReflectiveOperationException {
        for (String backend : List.of("ast", "bytecode")) withLanguage(false, (context, language) -> {
            var worker = bind("worker", lam(List.of(arg("prefix", ref), arg("p", pair)), unpack(v("p", pair), prim("+#", v("a"), v("b"))), integer, List.of(true, false)));
            var p = program(language, backend, List.of(worker,
                bind("make", lam(List.of(arg("prefix", ref)), app(v("worker", closure), List.of(v("prefix", ref)), closure, List.of(true)), closure)),
                bind("apply", lam(List.of(arg("f", closure), arg("x")), call("f", List.of(pack(v("x"), n(7))))))));
            var goodRoot = new PrefixThunk(language, false); var good = new Thunk(goodRoot.getCallTarget(), null);
            var badRoot = new PrefixThunk(language, true); var bad = new Thunk(badRoot.getCallTarget(), null);
            var goodPap = (Closure) run(p, "make", good); var badPap = (Closure) run(p, "make", bad);
            assertEquals(0, goodRoot.calls); assertEquals(0, badRoot.calls);
            var goodStorage = Objects.requireNonNull(goodPap.typedSupplied); var badStorage = Objects.requireNonNull(badPap.typedSupplied);
            assertEquals(0, goodStorage.getInputMode()); assertFalse(goodStorage.getLive()); assertSame(good, goodStorage.getLayout().getObject(goodStorage, 0));
            assertEquals(18L, run(p, "apply", goodPap, 11L)); clear(language);
            assertThrows(GuestException.class, () -> run(p, "apply", badPap, 99L)); clear(language);
            assertSame(bad, badStorage.getLayout().getObject(badStorage, 0)); assertEquals(1, badRoot.calls);
            assertEquals(-2L, run(p, "apply", goodPap, -9L)); clear(language);
            assertSame(good, goodStorage.getLayout().getObject(goodStorage, 0)); assertEquals(1, goodRoot.calls);
            assertThrows(GuestException.class, () -> run(p, "apply", badPap, 99L)); clear(language); assertEquals(1, badRoot.calls);
        });
    }
}
