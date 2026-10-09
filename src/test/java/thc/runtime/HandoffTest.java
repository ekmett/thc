// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.FrameSlotTypeException;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.nodes.DirectCallNode;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.Main.executionContext;

/** The object boundary remains real; every transport assertion also runs interpreted. */
@SuppressWarnings("unchecked")
class HandoffTest {
    @Test void detachedTailCallerRejectsBeforeLoanOrPacketMutation() throws Exception {
        withLanguage(language -> {
            var layout = new FrameLayout(); var entry = Objects.requireNonNull(HandoffEntry.create(language, layout, List.of(proof), proof, false));
            var target = new RootNode(null) { @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("Detached caller reached dispatch"); } }.getCallTarget();
            var caller = new HandoffCaller(target, entry, new Metrics(false)); var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], layout.build()); Object[] packet = {17L, 23L};
            var failure = assertThrows(IllegalStateException.class, () -> caller.call(frame, packet, DirectCallNode.create(target), true)); assertEquals("Check failed.", failure.getMessage()); assertArrayEquals(new Object[]{17L, 23L}, packet); assertReleased(language.getHandoffState().get());
        });
    }
    @Test void invalidTailDestinationPreservesBloomOrderAndReleasesOnlyItsInputLoan() throws Exception {
        withLanguage(language -> {
            var state = language.getHandoffState().get(); var nonGuest = new RootNode(null) { @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("Non-guest destination reached dispatch"); } };
            for (RootNode destination : Arrays.asList(null, nonGuest)) {
                var layout = new FrameLayout(); var reference = new CoreRepresentation(CoreKind.OBJECT, false, true, List.of("BoxedRep (Just Lifted)"), null, null, null, null, null); var entry = Objects.requireNonNull(HandoffEntry.create(language, layout, List.of(reference), proof, false)); Object[] packet = {17L, new Object()}; int[] reads = {0};
                RootCallTarget target = new RootCallTarget() {
                    @Override public RootNode getRootNode() { reads[0]++; assertEquals(0L, packet[0]); assertEquals(1, state.getArguments().getDepth()); assertEquals(1, state.getArguments().retainedReferences()); assertNull(state.getPending()); return destination; }
                    @Override public Object call(Object... arguments) { throw new IllegalStateException("Invalid destination reached dispatch"); }
                };
                var action = new RootNode(null) { @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("Invalid destination reached call action"); } }.getCallTarget();
                var body = new Expr() { @Child private HandoffCaller caller = new HandoffCaller(target, entry, new Metrics(true)); @Child private DirectCallNode dispatch = DirectCallNode.create(action); @Override public Object execute(VirtualFrame frame) { return caller.call(frame, frame.getArguments(), dispatch, true); } };
                var descriptor = layout.build(); var root = new FunctionRoot(language, descriptor, "invalid tail destination", null, new int[0], new int[0], new int[0], body, new Metrics(false), new CoreRepresentation[0], proof, body.getCoreSourceLocation(), new boolean[0], entry, null, new int[0], null, false, new int[0][], false, FunctionRootRole.FUNCTION, false); root.adoptChildren();
                var frame = Truffle.getRuntime().createVirtualFrame(packet, descriptor); frame.setLong(entry.getDestinationSlot(), 0L); var output = state.getResults().acquire(entry.getArguments()); var marker = new Object(); entry.getArguments().setObject(output, 1, marker); long calls = state.getCalls(), transfers = state.getTailTransfers();
                // Source bloom is read before the destination, inside the same cleanup region.
                frame.setObject(FrameLayout.BLOOM_FILTER, new Object()); assertThrows(FrameSlotTypeException.class, () -> body.execute(frame)); assertEquals(0, reads[0]); assertReleased(state); frame.setLong(FrameLayout.BLOOM_FILTER, 17L); packet[0] = 17L;
                Class<? extends RuntimeException> type = destination == null ? NullPointerException.class : ClassCastException.class; var failure = assertThrows(type, () -> body.execute(frame)); if (destination == null) assertEquals("null cannot be cast to non-null type thc.runtime.GuestRoot", failure.getMessage());
                assertEquals(1, reads[0]); assertEquals(0L, packet[0]); assertReleased(state); assertEquals(calls, state.getCalls()); assertEquals(transfers, state.getTailTransfers()); assertEquals(1, state.getResults().getDepth()); assertTrue(output.getLive()); assertSame(marker, entry.getArguments().getObject(output, 1)); state.getResults().release(output, entry.getArguments()); assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getResults().retainedReferences());
            }
        });
    }
    private static List<Object> list(Object... values) { return Arrays.asList(values); }
    private static Map<String, Object> map(Object... fields) { var result = new LinkedHashMap<String, Object>(); for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]); return result; }
    private final Map<String, Object> longRep = map("kind", "long", "primReps", list("IntRep"), "evaluated", true);
    private final Map<String, Object> closureRep = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
    private final CoreRepresentation proof = new CoreRepresentation(CoreKind.LONG, true, true, List.of("IntRep"), null, null, null, null, null);
    private List<Object> v(String id) { return list("var", id); }
    private List<Object> n(long value) { return list("lit", "int", Long.toString(value)); }
    @SafeVarargs private final List<Object> call(String fn, List<Object>... values) { return list("app", v(fn), Arrays.asList(values), Collections.nCopies(values.length, false)); }
    @SafeVarargs private final List<Object> prim(String fn, List<Object>... values) { return list("app", list("prim", fn), Arrays.asList(values), Collections.nCopies(values.length, false)); }
    private List<Object> choose(List<Object> test, List<Object> yes, List<Object> no) { return list("case", test, "test", list(list("lit", list("int", "1"), List.of(), yes), list("default", null, List.of(), no))); }
    private Map<String, Object> binding(String id, List<Object> body) { return map("id", id, "name", id, "lifted", true, "expr", list("lam", list(map("id", "n", "name", "n", "lifted", false, "coercion", false, "rep", longRep)), body, map("rep", closureRep, "resultRep", longRep, "entryStrict", list(false)))); }
    private Map<String, Object> module(List<Map<String, Object>> bindings) { return map("bindings", bindings, "constructors", List.of(), "instrument", true); }
    private Object invoke(ExecutableProgram program, long value) { return Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue("entry"), new Object[]{value}}); }
    private void compile(RootCallTarget target) throws ReflectiveOperationException { var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"); type.getMethod("compile", boolean.class).invoke(target, true); type.getMethod("waitForCompilation").invoke(target); assertEquals(true, type.getMethod("isValidLastTier").invoke(target)); }
    @FunctionalInterface private interface Action { void run(Language language) throws Exception; }
    private void withLanguage(Action action) throws Exception { withLanguage(true, action); }
    private void withLanguage(boolean inlining, Action action) throws Exception {
        String previous = System.getProperty(Handoff.HANDOFF_PROPERTY); System.setProperty(Handoff.HANDOFF_PROPERTY, "true");
        try { try (var context = inlining ? executionContext() : Context.newBuilder("thc").allowExperimentalOptions(true).option("compiler.Inlining", "false").option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").option("compiler.CompilationTimeout", "30").build()) {
            context.initialize("thc"); context.enter(); try { action.run(TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); }
        } } finally { if (previous == null) System.clearProperty(Handoff.HANDOFF_PROPERTY); else System.setProperty(Handoff.HANDOFF_PROPERTY, previous); }
    }
    private void assertReleased(HandoffState state) { assertEquals(0, state.getArguments().getDepth()); assertNull(state.getPending()); assertEquals(0, state.getArguments().retainedReferences()); }
    @Test void requestedModeReachesTestProcessAndContext() {
        // Unlike withLanguage, this proof never changes the process property.
        // Named Gradle forks supply an independent expectation; legacy `test`
        // still accepts the caller's JAVA_TOOL_OPTIONS setting.
        boolean actual = Boolean.getBoolean(Handoff.HANDOFF_PROPERTY); String expected = System.getProperty("thc.expectedHandoffSlabs");
        if (expected != null) { assertTrue(expected.equals("true") || expected.equals("false")); assertEquals(expected, System.getProperty(Handoff.HANDOFF_PROPERTY), "The fork must receive its requested handoff mode"); }
        try (var context = executionContext()) { context.initialize("thc"); context.enter(); try { var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); assertEquals(expected == null ? actual : Boolean.parseBoolean(expected), language.getHandoffLayouts().getEnabled(), "The runtime context must use the fork's handoff mode"); } finally { context.leave(); } }
        System.out.println("THC_HANDOFF_MODE=" + actual);
    }
    @Test void internedRepVectorsGenerateMutableDenseFieldsAndSeparateLifetimes() throws Exception {
        withLanguage(language -> {
            var reps = List.of("IntRep", "BoxedRep (Just Lifted)", "WordRep"); var layout = language.getHandoffLayouts().intern(reps); assertSame(layout, language.getHandoffLayouts().intern(new ArrayList<>(reps))); assertSame(layout, language.getHandoffLayouts().intern(List.of("WordRep", "BoxedRep (Just Unlifted)", "Int64Rep"))); assertNotSame(layout, language.getHandoffLayouts().intern(List.of("WordRep", "BoxedRep (Just Unlifted)", "Int8Rep")), "A narrow stored Int field is not a machine Long field");
            var state = language.getHandoffState().get(); var input = state.getArguments().acquire(layout); var independentPool = new HandoffPool(); var output = independentPool.acquire(layout); assertNotSame(input, output); assertSame(input.getClass(), output.getClass());
            var fields = new ArrayList<java.lang.reflect.Field>(); for (var field : input.getClass().getDeclaredFields()) if (field.getName().startsWith("handoff_")) fields.add(field); assertEquals(3, fields.size()); int longs = 0, objects = 0; for (var field : fields) { if (field.getType() == long.class) longs++; if (field.getType() == Object.class) objects++; } assertEquals(2, longs); assertEquals(1, objects);
            boolean noArrays = true; for (var field : input.getClass().getDeclaredFields()) if (field.getType().isArray()) { noArrays = false; break; } assertTrue(noArrays);
            var objectValue = new Object(); layout.copyIn(input, new Object[]{4_000_000_000L, objectValue, Long.MIN_VALUE}); state.getArguments().release(input); assertEquals(0, state.getArguments().getDepth()); assertEquals(1, independentPool.getDepth()); assertTrue(output.getLive()); var again = state.getArguments().acquire(layout); assertSame(input, again); assertNull(layout.getObject(again, 1)); layout.copyIn(again, new Object[]{7L, new Object(), 9L}); assertEquals(7L, layout.getLong(again, 0)); state.getArguments().release(again); independentPool.release(output); assertReleased(state);
        });
    }
    @Test void firstInstalledDynamicLayoutsClearReferencesAndReleaseLoans() throws Exception {
        withLanguage(false, language -> {
            var layouts = List.of(language.getHandoffLayouts().intern(List.of()),
                    language.getHandoffLayouts().intern(List.of("IntRep", "BoxedRep (Just Lifted)", "DoubleRep", "AddrRep")),
                    language.getHandoffLayouts().intern(List.of("BoxedRep (Just Lifted)", "WordRep", "BoxedRep (Just Unlifted)")));
            var pool = new HandoffPool();
            var releaser = new RootNode(language) {
                int dynamicEntries;
                int compiledReleases;
                @Override public Object execute(VirtualFrame frame) {
                    var layout = (HandoffLayout) frame.getArguments()[0];
                    if (CompilerDirectives.inCompiledCode() && !CompilerDirectives.isPartialEvaluationConstant(layout)) dynamicEntries++;
                    pool.release((HandoffStorage) frame.getArguments()[1], layout);
                    if (CompilerDirectives.inCompiledCode()) compiledReleases++;
                    return Unit.INSTANCE;
                }
            };
            var target = releaser.getCallTarget();
            target.getClass().getMethod("ensureInitialized").invoke(target);
            ThreadInventoryCoreEvidence.install(List.of(target));
            assertTrue(ThreadInventoryCoreEvidence.valid(target), "Install before the first release; no warmup");
            var address = ManagedAddress.fromByteArray(new byte[]{11});
            Object[][] values = {new Object[0], {Long.MIN_VALUE, new Object(), Double.longBitsToDouble(0x7ff8000000001234L), address},
                    {new Object(), Long.MAX_VALUE, new Object()}};
            for (int n = 0; n < layouts.size(); n++) {
                var layout = layouts.get(n); var storage = pool.acquire(layout); layout.copyIn(storage, values[n]);
                int entries = releaser.dynamicEntries, releases = releaser.compiledReleases;
                target.call(layout, storage);
                assertEquals(entries + 1, releaser.dynamicEntries, "The first installed call must use a runtime layout");
                assertEquals(releases + 1, releaser.compiledReleases);
                assertTrue(ThreadInventoryCoreEvidence.valid(target));
                assertEquals(0, pool.getDepth()); assertEquals(0, pool.retainedReferences()); assertFalse(storage.getLive());
            }
        });
    }
    @Test void nestedNonTailCallsKeepDistinctResultsAcrossCompilationAndReuse() throws Exception {
        withLanguage(language -> {
            var body = choose(prim("<=#", v("n"), n(0)), n(3_000_000_017L), prim("+#", call("worker", prim("-#", v("n"), n(1))), v("n"))); var data = module(List.of(binding("worker", body), binding("entry", prim("+#", call("worker", v("n")), n(7)))));
            for (String backend : List.of("ast", "bytecode")) { ExecutableProgram program = backend.equals("ast") ? new Program(language, data) : new BytecodeProgram(language, data); for (long input : new long[]{0, 1, 19, 80}) assertEquals(3_000_000_024L + input * (input + 1) / 2, invoke(program, input), backend); assertReleased(language.getHandoffState().get()); compile(program.entryTarget("entry")); assertEquals(3_000_003_264L, invoke(program, 80L), backend); assertReleased(language.getHandoffState().get()); }
            assertTrue(language.getHandoffState().get().getCalls() > 0);
        });
    }
    @Test void interiorTailCyclesForwardDestinationAndReuseIncomingLoan() throws Exception {
        withLanguage(language -> {
            var condition = prim("<=#", v("n"), n(0)); java.util.function.BiFunction<String, String, Map<String, Object>> worker = (id, next) -> binding(id, choose(condition, n(3_000_000_017L), call(next, prim("-#", v("n"), n(1)))));
            var data = module(List.of(worker.apply("a", "b"), worker.apply("b", "c"), worker.apply("c", "a"), binding("entry", prim("+#", call("a", v("n")), n(7))))); var program = new Program(language, data); assertEquals(3_000_000_024L, invoke(program, 12_000L)); var state = language.getHandoffState().get(); assertTrue(state.getTailTransfers() > 0); assertReleased(state); long incomingAllocations = state.getArguments().getAllocations(); compile(program.entryTarget("entry")); assertEquals(3_000_000_024L, invoke(program, 15_000L)); assertReleased(state); assertEquals(incomingAllocations, state.getArguments().getAllocations());
        });
    }
    @Test void forcedResidualCallsSupportMultipleAncestorCyclesAndPhysicalRepAliases() throws Exception {
        withLanguage(false, language -> {
            java.util.function.Function<List<Object>, List<Object>> terminal = next -> choose(prim("<=#", v("n"), n(0)), n(3_000_000_017L), next); java.util.function.Function<String, List<Object>> next = name -> call(name, prim("-#", v("n"), n(1)));
            var b = new LinkedHashMap<>(binding("b", terminal.apply(next.apply("c")))); var bLambda = new ArrayList<Object>((List<?>) b.get("expr")); var wordRep = new LinkedHashMap<>(longRep); wordRep.put("primReps", list("WordRep")); bLambda.set(3, map("rep", closureRep, "resultRep", wordRep, "entryStrict", list(false))); b.put("expr", bLambda);
            var data = module(List.of(binding("a", terminal.apply(next.apply("b"))), b, binding("c", terminal.apply(choose(prim("==#", prim("andI#", v("n"), n(1)), n(0)), next.apply("b"), next.apply("d")))), binding("d", terminal.apply(choose(prim("==#", prim("andI#", v("n"), n(2)), n(0)), next.apply("c"), next.apply("a")))), binding("entry", prim("+#", call("a", v("n")), n(7)))));
            var program = new Program(language, data); assertEquals(3_000_000_024L, invoke(program, 4_001L)); for (String id : List.of("a", "b", "c", "d", "entry")) compile(program.entryTarget(id)); assertEquals(3_000_000_024L, invoke(program, 40_001L)); assertTrue(language.getHandoffState().get().getTailTransfers() > 0); assertReleased(language.getHandoffState().get());
        });
    }
    @Test void interiorCReentryThenOutwardBReentryRemainInActiveRoots() throws Exception {
        withLanguage(language -> {
            java.util.function.Function<String, List<Object>> next = name -> call(name, prim("-#", v("n"), n(1))); java.util.function.BiFunction<String, List<Object>, Map<String, Object>> worker = (id, body) -> binding(id, choose(prim("<=#", v("n"), n(0)), n(3_000_000_017L), body));
            var data = module(List.of(binding("a", call("b", v("n"))), binding("b", call("c", v("n"))), worker.apply("c", next.apply("d")), worker.apply("d", next.apply("e")), worker.apply("e", choose(prim("<=#", v("n"), n(5)), next.apply("b"), next.apply("c"))), binding("entry", prim("+#", call("a", v("n")), n(7)))));
            Program selected = null;
            for (int attempt = 0; attempt < 64; attempt++) { var candidate = new Program(language, data); long ancestry = 0; boolean allFresh = true; for (String id : List.of("a", "b", "c", "d", "e")) { long mask = ((GuestRoot) candidate.entryTarget(id).getRootNode()).mask; boolean fresh = (ancestry & mask) != mask; ancestry |= mask; if (!fresh) { allFresh = false; break; } } if (allFresh) { selected = candidate; break; } }
            if (selected == null) throw new NoSuchElementException("Sequence contains no element matching the predicate."); var program = selected;
            java.util.function.LongConsumer check = input -> { long before = ((Number) program.diagnostics().get("selfTailReentries")).longValue(), loops = ((Number) program.diagnostics().get("trampolineIterations")).longValue(); assertEquals(3_000_000_024L, invoke(program, input)); long after = ((Number) program.diagnostics().get("selfTailReentries")).longValue(); assertTrue(after - before >= input / 3); assertEquals(loops, ((Number) program.diagnostics().get("trampolineIterations")).longValue()); assertReleased(language.getHandoffState().get()); };
            check.accept(12_000L); compile(program.entryTarget("entry")); check.accept(15_000L);
        });
    }
    @Test void lazyReferencePrefixesAndCapturedEnvironmentsRemainOwnedAcrossResidualCalls() throws Exception {
        withLanguage(false, language -> {
            java.util.function.BiFunction<String, Map<String, Object>, Map<String, Object>> parameter = (id, rep) -> map("id", id, "name", id, "lifted", id.equals("ignored"), "coercion", false, "rep", rep); var lazyClosure = new LinkedHashMap<>(closureRep); lazyClosure.put("evaluated", false);
            var inner = list("lam", list(parameter.apply("ignored", lazyClosure), parameter.apply("n", longRep)), prim("+#", v("seed"), v("n")), map("rep", closureRep, "resultRep", longRep, "entryStrict", list(false, false))); var outer = list("lam", list(parameter.apply("seed", longRep)), inner, map("rep", closureRep, "resultRep", closureRep, "entryStrict", list(false))); var program = new Program(language, module(List.of(map("id", "factory", "name", "factory", "lifted", true, "expr", outer))));
            var worker = (Closure) Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue("factory"), new Object[]{3_000_000_017L}}); var bottom = new Thunk(new RootNode(null) { @Override public Object execute(VirtualFrame frame) { throw new RuntimeFault("unforced PAP prefix"); } }.getCallTarget(), null); var pap = worker.pap(new Object[]{bottom}); java.util.function.LongFunction<Object> invoke = input -> Calls.target(program.hostEntryTarget(1), new Object[]{pap, new Object[]{input}});
            assertEquals(3_000_000_020L, invoke.apply(3L)); compile(worker.target); assertEquals(3_000_000_012L, invoke.apply(-5L)); if (pap.supplied.length == 0) throw new NoSuchElementException("Array is empty."); if (pap.supplied.length != 1) throw new IllegalArgumentException("Array has more than one element."); assertSame(bottom, pap.supplied[0]); assertEquals(0, bottom.getState()); assertTrue(language.getHandoffState().get().getCalls() > 0); assertReleased(language.getHandoffState().get());
        });
    }
    @Test void lexicalForksKeepOuterFormalsAcrossResidualCallsAndCarrierReuse() throws Exception {
        withLanguage(false, language -> {
            // The case binder shadows the formal only inside its alternative. Its
            // slots and the entry snapshot share allocation, never lexical names.
            var scoped = list("case", prim("+#", v("n"), n(10)), "n", list(list("default", null, List.of(), call("leaf", v("n"))))); var data = module(List.of(binding("leaf", prim("+#", v("n"), n(100))), binding("worker", prim("+#", scoped, v("n"))), binding("entry", prim("-#", call("worker", v("n")), call("leaf", v("n")))))); var program = new Program(language, data);
            Runnable check = () -> { for (long input : new long[]{0L, -5L, 3_000_000_017L, Long.MIN_VALUE, Long.MAX_VALUE}) { assertEquals(input + 10L, invoke(program, input)); assertReleased(language.getHandoffState().get()); } }; check.run(); for (String id : List.of("leaf", "worker", "entry")) compile(program.entryTarget(id)); check.run(); assertTrue(language.getHandoffState().get().getCalls() > 0);
        });
    }
    private static class RememberFrame extends Expr {
        private final int argumentSlot; private final List<MaterializedFrame> saved; boolean compiledBeforeDeopt = false;
        RememberFrame(int argumentSlot, List<MaterializedFrame> saved) { this.argumentSlot = argumentSlot; this.saved = saved; setRepresentation(new CoreRepresentation(CoreKind.LONG, true, true, List.of("IntRep"), null, null, null, null, null)); }
        @Override public Object execute(VirtualFrame frame) { return executeLong(frame); }
        @Override public long executeLong(VirtualFrame frame) { compiledBeforeDeopt = CompilerDirectives.inCompiledCode(); saved.add(frame.materialize()); long input = frame.getLong(argumentSlot); if (input == 777L) CompilerDirectives.transferToInterpreterAndInvalidate(); if (input == 999L) throw new RuntimeFault("intentional handoff exit"); return input + 1; }
    }
    private static class InvokeWorker extends GuestRoot {
        @Child private DirectCallerNode caller;
        InvokeWorker(RootCallTarget target) { super(null, new FrameLayout().build()); caller = new DirectCallerNode(target, new Metrics(false)); }
        @Override public long bloom(VirtualFrame frame) { return 0; }
        @Override public Object execute(VirtualFrame frame) { return caller.call(frame, frame.getArguments(), false); }
    }
    @Test void retainedMaterializedFramesAndExceptionalCleanupSurviveReuse() throws Exception {
        withLanguage(false, language -> {
            var layout = new FrameLayout(); int argument = layout.bind("n"); var entry = Objects.requireNonNull(HandoffEntry.create(language, layout, List.of(proof), proof, false)); var saved = new ArrayList<MaterializedFrame>(); var body = new RememberFrame(argument, saved);
            var root = new FunctionRoot(language, layout.build(), "retained slab frame", null, new int[0], new int[]{argument}, new int[]{0}, body, new Metrics(false), new CoreRepresentation[]{proof}, proof, null, new boolean[]{false}, entry, null, new int[0], null, false, new int[0][], false, FunctionRootRole.FUNCTION, false); var caller = new InvokeWorker(root.getCallTarget());
            assertEquals(3_000_000_018L, Calls.target(caller.getCallTarget(), new Object[]{0L, 3_000_000_017L})); assertThrows(RuntimeFault.class, () -> Calls.target(caller.getCallTarget(), new Object[]{0L, 999L})); assertReleased(language.getHandoffState().get()); assertEquals(11L, Calls.target(caller.getCallTarget(), new Object[]{0L, 10L})); boolean allEmpty = true; for (var frame : saved) if (frame.getArguments().length != 0) { allEmpty = false; break; } assertTrue(allEmpty);
            assertEquals(3_000_000_017L, saved.get(0).getLong(argument)); assertEquals(3_000_000_017L, saved.get(0).getLong(entry.getSnapshotSlots()[1])); assertEquals(999L, saved.get(1).getLong(entry.getSnapshotSlots()[1])); compile(root.getCallTarget()); assertEquals(778L, Calls.target(caller.getCallTarget(), new Object[]{0L, 777L})); assertTrue(body.compiledBeforeDeopt, "The frame must be reconstructed from installed guest code"); assertEquals(16L, Calls.target(caller.getCallTarget(), new Object[]{0L, 15L})); assertEquals(777L, saved.get(3).getLong(entry.getSnapshotSlots()[1])); assertEquals(3_000_000_017L, saved.get(0).getLong(entry.getSnapshotSlots()[1])); assertReleased(language.getHandoffState().get());
        });
    }
}
