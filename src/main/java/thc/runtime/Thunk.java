// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import jam.vm.Lifted;

/** A shared lazy update cell; state publishes the answer and ownership release. */
public final class Thunk implements Lifted {
    private RootCallTarget target;
    private final boolean asynchronousExceptions;
    private CapturedFrame environment;
    // 0=unevaluated, 1=owned, 2=WHNF, 3=failure, 4=unsupported unwind, 5=parked.
    private volatile int state;
    private Object value;
    private Thread owner;
    private boolean hasWaited;

    public Thunk(RootCallTarget target, CapturedFrame environment) {
        this.target = target;
        this.environment = environment;
        // The target is cleared on completion or suspension; admission still needs its policy.
        asynchronousExceptions = target.getRootNode() instanceof GuestRoot root && root.getAsynchronousExceptions();
    }
    /** The volatile WHNF state publishes the existing answer; other states are opaque. */
    @Override public Lifted resolve() { return state == 2 && value instanceof Lifted answer ? answer : null; }
    /** Inspect an already published reference field without entering this thunk. */
    @Override public Lifted project(int field) {
        Object answer = LiftedValues.resolveBoxed(this);
        return answer != this && answer instanceof Lifted lifted ? lifted.project(field) : null;
    }
    public boolean getAsynchronousExceptions() { return asynchronousExceptions; }
    public RootCallTarget getTarget() { return target; }
    public void setTarget(RootCallTarget target) { this.target = target; }
    public CapturedFrame getEnvironment() { return environment; }
    public void setEnvironment(CapturedFrame environment) { this.environment = environment; }
    public int getState() { return state; }
    public void setState(int state) { this.state = state; }
    public Object getValue() { return value; }
    public void setValue(Object value) { this.value = value; }
    public Thread getOwner() { return owner; }
    public void setOwner(Thread owner) { this.owner = owner; }
    /** Caller holds this thunk's monitor. Registration precedes the atomic wait release. */
    void awaitUpdate() throws InterruptedException { hasWaited = true; wait(); }
    /** Caller holds this thunk's monitor. Once contended, retain every later wakeup. */
    void notifyUpdate() { if (hasWaited) notifyAll(); }
    // One stable monitor per thunk, with no additional lock allocation.
    public Object getMonitor() { return this; }
}
