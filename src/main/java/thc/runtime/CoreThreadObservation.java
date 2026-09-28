// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.List;

/** Carrier/aggregate checks, not a second GHC type checker. */
public final class CoreThreadObservation {
    private CoreThreadObservation() {}
    public static boolean named(String name) { return name.equals("listThreads#") || name.equals("isCurrentThreadBound#"); }
    private static boolean scalar(CoreRepresentation rep, CoreKind kind) {
        return !rep.isAggregate() && !rep.isVector() && rep.getKind() == kind;
    }
    public static void validate(String name, List<CoreRepresentation> arguments, List<?> flags, CoreRepresentation result) {
        List<CoreRepresentation> fields = result.getComponents();
        CoreKind kind = name.equals("listThreads#") ? CoreKind.OBJECT : CoreKind.LONG;
        if (!named(name) || arguments.size() != 1 || !flags.equals(List.of(false)) ||
            !scalar(arguments.getFirst(), CoreKind.VOID) || !result.isTuple() || result.isSum() || result.isVector() ||
            result.getKind() != CoreKind.UNKNOWN || fields == null || fields.size() != 2 ||
            !scalar(fields.get(0), CoreKind.VOID) || !scalar(fields.get(1), kind))
            throw RuntimeFault.fault(name + " requires State# and a State#/observation tuple");
    }
}
