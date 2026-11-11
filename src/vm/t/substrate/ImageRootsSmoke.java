// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import org.graalvm.nativeimage.PinnedObject;

/** Sparse writable image roots, including a newly populated reference-handler queue. */
public final class ImageRootsSmoke {
    static final class ImageRoots {
        static final Object[] slots = new Object[65536];
        static {
            for (int i = 0; i < slots.length; i += 2) slots[i] = ImageRootsSmoke.class;
        }
    }

    private static void install(int value, ReferenceQueue<byte[]> queue) {
        ImageRoots.slots[1] = new int[]{value};
        ImageRoots.slots[3] = new WeakReference<>(new byte[1024], queue);
    }

    public static void main(String[] args) throws Exception {
        for (int round = 0; round < 32; round++) {
            ReferenceQueue<byte[]> queue = new ReferenceQueue<>();
            install(round, queue);
            // The array and weak-reference wrapper have no strong root outside image storage.
            try (PinnedObject pin = PinnedObject.create(new byte[64])) {
                SubstrateSmoke.minorCollection();
                if (((int[]) ImageRoots.slots[1])[0] != round) throw new AssertionError("image root lost during promotion");
                WeakReference<?> weak = (WeakReference<?>) ImageRoots.slots[3];
                if (weak.get() != null || queue.remove(10000) != weak) throw new AssertionError("pending reference lost during promotion");
                if (pin.addressOfArrayElement(0).isNull()) throw new AssertionError("null pin");
            }
        }
        System.gc();
        if (((int[]) ImageRoots.slots[1])[0] != 31) throw new AssertionError("image root lost during major collection");
        for (int i = 4; i < ImageRoots.slots.length; i++) {
            if (ImageRoots.slots[i] != ((i & 1) == 0 ? ImageRootsSmoke.class : null)) throw new AssertionError("permanent image slots changed");
        }
        System.out.println("Jam Native Image sparse image roots passed");
    }
}
