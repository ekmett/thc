// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Pinned RTS operations that own THC request/console scheduler state. Ordinary
 * package functions keep their native linkage. The prim calls cannot enter the
 * native GHC RTS's CurrentTSO or blocked queue on behalf of a THC guest. */
enum WindowsIoOp {
    READ("stg_asyncReadzh", true), WRITE("stg_asyncWritezh", true),
    INSTALL("rts_InstallConsoleEvent", false), DONE("rts_ConsoleHandlerDone", false);

    final String symbol;
    final boolean request;
    WindowsIoOp(String symbol, boolean request) { this.symbol = symbol; this.request = request; }
    String convention() { return request ? "prim" : "ccall"; }
    String safety() { return request ? "safe" : "unsafe"; }
    List<String> arguments() {
        return Collections.unmodifiableList(request ? Arrays.asList("IntRep", "IntRep", "IntRep", "AddrRep", null) :
            this == INSTALL ? Arrays.asList("Int32Rep", "AddrRep", null) : Arrays.asList("Int32Rep", null));
    }
    List<String> results() { return request ? List.of("IntRep", "IntRep") : this == INSTALL ? List.of("Int32Rep") : List.of(); }
    static WindowsIoOp named(Object symbol) {
        for (var op : values()) if (op.symbol.equals(symbol)) return op;
        return null;
    }
}
