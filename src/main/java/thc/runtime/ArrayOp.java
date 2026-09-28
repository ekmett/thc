// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Boxed array elements are copied opaquely, without entering their contents. */
public enum ArrayOp {
    NEW("newArray#", List.of("int", "element", "state"), List.of("state", "array")),
    READ("readArray#", List.of("array", "int", "state"), List.of("state", "element")),
    WRITE("writeArray#", List.of("array", "int", "element", "state"), List.of()),
    CAS("casArray#", List.of("array", "int", "element", "element", "state"), List.of("state", "int", "element")),
    FREEZE("unsafeFreezeArray#", List.of("array", "state"), List.of("state", "array")),
    INDEX("indexArray#", List.of("array", "int"), List.of("element")),
    CLONE("cloneArray#", List.of("array", "int", "int"), List.of("array")),
    FREEZE_COPY("freezeArray#", List.of("array", "int", "int", "state"), List.of("state", "array")),
    THAW("thawArray#", List.of("array", "int", "int", "state"), List.of("state", "array")),
    SIZE("sizeofArray#", List.of("array"), List.of("int")),
    SIZE_MUTABLE("sizeofMutableArray#", List.of("array"), List.of("int")),
    CLONE_MUTABLE("cloneMutableArray#", List.of("array", "int", "int", "state"), List.of("state", "array")),
    COPY("copyArray#", List.of("array", "int", "array", "int", "int", "state"), List.of()),
    COPY_MUTABLE("copyMutableArray#", List.of("array", "int", "array", "int", "int", "state"), List.of()),
    UNSAFE_THAW("unsafeThawArray#", List.of("array", "state"), List.of("state", "array"));

    private final String primitive;
    private final List<String> arguments, result;
    ArrayOp(String primitive, List<String> arguments, List<String> result) {
        this.primitive = primitive; this.arguments = arguments; this.result = result;
    }
    public String getPrimitive() { return primitive; }
    public boolean getTuple() { return !result.isEmpty() && this != CLONE && this != SIZE && this != SIZE_MUTABLE; }
    private static boolean matches(CoreRepresentation rep, String role) {
        if (rep.isAggregate() || rep.isVector()) return false;
        return switch (role) {
            case "state" -> rep.getKind() == CoreKind.VOID && List.of().equals(rep.getPrimReps());
            case "int" -> rep.getKind() == CoreKind.LONG && List.of("IntRep").equals(rep.getPrimReps());
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
                throw new RuntimeFault("Array primitive argument representation mismatch: " + primitive);
        }
        boolean valid;
        if (!getTuple()) valid = matches(proof, result.size() == 1 ? result.get(0) : "state");
        else {
            var components = proof.getComponents();
            valid = proof.isTuple() && proof.getKind() == CoreKind.UNKNOWN && components.size() == result.size();
            var reps = new ArrayList<String>();
            for (int i = 0; valid && i < result.size(); i++) {
                valid = matches(components.get(i), result.get(i));
                if (valid) reps.addAll(components.get(i).getPrimReps());
            }
            valid = valid && reps.equals(proof.getPrimReps());
        }
        if (!valid) throw new RuntimeFault("Array primitive result representation mismatch: " + primitive);
    }
    public static ArrayOp named(String name) {
        for (var operation : values()) if (operation.primitive.equals(name)) return operation;
        return null;
    }
    private static void rawProof(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) throw fault("Missing Array# representation proof");
        boolean tuple = "unboxed-tuple".equals(map.get("aggregate"));
        var keys = tuple ? Set.of("kind", "primReps", "evaluated", "aggregate", "components")
            : Set.of("kind", "primReps", "evaluated");
        if (!map.keySet().equals(keys) || !(map.get("evaluated") instanceof Boolean))
            throw fault("Malformed Array# representation proof");
        if (tuple) {
            if (!(map.get("components") instanceof List<?> components)) throw fault("Malformed Array# tuple proof");
            for (var component : components) rawProof(component);
        }
    }
    public static void validateApplications(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (var child : map.values()) validateApplications(child);
        } else if (value instanceof List<?> list) {
            var head = list.size() > 1 && list.get(1) instanceof List<?> h ? h : null;
            var operation = !list.isEmpty() && "app".equals(list.get(0)) && head != null && !head.isEmpty()
                && "prim".equals(head.get(0)) && head.size() > 1 && head.get(1) instanceof String name ? named(name) : null;
            if (operation != null) {
                if (list.size() <= 2 || !(list.get(2) instanceof List<?> args)) throw fault("Malformed Array# arguments");
                if (list.size() <= 3 || !(list.get(3) instanceof List<?> flags)) throw fault("Malformed Array# flags");
                if (list.size() != 7) throw fault("Malformed Array# application");
                for (var flag : flags) if (!(flag instanceof Boolean)) throw fault("Malformed Array# application");
                var proofs = new ArrayList<CoreRepresentation>();
                for (var argument : args) {
                    if (!(argument instanceof List<?> expression)) throw fault("Malformed Array# argument");
                    var metadata = CoreRepresentations.INSTANCE.metadata(expression);
                    var rep = metadata == null ? null : metadata.get("rep");
                    rawProof(rep);
                    proofs.add(CoreRepresentations.INSTANCE.parse(rep));
                }
                var rep = list.get(6) instanceof Map<?, ?> meta ? meta.get("rep") : null;
                rawProof(rep);
                operation.validate(proofs, flags, CoreRepresentations.INSTANCE.parse(rep));
            }
            for (var child : list) validateApplications(child);
        }
    }
    public static Expr expression(ArrayOp operation, CoreRepresentation proof, Expr[] operands) {
        return new ArrayExpression(operation, proof, operands);
    }
    private static final class ArrayExpression extends Expr {
        private final ArrayOp operation;
        @Children private Expr[] operands;
        ArrayExpression(ArrayOp operation, CoreRepresentation proof, Expr[] operands) {
            this.operation = operation; this.operands = operands;
            setRepresentation(new CoreRepresentation(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(),
                proof.getComponents(), proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
        }
        @Override public long executeLong(VirtualFrame frame) throws com.oracle.truffle.api.nodes.UnexpectedResultException {
            if (operation == SIZE || operation == SIZE_MUTABLE) return ManagedArray.size(ManagedArray.require(operands[0].execute(frame)));
            return super.executeLong(frame);
        }
        @Override public Object execute(VirtualFrame frame) {
            switch (operation) {
                case SIZE, SIZE_MUTABLE: return ManagedArray.size(ManagedArray.require(operands[0].execute(frame)));
                case CLONE: {
                    var array = ManagedArray.require(operands[0].execute(frame));
                    long start = operands[1].executeRequiredLong(frame), count = operands[2].executeRequiredLong(frame);
                    return ManagedArray.freeze(ManagedArray.slice(array, start, count));
                }
                case WRITE: {
                    var array = ManagedArray.require(operands[0].execute(frame));
                    long index = operands[1].executeRequiredLong(frame);
                    var value = operands[2].execute(frame);
                    var state = operands[3].execute(frame); TupleResultsKt.requireVoidCarrier(state);
                    ManagedArray.write(array, index, value); return state;
                }
                case COPY, COPY_MUTABLE: {
                    var source = ManagedArray.require(operands[0].execute(frame));
                    long from = operands[1].executeRequiredLong(frame);
                    var destination = ManagedArray.require(operands[2].execute(frame));
                    long to = operands[3].executeRequiredLong(frame), count = operands[4].executeRequiredLong(frame);
                    var state = operands[5].execute(frame); TupleResultsKt.requireVoidCarrier(state);
                    ManagedArray.copy(source, from, destination, to, count, operation == COPY_MUTABLE); return state;
                }
                default: throw fault("Tuple primitive requires a destination");
            }
        }
        @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
            switch (operation) {
                case NEW: {
                    long count = operands[0].executeRequiredLong(frame);
                    var initial = operands[1].execute(frame);
                    TupleResultsKt.requireVoidCarrier(operands[2].execute(frame));
                    FrameAccess.INSTANCE.write(frame, slots[offset], ManagedArray.allocate(count, initial)); break;
                }
                case READ, INDEX: {
                    var array = ManagedArray.require(operands[0].execute(frame));
                    long index = operands[1].executeRequiredLong(frame);
                    if (operation == READ) TupleResultsKt.requireVoidCarrier(operands[2].execute(frame));
                    FrameAccess.INSTANCE.write(frame, slots[offset], ManagedArray.read(array, index)); break;
                }
                case CAS: {
                    var array = ManagedArray.require(operands[0].execute(frame));
                    long index = operands[1].executeRequiredLong(frame);
                    var expected = operands[2].execute(frame); var replacement = operands[3].execute(frame);
                    TupleResultsKt.requireVoidCarrier(operands[4].execute(frame));
                    var witness = ManagedArray.compareExchange(array, index, expected, replacement);
                    boolean success = witness == expected;
                    FrameAccess.INSTANCE.writeLong(frame, slots[offset], success ? 0L : 1L);
                    FrameAccess.INSTANCE.write(frame, slots[offset + 1], success ? replacement : witness); break;
                }
                case FREEZE, UNSAFE_THAW: {
                    var array = ManagedArray.require(operands[0].execute(frame));
                    TupleResultsKt.requireVoidCarrier(operands[1].execute(frame));
                    FrameAccess.INSTANCE.write(frame, slots[offset], operation == FREEZE ? ManagedArray.freeze(array) : ManagedArray.thaw(array)); break;
                }
                case FREEZE_COPY, THAW, CLONE_MUTABLE: {
                    var array = ManagedArray.require(operands[0].execute(frame));
                    long start = operands[1].executeRequiredLong(frame), count = operands[2].executeRequiredLong(frame);
                    TupleResultsKt.requireVoidCarrier(operands[3].execute(frame));
                    var copy = ManagedArray.slice(array, start, count);
                    FrameAccess.INSTANCE.write(frame, slots[offset], operation == FREEZE_COPY ? ManagedArray.freeze(copy) : copy); break;
                }
                default: return super.executeTuple(frame, slots, offset);
            }
            return null;
        }
    }
}
