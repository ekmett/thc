// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc;

import java.util.Arrays;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

/** Same input cycle in both engines. Timing includes the Polyglot host boundary. */
public final class Probe {
    private record Window(long calls, long checksum, long elapsed) {}

    private static Window window(Value fn, long base, double seconds, long minimumCalls) {
        long duration = (long) (seconds * 1_000_000_000);
        long start = System.nanoTime();
        long calls = 0, checksum = 0;
        do {
            // A complete number of 16-input cycles permits cross-engine checksums
            // even when time-based windows execute different iteration counts.
            for (int i = 0; i < 256; i++) {
                checksum += fn.execute(base + (calls & 15)).asLong();
                calls++;
            }
        } while (System.nanoTime() - start < duration || calls < minimumCalls);
        return new Window(calls, checksum, System.nanoTime() - start);
    }

    public static void main(String[] args) {
        if (args.length < 4) throw new IllegalArgumentException(
            "Usage: probe MODULES ENTRY REPETITIONS INPUTBASE, or MODULES ENTRY --steady WARM_SECONDS SAMPLE_SECONDS SAMPLES INPUTBASE");
        var modules = Arrays.asList(args[0].split(",", -1));
        String entry = args[1];
        try (Context context = Main.executionContext(false)) {
            var fn = Main.loadEntry(context, modules, entry, false);
            if (args[2].equals("--steady")) {
                if (args.length != 7) throw new IllegalArgumentException("Failed requirement.");
                double warmSeconds = Double.parseDouble(args[3]), sampleSeconds = Double.parseDouble(args[4]);
                int samples = Integer.parseInt(args[5]);
                long base = Long.parseLong(args[6]);
                if (!(warmSeconds > 0 && sampleSeconds > 0 && samples > 0)) throw new IllegalArgumentException("Failed requirement.");
                for (int i = 0; i < 200; i++) fn.execute(base + (i & 15)).asLong();
                fn.invokeMember("compile"); // Throws unless last-tier guest code is installed.
                System.err.println("PHASE WARM BEGIN");
                long minimumWarmCalls = Long.parseLong(System.getProperty("thc.minimumWarmCalls", "20000"));
                if (minimumWarmCalls <= 0) throw new IllegalArgumentException("Failed requirement.");
                var warm = window(fn, base, warmSeconds, minimumWarmCalls);
                System.err.println("PHASE WARM END calls=" + warm.calls() + " elapsedNs=" + warm.elapsed() + " checksum=" + warm.checksum());
                fn.invokeMember("compile");
                for (int sample = 0; sample < samples; sample++) {
                    System.err.println("PHASE MEASURE " + (sample + 1) + " BEGIN");
                    var result = window(fn, base, sampleSeconds, 0);
                    System.err.println("PHASE MEASURE " + (sample + 1) + " END");
                    System.out.println(entry + "\t" + (sample + 1) + "\t" + result.calls() + "\t" + base + "\t" + result.checksum() + "\t" + result.elapsed());
                }
                System.err.println("PHASE VERIFY BEGIN");
                Main.installed((java.util.Map<?, ?>) Json.parse(fn.getMember("diagnostics").asString()));
                System.err.println("PHASE VERIFY END guestLastTierInstalled=true");
            } else {
                int repetitions = Integer.parseInt(args[2]);
                long base = Long.parseLong(args[3]);
                if (repetitions <= 0) throw new IllegalArgumentException("Failed requirement.");
                long warmChecksum = 0;
                for (int i = 0; i < 200; i++) warmChecksum += fn.execute(base + (i & 15)).asLong();
                fn.invokeMember("compile");
                for (int i = 0; i < 200; i++) warmChecksum += fn.execute(base + (i & 15)).asLong();
                long start = System.nanoTime(), checksum = 0;
                for (int i = 0; i < repetitions; i++) checksum += fn.execute(base + (i & 15)).asLong();
                long elapsed = System.nanoTime() - start;
                System.out.println(entry + "\t" + repetitions + "\t" + base + "\t" + checksum + "\t" + elapsed);
                System.err.println("warmChecksum=" + warmChecksum);
            }
            System.err.println("diagnostics=" + fn.getMember("diagnostics").asString());
        }
    }
}
