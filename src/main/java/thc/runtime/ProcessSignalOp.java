// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Exact GHC 9.14.1 TopHandler and unix-2.8.8.0 signal declarations. */
public enum ProcessSignalOp {
    INSTALL("stg_sig_install", Collections.unmodifiableList(Arrays.asList("Int32Rep", "Int32Rep", "AddrRep", null)), "Int32Rep");

    private final String symbol;
    private final List<String> arguments;
    private final String result;

    ProcessSignalOp(String symbol, List<String> arguments, String result) {
        this.symbol = symbol;
        this.arguments = arguments;
        this.result = result;
    }

    public String getSymbol() { return symbol; }
    public List<String> getArguments() { return arguments; }
    public String getResult() { return result; }
}
