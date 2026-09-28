// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Exact safe, non-returning GHC.Internal.TopHandler declarations. */
public enum RtsShutdownOp {
    EXIT("shutdownHaskellAndExit"), SIGNAL("shutdownHaskellAndSignal");
    private final String symbol;
    private final List<String> arguments = Collections.unmodifiableList(Arrays.asList("Int32Rep", "Int32Rep", null));
    RtsShutdownOp(String symbol) { this.symbol = symbol; }
    public String getSymbol() { return symbol; }
    public List<String> getArguments() { return arguments; }
    public String getResult() { return null; }
}
