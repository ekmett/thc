// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Original GHC.Internal.System.Environment declarations, not arbitrary C calls. */
public enum RtsArgumentsOp {
    GET("getProgArgv", "AddrRep", "AddrRep", null),
    SET("setProgArgv", "Int32Rep", "AddrRep", null);

    private final String symbol;
    private final List<String> arguments;

    RtsArgumentsOp(String symbol, String... arguments) {
        this.symbol = symbol;
        this.arguments = Collections.unmodifiableList(Arrays.asList(arguments));
    }

    public String getSymbol() { return symbol; }
    public List<String> getArguments() { return arguments; }
}
