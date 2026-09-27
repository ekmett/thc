// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public enum EnvironmentOp {
    GET("getenv", "AddrRep", "AddrRep", null),
    PUT("putenv", "Int32Rep", "AddrRep", null),
    UNSET("__hsbase_unsetenv", "Int32Rep", "AddrRep", null),
    ENUMERATE("__hscore_environ", "AddrRep", (String) null);

    private final String symbol;
    private final String result;
    private final List<String> arguments;

    EnvironmentOp(String symbol, String result, String... arguments) {
        this.symbol = symbol;
        this.result = result;
        this.arguments = Collections.unmodifiableList(Arrays.asList(arguments));
    }

    public String getSymbol() { return symbol; }
    public String getResult() { return result; }
    public List<String> getArguments() { return arguments; }
}
