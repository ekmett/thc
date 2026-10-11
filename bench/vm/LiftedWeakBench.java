// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

import jam.vm.Lifted;
import jam.vm.Weak;
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;

/** Measurement companion to WeakBridgeSmoke's language-owned handoff fixture. */
public final class LiftedWeakBench {
    private static final int BYTES = 4096;

    private static final class Node implements Lifted {
        final Node answer;
        final byte[] capture = new byte[BYTES];
        Node(Node answer) { this.answer = answer; }
        @Override public Lifted resolve() { return answer; }
        @Override public Lifted project(int index) { return null; }
    }

    private static final class Probe extends PhantomReference<byte[]> {
        final boolean backing;
        boolean observed;
        Probe(byte[] bytes, boolean backing, Stats stats) {
            super(bytes, stats.queue);
            this.backing = backing;
        }
    }

    private static final class Stats {
        final ReferenceQueue<byte[]> queue = new ReferenceQueue<>();
        final ArrayList<Probe> probes = new ArrayList<>();
        int registrations, callbacks, finalized, captures, backing;
        int peakCaptures, peakBacking;
        void track(byte[] bytes, boolean isBacking) {
            probes.add(new Probe(bytes, isBacking, this));
            if (isBacking) peakBacking = Math.max(peakBacking, ++backing);
            else peakCaptures = Math.max(peakCaptures, ++captures);
        }
        void poll() {
            // Avoid measuring ReferenceHandler scheduling as retention latency.
            for (Probe p : probes) if (!p.observed && p.refersTo(null)) {
                p.observed = true;
                if (p.backing) --backing;
                else --captures;
            }
        }
    }

    private record Action(Node key, byte[] backing, Stats stats) implements Runnable {
        @Override public void run() {
            // Keep the same backing value through each language-owned handoff.
            if (backing[0] != 42 || key.capture.length != BYTES)
                throw new AssertionError("lost finalizer captures");
            ++stats.callbacks;
            Node next = (Node) key.resolve();
            if (next == null) ++stats.finalized;
            else register(next, backing, stats);
        }
    }

    private static void register(Node key, byte[] backing, Stats stats) {
        Weak.create(key, backing, new Action(key, backing, stats));
        ++stats.registrations;
    }

    // Return only observations: no strong reference to any key or backing value.
    private static Stats install(int depth, int batch) {
        Stats stats = new Stats();
        for (int i = 0; i < batch; ++i) {
            Node key = null;
            for (int j = 0; j <= depth; ++j) {
                key = new Node(key);
                stats.track(key.capture, false);
            }
            byte[] backing = new byte[BYTES];
            backing[0] = 42;
            stats.track(backing, true);
            register(key, backing, stats);
        }
        return stats;
    }

    private static long collections() {
        long count = 0;
        for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long value = gc.getCollectionCount();
            if (value >= 0) count += value;
        }
        return count;
    }

    private static void measure(int depth, int batch, int delay, int sample, boolean report) {
        // Clean up the previous measurement before starting the clock.
        System.gc();
        Weak.pump();
        long countBefore = collections();
        long start = System.nanoTime();
        Stats stats = install(depth, batch);
        long registered = System.nanoTime();
        int rounds = 0, finalRound = -1, retainedCaptures = -1, retainedBacking = -1;
        long finalizedAt = 0, finalGcCount = 0;
        // A diagnostic safety bound, not a promised collection-count contract.
        while (rounds < 100 && (stats.finalized < batch || stats.captures != 0 || stats.backing != 0)) {
            System.gc();
            ++rounds;
            if (rounds % (delay + 1) == 0) Weak.pump();
            stats.poll();
            if (stats.finalized == batch && finalRound < 0) {
                finalRound = rounds;
                finalizedAt = System.nanoTime();
                finalGcCount = collections() - countBefore;
                retainedCaptures = stats.captures;
                retainedBacking = stats.backing;
            }
        }
        long end = System.nanoTime();
        if (stats.finalized != batch || stats.captures != 0 || stats.backing != 0)
            throw new AssertionError("unfinished finalizers or captures after " + rounds + " collections");
        if (stats.registrations != batch * (depth + 1) || stats.callbacks != stats.registrations)
            throw new AssertionError("lost or repeated handoff");
        if (report) System.out.printf(java.util.Locale.ROOT,
            "%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.3f,%.3f,%.3f%n",
            depth, batch, delay, sample, stats.registrations, stats.registrations - batch,
            stats.peakBacking * BYTES, stats.peakCaptures * BYTES,
            retainedBacking * BYTES, retainedCaptures * BYTES,
            finalRound, rounds, finalGcCount,
            (registered - start) / 1e6, (finalizedAt - registered) / 1e6, (end - registered) / 1e6);
    }

    public static void main(String[] args) {
        Weak.checkAvailable();
        int batch = args.length > 0 ? Integer.parseInt(args[0]) : 64;
        int samples = args.length > 1 ? Integer.parseInt(args[1]) : 7;
        System.err.println(System.getProperty("java.runtime.version") + " " + System.getProperty("java.vm.name"));
        System.err.println(ManagementFactory.getRuntimeMXBean().getInputArguments());
        System.out.println("handoffs,batch,pump_delay,sample,registrations,extra_registrations,peak_backing_bytes,peak_capture_bytes,backing_bytes_at_finalizer,capture_bytes_at_finalizer,explicit_gc_to_finalizer,explicit_gc_to_reclaim,mxbean_gc_to_finalizer,registration_ms,finalizer_ms,reclaim_ms");
        for (int warm = 0; warm < 3; ++warm)
            for (int depth : new int[]{0, 1, 4}) measure(depth, batch, 0, warm, false);
        // Interleave cases to avoid assigning a whole late/warm phase to one case.
        for (int sample = 0; sample < samples; ++sample)
            for (int delay : new int[]{0, 2})
                for (int depth : new int[]{0, 1, 4}) measure(depth, batch, delay, sample, true);
    }
}
