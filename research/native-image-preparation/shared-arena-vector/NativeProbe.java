/*
 * SPDX-FileCopyrightText: 2026 Edward Kmett
 * SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
 */

import java.lang.foreign.*;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.concurrent.*;
import jdk.incubator.vector.*;
import jdk.internal.foreign.AbstractMemorySegmentImpl;
import jdk.internal.misc.ScopedMemoryAccess;
import jdk.internal.vm.vector.VectorSupport;

/** Small real Native Image vector/shared-lifetime probe; not a guest cache test. */
public final class NativeProbe {
    // Only metadata is hosted-initialized; vector operations execute natively.
    private static final class Shape {
        private static final VectorSpecies<Integer> SPECIES = IntVector.SPECIES_128;
    }
    private static final VectorSpecies<Integer> SPECIES = Shape.SPECIES;
    private static final ByteOrder ORDER = ByteOrder.nativeOrder();
    private static final IntVector VALUE = IntVector.fromArray(SPECIES, new int[]{19, 23, 29, 31}, 0);
    private static final VectorMask<Integer> MASK = SPECIES.indexInRange(0, 2);
    private static final VectorMask<Integer> ZERO_MASK = SPECIES.maskAll(false);
    private static int checks;
    private static CountDownLatch entered;
    private static CountDownLatch resume;
    private static final class Failure extends RuntimeException {}

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }

    private static void expect(Class<? extends Throwable> kind, Runnable action) {
        try { action.run(); } catch (Throwable failure) {
            require(kind.isInstance(failure), "Expected " + kind + ", got " + failure);
            return;
        }
        throw new AssertionError("Expected " + kind);
    }

    private static int fixedAccess(MemorySegment segment) {
        // Keep the IR witness small; all bounds, mask and exception contracts
        // remain exercised by values() and the separate callback tests.
        segment.set(ValueLayout.JAVA_INT, 12, 41);
        IntVector value = IntVector.fromMemorySegment(Shape.SPECIES, segment, 0, ByteOrder.nativeOrder());
        value.intoMemorySegment(segment, 0, ByteOrder.nativeOrder());
        VectorMask<Integer> mask = VectorMask.fromLong(Shape.SPECIES, 3);
        IntVector masked = IntVector.fromMemorySegment(Shape.SPECIES, segment, 0, ByteOrder.nativeOrder(), mask);
        masked.intoMemorySegment(segment, 0, ByteOrder.nativeOrder(), mask);
        return segment.get(ValueLayout.JAVA_INT, 12);
    }

    private static void values(MemorySegment segment) {
        long address = segment.address();
        require(segment.isNative() && address != 0, "native addressable memory");
        segment.set(ValueLayout.JAVA_INT, 12, 41); // Original scalar @Scoped path before pin.
        require(segment.get(ValueLayout.JAVA_INT, 12) == 41, "scalar before vector");
        VALUE.intoMemorySegment(segment, 0, ORDER);
        require(IntVector.fromMemorySegment(SPECIES, segment, 0, ORDER).equals(VALUE), "unmasked values");
        segment.fill((byte) 0);
        VALUE.intoMemorySegment(segment, 0, ORDER, MASK);
        require(IntVector.fromMemorySegment(SPECIES, segment, 0, ORDER, MASK).equals(
                IntVector.fromArray(SPECIES, new int[]{19, 23, 0, 0}, 0)), "masked values");
        require(segment.address() == address, "original address retained");
        require(segment.get(ValueLayout.JAVA_INT, 4) == 23, "scalar after vector");
        VALUE.intoMemorySegment(segment, 0, ORDER, ZERO_MASK);
        require(IntVector.fromMemorySegment(SPECIES, segment, 0, ORDER, ZERO_MASK).equals(IntVector.zero(SPECIES)),
                "zero mask returns zero without changing scalar memory");
        require(segment.get(ValueLayout.JAVA_INT, 4) == 23, "zero mask store does not access inactive lanes");
        expect(IndexOutOfBoundsException.class, () -> VALUE.intoMemorySegment(segment, segment.byteSize(), ORDER));
        expect(IndexOutOfBoundsException.class, () -> IntVector.fromMemorySegment(SPECIES, segment, segment.byteSize(), ORDER));
        expect(IndexOutOfBoundsException.class, () -> VALUE.intoMemorySegment(segment, segment.byteSize(), ORDER, MASK));
        expect(IndexOutOfBoundsException.class, () -> IntVector.fromMemorySegment(SPECIES, segment, segment.byteSize(), ORDER, MASK));
        require(fixedAccess(segment) == 41, "fixed-shape scalar/vector/scalar witness");
    }

    private static void callback() {
        if (entered != null) {
            entered.countDown();
            try {
                if (!resume.await(10, TimeUnit.SECONDS)) throw new AssertionError("Fallback release timed out");
            } catch (InterruptedException failure) { throw new AssertionError(failure); }
        }
        throw new Failure();
    }

    @SuppressWarnings("unchecked")
    private static void fallback(int op, MemorySegment segment) {
        AbstractMemorySegmentImpl internal = (AbstractMemorySegmentImpl) segment;
        VectorSupport.VectorSpecies<Integer> species = (VectorSupport.VectorSpecies<Integer>) SPECIES;
        VectorSupport.VectorMask<Integer> mask = (VectorSupport.VectorMask<Integer>) MASK;
        // The abstract class cannot select a concrete vector intrinsic, so only
        // these calls take the ordinary Java fallback. Concrete API calls above
        // continue to use the unchanged Vector API intrinsic provider.
        switch (op) {
            case 0 -> ScopedMemoryAccess.loadFromMemorySegment(IntVector.class, int.class, 4,
                    internal, 0, species, (s, o, v) -> { callback(); return VALUE; });
            case 1 -> ScopedMemoryAccess.loadFromMemorySegmentMasked(IntVector.class,
                    (Class<VectorSupport.VectorMask<Integer>>) (Class<?>) SPECIES.maskType(), int.class, 4,
                    internal, 0, mask, species, 0, (s, o, v, m) -> { callback(); return VALUE; });
            case 2 -> ScopedMemoryAccess.storeIntoMemorySegment(IntVector.class, int.class, 4,
                    VALUE, internal, 0, (s, o, v) -> callback());
            case 3 -> ScopedMemoryAccess.storeIntoMemorySegmentMasked(IntVector.class,
                    (Class<VectorSupport.VectorMask<Integer>>) (Class<?>) SPECIES.maskType(), int.class, 4,
                    VALUE, mask, internal, 0, (s, o, v, m) -> callback());
            default -> throw new AssertionError(op);
        }
    }

    private static void racesAndExceptions() throws Exception {
        for (int op = 0; op < 4; op++) {
            int operation = op;
            Arena arena = Arena.ofShared();
            MemorySegment segment = arena.allocate(16, 4);
            expect(Failure.class, () -> fallback(operation, segment));
            arena.close();
            expect(IllegalStateException.class, () -> fallback(operation, segment));
            Arena racing = Arena.ofShared();
            MemorySegment racingSegment = racing.allocate(16, 4);
            entered = new CountDownLatch(1);
            resume = new CountDownLatch(1);
            FutureTask<Void> task = new FutureTask<>(() -> {
                expect(Failure.class, () -> fallback(operation, racingSegment)); return null;
            });
            Thread.ofPlatform().start(task);
            try {
                require(entered.await(10, TimeUnit.SECONDS), "native fallback entered while pinned");
                expect(IllegalStateException.class, racing::close);
                require(racing.scope().isAlive(), "concurrent close leaves pinned native segment alive");
                System.gc();
            } finally { resume.countDown(); }
            task.get(10, TimeUnit.SECONDS);
            entered = null;
            resume = null;
            racing.close();
            require(!racing.scope().isAlive(), "native finally release permits deterministic close");
        }
    }

    public static void main(String[] args) throws Exception {
        Arena shared = Arena.ofShared();
        MemorySegment segment = shared.allocate(16, 4);
        values(segment);
        MemorySegment tail = shared.allocate(8, 4);
        VALUE.intoMemorySegment(tail, 0, ORDER, MASK);
        require(IntVector.fromMemorySegment(SPECIES, tail, 0, ORDER, MASK).lane(1) == 23, "partial masked tail");
        FutureTask<Void> crossing = new FutureTask<>(() -> { values(segment); return null; });
        Thread.ofPlatform().start(crossing);
        crossing.get(10, TimeUnit.SECONDS);
        shared.close();
        require(!shared.scope().isAlive(), "deterministic close");
        expect(IllegalStateException.class, () -> VALUE.intoMemorySegment(segment, 0, ORDER));
        expect(IllegalStateException.class, () -> IntVector.fromMemorySegment(SPECIES, segment, 0, ORDER));
        expect(IllegalStateException.class, () -> VALUE.intoMemorySegment(segment, 0, ORDER, MASK));
        expect(IllegalStateException.class, () -> IntVector.fromMemorySegment(SPECIES, segment, 0, ORDER, MASK));
        expect(IllegalStateException.class, () -> VALUE.intoMemorySegment(segment, 0, ORDER, ZERO_MASK));
        expect(IllegalStateException.class, () -> IntVector.fromMemorySegment(SPECIES, segment, 0, ORDER, ZERO_MASK));
        expect(IllegalStateException.class, shared::close);
        Path file = Files.createTempFile("thc-native-vector-mapping-", ".bin");
        try (Arena mappedArena = Arena.ofShared(); FileChannel channel = FileChannel.open(file,
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            values(channel.map(FileChannel.MapMode.READ_WRITE, 0, 16, mappedArena));
        } finally { Files.delete(file); }
        try (Arena confined = Arena.ofConfined()) {
            MemorySegment local = confined.allocate(16, 4);
            FutureTask<Void> wrongThread = new FutureTask<>(() -> {
                expect(WrongThreadException.class, () -> VALUE.intoMemorySegment(local, 0, ORDER));
                expect(WrongThreadException.class, () -> IntVector.fromMemorySegment(SPECIES, local, 0, ORDER));
                return null;
            });
            Thread.ofPlatform().start(wrongThread);
            wrongThread.get(10, TimeUnit.SECONDS);
            values(local);
        }
        racesAndExceptions();
        System.out.println("PASS " + checks + " real Native Image vector/shared-arena checks; not persisted guest AOT qualification");
    }
}
