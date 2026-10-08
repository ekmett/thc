// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExecutionSignature;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.Main.executionContext;

/** Independent demand-loader model: classification must never prepare or enter a head. */
class DemandPapTest {
    private static final Map<String, Object> LONG = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
    private static final Map<String, Object> FUNCTION = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", false);
    private static List<Object> node(Object... fields) { return Arrays.asList(fields); }
    @Test void coldCompiledPeekObservesPublicationWithoutPreparingTheCell() throws Exception {
        try (var context = executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var cell = new GlobalBinding("unopened");
                int[] preparations = {0}, compiled = {0};
                cell.defer(new PreparationLock(), () -> { preparations[0]++; return 17L; });
                var target = new RootNode(null) {
                    @Override public ExecutionSignature prepareForAOT() { return ExecutionSignature.create(Long.class, new Class<?>[0]); }
                    @Override public Object execute(VirtualFrame frame) {
                        if (CompilerDirectives.inCompiledCode()) compiled[0]++;
                        Object value = cell.peek();
                        return value == null ? -1L : value;
                    }
                }.getCallTarget();
                var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
                assertEquals(true, type.getMethod("prepareForAOT").invoke(target));
                type.getMethod("compile", boolean.class).invoke(target, true);
                type.getMethod("waitForCompilation").invoke(target);
                var runtime = Truffle.getRuntime();
                runtime.getClass().getMethod("bypassedInstalledCode", type).invoke(runtime, target);
                assertEquals(true, type.getMethod("isValidLastTier").invoke(target));
                assertEquals(-1L, Calls.target(target, new Object[0]));
                assertEquals(0, preparations[0]);
                assertEquals(17L, cell.read());
                assertEquals(17L, Calls.target(target, new Object[0]));
                assertEquals(2, compiled[0]);
                assertEquals(1, preparations[0]);
                assertEquals(true, type.getMethod("isValidLastTier").invoke(target));
            } finally { context.leave(); }
        }
    }
    @Test void coldCompiledBytecodePredicateObservesPublicationWithoutEnteringTheHead() throws Exception {
        try (var context = executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var head = new Closure(null, 2, new RootNode(language) {
                    @Override public Object execute(VirtualFrame frame) { throw new AssertionError("PAP classification entered its head"); }
                }.getCallTarget());
                int[] preparations = {0}; var cell = new GlobalBinding("unopened");
                cell.defer(new PreparationLock(), () -> { preparations[0]++; return head; });
                var metrics = new Metrics(true);
                var target = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                    b.beginRoot(); b.emitEnterRoot(metrics);
                    b.beginReturn(); b.emitCanConstructPap(cell, 1); b.endReturn(); b.endRoot();
                }).getNode(0).getCallTarget();
                var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
                type.getMethod("compile", boolean.class).invoke(target, true);
                type.getMethod("waitForCompilation").invoke(target);
                var runtime = Truffle.getRuntime();
                runtime.getClass().getMethod("bypassedInstalledCode", type).invoke(runtime, target);
                assertEquals(true, type.getMethod("isValidLastTier").invoke(target));
                assertEquals(false, Calls.target(target, new Object[0]));
                assertEquals(1L, metrics.getCompiledEntries()); assertEquals(0, preparations[0]);
                assertSame(head, cell.read());
                assertEquals(true, Calls.target(target, new Object[0]));
                assertEquals(2L, metrics.getCompiledEntries()); assertEquals(1, preparations[0]);
                assertEquals(true, type.getMethod("isValidLastTier").invoke(target));
            } finally { context.leave(); }
        }
    }
    private static Map<String, Object> binder(String id, Map<String, Object> rep) {
        return Map.of("id", id, "name", id, "lifted", rep == FUNCTION, "coercion", false, "rep", rep);
    }
    private static List<Object> variable(String id, Map<String, Object> rep) { return node("var", id, Map.of("rep", rep)); }
    private static List<Object> number(long value) { return node("lit", "int", Long.toString(value), Map.of("rep", LONG)); }
    private static List<Object> lambda(List<Map<String, Object>> args, List<Object> body, Map<String, Object> result) {
        return node("lam", args, body, Map.of("rep", FUNCTION, "resultRep", result));
    }
    private static List<Object> apply(List<Object> head, List<?> args, List<Boolean> lifted, boolean certified, Map<String, Object> rep) {
        return node("app", head, args, lifted, certified, certified, Map.of("rep", rep));
    }
    private static Map<String, Object> binding(String id, int arity, List<Object> body) {
        return Map.of("id", id, "name", id, "lifted", true, "rep", FUNCTION, "arity", arity, "expr", body);
    }
    private static List<Object> bottom(List<Object> body, Map<String, Object> result) {
        var failure = apply(node("prim", "quotInt#"), List.of(number(1), number(0)), List.of(false, false), false, LONG);
        return node("case", failure, "fault", List.of(node("default", null, List.of(), body)),
            Map.of("rep", result, "binder", binder("fault", LONG)));
    }
    private static Map<String, Map<String, Object>> definitions(String kind, boolean certified) {
        var suffix = lambda(List.of(binder("answer", LONG)), variable("answer", LONG), LONG);
        var head = kind.equals("short") ? lambda(List.of(binder("prefix", FUNCTION)), bottom(suffix, FUNCTION), FUNCTION)
            : lambda(List.of(binder("prefix", FUNCTION), binder("answer", LONG)), variable("answer", LONG), LONG);
        if (kind.equals("caf")) head = bottom(head, FUNCTION);
        // Even the safe PAP must not demand this ignored prefix.
        var prefix = bottom(lambda(List.of(binder("identity", LONG)), variable("identity", LONG), LONG), FUNCTION);
        var partial = apply(variable("head", FUNCTION), List.of(prefix), List.of(true), certified, FUNCTION);
        var caller = lambda(List.of(binder("input", LONG)), apply(variable("ignore", FUNCTION),
            List.of(partial, variable("input", LONG)), List.of(true, false), false, LONG), LONG);
        var ignore = lambda(List.of(binder("ignored", FUNCTION), binder("value", LONG)), variable("value", LONG), LONG);
        return Map.of("head", binding("head", 2, head), "caller", binding("caller", 1, caller), "ignore", binding("ignore", 2, ignore));
    }
    private static final class Fixture {
        final Language language; final String backend; final boolean async;
        final Map<String, Integer> reads = new HashMap<>();
        final CoreDemandBindings demand;
        Fixture(Language language, String backend, boolean async, Map<String, Map<String, Object>> definitions) {
            this.language = language; this.backend = backend; this.async = async;
            demand = new CoreDemandBindings(definitions::containsKey, id -> {
                reads.merge(id, 1, Integer::sum); return definitions.get(id);
            }, id -> null, this::prepare, true, definitions::containsKey);
        }
        private ExecutableProgram prepare(String id, Map<String, Object> binding) {
            var module = Map.<String, Object>of("bindings", List.of(binding), "constructors", List.of(), "demandBindings", demand);
            return backend.equals("ast") ? new Program(language, module, async) : new BytecodeProgram(language, module, null, async);
        }
        Object run() {
            var program = demand.program("caller");
            return Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue("caller"), new Object[]{7L}});
        }
        long count(String field) { return ((Number) demand.program("caller").diagnostics().get(field)).longValue(); }
    }
    @ParameterizedTest
    @CsvSource({"ast,false,false", "ast,false,true", "ast,true,false", "ast,true,true",
        "bytecode,false,false", "bytecode,false,true", "bytecode,true,false", "bytecode,true,true"})
    void publishedHeadRefinesBothPreparationOrdersWithoutLoadingItForClassification(String backend, boolean async, boolean early) throws Exception {
            try (var context = executionContext(false)) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var fixture = new Fixture(language, backend, async, definitions("full", true));
                    if (early) assertInstanceOf(Closure.class, fixture.demand.cell("head").read());
                    var target = fixture.demand.program("caller").entryTarget("caller");
                    if (!early) {
                        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                        target.getClass().getMethod("waitForCompilation").invoke(target);
                        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                        // Reinstall HotSpot's call-boundary stub, without executing the guest.
                        var runtime = Truffle.getRuntime();
                        runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"))
                            .invoke(runtime, target);
                        // The predicate's cold installed path is checked independently above;
                        // the full numeric caller also contains adaptive conversion opcodes.
                        assertEquals(7L, fixture.run());
                        assertEquals(0, fixture.reads.getOrDefault("head", 0), "Ignored cold PAP must not decode its head");
                        assertEquals(0L, fixture.count("papAllocations"));
                        assertInstanceOf(Closure.class, fixture.demand.cell("head").read());
                    }
                    long paps = fixture.count("papAllocations");
                    assertEquals(7L, fixture.run());
                    assertEquals(paps + 1, fixture.count("papAllocations"), backend + "/" + async + "/early=" + early);
                    assertEquals(7L, fixture.run());
                    assertEquals(paps + 2, fixture.count("papAllocations"));
                    assertEquals(0L, fixture.count("thunkEvaluations"));
                    assertEquals(1, fixture.reads.get("head"));
                    assertSame(target, fixture.demand.program("caller").entryTarget("caller"));
                } finally { context.leave(); }
            }
    }
    @ParameterizedTest
    @CsvSource({"ast,false,short", "ast,false,caf", "ast,false,uncertified", "ast,true,short", "ast,true,caf", "ast,true,uncertified",
        "bytecode,false,short", "bytecode,false,caf", "bytecode,false,uncertified", "bytecode,true,short", "bytecode,true,caf", "bytecode,true,uncertified"})
    void shortenedLambdaCafAndUncertifiedApplicationsRemainLazy(String backend, boolean async, String kind) {
            try (var context = executionContext(false)) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var fixture = new Fixture(language, backend, async, definitions(kind, !kind.equals("uncertified")));
                    Object head = fixture.demand.cell("head").read();
                    if (kind.equals("caf")) assertInstanceOf(Thunk.class, head);
                    else assertEquals(kind.equals("short") ? 1 : 2, assertInstanceOf(Closure.class, head).arity);
                    for (int i = 0; i < 3; i++) assertEquals(7L, fixture.run());
                    assertEquals(0L, fixture.count("thunkEvaluations"));
                    assertEquals(0L, fixture.count("papAllocations"));
                    assertEquals(1, fixture.reads.get("head"));
                } finally { context.leave(); }
            }
    }
}
