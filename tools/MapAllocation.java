import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

/** Separate allocation measurement; not used for timing or graph capture. */
public final class MapAllocation {
  public static void main(String[] args) throws Exception {
    if (args.length != 2) throw new IllegalArgumentException("MODULES_FILE INPUT_BASE");
    var paths = Files.readAllLines(Path.of(args[0])).stream().filter(s -> !s.isBlank()).toList();
    long base = Long.parseLong(args[1]);
    var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    if (!bean.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Allocation counter unavailable");
    bean.setThreadAllocatedMemoryEnabled(true);
    long thread = Thread.currentThread().threadId();
    try (Context context = thc.MainKt.executionContext()) {
      Value function = thc.MainKt.loadEntry(context, paths, "mapAggregate", false);
      long checksum = 0, calls = 0;
      for (int i = 0; i < 200; i++) checksum += function.execute(base + (i & 15)).asLong();
      function.invokeMember("compile");
      long warmStart = System.nanoTime();
      do {
        for (int i = 0; i < 16; i++) { checksum += function.execute(base + (calls & 15)).asLong(); calls++; }
      } while (System.nanoTime() - warmStart < 15_000_000_000L);
      function.invokeMember("compile");
      for (int sample = 1; sample <= 3; sample++) {
        long before = bean.getThreadAllocatedBytes(thread), result = 0;
        for (int i = 0; i < 256; i++) result += function.execute(base + (i & 15)).asLong();
        long bytes = bean.getThreadAllocatedBytes(thread) - before;
        System.out.printf("%d\t256\t%d\t%d\t%d\n", sample, base, result, bytes);
      }
      System.err.println("warmCalls=" + calls + " checksum=" + checksum);
      System.err.println("diagnostics=" + function.getMember("diagnostics").asString());
    }
  }
}
