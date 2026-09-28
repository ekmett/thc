// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.List;

/** Load-time proof checks for the kept reference, State and continuation result. */
public final class CoreKeepAlive {
    private CoreKeepAlive() {}
    private static final List<String> LIFTED = List.of("BoxedRep (Just Lifted)");
    private static boolean state(CoreRepresentation proof) {
        return !proof.isAggregate() && !proof.isVector() && proof.getKind() == CoreKind.VOID && List.of().equals(proof.getPrimReps());
    }
    private static boolean reference(CoreRepresentation proof) {
        return !proof.isAggregate() && !proof.isVector() &&
            (proof.getKind() == CoreKind.OBJECT || proof.getKind() == CoreKind.DATA || proof.getKind() == CoreKind.CLOSURE) &&
            (LIFTED.equals(proof.getPrimReps()) || List.of("BoxedRep (Just Unlifted)").equals(proof.getPrimReps()));
    }
    public static void validate(List<CoreRepresentation> arguments, List<?> flags, CoreRepresentation result,
                                List<CoreRepresentation> continuationInputs, CoreRepresentation continuationResult) {
        if (arguments.size() != 3 || !reference(arguments.get(0)) || !state(arguments.get(1)) ||
            (arguments.get(2).getKind() != CoreKind.CLOSURE && arguments.get(2).getKind() != CoreKind.OBJECT) ||
            arguments.get(2).isAggregate() || arguments.get(2).isVector() || !LIFTED.equals(arguments.get(2).getPrimReps()) ||
            !flags.equals(List.of(LIFTED.equals(arguments.get(0).getPrimReps()), false, true)))
            throw new RuntimeFault("keepAlive#: exact reference, State and continuation operands required");
        if (!result.getPresent()) throw new RuntimeFault("keepAlive#: exact continuation result required");
        CoreRepresentations.requireNoVector(result, "keepAlive result");
        if (result.isSum()) SumShape.INSTANCE.validate(result);
        else if (result.isTuple()) TupleShape.Companion.validate(result);
        else {
            CoreRepresentations.requireScalar(result, "keepAlive result");
            if (result.getPrimReps() == null || result.getKind() == CoreKind.UNKNOWN)
                throw new RuntimeFault("keepAlive#: exact scalar result required");
        }
        if (continuationInputs != null) {
            if (continuationInputs.isEmpty() || !state(continuationInputs.getFirst()))
                throw new RuntimeFault("keepAlive#: continuation requires a logical State operand");
            if (continuationInputs.size() == 1) {
                result.refine(continuationResult);
                TupleShape.Companion.requireCompatible(result, continuationResult, true);
            } else if (!reference(result) || !LIFTED.equals(result.getPrimReps()))
                throw new RuntimeFault("keepAlive#: partially applied continuation must return a lifted function");
        }
    }
}
