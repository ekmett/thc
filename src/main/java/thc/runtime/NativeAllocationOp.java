// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Exact GHC 9.14.1 Foreign.Marshal.Alloc allocation declarations. */
public enum NativeAllocationOp {
    MALLOC("malloc", Collections.unmodifiableList(Arrays.asList("Word64Rep", null)), "AddrRep"),
    REALLOC("realloc", Collections.unmodifiableList(Arrays.asList("AddrRep", "Word64Rep", null)), "AddrRep"),
    FREE("free", Collections.unmodifiableList(Arrays.asList("AddrRep", null)), null);

    private final String symbol;
    private final List<String> arguments;
    private final String result;

    NativeAllocationOp(String symbol, List<String> arguments, String result) {
        this.symbol = symbol;
        this.arguments = arguments;
        this.result = result;
    }

    public String getSymbol() { return symbol; }
    public List<String> getArguments() { return arguments; }
    public String getResult() { return result; }
}
