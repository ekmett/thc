// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** Callback authority of a synchronous managed foreign activation. This is not
 * a promise of an interruptible native transport or permission to leave Truffle. */
public enum ForeignSafety {
    UNSAFE, SAFE, INTERRUPTIBLE;

    public static ForeignSafety synchronous(String declared) {
        return switch (declared) {
            case "unsafe" -> UNSAFE;
            case "safe" -> SAFE;
            default -> throw RuntimeFault.fault("Unsupported synchronous foreign safety: " + declared);
        };
    }
}
