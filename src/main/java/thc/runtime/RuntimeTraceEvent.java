// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;

/** JFR's eventThread is the emitter's Java thread; IDs are not GHC TSO addresses. */
@Name("thc.RuntimeTrace")
@Label("THC structured trace")
@Category("THC")
@StackTrace(false)
public final class RuntimeTraceEvent extends Event {
    public long contextId;
    public String phase = "";
    public long spanId;
    public String message = "";
    /** Exact original primop bytes as lowercase hex; empty for THC.Trace events. */
    public String payloadHex = "";
    @Timespan(Timespan.NANOSECONDS) public long elapsedNanos;
}
