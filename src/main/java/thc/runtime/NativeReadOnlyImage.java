// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;
import java.lang.ref.WeakReference;
import static thc.runtime.RuntimeFault.fault;

public final class NativeReadOnlyImage {
    private static final Cleaner CLEANER = Cleaner.create();
    private static final class CloseArena implements Runnable {
        private final Arena arena;
        private volatile boolean closed;
        CloseArena(Arena arena) { this.arena = arena; }
        @Override public synchronized void run() { if (!closed) { closed = true; arena.close(); } }
    }
    private final WeakReference<Object> source;
    private final long size;
    private final long base;
    private final MemorySegment segment;
    private final CloseArena cleanup;
    private final Cleaner.Cleanable cleanable;
    public NativeReadOnlyImage(Object source, byte[] bytes) {
        this.source = new WeakReference<>(source);
        size = bytes.length;
        var arena = Arena.ofShared();
        // The inclusive one-past-end address must belong to this image.
        segment = arena.allocate(size + 1L, 8);
        base = segment.address();
        cleanup = new CloseArena(arena);
        cleanable = CLEANER.register(this, cleanup);
        MemorySegment.copy(MemorySegment.ofArray(bytes), 0, segment, 0, size);
    }
    public long getBase() { return base; }
    public long getSize() { return size; }
    public boolean hasSource() { return source.get() != null && !cleanup.closed; }
    public Object source() { return source.get(); }
    public void requireLive() { if (!isAlive()) throw fault("Native address owner is closed"); }
    public void close() { cleanable.clean(); }
    public boolean isAlive() { return !cleanup.closed && segment.scope().isAlive(); }
}
