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
import java.util.Objects;

/** Host-owned native limb copies for one synchronous provider call. Closing the
 * arena is a host operation: it does not re-enter a possibly cancelled LLVM
 * context to call free. Neither owners nor addresses become guest Addr# values.
 * Callers must check guest capacities, mutability and alias rules first. */
public final class NativeLimbScope implements AutoCloseable {
    private final Arena arena = Arena.ofConfined();

    public Pointer allocate(long bytes) {
        if (bytes < 0 || bytes > Integer.MAX_VALUE || bytes % Long.BYTES != 0)
            throw new IllegalArgumentException("Invalid native limb byte count");
        // GMP's documented empty inputs still receive an aligned non-null
        // address, but their logical copy capacity remains zero.
        return new Pointer(arena.allocate(Math.max(bytes, Long.BYTES), Long.BYTES), bytes);
    }

    public Pointer snapshot(byte[] bytes, int offset, int count) {
        Objects.checkFromIndexSize(offset, count, bytes.length);
        Pointer result = allocate(count);
        MemorySegment.copy(MemorySegment.ofArray(bytes), offset, result.segment, 0, count);
        return result;
    }

    @Override public void close() { arena.close(); }

    /** A private transport value understood by Sulong, not a cast JVM buffer.
     * No raw pointer is stored separately from its checked arena lifetime. */
    @ExportLibrary(InteropLibrary.class)
    public static final class Pointer implements TruffleObject {
        private final MemorySegment segment;
        private final long capacity;

        private Pointer(MemorySegment segment, long capacity) {
            this.segment = segment;
            this.capacity = capacity;
        }

        public void copyTo(byte[] destination, int offset, int count) {
            Objects.checkFromIndexSize(offset, count, destination.length);
            Objects.checkFromIndexSize(0L, count, capacity);
            MemorySegment.copy(segment, 0, MemorySegment.ofArray(destination), offset, count);
        }

        @ExportMessage boolean isPointer() {
            return segment.scope().isAlive() && segment.isAccessibleBy(Thread.currentThread());
        }

        @ExportMessage long asPointer() throws UnsupportedMessageException {
            if (!isPointer()) throw UnsupportedMessageException.create();
            return segment.address();
        }
    }
}
