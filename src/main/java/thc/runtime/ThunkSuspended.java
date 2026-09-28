// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.exception.AbstractTruffleException;

/** A root-local yield hands the shared thunk to another evaluator. */
public final class ThunkSuspended extends AbstractTruffleException implements InternalGuestControl {
    private final Thunk thunk;
    private final AsyncRequest asyncRequest;
    private final boolean stackSpill;
    public ThunkSuspended(Thunk thunk) { this(thunk, null); }
    public ThunkSuspended(Thunk thunk, AsyncRequest asyncRequest) { this(thunk, asyncRequest, false); }
    public ThunkSuspended(Thunk thunk, AsyncRequest asyncRequest, boolean stackSpill) {
        super("Internal bytecode thunk suspension", null, 0, null);
        this.thunk = thunk; this.asyncRequest = asyncRequest; this.stackSpill = stackSpill;
    }
    public Thunk getThunk() { return thunk; }
    public AsyncRequest getAsyncRequest() { return asyncRequest; }
    public boolean getStackSpill() { return stackSpill; }
}
