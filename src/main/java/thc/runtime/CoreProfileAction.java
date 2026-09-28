// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.List;

public final class CoreProfileAction {
    private CoreProfileAction() {}
    public static void validate(List<CoreRepresentation> arguments, List<?> flags, CoreRepresentation result) {
        CoreRepresentation action = arguments.isEmpty() ? null : arguments.getFirst();
        if (arguments.size() != 2 || !flags.equals(List.of(true, false)) || action == null || action.isAggregate() || action.isVector() ||
            (action.getKind() != CoreKind.OBJECT && action.getKind() != CoreKind.CLOSURE) || arguments.get(1).getKind() != CoreKind.VOID ||
            arguments.get(1).isAggregate() || !result.isTuple() || result.getComponents() == null || result.getComponents().isEmpty() ||
            result.getComponents().getFirst().getKind() != CoreKind.VOID)
            throw RuntimeFault.fault("clearCCS#: expected State action and State-result tuple");
        TupleShape.Companion.validate(result);
    }
}
