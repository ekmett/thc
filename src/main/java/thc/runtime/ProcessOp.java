// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Original process-1.6.26.1 POSIX imports, including the interruptible wait. */
public enum ProcessOp {
    CREATE("runInteractiveProcess", "unsafe", Collections.unmodifiableList(Arrays.asList("AddrRep", "AddrRep", "AddrRep", "Int32Rep", "Int32Rep",
        "Int32Rep", "AddrRep", "AddrRep", "AddrRep", "AddrRep", "AddrRep", "Int32Rep", "AddrRep", null))),
    POLL("getProcessExitCode", "unsafe", Collections.unmodifiableList(Arrays.asList("Int32Rep", "AddrRep", null))),
    WAIT("waitForProcess", "interruptible", Collections.unmodifiableList(Arrays.asList("Int32Rep", "AddrRep", null))),
    TERMINATE("terminateProcess", "unsafe", Collections.unmodifiableList(Arrays.asList("Int32Rep", null)));

    private final String symbol;
    private final String safety;
    private final List<String> arguments;

    ProcessOp(String symbol, String safety, List<String> arguments) {
        this.symbol = symbol; this.safety = safety; this.arguments = arguments;
    }

    public String getSymbol() { return symbol; }
    public String getSafety() { return safety; }
    public List<String> getArguments() { return arguments; }
}
