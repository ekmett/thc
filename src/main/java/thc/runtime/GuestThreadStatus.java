// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
/** GHC 9.14.1 Constants.h why_blocked codes and PrimOps.cmm terminal overrides. */
public enum GuestThreadStatus {
    RUNNING(0), MVAR(1), BLACK_HOLE(2), READ(3), WRITE(4), DELAY(5), STM(6), FOREIGN(10), THROW_TO(12), MVAR_READ(14),
    FINISHED(16), DIED(17), RUNTIME_FAILURE(-1);
    private final long code;
    GuestThreadStatus(long code) { this.code = code; }
    public long getCode() { return code; }
    public boolean getTerminal() { return this == FINISHED || this == DIED || this == RUNTIME_FAILURE; }
    public static GuestThreadStatus uncaught(Throwable failure) {
        return failure instanceof GuestException || failure instanceof ForeignCallbackAsyncFailure ? DIED : RUNTIME_FAILURE;
    }
}
