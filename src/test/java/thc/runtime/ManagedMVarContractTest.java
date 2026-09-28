// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import kotlin.Unit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ManagedMVarContractTest {
    private CoreRepresentation scalar(CoreKind kind, List<String> reps) {
        return new CoreRepresentation(kind, false, true, reps, null, null, null, null, null);
    }
    private final CoreRepresentation state = scalar(CoreKind.VOID, List.of());
    private final CoreRepresentation mvar = scalar(CoreKind.OBJECT, List.of("BoxedRep (Just Unlifted)"));
    private final CoreRepresentation lifted = scalar(CoreKind.OBJECT, List.of("BoxedRep (Just Lifted)"));
    private final CoreRepresentation integer = scalar(CoreKind.LONG, List.of("IntRep"));
    private CoreRepresentation tuple(CoreRepresentation... fields) {
        return new CoreRepresentation(CoreKind.UNKNOWN, false, true,
            Arrays.stream(fields).flatMap(field -> field.getPrimReps().stream()).toList(), List.of(fields), null, null, null, null);
    }
    private List<CoreRepresentation> arguments(MVarOp op, CoreRepresentation payload) {
        return switch (op) { case NEW -> List.of(state); case PUT, TRY_PUT -> List.of(mvar, payload, state); default -> List.of(mvar, state); };
    }
    private CoreRepresentation result(MVarOp op, CoreRepresentation payload) {
        return switch (op) {
            case NEW -> tuple(state, mvar); case PUT -> state; case TAKE, READ -> tuple(state, payload);
            case TRY_TAKE, TRY_READ -> tuple(state, integer, payload); case TRY_PUT, IS_EMPTY -> tuple(state, integer);
        };
    }
    private List<Boolean> flags(List<CoreRepresentation> args) {
        return args.stream().map(arg -> List.of("BoxedRep (Just Lifted)").equals(arg.getPrimReps())).toList();
    }
    @Test void allEightContractsRetainLogicalStateAndBothBoxedLevities() {
        assertEquals(8, MVarOp.values().length);
        for (var op : MVarOp.values()) for (var payload : List.of(lifted, mvar,
            scalar(CoreKind.DATA, lifted.getPrimReps()), scalar(CoreKind.DATA, mvar.getPrimReps()), scalar(CoreKind.CLOSURE, lifted.getPrimReps()))) {
            var args = arguments(op, payload); op.validate(args, flags(args), result(op, payload));
            assertSame(op, MVarOp.Companion.named(op.getPrimitive()));
        }
        assertNull(MVarOp.Companion.named("newMutVar#"));
    }
    @Test void malformedArgumentProofsAndFlagsAreRejected() {
        var bad = List.of(CoreRepresentation.UNKNOWN, integer, scalar(CoreKind.OBJECT, List.of("BoxedRep Nothing")),
            scalar(CoreKind.ADDRESS, List.of("AddrRep")), tuple(), tuple(state), tuple(integer));
        for (var op : MVarOp.values()) {
            var args = arguments(op, lifted);
            for (int index = 0; index < args.size(); index++) {
                for (var proof : bad) {
                    var changed = new ArrayList<>(args); changed.set(index, proof);
                    assertThrows(RuntimeFault.class, () -> op.validate(changed, flags(changed), result(op, lifted)), op + " argument " + index + ": " + proof);
                }
                var inverted = new ArrayList<>(flags(args)); inverted.set(index, !inverted.get(index));
                assertThrows(RuntimeFault.class, () -> op.validate(args, inverted, result(op, lifted)));
                for (var invalid : Arrays.asList(null, 0L, "false")) {
                    var forged = new ArrayList<Object>(flags(args)); forged.set(index, invalid);
                    assertThrows(RuntimeFault.class, () -> op.validate(args, forged, result(op, lifted)));
                }
            }
            assertThrows(RuntimeFault.class, () -> op.validate(args.subList(0, args.size()-1), flags(args), result(op, lifted)));
            var extra = new ArrayList<>(args); extra.add(state); var extraFlags = new ArrayList<>(flags(args)); extraFlags.add(false);
            assertThrows(RuntimeFault.class, () -> op.validate(extra, extraFlags, result(op, lifted)));
        }
    }
    @Test void resultTuplesRequireExactLogicalFieldsAndFlattenedRepresentations() {
        for (var op : MVarOp.values()) {
            var args = arguments(op, lifted); var expected = result(op, lifted);
            for (var forged : List.of(CoreRepresentation.UNKNOWN, integer, tuple(),
                new CoreRepresentation(expected.getKind(), false, true, List.of("WordRep"), expected.getComponents(), null, null, null, null)))
                assertThrows(RuntimeFault.class, () -> op.validate(args, flags(args), forged));
            if (!op.getTuple()) continue;
            var fields = expected.getComponents();
            var extra = new ArrayList<>(fields); extra.add(state);
            for (var forged : List.of(new CoreRepresentation(CoreKind.OBJECT, false, true, expected.getPrimReps(), fields, null, null, null, null),
                tuple(fields.subList(1, fields.size()).toArray(CoreRepresentation[]::new)), tuple(extra.toArray(CoreRepresentation[]::new)),
                new CoreRepresentation(expected.getKind(), false, true, expected.getPrimReps(), fields, null, List.of(state), null, null)))
                assertThrows(RuntimeFault.class, () -> op.validate(args, flags(args), forged));
            for (int index = 0; index < fields.size(); index++) {
                var field = fields.get(index);
                var replacement = field.equals(state) ? tuple() : field.equals(integer) ? scalar(CoreKind.LONG, List.of("WordRep")) : integer;
                var changed = new ArrayList<>(fields); changed.set(index, replacement);
                assertThrows(RuntimeFault.class, () -> op.validate(args, flags(args), tuple(changed.toArray(CoreRepresentation[]::new))));
            }
        }
    }
    private record FrameSlots(VirtualFrame frame, int[] slots) {}
    private FrameSlots frame() {
        var builder = FrameDescriptor.newBuilder();
        int first = builder.addSlot(FrameSlotKind.Illegal, "flag", null), second = builder.addSlot(FrameSlotKind.Object, "payload", null);
        return new FrameSlots(Truffle.getRuntime().createVirtualFrame(new Object[0], builder.build()), new int[]{first, second});
    }
    private Expr operand(Object value) { return new Expr() { @Override public Object execute(VirtualFrame frame) { return value; } }; }
    @Test void invalidStateIsRejectedBeforeMutationOrTuplePublication() {
        var destination = frame(); var frame = destination.frame; var slots = destination.slots; var original = new Object();
        for (var op : MVarOp.values()) {
            var cell = new ManagedMVar(); assertTrue(cell.tryPut(original));
            FrameAccess.writeLong(frame, slots[0], 71L); FrameAccess.write(frame, slots[1], original);
            var operands = switch (op) {
                case NEW -> new Expr[]{operand(1L)};
                case PUT, TRY_PUT -> new Expr[]{operand(cell), operand(new Object()), operand(1L)};
                default -> new Expr[]{operand(cell), operand(1L)};
            };
            var expression = ManagedMVarsKt.mVarExpression(op, result(op, lifted), operands, false);
            assertThrows(RuntimeFault.class, () -> { if (op.getTuple()) expression.executeTuple(frame, slots, 0); else expression.execute(frame); });
            assertSame(original, cell.tryRead().getValue()); assertEquals(71L, frame.getLong(slots[0])); assertSame(original, frame.getObject(slots[1]));
        }
    }
    @Test void failedTryReadsClearOldPayloadAndDoNotInventSuccess() {
        var destination = frame(); var frame = destination.frame; var slots = destination.slots;
        for (var op : List.of(MVarOp.TRY_TAKE, MVarOp.TRY_READ)) {
            var cell = new ManagedMVar();
            var expression = ManagedMVarsKt.mVarExpression(op, result(op, lifted), new Expr[]{operand(cell), operand(Unit.INSTANCE)}, false);
            FrameAccess.writeLong(frame, slots[0], 1L); FrameAccess.write(frame, slots[1], new Object());
            expression.executeTuple(frame, slots, 0);
            assertEquals(0L, frame.getLong(slots[0])); assertNull(frame.getObject(slots[1])); assertTrue(cell.isEmpty());
            // Null is a legitimate stored reference; fullness is independent of the payload.
            assertTrue(cell.tryPut(null)); expression.executeTuple(frame, slots, 0);
            assertEquals(1L, frame.getLong(slots[0])); assertNull(frame.getObject(slots[1]));
            assertEquals(op == MVarOp.TRY_TAKE, cell.isEmpty());
        }
    }
}
