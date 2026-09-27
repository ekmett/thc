// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Exact original GHC 9.14.1 Conc.Sync, TopHandler and Debug.Trace declarations. */
public enum RtsDiagnosticOp {
    STACK("reportStackOverflow", "BoxedRep (Just Unlifted)", null),
    HEAP("reportHeapOverflow", (String) null),
    ERROR("errorBelch2", "AddrRep", "AddrRep", null),
    DEBUG("debugBelch2", "AddrRep", "AddrRep", null);

    private final String symbol;
    private final List<String> arguments;

    RtsDiagnosticOp(String symbol, String... arguments) {
        this.symbol = symbol;
        this.arguments = Collections.unmodifiableList(Arrays.asList(arguments));
    }
    public String getSymbol() { return symbol; }
    public List<String> getArguments() { return arguments; }
}
