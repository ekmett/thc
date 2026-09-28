// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.nodes.Node;

/** The acquisition site, not its consumers, owns both bounded dispatcher children. */
public final class InteropLibraryAcquisition extends Node {
    @Child private volatile InteropLibrary first;
    @Child private volatile InteropLibrary other;
    @Child private ForeignExceptionAccess exceptions = new ForeignExceptionAccess();

    public InteropLibrary execute(Object receiver) {
        if (!InteropLibrary.isValidValue(receiver))
            throw exceptions.raise(InteropFailure.create(UnsupportedTypeException.create(new Object[]{receiver}, "Not an interop receiver")));
        InteropLibrary selected = first;
        if (selected == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            selected = atomic(() -> {
                if (first == null) first = insert(InteropLibrary.getFactory().create(receiver));
                return first;
            });
        }
        if (selected.accepts(receiver)) return selected;
        selected = other;
        if (selected == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            // Bounded polymorphic dispatch may use Truffle's generic fallback.
            // The actual returned instance is never substituted at a use site.
            selected = atomic(() -> {
                if (other == null) other = insert(InteropLibrary.getFactory().createDispatched(3));
                return other;
            });
        }
        return selected;
    }
}
