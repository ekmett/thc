// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.nodes.Node;
public final class GuestException extends AbstractTruffleException {
    private final Object payload;
    private final boolean someException;
    public GuestException(Object payload, Node location) { this(payload, location, false); }
    public GuestException(Object payload, Node location, boolean someException) {
        super("Haskell exception (payload retained lazily)", location);
        this.payload = payload; this.someException = someException;
    }
    public Object getPayload() { return payload; }
    public boolean getSomeException() { return someException; }
}
