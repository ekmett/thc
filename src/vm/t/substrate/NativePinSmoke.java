// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

import java.util.List;
import org.graalvm.nativeimage.PinnedObject;
import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.nativeimage.c.function.CLibrary;
import org.graalvm.word.PointerBase;

/** A real native thread keeps writing while Java allocates and collects. */
@CContext(NativePinSmoke.Directives.class)
@CLibrary(value = "jam_pin_test", requireStatic = true)
public final class NativePinSmoke {
    private static volatile Object sink;
    public static final class Directives implements CContext.Directives {
        @Override public List<String> getHeaderFiles() { return List.of("<pin_writer.h>"); }
        @Override public List<String> getOptions() { return List.of("-I" + System.getProperty("jam.pin.include")); }
        @Override public List<String> getLibraryPaths() { return List.of(System.getProperty("jam.pin.library")); }
    }
    @CFunction("jam_pin_start") private static native PointerBase start(PointerBase payload);
    @CFunction("jam_pin_stop") private static native long stop(PointerBase writer);

    public static void main(String[] args) {
        for (int repetition = 0; repetition < 3; repetition++) {
            long[] payload = new long[8];
            try (PinnedObject pin = PinnedObject.create(payload)) {
                long address = pin.addressOfArrayElement(0).rawValue();
                PointerBase writer = start(pin.addressOfArrayElement(0));
                if (writer.isNull()) throw new AssertionError("native writer failed to start");
                long count;
                try {
                    for (int round = 0; round < 16; round++) {
                        for (int i = 0; i < 4000; i++) sink = new byte[1024 + (i & 127)];
                        System.gc();
                        if (pin.addressOfArrayElement(0).rawValue() != address) throw new AssertionError("pinned address moved");
                    }
                } finally {
                    count = stop(writer);
                }
                if (count <= 0 || payload[0] != count) throw new AssertionError("native writes lost during collection");
            }
            System.gc();
        }
        System.out.println("Jam Native Image concurrent pin passed");
    }
}
