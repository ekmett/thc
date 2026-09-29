// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact original ThreadId# ABI; nominal declaration evidence is checked at owning admission. */
public final class CoreThreadIdForeign {
    private CoreThreadIdForeign() {}
    private static final String THREAD = "BoxedRep (Just Unlifted)";
    private static void check(boolean value, String detail) {
        if (!value) throw RuntimeFault.fault("Invalid original thread identity call: " + detail);
    }
    private static boolean scalar(Object value, boolean thread, boolean declared) {
        return value instanceof Map<?,?> rep && rep.keySet().equals(Set.of("kind", "primReps", "evaluated")) &&
            (thread ? "object" : "void").equals(rep.get("kind")) &&
            (thread ? List.of(THREAD) : List.of()).equals(rep.get("primReps")) &&
            rep.get("evaluated") instanceof Boolean && (!declared || Boolean.FALSE.equals(rep.get("evaluated")));
    }
    public static void validateHead(List<?> head, boolean defined) { CoreMainThreadForeign.validateHead(head, defined); }
    public static ThreadIdForeignOp validate(Object metadata, List<?> arguments, List<?> flags, Object output) {
        if (!(metadata instanceof Map<?,?> meta) || !(meta.get("foreignCall") instanceof Map<?,?> call) ||
            !(call.get("target") instanceof Map<?,?> target)) return null;
        var operation = ThreadIdForeignOp.named(target.get("symbol")); if (operation == null) return null;
        check(call.keySet().equals(Set.of("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep")) &&
            CorePackageScalarForeign.number(call.get("schema"), 1), "descriptor schema");
        check(target.keySet().equals(Set.of("kind", "symbol", "unit", "isFunction")) && "static".equals(target.get("kind")) &&
            "ghc-internal".equals(target.get("unit")) && Boolean.TRUE.equals(target.get("isFunction")) &&
            "ccall".equals(call.get("convention")) && "unsafe".equals(call.get("safety")), "original target and calling convention");
        int count = operation.getArguments() + 1;
        check(CorePackageScalarForeign.number(call.get("arity"), count) && CorePackageScalarForeign.number(call.get("suppliedArity"), count) &&
            call.get("argumentReps") instanceof List<?> declared && declared.size() == count && arguments.size() == count && flags.size() == count,
            "saturated arity");
        var declared = (List<?>) call.get("argumentReps");
        for (int i = 0; i < count; i++) check(scalar(declared.get(i), i < count - 1, true) &&
            scalar(arguments.get(i), i < count - 1, false) && Boolean.FALSE.equals(flags.get(i)), "ThreadId#/State# operand " + i);
        check(CorePackageScalarForeign.result(call.get("resultRep"), operation.getResult(), true) &&
            CorePackageScalarForeign.result(meta.get("rep"), operation.getResult(), false) &&
            CorePackageScalarForeign.result(output, operation.getResult(), false), "State/numeric result");
        return operation;
    }
    public static void validateOperand(ThreadIdForeignOp operation, int index, CoreRepresentation lowered, CoreRepresentation stored) {
        boolean thread = index < operation.getArguments();
        var kind = thread ? CoreKind.OBJECT : CoreKind.VOID; var reps = thread ? List.of(THREAD) : List.<String>of();
        check(lowered.getPresent() && !lowered.isAggregate() && !lowered.isVector() && lowered.getKind() == kind && reps.equals(lowered.getPrimReps()),
            "lowered operand " + index);
        if (stored != null && stored.getPresent()) check(!stored.isAggregate() && !stored.isVector() &&
            (stored.getKind() == kind || stored.getKind() == CoreKind.UNKNOWN) && (stored.getPrimReps() == null || reps.equals(stored.getPrimReps())),
            "stored operand " + index);
    }
}
