// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

import static java.lang.foreign.ValueLayout.*;

/** Windows CPU Sets are advisory selection, not hard processor affinity.
 * We never change process defaults or a thread's hard group affinity. In
 * particular, a Get/SetThreadGroupAffinity roundtrip cannot restore the default
 * all-group affinity on Windows 11. Until full hard eligibility can be queried,
 * multi-group processes decline support rather than silently truncating to 64
 * CPUs. A process restricted to one group (including a nonzero group) is valid.
 * See https://learn.microsoft.com/windows/win32/procthread/cpu-sets . */
final class WindowsCpuAffinity implements NativeCpuAffinity {
    private final Api api;
    private final List<Cpu> cpus;
    private final int[] originalSelection;

    private WindowsCpuAffinity(Api api, List<Cpu> cpus, int[] originalSelection) {
        this.api = api;
        this.cpus = cpus;
        this.originalSelection = originalSelection;
    }

    @Override public int getCount() { return cpus.size(); }
    @Override public CpuAffinityMode getMode() { return CpuAffinityMode.ADVISORY; }
    @Override public CpuCoordinate coordinate(int index) {
        return index >= 0 && index < cpus.size() ? cpus.get(index).coordinate() : null;
    }

    @Override public AutoCloseable bindCurrent(int index) {
        try {
            if (Thread.currentThread().isVirtual() || index < 0 || index >= cpus.size()) return null;
            var cpu = cpus.get(index);
            // Restrictions can change after the context snapshot. Do not override a
            // newly restricted process default or select outside the current mask.
            var hard = api.hardEligibility();
            if (hard == null) return null;
            var defaults = api.ids(api.processSets, api.process());
            if (defaults == null) return null;
            var current = api.cpuSets();
            if (current == null || !eligible(cpu, hard.group, hard.mask, defaults) || !current.contains(cpu)) return null;
            return changeTo(new int[] {cpu.id});
        } catch (Throwable failure) { return unavailable(failure); }
    }

    @Override public AutoCloseable resetCurrent() {
        try { return Thread.currentThread().isVirtual() ? null : changeTo(originalSelection); }
        catch (Throwable failure) { return unavailable(failure); }
    }

    private AutoCloseable changeTo(int[] selection) throws Throwable {
        var owner = Thread.currentThread();
        var previous = api.ids(api.threadSets, api.thread());
        if (previous == null || !api.select(selection)) return null;
        return new AutoCloseable() {
            private boolean closed;
            @Override public void close() {
                // Pseudo-handles identify the CALLING native thread. Never use
                // one from a different Java thread or a migrating virtual thread.
                if (closed || Thread.currentThread() != owner) return;
                closed = true;
                try { api.select(previous); }
                catch (Throwable failure) { unavailable(failure); }
            }
        };
    }

    record Cpu(int id, int group, int index) {
        CpuCoordinate coordinate() { return new CpuCoordinate(group, index); }
    }

    private static Api platformApi;
    private static boolean apiInitialized;
    private static synchronized Api platformApi() {
        if (!apiInitialized) {
            // Cache ordinary native unavailability, including null. Other Errors
            // propagate and leave initialization retryable, as with the old lazy.
            try { platformApi = new Api(); }
            catch (Exception | LinkageError unavailable) { }
            apiInitialized = true;
        }
        return platformApi;
    }

    public static WindowsCpuAffinity discover() {
        try {
            if (!System.getProperty("os.name", "").startsWith("Windows") ||
                    ADDRESS.byteSize() != 8 || Thread.currentThread().isVirtual()) return null;
            var api = platformApi();
            if (api == null) return null;
            var hard = api.hardEligibility();
            if (hard == null) return null;
            var original = api.ids(api.threadSets, api.thread());
            if (original == null) return null;
            var defaults = api.ids(api.processSets, api.process());
            if (defaults == null) return null;
            var selected = original.length != 0 ? original : defaults;
            var available = api.cpuSets();
            if (available == null) return null;
            var cpus = new ArrayList<Cpu>();
            for (var cpu : available) if (eligible(cpu, hard.group, hard.mask, selected)) cpus.add(cpu);
            cpus.sort(Comparator.comparingInt(Cpu::group).thenComparingInt(Cpu::index));
            return cpus.isEmpty() ? null : new WindowsCpuAffinity(api, cpus, original);
        } catch (Throwable failure) { return unavailable(failure); }
    }

    static boolean eligible(Cpu cpu, int group, long mask, int[] selected) {
        if (cpu.group != group || cpu.index < 0 || cpu.index > 63 || (mask & (1L << cpu.index)) == 0) return false;
        if (selected.length == 0) return true;
        for (int id : selected) if (id == cpu.id) return true;
        return false;
    }

    /** The SDK explicitly requires advancing by Size, not a fixed stride. */
    static List<Cpu> decodeCpuSets(MemorySegment bytes) {
        var result = new ArrayList<Cpu>();
        long offset = 0;
        while (offset < bytes.byteSize()) {
            if (bytes.byteSize() - offset < 8) return null;
            long size = Integer.toUnsignedLong(bytes.get(JAVA_INT_UNALIGNED, offset));
            if (size < 8 || size > bytes.byteSize() - offset) return null;
            if (bytes.get(JAVA_INT_UNALIGNED, offset + 4) == 0) {
                if (size < 32) return null;
                int flags = Byte.toUnsignedInt(bytes.get(JAVA_BYTE, offset + 19));
                // Allocated CPU sets reserved for another process are not
                // eligible. Parked CPUs remain valid; the OS may unpark them.
                if ((flags & 2) == 0 || (flags & 4) != 0) {
                    int index = Byte.toUnsignedInt(bytes.get(JAVA_BYTE, offset + 14));
                    if (index > 63) return null;
                    result.add(new Cpu(bytes.get(JAVA_INT_UNALIGNED, offset + 8),
                        Short.toUnsignedInt(bytes.get(JAVA_SHORT_UNALIGNED, offset + 12)), index));
                }
            }
            offset += size;
        }
        var ids = new HashSet<Integer>();
        var coordinates = new HashSet<Integer>();
        for (var cpu : result) {
            if (!ids.add(cpu.id) || !coordinates.add(cpu.group * 64 + cpu.index)) return null;
        }
        return result;
    }

    /** Preserve optional native failures without swallowing VM/control-flow Errors. */
    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T unavailable(Throwable failure) throws E {
        if (failure instanceof Exception || failure instanceof LinkageError) return null;
        throw (E) failure;
    }

    private record HardEligibility(int group, long mask) {}

    private static final class Api {
        private final Linker linker = Linker.nativeLinker();
        private final SymbolLookup lookup = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());
        private MethodHandle function(String name, MemoryLayout result, MemoryLayout... args) {
            return linker.downcallHandle(lookup.find(name).orElseThrow(), FunctionDescriptor.of(result, args));
        }
        private final MethodHandle currentThread = function("GetCurrentThread", ADDRESS);
        private final MethodHandle currentProcess = function("GetCurrentProcess", ADDRESS);
        private final MethodHandle groups = function("GetProcessGroupAffinity", JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
        private final MethodHandle processMask = function("GetProcessAffinityMask", JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
        private final MethodHandle threadMask = function("GetThreadGroupAffinity", JAVA_INT, ADDRESS, ADDRESS);
        private final MethodHandle systemSets = function("GetSystemCpuSetInformation", JAVA_INT,
            ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT);
        private final MethodHandle threadSets = function("GetThreadSelectedCpuSets", JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS);
        private final MethodHandle processSets = function("GetProcessDefaultCpuSets", JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS);
        private final MethodHandle setThreadSets = function("SetThreadSelectedCpuSets", JAVA_INT, ADDRESS, ADDRESS, JAVA_INT);
        private MemorySegment thread() throws Throwable { return (MemorySegment) currentThread.invokeExact(); }
        private MemorySegment process() throws Throwable { return (MemorySegment) currentProcess.invokeExact(); }

        private HardEligibility hardEligibility() throws Throwable {
            try (var arena = Arena.ofConfined()) {
                var count = arena.allocate(JAVA_SHORT);
                count.set(JAVA_SHORT, 0, (short) 1);
                var group = arena.allocate(JAVA_SHORT);
                if ((int) groups.invokeExact(process(), count, group) == 0 || count.get(JAVA_SHORT, 0) != 1) return null;
                var processBits = arena.allocate(JAVA_LONG);
                var system = arena.allocate(JAVA_LONG);
                var threadBits = arena.allocate(16, 8);
                if ((int) processMask.invokeExact(process(), processBits, system) == 0 ||
                        (int) threadMask.invokeExact(thread(), threadBits) == 0) return null;
                int selectedGroup = Short.toUnsignedInt(group.get(JAVA_SHORT, 0));
                if (Short.toUnsignedInt(threadBits.get(JAVA_SHORT, 8)) != selectedGroup) return null;
                long mask = processBits.get(JAVA_LONG, 0) & system.get(JAVA_LONG, 0) & threadBits.get(JAVA_LONG, 0);
                return mask == 0 ? null : new HardEligibility(selectedGroup, mask);
            }
        }

        private int[] ids(MethodHandle query, MemorySegment handle) throws Throwable {
            try (var arena = Arena.ofConfined()) {
                var count = arena.allocate(JAVA_INT);
                int first = (int) query.invokeExact(handle, MemorySegment.NULL, 0, count);
                int required = count.get(JAVA_INT, 0);
                if (required == 0) return first != 0 ? new int[0] : null;
                if (required < 1 || required > 65536) return null;
                var ids = arena.allocate((long) required * 4, 4);
                if ((int) query.invokeExact(handle, ids, required, count) == 0) return null;
                int actual = count.get(JAVA_INT, 0);
                if (actual < 0 || actual > required) return null;
                var result = new int[actual];
                for (int i = 0; i < actual; i++) result[i] = ids.getAtIndex(JAVA_INT, i);
                return result;
            }
        }

        private List<Cpu> cpuSets() throws Throwable {
            try (var arena = Arena.ofConfined()) {
                var size = arena.allocate(JAVA_INT);
                int ignored = (int) systemSets.invokeExact(MemorySegment.NULL, 0, size, process(), 0);
                int required = size.get(JAVA_INT, 0);
                if (required < 1 || required > 16 * 1024 * 1024) return null;
                var bytes = arena.allocate(required, 8);
                if ((int) systemSets.invokeExact(bytes, required, size, process(), 0) == 0) return null;
                int actual = size.get(JAVA_INT, 0);
                return actual < 1 || actual > required ? null : decodeCpuSets(bytes.asSlice(0, actual));
            }
        }

        private boolean select(int[] ids) throws Throwable {
            try (var arena = Arena.ofConfined()) {
                var memory = ids.length == 0 ? MemorySegment.NULL : arena.allocateFrom(JAVA_INT, ids);
                return (int) setThreadSets.invokeExact(thread(), memory, ids.length) != 0;
            }
        }
    }
}
