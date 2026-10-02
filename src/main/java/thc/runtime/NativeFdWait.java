// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.nodes.Node;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.atomic.AtomicBoolean;

/** Private native readiness transport. A duplicate of an authenticated lease
 * prevents fd reuse from redirecting a wait. Logical close signals a private
 * wake descriptor; it never closes a descriptor underneath the wait. Wake/cleanup calls
 * work without entering a possibly disposed LLVM context. */
public final class NativeFdWait implements AutoCloseable, TruffleSafepoint.Interrupter {
    private final int descriptor;
    private final NativePollApi.Wakeup wake;
    private final Arena arena;
    private final MemorySegment polls;
    private final MemorySegment one;
    private final MemorySegment drained;
    private final MemorySegment wakeErrors;
    private final MemorySegment closeErrors;
    private final MemorySegment drainErrors;
    private final AtomicBoolean interrupted = new AtomicBoolean();
    private volatile boolean descriptorClosed;
    private boolean released;

    private NativeFdWait(int descriptor, NativePollApi.Wakeup wake, Arena arena) {
        this.descriptor = descriptor;
        this.wake = wake;
        this.arena = arena;
        polls = arena.allocate(16, 4); // Target/wake events in the selected-ABI pollfd layout.
        one = arena.allocate(ValueLayout.JAVA_LONG);
        one.set(ValueLayout.JAVA_LONG, 0, 1L);
        drained = arena.allocate(ValueLayout.JAVA_LONG);
        wakeErrors = arena.allocate(NativePollApi.CAPTURE);
        closeErrors = arena.allocate(NativePollApi.CAPTURE);
        drainErrors = arena.allocate(NativePollApi.CAPTURE);
        polls.set(ValueLayout.JAVA_INT, 0, descriptor);
        polls.set(ValueLayout.JAVA_INT, 8, wake.read());
        polls.set(ValueLayout.JAVA_SHORT, 12, (short) 1);
    }

    /** Ownership of the private duplicate transfers even if setup fails. */
    public static NativeFdWait acquire(NativeFileLease lease) {
        var arena = Arena.ofShared();
        int descriptor = -1;
        NativePollApi.Wakeup wake = null;
        try {
            descriptor = lease.duplicateForWait();
            wake = NativePollApi.wake();
            return new NativeFdWait(descriptor, wake, arena);
        } catch (Throwable failure) {
            if (wake != null) {
                try { wake.close(); }
                catch (Throwable closing) { failure.addSuppressed(closing); }
            }
            if (descriptor >= 0) {
                try { NativePollApi.close(descriptor); }
                catch (Throwable closing) { failure.addSuppressed(closing); }
            }
            arena.close();
            throw propagate(failure);
        }
    }

    // Truffle holds internal locks: callbacks only perform nonblocking wake IO.
    @Override public void interrupt(Thread thread) {
        interrupted.set(true);
        NativePollApi.signal(wake.write(), one, wakeErrors);
    }

    @Override public void resetInterrupted() {
        // Truffle 25.3.4.1 serializes interrupt/reset/unregister on its per-target
        // lock. The independent descriptorClosed flag stays sticky if drained.
        NativePollApi.drain(wake.read(), drained, drainErrors);
        interrupted.set(false);
    }

    public void descriptorClosed() {
        descriptorClosed = true;
        NativePollApi.signal(wake.write(), one, closeErrors);
    }

    public int await(Node node, boolean writing, long milliseconds) { return await(node, writing, milliseconds, null); }

    /** -2 denotes logical close; the caller selects the original guest response. */
    public int await(Node node, boolean writing, long milliseconds, Runnable beforeBlock) {
        polls.set(ValueLayout.JAVA_SHORT, 4, (short) (writing ? 4 : 1));
        long started = System.nanoTime();
        TruffleSafepoint.InterruptibleFunction<thc.runtime.Unit, Integer> action = ignored -> {
            while (true) {
                if (interrupted.get()) throw new InterruptedException();
                if (descriptorClosed) return -2;
                // Only genuine blocking is an interruptible Haskell cut.
                int probe = NativePollApi.readiness(polls, 0);
                if (interrupted.get()) throw new InterruptedException();
                if (descriptorClosed) return -2;
                if (probe > 0 && polls.get(ValueLayout.JAVA_SHORT, 6) != 0) return 1;
                if (milliseconds == 0) return 0;
                if (beforeBlock != null) beforeBlock.run();
                int timeout = remaining(started, milliseconds);
                int ready = NativePollApi.readiness(polls, timeout);
                if (interrupted.get()) throw new InterruptedException();
                if (descriptorClosed) return -2;
                if (polls.get(ValueLayout.JAVA_SHORT, 14) != 0) throw new InterruptedException();
                if (ready > 0) return 1;
                if (milliseconds >= 0 && remaining(started, milliseconds) == 0) return 0;
                // Only a timeout chunk longer than INT_MAX milliseconds reaches here.
            }
        };
        return TruffleSafepoint.getCurrent().setBlockedFunction(node, this, action, thc.runtime.Unit.INSTANCE, null, null);
    }

    private static int remaining(long started, long milliseconds) {
        if (milliseconds < 0) return -1;
        return Math.clamp(milliseconds - Math.max(0, System.nanoTime() - started) / 1_000_000, 0, Integer.MAX_VALUE);
    }

    /** Both registries must unregister before native destruction can begin. */
    @Override public void close() {
        if (released) return;
        released = true;
        try { wake.close(); }
        finally { try { NativePollApi.close(descriptor); } finally { arena.close(); } }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
}
