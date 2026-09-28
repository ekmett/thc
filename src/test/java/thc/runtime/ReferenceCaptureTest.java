// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import thc.Language;
import thc.Main;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.RepresentationTestSupport.*;

class ReferenceCaptureTest {
    private final Map<String, Object> wide = map("kind", "long", "primReps", list("IntRep"), "evaluated", true);
    private final Map<String, Object> data = map("kind", "data", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", false);
    private final Map<String, Object> closure = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
    private final Map<String, Object> address = map("kind", "address", "primReps", list("AddrRep"), "evaluated", true);
    private List<Object> variable(String id) { return list("var", id); }
    private List<Object> integer(long n) { return list("lit", "int", Long.toString(n)); }
    private Map<String, Object> parameter(String id) { return parameter(id, wide); }
    private Map<String, Object> parameter(String id, Map<String, Object> rep) { return map("id", id, "name", id, "lifted", rep == data || rep == closure, "coercion", false, "rep", rep); }
    private List<Object> lambda(List<Map<String, Object>> args, List<Object> body) { return lambda(args, body, wide); }
    private List<Object> lambda(List<Map<String, Object>> args, List<Object> body, Map<String, Object> result) { return list("lam", args, body, map("rep", closure, "resultRep", result)); }
    private Map<String, Object> binding(String id, List<Object> rhs) { return binding(id, rhs, closure); }
    private Map<String, Object> binding(String id, List<Object> rhs, Map<String, Object> rep) { return with(parameter(id, rep), "expr", rhs); }
    private List<Object> apply(List<Object> fn, List<List<Object>> args) { return apply(fn, args, Collections.nCopies(args.size(), false)); }
    private List<Object> apply(List<Object> fn, List<List<Object>> args, List<Boolean> lifted) { return list("app", fn, args, lifted, false, false); }
    @SafeVarargs private final List<Object> primitive(String name, List<Object>... args) { return apply(list("prim", name), list(args)); }
    private List<Object> box(List<Object> n) { return list("app", list("con", "Box", 1), list(n), list(false), true, true, map("rep", with(data, "evaluated", true))); }
    private List<Object> unbox(List<Object> value) { return unbox(value, variable("payload")); }
    private List<Object> unbox(List<Object> value, List<Object> body) { return list("case", value, "boxed", list(list("data", "Box", list("payload"), body, map("binders", list(parameter("payload"))))), map("binder", parameter("boxed", data))); }
    private List<Object> choose(List<Object> value, List<Object> zero, List<Object> other) { return list("case", value, "choice", list(list("lit", list("int", "0"), list(), zero), list("default", null, list(), other))); }
    private List<Object> local(List<Map<String, Object>> group, List<Object> body) { return local(group, body, false); }
    private List<Object> local(List<Map<String, Object>> group, List<Object> body, boolean recursive) { return list("let", recursive, group, body); }
    private long count(ExecutableProgram p, String name) { return ((Number) p.diagnostics().get(name)).longValue(); }
    private Object call(ExecutableProgram p, Object fn, Object... args) { return Calls.target(p.hostEntryTarget(args.length), new Object[]{fn, args}); }
    private void compile(RootCallTarget target) throws Exception { var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"); type.getMethod("compile", boolean.class).invoke(target, true); assertEquals(true, type.getMethod("isValidLastTier").invoke(target)); }
    private void inLanguage(CheckedConsumer<Language> action) throws Exception {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try { action.accept(TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); }
        }
    }
    private void eachBackend(List<Map<String, Object>> bindings, CheckedBiConsumer<String, ExecutableProgram> action) throws Exception {
        inLanguage(language -> {
            for (var backend : list("ast", "bytecode")) {
                var module = map("bindings", bindings, "instrument", true, "constructors", list(map("id", "Box", "name", "Box", "arity", 1, "kind", "boxed", "fieldReps", list(list("IntRep")), "strictFields", list(false), "fieldLifted", list(false))));
                action.accept(backend, backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module));
            }
        });
    }
    @Test void preciseReferenceAndPrimitiveCapturesRoundTripThroughBothStorageEntrypoints() throws Exception {
        inLanguage(language -> {
            var target = new RootNode(null) { @Override public Object execute(VirtualFrame frame) { return 0L; } }.getCallTarget();
            var constructor = new DataLayout(language, "Box", "Box", new String[]{"IntRep"}); var boxed = constructor.create(new Object[]{3_000_000_017L});
            var function = new Closure(null, 1, target); var literal = ManagedAddress.fromHex("41ff"); var thunk = new Thunk(target, null); var cell = new RecCell();
            Object[] values = {boxed, function, literal, Long.MIN_VALUE, thunk, cell};
            // Exact reference proof supersedes legacy adaptive primitive eligibility.
            var captures = new CaptureLayout(language, new boolean[]{true, true, false, true, false, false}, new boolean[]{false, false, false, true, false, false}, new Class<?>[]{DataValue.class, Closure.class, ManagedAddress.class, null, null, null});
            var layout = new FrameLayout(); var slots = new int[values.length]; for (int i = 0; i < values.length; i++) slots[i] = layout.bind("capture" + i);
            var descriptor = layout.build(); var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor);
            for (int i = 0; i < values.length; i++) FrameAccess.write(frame, slots[i], values[i]);
            for (var environment : list(captures.capture(frame, slots), captures.captureValues(values))) {
                var restored = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor);
                for (int i = 0; i < values.length; i++) {
                    captures.restore(environment, i, restored, slots[i]);
                    if (i == 3) { assertTrue(environment.isLong(i)); assertEquals(Long.MIN_VALUE, restored.getLong(slots[i])); }
                    else { assertTrue(environment.isObject(i)); assertSame(values[i], environment.getObject(i)); assertSame(values[i], FrameAccess.read(restored, slots[i])); }
                }
                assertEquals(255L, ((ManagedAddress) environment.getObject(2)).indexChar(1)); assertFalse(cell.getInitialized()); assertEquals(0, thunk.getState());
            }
            assertThrows(RuntimeException.class, () -> { var wrong = values.clone(); wrong[0] = thunk; captures.captureValues(wrong); });
            assertThrows(IllegalArgumentException.class, () -> new CaptureLayout(language, new boolean[]{true}, new boolean[]{true}, new Class<?>[]{DataValue.class}));
        });
    }
    private void check(ExecutableProgram p, String backend, Closure fn, long base, long index) { assertEquals(base + base + index + ((index & 1L) == 0L ? 65 : 255), call(p, fn, index), backend); }
    @Test void compiledEscapedClosuresKeepDataFunctionsAndManagedAddressesPrecise() throws Exception {
        var add = lambda(list(parameter("extra")), primitive("+#", variable("input"), variable("extra")));
        var body = primitive("+#", unbox(variable("tree")), primitive("+#", apply(variable("add"), list(variable("index"))), primitive("indexCharOffAddr#", variable("bytes"), primitive("andI#", variable("index"), integer(1)))));
        var maker = lambda(list(parameter("input")), local(list(binding("tree", box(variable("input")), data), binding("add", add), binding("bytes", list("lit", "string-bytes", "41ff", map("rep", address)), address)), lambda(list(parameter("index")), body)), closure);
        eachBackend(list(binding("make", maker)), (backend, p) -> {
            var a = (Closure) call(p, p.entryValue("make"), 3_000_000_017L); var b = (Closure) call(p, p.entryValue("make"), -7_000_000_003L); assertSame(a.target, b.target, backend);
            for (int i = 0; i < 30; i++) { check(p, backend, a, 3_000_000_017L, i); check(p, backend, b, -7_000_000_003L, i); }
            compile(a.target);
            for (long index : new long[]{Long.MIN_VALUE, Long.MAX_VALUE, 0L, 1L}) { check(p, backend, a, 3_000_000_017L, index); check(p, backend, b, -7_000_000_003L, index); }
            assertEquals(0L, count(p, "thunkEvaluations"), backend);
        });
    }
    @Test void lazyAndCaseRefinedRecursiveCapturesRetainTheirPhysicalStorage() throws Exception {
        var nested = lambda(list(parameter("delta")), unbox(variable("shared"), primitive("+#", variable("payload"), variable("delta"))));
        var reader = lambda(list(parameter("extra")), unbox(variable("shared"), local(list(binding("nested", nested)), apply(variable("nested"), list(variable("extra"))))));
        var recursiveGroup = list(binding("shared", box(variable("input")), data), binding("reader", reader));
        var makeRecursive = binding("recursive", lambda(list(parameter("input")), local(recursiveGroup, variable("reader"), true), closure));
        var makePublished = binding("published", lambda(list(parameter("input")), local(recursiveGroup, unbox(variable("shared"), nested), true), closure));
        var ignoreOrForce = lambda(list(parameter("n")), choose(variable("n"), integer(7), unbox(variable("tree"))));
        var lazyFactory = binding("lazyFactory", lambda(list(parameter("tree", data)), ignoreOrForce, closure));
        var unsafeBox = apply(list("con", "Box", 1), list(primitive("quotInt#", integer(1), variable("input"))));
        var lazyEntry = binding("lazy", lambda(list(parameter("input")), apply(variable("lazyFactory"), list(unsafeBox), list(true)), closure));
        eachBackend(list(makeRecursive, makePublished, lazyFactory, lazyEntry), (backend, p) -> {
            var a = (Closure) call(p, p.entryValue("recursive"), 3_000_000_017L); var b = (Closure) call(p, p.entryValue("recursive"), -7_000_000_003L); assertEquals(0L, count(p, "thunkEvaluations"), backend);
            for (int i = 0; i < 30; i++) { assertEquals(3_000_000_017L + i, call(p, a, (long) i), backend); assertEquals(-7_000_000_003L + i, call(p, b, (long) i), backend); }
            assertEquals(2L, count(p, "thunkEvaluations"), backend); compile(a.target); assertEquals(3_000_000_017L + Long.MAX_VALUE, call(p, a, Long.MAX_VALUE), backend);
            var published = (Closure) call(p, p.entryValue("published"), Long.MIN_VALUE); assertEquals(3L, count(p, "thunkEvaluations"), backend);
            for (int i = 0; i < 30; i++) assertEquals(Long.MIN_VALUE + i, call(p, published, (long) i), backend); compile(published.target); assertEquals(-1L, call(p, published, Long.MAX_VALUE), backend);
            var lazy = (Closure) call(p, p.entryValue("lazy"), 0L); for (int i = 0; i < 30; i++) assertEquals(7L, call(p, lazy, 0L), backend); compile(lazy.target);
            assertEquals(3L, count(p, "thunkEvaluations"), backend + " must not force a captured bottom"); assertThrows(ArithmeticException.class, () -> call(p, lazy, 1L));
        });
    }
}
