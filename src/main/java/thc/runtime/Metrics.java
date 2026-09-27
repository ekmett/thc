// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class Metrics {
    private final boolean enabled;
    private final Map<String, Long> thunkCounts = new LinkedHashMap<>();
    public Metrics(boolean enabled) { this.enabled = enabled; }
    public boolean getEnabled() { return enabled; }
    @TruffleBoundary public void recordThunk(RootCallTarget target) {
        String name = target.getRootNode().getName();
        String label = name == null ? "<unnamed>" : name;
        synchronized (this) { thunkCounts.put(label, thunkCounts.getOrDefault(label, 0L) + 1L); }
    }
    @TruffleBoundary public synchronized Map<String, Long> thunkCountsSnapshot() {
        return new LinkedHashMap<>(thunkCounts);
    }
    private final AtomicLong compiledEntriesCounter = new AtomicLong();
    public long getCompiledEntries() { return compiledEntriesCounter.get(); }
    public void incrementCompiledEntries() { compiledEntriesCounter.incrementAndGet(); }
    private final AtomicLong leadingCaseReturnsCounter = new AtomicLong();
    public long getLeadingCaseReturns() { return leadingCaseReturnsCounter.get(); }
    public void incrementLeadingCaseReturns() { leadingCaseReturnsCounter.incrementAndGet(); }
    private final AtomicLong thunkEvaluationsCounter = new AtomicLong();
    public long getThunkEvaluations() { return thunkEvaluationsCounter.get(); }
    public void incrementThunkEvaluations() { thunkEvaluationsCounter.incrementAndGet(); }
    private final AtomicLong thunkHitsCounter = new AtomicLong();
    public long getThunkHits() { return thunkHitsCounter.get(); }
    public void incrementThunkHits() { thunkHitsCounter.incrementAndGet(); }
    private final AtomicLong blackholesCounter = new AtomicLong();
    public long getBlackholes() { return blackholesCounter.get(); }
    public void incrementBlackholes() { blackholesCounter.incrementAndGet(); }
    private final AtomicLong directCacheMissesCounter = new AtomicLong();
    public long getDirectCacheMisses() { return directCacheMissesCounter.get(); }
    public void incrementDirectCacheMisses() { directCacheMissesCounter.incrementAndGet(); }
    private final AtomicLong indirectCallsCounter = new AtomicLong();
    public long getIndirectCalls() { return indirectCallsCounter.get(); }
    public void incrementIndirectCalls() { indirectCallsCounter.incrementAndGet(); }
    private final AtomicLong tailBouncesCounter = new AtomicLong();
    public long getTailBounces() { return tailBouncesCounter.get(); }
    public void incrementTailBounces() { tailBouncesCounter.incrementAndGet(); }
    private final AtomicLong selfTailReentriesCounter = new AtomicLong();
    public long getSelfTailReentries() { return selfTailReentriesCounter.get(); }
    public void incrementSelfTailReentries() { selfTailReentriesCounter.incrementAndGet(); }
    private final AtomicLong localJoinTransfersCounter = new AtomicLong();
    public long getLocalJoinTransfers() { return localJoinTransfersCounter.get(); }
    public void incrementLocalJoinTransfers() { localJoinTransfersCounter.incrementAndGet(); }
    private final AtomicLong trampolineIterationsCounter = new AtomicLong();
    public long getTrampolineIterations() { return trampolineIterationsCounter.get(); }
    public void incrementTrampolineIterations() { trampolineIterationsCounter.incrementAndGet(); }
    private final AtomicLong papAllocationsCounter = new AtomicLong();
    public long getPapAllocations() { return papAllocationsCounter.get(); }
    public void incrementPapAllocations() { papAllocationsCounter.incrementAndGet(); }
    private final AtomicLong unsupportedTrapsCounter = new AtomicLong();
    public long getUnsupportedTraps() { return unsupportedTrapsCounter.get(); }
    public void incrementUnsupportedTraps() { unsupportedTrapsCounter.incrementAndGet(); }
}
