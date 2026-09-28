// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** GHC 9.14.1 RTS Libdw.c/LibdwPool.c declarations, with USE_LIBDW=0 semantics. */
public enum LibdwForeignOp {
    TAKE("libdwPoolTake", Collections.singletonList(null), "AddrRep"),
    BACKTRACE("libdwGetBacktrace", Collections.unmodifiableList(Arrays.asList("AddrRep", null)), "AddrRep"),
    LOOKUP("libdwLookupLocation", Collections.unmodifiableList(Arrays.asList("AddrRep", "AddrRep", "AddrRep", null)), "Int32Rep"),
    CLEAR("libdwPoolClear", Collections.singletonList(null), null);

    private final String symbol;
    private final List<String> arguments;
    private final String result;

    LibdwForeignOp(String symbol, List<String> arguments, String result) {
        this.symbol = symbol;
        this.arguments = arguments;
        this.result = result;
    }

    public String getSymbol() { return symbol; }
    public List<String> getArguments() { return arguments; }
    public String getResult() { return result; }
}
