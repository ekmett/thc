// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.UncheckedIOException;
import java.io.IOException;
import thc.runtime.Unit;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.ContextProfile;
import thc.Language;
import thc.Main;
import static org.junit.jupiter.api.Assertions.*;

class RtsFlagsTest {
    private final Map<String, Object> address = Map.of("kind", "address", "primReps", List.of("AddrRep"), "evaluated", true);
    private final Map<String, Object> state = Map.of("kind", "void", "primReps", List.of(), "evaluated", true);
    private final Map<String, Object> integer = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
    private final Map<String, Object> byteRep = Map.of("kind", "long", "primReps", List.of("Word8Rep"), "evaluated", true);
    private final Map<String, Object> closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
    private CoreRepresentation proof() { return CoreRepresentations.parse(address); }

    static TargetLayout layout() {
        var receipt = System.getenv("THC_TEST_RTS_LAYOUT");
        if (receipt == null) return layout("selected-test-abi");
        try { return TargetLayout.fromDocument(thc.Json.parse(Files.readString(Path.of(receipt)))); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    @SuppressWarnings("unchecked")
    private static TargetLayout layout(String abi) {
        var document = new LinkedHashMap<>(StackInfoTestLayout.document(flagFields()));
        var compiler = new LinkedHashMap<>((Map<String, Object>) document.get("compiler"));
        compiler.put("abi", abi);
        document.put("compiler", compiler);
        return TargetLayout.fromDocument(document);
    }

    private static Map<String, Object> flagFields() {
        // Synthetic controls deliberately differ from the old Linux offset403.
        var fields = StackInfoTestLayout.fields();
        fields.put("schema", 2); fields.put("rtsFlagsBytes", 256); fields.put("traceFlagsBytes", 24);
        fields.put("rtsTraceFlagsOffset", 101); fields.put("rtsTraceFlagsBytes", 24);
        fields.put("traceUserOffset", 7); fields.put("traceUserBytes", 1);
        return fields;
    }
    private static long userOffset(TargetLayout layout) {
        return (long) layout.offset("rtsTraceFlagsOffset") + layout.offset("traceUserOffset");
    }

    private static Context context() { return context(new ByteArrayOutputStream()); }
    private static Context context(ByteArrayOutputStream output) {
        return Main.withContextProfile(Context.newBuilder("thc"), ContextProfile.SYNCHRONOUS_TEST).err(output).build();
    }
    private static void valid(RootCallTarget target) throws ReflectiveOperationException {
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
    }
    private static void compile(RootCallTarget target) throws ReflectiveOperationException {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
        valid(target);
        var runtime = Truffle.getRuntime();
        runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"))
            .invoke(runtime, target);
        valid(target);
    }

    @SuppressWarnings("unchecked")
    @Test void selectedProducerLayoutDeterminesTheOnlyReadableFlagByte() {
        var fields = StackInfoTestLayout.fields();
        fields.put("schema", 2);
        fields.put("rtsFlagsBytes", 256);
        fields.put("traceFlagsBytes", 24);
        fields.put("rtsTraceFlagsOffset", 101);
        fields.put("rtsTraceFlagsBytes", 24);
        fields.put("traceUserOffset", 7);
        fields.put("traceUserBytes", 1);
        var document = new LinkedHashMap<>(StackInfoTestLayout.document(fields));
        var compiler = new LinkedHashMap<>((Map<String, Object>) document.get("compiler"));
        compiler.put("abi", "7e95");
        document.put("compiler", compiler);
        var layout = TargetLayout.fromDocument(document);
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                Language.currentState().getRuntimeTrace().control(500, 1);
                var base = CoreDataLabels.fromCore("RtsFlags", proof(), layout);
                assertEquals(1L, base.plus(101).plus(7).readWord8(0));
                assertEquals(1L, base.readWord8(108));
                assertThrows(RuntimeFault.class, () -> base.readWord8(403));
            } finally { context.leave(); }
        }
    }

    @Test void invalidWidthsBoundsSchemasAndProducerReplacementReject() {
        for (var change : List.of(Map.of("rtsFlagsBytes", 0), Map.of("traceFlagsBytes", 0),
                Map.of("rtsTraceFlagsBytes", 23), Map.of("traceUserBytes", 0), Map.of("traceUserBytes", 2),
                Map.of("rtsTraceFlagsOffset", 233), Map.of("traceUserOffset", 24), Map.of("schema", 3))) {
            var fields = flagFields(); fields.putAll(change);
            assertThrows(IllegalArgumentException.class, () -> TargetLayout.fromDocument(StackInfoTestLayout.document(fields)));
        }
        var first = layout("selected-test-abi");
        var fields = flagFields(); fields.put("traceUserOffset", 8);
        var differentOffset = TargetLayout.fromDocument(StackInfoTestLayout.document(fields));
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                Language.currentState().getRuntimeTrace().control(500, 1);
                var address = CoreDataLabels.fromCore("RtsFlags", proof(), first);
                assertThrows(RuntimeFault.class, () -> CoreDataLabels.fromCore("RtsFlags", proof(), layout("another-build")));
                assertThrows(RuntimeFault.class, () -> CoreDataLabels.fromCore("RtsFlags", proof(), differentOffset));
                assertSame(address, CoreDataLabels.fromCore("RtsFlags", proof(), first));
                assertEquals(1L, address.readWord8(108));
            } finally { context.leave(); }
        }
    }

    @Test void composedFieldViewsAreReadOnlyAndContextBound() {
        var layout = layout();
        long user = userOffset(layout);
        var first = context(); var second = context();
        ManagedAddress base;
        ManagedAddress field;
        first.initialize("thc"); second.initialize("thc"); first.enter();
        try {
            Language.currentState().getRuntimeTrace().control(500, 1);
            base = CoreDataLabels.fromCore("RtsFlags", proof(), layout);
            assertSame(base, CoreDataLabels.fromCore("RtsFlags", proof(), layout));
            field = base.plus(layout.offset("rtsTraceFlagsOffset")).plus(layout.offset("traceUserOffset"));
            assertEquals(1L, field.readWord8(0));
            assertEquals(1L, base.readWord8(user));
            assertEquals(1L, base.plus(user + 1).readWord8(-1));
            assertTrue(field.sameLocation(base.plus(user)));
            assertFalse(field.sameLocation(base));
            for (long offset : new long[]{0L, layout.offset("rtsTraceFlagsOffset"), user - 1, user + 1, -1L, Long.MAX_VALUE}) {
                assertEquals("Unsupported RtsFlags byte field at offset " + offset,
                    assertThrows(RuntimeFault.class, () -> base.readWord8(offset)).getMessage());
                assertEquals("Unsupported RtsFlags byte field at offset " + offset,
                    assertThrows(RuntimeFault.class, () -> base.readWord8Int(offset)).getMessage());
            }
            assertThrows(RuntimeFault.class, () -> base.plus(Long.MAX_VALUE).plus(1));
            assertThrows(RuntimeFault.class, () -> field.readWord8(Long.MAX_VALUE));
            for (var operation : ManagedAddressRead.values())
                assertThrows(RuntimeFault.class, () -> operation.read(field, 0));
            assertThrows(RuntimeFault.class, () -> field.writeWord8(0, 0));
            assertThrows(RuntimeFault.class, () -> field.writeNativeScalar(0, 4, 0));
            assertThrows(RuntimeFault.class, () -> AtomicAddressOp.READ.numeric(field, 0, 0));
            assertThrows(RuntimeFault.class, field::toNativeBits);
            assertThrows(RuntimeFault.class, field::rawBacking);
            assertThrows(RuntimeFault.class, field::availableBytes);
            assertThrows(RuntimeFault.class, () -> field.compareWithinAllocation(base));
        } finally { first.leave(); }
        second.enter();
        try {
            Language.currentState().getRuntimeTrace().control(500, 1);
            assertEquals(1L, CoreDataLabels.fromCore("RtsFlags", proof(), layout).readWord8(user));
            assertThrows(RuntimeFault.class, () -> field.readWord8(0));
            assertEquals("Compiler RTS cell belongs to another or closed THC context",
                assertThrows(RuntimeFault.class, () -> base.readWord8Int(402)).getMessage());
            assertThrows(RuntimeFault.class, () -> base.plus(0));
            assertThrows(RuntimeFault.class, () -> field.sameLocation(field));
        } finally { second.leave(); second.close(); first.close(); }
        assertThrows(RuntimeFault.class, () -> field.readWord8(0));
        assertEquals("Compiler RTS cell belongs to another or closed THC context",
            assertThrows(RuntimeFault.class, () -> base.readWord8Int(402)).getMessage());
        assertThrows(RuntimeFault.class, () -> field.plus(0));
    }

    @Test void missingLayoutAndFalseRepresentationsDoNotAdmitTheLabel() {
        var valid = layout();
        var wrongAbi = layout("another-build");
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                assertThrows(RuntimeFault.class, () -> CoreDataLabels.fromCore("RtsFlags", proof()));
                assertThrows(RuntimeFault.class, () -> CoreDataLabels.fromCore("RtsFlags", proof(), StackInfoTestLayout.layout()));
                CoreDataLabels.fromCore("RtsFlags", proof(), valid);
                assertThrows(RuntimeFault.class, () -> CoreDataLabels.fromCore("RtsFlags", proof(), wrongAbi));
                assertThrows(RuntimeFault.class, () -> CoreDataLabels.fromCore("RtsFlags", null, valid));
                assertThrows(RuntimeFault.class, () -> {
                    var proof = proof();
                    CoreDataLabels.fromCore("RtsFlags", proof.copy(proof.getKind(), false, proof.getPresent(), proof.getPrimReps(),
                        proof.getComponents(), proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()), valid);
                });
                assertThrows(RuntimeFault.class, () -> CoreDataLabels.fromCore("RtsFlags", CoreRepresentations.parse(integer), valid));
                assertThrows(RuntimeFault.class, () -> CoreDataLabels.fromCore("RtsFlags_extra", proof(), valid));
            } finally { context.leave(); }
        }
    }

    private List<Object> plus(Object base, long amount) {
        return List.of("app", List.of("prim", "plusAddr#"),
            List.of(base, List.of("lit", "int", Long.toString(amount), Map.of("rep", integer))),
            List.of(false, false), false, false, Map.of("rep", address));
    }
    private Map<String, Object> getterModule(TargetLayout layout) {
        var label = List.of("lit", "data-addr", "RtsFlags", Map.of("rep", address));
        var tuple = Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "evaluated", true,
            "primReps", List.of("Word8Rep"), "components", List.of(state, byteRep));
        var call = List.of("app", List.of("prim", "readWord8OffAddr#"),
            List.of(plus(plus(label, layout.offset("rtsTraceFlagsOffset")), layout.offset("traceUserOffset")), List.of("lit", "int", "0", Map.of("rep", integer)),
                List.of("var", "s", Map.of("rep", state))), List.of(false, false, false), false, false, Map.of("rep", tuple));
        var fields = List.of(Map.of("id", "next", "lifted", false, "rep", state),
            Map.of("id", "value", "lifted", false, "rep", byteRep));
        var body = List.of("case", call, "pair", List.of(List.of("data", "tuple2", List.of("next", "value"),
            List.of("var", "value", Map.of("rep", byteRep)), Map.of("binders", fields))),
            Map.of("rep", byteRep, "binder", Map.of("id", "pair", "lifted", false, "rep", tuple)));
        return Map.of("targetLayout", layout, "instrument", true,
            "constructors", List.of(Map.of("id", "tuple2", "kind", "unboxed-tuple", "arity", 2, "tag", 1)),
            "bindings", List.of(Map.of("id", "read", "name", "read", "arity", 1, "lifted", true,
                "rep", closure, "expr", List.of("lam", List.of(Map.of("id", "s", "lifted", false, "rep", state)),
                    body, Map.of("rep", closure, "resultRep", byteRep)))));
    }

    @SuppressWarnings("unchecked")
    @Test void demandPreparationDoesNotClaimTheRuntimeFlagsLayout() {
        var preparedLayout = layout("prepared-producer");
        var runtimeLayout = layout("runtime-producer");
        for (String backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var strict = Map.<String, Object>of("id", "label", "name", "label", "arity", 0, "lifted", false,
                    "rep", address, "expr", List.of("lit", "data-addr", "RtsFlags", Map.of("rep", address)));
                var module = getterModule(preparedLayout);
                var read = (Map<String, Object>) ((List<?>) module.get("bindings")).getFirst();
                var definitions = Map.of("read", read, "label", strict);
                var demand = new CoreDemandBindings[1];
                demand[0] = new CoreDemandBindings(definitions::containsKey, definitions::get, id -> null, (id, binding) -> {
                    var selected = new LinkedHashMap<String, Object>(module);
                    selected.put("bindings", List.of(binding)); selected.put("demandBindings", demand[0]);
                    return backend.equals("ast") ? new Program(language, selected) : new BytecodeProgram(language, selected);
                }, true, definitions::containsKey);
                for (String id : definitions.keySet()) {
                    demand[0].prepareCode(id);
                    assertNull(demand[0].cell(id).peek());
                }
                // First actual resolution may still choose a different producer: lowering has no RTS authority.
                var flags = CoreDataLabels.fromCore("RtsFlags", proof(), runtimeLayout);
                assertEquals(0L, flags.readWord8(userOffset(runtimeLayout)));
                var failure = assertThrows(RuntimeFault.class, () -> demand[0].cell("label").read());
                assertTrue(failure.getMessage().contains("RtsFlags producing layout differs"), failure.getMessage());
            } finally { context.leave(); }
        }
    }

    @Test void exactGetterOperationsExecuteOnBothBackends() throws ReflectiveOperationException {
        var layout = layout();
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            var threads = Language.currentState(null).getThreads();
            threads.enterCurrent(null, false, true, null);
            try {
                Language.currentState().getRuntimeTrace().control(500, 1);
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                ExecutableProgram program = backend.equals("ast") ? new Program(language, getterModule(layout), false, false)
                    : new BytecodeProgram(language, getterModule(layout));
                var target = program.entryTarget("read");
                assertTrue(((GuestRoot) target.getRootNode()).getScalarResultProof().isInt());
                for (int repeat = 0; repeat < 3; repeat++) assertEquals(1, Calls.target(target, new Object[]{0L, Unit.INSTANCE}));
                long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                compile(target);
                assertEquals(before, ((Number) program.diagnostics().get("compiledEntries")).longValue());
                assertEquals(1, Calls.target(target, new Object[]{0L, Unit.INSTANCE}));
                assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue(),
                    backend + " immediate installed getter");
                valid(target);
            } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); context.leave(); }
        }
    }

    @Test void unsupportedOffsetFirstInstalledCallRejectsAndRecovers() throws ReflectiveOperationException {
        var layout = layout();
        long user = userOffset(layout);
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                Language.currentState().getRuntimeTrace().control(500, 1);
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var address = CoreDataLabels.fromCore("RtsFlags", proof(), layout);
                var root = new RootNode(language) {
                    long compiledEntries;
                    @Override public Object execute(VirtualFrame frame) {
                        if (CompilerDirectives.inCompiledCode()) compiledEntries++;
                        return address.readWord8Int((Long) frame.getArguments()[0]);
                    }
                };
                var target = root.getCallTarget();
                assertEquals(1, target.call(user));
                long before = root.compiledEntries;
                compile(target);
                assertEquals(before, root.compiledEntries);
                var failure = assertThrows(RuntimeFault.class, () -> target.call(user - 1));
                assertEquals("Unsupported RtsFlags byte field at offset " + (user - 1), failure.getMessage());
                assertTrue(root.compiledEntries > before, "First installed call is the invalid offset");
                assertEquals(1, target.call(user));
            } finally { context.leave(); }
        }
    }

    @Test void userFlagReflectsAllThreeTraceSinksNotDiagnosticCounters() {
        var layout = layout();
        var previous = System.getProperty("thc.diagnostics");
        try {
            for (boolean diagnostics : new boolean[]{false, true}) {
                System.setProperty("thc.diagnostics", Boolean.toString(diagnostics));
                var output = new ByteArrayOutputStream();
                try (var context = context(output)) {
                    context.initialize("thc"); context.enter();
                    var threads = Language.currentState(null).getThreads();
                    threads.enterCurrent(null, false, true, null);
                    try {
                        var trace = Language.currentState().getRuntimeTrace();
                        var flags = CoreDataLabels.fromCore("RtsFlags", proof(), layout);
                        assertEquals(0L, flags.readWord8(userOffset(layout)));
                        for (long sink : new long[]{1L, 2L, 3L, 0L}) {
                            assertEquals(0L, trace.control(500, sink));
                            assertEquals(sink == 0 ? 0L : 1L, flags.readWord8(userOffset(layout)));
                        }
                        trace.control(500, 1);
                        var text = ManagedAddress.fromByteArray(new byte[]{65, 0});
                        for (var operation : TraceOp.values()) RtsDiagnostics.trace(null, operation, text, 1);
                    } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); context.leave(); }
                }
                assertEquals("[thc trace event] A\n[thc trace binary] 41\n[thc trace marker] A\n", output.toString(StandardCharsets.UTF_8));
            }
        } finally {
            if (previous == null) System.clearProperty("thc.diagnostics"); else System.setProperty("thc.diagnostics", previous);
        }
    }
}
