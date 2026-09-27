// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

public final class HandoffState {
    private final HandoffPool arguments = new HandoffPool();
    private final TupleResultPool results = new TupleResultPool();
    public HandoffPool getArguments() { return arguments; }
    public TupleResultPool getResults() { return results; }
    private HandoffStorage pending = null;
    public HandoffStorage getPending() { return pending; }
    public void setPending(HandoffStorage value) { pending = value; }
    private int returnInt = 0;
    public int getReturnInt() { return returnInt; }
    public void setReturnInt(int value) { returnInt = value; }
    private long returnLong = 0L;
    public long getReturnLong() { return returnLong; }
    public void setReturnLong(long value) { returnLong = value; }
    private long calls = 0L;
    public long getCalls() { return calls; }
    public void setCalls(long value) { calls = value; }
    private long tailTransfers = 0L;
    public long getTailTransfers() { return tailTransfers; }
    public void setTailTransfers(long value) { tailTransfers = value; }
}
