// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/** Only the current platform thread is changed: pid=0 is not a Java thread ID.
 * The kernel intersects masks with online CPUs and cpuset restrictions. */
public final class LinuxCpuAffinity implements NativeCpuAffinity {
    private final MethodHandle get, set;
    private final byte[] original;
    private final int[] cpus;
    private LinuxCpuAffinity(MethodHandle get, MethodHandle set, byte[] original, int[] cpus) {
        this.get = get; this.set = set; this.original = original; this.cpus = cpus;
    }
    @Override public int getCount() { return cpus.length; }
    @Override public CpuAffinityMode getMode() { return CpuAffinityMode.PINNED; }
    @Override public CpuCoordinate coordinate(int index) {
        return index >= 0 && index < cpus.length ? new CpuCoordinate(0, cpus[index]) : null;
    }
    public byte[] currentMask() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mask = arena.allocate(original.length, 8);
            return (int) get.invokeExact(0, mask.byteSize(), mask) == 0 ? mask.toArray(ValueLayout.JAVA_BYTE) : null;
        } catch (Throwable failure) { return unavailable(failure); }
    }
    private boolean install(byte[] mask) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(mask.length, 8);
            segment.copyFrom(MemorySegment.ofArray(mask));
            return (int) set.invokeExact(0, segment.byteSize(), segment) == 0;
        }
    }
    private AutoCloseable change(byte[] mask) {
        try {
            Thread owner = Thread.currentThread();
            if (owner.isVirtual()) return null;
            byte[] previous = currentMask();
            if (previous == null || !install(mask)) return null;
            return new AutoCloseable() {
                private boolean closed;
                @Override public void close() {
                    if (closed || Thread.currentThread() != owner) return;
                    closed = true;
                    try { install(previous); }
                    catch (Throwable failure) { unavailable(failure); }
                }
            };
        } catch (Throwable failure) { return unavailable(failure); }
    }
    @Override public AutoCloseable bindCurrent(int index) {
        if (index < 0 || index >= cpus.length) return null;
        int cpu = cpus[index];
        byte[] mask = new byte[original.length];
        mask[cpu / 8] = (byte) (1 << (cpu % 8));
        return change(mask);
    }
    @Override public AutoCloseable resetCurrent() { return change(original); }
    public static LinuxCpuAffinity discover() {
        try {
            if (!System.getProperty("os.name").startsWith("Linux") || Thread.currentThread().isVirtual()) return null;
            Linker linker = Linker.nativeLinker();
            if (ValueLayout.ADDRESS.byteSize() != 8L) return null;
            FunctionDescriptor signature = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG, ValueLayout.ADDRESS);
            MethodHandle get = linker.downcallHandle(linker.defaultLookup().find("sched_getaffinity").orElseThrow(), signature);
            MethodHandle set = linker.downcallHandle(linker.defaultLookup().find("sched_setaffinity").orElseThrow(), signature);
            try (Arena arena = Arena.ofConfined()) {
                for (long size = 128; size <= 128 * 1024L; size *= 2) {
                    MemorySegment mask = arena.allocate(size, 8);
                    if ((int) get.invokeExact(0, size, mask) == 0) {
                        byte[] bytes = mask.toArray(ValueLayout.JAVA_BYTE);
                        int count = 0;
                        for (byte value : bytes) count += Integer.bitCount(value & 255);
                        if (count == 0) return null;
                        int[] cpus = new int[count];
                        int position = 0;
                        for (int i = 0; i < bytes.length * 8; i++)
                            if ((bytes[i / 8] & (1 << (i % 8))) != 0) cpus[position++] = i;
                        return new LinuxCpuAffinity(get, set, bytes, cpus);
                    }
                }
                return null;
            }
        } catch (Throwable failure) { return unavailable(failure); }
    }
    /** MethodHandle can throw checked failures. Only the old optional-affinity
     * Exception/LinkageError classes are swallowed; VM/control Errors retain identity. */
    private static <T> T unavailable(Throwable failure) {
        if (failure instanceof Exception || failure instanceof LinkageError) return null;
        return LinuxCpuAffinity.<RuntimeException, T>rethrow(failure);
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable, T> T rethrow(Throwable failure) throws E { throw (E) failure; }
}
