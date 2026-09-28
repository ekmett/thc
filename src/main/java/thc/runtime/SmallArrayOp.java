// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import java.util.ArrayList;
import java.util.List;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Small-array operations retain their separate storage carrier and logical size. */
public enum SmallArrayOp {
    NEW("newSmallArray#", List.of("int", "element", "state"), List.of("state", "array")),
    READ("readSmallArray#", List.of("array", "int", "state"), List.of("state", "element")),
    WRITE("writeSmallArray#", List.of("array", "int", "element", "state"), List.of("state")),
    CAS("casSmallArray#", List.of("array", "int", "element", "element", "state"), List.of("state", "int", "element")),
    INDEX("indexSmallArray#", List.of("array", "int"), List.of("element")),
    FREEZE("unsafeFreezeSmallArray#", List.of("array", "state"), List.of("state", "array")),
    SIZE("sizeofSmallArray#", List.of("array"), List.of("int")),
    SIZE_MUTABLE("sizeofSmallMutableArray#", List.of("array"), List.of("int")),
    GET_SIZE_MUTABLE("getSizeofSmallMutableArray#", List.of("array", "state"), List.of("state", "int")),
    CLONE("cloneSmallArray#", List.of("array", "int", "int"), List.of("array")),
    CLONE_MUTABLE("cloneSmallMutableArray#", List.of("array", "int", "int", "state"), List.of("state", "array")),
    COPY("copySmallArray#", List.of("array", "int", "array", "int", "int", "state"), List.of("state")),
    COPY_MUTABLE("copySmallMutableArray#", List.of("array", "int", "array", "int", "int", "state"), List.of("state")),
    SAFE_FREEZE("freezeSmallArray#", List.of("array", "int", "int", "state"), List.of("state", "array")),
    THAW("thawSmallArray#", List.of("array", "int", "int", "state"), List.of("state", "array")),
    UNSAFE_THAW("unsafeThawSmallArray#", List.of("array", "state"), List.of("state", "array")),
    SHRINK("shrinkSmallMutableArray#", List.of("array", "int", "state"), List.of("state"));

    private final String primitive;
    private final List<String> arguments, result;
    SmallArrayOp(String primitive, List<String> arguments, List<String> result) {
        this.primitive = primitive; this.arguments = arguments; this.result = result;
    }
    public String getPrimitive() { return primitive; }
    public boolean getTuple() { return switch (this) {
        case NEW, READ, CAS, INDEX, FREEZE, GET_SIZE_MUTABLE, CLONE_MUTABLE, SAFE_FREEZE, THAW, UNSAFE_THAW -> true;
        default -> false;
    }; }
    private static boolean matches(CoreRepresentation rep, String role) {
        if (rep.isAggregate() || rep.isVector()) return false;
        return switch (role) {
            case "state" -> rep.getKind() == CoreKind.VOID && List.of().equals(rep.getPrimReps());
            case "int" -> rep.getKind() == CoreKind.LONG;
            case "array" -> rep.getKind() == CoreKind.OBJECT && List.of("BoxedRep (Just Unlifted)").equals(rep.getPrimReps());
            default -> (rep.getKind() == CoreKind.DATA || rep.getKind() == CoreKind.CLOSURE || rep.getKind() == CoreKind.OBJECT)
                && rep.getPrimReps() != null && rep.getPrimReps().size() == 1 &&
                ("BoxedRep (Just Lifted)".equals(rep.getPrimReps().get(0)) || "BoxedRep (Just Unlifted)".equals(rep.getPrimReps().get(0)));
        };
    }
    public void validate(List<CoreRepresentation> actual, List<?> flags, CoreRepresentation proof) {
        if (actual.size() != arguments.size() || flags.size() != arguments.size())
            throw new RuntimeFault("Primitive arity mismatch: " + primitive);
        for (int i = 0; i < actual.size(); i++) {
            if (!Boolean.valueOf(List.of("BoxedRep (Just Lifted)").equals(actual.get(i).getPrimReps())).equals(flags.get(i))
                    || !matches(actual.get(i), arguments.get(i)))
                throw new RuntimeFault("SmallArray primitive argument representation mismatch: " + primitive);
        }
        boolean valid;
        if (!getTuple()) valid = matches(proof, result.size() == 1 ? result.get(0) : "state");
        else {
            var components = proof.getComponents();
            valid = proof.isTuple() && proof.getKind() == CoreKind.UNKNOWN && components.size() == result.size();
            var reps = new ArrayList<String>();
            for (int i = 0; valid && i < result.size(); i++) {
                valid = matches(components.get(i), result.get(i));
            }
            if (valid) for (var component : components) reps.addAll(component.getPrimReps());
            valid = valid && reps.equals(proof.getPrimReps());
        }
        if (!valid) throw new RuntimeFault("SmallArray primitive result representation mismatch: " + primitive);
    }
    public static SmallArrayOp named(String name) {
        for (var operation : values()) if (operation.primitive.equals(name)) return operation;
        return null;
    }
    public static Expr expression(SmallArrayOp operation, CoreRepresentation proof, Expr[] operands) {
        return new SmallArrayExpression(operation, proof, operands);
    }
    private static final class SmallArrayExpression extends Expr {
        private final SmallArrayOp operation;
        @Children private Expr[] operands;
        SmallArrayExpression(SmallArrayOp operation, CoreRepresentation proof, Expr[] operands) {
            this.operation = operation; this.operands = operands;
            setRepresentation(new CoreRepresentation(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(),
                proof.getComponents(), proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
        }
        @Override public long executeLong(VirtualFrame frame) throws com.oracle.truffle.api.nodes.UnexpectedResultException {
            if (operation == SIZE || operation == SIZE_MUTABLE) return ManagedSmallArray.size(ManagedSmallArray.require(operands[0].execute(frame)));
            return super.executeLong(frame);
        }
        @Override public Object execute(VirtualFrame frame) {
            switch (operation) {
                case SIZE, SIZE_MUTABLE: return ManagedSmallArray.size(ManagedSmallArray.require(operands[0].execute(frame)));
                case CLONE: {
                    var array = ManagedSmallArray.require(operands[0].execute(frame));
                    long start = operands[1].executeRequiredLong(frame), count = operands[2].executeRequiredLong(frame);
                    return ManagedSmallArray.freeze(ManagedSmallArray.slice(array, start, count));
                }
                case SHRINK: {
                    var array = ManagedSmallArray.require(operands[0].execute(frame));
                    long size = operands[1].executeRequiredLong(frame);
                    var state = operands[2].execute(frame); TupleResults.requireVoidCarrier(state);
                    array.shrink(size); return state;
                }
                case WRITE: {
                    var array = ManagedSmallArray.require(operands[0].execute(frame));
                    long index = operands[1].executeRequiredLong(frame);
                    var value = operands[2].execute(frame);
                    var state = operands[3].execute(frame); TupleResults.requireVoidCarrier(state);
                    ManagedSmallArray.write(array, index, value); return state;
                }
                case COPY, COPY_MUTABLE: {
                    var source = ManagedSmallArray.require(operands[0].execute(frame));
                    long from = operands[1].executeRequiredLong(frame);
                    var destination = ManagedSmallArray.require(operands[2].execute(frame));
                    long to = operands[3].executeRequiredLong(frame), count = operands[4].executeRequiredLong(frame);
                    var state = operands[5].execute(frame); TupleResults.requireVoidCarrier(state);
                    ManagedSmallArray.copy(source, from, destination, to, count, operation == COPY_MUTABLE); return state;
                }
                default: throw fault("Tuple primitive requires a destination");
            }
        }
        @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
            switch (operation) {
                case NEW: {
                    long count = operands[0].executeRequiredLong(frame);
                    var initial = operands[1].execute(frame);
                    TupleResults.requireVoidCarrier(operands[2].execute(frame));
                    FrameAccess.INSTANCE.write(frame, slots[offset], ManagedSmallArray.allocate(count, initial)); break;
                }
                case READ, INDEX: {
                    var array = ManagedSmallArray.require(operands[0].execute(frame));
                    long index = operands[1].executeRequiredLong(frame);
                    if (operation == READ) TupleResults.requireVoidCarrier(operands[2].execute(frame));
                    FrameAccess.INSTANCE.write(frame, slots[offset], ManagedSmallArray.read(array, index)); break;
                }
                case GET_SIZE_MUTABLE: {
                    var array = ManagedSmallArray.require(operands[0].execute(frame));
                    TupleResults.requireVoidCarrier(operands[1].execute(frame));
                    FrameAccess.INSTANCE.writeLong(frame, slots[offset], ManagedSmallArray.size(array)); break;
                }
                case CAS: {
                    var array = ManagedSmallArray.require(operands[0].execute(frame));
                    long index = operands[1].executeRequiredLong(frame);
                    var expected = operands[2].execute(frame); var replacement = operands[3].execute(frame);
                    TupleResults.requireVoidCarrier(operands[4].execute(frame));
                    var witness = ManagedSmallArray.compareExchange(array, index, expected, replacement);
                    boolean success = witness == expected;
                    FrameAccess.INSTANCE.writeLong(frame, slots[offset], success ? 0L : 1L);
                    FrameAccess.INSTANCE.write(frame, slots[offset + 1], success ? replacement : witness); break;
                }
                case FREEZE, UNSAFE_THAW: {
                    var array = ManagedSmallArray.require(operands[0].execute(frame));
                    TupleResults.requireVoidCarrier(operands[1].execute(frame));
                    FrameAccess.INSTANCE.write(frame, slots[offset], operation == FREEZE ? ManagedSmallArray.freeze(array) : ManagedSmallArray.thaw(array)); break;
                }
                case SAFE_FREEZE, THAW, CLONE_MUTABLE: {
                    var array = ManagedSmallArray.require(operands[0].execute(frame));
                    long start = operands[1].executeRequiredLong(frame), count = operands[2].executeRequiredLong(frame);
                    TupleResults.requireVoidCarrier(operands[3].execute(frame));
                    var copy = ManagedSmallArray.slice(array, start, count);
                    FrameAccess.INSTANCE.write(frame, slots[offset], operation == SAFE_FREEZE ? ManagedSmallArray.freeze(copy) : copy); break;
                }
                default: return super.executeTuple(frame, slots, offset);
            }
            return null;
        }
    }
}
