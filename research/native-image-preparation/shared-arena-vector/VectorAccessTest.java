/*
 * SPDX-FileCopyrightText: 2026 Edward Kmett
 * SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
 */

import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.foreign.*;
import java.lang.reflect.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import jdk.incubator.vector.*;
import jdk.internal.foreign.AbstractMemorySegmentImpl;
import jdk.internal.vm.vector.VectorSupport;

/** Executes the emitted substitutions on the JVM, not a native image. */
public final class VectorAccessTest {
    private static final String TARGET = "com.oracle.svm.core.foreign.Target_jdk_internal_misc_ScopedMemoryAccess";
    private static final VectorSpecies<Integer> SPECIES = IntVector.SPECIES_128;
    private static final VectorSupport.VectorSpecies<Integer> INTERNAL_SPECIES =
            (VectorSupport.VectorSpecies<Integer>) SPECIES;
    private static final IntVector VALUE = IntVector.fromArray(SPECIES, new int[]{19, 23, 29, 31}, 0);
    private static final VectorMask<Integer> MASK = SPECIES.indexInRange(0, 2);
    private static final Map<String, Method> METHODS = new HashMap<>();
    private static CountDownLatch entered;
    private static CountDownLatch resume;
    private static int checks;
    private enum Op { LOAD, MASKED_LOAD, STORE, MASKED_STORE }
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

    private static IntVector load(AbstractMemorySegmentImpl segment, long offset,
                    VectorSupport.VectorSpecies<Integer> species) {
        // An independent scalar fallback oracle; never used by the provider overlay.
        int[] lanes = new int[SPECIES.length()];
        for (int i = 0; i < lanes.length; i++) lanes[i] = segment.get(ValueLayout.JAVA_INT, offset + 4L * i);
        return IntVector.fromArray(SPECIES, lanes, 0);
    }

    private static IntVector maskedLoad(AbstractMemorySegmentImpl segment, long offset,
                    VectorSupport.VectorSpecies<Integer> species, VectorSupport.VectorMask<Integer> mask) {
        int[] lanes = new int[SPECIES.length()];
        for (int i = 0; i < lanes.length; i++) if (((VectorMask<Integer>) mask).laneIsSet(i))
            lanes[i] = segment.get(ValueLayout.JAVA_INT, offset + 4L * i);
        return IntVector.fromArray(SPECIES, lanes, 0);
    }

    private static void store(AbstractMemorySegmentImpl segment, long offset, IntVector value) {
        for (int i = 0; i < SPECIES.length(); i++) segment.set(ValueLayout.JAVA_INT, offset + 4L * i, value.lane(i));
    }

    private static void maskedStore(AbstractMemorySegmentImpl segment, long offset, IntVector value,
                    VectorSupport.VectorMask<Integer> mask) {
        for (int i = 0; i < SPECIES.length(); i++) if (((VectorMask<Integer>) mask).laneIsSet(i))
            segment.set(ValueLayout.JAVA_INT, offset + 4L * i, value.lane(i));
    }

    private static void block() {
        entered.countDown();
        try {
            if (!resume.await(10, TimeUnit.SECONDS)) throw new AssertionError("Timed out awaiting release");
        } catch (InterruptedException failure) { throw new AssertionError(failure); }
    }

    private static Object access(Op op, MemorySegment segment, long offset, int callback) {
        VectorSupport.LoadOperation<AbstractMemorySegmentImpl, IntVector, VectorSupport.VectorSpecies<Integer>> load =
                callback == 1 ? (s, o, v) -> { throw new Failure(); }
                : callback == 2 ? (s, o, v) -> { block(); return load(s, o, v); } : VectorAccessTest::load;
        VectorSupport.LoadVectorMaskedOperation<AbstractMemorySegmentImpl, IntVector, VectorSupport.VectorSpecies<Integer>, VectorSupport.VectorMask<Integer>> maskedLoad =
                callback == 1 ? (s, o, v, m) -> { throw new Failure(); }
                : callback == 2 ? (s, o, v, m) -> { block(); return maskedLoad(s, o, v, m); } : VectorAccessTest::maskedLoad;
        VectorSupport.StoreVectorOperation<AbstractMemorySegmentImpl, IntVector> store =
                callback == 1 ? (s, o, v) -> { throw new Failure(); }
                : callback == 2 ? (s, o, v) -> { block(); store(s, o, v); } : VectorAccessTest::store;
        VectorSupport.StoreVectorMaskedOperation<AbstractMemorySegmentImpl, IntVector, VectorSupport.VectorMask<Integer>> maskedStore =
                callback == 1 ? (s, o, v, m) -> { throw new Failure(); }
                : callback == 2 ? (s, o, v, m) -> { block(); maskedStore(s, o, v, m); } : VectorAccessTest::maskedStore;
        String name = switch (op) {
            case LOAD -> "loadFromMemorySegment";
            case MASKED_LOAD -> "loadFromMemorySegmentMasked";
            case STORE -> "storeIntoMemorySegment";
            case MASKED_STORE -> "storeIntoMemorySegmentMasked";
        };
        Object[] arguments = switch (op) {
            case LOAD -> new Object[]{SPECIES.vectorType(), int.class, SPECIES.length(), segment, offset, INTERNAL_SPECIES, load};
            case MASKED_LOAD -> new Object[]{SPECIES.vectorType(), SPECIES.maskType(), int.class, SPECIES.length(), segment, offset,
                    MASK, INTERNAL_SPECIES, 0, maskedLoad};
            case STORE -> new Object[]{SPECIES.vectorType(), int.class, SPECIES.length(), VALUE, segment, offset, store};
            case MASKED_STORE -> new Object[]{SPECIES.vectorType(), SPECIES.maskType(), int.class, SPECIES.length(), VALUE, MASK,
                    segment, offset, maskedStore};
        };
        try { return METHODS.get(name).invoke(null, arguments); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException cause) throw cause;
            if (failure.getCause() instanceof Error cause) throw cause;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void values(MemorySegment segment, boolean addressable) {
        long address = segment.address();
        require(segment.isNative() == addressable, "original storage kind");
        if (addressable) require(address != 0, "addressable native allocation/mapping");
        access(Op.STORE, segment, 0, 0);
        require(((IntVector) access(Op.LOAD, segment, 0, 0)).equals(VALUE), "unmasked direct segment values");
        segment.fill((byte) 0);
        access(Op.MASKED_STORE, segment, 0, 0);
        require(((IntVector) access(Op.MASKED_LOAD, segment, 0, 0)).equals(
                IntVector.fromArray(SPECIES, new int[]{19, 23, 0, 0}, 0)), "masked lanes");
        require(segment.address() == address, "no memory relocation or promotion");
        for (Op op : Op.values()) expect(IndexOutOfBoundsException.class, () -> access(op, segment, segment.byteSize(), 0));
    }

    private static void lifecycle() throws Exception {
        for (Op op : Op.values()) {
            Arena arena = Arena.ofShared();
            MemorySegment segment = arena.allocate(16, 4);
            expect(Failure.class, () -> access(op, segment, 0, 1));
            require(arena.scope().isAlive(), "exception does not close scope");
            arena.close(); // Must not retain an acquire after exceptional fallback.
            expect(IllegalStateException.class, () -> access(op, segment, 0, 0));
            expect(IllegalStateException.class, arena::close);

            Arena racing = Arena.ofShared();
            MemorySegment racingSegment = racing.allocate(16, 4);
            entered = new CountDownLatch(1);
            resume = new CountDownLatch(1);
            FutureTask<Object> task = new FutureTask<>(() -> access(op, racingSegment, 0, 2));
            Thread worker = Thread.ofPlatform().start(task);
            try {
                require(entered.await(10, TimeUnit.SECONDS), "callback entered with lifetime pin");
                expect(IllegalStateException.class, racing::close);
                require(racing.scope().isAlive(), "failed concurrent close leaves scope alive");
                System.gc(); // Exercise a safepoint while the fallback is suspended.
            } finally { resume.countDown(); }
            task.get(10, TimeUnit.SECONDS);
            worker.join();
            racing.close();
            require(!racing.scope().isAlive(), "successful close after finally release");

            try (Arena confined = Arena.ofConfined()) {
                MemorySegment confinedSegment = confined.allocate(16, 4);
                FutureTask<Void> wrongThread = new FutureTask<>(() -> {
                    expect(WrongThreadException.class, () -> access(op, confinedSegment, 0, 0)); return null;
                });
                Thread.ofPlatform().start(wrongThread);
                wrongThread.get(10, TimeUnit.SECONDS);
                access(op, confinedSegment, 0, 0);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Class<?> target = Class.forName(TARGET);
        for (Method method : target.getDeclaredMethods()) if (Modifier.isPublic(method.getModifiers()))
            METHODS.put(method.getName(), method);
        require(METHODS.keySet().containsAll(List.of("loadFromMemorySegment", "loadFromMemorySegmentMasked",
                "storeIntoMemorySegment", "storeIntoMemorySegmentMasked")), "all four emitted vector wrappers");
        byte[] bytes;
        try (var input = target.getResourceAsStream("Target_jdk_internal_misc_ScopedMemoryAccess.class")) { bytes = input.readAllBytes(); }
        int vectorCalls = 0;
        for (var method : ClassFile.of().parse(bytes).methods()) if (METHODS.containsKey(method.methodName().stringValue()) &&
                method.methodName().stringValue().contains("MemorySegment")) {
            var calls = method.code().orElseThrow().elementStream().filter(InvokeInstruction.class::isInstance)
                    .map(InvokeInstruction.class::cast).toList();
            require(calls.stream().anyMatch(c -> c.name().equalsString("acquire0")), "emitted lifetime acquisition");
            require(calls.stream().filter(c -> c.name().equalsString("release0")).count() == 2, "normal and exceptional release");
            require(calls.stream().anyMatch(c -> c.name().equalsString("reachabilityFence")), "emitted reachability fence");
            vectorCalls += (int) calls.stream().filter(c -> c.owner().asInternalName().equals("jdk/internal/vm/vector/VectorSupport")).count();
        }
        require(vectorCalls == 4, "four preserved direct VectorSupport intrinsic calls");
        try (Arena shared = Arena.ofShared()) {
            values(shared.allocate(16, 4), true);
            MemorySegment tail = shared.allocate(8, 4);
            access(Op.MASKED_STORE, tail, 0, 0);
            require(((IntVector) access(Op.MASKED_LOAD, tail, 0, 0)).lane(1) == 23, "masked partial tail");
        }
        values(MemorySegment.ofArray(new int[4]), false);
        Path file = Files.createTempFile("thc-vector-mapping-", ".bin");
        try (Arena shared = Arena.ofShared(); FileChannel channel = FileChannel.open(file,
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            values(channel.map(FileChannel.MapMode.READ_WRITE, 0, 16, shared), true);
        } finally { Files.delete(file); }
        lifecycle();
        System.out.println("PASS " + checks + " emitted-wrapper JVM contract checks; not Native Image qualification");
    }
}
