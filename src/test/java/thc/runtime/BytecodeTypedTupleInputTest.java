// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeUtil;
import java.util.*;
import java.util.function.LongUnaryOperator;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.EntryValue;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.CoreCallDemands.CALL_DEMANDS_PROPERTY;

/** Backend-local controls; genuine exported native coverage lives in the shared fixture. */
class BytecodeTypedTupleInputTest {
    private static List<Object> list(Object... values) { return Arrays.asList(values); }
    private static Map<String, Object> map(Object... values) {
        var result = new LinkedHashMap<String, Object>(); for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]); return result;
    }
    private final Map<String, Object> integer = map("kind", "long", "primReps", list("IntRep"), "evaluated", true);
    private final Map<String, Object> floating = map("kind", "float", "primReps", list("FloatRep"), "evaluated", true);
    private final Map<String, Object> doubleProof = map("kind", "double", "primReps", list("DoubleRep"), "evaluated", true);
    private final Map<String, Object> closure = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
    private final Map<String, Object> reference = map("kind", "object", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", false);
    private final Map<String, Object> state = map("kind", "void", "primReps", list(), "evaluated", true);
    @SafeVarargs @SuppressWarnings("unchecked") private final Map<String, Object> tuple(Map<String, Object>... fields) {
        var reps = new ArrayList<String>(); for (var field : fields) reps.addAll((List<String>) field.get("primReps"));
        return map("kind", "unknown", "primReps", reps, "evaluated", true, "aggregate", "unboxed-tuple", "components", Arrays.asList(fields));
    }
    private final Map<String, Object> pair = tuple(integer, integer);
    private List<Object> v(String id) { return v(id, integer); }
    private List<Object> v(String id, Map<String, Object> rep) { return list("var", id, map("rep", rep)); }
    private List<Object> n(long value) { return list("lit", "int", Long.toString(value), map("rep", integer)); }
    private Map<String, Object> arg(String id) { return arg(id, integer); }
    private Map<String, Object> arg(String id, Map<String, Object> rep) { return map("id", id, "name", id, "lifted", rep.equals(closure) || rep.equals(reference), "rep", rep); }
    private List<Object> app(List<Object> fn, List<List<Object>> args) { return app(fn, args, integer); }
    private List<Object> app(List<Object> fn, List<List<Object>> args, Map<String, Object> rep) { return app(fn, args, rep, Collections.nCopies(args.size(), false)); }
    private List<Object> app(List<Object> fn, List<List<Object>> args, Map<String, Object> rep, List<Boolean> flags) { return list("app", fn, args, flags, false, false, map("rep", rep)); }
    private List<Object> call(String id, List<List<Object>> args) { return call(id, args, integer); }
    private List<Object> call(String id, List<List<Object>> args, Map<String, Object> rep) { return app(v(id, closure), args, rep); }
    @SafeVarargs private final List<Object> prim(String id, List<Object>... args) { return app(list("prim", id), Arrays.asList(args)); }
    @SafeVarargs @SuppressWarnings("unchecked") private final List<Object> pack(Map<String, Object> rep, List<Object>... fields) {
        if (fields.length == 0) return list("con", "T0", 0, map("rep", rep));
        var flags = new ArrayList<Boolean>(); for (var component : (List<Map<String, Object>>) rep.get("components")) flags.add(component.equals(closure) || component.equals(reference));
        return app(list("con", "T" + fields.length, fields.length), Arrays.asList(fields), rep, flags);
    }
    private List<Object> unpack(List<Object> value, Map<String, Object> rep, List<String> ids, List<Object> body) { return unpack(value, rep, ids, body, integer); }
    @SuppressWarnings("unchecked") private List<Object> unpack(List<Object> value, Map<String, Object> rep, List<String> ids, List<Object> body, Map<String, Object> result) {
        var fields = (List<Map<String, Object>>) rep.get("components"); var binders = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < Math.min(ids.size(), fields.size()); i++) binders.add(arg(ids.get(i), fields.get(i)));
        return list("case", value, "whole", list(list("data", "T" + ids.size(), ids, body, map("binders", binders))), map("rep", result, "binder", arg("whole", rep)));
    }
    private List<Object> lam(List<Map<String, Object>> args, List<Object> body) { return lam(args, body, integer); }
    private List<Object> lam(List<Map<String, Object>> args, List<Object> body, Map<String, Object> result) { return list("lam", args, body, map("rep", closure, "resultRep", result, "entryStrict", Collections.nCopies(args.size(), false))); }
    private Map<String, Object> bind(String id, List<Object> expr) { return map("id", id, "name", id, "lifted", true, "rep", closure, "expr", expr); }
    @SafeVarargs private final Map<String, Object> module(Map<String, Object>... bindings) {
        var constructors = new ArrayList<Map<String, Object>>(); for (int i = 0; i <= 4; i++) constructors.add(map("id", "T" + i, "name", "T" + i, "kind", "unboxed-tuple", "arity", i));
        return map("bindings", Arrays.asList(bindings), "instrument", true, "constructors", constructors);
    }
    @FunctionalInterface private interface Action { void run(Context context, Language language) throws ReflectiveOperationException; }
    private void withLanguage(Action action) throws ReflectiveOperationException { withLanguage(true, action); }
    private void withLanguage(boolean inlining, Action action) throws ReflectiveOperationException {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("compiler.Inlining", Boolean.toString(inlining)).option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try { action.run(context, TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); }
        }
    }
    private Object run(ExecutableProgram p, String name, Object... args) { return Calls.target(p.hostEntryTarget(args.length), new Object[]{p.entryValue(name), args}); }
    private void valid(RootCallTarget target) throws ReflectiveOperationException { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), target.toString()); }
    private void released(Language language) {
        var s = language.getHandoffState().get(); assertNull(s.getPending()); assertEquals(0, s.getArguments().getDepth()); assertEquals(0, s.getArguments().retainedReferences());
        assertEquals(0, s.getResults().getDepth()); assertEquals(0, s.getResults().retainedReferences());
    }
    private Set<RootCallTarget> active(RootCallTarget host, RootCallTarget original) {
        var result = new LinkedHashSet<RootCallTarget>(); for (var call : NodeUtil.findAllNodeInstances(host.getRootNode(), DirectCallNode.class)) if (call.getCallTarget() == original) result.add((RootCallTarget) call.getCurrentCallTarget()); return result;
    }
    private void checkCompiled(Context context, Language language, ExecutableProgram p, String name, LongUnaryOperator expected) throws ReflectiveOperationException {
        var values = List.of(Long.MIN_VALUE, -17L, 0L, 1L, Long.MAX_VALUE); var fn = context.asValue(new EntryValue(p, name, 1));
        for (long x : values) assertEquals(expected.applyAsLong(x), fn.execute(x).asLong(), name + "/" + x + " interpreted");
        try { assertTrue(fn.invokeMember("compile").asBoolean()); } catch (Throwable failure) { throw new AssertionError("Compilation failed for bytecode tuple-input control " + name, failure); }
        var original = p.entryTarget(name); var host = p.hostEntryTarget(1); var targets = active(host, original);
        for (long x : values.reversed()) {
            long before = (Long) p.diagnostics().get("compiledEntries"); assertEquals(expected.applyAsLong(x), fn.execute(x).asLong(), name + "/" + x + " compiled");
            assertTrue((Long) p.diagnostics().get("compiledEntries") > before); valid(original); valid(host); assertEquals(targets, active(host, original)); for (var target : targets) valid(target); released(language);
        }
    }
    @Test void typedPairPapScalarSuffixCapturedOverapplicationAndResultLoans() throws ReflectiveOperationException {
        for (boolean inlining : new boolean[]{true, false}) withLanguage(inlining, (context, language) -> {
            var data = module(
                bind("worker", lam(List.of(arg("p", pair), arg("z")), unpack(v("p", pair), pair, List.of("a", "b"), prim("+#", prim("+#", v("a"), v("b")), v("z"))))),
                bind("identity", lam(List.of(arg("p", pair)), v("p", pair), pair)),
                bind("make", lam(List.of(arg("p", pair)), unpack(v("p", pair), pair, List.of("a", "b"), lam(List.of(arg("z")), prim("+#", prim("+#", v("a"), v("b")), v("z"))), closure), closure)),
                bind("makeTyped", lam(List.of(arg("captured")), lam(List.of(arg("p", pair)), unpack(v("p", pair), pair, List.of("a", "b"), prim("+#", v("captured"), prim("+#", v("a"), v("b"))))), closure)),
                bind("captured", lam(List.of(arg("x")), call("makeTyped", List.of(n(13), pack(pair, v("x"), n(7)))))),
                bind("exact", lam(List.of(arg("x")), call("worker", List.of(pack(pair, v("x"), n(7)), n(13))))),
                bind("pap", lam(List.of(arg("x")), app(call("worker", List.of(pack(pair, v("x"), n(7))), closure), List.of(n(13))))),
                bind("prefix", lam(List.of(arg("x")), call("worker", List.of(pack(pair, v("x"), n(7))), closure), closure)),
                bind("over", lam(List.of(arg("x")), call("make", List.of(pack(pair, v("x"), n(7)), n(13))))),
                bind("roundTrip", lam(List.of(arg("x")), call("worker", List.of(call("identity", List.of(pack(pair, v("x"), n(7))), pair), n(13))))));
            var p = new BytecodeProgram(language, data); var pap = (Closure) run(p, "prefix", 9L);
            assertEquals(1, pap.suppliedCount); assertEquals(1, pap.arity); assertEquals(0, pap.supplied.length); assertNotNull(pap.typedSupplied); assertEquals(2, Objects.requireNonNull(pap.typedSupplied).getLayout().getReps().size());
            var root = (BytecodeRoot) p.entryTarget("worker").getRootNode(); assertEquals(2, Objects.requireNonNull(root.getInputLayout()).getLogicalArity()); assertEquals(3, Objects.requireNonNull(root.getInputLayout()).getPhysicalArity()); assertNotNull(root.getTypedInput());
            for (String name : List.of("exact", "pap", "over", "roundTrip", "captured")) checkCompiled(context, language, p, name, x -> x + 20L);
            assertThrows(RuntimeFault.class, () -> Calls.target(p.entryTarget("worker"), new Object[]{0L, 9L, 7L, 13L})); released(language);
        });
    }
    @Test void lexicalLongProofsTypeBareAndUnknownScalarOperandsBetweenTuplesAndPapSuffixes() throws ReflectiveOperationException {
        class Variables {
            List<Object> bare(String id) { return list("var", id); }
            List<Object> unknown(String id) { return list("var", id, map("rep", map("kind", "unknown", "primReps", null, "evaluated", false))); }
        }
        var vars = new Variables();
        for (boolean inlining : new boolean[]{true, false}) withLanguage(inlining, (context, language) -> {
            var worker = bind("worker", lam(List.of(arg("before"), arg("p", pair), arg("after")), unpack(v("p", pair), pair, List.of("a", "b"), prim("+#", prim("+#", vars.bare("before"), vars.unknown("after")), prim("+#", vars.bare("a"), vars.unknown("b"))))));
            var payload = pack(pair, v("x"), n(7)); var p = new BytecodeProgram(language, module(worker,
                bind("interleaved", lam(List.of(arg("x")), call("worker", List.of(vars.bare("x"), payload, vars.unknown("x"))))),
                bind("scalarPrefix", lam(List.of(arg("x")), app(call("worker", List.of(vars.bare("x")), closure), List.of(payload, vars.unknown("x"))))),
                bind("scalarSuffix", lam(List.of(arg("x")), app(call("worker", List.of(vars.bare("x"), payload), closure), List.of(vars.unknown("x")))))));
            for (String name : List.of("interleaved", "scalarPrefix", "scalarSuffix")) checkCompiled(context, language, p, name, x -> x * 3L + 7L);
        });
    }
    @Test void nestedFloatDoubleAndLazyReferenceLeavesStayTyped() throws ReflectiveOperationException {
        var inner = tuple(floating, doubleProof); var mixed = tuple(integer, inner, reference, state);
        for (boolean inlining : new boolean[]{true, false}) withLanguage(inlining, (context, language) -> {
            var body = unpack(v("p", mixed), mixed, List.of("x", "fd", "lazy", "s"), unpack(v("fd", inner), inner, List.of("f", "d"), prim("+#", v("x"), prim("+#", prim("float2Int#", v("f", floating)), prim("double2Int#", v("d", doubleProof))))));
            var p = new BytecodeProgram(language, module(bind("worker", lam(List.of(arg("p", mixed)), body)),
                bind("entry", lam(List.of(arg("x")), call("worker", List.of(pack(mixed, v("x"), pack(inner, list("lit", "float", "1.5", map("rep", floating)), list("lit", "double", "2.75", map("rep", doubleProof))),
                    lam(List.of(arg("unused")), prim("quotInt#", n(1), n(0))), list("void", map("rep", state)))))))));
            checkCompiled(context, language, p, "entry", x -> x + 3L);
        });
    }
    @Test void matchingSelfAndMutualTupleTransfersRestoreLocalsAndRetainBloom() throws ReflectiveOperationException {
        withLanguage((context, language) -> {
            class Worker {
                Map<String, Object> make(String id, String next) { return bind(id, lam(List.of(arg("p", pair), arg("n")), list("case", prim("<=#", v("n"), n(0)), "done", list(
                    list("lit", list("int", "1"), list(), v("p", pair)), list("default", null, list(), call(next, List.of(v("p", pair), prim("-#", v("n"), n(1))), pair))), map("rep", pair, "binder", arg("done"))), pair)); }
            }
            var worker = new Worker(); var p = new BytecodeProgram(language, module(worker.make("self", "self"), worker.make("a", "b"), worker.make("b", "a"),
                bind("entry", lam(List.of(arg("x")), unpack(call("a", List.of(call("self", List.of(pack(pair, v("x"), n(7)), n(1000)), pair), n(1000)), pair), pair, List.of("a", "b"), prim("+#", v("a"), v("b")))))));
            checkCompiled(context, language, p, "entry", x -> x + 7L); assertTrue((Long) p.diagnostics().get("selfTailReentries") > 0);
        });
    }
    private static final class OperandEffect extends GuestRoot {
        private final List<Long> events; private final TupleShape shape;
        OperandEffect(Language language, List<Long> events, TupleShape shape) { super(language, new FrameLayout().build()); this.events = events; this.shape = shape; configureEntry(new boolean[]{false}, false); configureTupleResult(shape); }
        @Override public long bloom(VirtualFrame frame) { return 0L; }
        @Override public Object execute(VirtualFrame frame) {
            long x = (Long) frame.getArguments()[1]; events.add(shape == null ? x + 100L : x); if (x < 0L) throw new GuestException(x, this);
            if (shape == null) return x + 100L; var result = shape.finish(frame, ArgumentLayout.EMPTY_TUPLE_SLOTS); return result != null ? result : x + 100L;
        }
    }
    @Test void zeroWidthStateTupleOperandsExecuteBeforeLaterArgumentsAndLoanAcquisition() throws ReflectiveOperationException {
        withLanguage((context, language) -> {
            var zero = tuple(state, tuple()); var p = new BytecodeProgram(language, module(bind("worker", lam(List.of(arg("unused", zero), arg("n")), v("n"))),
                bind("entry", lam(List.of(arg("effect", closure), arg("later", closure), arg("x")), call("worker", List.of(call("effect", List.of(v("x")), zero), call("later", List.of(v("x")))))))));
            var events = new ArrayList<Long>(); var effect = new Closure(null, 1, new OperandEffect(language, events, new TupleShape(CoreRepresentations.INSTANCE.parse(zero), language)).getCallTarget());
            var later = new Closure(null, 1, new OperandEffect(language, events, null).getCallTarget());
            assertEquals(107L, run(p, "entry", effect, later, 7L)); assertEquals(List.of(7L, 107L), events); released(language);
            var entry = Objects.requireNonNull(((GuestRoot) p.entryTarget("worker").getRootNode()).getTypedInput()); assertEquals(2, entry.getLogical().getLogicalArity()); assertEquals(1, entry.getLogical().getPhysicalArity());
            events.clear(); assertThrows(GuestException.class, () -> run(p, "entry", effect, later, -1L)); assertEquals(List.of(-1L), events); released(language);
            events.clear(); assertEquals(109L, run(p, "entry", effect, later, 9L)); assertEquals(List.of(9L, 109L), events); released(language);
        });
    }
    @Test void failedReferenceRestoreReleasesInputWithoutTouchingOutstandingResult() throws ReflectiveOperationException {
        withLanguage((context, language) -> {
            var data = map("kind", "data", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true); var inputProof = tuple(data);
            var p = new BytecodeProgram(language, module(bind("worker", lam(List.of(arg("p", inputProof)), unpack(v("p", inputProof), inputProof, List.of("d"), v("d", data), data), data))));
            var target = p.entryTarget("worker"); var entry = Objects.requireNonNull(((GuestRoot) target.getRootNode()).getTypedInput()); var handoff = language.getHandoffState().get();
            var resultShape = new TupleShape(CoreRepresentations.INSTANCE.parse(tuple(reference, integer)), language); var result = handoff.getResults().acquire(resultShape.getLayout()); var sentinel = new Object();
            resultShape.getLayout().setObject(result, 0, sentinel); resultShape.getLayout().setLong(result, 1, 123L);
            var loan = handoff.getArguments().acquire(entry.getPacket()); loan.setInputMode(1); entry.getPacket().setLong(loan, 0, 0L); entry.getPacket().setObject(loan, entry.getHeader(), "not a constructor");
            assertThrows(RuntimeFault.class, () -> TypedInputsKt.invokeTypedInput(target, loan, packet -> Calls.target(target, packet)));
            assertNull(handoff.getPending()); assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getArguments().retainedReferences());
            assertEquals(1, handoff.getResults().getDepth()); assertSame(sentinel, resultShape.getLayout().getObject(result, 0)); assertEquals(123L, resultShape.getLayout().getLong(result, 1));
            handoff.getResults().release(result, resultShape.getLayout()); released(language);
        });
    }
    @Test void genericDispatchKeepsTypedInputsAfterFiveTargets() throws ReflectiveOperationException {
        for (boolean inlining : new boolean[]{true, false}) withLanguage(inlining, (context, language) -> {
            var workers = new ArrayList<Map<String, Object>>();
            for (int i = 0; i <= 4; i++) workers.add(bind("worker" + i, lam(List.of(arg("p", pair)), unpack(v("p", pair), pair, List.of("a", "b"), prim("+#", prim("+#", v("a"), v("b")), n(i))))));
            var entry = bind("entry", lam(List.of(arg("f", closure), arg("x")), call("f", List.of(pack(pair, v("x"), n(7)))))); workers.add(entry);
            @SuppressWarnings("unchecked") Map<String, Object>[] bindings = workers.toArray(Map[]::new); var p = new BytecodeProgram(language, module(bindings));
            var functions = new ArrayList<Object>(); for (int i = 0; i <= 4; i++) functions.add(p.entryValue("worker" + i)); var values = List.of(Long.MIN_VALUE, -3L, 0L, Long.MAX_VALUE);
            // Prime the finite PIC with every target before its generic path.
            for (int i = 0; i < functions.size(); i++) assertEquals(7L + i, run(p, "entry", functions.get(i), 0L));
            for (long x : values) for (int i = 0; i < functions.size(); i++) assertEquals(x + 7L + i, run(p, "entry", functions.get(i), x));
            var target = p.entryTarget("entry"); target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target);
            for (long x : values.reversed()) for (int i = 0; i < functions.size(); i++) {
                long before = (Long) p.diagnostics().get("compiledEntries"); assertEquals(x + 7L + i, run(p, "entry", functions.get(i), x)); valid(target);
                assertTrue((Long) p.diagnostics().get("compiledEntries") > before); released(language);
            }
            assertTrue((Long) p.diagnostics().get("indirectCalls") > 0);
        });
    }
    @Test void hiddenTupleCaseProofCannotBeLiftedOrPassedToScalarPrimitive() throws ReflectiveOperationException {
        withLanguage((context, language) -> {
            var hidden = list("case", n(1), "ignored", list(list("default", null, list(), pack(pair, n(3), n(5))))); var worker = bind("worker", lam(List.of(arg("p", pair)), n(9)));
            var lazyFailure = assertThrows(UnsupportedCore.class, () -> new BytecodeProgram(language, module(worker, bind("bad", lam(List.of(arg("f", closure), arg("x")), app(v("f", closure), List.of(hidden), integer, List.of(true)))))));
            assertTrue(Objects.requireNonNull(lazyFailure.getMessage()).contains("thunk")); String previous = System.getProperty(CALL_DEMANDS_PROPERTY);
            try {
                System.setProperty(CALL_DEMANDS_PROPERTY, "true"); var demanded = new ArrayList<>(app(v("f", closure), List.of(hidden), integer, List.of(true)));
                demanded.set(6, map("rep", integer, "callDemand", map("arity", 1, "strictArgs", list(true))));
                var failure = assertThrows(RuntimeFault.class, () -> new BytecodeProgram(language, module(worker, bind("badDemand", lam(List.of(arg("f", closure), arg("x")), demanded)))));
                assertTrue(Objects.requireNonNull(failure.getMessage()).contains("Tuple argument cannot be lifted"));
            } finally { if (previous == null) System.clearProperty(CALL_DEMANDS_PROPERTY); else System.setProperty(CALL_DEMANDS_PROPERTY, previous); }
            assertThrows(RuntimeException.class, () -> new BytecodeProgram(language, module(bind("bad", lam(List.of(arg("x")), prim("+#", hidden, n(1)))))));
            var unknownArm = list("app", v("f", closure), list(n(1)), list(false), false, false);
            var mixedCase = list("case", v("x"), "which", list(list("lit", list("int", "0"), list(), pack(pair, n(3), n(5))), list("default", null, list(), unknownArm)));
            var missingProof = assertThrows(UnsupportedCore.class, () -> new BytecodeProgram(language, module(bind("badMixed", lam(List.of(arg("f", closure), arg("x")), mixedCase)))));
            assertTrue(Objects.requireNonNull(missingProof.getMessage()).contains("Missing exact aggregate case result proof"));
            var good = new BytecodeProgram(language, module(worker, bind("good", lam(List.of(arg("f", closure), arg("x")), call("f", List.of(hidden))))));
            assertEquals(9L, run(good, "good", good.entryValue("worker"), 0L)); released(language);
        });
    }
}
