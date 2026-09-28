// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.sun.management.ThreadMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

/** Target-state inspection only; checked calls and reflection are not a timing benchmark. */
public final class RetentionProbe {
    private static boolean blank(String line) {
        return line.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c));
    }
    private static Map<String, Object> targetState(RootCallTarget target, RootCallTarget host,
                                                  RootCallTarget original, Class<?> targetType) throws Exception {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("name", target.toString());
        state.put("identity", System.identityHashCode(target));
        state.put("host", target == host);
        state.put("original", target == original);
        for (String method : List.of("isValidLastTier", "getCodeAddress", "getCallCount", "getCallAndLoopCount", "getSuccessfulCompilationCount"))
            state.put(method, targetType.getMethod(method).invoke(target));
        try {
            var method = target.getClass().getDeclaredMethod("getInvalidationReason");
            method.setAccessible(true);
            state.put("invalidationReason", method.invoke(target));
        } catch (ReflectiveOperationException failure) {
            // Preserve diagnostic failure without masking the compile failure
            // whose finally block requested this snapshot.
            state.put("invalidationReasonError", (failure.getCause() == null ? failure : failure.getCause()).toString());
        }
        return state;
    }
    private static void snapshot(String phase, int calls, RootCallTarget host, RootCallTarget original,
                                 Class<?> targetType, ThreadMXBean allocations,
                                 List<GarbageCollectorMXBean> collectors, Value function) throws Exception {
        var targets = new LinkedHashSet<RootCallTarget>();
        targets.add(host);
        targets.add(original);
        for (var node : NodeUtil.findAllNodeInstances(host.getRootNode(), DirectCallNode.class))
            if (node.getCallTarget() == original) targets.add((RootCallTarget) node.getCurrentCallTarget());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phase", phase);
        data.put("calls", calls);
        data.put("callingThreadAllocatedBytes", allocations != null && allocations.isThreadAllocatedMemorySupported()
            ? allocations.getThreadAllocatedBytes(Thread.currentThread().threadId()) : null);
        data.put("gcCount", collectors.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum());
        data.put("gcTimeMs", collectors.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum());
        List<Map<String, Object>> states = new ArrayList<>();
        for (var target : targets) states.add(targetState(target, host, original, targetType));
        data.put("targets", states);
        data.put("diagnostics", Json.INSTANCE.parse(function.getMember("diagnostics").asString()));
        System.out.println(Json.INSTANCE.stringify(data));
    }
    private static long checkedCall(Value function, long[] inputs, long[] expected, int index) {
        int at = index & 15;
        long actual = function.execute(inputs[at]).asLong();
        if (actual != expected[at]) throw new IllegalStateException("Native mismatch on input " + inputs[at] + ": " + actual);
        return actual;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Usage: retention-probe MODULES_MANIFEST ENTRY NATIVE_CYCLE_TSV");
        var rows = Files.readAllLines(Path.of(args[2])).stream().filter(line -> !blank(line)).map(line -> line.split("\t", -1)).toList();
        if (rows.size() != 16 || rows.stream().anyMatch(row -> row.length != 3 || !row[0].equals(args[1])))
            throw new IllegalArgumentException("Failed requirement.");
        long[] inputs = new long[16], expected = new long[16];
        for (int i = 0; i < 16; i++) { inputs[i] = Long.parseLong(rows.get(i)[1]); expected[i] = Long.parseLong(rows.get(i)[2]); }
        if (Arrays.stream(inputs).distinct().count() != 16) throw new IllegalArgumentException("Failed requirement.");
        boolean instrument = Boolean.getBoolean("thc.retentionProbe.instrument");
        boolean sampleTargets = Boolean.getBoolean("thc.retentionProbe.sampleTargets");
        var bean = ManagementFactory.getThreadMXBean();
        var allocations = bean instanceof ThreadMXBean threads ? threads : null;
        var collectors = ManagementFactory.getGarbageCollectorMXBeans();
        var launcher = Class.forName("thc.Main");
        // Select the stable public signature, not internal launcher overloads.
        var factories = Arrays.stream(launcher.getMethods()).filter(method -> method.getName().equals("executionContext")
            && method.getParameterCount() == 1 && method.getParameterTypes()[0] == boolean.class).toList();
        if (factories.size() > 1) throw new IllegalArgumentException("More than one context factory");
        var factory = factories.isEmpty() ? launcher.getMethod("executionContext") : factories.getFirst();
        var created = (Context) (factory.getParameterCount() == 0 ? factory.invoke(null) : factory.invoke(null, false));
        try (Context context = created) {
            var function = (Value) launcher.getMethod("loadEntry", Context.class, List.class,
                String.class, boolean.class, String.class).invoke(null, context,
                    Files.readAllLines(Path.of(args[0])).stream().filter(line -> !blank(line)).toList(),
                    args[1], instrument, System.getProperty("thc.backend", "bytecode"));
            // Diagnostic-only inspection of the pinned wrapper; not part of the guest API.
            var receiverField = Value.class.getSuperclass().getDeclaredField("receiver");
            receiverField.setAccessible(true);
            var receiver = receiverField.get(function);
            var programField = receiver.getClass().getDeclaredField("program");
            programField.setAccessible(true);
            var program = programField.get(receiver);
            var hostField = receiver.getClass().getDeclaredField("guestTarget");
            hostField.setAccessible(true);
            var host = (RootCallTarget) hostField.get(receiver);
            var original = (RootCallTarget) program.getClass().getMethod("entryTarget", String.class).invoke(program, args[1]);
            var targetType = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
            for (int i = 0; i < 200; i++) checkedCall(function, inputs, expected, i);
            if (!function.invokeMember("compile").asBoolean()) throw new IllegalStateException("Check failed.");
            snapshot("installed", 0, host, original, targetType, allocations, collectors, function);
            long checksum = 0;
            for (int index = 0; index < 12032; index++) {
                checksum += checkedCall(function, inputs, expected, index);
                // Target inspection can affect cold-code reclamation: keep its
                // original optional sampling separate from the default interval.
                if (index == 0 || sampleTargets && (index + 1) % 256 == 0)
                    snapshot("checked", index + 1, host, original, targetType, allocations, collectors, function);
            }
            if (checksum != Arrays.stream(expected).sum() * (12032 / 16)) throw new IllegalStateException("Check failed.");
            snapshot("before-retention-check", 12032, host, original, targetType, allocations, collectors, function);
            try {
                // No retry or settling call: retain the original harness assertion.
                if (!function.invokeMember("compile").asBoolean()) throw new IllegalStateException("Check failed.");
            } finally { snapshot("after-retention-check", 12032, host, original, targetType, allocations, collectors, function); }
        }
    }
}
