// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Exact original GHC declarations only; this table does not enable execution. */
public enum OriginalStackInfoOp {
    STACK_INFO("getStackInfoTableAddrzh", "prim", List.of("BoxedRep (Just Unlifted)"), List.of("AddrRep"), false),
    FRAME_INFO("getInfoTableAddrszh", "prim", List.of("BoxedRep (Just Unlifted)", "WordRep"), List.of("AddrRep", "AddrRep"), true),
    STACK_FIELDS("getStackFieldszh", "prim", List.of("BoxedRep (Just Unlifted)"), List.of("Word32Rep"), false),
    SMALL_BITMAP("getSmallBitmapzh", "prim", List.of("BoxedRep (Just Unlifted)", "WordRep"), List.of("WordRep", "WordRep"), true),
    ADVANCE("advanceStackFrameLocationzh", "prim", List.of("BoxedRep (Just Unlifted)", "WordRep"),
        List.of("BoxedRep (Just Unlifted)", "WordRep", "IntRep"), true),
    WORD("getWordzh", "prim", List.of("BoxedRep (Just Unlifted)", "WordRep"), List.of("WordRep"), false),
    CLOSURE("getStackClosurezh", "prim", List.of("BoxedRep (Just Unlifted)", "WordRep"), List.of("BoxedRep (Just Lifted)"), false),
    LARGE_BITMAP("getLargeBitmapzh", "prim", List.of("BoxedRep (Just Unlifted)", "WordRep"), List.of("AddrRep", "WordRep"), true),
    BCO_LARGE_BITMAP("getBCOLargeBitmapzh", "prim", List.of("BoxedRep (Just Unlifted)", "WordRep"), List.of("AddrRep", "WordRep"), true),
    RET_FUN_LARGE_BITMAP("getRetFunLargeBitmapzh", "prim", List.of("BoxedRep (Just Unlifted)", "WordRep"), List.of("AddrRep", "WordRep"), true),
    RET_FUN_SMALL_BITMAP("getRetFunSmallBitmapzh", "prim", List.of("BoxedRep (Just Unlifted)", "WordRep"), List.of("WordRep", "WordRep"), true),
    RET_FUN_BIG("isArgGenBigRetFunTypezh", "prim", List.of("BoxedRep (Just Unlifted)", "WordRep"), List.of("IntRep"), false),
    UNDERFLOW("getUnderflowFrameNextChunkzh", "prim", List.of("BoxedRep (Just Unlifted)", "WordRep"), List.of("BoxedRep (Just Unlifted)"), false),
    LOOKUP_IPE("lookupIPE", "ccall", Collections.unmodifiableList(Arrays.asList("AddrRep", "AddrRep", null)),
        Collections.unmodifiableList(Arrays.asList(null, "Word8Rep")), true);

    private final String symbol;
    private final String convention;
    private final List<String> arguments;
    private final List<String> results;
    private final boolean tupleResult;

    OriginalStackInfoOp(String symbol, String convention, List<String> arguments, List<String> results, boolean tupleResult) {
        this.symbol = symbol;
        this.convention = convention;
        this.arguments = arguments;
        this.results = results;
        this.tupleResult = tupleResult;
    }

    public String getSymbol() { return symbol; }
    public String getConvention() { return convention; }
    public List<String> getArguments() { return arguments; }
    public List<String> getResults() { return results; }
    public boolean getTupleResult() { return tupleResult; }
}
