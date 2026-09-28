// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Exact original text-2.1.3 declarations, including their hidden State argument. */
public enum TextForeignOp {
    MEMCHR("_hs_text_memchr", "Word8Rep", "Word64Rep"),
    MEASURE("_hs_text_measure_off", "Word64Rep", "Word64Rep"),
    REVERSE("_hs_text_reverse", "Word64Rep", "BoxedRep (Just Unlifted)");

    private final String symbol;
    private final String lastRep;
    private final List<String> arguments;

    TextForeignOp(String symbol, String lastRep, String secondRep) {
        this.symbol = symbol;
        this.lastRep = lastRep;
        this.arguments = Collections.unmodifiableList(Arrays.asList("BoxedRep (Just Unlifted)",
            secondRep, "Word64Rep", lastRep, null));
    }

    public String getSymbol() { return symbol; }
    public String getLastRep() { return lastRep; }
    public boolean getByteNeedle() { return this == MEMCHR; }
    public List<String> getArguments() { return arguments; }
}
