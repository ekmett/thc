// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

public class RuntimeFault extends RuntimeException {
    public RuntimeFault(String message) { super(message); }
    public static RuntimeFault fault(String message) {
        com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate();
        return new RuntimeFault(message);
    }
}
