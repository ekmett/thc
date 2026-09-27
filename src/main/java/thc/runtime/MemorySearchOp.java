// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public enum MemorySearchOp {
    COMPARE("memcmp", "AddrRep", "Int32Rep"),
    FIND("memchr", "Int32Rep", "AddrRep");

    private final String symbol;
    private final String second;
    private final String result;
    private final List<String> arguments;

    MemorySearchOp(String symbol, String second, String result) {
        this.symbol = symbol;
        this.second = second;
        this.result = result;
        this.arguments = Collections.unmodifiableList(Arrays.asList("AddrRep", second, "Word64Rep", null));
    }

    public String getSymbol() { return symbol; }
    public String getSecond() { return second; }
    public String getResult() { return result; }
    public List<String> getArguments() { return arguments; }
}
