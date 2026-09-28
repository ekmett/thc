// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.List;

/** Exact GHC 9.14.1 Core contract for the initial Java-thread primitives. */
public final class CoreGuestThreads {
    private CoreGuestThreads() {}
    private static final List<String> LIFTED = List.of("BoxedRep (Just Lifted)");
    private static final List<String> UNLIFTED = List.of("BoxedRep (Just Unlifted)");
    private static boolean state(CoreRepresentation rep) {
        return !rep.isAggregate() && !rep.isVector() && rep.getKind() == CoreKind.VOID && List.of().equals(rep.getPrimReps());
    }
    private static boolean thread(CoreRepresentation rep) {
        return !rep.isAggregate() && !rep.isVector() && rep.getKind() == CoreKind.OBJECT && UNLIFTED.equals(rep.getPrimReps());
    }
    private static boolean lifted(CoreRepresentation rep) {
        return !rep.isAggregate() && !rep.isVector() &&
            (rep.getKind() == CoreKind.DATA || rep.getKind() == CoreKind.CLOSURE || rep.getKind() == CoreKind.OBJECT) && LIFTED.equals(rep.getPrimReps());
    }
    private static boolean integer(CoreRepresentation rep) {
        return !rep.isAggregate() && !rep.isVector() && rep.getKind() == CoreKind.LONG && List.of("IntRep").equals(rep.getPrimReps());
    }
    private static boolean statusResult(CoreRepresentation rep) {
        List<CoreRepresentation> fields = rep.getComponents();
        return rep.getKind() == CoreKind.UNKNOWN && rep.isTuple() && !rep.isSum() && !rep.isVector() &&
            List.of("IntRep", "IntRep", "IntRep").equals(rep.getPrimReps()) && fields != null && fields.size() == 4 &&
            state(fields.get(0)) && integer(fields.get(1)) && integer(fields.get(2)) && integer(fields.get(3));
    }
    private static boolean labelResult(CoreRepresentation rep) {
        List<CoreRepresentation> fields = rep.getComponents();
        return rep.getKind() == CoreKind.UNKNOWN && rep.isTuple() && !rep.isSum() && !rep.isVector() &&
            List.of("IntRep", "BoxedRep (Just Unlifted)").equals(rep.getPrimReps()) && fields != null && fields.size() == 3 &&
            state(fields.get(0)) && integer(fields.get(1)) && thread(fields.get(2));
    }
    private static boolean action(CoreRepresentation rep) { return rep.getKind() == CoreKind.CLOSURE && lifted(rep); }
    private static boolean threadResult(CoreRepresentation rep) {
        List<CoreRepresentation> fields = rep.getComponents();
        return rep.getKind() == CoreKind.UNKNOWN && rep.isTuple() && !rep.isSum() && !rep.isVector() &&
            UNLIFTED.equals(rep.getPrimReps()) && fields != null && fields.size() == 2 && state(fields.get(0)) && thread(fields.get(1));
    }
    public static void validate(String name, List<CoreRepresentation> arguments, List<?> flags, CoreRepresentation result) {
        boolean valid = switch (name) {
            case "fork#" -> arguments.size() == 2 && action(arguments.get(0)) && state(arguments.get(1)) &&
                flags.equals(List.of(true, false)) && threadResult(result);
            case "forkOn#" -> arguments.size() == 3 && arguments.get(0).getKind() == CoreKind.LONG &&
                !arguments.get(0).isAggregate() && !arguments.get(0).isVector() && action(arguments.get(1)) && state(arguments.get(2)) &&
                flags.equals(List.of(false, true, false)) && threadResult(result);
            case "myThreadId#" -> arguments.size() == 1 && state(arguments.get(0)) && flags.equals(List.of(false)) && threadResult(result);
            case "threadStatus#" -> arguments.size() == 2 && thread(arguments.get(0)) && state(arguments.get(1)) &&
                flags.equals(List.of(false, false)) && statusResult(result);
            case "labelThread#" -> arguments.size() == 3 && thread(arguments.get(0)) && thread(arguments.get(1)) && state(arguments.get(2)) &&
                flags.equals(List.of(false, false, false)) && state(result);
            case "threadLabel#" -> arguments.size() == 2 && thread(arguments.get(0)) && state(arguments.get(1)) &&
                flags.equals(List.of(false, false)) && labelResult(result);
            case "killThread#" -> arguments.size() == 3 && thread(arguments.get(0)) && lifted(arguments.get(1)) && state(arguments.get(2)) &&
                flags.equals(List.of(false, true, false)) && state(result);
            default -> false;
        };
        if (!valid) throw new RuntimeFault(name + ": expected exact GHC ThreadId#, lazy payload and State# contract");
    }
}
