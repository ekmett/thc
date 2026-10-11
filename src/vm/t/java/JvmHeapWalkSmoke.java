// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

import com.sun.management.HotSpotDiagnosticMXBean;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.ref.Reference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.management.ObjectName;

/** Allocation starts remain complete after lazily enabling diagnostic heap walks. */
public final class JvmHeapWalkSmoke {
    static final class Entry implements Cloneable {
        final int value;
        Entry(int value) { this.value = value; }
        Entry copy() throws CloneNotSupportedException { return (Entry) super.clone(); }
    }
    static native long jvmtiCount(Class<?> type, boolean legacy);
    static volatile Object sink;
    static Entry[] keep;
    static Entry allocate(int value) { return new Entry(value); }
    static Entry[] array(int size) { return new Entry[size]; }
    static Entry duplicate(Entry entry) throws CloneNotSupportedException { return entry.copy(); }
    static void warmup() throws Exception {
        Entry seed = allocate(-1);
        for (int i = 0; i != 12_000; ++i) {
            sink = allocate(i);
            sink = array(i & 7);
            sink = duplicate(seed);
        }
        sink = null;
    }
    static String histogram() throws Exception {
        return (String) ManagementFactory.getPlatformMBeanServer().invoke(
            new ObjectName("com.sun.management:type=DiagnosticCommand"), "gcClassHistogram",
            new Object[]{new String[]{"-all"}}, new String[]{String[].class.getName()});
    }
    static long count(String histogram, String name) {
        for (String line : histogram.split("\\R")) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length >= 4 && fields[3].equals(name)) return Long.parseLong(fields[1]);
        }
        return 0;
    }
    static long collections() {
        return JamWeak.collections(0) + JamWeak.collections(2);
    }
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static void dump(HotSpotDiagnosticMXBean diagnostic, Path file) throws Exception {
        diagnostic.dumpHeap(file.toString(), false);
        try (InputStream input = Files.newInputStream(file)) {
            check(new String(input.readNBytes(19), StandardCharsets.US_ASCII)
                .equals("JAVA PROFILE 1.0.2" + (char) 0), "invalid heap dump header");
        }
        Files.delete(file);
    }
    public static void main(String[] args) throws Exception {
        var diagnostic = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        collections();
        warmup();
        keep = array(32);
        for (int i = 0; i != keep.length; ++i) keep[i] = allocate(i);
        System.gc();
        long initialCollections = collections();
        String firstRequest = System.getProperty("jam.heap.walk.first", "histogram");
        if (firstRequest.equals("jvmti") || firstRequest.equals("legacy")) {
            System.loadLibrary("jam_jni");
            check(jvmtiCount(Entry.class, firstRequest.equals("legacy")) == 32, "JVMTI lost retained entries");
        } else if (firstRequest.equals("locks")) {
            ManagementFactory.getThreadMXBean().dumpAllThreads(false, true);
        } else {
            check(firstRequest.equals("histogram"), "unknown first diagnostic");
            histogram();
        }
        check(collections() == initialCollections + 1, "first walk did not initialize starts with one major collection");
        String first = histogram();
        check(count(first, Entry.class.getName()) == 32, "first walk lost retained entries");
        long arrays = count(first, Entry[].class.getName());
        long before = collections();
        Entry[] expanded = array(128);
        System.arraycopy(keep, 0, expanded, 0, keep.length);
        for (int i = keep.length; i != expanded.length; ++i)
            expanded[i] = (i & 1) == 0 ? allocate(i) : duplicate(keep[i % keep.length]);
        keep = expanded;
        String second = histogram();
        check(count(second, Entry.class.getName()) == 128, "walk lost newly allocated or cloned entries");
        check(count(second, Entry[].class.getName()) == arrays + 1, "walk lost the newly allocated array");
        check(collections() == before, "initialized -all walk unexpectedly collected");
        Path directory = Files.createTempDirectory("jam-jvm-heap-walk-");
        try {
            dump(diagnostic, directory.resolve("before.hprof"));
            check(collections() == before, "initialized non-live dump unexpectedly collected");
            System.gc();
            dump(diagnostic, directory.resolve("after.hprof"));
            check(count(histogram(), Entry.class.getName()) == 128, "compaction lost object starts");
            for (int i = 0; i != 32; ++i) check(keep[i].value == i, "object payload changed");
        } finally {
            try (var paths = Files.list(directory)) {
                for (Path path : paths.toList()) Files.delete(path);
            }
            Files.delete(directory);
        }
        Reference.reachabilityFence(keep);
        System.out.println("JvmHeapWalkSmoke passed: " + firstRequest + "; lazy starts, allocation, clone, repeated walks and compaction");
    }
}
