// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

import thc.Json;
import java.lang.management.*;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import java.security.*;
public final class JsonAllocationProbe {
  static long allocated() {
    return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getThreadAllocatedBytes(Thread.currentThread().threadId());
  }
  public static void main(String[] args) throws Exception {
    var paths = Files.readAllLines(Path.of(args[0]));
    var modules = new ArrayList<Object>();
    for (var path : paths) modules.add(Json.INSTANCE.parse(Files.readString(Path.of(path))));
    var request = new LinkedHashMap<String,Object>();
    request.put("modules", modules); request.put("entry", args.length > 2 ? args[2] : "intMapAggregate"); request.put("instrument", true);
    request.put("diagnosticUnsupported", false); request.put("backend", "ast"); request.put("sourceNotesEnabled", true);
    Object value = args.length > 1 && args[1].equals("single") ? modules.getFirst() : request;
    System.gc();
    long baseline = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    for (var pool : ManagementFactory.getMemoryPoolMXBeans()) pool.resetPeakUsage();
    long before = allocated();
    String output = Json.INSTANCE.stringify(value);
    long bytes = allocated() - before;
    long heapAfter = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    long peakPools = ManagementFactory.getMemoryPoolMXBeans().stream().filter(p -> p.getType() == MemoryType.HEAP).mapToLong(p -> p.getPeakUsage().getUsed()).sum();
    var digest = MessageDigest.getInstance("SHA-256");
    try (var writer = new OutputStreamWriter(new DigestOutputStream(OutputStream.nullOutputStream(), digest), java.nio.charset.StandardCharsets.UTF_8)) { writer.write(output); }
    System.out.printf("chars=%d allocated=%d heapBefore=%d heapAfter=%d sumPoolPeaks=%d sha256=%s%n", output.length(), bytes, baseline, heapAfter, peakPools, HexFormat.of().formatHex(digest.digest()));
  }
}
