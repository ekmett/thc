// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.List;
import java.util.Map;
import thc.runtime.CoreRepresentation;

public record ManagedExportSignature(String unit, String module, String symbol, String binder,
        List<Map<String,Object>> arguments, Map<String,Object> result, boolean io, int wordBits, CoreRepresentation ioResult) {
    public ManagedExportSignature(String unit, String module, String symbol, String binder, List<Map<String,Object>> arguments,
            Map<String,Object> result, boolean io, int wordBits) { this(unit, module, symbol, binder, arguments, result, io, wordBits, null); }
    public String getUnit() { return unit; }
    public String getModule() { return module; }
    public String getSymbol() { return symbol; }
    public String getBinder() { return binder; }
    public List<Map<String,Object>> getArguments() { return arguments; }
    public Map<String,Object> getResult() { return result; }
    public boolean getIo() { return io; }
    public int getWordBits() { return wordBits; }
    public CoreRepresentation getIoResult() { return ioResult; }
}
