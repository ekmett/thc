// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import java.lang.ref.WeakReference;
/** An unlifted ThreadId# names a guest lifetime, independently of its Java carrier. */
public final class GuestThreadId {
    final long logicalId;
    final GuestThreads owner;
    // A retained ThreadId keeps the canonical logical lifetime and captured work alive.
    volatile GuestThreads.GuestThread lifetime;
    volatile long capability;
    final long javaId;
    final WeakReference<Thread> carrier;
    final boolean forked, capabilityLocked, callback;
    boolean affinityApplied;
    volatile GuestThreadStatus status = GuestThreadStatus.RUNNING;
    volatile GuestThreadStatus lastOutcome = GuestThreadStatus.FINISHED;
    long allocationRemaining;
    long allocationBaseline = -1;
    boolean allocationUnavailable, allocationSuspended;
    public GuestThreadId(long logicalId, GuestThreads owner, long capability, Thread carrier, boolean forked) {
        this(logicalId, owner, capability, carrier, forked, false, false);
    }
    public GuestThreadId(long logicalId, GuestThreads owner, long capability, Thread carrier, boolean forked, boolean capabilityLocked) {
        this(logicalId, owner, capability, carrier, forked, capabilityLocked, false);
    }
    public GuestThreadId(long logicalId, GuestThreads owner, long capability, Thread carrier, boolean forked, boolean capabilityLocked, boolean callback) {
        this.logicalId = logicalId; this.owner = owner; this.capability = capability; this.forked = forked;
        this.capabilityLocked = capabilityLocked; this.callback = callback; javaId = carrier.threadId();
        this.carrier = new WeakReference<>(carrier);
    }
    public long getLogicalId() { return logicalId; }
    public GuestThreads getOwner() { return owner; }
    public long getCapability() { return capability; }
    public void setCapability(long value) { capability = value; }
    public long getJavaId() { return javaId; }
    public WeakReference<Thread> getCarrier() { return carrier; }
    public boolean getForked() { return forked; }
    public boolean getCapabilityLocked() { return capabilityLocked; }
    public boolean getCallback() { return callback; }
    public boolean getAffinityApplied() { return affinityApplied; }
    public void setAffinityApplied(boolean value) { affinityApplied = value; }
    public GuestThreadStatus getStatus() { return status; }
    public void setStatus(GuestThreadStatus value) { status = value; }
    public GuestThreadStatus getLastOutcome() { return lastOutcome; }
    public void setLastOutcome(GuestThreadStatus value) { lastOutcome = value; }
    public long getAllocationRemaining() { return allocationRemaining; }
    public void setAllocationRemaining(long value) { allocationRemaining = value; }
    public long getAllocationBaseline() { return allocationBaseline; }
    public void setAllocationBaseline(long value) { allocationBaseline = value; }
    public boolean getAllocationUnavailable() { return allocationUnavailable; }
    public void setAllocationUnavailable(boolean value) { allocationUnavailable = value; }
    public boolean getAllocationSuspended() { return allocationSuspended; }
    public void setAllocationSuspended(boolean value) { allocationSuspended = value; }
    @Override public boolean equals(Object other) {
        return other instanceof GuestThreadId id && owner == id.owner && logicalId == id.logicalId;
    }
    @Override public int hashCode() { return 31 * System.identityHashCode(owner) + Long.hashCode(logicalId); }
}
