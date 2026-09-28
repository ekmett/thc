// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import java.util.List;
public final class CoreNoDuplicate {
    public static final CoreNoDuplicate INSTANCE = new CoreNoDuplicate();
    private CoreNoDuplicate() {}
    public static void validate(List<CoreRepresentation> arguments, List<?> flags, CoreRepresentation result) {
        if (arguments.size() != 1 || !CoreSynchronousExceptions.state(arguments.getFirst()) || !flags.equals(List.of(false)) || !CoreSynchronousExceptions.state(result))
            throw new RuntimeFault("noDuplicate#: exact State# input and result required");
    }
}
