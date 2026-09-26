// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;

public final class HeapPinProbe {
    private static native long criticalAlias(byte[] first, byte[] second, int offset, int value);
    private static native long addressSnapshot(byte[] bytes);
    private static volatile Object pressure;

    public static void main(String[] args) throws Throwable {
        Path library = Path.of(args[0]).toAbsolutePath();
        System.load(library.toString());
        System.out.println("runtime=" + System.getProperty("java.runtime.version"));
        System.out.println("collectors=" + ManagementFactory.getGarbageCollectorMXBeans().stream()
            .map(bean -> bean.getName()).toList());
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment symbol = SymbolLookup.libraryLookup(library, arena).find("heap_alias").orElseThrow();
            FunctionDescriptor abi = FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
            MethodHandle critical = Linker.nativeLinker().downcallHandle(symbol, abi, Linker.Option.critical(true));
            MethodHandle ordinary = Linker.nativeLinker().downcallHandle(symbol, abi);
            byte[] bytes = new byte[128];
            MemorySegment whole = MemorySegment.ofArray(bytes);
            boolean rejectedHeap = false;
            try {
                int ignored = (int)ordinary.invokeExact(whole, whole.asSlice(7), 7, 1);
            } catch (IllegalArgumentException expected) {
                rejectedHeap = true;
            }
            if (!rejectedHeap) throw new AssertionError("ordinary native call unexpectedly accepted heap segment");
            long lastAddress = addressSnapshot(bytes);
            int moves = 0, ffmChecks = 0, jniChecks = 0;
            for (int round = 0; round < 16; round++) {
                for (int offset : new int[]{0, 1, 7, 63, 127}) {
                    int value = (round * 13 + offset) & 255;
                    int observed = (int)critical.invokeExact(whole, whole.asSlice(offset), offset, value);
                    if (observed != value || Byte.toUnsignedInt(bytes[offset]) != value)
                        throw new AssertionError("FFM alias/mutation mismatch");
                    ffmChecks++;
                    int next = (value + 1) & 255;
                    long result = criticalAlias(bytes, bytes, offset, next);
                    if (result != (next | 256) || Byte.toUnsignedInt(bytes[offset]) != next)
                        throw new AssertionError("JNI copied or lost alias/mutation: " + result);
                    jniChecks++;
                }
                pressure = new byte[4 * 1024 * 1024];
                System.gc();
                long currentAddress = addressSnapshot(bytes);
                if (lastAddress != currentAddress) moves++;
                lastAddress = currentAddress;
            }
            System.out.println("ordinary_heap_rejected=true");
            System.out.println("ffm_heap_alias_mutation_checks=" + ffmChecks);
            System.out.println("jni_no_copy_alias_mutation_checks=" + jniChecks);
            System.out.println("observed_array_relocations_between_calls=" + moves);
            System.out.println("PASS (bounded leaf calls only; no general Sulong/native integration claim)");
        }
    }
}
