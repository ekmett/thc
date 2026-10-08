// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreExecutionTestSupport.*;

/** Source/lowering counters are independent of guest-entry instrumentation. */
class LazyBindingTest {
    private static final class ColdCellRoot extends RootNode {
        private final GlobalBinding binding; private final long[] compiled;
        ColdCellRoot(GlobalBinding binding, long[] compiled) { super(null); this.binding = binding; this.compiled = compiled; }
        @Override public ExecutionSignature prepareForAOT() { return ExecutionSignature.create(Long.class, new Class<?>[0]); }
        @Override public Object execute(VirtualFrame frame) {
            if (CompilerDirectives.inCompiledCode()) compiled[0]++;
            Object result = binding.read();
            if (CompilerDirectives.inCompiledCode()) compiled[1]++;
            if (compiled.length > 2 && CompilerDirectives.inCompiledCode() &&
                    CompilerDirectives.isPartialEvaluationConstant(result)) compiled[2]++;
            return result;
        }
    }
    private final Map<String, Object> formal = map("id", "x", "name", "x", "type", "Int#", "lifted", false, "coercion", false);
    private CoreBindingBody lambda(Supplier<List<Object>> body) {
        return new CoreBindingBody(new CoreBindingBody.Header(3, Map.of(0, "lam", 1, list(formal)), false), null, () -> list("lam", list(formal), body.get()));
    }
    private Map<String, Object> binding(String id, List<Object> body) { return binding(id, body, 1, true); }
    private Map<String, Object> binding(String id, List<Object> body, int arity) { return binding(id, body, arity, true); }
    private Map<String, Object> binding(String id, List<Object> body, int arity, boolean lifted) {
        return map("id", id, "name", id, "type", "Synthetic", "lifted", lifted, "arity", arity, "expr", body);
    }
    private Map<String, Object> module(List<Map<String, Object>> bindings) {
        return map("schema", 1, "ghc", "9.14.1", "module", "Synthetic.LazyBinding", "sourceNotesEnabled", false,
            "instrument", true, "bindings", bindings, "constructors", list());
    }
    @FunctionalInterface private interface Action { void run(Language language, String backend, boolean async) throws Exception; }
    private void bothBackends(Action action) throws Exception {
        for (String backend : list("ast", "bytecode")) for (boolean async : list(false, true)) try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try { action.run(TruffleLanguage.LanguageReference.create(Language.class).get(null), backend, async); }
            finally { context.leave(); }
        }
    }
    private ExecutableProgram program(Language language, String backend, boolean async, Map<String, Object> data) {
        return backend.equals("ast") ? new Program(language, data, async, false) : new BytecodeProgram(language, data, null, async);
    }
    private long count(ExecutableProgram program, String name) { return ((Number) program.diagnostics().get(name)).longValue(); }
    private Object invoke(ExecutableProgram program, Object value, Object... arguments) { return Calls.target(program.hostEntryTarget(arguments.length), new Object[]{value, arguments}); }
    private long attempts(List<CoreBindingBody> sources) { long total = 0; for (var source : sources) total += source.decodeAttempts(); return total; }
    @Test void unselectedNativeFunctionLabelDoesNotResolveButDemandedMissingLabelFails() throws Exception {
        bothBackends((language, backend, async) -> {
            var address = map("kind", "address", "primReps", list("AddrRep"), "evaluated", true);
            var label = list("lit", "function-addr", "missing_optional_native_function", map("rep", address));
            var body = list("case", list("var", "x"), "choice", list(
                list("lit", list("int", "0"), list(), list("lit", "null-addr", "0", map("rep", address))),
                list("default", null, list(), label)), map("rep", address));
            var source = module(list(binding("entry", list("lam", list(formal), body))));
            var prepared = program(language, backend, async, source);
            var entry = prepared.entryValue("entry");
            assertSame(ManagedAddress.nullAddress(), invoke(prepared, entry, 0L));
            assertThrows(RuntimeFault.class, () -> invoke(prepared, entry, 1L));
            assertSame(ManagedAddress.nullAddress(), invoke(prepared, entry, 0L));
            for (var malformed : list(
                    list("lit", "function-addr", "", map("rep", address)),
                    list("lit", "function-addr", "missing_optional_native_function", map("rep", map("kind", "long", "primReps", list("IntRep")))))) {
                var invalid = module(list(binding("entry", list("lam", list(formal), malformed))));
                assertThrows(RuntimeFault.class, () -> program(language, backend, async, invalid).entryValue("entry"));
            }
        });
    }
    @Test void firstCompiledColdCellReadDoesNotRetireTheCaller() throws Exception {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var cell = new GlobalBinding("cold scalar"); int[] preparations = {0};
                cell.defer(new PreparationLock(), () -> { preparations[0]++; return 17L; });
                long[] compiled = new long[2]; var target = new ColdCellRoot(cell, compiled).getCallTarget();
                var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
                // Exact scalar signature; no execution or fabricated specialization history.
                assertEquals(true, type.getMethod("prepareForAOT").invoke(target));
                type.getMethod("compile", boolean.class).invoke(target, true);
                type.getMethod("waitForCompilation").invoke(target);
                assertEquals(true, type.getMethod("isValidLastTier").invoke(target));
                var runtime = Truffle.getRuntime(); runtime.getClass().getMethod("bypassedInstalledCode", type).invoke(runtime, target);
                assertEquals(0, preparations[0]); assertEquals(17L, Calls.target(target, new Object[0])); assertEquals(1, preparations[0]);
                assertEquals(1L, compiled[0], "the very first read must enter installed code");
                assertEquals(1L, compiled[1], "preparing the cold value must not deoptimize its caller");
                assertEquals(true, type.getMethod("isValidLastTier").invoke(target));
                for (int i = 0; i < 32; i++) assertEquals(17L, Calls.target(target, new Object[0]));
                assertEquals(1, preparations[0]); assertEquals(33L, compiled[0]); assertEquals(33L, compiled[1]);
                assertEquals(true, type.getMethod("isValidLastTier").invoke(target));
            } finally { context.leave(); }
        }
    }
    @Test void alreadyPreparedCellIsConstantAtFirstCompiledEntry() throws Exception {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var cell = new GlobalBinding("prepared scalar"); int[] preparations = {0};
                cell.defer(new PreparationLock(), () -> { preparations[0]++; return 17L; });
                assertEquals(17L, cell.read()); // Inert preparation, not guest execution.
                long[] compiled = new long[3]; var target = new ColdCellRoot(cell, compiled).getCallTarget();
                var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
                assertEquals(true, type.getMethod("prepareForAOT").invoke(target));
                type.getMethod("compile", boolean.class).invoke(target, true);
                type.getMethod("waitForCompilation").invoke(target);
                assertEquals(true, type.getMethod("isValidLastTier").invoke(target));
                assertEquals(17L, Calls.target(target, new Object[0]));
                assertArrayEquals(new long[]{1, 1, 1}, compiled,
                    "published linkage must fold without executing or retraining the guest root");
                assertEquals(1, preparations[0]);
                assertEquals(true, type.getMethod("isValidLastTier").invoke(target));
            } finally { context.leave(); }
        }
    }
    @Test void unusedBodiesStayUndecodedAndEachDemandIsMemoized() throws Exception { bothBackends((language, backend, async) -> {
        for (int size : new int[]{32, 64}) {
            var sources = new ArrayList<CoreBindingBody>();
            for (int i = 0; i < size; i++) sources.add(lambda(() -> list("var", "x")));
            long[] reads = new long[size]; var headers = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < size; i++) {
                int index = i; var header = binding("f" + i, sources.get(i));
                headers.add(new AbstractMap<>() {
                    @Override public Object get(Object key) { reads[index]++; return header.get(key); }
                    @Override public Set<Entry<String, Object>> entrySet() { return header.entrySet(); }
                    @Override public boolean containsKey(Object key) { return header.containsKey(key); }
                });
            }
            var program = program(language, backend, async, module(headers)); long[] before = reads.clone();
            assertEquals(0L, attempts(sources), backend + "/" + async + "/" + size + " pre-entry decode");
            assertEquals(0L, count(program, "initializedBindingCount")); assertEquals(0L, count(program, "loweredRootCount"));
            assertEquals(0L, count(program, "hostEntryRootCount"));
            var first = program.entryValue("f0"); assertTrue(first instanceof Closure); assertSame(first, program.entryValue("f0"));
            assertEquals(1L, attempts(sources)); assertEquals(1L, count(program, "initializedBindingCount"));
            for (int i = 1; i < size; i++) assertEquals(before[i], reads[i], "one demand must not rebuild the full global header index");
            var second = program.entryValue("f" + (size - 1)); assertNotSame(first, second);
            assertEquals(2L, attempts(sources)); assertEquals(2L, count(program, "initializedBindingCount"));
            assertEquals(Long.MIN_VALUE, invoke(program, first, Long.MIN_VALUE)); assertEquals(Long.MAX_VALUE, invoke(program, second, Long.MAX_VALUE));
            assertEquals(2L, attempts(sources), "guest execution must not reload prepared bodies");
        }
    }); }
    @Test void demandedInputChecksKeepTheFullGlobalHeaderEnvironment() throws Exception { bothBackends((language, backend, async) -> {
        var scalar = map("kind", "long", "evaluated", true, "primReps", list("IntRep"));
        var tuple = map("kind", "unknown", "evaluated", true, "aggregate", "unboxed-tuple", "components", list(scalar, scalar), "primReps", list("IntRep", "IntRep"));
        var targetFormals = list(with(formal, "rep", tuple));
        var target = new CoreBindingBody(new CoreBindingBody.Header(3, Map.of(0, "lam", 1, targetFormals), false), null,
            () -> { throw new IllegalStateException("input validation must not decode the callee implementation"); });
        var caller = lambda(() -> list("app", list("var", "target"), list(list("lit", "int", "1")), list(false)));
        var program = program(language, backend, async, module(list(binding("caller", caller), binding("target", target))));
        var failure = assertThrows(RuntimeFault.class, () -> program.entryValue("caller"));
        assertTrue(Objects.toString(failure.getMessage(), "").contains("exact tuple argument proof"), failure.getMessage());
        assertEquals(0L, target.decodeAttempts()); assertEquals(0L, count(program, "initializedBindingCount"));
    }); }
    @Test void reachableMalformedBodyFailsOnceWhileUnusedBodyDoesNotBlockEntry() throws Exception { bothBackends((language, backend, async) -> {
        var good = lambda(() -> list("var", "x"));
        var broken = lambda(() -> { throw new IllegalArgumentException("retained malformed body"); });
        var invalid = lambda(() -> list("var", "missing"));
        var program = program(language, backend, async, module(list(binding("good", good), binding("broken", broken), binding("invalid", invalid))));
        assertEquals(7L, invoke(program, program.entryValue("good"), 7L)); assertEquals(0L, broken.decodeAttempts());
        var decodeFailure = assertThrows(IllegalArgumentException.class, () -> program.entryValue("broken"));
        assertSame(decodeFailure, assertThrows(IllegalArgumentException.class, () -> program.entryValue("broken"))); assertEquals(1L, broken.decodeAttempts());
        var admissionFailure = assertThrows(RuntimeFault.class, () -> program.entryValue("invalid"));
        assertTrue(Objects.toString(admissionFailure.getMessage(), "").contains("Unresolved external binding missing"));
        assertSame(admissionFailure, assertThrows(RuntimeFault.class, () -> program.entryValue("invalid")));
        assertEquals(1L, invalid.decodeAttempts()); assertEquals(1L, count(program, "initializedBindingCount"));
    }); }
    @Test void bytecodeDeferredFailureKeepsItsIdentityAndAddsOnlyItsExactOwner() {
        for (boolean async : list(false, true)) try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var cause = new IllegalArgumentException("original cause"); var previous = new IllegalStateException("original suppressed context");
                var original = new UnsupportedCore("original deferred failure"); original.initCause(cause); original.addSuppressed(previous);
                var stack = original.getStackTrace().clone(); var broken = lambda(() -> { throw original; });
                var cold = lambda(() -> { throw new IllegalStateException("unrequested body must stay cold"); });
                String id = "actual-unit:Original.Module.$worker";
                var program = new BytecodeProgram(language, module(list(binding(id, broken), binding("cold-unit:Cold.entry", cold))), null, async);
                assertEquals(0L, broken.decodeAttempts()); assertEquals(0L, cold.decodeAttempts());
                var failure = assertThrows(UnsupportedCore.class, () -> program.entryValue(id));
                assertSame(original, failure); assertEquals("original deferred failure", failure.getMessage()); assertSame(cause, failure.getCause());
                assertArrayEquals(stack, failure.getStackTrace()); assertEquals(2, failure.getSuppressed().length);
                assertSame(previous, failure.getSuppressed()[0]); assertEquals("While preparing Core binding " + id, failure.getSuppressed()[1].getMessage());
                assertSame(original, assertThrows(UnsupportedCore.class, () -> program.entryValue(id)));
                assertEquals(2, original.getSuppressed().length, "memoized failure must not acquire duplicate context");
                assertEquals(1L, broken.decodeAttempts()); assertEquals(0L, cold.decodeAttempts());
                assertEquals(0L, count(program, "initializedBindingCount")); assertEquals(0L, count(program, "loweredRootCount"));
            } finally { context.leave(); }
        }
    }
    @Test void codePreparationKeepsCafIdentityAndDoesNotEvaluateItsBottom() throws Exception { bothBackends((language, backend, async) -> {
        var bottom = new CoreBindingBody(new CoreBindingBody.Header(2, Map.of(0, "var", 1, "bottom"), false), null, () -> list("var", "bottom"));
        var program = program(language, backend, async, module(list(binding("bottom", bottom, 0))));
        var thunk = (Thunk) program.entryValue("bottom"); assertSame(thunk, program.entryValue("bottom")); assertEquals(0, thunk.getState());
        assertEquals(0L, count(program, "thunkEvaluations"));
        var failure = assertThrows(RuntimeFault.class, () -> invoke(program, thunk));
        assertTrue(Objects.toString(failure.getMessage(), "").contains("Blackhole"));
        assertSame(failure, assertThrows(RuntimeFault.class, () -> invoke(program, thunk))); assertEquals(1L, count(program, "thunkEvaluations"));
    }); }
    @Test void sourceSharingDoesNotShareExecutableClosuresBetweenPrograms() throws Exception { bothBackends((language, backend, async) -> {
        var body = lambda(() -> list("var", "x")); var data = module(list(binding("entry", body)));
        var first = program(language, backend, async, data); var second = program(language, backend, async, data);
        var firstClosure = (Closure) first.entryValue("entry"); var secondClosure = (Closure) second.entryValue("entry");
        assertNotSame(firstClosure, secondClosure); assertNotSame(firstClosure.target, secondClosure.target);
        assertEquals(1L, body.decodeAttempts(), "immutable source is shared, runtime roots are not");
        assertEquals(5L, invoke(first, firstClosure, 5L)); assertEquals(9L, invoke(second, secondClosure, 9L));
    }); }
    @Test void ordinaryAndStrictBindingsKeepEagerValidation() throws Exception { bothBackends((language, backend, async) -> {
        List<Object> ordinary = list("lam", list(formal), list("var", "missing"));
        assertThrows(RuntimeFault.class, () -> program(language, backend, async, module(list(binding("ordinary", ordinary)))));
        var strict = new CoreBindingBody(new CoreBindingBody.Header(3, Map.of(0, "lit", 1, "int", 2, "11"), false), null, () -> list("lit", "int", "11"));
        var program = program(language, backend, async, module(list(binding("strict", strict, 0, false))));
        assertEquals(1L, count(program, "initializedBindingCount")); assertEquals(11L, program.entryValue("strict"));
    }); }
    @Test void concurrentPreparationPublishesOnceAndSharesTheProgramLock() throws Exception {
        var lock = new PreparationLock(); var first = new GlobalBinding("first"); var second = new GlobalBinding("second");
        var preparations = new AtomicInteger(); var result = new Object();
        second.defer(lock, () -> { preparations.incrementAndGet(); return result; });
        first.defer(lock, () -> { preparations.incrementAndGet(); return second.read(); });
        var pool = Executors.newFixedThreadPool(4);
        try {
            var tasks = new ArrayList<Callable<Object>>();
            for (int i = 0; i < 32; i++) { int index = i; tasks.add(() -> index % 2 == 0 ? first.read() : second.read()); }
            for (var value : pool.invokeAll(tasks)) assertSame(result, value.get());
        } finally { pool.shutdown(); }
        assertEquals(2, preparations.get());
    }
}
