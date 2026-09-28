// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.nodes.Node;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.atomic.AtomicBoolean;

/** A poll snapshot owns its duplicates. A separate eventfd wakes cancellation
 * or logical close without closing an fd beneath the syscall. */
public final class NativeEventWait implements AutoCloseable, TruffleSafepoint.Interrupter {
    private final int[] descriptors;
    private final int wake;
    private final Arena arena;
    private final MemorySegment polls;
    private final MemorySegment one;
    private final MemorySegment drained;
    private final MemorySegment interruptErrors;
    private final MemorySegment drainErrors;
    private final AtomicBoolean interrupted = new AtomicBoolean();
    private boolean released;
    private final Watch[] watches;

    private NativeEventWait(int[] descriptors, short[] events, boolean[] invalid, int wake, Arena arena) {
        this.descriptors = descriptors;
        this.wake = wake;
        this.arena = arena;
        polls = arena.allocate(((long) descriptors.length + 1) * 8, 4);
        one = arena.allocate(ValueLayout.JAVA_LONG);
        one.set(ValueLayout.JAVA_LONG, 0, 1L);
        drained = arena.allocate(ValueLayout.JAVA_LONG);
        interruptErrors = arena.allocate(NativePollApi.CAPTURE);
        drainErrors = arena.allocate(NativePollApi.CAPTURE);
        watches = new Watch[descriptors.length];
        for (int index = 0; index < descriptors.length; index++) watches[index] = new Watch(index, invalid[index]);
        for (int index = 0; index < descriptors.length; index++) {
            polls.set(ValueLayout.JAVA_INT, index * 8L, descriptors[index]);
            polls.set(ValueLayout.JAVA_SHORT, index * 8L + 4, events[index]);
        }
        polls.set(ValueLayout.JAVA_INT, descriptors.length * 8L, wake);
        polls.set(ValueLayout.JAVA_SHORT, descriptors.length * 8L + 4, (short) 1);
    }

    /** Registry callbacks only flag closure and perform nonblocking eventfd IO.
     * Each callback has its own errno slot, independent of interrupt/reset. */
    public final class Watch {
        private final int index;
        private final AtomicBoolean closed;
        private final MemorySegment errors;
        Watch(int index, boolean invalid) {
            this.index = index;
            closed = new AtomicBoolean(invalid);
            errors = arena.allocate(NativePollApi.CAPTURE);
        }
        public int getIndex() { return index; }
        public AtomicBoolean getClosed() { return closed; }
        public void descriptorClosed() {
            closed.set(true);
            NativePollApi.signal(wake, one, errors);
        }
    }

    public Watch[] getWatches() { return watches; }

    /** Takes every supplied duplicate, including on failed wake setup. */
    public static NativeEventWait acquire(int[] descriptors, short[] events, boolean[] invalid) {
        var arena = Arena.ofShared();
        int wake = -1;
        try {
            wake = NativePollApi.eventfd();
            return new NativeEventWait(descriptors, events, invalid, wake, arena);
        } catch (Throwable failure) {
            for (int fd : descriptors) if (fd >= 0) {
                try { NativePollApi.close(fd); }
                catch (Throwable closing) { failure.addSuppressed(closing); }
            }
            if (wake >= 0) {
                try { NativePollApi.close(wake); }
                catch (Throwable closing) { failure.addSuppressed(closing); }
            }
            arena.close();
            throw propagate(failure);
        }
    }

    @Override public void interrupt(Thread thread) {
        interrupted.set(true);
        NativePollApi.signal(wake, one, interruptErrors);
    }

    @Override public void resetInterrupted() {
        NativePollApi.drain(wake, drained, drainErrors);
        interrupted.set(false);
    }

    public short[] await(Node node, int timeout) { return await(node, timeout, null); }

    public short[] await(Node node, int timeout, Runnable beforeBlock) {
        long started = System.nanoTime();
        TruffleSafepoint.InterruptibleFunction<thc.runtime.Unit, short[]> action = ignored -> {
            while (true) {
                if (interrupted.get()) throw new InterruptedException();
                NativePollApi.poll(polls, 0, (long) descriptors.length + 1);
                if (interrupted.get()) throw new InterruptedException();
                short[] immediate = results();
                if (ready(immediate) || timeout == 0) return immediate;
                // Observe again after a Truffle wake without claiming guest delivery.
                if (beforeBlock != null) beforeBlock.run();
                NativePollApi.poll(polls, remaining(started, timeout), (long) descriptors.length + 1);
                if (interrupted.get()) throw new InterruptedException();
                short[] result = results();
                if (ready(result) || timeout >= 0 && remaining(started, timeout) == 0) return result;
                if (polls.get(ValueLayout.JAVA_SHORT, descriptors.length * 8L + 6) != 0)
                    throw new InterruptedException();
            }
        };
        return TruffleSafepoint.getCurrent().setBlockedFunction(node, this, action, thc.runtime.Unit.INSTANCE, null, null);
    }

    private static int remaining(long started, int timeout) {
        if (timeout < 0) return -1;
        return Math.clamp((long) timeout - Math.max(0, System.nanoTime() - started) / 1_000_000, 0, Integer.MAX_VALUE);
    }

    private short[] results() {
        short[] result = new short[descriptors.length];
        for (int index = 0; index < result.length; index++)
            result[index] = watches[index].closed.get() ? (short) 32 : polls.get(ValueLayout.JAVA_SHORT, index * 8L + 6);
        return result;
    }

    private static boolean ready(short[] values) {
        for (short value : values) if (value != 0) return true;
        return false;
    }

    /** Unregister every Watch and restore Truffle blocked state before close. */
    @Override public void close() {
        if (released) return;
        released = true;
        Throwable failure = null;
        try {
            for (int fd : descriptors) if (fd >= 0) {
                try { NativePollApi.close(fd); }
                catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            }
            if (wake >= 0) {
                try { NativePollApi.close(wake); }
                catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            }
        } finally { arena.close(); }
        if (failure != null) throw propagate(failure);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
}
