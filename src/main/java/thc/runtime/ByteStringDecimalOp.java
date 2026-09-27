// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Arrays;
import java.util.List;

enum ByteStringDecimalOp {
    SIGNED("_hs_bytestring_long_long_int_dec", true),
    PADDED18("_hs_bytestring_long_long_int_dec_padded18", false);

    private final String symbol;
    private final boolean addressResult;
    private final List<String> arguments = Arrays.asList("Int64Rep", "AddrRep", null);

    ByteStringDecimalOp(String symbol, boolean addressResult) {
        this.symbol = symbol;
        this.addressResult = addressResult;
    }

    public String getSymbol() { return symbol; }
    public boolean getAddressResult() { return addressResult; }
    public List<String> getArguments() { return arguments; }
}
