// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.*;

public record PackageScalarSignature(String symbol, String entry, List<String> arguments, String result, String convention, String safety) {
    public PackageScalarSignature(String symbol, String entry, List<String> arguments, String result) { this(symbol, entry, arguments, result, "ccall", "unsafe"); }
    public PackageScalarSignature(String symbol, String entry, List<String> arguments, String result, String convention) { this(symbol, entry, arguments, result, convention, "unsafe"); }
    public String getSymbol() { return symbol; }
    public String getEntry() { return entry; }
    public List<String> getArguments() { return arguments; }
    public String getResult() { return result; }
    public String getConvention() { return convention; }
    public String getSafety() { return safety; }
}
