// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import thc.runtime.Unit;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.ContextProfile;
import thc.Language;
import thc.Main;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Fixture-free capability publication, ownership and safe-completion controls.
 * Inputs are typed Core maps and runtime operands; no generated products are read or written.
 */
class EnabledCapabilitiesTest {
    private final Map<String, Object> address = Map.of("kind", "address", "primReps", List.of("AddrRep"), "evaluated", true);
    private final Map<String, Object> state = Map.of("kind", "void", "primReps", List.of(), "evaluated", true);
    private final Map<String, Object> offset = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
    private final Map<String, Object> word32 = Map.of("kind", "long", "primReps", List.of("Word32Rep"), "evaluated", true);
    private final Map<String, Object> closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
    private final Map<String, Object> result = Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "evaluated", true,
        "primReps", List.of("Word32Rep"), "components", List.of(state, word32));
    private final List<Object> label = List.of("lit", "data-addr", "enabled_capabilities", Map.of("rep", address));

    private Map<String, Object> module() {
        var parameter = Map.of("id", "s", "lifted", false, "rep", state);
        var call = List.of("app", List.of("prim", "readWord32OffAddr#"),
            List.of(label, List.of("lit", "int", "0", Map.of("rep", offset)),
                List.of("var", "s", Map.of("rep", state))),
            List.of(false, false, false), false, false, Map.of("rep", result));
        var fields = List.of(Map.of("id", "next", "lifted", false, "rep", state),
            Map.of("id", "value", "lifted", false, "rep", word32));
        var body = List.of("case", call, "pair", List.of(List.of("data", "tuple2", List.of("next", "value"),
            List.of("var", "value", Map.of("rep", word32)), Map.of("binders", fields))),
            Map.of("rep", word32, "binder", Map.of("id", "pair", "lifted", false, "rep", result)));
        return Map.of("instrument", true,
            "constructors", List.of(Map.of("id", "tuple2", "kind", "unboxed-tuple", "arity", 2, "tag", 1)),
            "bindings", List.of(Map.of("id", "read", "name", "read", "arity", 1,
                "lifted", true, "rep", closure,
                "expr", List.of("lam", List.of(parameter), body, Map.of("rep", closure, "resultRep", word32)))));
    }

    private Map<String, Object> setterModule() {
        var tuple = Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "evaluated", false,
            "primReps", List.of(), "components", List.of(state));
        var declaredWord = new LinkedHashMap<>(word32); declaredWord.put("evaluated", false);
        var declaredState = new LinkedHashMap<>(state); declaredState.put("evaluated", false);
        var descriptor = Map.of("schema", 1L,
            "target", Map.of("kind", "static", "symbol", "setNumCapabilities", "unit", "ghc-internal", "isFunction", true),
            "convention", "ccall", "safety", "safe", "arity", 2L, "suppliedArity", 2L,
            "argumentReps", List.of(declaredWord, declaredState), "resultRep", tuple);
        var call = List.of("app", List.of("var", "original-setter", Map.of("rep", closure)),
            List.of(List.of("var", "count", Map.of("rep", word32)), List.of("void", Map.of("rep", state))),
            List.of(false, false), false, false, Map.of("rep", tuple, "foreignCall", descriptor));
        return Map.of("instrument", true, "constructors", List.of(),
            "bindings", List.of(Map.of("id", "set", "name", "set", "arity", 1, "lifted", true, "rep", closure,
                "expr", List.of("lam", List.of(Map.of("id", "count", "lifted", false, "rep", word32)),
                    call, Map.of("rep", closure, "resultRep", tuple)))));
    }

    @Test void originalSetterPublishesWord32CountAndRejectsZeroInBothBackends() throws Exception {
        for (String backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var threads = Language.currentState().getThreads();
                ExecutableProgram program = backend.equals("ast") ? new Program(language, setterModule(), false)
                    : new BytecodeProgram(language, setterModule(), false);
                var target = program.entryTarget("set");
                var root = (GuestRoot) target.getRootNode();
                var destination = Truffle.getRuntime().createVirtualFrame(new Object[0], root.getFrameDescriptor());
                java.util.function.IntConsumer set = count -> root.getTupleResult().consume(destination,
                    ScalarTestCalls.callScalarTestTarget(target, new Object[]{0L, count}), new int[0], 0);
                var cell = CoreDataLabels.fromCore("enabled_capabilities", CoreRepresentations.parse(address));
                long processors = threads.getCpuAffinity().getCount();
                threads.enterCurrent(null, false, true, null);
                try {
                    set.accept(2);
                    assertEquals(2L, Integer.toUnsignedLong(ManagedAddressRead.WORD32.readInt(cell, 0)));
                    target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target);
                    long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                    set.accept(3);
                    assertEquals(3L, Integer.toUnsignedLong(ManagedAddressRead.WORD32.readInt(cell, 0)));
                    assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before,
                        "The first call after compilation must enter installed guest code"); valid(target);
                    assertThrows(RuntimeFault.class, () -> set.accept(0));
                    assertEquals(3L, threads.capabilityCount(), "Rejected zero must preserve the published count");
                    set.accept(-1);
                    assertEquals(0xffff_ffffL, threads.capabilityCount(), "Word32 uses the unsigned carrier");
                    assertEquals(processors, threads.getCpuAffinity().getCount());
                } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); }
            } finally { context.leave(); }
        }
    }

    @Test void astSafeCompletionPublishesOnceAndHonorsEveryLogicalMask() throws Exception {
        for (var mask : MaskingState.values()) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var wordProof = CoreRepresentations.parse(word32); var stateProof = CoreRepresentations.parse(state);
                var tupleProof = CoreRepresentations.parse(Map.of("kind", "unknown", "aggregate", "unboxed-tuple",
                    "evaluated", true, "primReps", List.of(), "components", List.of(state)));
                var layout = new FrameLayout();
                int countSlot = layout.bind("count", FrameLayout.carrierKind(wordProof));
                int stateSlot = layout.bind("state", FrameLayout.carrierKind(stateProof));
                int[] evaluations = {0};
                var countInput = new Expr() {
                    @Override public Object execute(VirtualFrame frame) { evaluations[0]++; return 3; }
                }.proven(wordProof);
                var leaf = new RtsEventForeignExpression(RtsEventForeignOp.CAPABILITIES,
                    new Expr[]{new LocalRead(countSlot, false).proven(wordProof), new LocalRead(stateSlot, false).proven(stateProof)}, tupleProof);
                // Exercise the completed-call boundary with an explicit operand
                // scope; the separate setter test covers both full lowering paths.
                var sequence = new AstOperands(new LocalBinding[]{new LocalBinding(countSlot, countInput, false),
                    new LocalBinding(stateSlot, new Literal(Unit.INSTANCE).proven(stateProof), false)},
                    new int[]{countSlot, stateSlot}, leaf);
                var root = new FunctionRoot(language, layout.build(), "safe capability completion", null,
                    new int[0], new int[0], new int[0], sequence, new Metrics(false), new CoreRepresentation[0],
                    tupleProof, null, new boolean[0], null, new TupleShape(tupleProof, language), new int[0], null,
                    true, new int[0][], false, FunctionRootRole.FUNCTION, false);
                root.getCallTarget();
                var threads = Language.currentState().getThreads(); threads.enterCurrent(null, false, true, null);
                try {
                    SynchronousMasking.set(root, mask); var self = threads.currentIdentity();
                    var incoming = CompletableFuture.supplyAsync(() -> threads.send(self, "after setter")).get(5, TimeUnit.SECONDS);
                    var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], root.getFrameDescriptor());
                    if (mask == MaskingState.UNMASKED) {
                        var cut = assertThrows(AstCapture.class, () -> sequence.executeTuple(frame, new int[0], 0));
                        assertSame(incoming, cut.getYielded()); assertEquals(3L, threads.capabilityCount());
                        incoming.acknowledge(); var saved = cut.freeze(root, frame.materialize()); threads.setCapabilityCount(5);
                        assertNull(saved.continueWith(Unit.INSTANCE));
                        assertEquals(5L, threads.capabilityCount(), "Resumption must not replay the completed setter");
                        assertThrows(RuntimeFault.class, () -> saved.continueWith(Unit.INSTANCE));
                    } else {
                        assertNull(sequence.executeTuple(frame, new int[0], 0)); assertEquals(3L, threads.capabilityCount());
                        assertEquals(AsyncRequestState.PENDING, incoming.getState());
                        SynchronousMasking.set(root, MaskingState.UNMASKED); assertSame(incoming, threads.poll(root, false)); incoming.acknowledge();
                    }
                    assertEquals(1, evaluations[0]); assertEquals(AsyncRequestState.ACKNOWLEDGED, incoming.getState());
                } finally { SynchronousMasking.set(root, MaskingState.UNMASKED); threads.leaveCurrent(GuestThreadStatus.FINISHED); }
            } finally { context.leave(); }
        }
    }

    private Context context() { return Main.withContextProfile(Context.newBuilder("thc"), ContextProfile.SYNCHRONOUS_TEST).build(); }
    private void valid(RootCallTarget target) throws Exception {
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
    }

    @Test void liveLabelUsesExactWord32ReadAndOwnedContextInBothBackends() throws Exception {
        for (String backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var threads = Language.currentState().getThreads();
                long cpuCount = threads.getCpuAffinity().getCount();
                assertTrue(cpuCount >= 1 && cpuCount <= Runtime.getRuntime().availableProcessors());
                threads.enterCurrent(null, false, true, null);
                try {
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, module()) : new BytecodeProgram(language, module());
                    var target = program.entryTarget("read");
                    LongSupplier read = () -> Integer.toUnsignedLong((Integer) Calls.target(target, new Object[]{0L, Unit.INSTANCE}));
                    assertEquals(cpuCount, read.getAsLong());
                    target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                    valid(target);
                    long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                    assertEquals(cpuCount, read.getAsLong());
                    assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before,
                        "The first call after compilation must enter installed guest code");
                    valid(target);

                    var workerFailure = new AtomicReference<Throwable>();
                    var worker = new Thread(() -> {
                        try { threads.enterCurrent(null, false, true, null); threads.leaveCurrent(GuestThreadStatus.FINISHED); }
                        catch (Throwable failure) { workerFailure.set(failure); }
                    });
                    worker.start(); worker.join();
                    if (workerFailure.get() != null) throw new AssertionError("Guest carrier registration failed", workerFailure.get());
                    assertEquals(cpuCount, read.getAsLong(), "Guest carriers share the available CPU capacity");
                    valid(target);

                    var cell = CoreDataLabels.fromCore("enabled_capabilities", CoreRepresentations.parse(address));
                    assertEquals(cpuCount, Integer.toUnsignedLong(ManagedAddressRead.WORD32.readInt(cell, 0)));
                    for (var operation : ManagedAddressRead.values()) if (operation != ManagedAddressRead.WORD32)
                        assertThrows(RuntimeFault.class, () -> {
                            if (operation.isInt()) operation.readInt(cell, 0); else operation.read(cell, 0);
                        });
                    assertThrows(RuntimeFault.class, () -> ManagedAddressRead.WORD32.readInt(cell, 1));
                    assertThrows(RuntimeFault.class, () -> cell.readWord8(0));
                    assertThrows(RuntimeFault.class, () -> cell.writeWord8(0, 1));
                    assertThrows(RuntimeFault.class, () -> cell.writeNativeScalar(0, 4, 1));
                    assertThrows(RuntimeFault.class, () -> cell.plus(1));
                    assertThrows(RuntimeFault.class, cell::toNativeBits);
                    for (String bad : List.of("other_symbol", "enabled_capabilities_extra"))
                        assertThrows(RuntimeFault.class, () -> CoreDataLabels.fromCore(bad, CoreRepresentations.parse(address)));
                    var wrongRep = new LinkedHashMap<>(address);
                    wrongRep.put("primReps", List.of("WordRep"));
                    assertThrows(RuntimeFault.class, () -> CoreDataLabels.fromCore("enabled_capabilities", CoreRepresentations.parse(wrongRep)));
                    var unevaluated = new LinkedHashMap<>(address);
                    unevaluated.put("evaluated", false);
                    assertThrows(RuntimeFault.class, () -> CoreDataLabels.fromCore("enabled_capabilities", CoreRepresentations.parse(unevaluated)));

                    ManagedAddress foreignCell;
                    try (var foreign = context()) {
                        foreign.initialize("thc"); foreign.enter();
                        try {
                            assertThrows(RuntimeFault.class, () -> ManagedAddressRead.WORD32.readInt(cell, 0));
                            foreignCell = CoreDataLabels.fromCore("enabled_capabilities", CoreRepresentations.parse(address));
                        } finally { foreign.leave(); }
                    }
                    assertEquals(cpuCount, Integer.toUnsignedLong(ManagedAddressRead.WORD32.readInt(cell, 0)));
                    assertTrue(cell.sameLocation(cell));
                    assertThrows(RuntimeFault.class, () -> cell.sameLocation(foreignCell));
                } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); }
            } finally { context.leave(); }
        }
    }
}
