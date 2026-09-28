// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrameAccessThreadTest {
    @Test void explicitScratchCarriersStartTypedAndRetainOrdinaryWidening() {
        var kinds = List.of(FrameSlotKind.Int, FrameSlotKind.Long, FrameSlotKind.Float, FrameSlotKind.Double, FrameSlotKind.Object);
        var values = List.of(-128, 255L, -0.0f, -0.0, new Object());
        var layout = new FrameLayout(); var slots = new ArrayList<Integer>();
        for (var kind : kinds) slots.add(layout.bind("scratch", kind));
        int unknown = layout.bind("scratch"); var distinct = new HashSet<>(slots); distinct.add(unknown);
        assertEquals(slots.size() + 1, distinct.size(), "Shadowed names own fresh slots");
        var descriptor = layout.build(); assertEquals(FrameSlotKind.Illegal, descriptor.getSlotKind(unknown));
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor);
        java.util.function.IntConsumer write = index -> {
            switch (values.get(index)) {
                case Integer value -> FrameAccess.writeInt(frame, slots.get(index), value);
                case Long value -> FrameAccess.writeLong(frame, slots.get(index), value);
                case Float value -> FrameAccess.writeFloat(frame, slots.get(index), value);
                case Double value -> FrameAccess.writeDouble(frame, slots.get(index), value);
                default -> FrameAccess.writeObject(frame, slots.get(index), values.get(index));
            }
        };
        for (int index = 0; index < slots.size(); index++) {
            assertEquals(kinds.get(index), descriptor.getSlotKind(slots.get(index)));
            write.accept(index); assertEquals(values.get(index), FrameAccess.read(frame, slots.get(index)));
            FrameAccess.writeObject(frame, slots.get(index), null); write.accept(index);
            assertEquals(FrameSlotKind.Object, descriptor.getSlotKind(slots.get(index)));
            assertEquals(values.get(index), FrameAccess.read(frame, slots.get(index)));
        }
    }
    @Test void genericWritesKeepExactScalarKindsAndOtherNumbersAsReferences() {
        var customNumber = new Number() {
            @Override public byte byteValue() { throw new IllegalStateException("not a guest scalar"); }
            @Override public short shortValue() { throw new IllegalStateException("not a guest scalar"); }
            @Override public int intValue() { throw new IllegalStateException("not a guest scalar"); }
            @Override public long longValue() { throw new IllegalStateException("not a guest scalar"); }
            @Override public float floatValue() { throw new IllegalStateException("not a guest scalar"); }
            @Override public double doubleValue() { throw new IllegalStateException("not a guest scalar"); }
        };
        var values = Arrays.asList(Long.MIN_VALUE, Long.MAX_VALUE, -0.0f, -0.0, true, false,
            (byte) 1, (short) 2, 3, BigInteger.ONE.shiftLeft(80), new BigDecimal("1.125"), customNumber, null);
        for (var value : values) {
            var builder = FrameDescriptor.newBuilder(); int slot = builder.addSlot(FrameSlotKind.Illegal, "value", null);
            var descriptor = builder.build(); var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor);
            FrameAccess.write(frame, slot, value);
            var expectedKind = switch (value) {
                case Integer ignored -> FrameSlotKind.Int; case Long ignored -> FrameSlotKind.Long;
                case Float ignored -> FrameSlotKind.Float; case Double ignored -> FrameSlotKind.Double;
                case Boolean ignored -> FrameSlotKind.Boolean; case null, default -> FrameSlotKind.Object;
            };
            assertEquals(expectedKind, descriptor.getSlotKind(slot));
            if (expectedKind == FrameSlotKind.Object) assertSame(value, FrameAccess.read(frame, slot));
            else assertEquals(value, FrameAccess.read(frame, slot));
            FrameAccess.writeObject(frame, slot, new Object()); FrameAccess.write(frame, slot, value);
            assertEquals(FrameSlotKind.Object, descriptor.getSlotKind(slot)); assertEquals(value, FrameAccess.read(frame, slot));
        }
    }
    @Test void concurrentPrimitiveClaimsCannotUndoObjectWidening() throws Exception {
        var workers = Executors.newFixedThreadPool(5);
        try {
            for (int iteration = 0; iteration < 200; iteration++) {
                var builder = FrameDescriptor.newBuilder(); int slot = builder.addSlot(FrameSlotKind.Illegal, "shared", null);
                var descriptor = builder.build(); var barrier = new CyclicBarrier(5); var marker = new Object();
                var results = new ArrayList<Future<Object>>();
                for (int i = 0; i < 5; i++) {
                    int worker = i;
                    results.add(workers.submit(() -> {
                        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor);
                        barrier.await(10, TimeUnit.SECONDS);
                        switch (worker) {
                            case 0 -> FrameAccess.writeLong(frame, slot, 41L); case 1 -> FrameAccess.writeFloat(frame, slot, 3.5f);
                            case 2 -> FrameAccess.writeDouble(frame, slot, -2.25); case 3 -> FrameAccess.write(frame, slot, true);
                            default -> TypedInputsKt.writeInputReference(frame, slot, marker);
                        }
                        return FrameAccess.read(frame, slot);
                    }));
                }
                assertEquals(41L, results.get(0).get(10, TimeUnit.SECONDS)); assertEquals(3.5f, results.get(1).get(10, TimeUnit.SECONDS));
                assertEquals(-2.25, results.get(2).get(10, TimeUnit.SECONDS)); assertEquals(true, results.get(3).get(10, TimeUnit.SECONDS));
                assertSame(marker, results.get(4).get(10, TimeUnit.SECONDS)); assertEquals(FrameSlotKind.Object, descriptor.getSlotKind(slot));
                var later = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor);
                FrameAccess.writeLong(later, slot, 99L); assertEquals(FrameSlotKind.Object, descriptor.getSlotKind(slot));
                assertEquals(99L, FrameAccess.read(later, slot));
                FrameAccess.writeFloat(later, slot, 4.5f); FrameAccess.writeDouble(later, slot, 8.25); FrameAccess.write(later, slot, false);
                assertEquals(FrameSlotKind.Object, descriptor.getSlotKind(slot)); assertEquals(false, FrameAccess.read(later, slot));
            }
        } finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS), "Frame workers did not terminate"); }
    }
}
