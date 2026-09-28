// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.*;

public record ForeignBitcode(String unit, String module, String target, Set<String> symbols, Map<String,String> abi, byte[] bytes) {

    public String getUnit() { return unit; }
    public String getModule() { return module; }
    public String getTarget() { return target; }
    public Set<String> getSymbols() { return symbols; }
    public Map<String,String> getAbi() { return abi; }
    public byte[] getBytes() { return bytes; }
}
