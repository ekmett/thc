// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.Node;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Native allocations remain owned until their matching deallocator or context disposal.
 * Malloc and Windows local allocations share borrow lifetimes, never deallocator identities. */
public final class ManagedNativeAllocations {
    private final TruffleLanguage.Env env;
    private final HashSet<Owner> live = new HashSet<>();
    private final HashSet<Owner> freeing = new HashSet<>();
    private boolean closed;

    public ManagedNativeAllocations(TruffleLanguage.Env env) { this.env = env; }

    private void current() {
        if (Language.currentState(null).getNativeAllocations() != this)
            throw fault("Native allocation belongs to another context");
        if (!env.isNativeAccessAllowed()) throw fault("Native allocation requires native access");
    }

    public enum Allocator { MALLOC, WINDOWS_LOCAL }

    public final class Owner {
        private final MemorySegment pointer;
        private final long size;
        private final Allocator allocator;
        private final ReentrantReadWriteLock lifetime = new ReentrantReadWriteLock(true);
        private final Arena arena = Arena.ofShared();
        private final MemorySegment segment;
        private boolean closed;

        Owner(MemorySegment pointer, long size) { this(pointer, size, Allocator.MALLOC); }
        Owner(MemorySegment pointer, long size, Allocator allocator) {
            this.pointer = pointer;
            this.size = size;
            this.allocator = allocator;
            try { segment = pointer.reinterpret(size, arena, null); }
            catch (Throwable failure) { arena.close(); throw propagate(failure); }
        }

        public long getSize() { return size; }
        public Allocator getAllocator() { return allocator; }

        @TruffleBoundary
        public Borrow borrow() {
            current();
            lifetime.readLock().lock();
            try {
                if (closed) throw fault("Native allocation is freed");
                return new Borrow();
            } catch (Throwable failure) { lifetime.readLock().unlock(); throw propagate(failure); }
        }

        public final class Borrow implements AutoCloseable {
            private boolean released;
            private final Thread thread = Thread.currentThread();
            public MemorySegment segment() {
                if (released || thread != Thread.currentThread()) throw fault("Invalid native allocation borrow");
                return segment;
            }
            @TruffleBoundary
            @Override public void close() {
                if (thread != Thread.currentThread()) throw fault("Native allocation borrow belongs to another thread");
                if (!released) { released = true; lifetime.readLock().unlock(); }
            }

            /** Preserve try-with-resources cleanup without expanding suppression into guest graphs. */
            @TruffleBoundary void closeAfter(Throwable failure) {
                if (failure == null) {
                    close();
                } else {
                    try { close(); }
                    catch (Throwable closing) { failure.addSuppressed(closing); }
                }
            }
        }

        void requireFreeable() {
            // A synchronous native callback must not upgrade its own live borrow.
            if (lifetime.getReadHoldCount() != 0) throw fault("Cannot free an allocation borrowed by this thread");
        }

        @TruffleBoundary
        void release() {
            requireFreeable();
            lifetime.writeLock().lock();
            try {
                if (closed) return;
                // Failed LocalFree retains ownership and a usable segment.
                if (allocator == Allocator.WINDOWS_LOCAL) WindowsCodePages.releaseLocal(pointer);
                else releaseNative(pointer);
                closed = true;
                arena.close();
            } finally { lifetime.writeLock().unlock(); }
        }

        @TruffleBoundary public void requireLive() { try (var ignored = borrow()) {} }
        @TruffleBoundary public <T> T access(Function<MemorySegment, T> body) {
            try (var loan = borrow()) { return body.apply(loan.segment()); }
        }
        @TruffleBoundary public int accessInt(ToIntFunction<MemorySegment> body) {
            try (var loan = borrow()) { return body.applyAsInt(loan.segment()); }
        }
        @TruffleBoundary public long accessLong(ToLongFunction<MemorySegment> body) {
            try (var loan = borrow()) { return body.applyAsLong(loan.segment()); }
        }
    }

    @TruffleBoundary
    public synchronized ManagedAddress malloc(long size) {
        current();
        if (closed) throw fault("Native allocation registry is closed");
        boolean windows = WindowsDirectoryStreams.supportedHost();
        if (!windows && !supportsLibcAbi())
            throw fault("Native malloc requires a 64-bit size_t/pointer ABI with int errno, or the paired Windows CRT");
        var threads = Language.currentState(null).getThreads();
        var previous = threads.enterForeign(ForeignSafety.UNSAFE);
        try {
            if (size < 0) throw fault("Native malloc size exceeds the signed Long segment domain");
            long errno = 0;
            Owner owner;
            try (var call = Arena.ofConfined()) {
                var errors = call.allocate(windows ? ValueLayout.JAVA_INT : Libc.CAPTURE);
                var pointer = windows ? (MemorySegment) WindowsMalloc.malloc.invokeExact(size, errors)
                    : (MemorySegment) Libc.MALLOC.invokeExact(errors, size);
                if (pointer.address() == 0) {
                    errno = errors.get(ValueLayout.JAVA_INT, windows ? 0 : Libc.ERRNO);
                    owner = null;
                } else {
                    try { owner = new Owner(pointer, size); }
                    catch (Throwable failure) { releaseNative(pointer); throw failure; }
                }
            } catch (Throwable failure) { throw nativeFailure("Native malloc invocation failed", failure); }
            if (owner == null) {
                Language.currentState(null).getStdio().nativeError(errno);
                return ManagedAddress.nullAddress();
            }
            try {
                var address = ManagedAddress.fromNativeAllocation(owner);
                live.add(owner);
                return address;
            } catch (Throwable failure) { owner.release(); throw propagate(failure); }
        } finally { threads.leaveForeign(previous); }
    }

    @TruffleBoundary
    public synchronized ManagedAddress adoptWindowsLocal(MemorySegment pointer, long size) {
        Owner owner = null;
        try {
            current();
            if (closed) throw fault("Native allocation registry is closed");
            WindowsCodePages.Abi.requireLayout();
            if (pointer.address() == 0 || size <= 0) throw fault("Invalid Windows local allocation");
            owner = new Owner(pointer, size, Allocator.WINDOWS_LOCAL);
            var address = ManagedAddress.fromNativeAllocation(owner);
            live.add(owner);
            return address;
        } catch (Throwable failure) {
            try { if (owner == null) WindowsCodePages.releaseLocal(pointer); else owner.release(); }
            catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            throw propagate(failure);
        }
    }

    public void free(ManagedAddress address) { free(address, Allocator.MALLOC); }

    @TruffleBoundary
    public void free(ManagedAddress address, Allocator allocator) {
        current();
        address = deallocationAddress(address);
        if (allocator == Allocator.MALLOC && address.returnedAddress() != null) {
            Language.currentState(null).getPackageCbits().free(address.returnedAddress());
            return;
        }
        Owner owner;
        synchronized (this) {
            owner = freeableOwner(address, allocator);
            if (owner == null) return;
            freeing.add(owner);
        }
        var threads = Language.currentState(null).getThreads();
        var previous = threads.enterForeign(ForeignSafety.UNSAFE);
        boolean retired = false;
        try {
            owner.release(); // Wait for other threads' borrows before consuming ownership.
            retired = true;
        } finally {
            synchronized (this) { if (retired) live.remove(owner); freeing.remove(owner); }
            threads.leaveForeign(previous);
        }
    }

    /** Allocate/copy/retire invalidates old aliases on success and retains the old owner on failure. */
    @TruffleBoundary
    public ManagedAddress realloc(ManagedAddress address, long size) {
        current();
        if (size < 0) throw fault("Native realloc size exceeds the signed Long segment domain");
        address = deallocationAddress(address);
        if (address.returnedAddress() != null)
            return Language.currentState(null).getPackageCbits().realloc(address.returnedAddress(), size);
        if (address == ManagedAddress.nullAddress()) return malloc(size);
        Owner owner;
        synchronized (this) {
            owner = freeableOwner(address, Allocator.MALLOC);
            freeing.add(owner);
        }
        var replacement = ManagedAddress.nullAddress();
        boolean retired = false;
        try {
            // Owned zero-size realloc consumes the allocation and returns NULL.
            if (size != 0) {
                replacement = malloc(size);
                if (replacement == ManagedAddress.nullAddress()) return replacement;
                try (var source = owner.borrow()) {
                    var sourceSegment = source.segment();
                    try (var destination = replacement.nativeAllocation().borrow()) {
                        var destinationSegment = destination.segment();
                        long copied = Math.min(owner.size, size);
                        destinationSegment.asSlice(0, copied).copyFrom(sourceSegment.asSlice(0, copied));
                    }
                }
            }
            owner.release();
            retired = true;
            return replacement;
        } catch (Throwable failure) {
            if (replacement != ManagedAddress.nullAddress()) {
                try { free(replacement); }
                catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            }
            throw propagate(failure);
        } finally {
            synchronized (this) { if (retired) live.remove(owner); freeing.remove(owner); }
        }
    }

    /** Check the same ownership contract when installing an {@code &free} callback. */
    @TruffleBoundary
    public synchronized void requireFreeTarget(ManagedAddress address) {
        current();
        address = deallocationAddress(address);
        if (address.returnedAddress() == null) freeableOwner(address, Allocator.MALLOC);
    }

    /** A direct owned base supplies a referent-independent token; wrappers stay explicit-only. */
    @TruffleBoundary
    synchronized Owner ownedFreeTarget(ManagedAddress address) {
        current();
        if (address.returnedAddress() != null) return null;
        return freeableOwner(address, Allocator.MALLOC);
    }

    /** GC retirement never waits for a borrower, or repeats an already-consumed owner. */
    @TruffleBoundary
    boolean tryFree(Owner owner) {
        current();
        synchronized (this) {
            if (closed) throw fault("Native allocation registry is closed");
            if (owner == null || !live.contains(owner)) return true;
            if (freeing.contains(owner) || !owner.lifetime.writeLock().tryLock()) return false;
            freeing.add(owner);
        }
        boolean retired = false;
        try {
            var threads = Language.currentState(null).getThreads();
            var previous = threads.enterForeign(ForeignSafety.UNSAFE);
            try {
                owner.release(); // The reentrant write lock is already held: no borrow wait.
                retired = true;
                return true;
            } finally { threads.leaveForeign(previous); }
        } finally {
            synchronized (this) { if (retired) live.remove(owner); freeing.remove(owner); }
            owner.lifetime.writeLock().unlock();
        }
    }

    /** Known aliases retain our ownership checks; external C keeps its allocator contract. */
    private synchronized ManagedAddress deallocationAddress(ManagedAddress address) {
        if (closed) throw fault("Native allocation registry is closed");
        while (address.returnedAddress() != null) {
            var returned = address.returnedAddress();
            returned.requireCurrent();
            if (returned.getBacking() == null) break;
            address = returned.getBacking();
        }
        return address;
    }

    private Owner freeableOwner(ManagedAddress address, Allocator allocator) {
        if (closed) throw fault("Native allocation registry is closed");
        if (address == ManagedAddress.nullAddress()) return null;
        var owner = address.nativeAllocation();
        if (owner == null) throw fault("Native free requires an owned malloc base");
        if (owner.allocator != allocator) throw fault("Native deallocation requires its matching allocator");
        if (!live.contains(owner) || freeing.contains(owner)) throw fault("Native free requires a live allocation from this context");
        if (!address.isNativeBase()) throw fault("Native free requires the allocation base");
        owner.requireFreeable();
        return owner;
    }

    @TruffleBoundary
    public void close() {
        ArrayList<Owner> pending;
        synchronized (this) {
            if (closed) return;
            live.forEach(Owner::requireFreeable);
            closed = true;
            pending = new ArrayList<>(live);
        }
        // A borrowed call may acquire another allocation: never hold the registry while waiting.
        Throwable failed = null;
        for (var owner : pending) {
            try { owner.release(); }
            catch (Throwable failure) {
                if (failed == null) failed = failure;
                else failed.addSuppressed(failure);
            }
        }
        synchronized (this) { live.clear(); freeing.clear(); }
        if (failed != null) throw propagate(failed);
    }

    public synchronized int liveCount() { return live.size(); }

    /** Requested bytes still owned, including allocations whose free awaits active borrows. */
    @TruffleBoundary
    public synchronized long liveBytes() {
        long bytes = 0;
        for (var owner : live) bytes = Math.addExact(bytes, owner.size);
        return bytes;
    }

    /** Recover only existing context-owned allocations; unknown bits remain non-dereferenceable. */
    @TruffleBoundary
    public synchronized ManagedAddress recoverAddress(long bits) {
        current();
        if (closed) throw fault("Native allocation registry is closed");
        for (var owner : live) if (!freeing.contains(owner)) {
            long displacement;
            try (var loan = owner.borrow()) { displacement = bits - loan.segment().address(); }
            if (Long.compareUnsigned(displacement, owner.size) <= 0)
                return ManagedAddress.fromNativeAllocation(owner).plus(displacement);
        }
        return null;
    }

    public static ManagedNativeAllocations current(Node node) {
        return Language.currentState(node).getNativeAllocations();
    }

    private static void releaseNative(MemorySegment pointer) {
        try {
            if (WindowsDirectoryStreams.supportedHost()) WindowsMalloc.free.invokeExact(pointer);
            else Libc.FREE.invokeExact(pointer);
        } catch (Throwable failure) { throw nativeFailure("Native free invocation failed", failure); }
    }

    private static RuntimeException nativeFailure(String message, Throwable failure) {
        if (failure instanceof RuntimeException exception) return exception;
        if (failure instanceof Error error) throw error;
        var fault = new RuntimeFault(message);
        fault.initCause(failure);
        return fault;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }

    /** Inspect metadata only: no libc symbol lookup or downcall before context/native permission. */
    static boolean supportsLibcAbi() {
        // FFM's Windows errno may belong to another CRT; never replace the paired allocator.
        if (System.getProperty("os.name").startsWith("Windows")) return false;
        try {
            return matchesLibcAbi(Linker.nativeLinker().canonicalLayouts(), Linker.Option.captureStateLayout());
        } catch (UnsupportedOperationException unsupported) { return false; }
    }

    static boolean matchesLibcAbi(Map<String, MemoryLayout> layouts, StructLayout capture) {
        return matchesNativeLayout(layouts.get("size_t"), ValueLayout.JAVA_LONG)
            && ValueLayout.ADDRESS.byteSize() == Long.BYTES
            && matchesNativeLayout(layouts.get("void*"), ValueLayout.ADDRESS)
            && capture.memberLayouts().stream().anyMatch(layout ->
                "errno".equals(layout.name().orElse(null)) && matchesNativeLayout(layout, ValueLayout.JAVA_INT));
    }

    private static boolean matchesNativeLayout(MemoryLayout layout, ValueLayout expected) {
        return layout instanceof ValueLayout value && value.carrier() == expected.carrier()
            && value.byteSize() == expected.byteSize() && value.byteAlignment() == expected.byteAlignment()
            && value.order() == expected.order();
    }

    // Initialize host downcalls only when the authorized native path is used.
    private static final class Libc {
        private static final Linker LINKER = Linker.nativeLinker();
        static final StructLayout CAPTURE = Linker.Option.captureStateLayout();
        static final long ERRNO = CAPTURE.byteOffset(MemoryLayout.PathElement.groupElement("errno"));
        static final MethodHandle MALLOC = LINKER.downcallHandle(LINKER.defaultLookup().find("malloc").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG), Linker.Option.captureCallState("errno"));
        static final MethodHandle FREE = LINKER.downcallHandle(LINKER.defaultLookup().find("free").orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
    }
}
