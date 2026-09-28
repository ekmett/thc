// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import java.util.List;
public final class CoreArithmeticExceptions {
    public static final CoreArithmeticExceptions INSTANCE = new CoreArithmeticExceptions();
    private CoreArithmeticExceptions() {}
    public static String payload(String name) {
        String value = switch (name) {
            case "raiseDivZero#" -> "divZeroException";
            case "raiseOverflow#" -> "overflowException";
            case "raiseUnderflow#" -> "underflowException";
            default -> null;
        };
        return value == null ? null : "ghc-internal:GHC.Internal.Exception.Type." + value;
    }
    public static void validate(String name, List<CoreRepresentation> arguments, List<?> flags, CoreRepresentation result) {
        if (payload(name) == null || arguments.size() != 1 || !arguments.getFirst().isEmptyTuple() || !flags.equals(List.of(false)))
            throw new RuntimeFault(name + ": expected one exact unlifted empty tuple argument");
        CoreRepresentations.INSTANCE.requireNoVector(result, name + " result");
        CoreRepresentations.INSTANCE.requireNoSum(result, name + " result");
    }
}
