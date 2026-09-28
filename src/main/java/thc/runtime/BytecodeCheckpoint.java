// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import java.util.concurrent.atomic.AtomicInteger;

/** In-process proof control. Exported Core has no way to construct or arm this. */
public final class BytecodeCheckpoint {
    private volatile boolean armed;
    private final AtomicInteger visits = new AtomicInteger();
    private final AtomicInteger compiledVisits = new AtomicInteger();

    public boolean getArmed() { return armed; }
    public void setArmed(boolean armed) { this.armed = armed; }
    public AtomicInteger getVisits() { return visits; }
    public AtomicInteger getCompiledVisits() { return compiledVisits; }
}
