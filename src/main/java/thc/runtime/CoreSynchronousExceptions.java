// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import java.util.List;
public final class CoreSynchronousExceptions {
    public static final CoreSynchronousExceptions INSTANCE = new CoreSynchronousExceptions();
    private CoreSynchronousExceptions() {}
    private static final List<String> LIFTED = List.of("BoxedRep (Just Lifted)"), UNLIFTED = List.of("BoxedRep (Just Unlifted)");
    static boolean state(CoreRepresentation proof) {
        return !proof.isAggregate() && !proof.isVector() && proof.getKind() == CoreKind.VOID && List.of().equals(proof.getPrimReps());
    }
    private static boolean boxed(CoreRepresentation proof) {
        return !proof.isAggregate() && !proof.isVector() &&
            (proof.getKind() == CoreKind.DATA || proof.getKind() == CoreKind.CLOSURE || proof.getKind() == CoreKind.OBJECT) &&
            (LIFTED.equals(proof.getPrimReps()) || UNLIFTED.equals(proof.getPrimReps()));
    }
    private static boolean closure(CoreRepresentation proof) {
        return boxed(proof) && proof.getKind() == CoreKind.CLOSURE && LIFTED.equals(proof.getPrimReps());
    }
    public static void validate(String name, List<CoreRepresentation> arguments, List<?> flags, CoreRepresentation result) {
        boolean valid = switch (name) {
            case "raiseIO#" -> arguments.size() == 2 && boxed(arguments.get(0)) && state(arguments.get(1)) &&
                flags.equals(List.of(LIFTED.equals(arguments.get(0).getPrimReps()), false));
            case "catch#" -> arguments.size() == 3 && closure(arguments.get(0)) && closure(arguments.get(1)) &&
                state(arguments.get(2)) && flags.equals(List.of(true, true, false));
            case "unmaskAsyncExceptions#", "maskAsyncExceptions#", "maskUninterruptible#" ->
                arguments.size() == 2 && closure(arguments.get(0)) && state(arguments.get(1)) && flags.equals(List.of(true, false));
            case "getMaskingState#" -> arguments.size() == 1 && state(arguments.getFirst()) && flags.equals(List.of(false));
            default -> false;
        };
        List<CoreRepresentation> components = result.getComponents();
        if (!valid || result.getKind() != CoreKind.UNKNOWN || !result.isTuple() || result.isSum() || result.isVector() ||
            components == null || components.size() != 2 || !state(components.getFirst()) ||
            name.equals("getMaskingState#") && (components.get(1).getKind() != CoreKind.LONG ||
                !List.of("IntRep").equals(components.get(1).getPrimReps()) || !List.of("IntRep").equals(result.getPrimReps())))
            throw new RuntimeFault(name + ": expected exact boxed exception, State# and result tuple contract");
        TupleShape.Companion.validate(result);
    }
}
