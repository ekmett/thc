// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;

/** A certified application becomes inert only after its actual head is known. */
final class DeferredPap extends Expr {
    private final GlobalBinding head;
    private final int arguments;
    @Child private Expr application;
    @Child private Expr suspension;
    DeferredPap(GlobalBinding head, int arguments, Expr application, Expr suspension) {
        this.head = head; this.arguments = arguments;
        this.application = application; this.suspension = suspension;
        setRepresentation(suspension.getRepresentation());
    }
    static boolean canConstruct(GlobalBinding head, int arguments) {
        return head.peek() instanceof Closure closure && arguments < closure.arity;
    }
    @Override public Object execute(VirtualFrame frame) {
        return canConstruct(head, arguments) ? application.execute(frame) : suspension.execute(frame);
    }
}
