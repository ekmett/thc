// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.io.ByteArrayOutputStream;
import java.lang.ref.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreFormatTestSupport.*;

/** Cold source names are semantic references even before JVM nodes exist for them. */
@Timeout(60)
class CoreUnitCafLifetimeTest {
    @TempDir Path directory;
    @AfterEach void releaseIdleFixtureMappings() { CoreFileMappings.shared.evictIdleBelow(directory); }
    private final Map<String, Object> state = map("kind", "void", "primReps", List.of(), "evaluated", true);
    private final Map<String, Object> integer = map("kind", "long", "primReps", list("IntRep"), "evaluated", true);
    private final Map<String, Object> data = map("kind", "data", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", false);
    private final Map<String, Object> closure = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
    private List<Object> literal(int value) { return list("lit", "int", Integer.toString(value), map("rep", integer)); }
    private List<Object> box(int value) { return list("app", list("con", "uB:B.Box", 1, map()), list(literal(value)), list(false), true, true, map("rep", with(data, "evaluated", true))); }
    private List<Object> trace(String label, List<Object> body) {
        var bytes = HexFormat.of().formatHex(label.getBytes(StandardCharsets.UTF_8));
        return list("case", list("app", list("prim", "traceEvent#", map()), list(list("lit", "string-bytes", bytes, map()), list("void", map("rep", state))),
            list(false, false), false, false, map("rep", state)), "traced", list(list("default", null, List.of(), body, map("binders", List.of()))),
            map("rep", data, "binder", map("id", "traced", "lifted", false, "rep", state)));
    }
    private Map<String, Object> function(String id, List<Object> body) {
        return map("id", id, "name", id.substring(id.lastIndexOf('.') + 1), "type", "Int# -> Int#", "lifted", true, "arity", 1, "rep", closure,
            "expr", list("lam", list(map("id", "x", "name", "x", "type", "Int#", "lifted", false, "coercion", false, "rep", integer)), body,
                map("rep", closure, "resultRep", integer)));
    }
    private Map<String, Object> caf(String id, List<Object> body) { return map("id", id, "name", id.substring(id.lastIndexOf('.') + 1), "type", "Box", "lifted", true, "arity", 0, "rep", data, "expr", body); }
    private List<Object> readCaf(String id) {
        return list("case", list("var", id, map("rep", data)), "boxed", list(list("data", "uB:B.Box", list("payload"), list("var", "payload", map("rep", integer)),
            map("binders", list(map("id", "payload", "name", "payload", "type", "Int#", "lifted", false, "coercion", false, "rep", integer))))),
            map("rep", integer, "binder", map("id", "boxed", "lifted", true, "rep", data)));
    }
    private Map<String, Object> unit(String name, List<Map<String, Object>> bindings, List<Object> constructors) throws Exception {
        var module = map("schema", 1, "ghc", "9.14.1", "unit", "u" + name, "module", name,
            "boundary", "optimized-Core-after-Tidy-before-CorePrep", "constructors", constructors, "bindings", bindings);
        return map("id", "u" + name, "depends", List.of(), "modules",
            list(CoreCbdFixtures.module(directory.resolve(name + ".cbd"), module)));
    }
    private Path fixture() throws Exception {
        var a = unit("A", List.of(function("uA:A.entry", literal(7))), List.of());
        var raised = list("app", list("prim", "raise#", map()), list(box(23)), list(true), false, false, map("rep", data));
        var b = unit("B", List.of(caf("uB:B.value", trace("success", box(17))), caf("uB:B.failure", trace("failure", raised))),
            list(map("id", "uB:B.Box", "name", "Box", "kind", "boxed", "arity", 1, "tag", 1, "fieldReps", list(list("IntRep")), "fieldTypes", list(integer), "strictFields", list(false), "fieldLifted", list(false))));
        var c = unit("C", List.of(function("uC:C.success", readCaf("uB:B.value")), function("uC:C.failure", readCaf("uB:B.failure")),
            function("uC:C.successAgain", readCaf("uB:B.value")), function("uC:C.failureAgain", readCaf("uB:B.failure"))), List.of());
        return Files.writeString(directory.resolve("packages.json"), Json.stringify(map("format", "thc-core-packages", "schema", 1, "ghc", "9.14.1", "units", list(a, b, c))));
    }
    private long count(ExecutableProgram program, String name) { return ((Number) program.diagnostics().get(name)).longValue(); }
    private record Observed(WeakReference<Thunk> success, WeakReference<Thunk> failure, WeakReference<DataValue> result, WeakReference<DataValue> payload) {}
    private Observed evaluateAndDropHandles(ExecutableProgram program, Language language, boolean async) {
        var metrics = new Metrics(true);
        var force = new RootNode(language, new FrameLayout().build()) {
            @Child private Force evaluator = new Force(metrics, async);
            @Override public Object execute(VirtualFrame frame) { return evaluator.execute(frame, frame.getArguments()[0]); }
        }.getCallTarget();
        var success = (Thunk) program.entryValue("uB:B.value"); var failure = (Thunk) program.entryValue("uB:B.failure");
        var result = (DataValue) Calls.target(force, new Object[]{success}); assertEquals(17L, result.getLayout().readLong(result, 0));
        var thrown = assertThrows(GuestException.class, () -> Calls.target(force, new Object[]{failure}));
        var payload = (DataValue) thrown.getPayload(); assertEquals(23L, payload.getLayout().readLong(payload, 0));
        assertEquals(2L, metrics.getThunkEvaluations()); assertEquals(2, success.getState()); assertEquals(3, failure.getState());
        // No target/environment root from this helper can keep the CAF alive.
        assertNull(success.getTarget()); assertNull(success.getEnvironment()); assertNull(failure.getTarget()); assertNull(failure.getEnvironment());
        return new Observed(new WeakReference<>(success), new WeakReference<>(failure), new WeakReference<>(result), new WeakReference<>(payload));
    }
    private static final class Cycle { Cycle other; }
    private WeakReference<Cycle> sentinel(ReferenceQueue<Cycle> queue) {
        var first = new Cycle(); var second = new Cycle(); first.other = second; second.other = first; return new WeakReference<>(first, queue);
    }
    private void collectWithPressure() throws Exception {
        var queue = new ReferenceQueue<Cycle>(); var sentinel = sentinel(queue); long deadline = System.nanoTime() + 10_000_000_000L;
        do {
            var pressure = new byte[16][1024 * 1024]; System.gc(); Reference.reachabilityFence(pressure);
            if (queue.remove(100) == sentinel) { assertNull(sentinel.get()); return; }
        } while (System.nanoTime() < deadline);
        fail("Allocation pressure plus collection requests did not collect the unrelated cyclic sentinel");
    }
    @Test void publicProgramEntriesShareLazySuccessAndFailureWhileIndependentLoadsDoNot() throws Exception {
        var paths = List.of("@" + fixture());
        var effects = "[thc trace event] success\n[thc trace event] failure\n";
        for (var backend : List.of("ast", "bytecode")) {
            var output = new ByteArrayOutputStream();
            try (var context = Context.newBuilder("thc").err(output).build()) {
                var program = Main.loadProgram(context, paths, true, backend, false, false);
                context.enter();
                try { Language.currentState().getRuntimeTrace().control(500, 1); }
                finally { context.leave(); }
                assertThrows(PolyglotException.class, () -> Main.loadEntry(program, "uC:C.missing"));
                var success = Main.loadEntry(program, "uC:C.success");
                var successAgain = Main.loadEntry(program, "uC:C.successAgain");
                var failure = Main.loadEntry(program, "uC:C.failure");
                var failureAgain = Main.loadEntry(program, "uC:C.failureAgain");
                assertEquals("", output.toString(StandardCharsets.UTF_8), "looking up views cannot evaluate CAFs");
                assertEquals(17L, success.execute(0).asLong());
                assertThrows(PolyglotException.class, () -> failure.execute(0));
                assertEquals(17L, successAgain.execute(0).asLong());
                assertThrows(PolyglotException.class, () -> failureAgain.execute(0));
                assertEquals(effects, output.toString(StandardCharsets.UTF_8), "both views must observe the same memoized effects");
                var independent = Main.loadProgram(context, paths, true, backend, false, false);
                assertEquals(17L, Main.loadEntry(independent, "uC:C.success").execute(0).asLong());
                assertThrows(PolyglotException.class, () -> Main.loadEntry(independent, "uC:C.failure").execute(0));
                assertEquals(effects + effects, output.toString(StandardCharsets.UTF_8), "another explicit load has fresh CAFs");
                assertEquals(17L, Main.loadEntry(program, "uC:C.success").execute(0).asLong());
                assertEquals(effects + effects, output.toString(StandardCharsets.UTF_8));
            }
        }
    }
    @Test void laterColdNameLookupPreservesMemoizedResultAndFailureWithoutRepeatingEffects() throws Exception {
        var manifest = fixture();
        for (var backend : List.of("ast", "bytecode")) for (boolean async : new boolean[]{false, true}) {
            var output = new ByteArrayOutputStream();
            try (var context = Context.newBuilder("thc").err(output).build()) {
                var entry = context.eval("thc", request(List.of("@" + manifest), "uA:A.entry", backend, false, async, false));
                assertEquals(7L, entry.execute(0).asLong()); context.enter();
                try {
                    Language.currentState().getRuntimeTrace().control(500, 1);
                    var programs = Language.currentState(null).getCoreUnitPrograms(); assertEquals(1, programs.size()); var program = programs.getFirst();
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var prior = evaluateAndDropHandles(program, language, async);
                    assertEquals(3L, count(program, "coreCompactDecodedBindings"), "A plus two CAFs; C is still cold"); assertEquals(2L, count(program, "coreCompactModuleOpens"));
                    var effects = "[thc trace event] success\n[thc trace event] failure\n"; assertEquals(effects, output.toString(StandardCharsets.UTF_8));
                    long evaluations = count(program, "thunkEvaluations"); collectWithPressure();
                    // C's unparsed names were not JVM references to B's mutable cells.
                    var success = (Closure) program.entryValue("uC:C.success"); assertEquals(17L, Calls.target(success.target, new Object[]{0L, 0L}));
                    var failure = (Closure) program.entryValue("uC:C.failure"); var observed = assertThrows(GuestException.class, () -> Calls.target(failure.target, new Object[]{0L, 0L}));
                    assertNotNull(prior.success().get(), backend + "/" + async + " existing CAF identity must survive cold names");
                    assertNotNull(prior.failure().get(), backend + "/" + async + " existing failed CAF must survive cold names");
                    assertSame(prior.success().get(), program.entryValue("uB:B.value")); assertSame(prior.failure().get(), program.entryValue("uB:B.failure"));
                    assertSame(prior.result().get(), ((Thunk) program.entryValue("uB:B.value")).getValue()); assertNotNull(prior.payload().get());
                    assertSame(prior.payload().get(), observed.getPayload(), "memoized guest payload, not exception-wrapper identity");
                    assertEquals(effects, output.toString(StandardCharsets.UTF_8), "cold demand must not replay either CAF's effect");
                    assertEquals(evaluations, count(program, "thunkEvaluations")); assertEquals(5L, count(program, "coreCompactDecodedBindings")); assertEquals(3L, count(program, "coreCompactModuleOpens"));
                    assertEquals(7L, entry.execute(1).asLong(), "the original entry and context stay live"); Reference.reachabilityFence(program);
                } finally { context.leave(); }
            }
        }
    }
}
