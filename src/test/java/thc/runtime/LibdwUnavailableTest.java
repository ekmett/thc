// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.interop.InteropLibrary;
import thc.runtime.Unit;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import thc.Json;
import thc.Language;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.LongStream;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarTestCalls.callScalarTestTarget;

/** Genuine declaration certificates in explicitly synthetic scalar consumers. */
@SuppressWarnings("unchecked")
class LibdwUnavailableTest {
    @Test void brokenOperandArrayRetainsItsJavaNullDiagnostic() throws Exception {
        var expression = new OriginalLibdwExpression(LibdwForeignOp.CLEAR, new Expr[0],
            new CoreRepresentation(CoreKind.LONG, true, true, List.of("IntRep"), null, null, null, null, null));
        var operands = OriginalLibdwExpression.class.getDeclaredField("operands");
        operands.setAccessible(true);
        var original = operands.get(expression);
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], new FrameLayout().build());
        try {
            operands.set(expression, null);
            var failure = assertThrows(NullPointerException.class, () -> expression.executeTuple(frame, new int[0], 0));
            assertEquals("Cannot read the array length because \"firstOperands\" is null", failure.getMessage());
        } finally { operands.set(expression, original); }
    }

    private final Map<String, Object> closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
    private final Map<String, Object> longRep = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
    private List<Map<String, Object>> declarations() throws Exception {
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/core/original-libdw-descriptors.json"))) {
            return (List<Map<String, Object>>) Json.parse(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    private String symbol(Map<String, Object> call) { return (String) ((Map<?, ?>) call.get("target")).get("symbol"); }
    private static Map<String, Object> changed(Map<String, Object> source, String key, Object value) {
        var result = new LinkedHashMap<>(source); result.put(key, value); return result;
    }
    private Map<String, Object> module() throws Exception { return module(binding -> {}); }
    private Map<String, Object> module(Consumer<Map<String, Object>> mutate) throws Exception {
        var bindings = new ArrayList<Map<String, Object>>();
        for (var declaration : declarations()) {
            String name = symbol(declaration);
            var tuple = (Map<String, Object>) declaration.get("resultRep");
            var components = (List<Map<String, Object>>) tuple.get("components");
            var formals = new ArrayList<Map<String, Object>>();
            var reps = (List<Map<String, Object>>) declaration.get("argumentReps");
            for (int i = 0; i < reps.size(); i++) formals.add(Map.of("id", name + "-arg-" + i,
                "lifted", false, "rep", changed(reps.get(i), "evaluated", true)));
            var call = List.of("app", List.of("var", name + "-synthetic-fcall-id", Map.of("rep", closure)),
                formals.stream().map(formal -> List.of("var", formal.get("id"), Map.of("rep", formal.get("rep")))).toList(),
                Collections.nCopies(formals.size(), false), false, false, Map.of("rep", tuple, "foreignCall", declaration));
            var ids = new ArrayList<String>();
            for (int i = 0; i < components.size(); i++) ids.add(name + "-field-" + i);
            var output = components.size() == 1 ? longRep : components.getLast();
            var result = components.size() == 1 ? List.of("lit", "int", "23", Map.of("rep", longRep))
                : List.of("var", ids.getLast(), Map.of("rep", output));
            String ctor = components.size() == 1 ? "tuple1" : "tuple2";
            var binders = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < components.size(); i++) binders.add(Map.of("id", ids.get(i), "lifted", false, "rep", components.get(i)));
            var body = List.of("case", call, name + "-tuple", List.of(List.of("data", ctor, ids, result, Map.of("binders", binders))),
                Map.of("rep", output, "binder", Map.of("id", name + "-tuple", "lifted", false, "rep", changed(tuple, "evaluated", true))));
            var binding = new LinkedHashMap<String, Object>(Map.of("id", name, "name", name, "arity", formals.size(),
                "lifted", true, "rep", closure, "expr", List.of("lam", formals, body, Map.of("rep", closure, "resultRep", output))));
            mutate.accept(binding); bindings.add(binding);
        }
        return Map.of("schema", 1, "module", "SyntheticLibdwConsumers", "unit", "test", "ghc", "9.14.1",
            "instrument", true, "bindings", bindings, "constructors", List.of(
                Map.of("id", "tuple1", "name", "Solo#", "kind", "unboxed-tuple", "arity", 1, "tag", 1),
                Map.of("id", "tuple2", "name", "(#,#)", "kind", "unboxed-tuple", "arity", 2, "tag", 1)));
    }
    private ExecutableProgram load(Language language, String backend, Map<String, Object> module) {
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }
    private Context context(boolean inlining) {
        return Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("compiler.Inlining", Boolean.toString(inlining)).option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
            .option("engine.SingleTierCompilationThreshold", "10000000").build();
    }
    private void valid(RootCallTarget target) throws Exception {
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
    }
    private void released(Language language) {
        var handoff = language.getHandoffState().get();
        assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth());
        assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
    }
    private List<Object> findLabel(Object value, String symbol) {
        if (value instanceof List<?> list) {
            if (list.size() >= 3 && list.subList(0, 3).equals(List.of("lit", "function-addr", symbol))) return (List<Object>) list;
            for (var element : list) { var found = findLabel(element, symbol); if (found != null) return found; }
        } else if (value instanceof Map<?, ?> map) {
            for (var element : map.values()) { var found = findLabel(element, symbol); if (found != null) return found; }
        }
        return null;
    }
    private List<Object> originalLabel(String symbol) throws Exception {
        var root = new File(System.getProperty("thc.projectRoot"));
        var source = OriginalStdioChecks.module(new File(root, "build/libdw-unavailable/foreign-labels.cbd"));
        var found = findLabel(source, symbol);
        if (found == null) throw new IllegalStateException("Missing genuine GHC " + symbol + " label");
        return found;
    }

    private Map<String, Object> cFinalizerConsumer(List<Object> label) {
        var state = Map.of("kind", "void", "primReps", List.of(), "evaluated", true);
        var address = Map.of("kind", "address", "primReps", List.of("AddrRep"), "evaluated", true);
        var weak = Map.of("kind", "object", "primReps", List.of("BoxedRep (Just Unlifted)"), "evaluated", true);
        var flag = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
        var closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
        var result = Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "components", List.of(state, flag),
            "primReps", List.of("IntRep"), "evaluated", true);
        var formals = List.of(Map.of("id", "pointer", "lifted", false, "rep", address),
            Map.of("id", "weak", "lifted", false, "rep", weak));
        var operands = List.of(label, List.of("var", "pointer", Map.of("rep", address)),
            List.of("lit", "int", "0", Map.of("rep", flag)), List.of("lit", "null-addr", "0", Map.of("rep", address)),
            List.of("var", "weak", Map.of("rep", weak)), List.of("void", Map.of("rep", state)));
        var call = List.of("app", List.of("prim", "addCFinalizerToWeak#"), operands,
            Collections.nCopies(6, false), false, false, Map.of("rep", result));
        var fields = List.of(Map.of("id", "outState", "lifted", false, "rep", state),
            Map.of("id", "outFlag", "lifted", false, "rep", flag));
        var body = List.of("case", call, "outTuple", List.of(List.of("data", "tuple2", List.of("outState", "outFlag"),
            List.of("var", "outFlag", Map.of("rep", flag)), Map.of("binders", fields))),
            Map.of("rep", flag, "binder", Map.of("id", "outTuple", "lifted", false, "rep", result)));
        return Map.of("schema", 1, "module", "SyntheticCFinalizerConsumer", "unit", "test", "ghc", "9.14.1",
            "instrument", true, "bindings", List.of(Map.of("id", "attach", "name", "attach", "arity", 2,
                "lifted", true, "rep", closure, "expr", List.of("lam", formals, body, Map.of("rep", closure, "resultRep", flag)))),
            "constructors", List.of(Map.of("id", "tuple2", "name", "(#,#)", "kind", "unboxed-tuple", "arity", 2, "tag", 1)));
    }

    @Test void actualGhcFunctionLabelAndTypedWeakRegistrationCompileOnBothBackends() throws Exception {
        var module = cFinalizerConsumer(originalLabel("libdwPoolRelease"));
        for (String backend : List.of("ast", "bytecode")) try (var context = Context.newBuilder("thc").allowNativeAccess(true)
                .allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = load(language, backend, module);
                var target = program.entryTarget("attach");
                var pointer = ManagedAddress.fromAllocation(ManagedAllocation.mutable(16, 8));
                java.util.function.LongSupplier call = () -> {
                    var weak = Language.currentState().getWeaks().make(new Object(), new Object(), null);
                    long added = (Long) callScalarTestTarget(target, new Object[]{0L, pointer, weak});
                    assertEquals(1L, added);
                    assertEquals(0L, Language.currentState().getWeaks().finalize(weak).getFlag());
                    released(language);
                    return added;
                };
                for (int i = 0; i < 3; i++) call.getAsLong();
                target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                valid(target);
                long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                assertEquals(1L, call.getAsLong());
                assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue());
                valid(target);
            } finally { context.leave(); }
        }
    }

    @Test void originalFreeLabelFinalizesOnlyOwnedMallocBasesOnBothBackends() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").equals("Linux") &&
            Set.of("amd64", "x86_64").contains(System.getProperty("os.arch")));
        var module = cFinalizerConsumer(originalLabel("free"));
        var proof = new CoreRepresentation(CoreKind.ADDRESS, true, true, List.of("AddrRep"), null, null, null, null, null);
        for (String backend : List.of("ast", "bytecode")) try (var context = Context.newBuilder("thc").allowNativeAccess(true)
                .allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = load(language, backend, module);
                var target = program.entryTarget("attach");
                var state = Language.currentState();
                var function = CFinalizerLabels.fromCore("free", proof);
                assertTrue(function.sameLocation(CFinalizerLabels.fromCore("free", proof)));
                assertThrows(RuntimeFault.class, function::toNativeBits);
                java.util.function.LongSupplier call = () -> {
                    var base = state.getNativeAllocations().malloc(16);
                    var weak = state.getWeaks().make(new Object(), new Object(), null);
                    assertThrows(RuntimeFault.class, () -> state.getWeaks().addCFinalizer(function, base.plus(1), 0, weak, state.cbits()));
                    assertThrows(RuntimeFault.class, () -> state.getWeaks().addCFinalizer(function,
                        ManagedAddress.fromAllocation(ManagedAllocation.mutable(16, 8)), 0, weak, state.cbits()));
                    var borrow = Objects.requireNonNull(base.nativeAllocation()).borrow();
                    try {
                        assertThrows(RuntimeFault.class, () -> state.getWeaks().addCFinalizer(function, base, 0, weak, state.cbits()));
                    } finally { borrow.close(); }
                    assertEquals(1L, (Long) callScalarTestTarget(target, new Object[]{0L, base, weak}));
                    assertEquals(1, state.getNativeAllocations().liveCount());
                    assertEquals(0L, state.getWeaks().finalize(weak).getFlag());
                    assertEquals(0, state.getNativeAllocations().liveCount());
                    assertThrows(RuntimeFault.class, () -> base.readWord8(0));
                    assertEquals(0L, state.getWeaks().finalize(weak).getFlag());
                    assertEquals(0L, (Long) callScalarTestTarget(target, new Object[]{0L, base, weak}),
                        "A finalized Weak# rejects registration before inspecting its freed base");
                    assertThrows(RuntimeFault.class, () -> state.getWeaks().addCFinalizer(function, base, 1, weak, state.cbits()));
                    released(language);
                    return 1L;
                };
                for (int i = 0; i < 3; i++) call.getAsLong();
                target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                valid(target);
                long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                assertEquals(1L, call.getAsLong());
                assertEquals(before + 2, ((Number) program.diagnostics().get("compiledEntries")).longValue());
                valid(target);
            } finally { context.leave(); }
        }
    }

    @Test void certifiedLabelsUseContextOwnedNativeCallablesOnlyOnExplicitFinalization() throws Exception {
        var proof = new CoreRepresentation(CoreKind.ADDRESS, true, true, List.of("AddrRep"), null, null, null, null, null);
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).build()) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState();
                var first = CFinalizerLabels.fromCore("libdwPoolRelease", proof);
                var second = CFinalizerLabels.fromCore("backtraceFree", proof);
                assertTrue(first.sameLocation(CFinalizerLabels.fromCore("libdwPoolRelease", proof)));
                assertFalse(first.sameLocation(second));
                assertFalse(first.sameLocation(ManagedAddress.nullAddress()));
                assertThrows(RuntimeFault.class, first::toNativeBits);
                assertThrows(RuntimeFault.class, () -> first.plus(0));
                assertThrows(RuntimeFault.class, () -> first.readWord8(0));
                assertThrows(RuntimeFault.class, () -> CFinalizerLabels.fromCore("enabled_capabilities", proof));
                assertThrows(RuntimeFault.class, () -> CFinalizerLabels.fromCore("libdwPoolRelease",
                    proof.copy(proof.getKind(), proof.getEvaluated(), proof.getPresent(), List.of("WordRep"),
                        proof.getComponents(), proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots())));
                var bytes = ManagedAllocation.mutable(16, 8);
                for (long index = 0; index < 16; index++) bytes.writeByte(index, index + 17);
                var pointer = ManagedAddress.fromAllocation(bytes);
                var interop = InteropLibrary.getUncached();
                var shifted = state.cbits().pointerTransport(pointer.plus(5), true);
                assertEquals((byte) 22, interop.readBufferByte(shifted, 0));
                assertEquals(11L, interop.getBufferSize(shifted));
                var immutable = ManagedAddress.fromHex("0112233445").plus(2);
                var projected = state.cbits().pointerTransport(immutable, true);
                assertEquals((byte) 0x23, interop.readBufferByte(projected, 0));
                interop.toNative(projected);
                assertEquals(immutable.toNativeBits(), interop.asPointer(projected));
                var weak = state.getWeaks().make(new Object(), new Object(), null);
                assertEquals(1L, state.getWeaks().addCFinalizer(first, pointer.plus(5), 0, weak, state.cbits()));
                assertEquals(1L, state.getWeaks().addCFinalizer(second, pointer.plus(5), 0, weak, state.cbits()));
                assertThrows(RuntimeFault.class, () -> state.getWeaks().addCFinalizer(first, pointer, 1, weak, state.cbits()));
                assertEquals(0L, state.getWeaks().finalize(weak).getFlag());
                assertEquals(LongStream.range(0, 16).map(index -> index + 17).boxed().toList(),
                    LongStream.range(0, 16).map(bytes::readByte).boxed().toList());
                assertEquals(0L, state.getWeaks().addCFinalizer(first, pointer, 0, weak, state.cbits()));
                assertEquals(0L, state.getWeaks().finalize(weak).getFlag());
            } finally { context.leave(); }
        }
    }

    @Test void nativeLabelsPreserveStorageAndRejectCrossContextAndExpiredOwners() {
        var first = Context.newBuilder("thc").allowNativeAccess(true).allowIO(IOAccess.NONE).build();
        ManagedAddress label;
        try {
            first.initialize("thc"); first.enter();
            try {
                var state = Language.currentState();
                label = state.cbits().finalizerLabel("backtraceFree");
                var buffers = new ArrayList<ManagedAddress>();
                for (boolean pinned : new boolean[]{false, true}) {
                    var pointer = ManagedAddress.fromAllocation(ManagedAllocation.mutable(16, 8, pinned));
                    pointer.fill(16, 165); buffers.add(pointer);
                }
                buffers.add(ManagedAddress.fromHex("001122334455"));
                for (var pointer : buffers) {
                    var before = LongStream.range(0, pointer.availableBytes()).map(pointer::readWord8).boxed().toList();
                    for (String symbol : List.of("libdwPoolRelease", "backtraceFree")) {
                        var weak = state.getWeaks().make(new Object(), new Object(), null);
                        assertEquals(1L, state.getWeaks().addCFinalizer(state.cbits().finalizerLabel(symbol), pointer.plus(2), 0, weak, state.cbits()));
                        assertEquals(0L, state.getWeaks().finalize(weak).getFlag());
                        assertEquals(before, LongStream.range(0, pointer.availableBytes()).map(pointer::readWord8).boxed().toList());
                    }
                }
                Objects.requireNonNull(label.finalizerFunction()).invoke(ManagedAddress.nullAddress());
                assertThrows(RuntimeFault.class, () -> Objects.requireNonNull(label.finalizerFunction())
                    .invoke(ManagedAddress.unownedNumeric(1)));
                if (WindowsDirectoryStreams.supportedHost()) {
                    // Also exercise an actual context-owned native allocation,
                    // including registration before its owner is retired.
                    var address = state.getWindowsCodePages().message(2);
                    var weak = state.getWeaks().make(new Object(), new Object(), null);
                    state.getWeaks().addCFinalizer(label, address, 0, weak, state.cbits());
                    Objects.requireNonNull(label.finalizerFunction()).invoke(address.plus(2));
                    state.getWindowsCodePages().localFree(address);
                    assertThrows(RuntimeFault.class, () -> state.getWeaks().finalize(weak));
                    assertEquals(0L, state.getWeaks().finalize(weak).getFlag());
                }
                assertEquals(0, state.getWeaks().retainedCount());
            } finally { first.leave(); }
            try (var second = Context.newBuilder("thc").allowNativeAccess(true).allowIO(IOAccess.NONE).build()) {
                second.initialize("thc"); second.enter();
                try {
                    assertThrows(RuntimeFault.class, () -> Objects.requireNonNull(label.finalizerFunction()).invoke(ManagedAddress.nullAddress()));
                    var state = Language.currentState();
                    var weak = state.getWeaks().make(new Object(), new Object(), null);
                    assertThrows(RuntimeFault.class, () -> state.getWeaks().addCFinalizer(label, ManagedAddress.nullAddress(), 0, weak, state.cbits()));
                    assertEquals(0L, state.getWeaks().finalize(weak).getFlag());
                } finally { second.leave(); }
            }
        } finally { first.close(); }
        try (var denied = Context.newBuilder("thc").allowIO(IOAccess.NONE).build()) {
            denied.initialize("thc"); denied.enter();
            try { assertThrows(RuntimeFault.class, () -> Language.currentState().cbits().finalizerLabel("backtraceFree")); }
            finally { denied.leave(); }
        }
    }

    @Test void unavailableBackendMatchesNativeFailureAndNeverTouchesLocationIncludingFirstCompiledEntry() throws Exception {
        for (String backend : List.of("ast", "bytecode")) for (boolean inlining : new boolean[]{false, true}) try (var context = context(inlining)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = load(language, backend, module());
                var targets = new LinkedHashMap<String, RootCallTarget>();
                for (var op : LibdwForeignOp.values()) targets.put(op.getSymbol(), program.entryTarget(op.getSymbol()));
                var bytes = ManagedAllocation.mutable(64, 8);
                for (long offset = 0; offset < 64; offset++) bytes.writeByte(offset, 165L);
                var pointer = ManagedAddress.fromAllocation(bytes);
                var nil = ManagedAddress.nullAddress();
                class Exercise {
                    boolean compiled;
                    Object call(String name, Object... args) throws Exception {
                        long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                        var target = targets.get(name);
                        var arguments = new Object[args.length + 1];
                        arguments[0] = 0L; System.arraycopy(args, 0, arguments, 1, args.length);
                        var result = callScalarTestTarget(target, arguments);
                        if (compiled) {
                            assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue(), backend + "/" + name);
                            valid(target);
                        }
                        released(language);
                        return result;
                    }
                    void run() throws Exception {
                        assertTrue(((ManagedAddress) call("libdwPoolTake", Unit.INSTANCE)).sameLocation(nil));
                        for (var session : List.of(nil, pointer)) {
                            assertTrue(((ManagedAddress) call("libdwGetBacktrace", session, Unit.INSTANCE)).sameLocation(nil));
                            assertEquals(1, call("libdwLookupLocation", session, pointer, session, Unit.INSTANCE));
                            assertEquals(Collections.nCopies(64, 165L), LongStream.range(0, 64).map(bytes::readByte).boxed().toList());
                        }
                        // Failure does not require a writable Location, or dereference any pointer.
                        assertEquals(1, call("libdwLookupLocation", nil, nil, nil, Unit.INSTANCE));
                        assertEquals(23L, call("libdwPoolClear", Unit.INSTANCE));
                    }
                }
                var exercise = new Exercise();
                for (int i = 0; i < 3; i++) exercise.run();
                for (var target : targets.values()) { target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target); }
                exercise.compiled = true;
                exercise.run(); // no settling or recompilation after installation
                exercise.compiled = false;
                assertThrows(RuntimeFault.class, () -> exercise.call("libdwPoolTake", 0L));
                assertThrows(RuntimeFault.class, () -> exercise.call("libdwGetBacktrace", 0L, Unit.INSTANCE));
                assertThrows(RuntimeFault.class, () -> exercise.call("libdwLookupLocation", nil, 0L, nil, Unit.INSTANCE));
                released(language);
            } finally { context.leave(); }
        }
    }

    private void walkLabels(Object value, List<List<String>> labels) {
        if (value instanceof Map<?, ?> map) map.values().forEach(element -> walkLabels(element, labels));
        else if (value instanceof List<?> list) {
            if (list.size() >= 2 && "lit".equals(list.getFirst()) && Set.of("function-addr", "data-addr").contains(list.get(1))) {
                var proof = (Map<?, ?>) ((Map<?, ?>) list.get(3)).get("rep");
                assertEquals("address", proof.get("kind"));
                assertEquals(List.of("AddrRep"), proof.get("primReps"));
                labels.add(List.of((String) list.get(1), (String) list.get(2)));
            }
            list.forEach(element -> walkLabels(element, labels));
        }
    }
    @Test void nativeOracleUsesTheSelectedDisabledDwarfConfigurationAndOriginalHaskellWrapper() throws Exception {
        var root = new File(System.getProperty("thc.projectRoot"));
        String prefix = "build/libdw-unavailable";
        var manifest = (Map<String, Object>) Json.parse(Files.readString(root.toPath().resolve(prefix + "/manifest.json")));
        assertEquals("9.14.1", manifest.get("ghc"));
        OriginalStdioChecks.hashes(root, manifest.get("inputHashes"), Set.of(
            "t/fixtures/compiler/LibdwUnavailableNative.hs", "t/fixtures/compiler/CFinalizerNative.hs",
            "t/fixtures/compiler/ForeignLabelAudit.hs", "src/compiler/THC/Plugin.hs", "t/haskell-fixtures/LibdwUnavailableFixtures.hs"), null);
        OriginalStdioChecks.hashes(root, manifest.get("artifactHashes"), Set.of(prefix + "/oracle.json", prefix + "/foreign-labels.cbd"), prefix + "/");
        var oracle = (Map<String, Object>) Json.parse(Files.readString(root.toPath().resolve(prefix + "/oracle.json")));
        assertEquals(false, oracle.get("useLibdw"));
        assertEquals(Collections.nCopies(8, true), oracle.get("observations"));
        assertEquals(Collections.nCopies(18, true), oracle.get("cFinalizerObservations"));
        var labels = new ArrayList<List<String>>();
        walkLabels(OriginalStdioChecks.module(new File(root, prefix + "/foreign-labels.cbd")), labels);
        assertEquals(List.of(List.of("data-addr", "enabled_capabilities"), List.of("function-addr", "backtraceFree"),
            List.of("function-addr", "free"), List.of("function-addr", "libdwPoolRelease")),
            labels.stream().distinct().sorted(Comparator.<List<String>, String>comparing(pair -> pair.get(0)).thenComparing(pair -> pair.get(1))).toList());
    }

    @Test void declarationAndStoredOperandProofsCannotBeForged() throws Exception {
        for (var declaration : declarations()) {
            var output = declaration.get("resultRep");
            var args = (List<Map<String, Object>>) declaration.get("argumentReps");
            var flags = Collections.nCopies(args.size(), false);
            var operation = Objects.requireNonNull(CoreLibdwForeign.validate(Map.of("rep", output, "foreignCall", declaration), args, flags, output));
            assertEquals(symbol(declaration), operation.getSymbol());
            var target = (Map<String, Object>) declaration.get("target");
            var bad = List.of(changed(declaration, "schema", 1.0), changed(declaration, "safety", "safe"),
                changed(declaration, "arity", 0), changed(declaration, "convention", "capi"),
                changed(declaration, "target", changed(target, "unit", "main")),
                changed(declaration, "target", changed(target, "isFunction", false)), changed(declaration, "resultRep", longRep));
            for (var incorrect : bad) assertThrows(RuntimeFault.class, () ->
                CoreLibdwForeign.validate(Map.of("rep", output, "foreignCall", incorrect), args, flags, output));
            for (int index = 0; index < operation.getArguments().size(); index++) {
                int operand = index;
                String primitive = operation.getArguments().get(index);
                var proof = new CoreRepresentation(primitive == null ? CoreKind.VOID : CoreKind.ADDRESS, true, true,
                    primitive == null ? List.of() : List.of(primitive), null, null, null, null, null);
                CoreLibdwForeign.validateOperand(operation, index, proof, proof);
                assertThrows(RuntimeFault.class, () -> CoreLibdwForeign.validateOperand(operation, operand, proof,
                    proof.copy(CoreKind.LONG, proof.getEvaluated(), proof.getPresent(), List.of("IntRep"),
                        proof.getComponents(), proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots())));
            }
        }
        for (String backend : List.of("ast", "bytecode")) try (var context = context(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var bad = module(binding -> {
                    var lambda = new ArrayList<>((List<Object>) binding.get("expr"));
                    var formals = new ArrayList<>((List<Map<String, Object>>) lambda.get(1));
                    formals.set(0, changed(formals.get(0), "rep", longRep));
                    lambda.set(1, formals); binding.put("expr", lambda);
                });
                assertThrows(RuntimeFault.class, () -> load(language, backend, bad));
                released(language);
            } finally { context.leave(); }
        }
    }
}
