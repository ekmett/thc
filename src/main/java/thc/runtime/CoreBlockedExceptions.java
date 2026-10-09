// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** Original GHC SomeException CAFs supplied implicitly by the RTS. */
public final class CoreBlockedExceptions {
    private CoreBlockedExceptions() {}
    public static final String MVAR = "ghc-internal:GHC.Internal.IO.Exception.blockedIndefinitelyOnMVar";
    public static final String STM = "ghc-internal:GHC.Internal.IO.Exception.blockedIndefinitelyOnSTM";
    public static String payload(String primitive) {
        return switch (primitive) {
            case "takeMVar#", "readMVar#", "putMVar#" -> MVAR;
            case "atomically#" -> STM;
            default -> null;
        };
    }
}
