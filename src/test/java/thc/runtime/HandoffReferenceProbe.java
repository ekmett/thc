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

/** Bounded standalone diagnostic, not a timing assertion in the semantic suite. */
public final class HandoffReferenceProbe {
    private static long referenceProbeCalls(RootCallTarget target, Object[] packet, int count) {
        long sink = 0L;
        for (int i = 0; i < count; i++) sink += (Long) Calls.target(target, packet);
        return sink;
    }
    private static boolean strictBoolean(String value) {
        if (value.equals("true")) return true;
        if (value.equals("false")) return false;
        throw new IllegalArgumentException("The string doesn't represent a boolean value: " + value);
    }
    private static void check(boolean condition) { if (!condition) throw new IllegalStateException("Check failed."); }
    private static void require(boolean condition) { if (!condition) throw new IllegalArgumentException("Failed requirement."); }
    private static List<Object> v(String id) { return List.of("var", id); }
    private static List<Object> n(long value) { return List.of("lit", "int", Long.toString(value)); }
    @SafeVarargs private static List<Object> app(List<Object> fn, List<Object>... values) { return List.of("app", fn, Arrays.asList(values), Collections.nCopies(values.length, false)); }
    @SafeVarargs private static List<Object> call(String fn, List<Object>... values) { return app(v(fn), values); }
    @SafeVarargs private static List<Object> prim(String fn, List<Object>... values) { return app(List.of("prim", fn), values); }
    private static Map<String, Object> binding(String id, List<String> args, List<Object> body, Map<String, Object> longRep, Map<String, Object> closureRep) {
        var formals = new ArrayList<Map<String, Object>>();
        for (var arg : args) formals.add(Map.of("id", arg, "name", arg, "lifted", false, "coercion", false, "rep", longRep));
        return Map.of("id", id, "name", id, "lifted", true, "expr", List.of("lam", formals, body, Map.of("rep", closureRep, "resultRep", longRep, "entryStrict", Collections.nCopies(args.size(), false))));
    }
    private static List<Object> box(List<Object> value) { return List.of("app", List.of("con", "Box", 1), List.of(value), List.of(false), true, true); }
    private static List<Object> unbox(List<Object> value, String id, Map<String, Object> longRep, Map<String, Object> dataRep) {
        return List.of("case", value, id, List.of(List.of("data", "Box", List.of(id + "Payload"), v(id + "Payload"), Map.of("binders", List.of(
            Map.of("id", id + "Payload", "name", id + "Payload", "lifted", false, "coercion", false, "rep", longRep))))),
            Map.of("rep", longRep, "binder", Map.of("id", id, "name", id, "lifted", true, "coercion", false, "rep", dataRep)));
    }
    private static void compile(Class<?> targetClass, RootCallTarget target) throws Exception {
        targetClass.getMethod("compile", boolean.class).invoke(target, true); check(Boolean.TRUE.equals(targetClass.getMethod("isValidLastTier").invoke(target)));
    }
    private static long collectionCount() {
        long total = 0;
        for (var bean : ManagementFactory.getGarbageCollectorMXBeans()) total += Math.max(0L, bean.getCollectionCount());
        return total;
    }
    @SuppressWarnings("unchecked")
    public static void referenceProbeMain(String[] arguments) throws Exception {
        boolean enabled = strictBoolean(arguments[0]), inlining = strictBoolean(arguments[1]); var workload = arguments[2];
        System.setProperty(Handoff.HANDOFF_PROPERTY, Boolean.toString(enabled));
        var builder = Context.newBuilder("thc").allowExperimentalOptions(true).option("compiler.Inlining", Boolean.toString(inlining))
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
            .option("engine.SingleTierCompilationThreshold", "10000000").option("compiler.CompilationTimeout", "30").option("compiler.MaximumGraalGraphSize", "200000");
        if (arguments.length > 3) builder.option("compiler.Dump", "Truffle:2").option("compiler.DumpPath", arguments[3]);
        try (var context = builder.build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                Map<String, Object> longRep = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
                Map<String, Object> closureRep = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
                Map<String, Object> dataRep = Map.of("kind", "data", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", false);
                var names = List.of("a", "b", "c", "d");
                var values = new ArrayList<List<Object>>();
                for (var name : names) values.add(unbox(v(name), "read" + name, longRep, dataRep));
                var sum = values.getFirst();
                for (int i = 1; i < values.size(); i++) sum = prim("+#", sum, values.get(i));
                boolean shared = workload.equals("reference-shared"); require(shared || workload.equals("reference-fresh"));
                List<Object> workerBody = shared ? List.of("case", prim("andI#", sum, n(1)), "parity", List.of(
                    List.of("lit", List.of("int", "0"), List.of(), v("a")), Arrays.asList("default", null, List.of(), v("b")))) : box(sum);
                var formals = new ArrayList<Map<String, Object>>();
                for (var name : names) formals.add(Map.of("id", name, "name", name, "lifted", true, "coercion", false, "rep", dataRep));
                var workerBinding = Map.of("id", "worker", "name", "worker", "lifted", true, "expr", List.of("lam", formals, workerBody,
                    Map.of("rep", closureRep, "resultRep", dataRep, "entryStrict", Collections.nCopies(4, false))));
                var inputs = new ArrayList<List<Object>>();
                for (int i = 0; i < names.size(); i++) inputs.add(box(prim("+#", v("n"), n(i))));
                var application = call("worker", inputs.toArray(List[]::new));
                var program = new Program(language, Map.of("instrument", false, "constructors", List.of(
                    Map.of("id", "Box", "name", "Box", "arity", 1, "kind", "boxed", "fieldReps", List.of(List.of("IntRep")), "strictFields", List.of(false), "fieldLifted", List.of(false))),
                    "bindings", List.of(workerBinding, binding("entry", List.of("n"), unbox(application, "answer", longRep, dataRep), longRep, closureRep))));
                var entry = program.entryTarget("entry"); var worker = program.entryTarget("worker");
                Object[] packet = {0L, 3_000_000_017L}; // Immutable across calls: no pooled/exposed argument alias mutation.
                long expected = shared ? 3_000_000_017L : 12_000_000_074L;
                for (int i = 0; i < 2_000; i++) check((Long) Calls.target(entry, packet) == expected);
                var targetClass = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"); compile(targetClass, worker); compile(targetClass, entry);
                long gcBefore = collectionCount();
                System.gc(); // Retained input carrier survives this full collection before long warmup.
                long agingCollections = collectionCount() - gcBefore;
                var warmText = System.getenv("THC_PROBE_WARM_SECONDS"); long warmSeconds = warmText == null ? 45L : Long.parseLong(warmText);
                var sampleText = System.getenv("THC_PROBE_SAMPLE_MILLIS"); long sampleMillis = sampleText == null ? 1000L : Long.parseLong(sampleText);
                var countText = System.getenv("THC_PROBE_SAMPLES"); int sampleCount = countText == null ? 5 : Integer.parseInt(countText);
                require(warmSeconds >= 1 && sampleMillis >= 100 && sampleCount >= 1);
                var compilation = ManagementFactory.getCompilationMXBean(); long sink = 0L, warmStart = System.nanoTime(), warmCalls = 0L;
                do {
                    long batchSum = referenceProbeCalls(entry, packet, 4096); check(batchSum == expected * 4096L);
                    sink += batchSum; warmCalls += 4096;
                } while (System.nanoTime() - warmStart < warmSeconds * 1_000_000_000L);
                check(sink == expected * warmCalls);
                var allocation = (ThreadMXBean) ManagementFactory.getThreadMXBean(); check(allocation.isThreadAllocatedMemorySupported());
                allocation.setThreadAllocatedMemoryEnabled(true); long thread = Thread.currentThread().threadId();
                var bytes = new ArrayList<Double>(); var nanos = new ArrayList<Double>(); var samples = new ArrayList<Map<String, Object>>();
                long measurementStartMillis = System.currentTimeMillis();
                for (int i = 0; i < sampleCount; i++) {
                    long compileBefore = compilation.getTotalCompilationTime(), beforeBytes = allocation.getThreadAllocatedBytes(thread), beforeTime = System.nanoTime();
                    long count = 0L, sampleSum = 0L;
                    do {
                        long batchSum = referenceProbeCalls(entry, packet, 4096); check(batchSum == expected * 4096L);
                        sampleSum += batchSum; count += 4096;
                    } while (System.nanoTime() - beforeTime < sampleMillis * 1_000_000L);
                    long elapsed = System.nanoTime() - beforeTime, allocated = allocation.getThreadAllocatedBytes(thread) - beforeBytes;
                    bytes.add((double) allocated / count); nanos.add((double) elapsed / count);
                    boolean entryValid = Boolean.TRUE.equals(targetClass.getMethod("isValidLastTier").invoke(entry));
                    boolean workerValid = Boolean.TRUE.equals(targetClass.getMethod("isValidLastTier").invoke(worker)); check(entryValid && workerValid);
                    var sample = new LinkedHashMap<String, Object>(); sample.put("calls", count); sample.put("elapsedNs", elapsed);
                    sample.put("hostCompilationMillisDelta", compilation.getTotalCompilationTime() - compileBefore);
                    sample.put("entryCompiled", entryValid); sample.put("workerCompiled", workerValid); sample.put("checksum", sampleSum);
                    sample.put("expectedChecksum", expected * count); sample.put("checksumValid", sampleSum == expected * count); samples.add(sample);
                    check(sampleSum == expected * count);
                }
                long measurementEndMillis = System.currentTimeMillis(); var state = language.getHandoffState().get();
                check(state.getArguments().getDepth() == 0 && state.getPending() == null); check(state.getArguments().retainedReferences() == 0);
                check((Long) Calls.target(entry, packet) == expected && sink == expected * warmCalls);
                var report = new LinkedHashMap<String, Object>(); report.put("handoff", enabled); report.put("inlining", inlining); report.put("workload", workload);
                report.put("warmSeconds", warmSeconds); report.put("warmCalls", warmCalls); report.put("sampleMillis", sampleMillis);
                report.put("measurementStartMillis", measurementStartMillis); report.put("measurementEndMillis", measurementEndMillis);
                report.put("samples", samples); report.put("bytesPerCall", bytes); report.put("nsPerCall", nanos);
                report.put("argumentCarriers", state.getArguments().getAllocations()); report.put("resultCarriers", 0); report.put("resultProtocol", "natural-object"); report.put("agingCollections", agingCollections);
                report.put("entryCompiled", targetClass.getMethod("isValidLastTier").invoke(entry)); report.put("workerCompiled", targetClass.getMethod("isValidLastTier").invoke(worker)); report.put("expected", expected);
                System.out.println(Json.stringify(report));
            } finally { context.leave(); }
        }
    }
    public static void main(String[] arguments) throws Exception { referenceProbeMain(arguments); }
}
