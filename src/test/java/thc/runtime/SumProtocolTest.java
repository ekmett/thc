// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.RootNode;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import thc.CoreModules;
import thc.CoreCbdFixtures;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import thc.Json;
import thc.Language;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class SumProtocolTest {
    @TempDir Path temporary;
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private static List<Object> list(Object... elements) { return Arrays.asList(elements); }
    private static Map<String, Object> map(Object... fields) {
        var result = new LinkedHashMap<String, Object>(); for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]); return result;
    }
    private static <T> T single(List<T> values, Predicate<T> predicate) {
        T result = null; boolean found = false;
        for (T value : values) if (predicate.test(value)) { if (found) throw new IllegalArgumentException("Collection contains more than one matching element."); result = value; found = true; }
        if (!found) throw new NoSuchElementException("Collection contains no element matching the predicate."); return result;
    }
    private Map<String, Object> module() throws Exception { return module(false); }
    private Map<String, Object> module(boolean extended) throws Exception {
        return CoreCbdFixtures.read(artifact(extended));
    }
    private Path artifact(boolean extended) { return new File(root, "build/" + (extended ? "sum-result" : "sum-layout") + "/pre-core/" + (extended ? "SumResultAudit" : "SumLayoutAudit") + ".cbd").toPath(); }
    private String id(Map<String, Object> module, String name) { return "main:" + module.get("module") + "." + name; }
    private Map<String, Object> binding(Map<String, Object> module, String name) { return single((List<Map<String, Object>>) module.get("bindings"), it -> Objects.equals(it.get("id"), id(module, name))); }
    private Map<String, Object> shape(Map<String, Object> module, String name) { return (Map<String, Object>) ((Map<?, ?>) ((List<?>) binding(module, name).get("expr")).get(3)).get("resultRep"); }
    private Context context() { return context(true); }
    private Context context(boolean inline) { return Context.newBuilder("thc").allowExperimentalOptions(true).option("compiler.Inlining", Boolean.toString(inline)).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build(); }
    @FunctionalInterface private interface Action { void run(Language language) throws Exception; }
    private void withLanguage(Action action) throws Exception { withLanguage(true, action); }
    private void withLanguage(boolean inline, Action action) throws Exception {
        try (var context = context(inline)) { context.initialize("thc"); context.enter(); try { action.run(TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); } }
    }
    private ExecutableProgram program(Language language, Map<String, Object> module, String entry, String backend) { var linked = CoreModules.reachable(module, id(module, entry)); return backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked); }
    private void compile(RootCallTarget target) throws ReflectiveOperationException { target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target); }
    private void valid(RootCallTarget target) throws ReflectiveOperationException { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private void released(Language language) {
        var state = language.getHandoffState().get(); assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getResults().retainedReferences()); assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getArguments().retainedReferences());
    }
    private List<List<Object>> walk(Object value) {
        var result = new ArrayList<List<Object>>();
        if (value instanceof List<?> values) { result.add((List<Object>) values); for (Object child : values) result.addAll(walk(child)); }
        if (value instanceof Map<?, ?> values) for (Object child : values.values()) result.addAll(walk(child));
        return result;
    }

    @Test void exactLogicalAndPhysicalProofsRejectMalformedOrUnsupportedSums() throws Exception {
        List<Consumer<Map<String, Object>>> mutants = List.of(
            it -> it.put("tagSlot", 1L), it -> it.put("tagSlot", 0.0), it -> it.remove("tagSlot"),
            it -> it.put("alternativeSlots", list(list(0L), list(1L))), it -> it.put("alternativeSlots", list(list(1.0), list(1L))), it -> it.put("alternativeSlots", list(list(1L, 1L), list(1L))),
            it -> it.put("primReps", list("WordRep", "WordRep", "WordRep")), it -> it.put("components", List.of()), it -> it.put("kind", "long"));
        for (var mutate : mutants) { var proof = shape(module(), "returnedSum"); mutate.accept(proof); assertThrows(RuntimeFault.class, () -> CoreRepresentations.parse(proof)); }
        for (String name : List.of("narrowWideSum", "threeWaySum")) {
            var exact = CoreRepresentations.parse(shape(module(), name)); assertTrue(exact.isSum());
            boolean allLong = true; for (var field : SumShape.storage(exact)) if (!field.isLong()) { allLong = false; break; } assertTrue(allLong);
        }
        for (String name : List.of("nestedSum", "addressResult", "vectorResult")) assertTrue(CoreRepresentations.parse(shape(module(), name)).isSum());
        assertTrue(CoreRepresentations.parse(shape(module(), "levityPolymorphic")).isSum());
        for (String name : List.of("runtimePolymorphic", "abstractSumIdentity", "abstractRuntimeSum", "abstractAlternative")) assertThrows(UnsupportedCore.class, () -> CoreRepresentations.parse(shape(module(), name)));
        var proof = shape(module(), "returnedSum"); proof.put("alternativeSlots", null); assertThrows(UnsupportedCore.class, () -> CoreRepresentations.parse(proof));
        withLanguage(language -> assertThrows(RuntimeFault.class, () -> new TupleShape(new CoreRepresentation(CoreKind.LONG, true, true, List.of("WordRep"), null, null, null, null, null), language)));
    }

    @Test void malformedColdConstructorAndCaseProofsRejectOnBothBackends() throws Exception {
        withLanguage(language -> {
            for (String backend : List.of("ast", "bytecode")) for (int mode = 0; mode <= 9; mode++) {
                var module = module(); var worker = (List<Object>) binding(module, "returnedSum").get("expr"); var constructors = (List<Map<String, Object>>) module.get("constructors");
                var second = single(constructors, it -> Objects.equals(it.get("kind"), "unboxed-sum") && Objects.equals(it.get("tag"), 2L) && Objects.equals(it.get("sumArity"), 2L));
                var consumer = (List<Object>) binding(module, "sumCase").get("expr"); var sumCase = (List<Object>) consumer.get(2); var alternatives = (List<List<Object>>) sumCase.get(3);
                switch (mode) {
                    case 0 -> second.put("sumArity", 3L);
                    case 1 -> second.put("tag", 0L);
                    case 2 -> second.put("arity", 1.0);
                    case 3 -> alternatives.get(1).set(1, alternatives.get(0).get(1));
                    case 4 -> alternatives.get(1).set(2, List.of());
                    case 5 -> {
                        // Int# and Word# deliberately share the Long carrier at
                        // runtime; reject a real scalar carrier mismatch instead.
                        alternatives.get(1).set(3, list("lit", "float", "1", map("rep", map("kind", "float", "primReps", list("FloatRep"), "evaluated", true))));
                    }
                    case 6 -> alternatives.get(1).set(3, list("void", map("rep", map("kind", "void", "primReps", List.of(), "evaluated", true))));
                    case 9 -> { alternatives.get(1).set(0, "default"); alternatives.get(1).set(2, List.of()); }
                    case 8 -> alternatives.get(1).set(3, list("var", binding(module, "lazySum").get("id"), map("rep", map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true))));
                    case 7 -> {
                        List<Object> app = null;
                        for (var node : walk(worker)) if (!node.isEmpty() && "app".equals(node.getFirst()) && node.get(1) instanceof List<?> fn && !fn.isEmpty() && "con".equals(fn.getFirst())) { app = node; break; }
                        if (app == null) throw new NoSuchElementException("Sequence contains no element matching the predicate."); app.set(3, list(true));
                    }
                }
                assertThrows(RuntimeFault.class, () -> program(language, module, "sumCase", backend), backend + "/mutation" + mode);
            }
        });
    }

    @Test void inactiveReferencesAreClearedBeforeResidualCompletionAndShapeFailureReleasesLoan() throws Exception {
        withLanguage(false, language -> {
            for (String backend : List.of("ast", "bytecode")) {
                var module = module(true); var program = program(language, module, "lazyLeaf", backend); var target = program.entryTarget((String) binding(module, "lazyLeaf").get("id"));
                var shape = new TupleShape(CoreRepresentations.parse(shape(module, "lazyLeaf")), language); assertEquals(list("long", "reference", "long"), shape.getLayout().getReps());
                var layout = new FrameLayout(); int[] slots = new int[shape.getWidth()]; for (int i = 0; i < slots.length; i++) slots[i] = layout.bind("sum" + i); var descriptor = layout.build();
                class Call {
                    Object invoke(long x) throws Exception {
                        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor); shape.consume(frame, Calls.target(target, new Object[]{0L, x}), slots, 0);
                        assertEquals(x < 0 ? 1L : 2L, frame.getLong(slots[0])); var reference = frame.getObject(slots[1]);
                        if (x < 0) assertTrue(reference instanceof Thunk); else { assertNull(reference); assertEquals(x + 9, frame.getLong(slots[2])); }
                        released(language); return reference;
                    }
                }
                var call = new Call(); var lazy = call.invoke(-1); call.invoke(1); compile(target);
                for (long x : new long[]{-7, 7, -1, 0}) { var value = call.invoke(x); if (x < 0) assertSame(lazy, value); valid(target); }
                assertEquals(0L, ((Number) program.diagnostics().get("blackholes")).longValue());
                var wrong = new TupleShape(CoreRepresentations.parse(shape(module(), "returnedSum")), language); var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor); var completion = Calls.target(target, new Object[]{0L, -1L});
                assertThrows(IllegalStateException.class, () -> wrong.consume(frame, completion, slots, 0)); released(language);
            }
        });
    }

    private static class Barrier {
        static volatile boolean requested = false;
        static int materialized = 0;
        @CompilerDirectives.TruffleBoundary static boolean observe() { return requested; }
    }
    private static class ResultSlots {
        final FrameLayout layout = new FrameLayout();
        @CompilationFinal(dimensions = 1) final int[] fields;
        ResultSlots(int width) { fields = new int[width]; for (int i = 0; i < width; i++) fields[i] = layout.bind("sum" + i); }
    }
    private static class DeoptConsumer extends RootNode {
        private final TupleShape shape; private final ResultSlots slots;
        @Child private DirectCallNode call;
        DeoptConsumer(Language language, TupleShape shape, RootCallTarget target) { this(language, shape, target, new ResultSlots(shape.getWidth())); }
        DeoptConsumer(Language language, TupleShape shape, RootCallTarget target, ResultSlots slots) { super(language, slots.layout.build()); this.shape = shape; this.slots = slots; call = DirectCallNode.create(target); call.forceInlining(); }
        @Override public Object execute(VirtualFrame frame) {
            var result = Calls.direct(call, new Object[]{0L, frame.getArguments()[0]}); if (Barrier.observe()) CompilerDirectives.transferToInterpreterAndInvalidate();
            if (CompilerDirectives.inInterpreter() && result instanceof HandoffStorage) Barrier.materialized++;
            shape.consume(frame, result, slots.fields, 0); if (frame.getLong(slots.fields[0]) != 1L) throw new IllegalStateException("Check failed."); return frame.getObject(slots.fields[1]);
        }
    }
    @Test void realSumProducerMaterializesFreshCarrierAcrossDeoptWithoutPoolLoan() throws Exception {
        withLanguage(language -> {
            for (String backend : List.of("ast", "bytecode")) {
                var module = module(true); var program = program(language, module, "lazyLeaf", backend); var target = program.entryTarget((String) binding(module, "lazyLeaf").get("id"));
                var shape = new TupleShape(CoreRepresentations.parse(shape(module, "lazyLeaf")), language); var consumer = new DeoptConsumer(language, shape, target).getCallTarget();
                Barrier.requested = false; var pointer = consumer.call(-1L);
                for (int i = 0; i < 20; i++) { assertSame(pointer, consumer.call(-7L)); released(language); }
                compile(consumer); assertSame(pointer, consumer.call(-4097L)); valid(consumer); released(language); int before = Barrier.materialized; Barrier.requested = true;
                try { assertSame(pointer, consumer.call(-8193L)); } finally { Barrier.requested = false; }
                assertEquals(before + 1, Barrier.materialized, backend + " requires actual post-return carrier materialization"); assertEquals(0L, ((Number) program.diagnostics().get("blackholes")).longValue()); released(language);
            }
        });
    }
    @Test void publicHostTransportsKnownAliasToSumResult() throws Exception {
        for (String backend : List.of("ast", "bytecode")) try (var context = context()) {
            var module = module(true); var source = binding(module, "produce");
            var alias = map("id", "sum-alias", "name", "sumAlias", "arity", 1L, "lifted", true, "rep", source.get("rep"), "expr", list("var", source.get("id"), map("rep", source.get("rep"))));
            var driver = map("schema", 1, "ghc", "9.14.1", "unit", "main", "boundary", "main", "module", "Synthetic.SumAlias", "constructors", list(), "bindings", list(alias));
            var encoded = CoreCbdFixtures.write(temporary.resolve("alias-" + backend + ".cbd"), driver);
            var entry = context.eval("thc", CoreModules.request(List.of(artifact(true).toString(), encoded.toString()), "sum-alias", true, false, backend));
            var negative = entry.execute(-7L);
            assertEquals(2, negative.getArraySize()); assertEquals(1L, negative.getArrayElement(0).asLong());
            assertEquals(-7L, negative.getArrayElement(1).asLong());
            var positive = entry.execute(7L);
            assertEquals(2, positive.getArraySize()); assertEquals(2L, positive.getArrayElement(0).asLong());
            assertEquals(8L, positive.getArrayElement(1).asLong());
            assertThrows(PolyglotException.class, () -> entry.execute("invalid host argument"));
            assertThrows(PolyglotException.class, () -> entry.execute());
        }
    }
    @Test void sumAndTuplePayloadBindingsShadowOuterJoinWithoutChangingProjectionSlots() throws Exception {
        withLanguage(language -> {
            for (String backend : List.of("ast", "bytecode")) for (boolean tuple : new boolean[]{false, true}) {
                var module = module(tuple); String name = tuple ? "pairedInputs" : "sumCase"; var lambda = (List<Object>) binding(module, name).get("expr"); var body = (List<Object>) lambda.get(2); var sumArm = ((List<List<Object>>) body.get(3)).getFirst();
                var bindingArm = tuple ? ((List<List<Object>>) ((List<Object>) sumArm.get(3)).get(3)).getFirst() : sumArm;
                var binder = (List<Map<String, Object>>) ((Map<String, Object>) bindingArm.get(4)).get("binders"); String id = ((List<String>) bindingArm.get(2)).getFirst(); var proof = binder.getFirst().get("rep");
                if (!tuple) bindingArm.set(3, list("var", id, map("rep", proof)));
                var join = map("id", id, "name", "shadowedJoin", "lifted", false, "rep", proof, "expr", list("lit", "int", "99", map("rep", proof)), "joinValueArity", 0L, "joinResultRep", proof, "info", map("joinArity", 0L));
                lambda.set(2, list("let", false, list(join), body, map("rep", proof))); var program = program(language, module, name, backend); var target = program.entryTarget((String) binding(module, name).get("id"));
                var actual = Calls.target(target, tuple ? new Object[]{0L, 1L, 3L} : new Object[]{0L, -1L}); assertEquals(tuple ? 24L : -1L, actual, backend + "/tuple=" + tuple); released(language);
            }
        });
    }
    @Test void inferredSumCannotEnterScalarFormalWhenOuterProofIsOmitted() throws Exception {
        withLanguage(language -> {
            for (String backend : List.of("ast", "bytecode")) for (boolean explicitUnknown : new boolean[]{false, true}) {
                var module = module(); var lambda = (List<Object>) binding(module, "sumCase").get("expr"); var body = (List<Object>) lambda.get(2); var sum = shape(module, "returnedSum"); var scalar = ((List<Object>) sum.get("alternatives")).getFirst(); var closure = binding(module, "returnedSum").get("rep");
                var constructor = single((List<Map<String, Object>>) module.get("constructors"), it -> Objects.equals(it.get("kind"), "unboxed-sum") && Objects.equals(it.get("tag"), 1L) && Objects.equals(it.get("sumArity"), 2L)).get("id");
                var literal = list("lit", "int", "1", map("rep", scalar)); var constructed = list("app", list("con", constructor, 1L, map("rep", closure)), list(literal), list(false), true, true, map("rep", sum));
                for (var arm : (List<List<Object>>) body.get(3)) arm.set(3, constructed); var metadata = (Map<String, Object>) body.get(4);
                if (explicitUnknown) metadata.put("rep", map("kind", "unknown", "primReps", null, "evaluated", false)); else metadata.remove("rep");
                var function = list("lam", list(map("id", "ignored", "lifted", false, "rep", scalar)), literal, map("rep", closure, "resultRep", scalar)); lambda.set(2, list("app", function, list(body), list(false), false, false, map("rep", scalar)));
                // Ordinary sum arguments now lower, including an inferred case result.
                // The scalar callee still cannot accept that logical aggregate layout.
                var error = assertThrows(RuntimeFault.class, () -> program(language, module, "sumCase", backend));
                assertEquals("Conflicting scalar and aggregate representation proofs", error.getMessage(), backend + "/" + explicitUnknown); released(language);
            }
        });
    }
    @Test void intrinsicScalarColdArmCannotSatisfyAggregateResultWithoutMetadata() throws Exception {
        withLanguage(language -> {
            for (String backend : List.of("ast", "bytecode")) for (boolean genericCase : new boolean[]{false, true}) {
                var module = module(); var sum = shape(module, "returnedSum"); String name = genericCase ? "returnedSum" : "sumCase"; var lambda = (List<Object>) binding(module, name).get("expr"); var body = (List<Object>) lambda.get(2); var alternatives = (List<List<Object>>) body.get(3);
                if (!genericCase) {
                    var scalar = ((List<Object>) sum.get("alternatives")).getFirst(); var closure = binding(module, "returnedSum").get("rep"); var constructor = single((List<Map<String, Object>>) module.get("constructors"), it -> Objects.equals(it.get("kind"), "unboxed-sum") && Objects.equals(it.get("tag"), 1L) && Objects.equals(it.get("sumArity"), 2L)).get("id");
                    alternatives.getFirst().set(3, list("app", list("con", constructor, 1L, map("rep", closure)), list(list("lit", "int", "1", map("rep", scalar))), list(false), true, true, map("rep", sum)));
                    ((Map<String, Object>) body.get(4)).put("rep", sum); ((Map<String, Object>) lambda.get(3)).put("resultRep", sum);
                }
                alternatives.get(1).set(3, list("lit", "int", "99")); var error = assertThrows(RuntimeFault.class, () -> program(language, module, name, backend));
                assertTrue(Objects.toString(error.getMessage(), "").contains("scalar and aggregate"), backend + "/generic=" + genericCase + ": " + error.getMessage());
            }
            var proof = CoreRepresentations.parse(shape(module(), "returnedSum")); assertEquals(proof, proof.refine(CoreRepresentation.UNKNOWN), "A genuinely unknown legacy proof adds no constraint");
        });
    }
    @Test void defaultAndNonExhaustiveSumCasesConsumeAndReleaseResults() throws Exception {
        withLanguage(language -> {
            for (String backend : List.of("ast", "bytecode")) for (boolean defaultArm : new boolean[]{false, true}) {
                var module = module(); var lambda = (List<Object>) binding(module, "sumCase").get("expr"); var body = (List<Object>) lambda.get(2); var alternatives = (List<List<Object>>) body.get(3);
                if (defaultArm) { var scalar = ((List<Object>) shape(module, "returnedSum").get("alternatives")).getFirst(); alternatives.set(1, list("default", null, List.of(), list("lit", "int", "77", map("rep", scalar)), map("binders", List.of()))); }
                else alternatives.remove(1);
                var program = program(language, module, "sumCase", backend); var target = program.entryTarget((String) binding(module, "sumCase").get("id")); java.util.function.LongFunction<Object> call = x -> Calls.target(target, new Object[]{0L, x});
                assertEquals(-4L, call.apply(-1));
                if (defaultArm) { assertEquals(77L, call.apply(1)); compile(target); assertEquals(-4L, call.apply(-1)); valid(target); assertEquals(77L, call.apply(1)); valid(target); }
                else { var failure = assertThrows(RuntimeFault.class, () -> call.apply(1)); assertTrue(Objects.toString(failure.getMessage(), "").contains("Non-exhaustive"), failure.getMessage()); }
                released(language);
            }
        });
    }
}
