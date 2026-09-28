// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** Closed managed-memory ABI only; this enum is not a general FFI dispatch table. */
public enum Md5ForeignOp {
    INIT("__hsbase_MD5Init", 2), UPDATE("__hsbase_MD5Update", 4), FINAL("__hsbase_MD5Final", 3);

    private final String symbol;
    private final int arity;
    Md5ForeignOp(String symbol, int arity) { this.symbol = symbol; this.arity = arity; }
    public String getSymbol() { return symbol; }
    public int getArity() { return arity; }
}
