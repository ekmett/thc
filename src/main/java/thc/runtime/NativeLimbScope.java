// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;

/** Synchronous provider copies close on the host without re-entering LLVM. */
public final class NativeLimbScope implements AutoCloseable {
    private final Arena arena = Arena.ofConfined();
    public Pointer allocate(long bytes) {
        if (bytes < 0 || bytes > Integer.MAX_VALUE || bytes % Long.BYTES != 0)
            throw new IllegalArgumentException("Invalid native limb byte count");
        return new Pointer(arena.allocate(Math.max(bytes, Long.BYTES), Long.BYTES), bytes, null);
    }
    public Pointer snapshot(byte[] bytes, int offset, int count) {
        Objects.checkFromIndexSize(offset, count, bytes.length);
        var pointer = allocate(count);
        pointer.copyFrom(bytes, offset, count);
        return pointer;
    }
    public Pointer borrow(ManagedAddress address, long count) {
        address.requireByteRegion$org_intelligence_thc(count, false);
        return new Pointer(address.cbitsSegment$org_intelligence_thc().asSlice(address.cbitsOffset$org_intelligence_thc(), count), count, address);
    }
    @Override public void close() { arena.close(); }

    /** Sulong transport tied to its arena lifetime; never a guest Addr#. */
    @ExportLibrary(InteropLibrary.class)
    public static final class Pointer implements TruffleObject {
        private final MemorySegment segment;
        private final long capacity;
        private final ManagedAddress retainedOwner;
        Pointer(MemorySegment segment, long capacity, ManagedAddress retainedOwner) {
            this.segment = segment; this.capacity = capacity; this.retainedOwner = retainedOwner;
        }
        public boolean aliases(ManagedAddress address) { return retainedOwner != null && retainedOwner.sameLocation(address); }
        public void copyTo(MemorySegment destination, long offset, long count) {
            Objects.checkFromIndexSize(0, count, capacity);
            MemorySegment.copy(segment, 0, destination, offset, count);
        }
        public void copyTo(byte[] destination, int offset, int count) {
            Objects.checkFromIndexSize(offset, count, destination.length);
            Objects.checkFromIndexSize(0L, count, capacity);
            MemorySegment.copy(segment, 0, MemorySegment.ofArray(destination), offset, count);
        }
        public void copyFrom(byte[] source, int offset, int count) {
            Objects.checkFromIndexSize(offset, count, source.length);
            Objects.checkFromIndexSize(0L, count, capacity);
            MemorySegment.copy(MemorySegment.ofArray(source), offset, segment, 0, count);
        }
        public long readWord(long index) {
            Objects.checkIndex(index, capacity / Long.BYTES);
            return segment.get(ValueLayout.JAVA_LONG, index * Long.BYTES);
        }
        @ExportMessage public boolean isPointer() {
            return segment.scope().isAlive() && segment.isAccessibleBy(Thread.currentThread());
        }
        @ExportMessage public long asPointer() throws UnsupportedMessageException {
            if (!isPointer()) throw UnsupportedMessageException.create();
            return segment.address();
        }
    }
}
