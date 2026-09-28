// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.List;

/** Carrier and tuple checks only; pinned primop types are checked by the exporter audit. */
public final class CoreThreadScheduling {
    private CoreThreadScheduling() {}
    public static final String FALSE = "ghc-internal:GHC.Internal.Types.False";
    public static boolean named(String name) {
        return switch (name) {
            case "par#", "spark#", "numSparks#", "getSpark#", "delay#", "setThreadAllocationCounter#", "setOtherThreadAllocationCounter#" -> true;
            default -> false;
        };
    }
    private static boolean scalar(CoreRepresentation rep, CoreKind kind) {
        return !rep.isAggregate() && !rep.isVector() && rep.getKind() == kind;
    }
    private static boolean role(CoreRepresentation rep, CoreKind kind) {
        if (kind != null) return scalar(rep, kind);
        return !rep.isAggregate() && !rep.isVector() &&
            (rep.getKind() == CoreKind.OBJECT || rep.getKind() == CoreKind.DATA || rep.getKind() == CoreKind.CLOSURE) &&
            List.of("BoxedRep (Just Lifted)").equals(rep.getPrimReps());
    }
    public static void validate(String name, List<CoreRepresentation> arguments, List<?> flags, CoreRepresentation result) {
        CoreKind[] inputs = switch (name) {
            case "par#" -> new CoreKind[] {null};
            case "spark#" -> new CoreKind[] {null, CoreKind.VOID};
            case "numSparks#", "getSpark#" -> new CoreKind[] {CoreKind.VOID};
            case "delay#", "setThreadAllocationCounter#" -> new CoreKind[] {CoreKind.LONG, CoreKind.VOID};
            case "setOtherThreadAllocationCounter#" -> new CoreKind[] {CoreKind.LONG, CoreKind.OBJECT, CoreKind.VOID};
            default -> throw RuntimeFault.fault("Unknown thread scheduling operation " + name);
        };
        CoreKind[] outputs = switch (name) {
            case "spark#" -> new CoreKind[] {CoreKind.VOID, null};
            case "numSparks#" -> new CoreKind[] {CoreKind.VOID, CoreKind.LONG};
            case "getSpark#" -> new CoreKind[] {CoreKind.VOID, CoreKind.LONG, null};
            default -> null;
        };
        List<CoreRepresentation> fields = result.getComponents();
        boolean validResult;
        if (outputs == null) validResult = scalar(result, name.equals("par#") ? CoreKind.LONG : CoreKind.VOID);
        else {
            validResult = result.isTuple() && !result.isSum() && !result.isVector() && fields != null && fields.size() == outputs.length;
            if (validResult) for (int i = 0; i < outputs.length; i++) if (!role(fields.get(i), outputs[i])) { validResult = false; break; }
        }
        boolean validArguments = arguments.size() == inputs.length && flags.size() == inputs.length;
        if (validArguments) for (int i = 0; i < inputs.length; i++)
            if (!Boolean.valueOf(inputs[i] == null).equals(flags.get(i)) || !role(arguments.get(i), inputs[i])) { validArguments = false; break; }
        if (!validArguments || !validResult)
            throw RuntimeFault.fault(name + " requires its scalar/lazy operand and ordered state-result carriers");
    }
}
