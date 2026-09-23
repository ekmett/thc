import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.TreeMap;
import com.sun.management.HotSpotDiagnosticMXBean;

/** Read-only sizing outside the timed/allocation process. No bytecode transformation. */
public final class IntThunkSizes {
    private static Instrumentation sizes;
    public static void premain(String args, Instrumentation instrumentation) {
        sizes = instrumentation;
        Runtime.getRuntime().addShutdownHook(new Thread(IntThunkSizes::report, "int-thunk-sizes"));
    }
    private static boolean guest(Object o) {
        if (o instanceof Object[]) return true;
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            String n = c.getName();
            if (n.equals("thc.runtime.Thunk") || n.equals("thc.runtime.ExperimentalIntThunk") ||
                n.equals("thc.runtime.CapturedFrame") || n.equals("thc.runtime.DataValue")) return true;
        }
        return false;
    }
    private static String category(Object o) {
        if (o instanceof Object[]) return "reference-array";
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            String n = c.getName();
            if (n.equals("thc.runtime.CapturedFrame")) return "capture";
            if (n.equals("thc.runtime.DataValue")) return "data-value";
        }
        return o.getClass().getSimpleName();
    }
    private static Object readStatic(Class<?> c, String name) throws Exception {
        Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(null);
    }
    private static void report() {
        try {
            Class<?> harness = Class.forName("thc.runtime.IntThunkExperiment");
            var vm = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
            for (String name : new String[]{"UseCompactObjectHeaders", "UseCompressedOops", "UseCompressedClassPointers", "ObjectAlignmentInBytes"}) {
                System.out.println("{\"kind\":\"vm-flag\",\"name\":\"" + name + "\",\"value\":\"" + vm.getVMOption(name).getValue() + "\"}");
            }
            for (String name : new String[]{"sampleBox", "sampleCapture", "sampleCell"}) {
                Object o = readStatic(harness, name);
                if (o == null) continue;
                System.out.println("{\"kind\":\"shallow-size\",\"sample\":\""+name+"\",\"class\":\""+o.getClass().getName()+"\",\"bytes\":"+sizes.getObjectSize(o)+"}");
                for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        if (!Modifier.isStatic(f.getModifiers())) System.out.println("{\"kind\":\"field\",\"sample\":\""+name+"\",\"owner\":\""+c.getName()+"\",\"name\":\""+f.getName()+"\",\"type\":\""+f.getType().getName()+"\"}");
                    }
                }
            }
            Object root = readStatic(harness, "heapRoots");
            if (root == null) return;
            var seen = new IdentityHashMap<Object, Boolean>();
            var pending = new ArrayDeque<Object>(); pending.add(root);
            Map<String, long[]> totals = new TreeMap<>();
            while (!pending.isEmpty()) {
                Object o = pending.remove();
                if (!guest(o) || seen.put(o, true) != null) continue;
                long[] entry = totals.computeIfAbsent(category(o), k -> new long[2]);
                entry[0]++; entry[1] += sizes.getObjectSize(o);
                if (o instanceof Object[] a) {
                    for (Object ref : a) if (ref != null && guest(ref)) pending.add(ref);
                } else {
                    for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
                        for (Field f : c.getDeclaredFields()) {
                            if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                            f.setAccessible(true); Object ref = f.get(o);
                            if (ref != null && guest(ref)) pending.add(ref);
                        }
                    }
                }
            }
            long bytes = 0, count = 0;
            for (var e : totals.entrySet()) {
                count += e.getValue()[0]; bytes += e.getValue()[1];
                System.out.println("{\"kind\":\"reachable-guest-category\",\"category\":\""+e.getKey()+"\",\"count\":"+e.getValue()[0]+",\"bytes\":"+e.getValue()[1]+"}");
            }
            System.out.println("{\"kind\":\"reachable-guest-total\",\"count\":"+count+",\"bytes\":"+bytes+",\"boundary\":\"heapRoots; guest cells, captures, data values and reference arrays only; metadata excluded; identity deduplicated; not dominator retained size\"}");
        } catch (Throwable failure) {
            failure.printStackTrace(); System.err.println("INT_THUNK_SIZE_AGENT_FAILED");
        }
    }
}
