// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Original GHC RTS declarations, translated to the JVM rather than fabricated RTS counters. */
public enum GcForeignOp {
    ENABLED("getRTSStatsEnabled", "IntRep"),
    STATS("getRTSStats", null),
    MINOR("performGC", null),
    MAJOR("performMajorGC", null),
    BLOCKING("performBlockingMajorGC", null),
    MONOTONIC("getMonotonicNSec", "Word64Rep", "unsafe"),
    HEAP_HINT("setHeapSize", null, "unsafe");

    private final String symbol;
    private final String result;
    private final String safety;
    private static final List<String> STATE_ARGUMENT = Collections.singletonList(null);
    private static final List<String> STATS_ARGUMENTS = Collections.unmodifiableList(Arrays.asList("AddrRep", null));
    private static final List<String> HEAP_ARGUMENTS = Collections.unmodifiableList(Arrays.asList("IntRep", null));

    GcForeignOp(String symbol, String result) { this(symbol, result, "safe"); }
    GcForeignOp(String symbol, String result, String safety) {
        this.symbol = symbol;
        this.result = result;
        this.safety = safety;
    }
    public String getSymbol() { return symbol; }
    public String getResult() { return result; }
    public String getSafety() { return safety; }
    public String getUnit() { return this == HEAP_HINT ? "ghc-9.14.1-inplace" : "ghc-internal"; }
    public List<String> getArguments() {
        return switch (this) {
            case STATS -> STATS_ARGUMENTS;
            case HEAP_HINT -> HEAP_ARGUMENTS;
            default -> STATE_ARGUMENT;
        };
    }

    @TruffleBoundary
    public long invoke() {
        return switch (this) {
            case ENABLED -> 0L;
            case STATS -> throw fault("GHC RTS statistics are unavailable on the JVM; getRTSStatsEnabled is false");
            case MONOTONIC -> System.nanoTime();
            // The compiler's native heap suggestion cannot resize a context's share
            // of the JVM heap. Ignore the advisory; do not change global VM policy.
            case HEAP_HINT -> 0L;
            default -> {
                System.gc(); // Advisory; JVM flags/collector decide when and what to collect.
                yield 0L;
            }
        };
    }
}
