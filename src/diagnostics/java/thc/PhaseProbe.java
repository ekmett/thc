// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc;

import com.sun.management.ThreadMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

public final class PhaseProbe {
    @FunctionalInterface private interface Action<T> { T run() throws Throwable; }

    /** Cold lifecycle diagnostics, deliberately separate from Probe's throughput runs. */
    private static final class PhaseMeasurements {
        private final ThreadMXBean threads = ManagementFactory.getThreadMXBean() instanceof ThreadMXBean bean ? bean : null;
        private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        private final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();

        private long allocated() {
            return threads != null && threads.isThreadAllocatedMemorySupported()
                ? threads.getThreadAllocatedBytes(Thread.currentThread().threadId()) : -1;
        }
        private long collections() { return collectors.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum(); }
        private long collectionMillis() { return collectors.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum(); }

        private <T> T measure(String name, Action<T> action) throws Throwable {
            long allocatedBefore = allocated(), collectionsBefore = collections(), collectionMsBefore = collectionMillis();
            long start = System.nanoTime();
            Throwable failure = null;
            try {
                return action.run();
            } catch (Throwable error) {
                failure = error;
                throw error;
            } finally {
                long elapsed = System.nanoTime() - start, allocatedAfter = allocated();
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("phase", name);
                data.put("elapsedNs", elapsed);
                data.put("callingThreadAllocatedBytes", allocatedBefore < 0 || allocatedAfter < 0 ? null : allocatedAfter - allocatedBefore);
                data.put("heapUsedBytes", memory.getHeapMemoryUsage().getUsed());
                data.put("gcCount", collections() - collectionsBefore);
                data.put("gcTimeMs", collectionMillis() - collectionMsBefore);
                data.put("failure", failure == null ? null : failure.toString());
                System.out.println(Json.INSTANCE.stringify(data));
            }
        }

        /** Whole-process observations, not retained Core-only object sizes. */
        private void memoryCheckpoint(String name) throws Exception {
            long before = collections();
            System.gc();
            if (collections() <= before) throw new IllegalStateException("Explicit GC did not complete at " + name + "; no post-GC measurement available");
            var status = Path.of("/proc/self/status");
            Map<String, Long> rss = new LinkedHashMap<>();
            if (Files.isRegularFile(status)) for (String line : Files.readAllLines(status)) {
                if (!line.startsWith("VmRSS:") && !line.startsWith("VmHWM:")) continue;
                String[] fields = line.trim().split("\\s+");
                if (fields.length != 3 || !fields[2].equals("kB")) throw new IllegalStateException("Unexpected Linux RSS units: " + line);
                rss.put(fields[0].substring(0, fields[0].length() - 1), Long.parseLong(fields[1]));
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("checkpoint", name);
            data.put("explicitGc", true);
            data.put("heapUsedBytes", memory.getHeapMemoryUsage().getUsed());
            data.put("processRssKiB", rss.get("VmRSS"));
            data.put("processHighWaterRssKiB", rss.get("VmHWM"));
            data.put("scope", "whole-process, not Core-only retained memory");
            System.out.println(Json.INSTANCE.stringify(data));
        }
    }

    private static boolean strictBoolean(String text) {
        return switch (text) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("The string doesn't represent a boolean value: " + text);
        };
    }

    /** Loading experiment only: no training, compilation or throughput claim.
     * Uses the public compact/package request path without a sidecar index.
     * The compiled-call probe below remains a separate strict control.
     */
    private static void jsonLoadProbe(String[] args, boolean interfaceHelper) throws Throwable {
        if (args.length != 5 && args.length != 7) throw new IllegalArgumentException(
            "Usage: phase-probe [--allow-interface-helper] --load-json MODULE ENTRY INPUT EXPECTED [NEXT_INPUT NEXT_EXPECTED]");
        var phases = new PhaseMeasurements();
        String backend = System.getProperty("thc.backend", "bytecode");
        boolean async = strictBoolean(System.getProperty("thc.asyncExceptions", "true"));
        boolean notes = strictBoolean(System.getProperty("thc.sourceNotesEnabled", "false"));
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("mode", "load-only");
        header.put("indexed", true);
        header.put("backend", backend);
        header.put("asyncExceptions", async);
        header.put("sourceNotesEnabled", notes);
        header.put("interfaceHelperAllowed", interfaceHelper);
        System.out.println(Json.INSTANCE.stringify(header));
        var context = phases.measure("context", () -> Main.executionContext(false, interfaceHelper));
        try {
            phases.memoryCheckpoint("preLoad");
            var request = phases.measure("request", () -> CoreModules.request(List.of(args[1]), args[2],
                true, false, backend, notes, false, null, async, false));
            var function = phases.measure("eval", () -> context.eval("thc", request));
            System.out.println(function.getMember("diagnostics").asString());
            phases.memoryCheckpoint("postLoadPreEntry");
            for (int offset = 3; offset < args.length; offset += 2) {
                long input = Long.parseLong(args[offset]), expected = Long.parseLong(args[offset + 1]);
                phases.measure("demand" + ((offset - 3) / 2 + 1), () -> {
                    long actual = function.execute(input).asLong();
                    if (actual != expected) throw new IllegalStateException(args[2] + "(" + input + "): " + actual + " != expected " + expected);
                    return null;
                });
                System.out.println(function.getMember("diagnostics").asString());
                phases.memoryCheckpoint("postDemand" + ((offset - 3) / 2 + 1));
            }
        } finally {
            phases.measure("close", () -> { context.close(); return null; });
        }
    }

    private static long checkResult(Value function, String entry, long input, long expected) {
        long actual = function.execute(input).asLong();
        if (actual != expected) throw new IllegalStateException(entry + "(" + input + "): " + actual + " != native " + expected);
        return actual;
    }
    private static long compiledEntries(Value function) {
        return ((Number) ((Map<?, ?>) Json.INSTANCE.parse(function.getMember("diagnostics").asString())).get("compiledEntries")).longValue();
    }

    /** Uses the stable Java launcher overload to inspect older frozen distributions.
     * Allocation counts cover this calling thread only, not Graal compiler workers.
     */
    public static void main(String[] args) throws Throwable {
        if (args.length >= 2 && args[0].equals("--allow-interface-helper") && args[1].equals("--load-json")) {
            jsonLoadProbe(Arrays.copyOfRange(args, 1, args.length), true); return;
        }
        if (args.length != 0 && args[0].equals("--load-json")) { jsonLoadProbe(args, false); return; }
        if (args.length != 4) throw new IllegalArgumentException("Usage: phase-probe MODULES ENTRY INPUT NATIVE_EXPECTED");
        var modules = Arrays.asList(args[0].split(",", -1));
        String entry = args[1];
        long input = Long.parseLong(args[2]), expected = Long.parseLong(args[3]);
        var phases = new PhaseMeasurements();
        var launcher = Class.forName("thc.Main");
        var context = phases.measure("context", () -> {
            // Select the stable public signature, not internal launcher overloads.
            var factories = Arrays.stream(launcher.getMethods()).filter(method -> method.getName().equals("executionContext")
                && method.getParameterCount() == 1 && method.getParameterTypes()[0] == boolean.class).toList();
            if (factories.size() > 1) throw new IllegalArgumentException("More than one context factory");
            var factory = factories.isEmpty() ? launcher.getMethod("executionContext") : factories.getFirst();
            return (Context) (factory.getParameterCount() == 0 ? factory.invoke(null) : factory.invoke(null, false));
        });
        try {
            var function = phases.measure("load", () -> (Value) launcher.getMethod("loadEntry", Context.class, List.class,
                String.class, boolean.class, String.class).invoke(null, context, modules, entry, true,
                    System.getProperty("thc.backend", "bytecode")));
            phases.measure("firstResult", () -> checkResult(function, entry, input, expected));
            phases.measure("training200", () -> {
                for (int i = 0; i < 200; i++) checkResult(function, entry, input, expected);
                return null;
            });
            phases.measure("compile", () -> {
                if (!function.invokeMember("compile").asBoolean()) throw new IllegalStateException("Check failed.");
                return null;
            });
            long before = compiledEntries(function);
            phases.measure("firstCompiledResult", () -> checkResult(function, entry, input, expected));
            if (compiledEntries(function) <= before) throw new IllegalStateException("First call after compilation did not enter installed guest code");
            System.out.println(function.getMember("diagnostics").asString());
        } finally {
            phases.measure("close", () -> { context.close(); return null; });
        }
    }
}
