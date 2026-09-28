// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import java.util.List;
public enum PolyglotOp {
    EVAL("thc_polyglot_v1_eval", List.of("AddrRep", "AddrRep", "AddrRep", "State# RealWorld"), "BoxedRep (Just Lifted)"),
    READ_MEMBER("thc_polyglot_v1_read_member", List.of("BoxedRep (Just Lifted)", "AddrRep", "State# RealWorld"), "BoxedRep (Just Lifted)"),
    EXECUTE_INT("thc_polyglot_v1_execute_int", List.of("BoxedRep (Just Lifted)", "IntRep", "State# RealWorld"), "IntRep");
    private final String symbol;
    private final List<String> arguments;
    private final String result;
    PolyglotOp(String symbol, List<String> arguments, String result) { this.symbol = symbol; this.arguments = arguments; this.result = result; }
    public String getSymbol() { return symbol; }
    public List<String> getArguments() { return arguments; }
    public String getResult() { return result; }
}
