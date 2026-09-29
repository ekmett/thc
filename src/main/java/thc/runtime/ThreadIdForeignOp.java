// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.nodes.Node;

/** GHC TSO identity operations must observe THC's guest lifetimes, never native heap pointers. */
public enum ThreadIdForeignOp {
    ID("rts_getThreadId", 1, "Word64Rep"), EQUAL("eq_thread", 2, "Word8Rep"), ORDER("cmp_thread", 2, "Int32Rep");
    private final String symbol, result;
    private final int arguments;
    ThreadIdForeignOp(String symbol, int arguments, String result) {
        this.symbol = symbol; this.arguments = arguments; this.result = result;
    }
    public String getSymbol() { return symbol; }
    public String getResult() { return result; }
    public int getArguments() { return arguments; }
    public static ThreadIdForeignOp named(Object symbol) {
        for (var operation : values()) if (operation.symbol.equals(symbol)) return operation;
        return null;
    }
    public long execute(Node node, Object first, Object second) {
        var threads = GuestThreads.current(node);
        var left = threads.requireIdentity(first);
        if (this == ID) return left.getLogicalId();
        var right = threads.requireIdentity(second);
        return this == EQUAL ? left == right ? 1L : 0L : Long.compareUnsigned(left.getLogicalId(), right.getLogicalId());
    }
}
