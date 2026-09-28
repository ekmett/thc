// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.nodes.Node;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Original event-manager prerequisites; no OS event descriptors are manufactured. */
public enum RtsEventForeignOp {
    PROCESSORS("getNumberOfProcessors", "Word32Rep", "unsafe"),
    CAPABILITIES("setNumCapabilities", null, "safe"),
    SIGINFO_SIZE("__hscore_sizeof_siginfo_t", "Word64Rep", "safe");

    private final String symbol;
    private final String result;
    private final String safety;
    private static final List<String> STATE_ARGUMENT = Collections.singletonList(null);
    private static final List<String> CAPABILITY_ARGUMENTS = Collections.unmodifiableList(Arrays.asList("Word32Rep", null));

    RtsEventForeignOp(String symbol, String result, String safety) {
        this.symbol = symbol;
        this.result = result;
        this.safety = safety;
    }
    public String getSymbol() { return symbol; }
    public String getResult() { return result; }
    public String getSafety() { return safety; }
    public List<String> getArguments() { return this == CAPABILITIES ? CAPABILITY_ARGUMENTS : STATE_ARGUMENT; }
    public long invoke(Node node) { return invoke(node, 0L); }
    public long invoke(Node node, long count) {
        return switch (this) {
            case PROCESSORS -> GuestThreads.current(node).getCpuAffinity().getCount();
            case CAPABILITIES -> { GuestThreads.current(node).setCapabilityCount(count); yield 0L; }
            case SIGINFO_SIZE -> CoreOriginalStdio.current(node).siginfoSize();
        };
    }
}
