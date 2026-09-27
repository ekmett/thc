// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Versioned scalar C declarations, with honest native-GHC compatibility stubs.
 * These are reserved calls, not admission of arbitrary host/native functions. */
public enum RuntimeServiceCall {
    QUERY("thc_runtime_v1_query", "Int32Rep", "Int64Rep", "Int64Rep", null),
    CONTROL("thc_runtime_v1_control", "Int32Rep", "Int64Rep", null),
    TRACE("thc_runtime_v1_trace", "Int32Rep", "Int64Rep", "AddrRep", "Int64Rep", null),
    EXCEPTION_TEXT("thc_exception_v1_text", "AddrRep", "Int32Rep", "Int64Rep", null);

    private final String symbol;
    private final List<String> arguments;

    RuntimeServiceCall(String symbol, String... arguments) {
        this.symbol = symbol;
        this.arguments = Collections.unmodifiableList(Arrays.asList(arguments));
    }

    public String getSymbol() { return symbol; }
    public List<String> getArguments() { return arguments; }
}
