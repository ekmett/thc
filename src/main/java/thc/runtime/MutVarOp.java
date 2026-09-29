// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import java.util.ArrayList;
import java.util.List;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

public enum MutVarOp {
    NEW("newMutVar#", List.of("boxed", "state"), true),
    READ("readMutVar#", List.of("mutvar", "state"), true),
    SWAP("atomicSwapMutVar#", List.of("mutvar", "boxed", "state"), true),
    CAS("casMutVar#", List.of("mutvar", "boxed", "boxed", "state"), true),
    MODIFY("atomicModifyMutVar_#", List.of("mutvar", "function", "state"), true),
    MODIFY2("atomicModifyMutVar2#", List.of("mutvar", "function", "state"), true),
    WRITE("writeMutVar#", List.of("mutvar", "boxed", "state"), false);
    private static final String MUTVAR_REP = "BoxedRep (Just Unlifted)", LIFTED_REP = "BoxedRep (Just Lifted)";
    private final String primitive;
    private final List<String> arguments;
    private final boolean tuple;
    MutVarOp(String primitive, List<String> arguments, boolean tuple) {
        this.primitive = primitive; this.arguments = arguments; this.tuple = tuple;
    }
    public String getPrimitive() { return primitive; }
    public boolean getTuple() { return tuple; }
    private static boolean matches(CoreRepresentation proof, String role) {
        if (proof.isTuple() || proof.isVector()) return false;
        return switch (role) {
            case "state" -> proof.getKind() == CoreKind.VOID && List.of().equals(proof.getPrimReps());
            case "mutvar" -> proof.getKind() == CoreKind.OBJECT && List.of(MUTVAR_REP).equals(proof.getPrimReps());
            case "flag" -> proof.getKind() == CoreKind.LONG;
            case "function" -> proof.getKind() == CoreKind.CLOSURE && List.of(LIFTED_REP).equals(proof.getPrimReps());
            default -> (proof.getKind() == CoreKind.DATA || proof.getKind() == CoreKind.CLOSURE || proof.getKind() == CoreKind.OBJECT)
                && (role.equals("lifted") ? List.of(LIFTED_REP).equals(proof.getPrimReps()) :
                    proof.getPrimReps() != null && proof.getPrimReps().size() == 1 &&
                    (LIFTED_REP.equals(proof.getPrimReps().get(0)) || MUTVAR_REP.equals(proof.getPrimReps().get(0))));
        };
    }
    public void validate(List<CoreRepresentation> actual, List<?> flags, CoreRepresentation result) {
        if (actual.size() != arguments.size() || flags.size() != arguments.size())
            throw new RuntimeFault("Primitive arity mismatch: " + primitive);
        for (int i = 0; i < actual.size(); i++)
            if (!matches(actual.get(i), arguments.get(i)) || !Boolean.valueOf(List.of(LIFTED_REP).equals(actual.get(i).getPrimReps())).equals(flags.get(i)))
                throw new RuntimeFault("MutVar primitive argument representation mismatch: " + primitive);
        var fields = result.getComponents();
        boolean valid;
        if (this == MODIFY || this == MODIFY2) valid = result.isTuple() && fields.size() == 3 &&
            matches(fields.get(0), "state") && matches(fields.get(1), "lifted") && matches(fields.get(2), "lifted") &&
            List.of(LIFTED_REP, LIFTED_REP).equals(result.getPrimReps());
        else if (this == CAS) {
            valid = result.isTuple() && fields.size() == 3 && matches(fields.get(0), "state") && matches(fields.get(1), "flag") && matches(fields.get(2), "boxed");
            if (valid) {
                var reps = new ArrayList<String>();
                for (var field : fields) reps.addAll(field.getPrimReps());
                valid = reps.equals(result.getPrimReps());
            }
        } else if (tuple) valid = result.isTuple() && fields.size() == 2 && matches(fields.get(0), "state") &&
            matches(fields.get(1), this == NEW ? "mutvar" : "boxed") && java.util.Objects.equals(result.getPrimReps(), fields.get(1).getPrimReps());
        else valid = matches(result, "state");
        if (!valid) throw new RuntimeFault("MutVar primitive result representation mismatch: " + primitive);
    }
    public static MutVarOp named(String name) {
        for (var operation : values()) if (operation.primitive.equals(name)) return operation;
        return null;
    }
    public static Expr expression(MutVarOp operation, CoreRepresentation proof, Expr[] operands) {
        return expression(operation, proof, operands, null, null, false);
    }
    public static Expr expression(MutVarOp operation, CoreRepresentation proof, Expr[] operands,
                                  TruffleLanguage<?> language, Metrics metrics, boolean async) {
        MutVarModifySite site = null;
        if (operation == MODIFY || operation == MODIFY2) {
            if (!(language instanceof Language guest)) throw fault("Missing atomic MutVar guest language");
            if (metrics == null) throw fault("Missing atomic MutVar metrics");
            site = new MutVarModifySite(guest, metrics, async, operation == MODIFY2);
        }
        return expression(operation, proof, operands, site, -1);
    }
    static Expr expression(MutVarOp operation, CoreRepresentation proof, Expr[] operands, MutVarModifySite site, int programSlot) {
        return new MutVarExpression(operation, proof, operands, site, programSlot);
    }
    private static final class MutVarExpression extends Expr {
        private final MutVarOp operation;
        private final MutVarModifySite site;
        private final int programSlot;
        @Children private Expr[] operands;
        MutVarExpression(MutVarOp operation, CoreRepresentation proof, Expr[] operands, MutVarModifySite site, int programSlot) {
            this.operation = operation; this.operands = operands; this.site = site; this.programSlot = programSlot;
            setRepresentation(new CoreRepresentation(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(),
                proof.getComponents(), proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
        }
        @Override public Object execute(VirtualFrame frame) {
            if (operation != WRITE) throw fault("Tuple primitive requires a destination");
            var cell = ManagedMutVar.require(operands[0].execute(frame));
            var value = operands[1].execute(frame);
            var state = operands[2].execute(frame); TupleResults.requireVoidCarrier(state);
            cell.setValue(value); return state;
        }
        @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
            if (operation == NEW) {
                var value = operands[0].execute(frame);
                TupleResults.requireVoidCarrier(operands[1].execute(frame));
                FrameAccess.INSTANCE.write(frame, slots[offset], new ManagedMutVar(value)); return null;
            }
            if (operation == WRITE) return super.executeTuple(frame, slots, offset);
            var cell = ManagedMutVar.require(operands[0].execute(frame));
            switch (operation) {
                case READ:
                    TupleResults.requireVoidCarrier(operands[1].execute(frame));
                    FrameAccess.INSTANCE.write(frame, slots[offset], cell.getValue()); break;
                case SWAP: {
                    var replacement = operands[1].execute(frame);
                    TupleResults.requireVoidCarrier(operands[2].execute(frame));
                    FrameAccess.INSTANCE.write(frame, slots[offset], cell.exchange(replacement)); break;
                }
                case CAS: {
                    var expected = operands[1].execute(frame); var replacement = operands[2].execute(frame);
                    TupleResults.requireVoidCarrier(operands[3].execute(frame));
                    var witness = cell.compareExchange(expected, replacement); boolean success = witness == expected;
                    FrameAccess.INSTANCE.writeLong(frame, slots[offset], success ? 0L : 1L);
                    FrameAccess.INSTANCE.write(frame, slots[offset + 1], success ? replacement : witness); break;
                }
                case MODIFY, MODIFY2: {
                    var function = operands[1].execute(frame);
                    TupleResults.requireVoidCarrier(operands[2].execute(frame));
                    var modified = cell.modify(function, site, programSlot < 0 ? null : Program.instance(frame, programSlot));
                    FrameAccess.INSTANCE.write(frame, slots[offset], modified.getOld());
                    FrameAccess.INSTANCE.write(frame, slots[offset + 1], modified.getResult()); break;
                }
                default: throw new AssertionError(operation);
            }
            return null;
        }
    }
}
