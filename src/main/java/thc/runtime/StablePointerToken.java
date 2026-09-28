// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import java.lang.foreign.Arena;

/** Persistent opaque identity, not guest byte storage. A genuine native address
 * also works when host C retains the token. Simultaneously live contexts cannot
 * collide as they could with Sulong's context-local numeric handle slots. */
@ExportLibrary(InteropLibrary.class)
public final class StablePointerToken implements TruffleObject, AutoCloseable {
    private final StablePointers owner;
    private final Arena arena = Arena.ofShared();
    private final long bits;
    private volatile boolean closed;
    public StablePointerToken(StablePointers owner) {
        this.owner = owner;
        try { bits = arena.allocate(1).address(); }
        catch (Throwable failure) { arena.close(); throw failure; }
    }
    public long getBits() { return bits; }
    @ExportMessage public boolean isPointer() { return !closed; }
    @ExportMessage public long asPointer() throws UnsupportedMessageException {
        owner.requireNativeAccess();
        if (closed) throw UnsupportedMessageException.create();
        return bits;
    }
    @Override public synchronized void close() {
        if (!closed) { closed = true; arena.close(); }
    }
}
