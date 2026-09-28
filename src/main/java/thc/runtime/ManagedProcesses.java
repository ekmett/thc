// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import java.io.Closeable;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import thc.Language;
import thc.NativeIO;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Context-owned process lifecycle. Spawn/reap publication are single effects;
 * waits block only on duplicated pidfds. Disposal kills/reaps only owned children.
 * The original Haskell ProcessHandle owns exit-code caching, so repeated raw C
 * calls retain their original ECHILD behavior. */
public final class ManagedProcesses implements Closeable {
    private final NativeDirectoryOwner directory;
    private final Language.State context = Language.currentState(null);
    private final IdentityHashMap<Handle, Child> children = new IdentityHashMap<>();
    private final LinkedHashMap<Integer, Handle> processIds = new LinkedHashMap<>();
    private boolean disposed;

    /** Identity capability, never a guest-supplied host PID. */
    public static final class Handle { Handle() {} }

    public sealed interface Stream {
        enum Endpoint implements Stream { PIPE, CLOSED }
        final class Descriptor implements Stream {
            private final NativeFileResource resource;
            public Descriptor(NativeFileResource resource) { this.resource = resource; }
            public NativeFileResource getResource() { return resource; }
        }
    }

    public static final class Launch {
        private final Handle handle;
        private final Pipe input;
        private final Pipe output;
        private final Pipe error;
        Launch(Handle handle, Pipe input, Pipe output, Pipe error) {
            this.handle = handle; this.input = input; this.output = output; this.error = error;
        }
        public Handle getHandle() { return handle; }
        public Pipe getInput() { return input; }
        public Pipe getOutput() { return output; }
        public Pipe getError() { return error; }
    }

    /** Until transferred, the context retains cleanup ownership. A transfer is
     * one-way; the receiver must install or close the returned lease. */
    public static final class Pipe implements Closeable {
        private final Language.State context = Language.currentState(null);
        private NativeFileLease owned = new NativeFileLease();
        Pipe() {}
        MemorySegment slot() { return Objects.requireNonNull(owned).openSlot(); }
        public synchronized NativeFileLease takeLease() {
            if (Language.currentState(null) != context) throw fault("Process pipe belongs to another context");
            var lease = owned;
            if (lease == null) throw propagate(new ClosedChannelException());
            owned = null;
            return lease;
        }
        public synchronized int duplicate() {
            var lease = owned;
            if (lease == null) throw propagate(new ClosedChannelException());
            return lease.duplicateForWait();
        }
        @Override public synchronized void close() {
            var lease = owned;
            if (lease == null) return;
            owned = null;
            lease.close();
        }
    }

    private static final class Child {
        final NativeFileLease pidfd;
        final List<Pipe> pipes;
        int pid = -1;
        int descriptor = -1;
        final LinkedHashSet<NativeEventWait.Watch> waiters = new LinkedHashSet<>();
        boolean reaped;
        boolean closed;
        Child(NativeFileLease pidfd, List<Pipe> pipes) { this.pidfd = pidfd; this.pipes = pipes; }
    }

    public ManagedProcesses(NativeDirectoryOwner directory) {
        this.directory = directory;
        if (!NativeIO.supportedPosixHost()) throw new UnsupportedOperationException("Native processes require Linux x86_64");
        if (!context.getEnv().isNativeAccessAllowed() || !context.getEnv().isCreateProcessAllowed())
            throw new SecurityException("Native processes require explicit native access and process creation permission");
        context.getEnv().registerOnDispose(this);
    }

    private void current() {
        if (Language.currentState(null) != context) throw fault("Process registry belongs to another context");
        if (disposed) throw propagate(new ClosedChannelException());
    }

    private synchronized Child child(Handle handle) {
        current();
        var child = children.get(handle);
        if (child == null) throw fault("Unknown or cross-context process handle");
        return child;
    }

    public Launch spawn(List<byte[]> arguments, List<byte[]> environment) {
        return spawn(arguments, environment, null, Stream.Endpoint.CLOSED, Stream.Endpoint.CLOSED,
            Stream.Endpoint.CLOSED, 0, null, null, null);
    }

    /** Raw non-NUL bytes and an explicit environment snapshot. Provider resources
     * authenticate inherited streams. CWD is anchored to the context directory;
     * searchPath is the parent's PATH, independently of the child environment. */
    @TruffleBoundary
    public synchronized Launch spawn(List<byte[]> arguments, List<byte[]> environment, byte[] cwd,
        Stream input, Stream output, Stream error, int flags, Long childGroup, Long childUser, byte[] searchPath) {
        current();
        if (arguments.isEmpty() || arguments.getFirst().length == 0) throw new IllegalArgumentException("Empty process command");
        for (byte[] bytes : arguments) requireString(bytes);
        for (byte[] bytes : environment) requireString(bytes);
        if (cwd != null) requireString(cwd);
        if (searchPath != null) requireString(searchPath);
        if (childGroup != null || childUser != null || (flags & ~(0x1 | 0x2 | 0x8 | 0x20)) != 0)
            throw new UnsupportedOperationException("Process credentials or console flags are unavailable");
        // Complete allocation and validation before the native spawn effect.
        Stream[] streams = {input, output, error};
        Pipe[] pipes = new Pipe[3];
        for (int index = 0; index < streams.length; index++)
            if (streams[index] == Stream.Endpoint.PIPE) pipes[index] = new Pipe();
        var pidfd = new NativeFileLease();
        var result = new Launch(new Handle(), pipes[0], pipes[1], pipes[2]);
        var ownedPipes = new ArrayList<Pipe>();
        for (var pipe : pipes) if (pipe != null) ownedPipes.add(pipe);
        var child = new Child(pidfd, ownedPipes);
        int[] duplicates = {-1, -1, -1};
        boolean published = false;
        Throwable failure = null;
        try {
            var pidSlot = pidfd.openSlot();
            MemorySegment[] pipeSlots = new MemorySegment[pipes.length];
            for (int index = 0; index < pipes.length; index++) if (pipes[index] != null) pipeSlots[index] = pipes[index].slot();
            int[] descriptors = new int[streams.length];
            for (int index = 0; index < streams.length; index++) {
                descriptors[index] = switch (streams[index]) {
                    case Stream.Endpoint endpoint -> endpoint == Stream.Endpoint.PIPE ? -1 : -2;
                    case Stream.Descriptor descriptor -> {
                        var resource = descriptor.getResource();
                        resource.requireLive();
                        int duplicate = resource.duplicateDescriptor();
                        duplicates[index] = duplicate;
                        yield duplicate;
                    }
                };
            }
            try (var anchor = directory.borrow(); var arena = Arena.ofConfined()) {
                var slots = arena.allocate(24, 4);
                int errno = NativeProcessApi.spawn(arguments, environment, anchor.getDescriptor(),
                    cwd, descriptors, flags, searchPath, slots);
                if (errno != 0) {
                    int ordinal = slots.get(ValueLayout.JAVA_INT, 20);
                    var stages = ProcessFailureStage.values();
                    if (ordinal < 0 || ordinal >= stages.length) throw fault("Invalid native process failure stage");
                    var stage = stages[ordinal];
                    if (stage == ProcessFailureStage.NONE) throw fault("Missing native process failure stage");
                    throw new ProcessSpawnException(errno, stage);
                }
                child.pid = slots.get(ValueLayout.JAVA_INT, 0);
                child.descriptor = slots.get(ValueLayout.JAVA_INT, 4);
                pidSlot.set(ValueLayout.JAVA_INT, 0, child.descriptor);
                for (int index = 0; index < pipeSlots.length; index++)
                    if (pipeSlots[index] != null) pipeSlots[index].set(ValueLayout.JAVA_INT, 0,
                        slots.get(ValueLayout.JAVA_INT, (index + 2) * 4L));
            }
            children.put(result.handle, child);
            published = true;
            return result;
        } catch (Throwable thrown) { failure = thrown; throw propagate(thrown); }
        finally {
            Throwable cleanupFailure = null;
            for (int fd : duplicates) if (fd >= 0) {
                try { NativePollApi.close(fd); }
                catch (Throwable closing) { cleanupFailure = combine(cleanupFailure, closing); }
            }
            if (!published || cleanupFailure != null) {
                children.remove(result.handle);
                try { dispose(child); }
                catch (Throwable closing) { cleanupFailure = combine(cleanupFailure, closing); }
            }
            if (cleanupFailure != null) {
                if (failure == null) throw propagate(cleanupFailure);
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    private static void requireString(byte[] bytes) {
        for (byte value : bytes) if (value == 0) throw new IllegalArgumentException("Failed requirement.");
    }

    @TruffleBoundary public int processId(Handle handle) {
        var child = child(handle);
        synchronized (child) {
            if (child.closed) throw propagate(new ClosedChannelException());
            return child.pid;
        }
    }

    /** Retired numeric identities remain reserved; a collision retires only the new child. */
    @TruffleBoundary public synchronized int publishProcessId(Handle handle) {
        int pid = processId(handle);
        var previous = processIds.get(pid);
        if (previous != null && previous != handle) {
            var failure = new NativeFileException("process PID identity collision", 11);
            try { abortUnpublished(handle); }
            catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            throw propagate(failure);
        }
        processIds.put(pid, handle);
        return pid;
    }

    @TruffleBoundary public synchronized Handle fromProcessId(int pid) {
        current();
        var handle = processIds.get(pid);
        if (handle == null) throw fault("Unknown or cross-context process ID");
        return handle;
    }

    /** Transferred pipes belong to their receiver; numeric identities remain reserved. */
    @TruffleBoundary public synchronized void abortUnpublished(Handle handle) { dispose(child(handle)); }

    private ProcessResult query(Child child) {
        if (child.closed) throw propagate(new ClosedChannelException());
        if (child.reaped) return new ProcessResult(-1, 0, 10);
        var result = NativeProcessApi.poll(child.descriptor);
        if (result.status() == 1 || result.errno() == 10) {
            child.reaped = true;
            child.descriptor = -1;
            // Linux consumes close even on EINTR; retain the completed reap result.
            child.pidfd.closeDirectory();
        }
        return result;
    }

    @TruffleBoundary public ProcessResult poll(Handle handle) {
        var child = child(handle);
        synchronized (child) {
            var result = query(child);
            return result.errno() == 10 ? new ProcessResult(1, 0, 10) : result;
        }
    }

    @TruffleBoundary public ProcessResult terminate(Handle handle) {
        var child = child(handle);
        synchronized (child) {
            if (child.closed) throw propagate(new ClosedChannelException());
            return child.reaped ? new ProcessResult(0, null, 3) : NativeProcessApi.terminate(child.descriptor);
        }
    }

    public ProcessResult waitFor(Handle handle) { return waitFor(handle, null, null); }
    public ProcessResult waitFor(Handle handle, Node node) { return waitFor(handle, node, null); }

    /** beforeBlock is the caller's existing interruptible-operation observation. */
    @TruffleBoundary public ProcessResult waitFor(Handle handle, Node node, Runnable beforeBlock) {
        var child = child(handle);
        NativeEventWait wait;
        synchronized (child) {
            var immediate = query(child);
            if (immediate.status() != 0) return waitResult(immediate);
            wait = NativeEventWait.acquire(new int[]{child.pidfd.duplicateForWait()}, new short[]{1}, new boolean[]{false});
            child.waiters.add(wait.getWatches()[0]);
        }
        try {
            while (true) {
                if (beforeBlock != null) beforeBlock.run();
                int readiness = wait.await(node, -1, beforeBlock)[0];
                synchronized (child) {
                    if (child.closed || (readiness & 32) != 0) throw propagate(new ClosedChannelException());
                    var result = query(child);
                    if (result.status() != 0) return waitResult(result);
                }
            }
        } finally {
            synchronized (child) { child.waiters.remove(wait.getWatches()[0]); }
            wait.close();
        }
    }

    private static ProcessResult waitResult(ProcessResult result) {
        return result.status() == 1 ? new ProcessResult(0, result.exitCode(), result.errno()) : new ProcessResult(-1, null, result.errno());
    }

    private void dispose(Child child) {
        synchronized (child) {
            if (child.closed) return;
            child.closed = true;
            Throwable failure = null;
            for (var waiter : child.waiters) {
                try { waiter.descriptorClosed(); }
                catch (Throwable error) { failure = combine(failure, error); }
            }
            if (!child.reaped && child.pidfd.isOpen()) {
                try { NativeProcessApi.dispose(child.descriptor); }
                catch (Throwable error) { failure = combine(failure, error); }
            }
            for (var pipe : child.pipes) {
                try { pipe.close(); }
                catch (Throwable error) { failure = combine(failure, error); }
            }
            try { child.pidfd.close(); }
            catch (Throwable error) { failure = combine(failure, error); }
            if (failure != null) throw propagate(failure);
        }
    }

    @TruffleBoundary @Override public synchronized void close() {
        if (disposed) return;
        disposed = true;
        Throwable failure = null;
        for (var child : children.values()) {
            try { dispose(child); }
            catch (Throwable error) { failure = combine(failure, error); }
        }
        children.clear();
        processIds.clear();
        if (failure != null) throw propagate(failure);
    }

    private static Throwable combine(Throwable failure, Throwable error) {
        if (failure == null) return error;
        failure.addSuppressed(error);
        return failure;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
}
