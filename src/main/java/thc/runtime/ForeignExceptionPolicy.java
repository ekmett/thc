// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.interop.ExceptionType;
import java.util.concurrent.CancellationException;

/** Framework classification is separate from arbitrary foreign metadata access. */
public final class ForeignExceptionPolicy {
    private ForeignExceptionPolicy() {}
    public static boolean kind(ExceptionType type) {
        return type == ExceptionType.RUNTIME_ERROR || type == ExceptionType.PARSE_ERROR;
    }
    public static boolean host(Throwable cause) {
        return !(cause instanceof VirtualMachineError) && !(cause instanceof ThreadDeath) &&
            !(cause instanceof LinkageError) && !(cause instanceof InterruptedException) &&
            !(cause instanceof CancellationException) && !(cause instanceof RuntimeFault) &&
            !(cause instanceof InternalGuestControl) && !(cause instanceof GuestException);
    }
}
