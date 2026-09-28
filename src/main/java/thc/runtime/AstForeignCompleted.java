// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import thc.Language;

/** The typed result is already in the caller's destination before this cut. */
public final class AstForeignCompleted implements AstResumeStep {
    private static final AstForeignCompleted COMPLETED = new AstForeignCompleted();
    private AstForeignCompleted() {}

    private static final class RestoreErrno implements AstResumeStep {
        private final long errno;
        RestoreErrno(long errno) { this.errno = errno; }
        @Override public Object resume(VirtualFrame frame, Object input) {
            if (input != kotlin.Unit.INSTANCE) throw RuntimeFault.fault("Invalid completed foreign-call continuation");
            Language.currentState(null).getStdio().captureForeignErrno$org_intelligence_thc(errno);
            return null;
        }
    }

    @Override public Object resume(VirtualFrame frame, Object input) {
        if (input != kotlin.Unit.INSTANCE) throw RuntimeFault.fault("Invalid completed foreign-call continuation");
        return null;
    }

    public static void poll(Node node) {
        if (!AstControl.enabled(node)) return;
        boolean compiled = CompilerDirectives.inCompiledCode();
        var request = GuestThreads.pollCurrent(node, false);
        if (request != null) {
            request.compiledCapture = compiled;
            throw new AstCapture(request, SynchronousMasking.current(node)).append(COMPLETED);
        }
    }

    /** Both the result destination and errno have committed before delivery.
     * A resumed waiter restores errno, never the process effect. */
    public static void poll(Node node, long errno, boolean interruptible) {
        if (!AstControl.enabled(node)) return;
        boolean compiled = CompilerDirectives.inCompiledCode();
        var request = GuestThreads.pollCurrent(node, interruptible);
        if (request != null) {
            request.compiledCapture = compiled;
            throw new AstCapture(request, SynchronousMasking.current(node)).append(new RestoreErrno(errno));
        }
    }
}
