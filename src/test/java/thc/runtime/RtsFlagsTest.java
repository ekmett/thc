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
import java.util.Set;
import kotlin.Unit;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.ContextProfile;
import thc.Language;
import thc.MainKt;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RtsFlagsTest {
    private final Map<String, Object> address = Map.of("kind", "address", "primReps", List.of("AddrRep"), "evaluated", true);
    private final Map<String, Object> state = Map.of("kind", "void", "primReps", List.of(), "evaluated", true);
    private final Map<String, Object> integer = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
    private final Map<String, Object> byteRep = Map.of("kind", "long", "primReps", List.of("Word8Rep"), "evaluated", true);
    private final Map<String, Object> closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
    private CoreRepresentation proof() { return CoreRepresentations.INSTANCE.parse(address); }

    private static TargetLayout layout() { return layout("inplace"); }
    @SuppressWarnings("unchecked")
    private static TargetLayout layout(String abi) {
        assumeTrue(System.getProperty("os.name").startsWith("Linux") &&
            Set.of("amd64", "x86_64").contains(System.getProperty("os.arch")), "Only the evidenced Linux producing layout is supported");
        var document = new LinkedHashMap<>(StackInfoTestLayout.INSTANCE.document(StackInfoTestLayout.INSTANCE.fields()));
        var compiler = new LinkedHashMap<>((Map<String, Object>) document.get("compiler"));
        compiler.put("abi", abi);
        document.put("compiler", compiler);
        return TargetLayout.Companion.fromDocument(document);
    }

    private static Context context() { return context(new ByteArrayOutputStream()); }
    private static Context context(ByteArrayOutputStream output) {
        return MainKt.withContextProfile(Context.newBuilder("thc"), ContextProfile.SYNCHRONOUS_TEST).err(output).build();
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

    @Test void composedFieldViewsAreReadOnlyAndContextBound() {
        var layout = layout();
        var first = context(); var second = context();
        ManagedAddress base;
        ManagedAddress field;
        first.initialize("thc"); second.initialize("thc"); first.enter();
        try {
            base = CoreDataLabels.fromCore("RtsFlags", proof(), layout);
            assertSame(base, CoreDataLabels.fromCore("RtsFlags", proof(), layout));
            field = base.plus(392).plus(11);
            assertEquals(1L, field.readWord8(0));
            assertEquals(1L, base.readWord8(403));
            assertEquals(1L, base.plus(404).readWord8(-1));
            assertTrue(field.sameLocation(base.plus(403)));
            assertFalse(field.sameLocation(base));
            for (long offset : new long[]{0L, 392L, 402L, 404L, -1L, Long.MAX_VALUE}) {
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
            assertThrows(RuntimeFault.class, field::rawBacking$org_intelligence_thc);
            assertThrows(RuntimeFault.class, field::availableBytes);
            assertThrows(RuntimeFault.class, () -> field.compareWithinAllocation(base));
        } finally { first.leave(); }
        second.enter();
        try {
            assertEquals(1L, CoreDataLabels.fromCore("RtsFlags", proof(), layout).readWord8(403));
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
                assertThrows(RuntimeFault.class, () -> CoreDataLabels.fromCore("RtsFlags", proof(), wrongAbi));
                assertThrows(RuntimeFault.class, () -> CoreDataLabels.fromCore("RtsFlags", null, valid));
                assertThrows(RuntimeFault.class, () -> {
                    var proof = proof();
                    CoreDataLabels.fromCore("RtsFlags", proof.copy(proof.getKind(), false, proof.getPresent(), proof.getPrimReps(),
                        proof.getComponents(), proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()), valid);
                });
                assertThrows(RuntimeFault.class, () -> CoreDataLabels.fromCore("RtsFlags", CoreRepresentations.INSTANCE.parse(integer), valid));
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
            List.of(plus(plus(label, 392), 11), List.of("lit", "int", "0", Map.of("rep", integer)),
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

    @Test void exactGetterOperationsExecuteOnBothBackends() throws ReflectiveOperationException {
        var layout = layout();
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            var threads = Language.currentState(null).getThreads$org_intelligence_thc();
            threads.enterCurrent(null, false, true, null);
            try {
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

    @Test void unsupportedOffsetFirstInstalledCallKeepsTheTargetAndValidRead() throws ReflectiveOperationException {
        var layout = layout();
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
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
                assertEquals(1, target.call(403L));
                long before = root.compiledEntries;
                compile(target);
                assertEquals(before, root.compiledEntries);
                var failure = assertThrows(RuntimeFault.class, () -> target.call(402L));
                assertEquals("Unsupported RtsFlags byte field at offset 402", failure.getMessage());
                assertEquals(before + 1, root.compiledEntries, "First installed call is the invalid offset");
                valid(target);
                assertEquals(1, target.call(403L));
                assertEquals(before + 2, root.compiledEntries);
                valid(target);
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
                    var threads = Language.currentState(null).getThreads$org_intelligence_thc();
                    threads.enterCurrent(null, false, true, null);
                    try {
                        assertEquals(1L, CoreDataLabels.fromCore("RtsFlags", proof(), layout).readWord8(403));
                        var text = ManagedAddress.Companion.fromByteArray(new byte[]{65, 0});
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
