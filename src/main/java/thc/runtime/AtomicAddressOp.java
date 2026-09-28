// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.List;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Address atomics return the pre-operation value, including failed CAS. */
public enum AtomicAddressOp {
    READ("atomicReadWordAddr#"), WRITE("atomicWriteWordAddr#"), EXCHANGE("atomicExchangeWordAddr#"),
    EXCHANGE_ADDR("atomicExchangeAddrAddr#", 8, true, false), CAS("atomicCasWordAddr#", 8, false, true),
    CAS8("atomicCasWord8Addr#", 1, false, true), CAS16("atomicCasWord16Addr#", 2, false, true),
    CAS32("atomicCasWord32Addr#", 4, false, true), CAS64("atomicCasWord64Addr#", 8, false, true),
    CAS_ADDR("atomicCasAddrAddr#", 8, true, true), ADD("fetchAddWordAddr#"), SUB("fetchSubWordAddr#"),
    AND("fetchAndWordAddr#"), NAND("fetchNandWordAddr#"), OR("fetchOrWordAddr#"), XOR("fetchXorWordAddr#");
    private final String primitive;
    private final int width;
    private final boolean pointer, cas;
    private static final boolean LITTLE = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
    private static final VarHandle INT_HANDLE = ValueLayout.JAVA_INT.varHandle(), LONG_HANDLE = ValueLayout.JAVA_LONG.varHandle();
    AtomicAddressOp(String primitive) { this(primitive, 8, false, false); }
    AtomicAddressOp(String primitive, int width, boolean pointer, boolean cas) { this.primitive = primitive; this.width = width; this.pointer = pointer; this.cas = cas; }
    public String getPrimitive() { return primitive; }
    public int getWidth() { return width; }
    public boolean getPointer() { return pointer; }
    public boolean getCas() { return cas; }
    public int getArity() { return this == READ ? 2 : cas ? 4 : 3; }
    private static boolean scalar(CoreRepresentation proof, CoreKind kind) { return !proof.isAggregate() && !proof.isVector() && proof.getKind() == kind; }
    private boolean payload(CoreRepresentation proof) { return !proof.isTypedTransport() && (pointer ? proof.getKind() == CoreKind.ADDRESS : width < 8 ? proof.isInt() : proof.isLong()); }
    public void validate(List<CoreRepresentation> arguments, List<?> flags, CoreRepresentation result) {
        if (arguments.size() != getArity() || !flags.equals(java.util.Collections.nCopies(getArity(), false)) ||
            !scalar(arguments.getFirst(), CoreKind.ADDRESS) || !scalar(arguments.getLast(), CoreKind.VOID))
            throw fault("Atomic Addr# argument carrier mismatch: " + primitive);
        for (int i = 1; i < arguments.size() - 1; i++) if (!payload(arguments.get(i)))
            throw fault("Atomic Addr# argument carrier mismatch: " + primitive);
        if (this == WRITE) {
            if (!scalar(result, CoreKind.VOID)) throw fault("Atomic Addr# write requires State#");
        } else if (!result.isTuple() || result.isSum() || result.isVector() || result.getComponents() == null || result.getComponents().size() != 2 ||
            !scalar(result.getComponents().get(0), CoreKind.VOID) || !payload(result.getComponents().get(1)))
            throw fault("Atomic Addr# requires a State#/value tuple: " + primitive);
    }
    public long narrow(long value) { return width == 8 ? value : value & ((1L << (width * 8)) - 1); }
    private long update(long old, long operand, long replacement) {
        if (cas) return old == narrow(operand) ? narrow(replacement) : old;
        if (this == ADD) return old + operand;
        if (this == SUB) return old - operand;
        if (this == AND) return old & operand;
        if (this == NAND) return ~(old & operand);
        if (this == OR) return old | operand;
        if (this == XOR) return old ^ operand;
        return this == READ ? old : operand;
    }
    public int numericInt(ManagedAddress address, int operand, int replacement) {
        if (!cas || width < 1 || width > 4) throw fault("Expected narrow atomic CAS");
        int mask = width == 4 ? -1 : (1 << (width * 8)) - 1;
        int expected = operand & mask, desired = replacement & mask;
        return address.hasNativeStorage() ? address.nativeAtomicInt(this, expected, desired) : address.atomicInt(this, expected, desired);
    }
    int nativeInt(ManagedAddress address, MemorySegment segment, int expected, int desired) {
        address.requireByteRegion(width, true);
        if (segment.address() % width != 0) throw fault("Misaligned native atomic Addr#");
        return width < 4 ? (int) NativeNarrowAtomic.cas(segment, width, expected, desired) : (int) INT_HANDLE.compareAndExchange(segment, 0L, expected, desired);
    }
    int managedInt(MemorySegment bytes, int start, int expected, int desired) {
        int old = 0;
        for (int i = 0; i < width; i++) old |= (bytes.get(ValueLayout.JAVA_BYTE, (long) start + i) & 255) << (8 * (LITTLE ? i : width - i - 1));
        if (old == expected) for (int i = 0; i < width; i++) bytes.set(ValueLayout.JAVA_BYTE, (long) start + i, (byte) (desired >>> (8 * (LITTLE ? i : width - i - 1))));
        return old;
    }
    public long numeric(ManagedAddress address) { return numeric(address, 0, 0); }
    public long numeric(ManagedAddress address, long operand) { return numeric(address, operand, 0); }
    public long numeric(ManagedAddress address, long operand, long replacement) {
        if (pointer) throw fault("Pointer atomic requires address operands");
        return address.hasNativeStorage() ? address.nativeAtomicLong(this, operand, replacement) : address.atomicLong(this, operand, replacement);
    }
    long nativeLong(ManagedAddress address, MemorySegment segment, long operand, long replacement) {
        address.requireByteRegion(width, this != READ);
        if (segment.address() % width != 0) throw fault("Misaligned native atomic Addr#");
        if (width <= 2) return NativeNarrowAtomic.cas(segment, width, operand, replacement);
        if (width == 4) return ((int) INT_HANDLE.compareAndExchange(segment, 0L, (int) operand, (int) replacement)) & 0xffff_ffffL;
        if (cas) return (long) LONG_HANDLE.compareAndExchange(segment, 0L, operand, replacement);
        if (this == READ) return (long) LONG_HANDLE.getVolatile(segment, 0L);
        if (this == WRITE) { LONG_HANDLE.setVolatile(segment, 0L, operand); return 0; }
        if (this == EXCHANGE) return (long) LONG_HANDLE.getAndSet(segment, 0L, operand);
        long old = (long) LONG_HANDLE.getVolatile(segment, 0L);
        while (true) {
            long witnessed = (long) LONG_HANDLE.compareAndExchange(segment, 0L, old, update(old, operand, replacement));
            if (witnessed == old) return old;
            old = witnessed;
        }
    }
    long managedLong(MemorySegment bytes, int start, long operand, long replacement) {
        long old = 0;
        for (int i = 0; i < width; i++) old |= (bytes.get(ValueLayout.JAVA_BYTE, (long) start + i) & 255L) << (8 * (LITTLE ? i : width - i - 1));
        if (this != READ) {
            long value = update(old, operand, replacement);
            for (int i = 0; i < width; i++) bytes.set(ValueLayout.JAVA_BYTE, (long) start + i, (byte) (value >>> (8 * (LITTLE ? i : width - i - 1))));
        }
        return old;
    }
    public ManagedAddress address(ManagedAddress location, ManagedAddress operand, ManagedAddress replacement) {
        if (!pointer) throw fault("Numeric atomic requires integral operands");
        if (location.nativeAllocation() == null) return location.atomicPointer(cas ? operand : null, cas ? java.util.Objects.requireNonNull(replacement) : operand);
        long expected = operand.toNativeBits(), desired = cas ? java.util.Objects.requireNonNull(replacement).toNativeBits() : expected;
        long old = location.nativeAtomicPointer(this, expected, desired);
        var recovered = ManagedNativeAllocations.current(null).recoverAddress$org_intelligence_thc(old);
        return recovered != null ? recovered : NativeAddresses.current(null).recover(old);
    }
    long nativePointer(ManagedAddress location, MemorySegment segment, long expected, long desired) {
        location.requireRange(0, 8, true);
        if (segment.address() % 8 != 0) throw fault("Misaligned native atomic pointer Addr#");
        return cas ? (long) LONG_HANDLE.compareAndExchange(segment, 0L, expected, desired) : (long) LONG_HANDLE.getAndSet(segment, 0L, desired);
    }
    public static AtomicAddressOp named(String name) { for (var operation : values()) if (operation.primitive.equals(name)) return operation; return null; }
}
