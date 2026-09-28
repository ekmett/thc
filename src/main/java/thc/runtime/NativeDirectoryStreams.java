// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.io.Closeable;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.IdentityHashMap;
import thc.Language;

/** Original Unix streams over libc DIR objects. Guest addresses are allocation
 * identities, never supplied native pointers. Callback-free operations and
 * retirement share one context lock. */
public final class NativeDirectoryStreams implements Closeable {
    private final NativeDirectoryOwner directory;
    private final Language.State context = Language.currentState();
    private final IdentityHashMap<ManagedAllocation, Stream> streams = new IdentityHashMap<>();
    private final IdentityHashMap<ManagedAllocation, Entry> entries = new IdentityHashMap<>();
    private boolean disposed;
    public NativeDirectoryStreams(NativeDirectoryOwner directory) { this.directory = directory; }

    private static final class Stream implements Closeable {
        final Arena arena = Arena.ofShared();
        final MemorySegment slot = arena.allocate(ValueLayout.ADDRESS);
        final ManagedAddress address = token();
        Entry entry;
        private boolean closed;
        int closeResult() {
            if (closed) return 0;
            closed = true;
            try { return NativeDirectoryApi.closeStream(slot); } finally { arena.close(); }
        }
        @Override public void close() { closeResult(); }
    }
    private record Entry(Stream stream, ManagedAddress address, ManagedAllocation name) {}

    private void current() {
        if (Language.currentState() != context) throw RuntimeFault.fault("Directory stream belongs to another context");
        if (disposed) throw RuntimeFault.fault("Directory stream registry is closed");
    }
    private ManagedAllocation key(ManagedAddress address) {
        if (address.cbitsOffset$org_intelligence_thc() != 0) throw RuntimeFault.fault("Directory handle requires its exact opaque base");
        var owner = address.cbitsOwner$org_intelligence_thc();
        if (owner == null) throw RuntimeFault.fault("Directory handle has no managed identity");
        return owner;
    }
    private Stream stream(ManagedAddress address) {
        current();
        var result = streams.get(key(address));
        if (result == null) throw RuntimeFault.fault("Unknown, closed or cross-context directory stream");
        return result;
    }
    @FunctionalInterface private interface ForeignAction<T> { T run() throws Throwable; }
    private <T> T foreign(ForeignAction<T> action) {
        var previous = context.getThreads().enterForeign(ForeignSafety.UNSAFE);
        try { return action.run(); }
        catch (Throwable failure) { throw propagate(failure); }
        finally { context.getThreads().leaveForeign(previous); }
    }
    private void retireEntry(Stream stream) {
        if (stream.entry != null) {
            entries.remove(key(stream.entry.address));
            // Derived byte views retain this allocation; shrinking expires all
            // ranges at the point where libc may reuse its dirent buffer.
            stream.entry.name.shrink(0);
        }
        stream.entry = null;
    }
    @FunctionalInterface private interface Acquire { void run(Stream stream) throws Throwable; }
    private ManagedAddress acquire(Acquire action) {
        current();
        var stream = new Stream();
        try {
            foreign(() -> { action.run(stream); return null; });
            streams.put(key(stream.address), stream);
            return stream.address;
        } catch (Throwable failure) {
            try { stream.close(); } catch (Throwable closing) { failure.addSuppressed(closing); }
            throw propagate(failure);
        }
    }
    @TruffleBoundary public synchronized ManagedAddress open(byte[] path) {
        return acquire(stream -> {
            try (var anchor = directory.borrow()) { NativeDirectoryApi.openStream(anchor.getDescriptor(), path, stream.slot); }
        });
    }
    /** Failed fdopendir closes only this private duplicate, never the original. */
    @TruffleBoundary public synchronized ManagedAddress fromDescriptor(NativeFileResource resource) {
        try (var lease = new NativeFileLease()) {
            var slot = lease.openSlot();
            slot.set(ValueLayout.JAVA_INT, 0, resource.duplicateDescriptor());
            return acquire(stream -> NativeDirectoryApi.fdOpenStream(slot, stream.slot));
        }
    }
    @TruffleBoundary public synchronized long read(ManagedAddress address, ManagedAddress output) {
        var stream = stream(address);
        var allocation = output.cbitsOwner$org_intelligence_thc();
        return output.withNativeBorrow$org_intelligence_thc(() -> {
            if (allocation == null) return readChecked(stream, output, null);
            synchronized (allocation) { return readChecked(stream, output, allocation); }
        });
    }
    private long readChecked(Stream stream, ManagedAddress output, ManagedAllocation allocation) {
        if (allocation != null && stream.entry != null && allocation == stream.entry.name)
            throw RuntimeFault.fault("Directory output pointer cell overlaps the active directory-entry image");
        output.requireRange(0, 8, true);
        if (output.cbitsOffset$org_intelligence_thc() % 8 != 0)
            throw RuntimeFault.fault("Directory entry output requires an aligned pointer cell");
        if (allocation != null) {
            if (allocation.getAddressWidth() != 8) throw RuntimeFault.fault("Directory entry output requires an LP64 pointer cell");
            allocation.requireAddressCell(output.cbitsOffset$org_intelligence_thc());
        } else output.requireByteRegion$org_intelligence_thc(8, true);
        var token = token();
        // Reserve any native projection before advancing the directory.
        if (output.nativeAllocation$org_intelligence_thc() != null) token.toNativeBits();
        retireEntry(stream);
        int[] status = {-1};
        foreign(() -> {
            NativeDirectoryApi.readStream(stream.slot.get(ValueLayout.ADDRESS, 0), (int) context.getStdio().errno(), (result, error, name) -> {
                status[0] = result;
                ManagedAddress entryAddress;
                if (name == null) entryAddress = ManagedAddress.Companion.nullAddress();
                else {
                    var bytes = ManagedAllocation.mutable(name.length, 8);
                    for (int i = 0; i < name.length; i++) bytes.writeByte(i, name[i]);
                    var entry = new Entry(stream, token, bytes);
                    entries.put(key(token), entry);
                    stream.entry = entry;
                    entryAddress = token;
                }
                output.writeAddressElementIndex(0, entryAddress);
                context.getStdio().captureForeignErrno(error);
            });
            return null;
        });
        return status[0];
    }
    @TruffleBoundary public synchronized ManagedAddress name(ManagedAddress address) {
        current();
        var entry = entries.get(key(address));
        if (entry == null) throw RuntimeFault.fault("Directory entry is expired or belongs to another context");
        return ManagedAddress.Companion.fromAllocation(entry.name);
    }
    @TruffleBoundary public synchronized void freeEntry(ManagedAddress address) {
        current();
        if (address != ManagedAddress.Companion.nullAddress() && !entries.containsKey(key(address)))
            throw RuntimeFault.fault("Directory entry is expired or belongs to another context");
        // On the selected glibc platform free_dirent is a no-op. Only the next
        // read or closedir invalidates its reusable entry image.
    }
    @TruffleBoundary public synchronized long closeStream(ManagedAddress address) {
        var stream = stream(address);
        streams.remove(key(address));
        retireEntry(stream);
        int error = foreign(stream::closeResult);
        context.getStdio().nativeError(error);
        return error == 0 ? 0 : -1;
    }
    public synchronized void abandon(ManagedAddress address) {
        var stream = streams.remove(key(address));
        if (stream == null) return;
        retireEntry(stream);
        stream.close();
    }
    @Override public synchronized void close() {
        if (disposed) return;
        disposed = true;
        Throwable failure = null;
        for (var stream : streams.values()) {
            try { retireEntry(stream); stream.close(); }
            catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        }
        streams.clear();
        entries.clear();
        if (failure != null) throw propagate(failure);
    }
    public synchronized int liveCount() { return streams.size(); }
    private static ManagedAddress token() {
        return ManagedAddress.Companion.fromAllocation(ManagedAllocation.immutable(new byte[0], 8, true));
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E {
        throw (E) failure;
    }
}
