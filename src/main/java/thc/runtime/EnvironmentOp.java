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
    SET("setenv", "Int32Rep", "AddrRep", "AddrRep", "Int32Rep", null),
    CLEAR("clearenv", "IntRep", (String) null),
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
    public boolean matchesSymbol(Object value) {
        if (symbol.equals(value)) return true;
        if (this == ENUMERATE) return "__hsunix_get_environ".equals(value);
        return this == UNSET && value instanceof String name && name.matches(
            "ghczuwrapperZC0ZCunixzm2zi8zi8zi0zm(?:inplace|[0-9a-f]+)ZCSystemziPosixziEnv(?:ziByteString|ziPosixString)?ZCunsetenv");
    }
}
