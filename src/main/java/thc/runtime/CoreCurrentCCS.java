// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import java.util.List;
public final class CoreCurrentCCS {
    private CoreCurrentCCS() {}
    private static boolean state(CoreRepresentation proof) { return !proof.isAggregate() && !proof.isVector() && proof.getKind() == CoreKind.VOID && List.of().equals(proof.getPrimReps()); }
    private static boolean address(CoreRepresentation proof) { return !proof.isAggregate() && !proof.isVector() && proof.getKind() == CoreKind.ADDRESS && List.of("AddrRep").equals(proof.getPrimReps()); }
    public static void validate(List<CoreRepresentation> arguments, List<?> flags, CoreRepresentation result) {
        var dummy = arguments.isEmpty() ? null : arguments.getFirst(); var fields = result.getComponents();
        if (arguments.size() != 2 || dummy == null || dummy.isAggregate() || dummy.isVector() ||
            !(dummy.getKind() == CoreKind.OBJECT || dummy.getKind() == CoreKind.DATA || dummy.getKind() == CoreKind.CLOSURE) ||
            !List.of("BoxedRep (Just Lifted)").equals(dummy.getPrimReps()) || !state(arguments.get(1)) ||
            !List.of(true, false).equals(flags) || !result.isTuple() || result.isSum() || result.isVector() ||
            result.getKind() != CoreKind.UNKNOWN || fields == null || fields.size() != 2 || !state(fields.get(0)) ||
            !address(fields.get(1)) || !List.of("AddrRep").equals(result.getPrimReps()))
            throw new RuntimeFault("getCurrentCCS#: expected lifted dummy, State# and State#/Addr# tuple");
    }
}
