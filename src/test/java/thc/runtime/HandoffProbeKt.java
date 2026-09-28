// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.sun.management.ThreadMXBean;
import org.graalvm.polyglot.Context;
import thc.Json;
import thc.Language;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded standalone diagnostic, not a timing assertion in the semantic suite.
 * The existing launcher class name is retained for script compatibility. */
public final class HandoffProbeKt {
    private static long probeCalls(RootCallTarget target, Object[] packet, int count) {
        long sink = 0L;
        for (int i = 0; i < count; i++) sink ^= (Long) Calls.target(target, packet);
        return sink;
    }
    private static boolean strictBoolean(String value) {
        if (value.equals("true")) return true;
        if (value.equals("false")) return false;
        throw new IllegalArgumentException("The string doesn't represent a boolean value: " + value);
    }
    private static void check(boolean condition) { if (!condition) throw new IllegalStateException("Check failed."); }
    private static List<Object> v(String id) { return List.of("var", id); }
    private static List<Object> n(long value) { return List.of("lit", "int", Long.toString(value)); }
    @SafeVarargs private static List<Object> app(List<Object> fn, List<Object>... values) { return List.of("app", fn, Arrays.asList(values), Collections.nCopies(values.length, false)); }
    @SafeVarargs private static List<Object> call(String fn, List<Object>... values) { return app(v(fn), values); }
    @SafeVarargs private static List<Object> prim(String fn, List<Object>... values) { return app(List.of("prim", fn), values); }
    private static Map<String, Object> binding(String id, List<String> args, List<Object> body,
                                             Map<String, Object> longRep, Map<String, Object> closureRep) {
        var formals = new ArrayList<Map<String, Object>>();
        for (var arg : args) formals.add(Map.of("id", arg, "name", arg, "lifted", false, "coercion", false, "rep", longRep));
        return Map.of("id", id, "name", id, "lifted", true, "expr", List.of("lam", formals, body,
            Map.of("rep", closureRep, "resultRep", longRep, "entryStrict", Collections.nCopies(args.size(), false))));
    }
    private static void compile(Class<?> targetClass, RootCallTarget target) throws Exception {
        targetClass.getMethod("compile", boolean.class).invoke(target, true); check(Boolean.TRUE.equals(targetClass.getMethod("isValidLastTier").invoke(target)));
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] arguments) throws Exception {
        boolean enabled = strictBoolean(arguments[0]), inlining = strictBoolean(arguments[1]); var workload = arguments[2];
        System.setProperty(HandoffKt.HANDOFF_PROPERTY, Boolean.toString(enabled));
        var builder = Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("compiler.Inlining", Boolean.toString(inlining)).option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
            .option("engine.SingleTierCompilationThreshold", "10000000")
            .option("compiler.CompilationTimeout", "30").option("compiler.MaximumGraalGraphSize", "200000");
        if (arguments.length > 3) builder.option("compiler.Dump", "Truffle:2").option("compiler.DumpPath", arguments[3]);
        try (var context = builder.build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                Map<String, Object> longRep = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
                Map<String, Object> closureRep = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
                boolean updatePayloads = workload.startsWith("update");
                int wideArity = 0;
                if (workload.startsWith("wide") || updatePayloads) {
                    int index = 0; while (index < workload.length() && !Character.isDigit(workload.charAt(index))) index++;
                    wideArity = Integer.parseInt(workload.substring(index));
                }
                var payloadNames = new ArrayList<String>();
                for (int i = 1; i < wideArity; i++) payloadNames.add("x" + i);
                List<Object> workerBody;
                if (wideArity > 0) {
                    if (payloadNames.isEmpty()) throw new UnsupportedOperationException("Empty collection can't be reduced.");
                    var sum = v(payloadNames.getFirst());
                    for (int i = 1; i < payloadNames.size(); i++) sum = prim("+#", sum, v(payloadNames.get(i)));
                    var recurse = new ArrayList<List<Object>>(); recurse.add(prim("-#", v("d"), n(1)));
                    for (var name : payloadNames) recurse.add(updatePayloads ? prim("+#", v(name), n(1)) : v(name));
                    workerBody = List.of("case", prim("<=#", v("d"), n(4_000_000_000L)), "test", List.of(
                        List.of("lit", List.of("int", "1"), List.of(), sum),
                        Arrays.asList("default", null, List.of(), prim("+#", call("worker", recurse.toArray(List[]::new)), n(1)))));
                } else if (workload.equals("recursive")) workerBody = List.of("case", prim("<=#", v("d"), n(0)), "test", List.of(
                    List.of("lit", List.of("int", "1"), List.of(), v("n")),
                    Arrays.asList("default", null, List.of(), prim("+#", call("worker", prim("-#", v("d"), n(1)), v("n")), v("d")))));
                else workerBody = prim("+#", prim("*#", v("n"), n(3)), n(1));
                List<Object> application;
                if (wideArity > 0) {
                    var values = new ArrayList<List<Object>>(); values.add(n(4_000_000_012L));
                    for (int i = 0; i < payloadNames.size(); i++) values.add(prim("+#", v("n"), n(i)));
                    application = call("worker", values.toArray(List[]::new));
                } else application = workload.equals("recursive") ? call("worker", n(12), v("n")) : call("worker", v("n"));
                List<String> workerArgs;
                if (wideArity > 0) { workerArgs = new ArrayList<>(List.of("d")); workerArgs.addAll(payloadNames); }
                else workerArgs = workload.equals("recursive") ? List.of("d", "n") : List.of("n");
                var program = new Program(language, Map.of("instrument", false, "bindings", List.of(
                    binding("worker", workerArgs, workerBody, longRep, closureRep),
                    binding("entry", List.of("n"), prim("+#", application, n(7)), longRep, closureRep))));
                var entry = program.entryTarget("entry"); var worker = program.entryTarget("worker");
                Object[] packet = {0L, 3_000_000_017L}; // Immutable across calls: no pooled/exposed argument alias mutation.
                long expected;
                if (wideArity > 0) {
                    expected = 0;
                    for (int i = 0; i < payloadNames.size(); i++) expected += 3_000_000_017L + i + (updatePayloads ? 12L : 0L);
                    expected += 19L;
                } else expected = workload.equals("recursive") ? 3_000_000_102L : 9_000_000_059L;
                for (int i = 0; i < 2_000; i++) check((Long) Calls.target(entry, packet) == expected);
                var targetClass = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
                compile(targetClass, worker); compile(targetClass, entry);
                long sink = probeCalls(entry, packet, 1_000_000);
                var allocation = (ThreadMXBean) ManagementFactory.getThreadMXBean();
                check(allocation.isThreadAllocatedMemorySupported()); allocation.setThreadAllocatedMemoryEnabled(true);
                long thread = Thread.currentThread().threadId();
                var bytes = new ArrayList<Double>(); var nanos = new ArrayList<Double>(); int count = 50_000;
                for (int i = 0; i < 9; i++) {
                    long beforeBytes = allocation.getThreadAllocatedBytes(thread), beforeTime = System.nanoTime();
                    sink ^= probeCalls(entry, packet, count);
                    long elapsed = System.nanoTime() - beforeTime, allocated = allocation.getThreadAllocatedBytes(thread) - beforeBytes;
                    bytes.add((double) allocated / count); nanos.add((double) elapsed / count);
                }
                var state = language.getHandoffState().get();
                check(state.getArguments().getDepth() == 0 && state.getPending() == null); check(state.getArguments().retainedReferences() == 0);
                check((Long) Calls.target(entry, packet) == expected && sink == 0L);
                var report = new LinkedHashMap<String, Object>();
                report.put("handoff", enabled); report.put("inlining", inlining); report.put("workload", workload);
                report.put("callsPerSample", count); report.put("bytesPerCall", bytes); report.put("nsPerCall", nanos);
                report.put("argumentCarriers", state.getArguments().getAllocations()); report.put("resultCarriers", 0); report.put("resultProtocol", "scalar-register");
                report.put("entryCompiled", targetClass.getMethod("isValidLastTier").invoke(entry));
                report.put("workerCompiled", targetClass.getMethod("isValidLastTier").invoke(worker)); report.put("expected", expected);
                System.out.println(Json.stringify(report));
            } finally { context.leave(); }
        }
    }
}
