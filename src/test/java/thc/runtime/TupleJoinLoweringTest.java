// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

class TupleJoinLoweringTest {
    private final CoreRepresentation longProof = new CoreRepresentation(CoreKind.LONG, true, true, List.of("IntRep"), null, null, null, null, null);
    private final CoreRepresentation reference = new CoreRepresentation(CoreKind.OBJECT, false, true, List.of("BoxedRep (Just Lifted)"), null, null, null, null, null);
    private CoreRepresentation tuple(CoreRepresentation... components) {
        var reps = new ArrayList<String>(); for (var component : components) reps.addAll(Objects.requireNonNull(component.getPrimReps()));
        return new CoreRepresentation(CoreKind.UNKNOWN, true, true, reps, Arrays.asList(components), null, null, null, null);
    }
    @Test void tupleOperandsMoveInParallelAndReleaseScratchReferencesWithoutForcing() throws ReflectiveOperationException {
        try (var context = Context.newBuilder("thc").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var builder = FrameDescriptor.newBuilder();
                for (int i = 0; i < 8; i++) builder.addSlot(FrameSlotKind.Illegal, "field " + i, null); var descriptor = builder.build(); var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor);
                var left = new Object(); var right = new Object(); var events = new ArrayList<Integer>();
                FrameAccess.writeLong(frame, 0, Long.MIN_VALUE); FrameAccess.write(frame, 1, left); FrameAccess.writeLong(frame, 2, Long.MAX_VALUE); FrameAccess.write(frame, 3, right);
                var shape = tuple(longProof, reference); var target = new LocalJoinTarget(new Object(), 1, new int[]{-1, -1}, new CoreRepresentation[]{shape, shape}, new boolean[]{false, false}, CoreRepresentation.Companion.getUNKNOWN(), new int[][]{{0, 1}, {2, 3}});
                class Operand extends Expr {
                    private final int index, source;
                    Operand(int index, int source) { this.index = index; this.source = source; }
                    @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("No aggregate Object carrier"); }
                    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
                        events.add(index); assertSame(left, frame.getObject(1)); assertSame(right, frame.getObject(3));
                        FrameAccess.writeLong(frame, slots[offset], frame.getLong(source)); FrameAccess.write(frame, slots[offset + 1], frame.getObject(source + 1)); return null;
                    }
                }
                var metrics = new Metrics(true); var call = new LocalJoinCall(language, target, new Expr[]{new Operand(0, 2), new Operand(1, 0)}, new int[]{-1, -1}, metrics, new int[][]{{4, 5}, {6, 7}});
                assertSame(target.getJump(), assertThrows(LocalJoinJump.class, () -> call.execute(frame))); assertEquals(List.of(0, 1), events);
                assertEquals(Long.MAX_VALUE, frame.getLong(0)); assertSame(right, frame.getObject(1)); assertEquals(Long.MIN_VALUE, frame.getLong(2)); assertSame(left, frame.getObject(3));
                var field = frame.getClass().getDeclaredField("indexedLocals"); field.setAccessible(true); var references = (Object[]) field.get(frame);
                for (int slot = 4; slot <= 7; slot++) { assertEquals(FrameSlotKind.Illegal.tag, frame.getTag(slot)); assertNull(references[slot], "Completed join retains no scratch root"); }
                assertEquals(1L, metrics.getLocalJoinTransfers()); assertEquals(0, language.getHandoffState().get().getArguments().getDepth()); assertEquals(0, language.getHandoffState().get().getResults().getDepth());
            } finally { context.leave(); }
        }
    }
    @Test void tupleLogicalShapeIsNotInferredFromEqualPhysicalWidth() {
        var expected = tuple(longProof, reference); CoreRepresentations.INSTANCE.requireJoinArgument(expected, tuple(longProof, reference));
        for (var actual : List.of(tuple(tuple(longProof), reference), tuple(reference, longProof), reference, CoreRepresentation.Companion.getUNKNOWN())) assertThrows(RuntimeFault.class, () -> CoreRepresentations.INSTANCE.requireJoinArgument(expected, actual));
    }
    @Test void sumSwapsMoveTagsAndInactiveReferencesTogetherAndClearScratchRoots() throws ReflectiveOperationException {
        try (var context = Context.newBuilder("thc").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var builder = FrameDescriptor.newBuilder();
                for (int i = 0; i < 12; i++) builder.addSlot(FrameSlotKind.Illegal, "sum field " + i, null); var descriptor = builder.build(); var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor); var marker = new Object();
                var shape = new CoreRepresentation(CoreKind.UNKNOWN, true, true, List.of("WordRep", "BoxedRep (Just Lifted)", "WordRep"), null, null, List.of(reference, longProof), 0, List.of(List.of(1), List.of(2)));
                FrameAccess.writeLong(frame, 0, 1); FrameAccess.write(frame, 1, marker); FrameAccess.writeLong(frame, 2, 0); FrameAccess.writeLong(frame, 3, 2); FrameAccess.write(frame, 4, null); FrameAccess.writeLong(frame, 5, Long.MIN_VALUE);
                var target = new LocalJoinTarget(new Object(), 1, new int[]{-1, -1}, new CoreRepresentation[]{shape, shape}, new boolean[]{false, false}, CoreRepresentation.Companion.getUNKNOWN(), new int[][]{{0, 1, 2}, {3, 4, 5}});
                class Operand extends Expr {
                    private final int source; Operand(int source) { this.source = source; }
                    @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("No sum Object carrier"); }
                    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
                        assertEquals(1L, frame.getLong(0)); assertSame(marker, frame.getObject(1)); assertEquals(2L, frame.getLong(3)); assertNull(frame.getObject(4));
                        FrameAccess.writeLong(frame, slots[offset], frame.getLong(source)); FrameAccess.write(frame, slots[offset + 1], frame.getObject(source + 1)); FrameAccess.writeLong(frame, slots[offset + 2], frame.getLong(source + 2)); return null;
                    }
                }
                var metrics = new Metrics(true); var call = new LocalJoinCall(language, target, new Expr[]{new Operand(3), new Operand(0)}, new int[]{-1, -1}, metrics, new int[][]{{6, 7, 8}, {9, 10, 11}});
                assertSame(target.getJump(), assertThrows(LocalJoinJump.class, () -> call.execute(frame)));
                assertEquals(2L, frame.getLong(0)); assertNull(frame.getObject(1)); assertEquals(Long.MIN_VALUE, frame.getLong(2)); assertEquals(1L, frame.getLong(3)); assertSame(marker, frame.getObject(4)); assertEquals(0L, frame.getLong(5));
                var field = frame.getClass().getDeclaredField("indexedLocals"); field.setAccessible(true); var references = (Object[]) field.get(frame);
                for (int slot = 6; slot <= 11; slot++) { assertEquals(FrameSlotKind.Illegal.tag, frame.getTag(slot)); assertNull(references[slot]); }
                assertEquals(1L, metrics.getLocalJoinTransfers()); assertEquals(0, language.getHandoffState().get().getArguments().getDepth()); assertEquals(0, language.getHandoffState().get().getResults().getDepth());
            } finally { context.leave(); }
        }
    }
    @Test void emptyTupleCasesRunTheScrutineeAndTrapIfMalformedCoreReturns() {
        var scalar = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true); var closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
        var tuple = Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "components", List.of(scalar), "primReps", List.of("IntRep"), "evaluated", true);
        var value = List.of("app", List.of("con", "Tuple", 1), List.of(List.of("var", "x", Map.of("rep", scalar))), List.of(false), true, true, Map.of("rep", tuple));
        var body = List.of("case", value, "dead", List.of(), Map.of("rep", scalar, "binder", Map.of("id", "dead", "lifted", false, "rep", tuple)));
        var function = List.of("lam", List.of(Map.of("id", "x", "name", "x", "lifted", false, "rep", scalar)), body, Map.of("rep", closure, "resultRep", scalar));
        Map<String, Object> module = Map.of("constructors", List.of(Map.of("id", "Tuple", "kind", "unboxed-tuple", "arity", 1, "fieldReps", List.of(List.of("IntRep")), "fieldLifted", List.of(false), "strictFields", List.of(false))),
            "bindings", List.of(Map.of("id", "entry", "name", "entry", "rep", closure, "lifted", true, "expr", function)));
        for (String backend : List.of("ast", "bytecode")) try (var context = Context.newBuilder("thc").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); ExecutableProgram program = backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
                var failure = assertThrows(RuntimeFault.class, () -> Calls.target(program.entryTarget("entry"), new Object[]{0L, 42L})); assertTrue(Objects.requireNonNull(failure.getMessage()).contains("Non-exhaustive"), failure.getMessage()); assertEquals(0, language.getHandoffState().get().getResults().getDepth());
            } finally { context.leave(); }
        }
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], FrameDescriptor.newBuilder().build()); int[] evaluated = {0}; var bottom = new RuntimeFault("original scrutinee failure");
        var scrutinee = new Expr() {
            @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("No scalar tuple execution"); }
            @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) { evaluated[0]++; throw bottom; }
        };
        assertSame(bottom, assertThrows(RuntimeFault.class, () -> new TupleCase(scrutinee, new int[0], new EmptyCaseResult(longProof)).executeLong(frame))); assertEquals(1, evaluated[0]);
    }
}
