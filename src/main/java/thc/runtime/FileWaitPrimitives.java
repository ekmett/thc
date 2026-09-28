// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import java.nio.channels.ClosedChannelException;
import thc.Language;

/** Bytecode operations retain opaque logical wait tokens across async cuts. */
public final class FileWaitPrimitives {
    private FileWaitPrimitives() {}
    @TruffleBoundary(transferToInterpreterOnException = false)
    static void badFileDescriptor(GlobalBinding payload, Node node) { throw new GuestException(payload.read(), node); }
    public static Object prepareFileWait(long fd, Object state, boolean writing, Node node) {
        TupleResults.requireVoidCarrier(state);
        return Language.currentState(node).getFiles().waitToken(fd, writing);
    }
    public static Object awaitFileWait(Object token, GlobalBinding payload, boolean async, boolean compiledAtCut, Node node) {
        if (!(token instanceof ManagedFiles.WaitToken saved)) throw RuntimeFault.fault("Invalid descriptor-wait token");
        try { saved.await(node, async, compiledAtCut); }
        catch (Throwable failure) {
            if (failure instanceof ClosedChannelException) badFileDescriptor(payload, node);
            throw propagate(failure);
        }
        return thc.runtime.Unit.INSTANCE;
    }
    @SuppressWarnings("unchecked") static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E {
        throw (E) failure;
    }
}
