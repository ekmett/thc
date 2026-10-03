// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.nodes.RootNode;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
@SuppressWarnings("unchecked")
public class ClosureInspectionTest {
    @Test public void unpackedGuestBytesHonorStoragePolicyWithoutEnteringPointers() {
        for (String policy : List.of("heap", "native")) for (String backend : List.of("ast", "bytecode"))
            try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).allowNativeAccess(true)
                    .option("thc.ByteArrayStorage", policy).build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var never = new GuestRoot(language, new FrameLayout().build()) {
                        @Override public Object execute(VirtualFrame frame) { throw new AssertionError("inspection entered a field"); }
                        @Override public long bloom(VirtualFrame frame) { return 0; }
                    };
                    var thunk = new Thunk(never.getCallTarget(), null);
                    var data = new DataLayout(language, "test:NativeImage.Payload", "Payload", new String[]{"IntRep", "LiftedRep"})
                        .create(new Object[]{73L, thunk});
                    RootCallTarget target;
                    if (backend.equals("bytecode")) target = BytecodeRootGen.create(language,
                            com.oracle.truffle.api.bytecode.BytecodeConfig.DEFAULT, b -> {
                        b.beginRoot(); var info = b.createLocal(); var bytes = b.createLocal(); var pointers = b.createLocal();
                        b.beginUnpackClosure(info, bytes, pointers); b.emitLoadArgument(0); b.endUnpackClosure();
                        b.beginReturn(); b.emitLoadLocal(bytes); b.endReturn(); b.endRoot();
                    }).getNode(0).getCallTarget();
                    else {
                        var layout = new FrameLayout();
                        int info = layout.bind("info", com.oracle.truffle.api.frame.FrameSlotKind.Object);
                        int bytes = layout.bind("bytes", com.oracle.truffle.api.frame.FrameSlotKind.Object);
                        int pointers = layout.bind("pointers", com.oracle.truffle.api.frame.FrameSlotKind.Object);
                        target = new RootNode(language, layout.build()) {
                            @Child ClosureInspectExpression expression = new ClosureInspectExpression(ClosureInspectOp.UNPACK,
                                new Expr[]{new Expr() { @Override public Object execute(VirtualFrame frame) { return frame.getArguments()[0]; } }},
                                CoreRepresentation.UNKNOWN);
                            @Override public Object execute(VirtualFrame frame) {
                                expression.executeTuple(frame, new int[]{info, bytes, pointers}, 0);
                                assertArrayEquals(new Object[]{thunk}, (Object[]) frame.getValue(pointers));
                                return frame.getValue(bytes);
                            }
                        }.getCallTarget();
                    }
                    Object first = Calls.target(target, new Object[]{data}), second = Calls.target(target, new Object[]{data});
                    assertNotSame(first, second); assertEquals(0, thunk.getState());
                    for (Object bytes : List.of(first, second)) {
                        assertEquals(24, ManagedByteArray.sizeGuest(bytes));
                        assertEquals(1, ManagedByteArray.readIntGuest(bytes, 0));
                        assertEquals(73, ManagedByteArray.readIntGuest(bytes, 1));
                        assertEquals(0, ManagedByteArray.readIntGuest(bytes, 2));
                        if (policy.equals("native")) {
                            var allocation = assertInstanceOf(ManagedAllocation.class, bytes);
                            assertTrue(allocation.hasNativeStorage()); assertFalse(allocation.isPinned());
                            assertEquals(allocation.nativeSegment().address(), ManagedAddress.fromGuestByteArray(bytes).toNativeBits());
                        } else assertInstanceOf(byte[].class, bytes);
                        assertSame(bytes, ManagedByteArray.freezeGuest(bytes));
                        ManagedByteArray.writeIntGuest(ManagedByteArray.freezeGuest(bytes), 1, 91);
                        assertEquals(91, ManagedByteArray.readIntGuest(bytes, 1));
                    }
                } finally { context.leave(); }
            }
    }
    @Test public void tupleOperationsRejectScalarEntryBeforeEvaluatingOperands() {
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], new FrameLayout().build()); var evaluations = new int[]{0};
        for (var operation : ClosureInspectOp.values()) if (operation != ClosureInspectOp.SIZE) {
            var operand = new Expr() { @Override public Object execute(VirtualFrame frame) { evaluations[0]++; throw new IllegalStateException("Rejected inspection evaluated its operand"); } };
            var expression = new ClosureInspectExpression(operation, new Expr[]{operand}, CoreRepresentation.UNKNOWN);
            var failure = assertThrows(RuntimeFault.class, () -> expression.executeLong(frame));
            assertEquals(operation.getPrimitive() + " requires a tuple destination", failure.getMessage()); assertEquals(0, evaluations[0]);
        }
    }
    @Test public void scalarSizeKeepsItsFirstInstalledEntryAndEvaluatesOnlyTheOperand() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var never = new GuestRoot(language, new FrameLayout().build()) {
                    @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("size inspection forced a field"); }
                    @Override public long bloom(VirtualFrame frame) { return 0; }
                };
                var thunk = new Thunk(never.getCallTarget(), null); var layout = new DataLayout(language, "test:Size.Payload", "Payload", new String[]{"IntRep", "LiftedRep"});
                var values = List.of(layout.create(new Object[]{73L, thunk}), layout.create(new Object[]{-1L, thunk}));
                class InspectionRoot extends RootNode {
                    long compiledEntries, operandEntries;
                    @Child private ClosureInspectExpression inspection = new ClosureInspectExpression(ClosureInspectOp.SIZE,
                        new Expr[]{new Expr() { @Override public Object execute(VirtualFrame frame) { operandEntries++; return frame.getArguments()[0]; } }}, CoreRepresentation.UNKNOWN);
                    InspectionRoot() { super(language); }
                    @Override public Object execute(VirtualFrame frame) { if (CompilerDirectives.inCompiledCode()) compiledEntries++; return inspection.executeLong(frame); }
                }
                var root = new InspectionRoot(); var target = root.getCallTarget(); assertEquals(3L, target.call(values.get(0))); assertEquals(1L, root.operandEntries);
                target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target);
                var runtime = Truffle.getRuntime(); runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target);
                for (var value : values) {
                    long before = root.compiledEntries, operandsBefore = root.operandEntries;
                    assertEquals(3L, target.call(value)); assertEquals(before + 1, root.compiledEntries, "immediate installed size inspection");
                    assertEquals(operandsBefore + 1, root.operandEntries); assertEquals(0, thunk.getState()); valid(target);
                }
            } finally { context.leave(); }
        }
    }
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private Context context() { return Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").option("engine.SingleTierCompilationThreshold", "10000000").build(); }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), target.getRootNode().getName()); }
    private List<RootCallTarget> targets(RootCallTarget entry) {
        Set<RootCallTarget> seen = Collections.newSetFromMap(new IdentityHashMap<>()); var result = new ArrayList<RootCallTarget>(); visit(entry, seen, result); return result;
    }
    private void visit(RootCallTarget target, Set<RootCallTarget> seen, List<RootCallTarget> result) {
        if (!seen.add(target)) return; var body = target.getRootNode(); var nodes = new ArrayList<Node>(); nodes.add(body);
        if (body instanceof BytecodeRoot bytecode) for (var instruction : bytecode.getBytecodeNode().getInstructions()) for (var argument : instruction.getArguments())
            if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) { var cached = argument.asCachedNode(); if (cached != null) nodes.add(cached); }
        for (var node : nodes) for (var call : NodeUtil.findAllNodeInstances(node, DirectCallNode.class))
            if (call.getCurrentCallTarget() instanceof RootCallTarget next && next.getRootNode() instanceof GuestRoot) visit(next, seen, result);
        result.add(target);
    }
    private Map<String, Object> fixture() throws Exception {
        var receipt = (Map<String, Object>) Json.parse(Files.readString(new File(root, "build/closure-inspection/manifest.json").toPath()));
        assertEquals("9.14.1", receipt.get("ghc")); assertEquals(45L, receipt.get("nativeRows"));
        for (var kind : List.of("inputHashes", "artifactHashes")) for (var item : ((Map<String, String>) receipt.get(kind)).entrySet()) {
            var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, item.getKey()).toPath())));
            assertEquals(item.getValue(), digest, "Stale fixture: " + item.getKey());
        }
        return receipt;
    }
    @Test public void originalCoreMatchesNativeWithoutEnteringInspectedPayloads() throws Exception { runOriginal(false); }
    /** A counterfactual dispatcher control, not an additional original-Core oracle.
     * Erase only the annotation scope; retain its action and every guest call. */
    @Test public void annotationFreeResumptionKeepsInstalledTupleDispatch() throws Exception { runOriginal(true); }
    private Object withoutAnnotations(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<Object, Object>(); for (var item : map.entrySet()) result.put(item.getKey(), withoutAnnotations(item.getValue())); return result;
        }
        if (value instanceof List<?> list) {
            if (!list.isEmpty() && "app".equals(list.getFirst()) && list.size() > 1 && list.get(1) instanceof List<?> head && head.size() >= 2 && head.subList(0, 2).equals(List.of("prim", "annotateStack#"))) {
                var arguments = (List<?>) list.get(2); var metadata = new LinkedHashMap<>((Map<String, Object>) list.get(6)); metadata.remove("callDemand");
                return Arrays.asList("app", withoutAnnotations(arguments.get(1)), Arrays.asList(withoutAnnotations(arguments.get(2))), List.of(false), list.get(4), list.get(5), metadata);
            }
            var result = new ArrayList<>(); for (var child : list) result.add(withoutAnnotations(child)); return result;
        }
        return value;
    }
    private void runOriginal(boolean eraseAnnotations) throws Exception {
        var receipt = fixture(); var module = (Map<String, Object>) thc.CoreCbdFixtures.read(new File(root, "build/closure-inspection/core/ClosureInspectionAudit.cbd").toPath());
        var rows = new ArrayList<List<String>>(); for (var line : Files.readAllLines(new File(root, "build/closure-inspection/oracle.tsv").toPath())) rows.add(Arrays.asList(line.split("\t", -1)));
        assertEquals(45, rows.size());
        for (var backend : List.of("ast", "bytecode")) for (var name : (List<String>) receipt.get("entries")) {
            if (eraseAnnotations && !name.equals("annotatedResume")) continue;
            try (var context = context()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var original = CoreModules.reachable(module, List.of("main:ClosureInspectionAudit." + name), true);
                    var source = new LinkedHashMap<>(eraseAnnotations ? (Map<String, Object>) withoutAnnotations(original) : original); source.put("instrument", true);
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, source) : new BytecodeProgram(language, source);
                    var selected = new ArrayList<List<String>>(); for (var row : rows) if (row.get(0).equals(name)) selected.add(row);
                    class Runner { void check(List<String> row) {
                        long input = Long.parseLong(row.get(1));
                        long model = switch (name) { case "payload" -> input; case "sizeConsistent", "notStack" -> 0L; case "pointerCount" -> 2L; case "noCCS" -> 1L;
                            case "noProvenance" -> 123L; case "cleared" -> input + 1; case "annotated" -> input + 2; case "annotatedResume" -> input + 21; default -> throw new IllegalStateException(name); };
                        assertEquals(model, Long.parseLong(row.get(2)), "native/" + name + "/" + input);
                        assertEquals(model, Calls.target(program.entryTarget("main:ClosureInspectionAudit." + name), new Object[]{0L, input}), backend + "/" + name + "/" + input);
                        var state = language.getHandoffState().get(); assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getResults().getDepth());
                        assertEquals(0, state.getArguments().retainedReferences()); assertEquals(0, state.getResults().retainedReferences());
                        assertTrue(StackAnnotations.current(null).values().isEmpty(), backend + "/" + name + " annotation return");
                    } }
                    var runner = new Runner(); for (var row : selected) runner.check(row);
                    var entry = program.entryTarget("main:ClosureInspectionAudit." + name); var installed = targets(entry); var runtime = Truffle.getRuntime();
                    var bypass = runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"));
                    for (var target : installed) { target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target); bypass.invoke(runtime, target); valid(target); }
                    for (var row : selected) {
                        long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); runner.check(row);
                        assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before, backend + "/" + name + "/" + row.get(1) + " first installed execution");
                        assertSame(entry, program.entryTarget("main:ClosureInspectionAudit." + name)); for (var target : installed) valid(target);
                    }
                } finally { context.leave(); }
            }
        }
    }
    @Test public void managedImagePreservesBitsPointersAndLazyThunkStates() {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var never = new GuestRoot(language, new FrameLayout().build()) {
                    @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("inspection entered a closure"); }
                    @Override public long bloom(VirtualFrame frame) { return 0; }
                };
                var thunk = new Thunk(never.getCallTarget(), null);
                var layout = new DataLayout(language, "test:Inspection.Payload", "Payload", new String[]{"IntRep", "FloatRep", "DoubleRep", "LiftedRep"});
                var value = layout.create(new Object[]{Long.MIN_VALUE, -0.0f, Double.longBitsToDouble(0x7ff8000000000055L), thunk});
                var image = ClosureInspection.image(value); var words = ByteBuffer.wrap(image.getBytes()).order(ByteOrder.nativeOrder());
                assertEquals(40, image.getBytes().length); assertEquals(5L, ClosureInspection.size(value));
                assertEquals(Long.MIN_VALUE, words.getLong(8)); assertEquals(Float.floatToRawIntBits(-0.0f), words.getInt(16));
                assertEquals(0x7ff8000000000055L, words.getLong(24)); assertEquals(0L, words.getLong(32)); assertArrayEquals(new Object[]{thunk}, image.getPointers());
                assertEquals(0, thunk.getState()); var info = Language.currentState(null).closureInfo;
                assertTrue(info.address(image.getDescriptor()).sameLocation(info.address(image.getDescriptor())));
                assertFalse(info.address(image.getDescriptor()).sameLocation(new ClosureInfoTables().address(image.getDescriptor())));
                var fresh = ClosureInspection.image(value); Arrays.fill(image.getBytes(), (byte) 0); image.getPointers()[0] = null;
                assertSame(thunk, fresh.getPointers()[0]); assertEquals(Long.MIN_VALUE, ByteBuffer.wrap(fresh.getBytes()).order(ByteOrder.nativeOrder()).getLong(8));
                for (int state : new int[]{0, 1, 3, 4, 5}) { thunk.setState(state); assertEquals(8, ClosureInspection.image(thunk).getBytes().length); assertEquals(state, thunk.getState()); }
                thunk.setValue(value); thunk.setState(2); var pointers = ClosureInspection.image(thunk).getPointers();
                if (pointers.length != 1) throw new IllegalArgumentException("Expected one pointer"); assertSame(value, pointers[0]);
            } finally { context.leave(); }
        }
    }
}
