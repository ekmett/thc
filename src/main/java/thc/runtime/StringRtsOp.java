// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Original Posix and ByteString declarations with their exact installed units. */
public enum StringRtsOp {
    STRLEN("strlen", Arrays.asList("AddrRep", null), "ghc-internal", "IntRep"),
    STRLEN_CSIZE("strlen", Arrays.asList("AddrRep", null), "bytestring-0.12.2.0-inplace", "Word64Rep"),
    THREADED("rts_isThreaded", Collections.singletonList(null), "ghc-internal", "IntRep"),
    KEEP_CAFS("keepCAFsForGHCi", Collections.singletonList(null), "ghc-9.14.1-inplace", "IntRep");

    private final String symbol;
    private final List<String> arguments;
    private final String unit;
    private final String result;

    StringRtsOp(String symbol, List<String> arguments, String unit, String result) {
        this.symbol = symbol;
        this.arguments = Collections.unmodifiableList(arguments);
        this.unit = unit;
        this.result = result;
    }

    public String getSymbol() { return symbol; }
    public List<String> getArguments() { return arguments; }
    public String getUnit() { return unit; }
    public String getResult() { return result; }

    public boolean acceptsUnit(Object value) {
        return this == STRLEN_CSIZE ? CoreMemorySearchForeign.isOriginalByteStringUnit(value) : unit.equals(value);
    }
}
