// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import thc.*;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class ShortByteStringSliceTest {
    private final File root = new File(System.getProperty("thc.projectRoot")),
                       directory = new File(root, "build/short-bytes-slices");
    private final List<String> entries = List.of("takeCase", "dropCase", "splitCase");
    private final String lengthWorker = "ghc-internal:GHC.Internal.List.$wlenAcc";
    private Context context(boolean inlining) {
        return Context.newBuilder("thc")
            .allowExperimentalOptions(true)
            .option("compiler.Inlining", Boolean.toString(inlining))
            .option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw")
            .build();
    }
    private void valid(RootCallTarget target, String label, Row row, RootCallTarget host) throws Exception {
        var installed = target.getClass().getMethod("isValidLastTier").invoke(target);
        var detail = Boolean.TRUE.equals(installed) ? label
                                                    : label + " row=" + row + " target=" + target.getClass().getName()
                + "@" + Integer.toHexString(System.identityHashCode(target))
                + " root=" + target.getRootNode().getClass().getName() + ":" + target.getRootNode().getName()
                + " host=" + (target == host);
        assertEquals(true, installed, detail);
    }
    private void compile(RootCallTarget target) throws Exception {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
        valid(target, "initial installation", null, null);
    }
    private List<RootCallTarget> activeTargets(RootCallTarget entry) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<RootCallTarget, Boolean>());
        var targets = new ArrayList<RootCallTarget>();
        visit(entry, seen, targets);
        return targets;
    }
    private void visit(RootCallTarget target, Set<RootCallTarget> seen, List<RootCallTarget> targets) {
        if (!seen.add(target))
            return;
        var root = target.getRootNode();
        var nodes = new ArrayList<Node>();
        nodes.add(root);
        if (root instanceof BytecodeRoot bytecode)
            for (var instruction : bytecode.getBytecodeNode().getInstructions())
                for (var argument : instruction.getArguments())
                    if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) {
                        var node = argument.asCachedNode();
                        if (node != null)
                            nodes.add(node);
                    }
        for (var node : nodes)
            for (var call : NodeUtil.findAllNodeInstances(node, DirectCallNode.class))
                if (call.getCurrentCallTarget() instanceof RootCallTarget active
                    && active.getRootNode() instanceof GuestRoot)
                    visit(active, seen, targets);
        targets.add(target);
    }
    private record BoundaryProbe(Object method, Method hasCompiledCode) {}
    private record Observation<T>(T value, Throwable failure) {}
    @FunctionalInterface
    private interface Probe<T> {
        T get() throws Throwable;
    }
    private <T> Observation<T> attempt(Probe<T> probe) {
        try {
            return new Observation<>(probe.get(), null);
        } catch (Throwable failure) {
            return new Observation<>(null, failure);
        }
    }
    private record SnapshotProbes(Method validity, Observation<Method> callCount, Observation<BoundaryProbe> boundary) {
    }
    private SnapshotProbes snapshotProbes(RootCallTarget entry) throws Exception {
        return new SnapshotProbes(entry.getClass().getMethod("isValidLastTier"),
            attempt(() -> entry.getClass().getMethod("getCallCount")), attempt(() -> {
                var jvmci = Class.forName("jdk.vm.ci.runtime.JVMCI").getMethod("getRuntime").invoke(null);
                var backend =
                    Class.forName("jdk.vm.ci.runtime.JVMCIRuntime").getMethod("getHostJVMCIBackend").invoke(jvmci);
                var metaAccess =
                    Class.forName("jdk.vm.ci.runtime.JVMCIBackend").getMethod("getMetaAccess").invoke(backend);
                var method = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
                                 .getDeclaredMethod("callBoundary", Object[].class);
                var boundary = Class.forName("jdk.vm.ci.meta.MetaAccessProvider")
                                   .getMethod("lookupJavaMethod", java.lang.reflect.Executable.class)
                                   .invoke(metaAccess, method);
                return new BoundaryProbe(boundary,
                    Class.forName("jdk.vm.ci.hotspot.HotSpotResolvedJavaMethod").getMethod("hasCompiledCode"));
            }));
    }
    private record TargetState(RootCallTarget target, Observation<Boolean> valid, Observation<Integer> calls) {}
    private record CallSnapshot(List<TargetState> targets, Observation<Boolean> boundary) {}
    private CallSnapshot snapshot(List<RootCallTarget> targets, SnapshotProbes probes) {
        var states = new ArrayList<TargetState>();
        for (var target : targets)
            states.add(new TargetState(target, attempt(() -> (Boolean) probes.validity().invoke(target)),
                probes.callCount().failure() != null
                    ? new Observation<>(null, probes.callCount().failure())
                    : attempt(() -> (Integer) probes.callCount().value().invoke(target))));
        var boundary = probes.boundary();
        Observation<Boolean> compiled = boundary.failure() != null
            ? new Observation<>(null, boundary.failure())
            : attempt(() -> (Boolean) boundary.value().hasCompiledCode().invoke(boundary.value().method()));
        return new CallSnapshot(states, compiled);
    }
    private String unavailable(Throwable error) {
        var message = Objects.toString(error.getMessage(), "");
        return "unavailable(" + error.getClass().getSimpleName() + ": "
            + message.substring(0, Math.min(160, message.length())) + ")";
    }
    private String observed(Observation<?> value) {
        return value.failure() == null ? value.value().toString() : unavailable(value.failure());
    }
    private String describe(
        CallSnapshot snapshot, RootCallTarget host, RootCallTarget original, RootCallTarget worker) {
        var descriptions = new ArrayList<String>();
        for (int i = 0; i < snapshot.targets().size(); i++) {
            var state = snapshot.targets().get(i);
            var target = state.target();
            var name = target.getRootNode().getName();
            descriptions.add(i + ":" + target.getClass().getSimpleName() + "@"
                + Integer.toHexString(System.identityHashCode(target))
                + " root=" + target.getRootNode().getClass().getSimpleName() + ":"
                + name.substring(0, Math.min(120, name.length())) + " host=" + (target == host)
                + " original=" + (target == original) + " worker=" + (target == worker)
                + " valid=" + observed(state.valid()) + " calls=" + observed(state.calls()));
        }
        return "[" + String.join(", ", descriptions) + "] boundaryHasCompiledCode=" + observed(snapshot.boundary());
    }
    private void released(Language language) {
        var state = language.getHandoffState().get();
        assertEquals(0, state.getArguments().getDepth());
        assertEquals(0, state.getResults().getDepth());
        assertEquals(0, state.getArguments().retainedReferences());
        assertEquals(0, state.getResults().retainedReferences());
    }
    private record Row(String name, long seed, long count, long side, long selector, long expected) {}
    private List<Long> payload(long seed) {
        int length = (int) Math.abs(seed % 17);
        var result = new ArrayList<Long>();
        for (int i = 0; i < length; i++) result.add((seed + i * 73L) & 255L);
        return result;
    }
    private List<Long> value(String name, long seed, long count, long side) {
        var data = payload(seed);
        int cut = (int) Math.max(0, Math.min(count, data.size()));
        return new ArrayList<>(name.equals("takeCase") || name.equals("splitCase") && side == 0L
                ? data.subList(0, cut)
                : data.subList(cut, data.size()));
    }
    private long observe(List<Long> data, long selector) {
        if (selector == -2L) {
            long result = 0;
            for (long b : data) result = result * 33 + b;
            return result;
        }
        if (selector == -1L)
            return data.size();
        return selector >= 0 && selector < data.size() ? data.get((int) selector) : -1L;
    }
    private Map<String, Object> manifest() throws Exception {
        var manifest =
            (Map<String, Object>) Json.parse(Files.readString(new File(directory, "manifest.json").toPath()));
        assertEquals(entries, manifest.get("entries"));
        return manifest;
    }
    private Map<String, Object> merged(List<String> paths) throws Exception {
        var modules = new ArrayList<Map<String, Object>>();
        for (var path : paths)
            modules.add(thc.CoreCbdFixtures.read(new File(root, path).toPath()));
        return CoreModules.merge(modules);
    }
    @Test
    void publicSlicesWithInlining() throws Exception {
        verifyNative(true, false);
    }
    @Test
    void publicSlicesAcrossResidualCalls() throws Exception {
        verifyNative(false, false);
    }
    @Test
    @Tag("jit-stability")
    void publicSlicesRetainCodeWithInlining() throws Exception {
        verifyNative(true, true);
    }
    @Test
    @Tag("jit-stability")
    void publicSlicesRetainCodeAcrossResidualCalls() throws Exception {
        verifyNative(false, true);
    }
    private void check(Row row, Value function, Language language, String label) {
        assertEquals(row.expected(), function.execute(row.seed(), row.count(), row.side(), row.selector()).asLong(),
            label + "/" + row.seed() + "/" + row.count() + "/" + row.side() + "/" + row.selector());
        released(language);
    }
    private void verifyNative(boolean inlining, boolean stability) throws Exception {
        var manifest = manifest();
        var rows = new ArrayList<Row>();
        for (var line : Files.readAllLines(new File(directory, "oracle.tsv").toPath())) {
            var p = line.split("\t", -1);
            rows.add(new Row(p[0], Long.parseLong(p[1]), Long.parseLong(p[2]), Long.parseLong(p[3]),
                Long.parseLong(p[4]), Long.parseLong(p[5])));
        }
        var seeds = new ArrayList<Long>();
        for (var n : (List<Number>) manifest.get("seeds")) seeds.add(n.longValue());
        assertEquals(265, seeds.size());
        assertTrue(seeds.containsAll(List.of(Long.MIN_VALUE, Long.MAX_VALUE, -1L, 0L, 255L)));
        var expected = new ArrayList<Row>();
        for (var name : entries)
            for (long seed : seeds) {
                long n = payload(seed).size();
                for (long count :
                    new TreeSet<>(List.of(Long.MIN_VALUE, -1L, 0L, 1L, n / 2, n - 1, n, n + 1, Long.MAX_VALUE)))
                    for (long side : name.equals("splitCase") ? List.of(0L, 1L) : List.of(0L)) {
                        var data = value(name, seed, count, side);
                        var selectors = new ArrayList<>(List.of(Long.MIN_VALUE, -2L, -1L));
                        for (long i = 0; i <= data.size(); i++) selectors.add(i);
                        selectors.add(Long.MAX_VALUE);
                        for (long selector : selectors)
                            expected.add(new Row(name, seed, count, side, selector, observe(data, selector)));
                    }
            }
        assertEquals(81312, rows.size());
        assertEquals(((Number) manifest.get("nativeRows")).intValue(), rows.size());
        assertEquals(
            expected, rows, "Native oracle includes every result byte, length, checksum and boundary sentinel");
        var stages = (Map<String, List<String>>) manifest.get("stages");
        assertEquals(Set.of("pre", "post"), stages.keySet());
        for (var stageEntry : stages.entrySet()) {
            var stage = stageEntry.getKey();
            var module = merged(stageEntry.getValue());
            for (var name : entries) {
                var audit = (Map<String, Object>) Json.parse(
                    Files.readString(new File(directory, stage + "-" + name + ".audit.json").toPath()));
                assertEquals(true, audit.get("accepted"));
                boolean retained = false;
                for (var binding : (List<Map<String, Object>>) audit.get("reachableBindings"))
                    retained |= lengthWorker.equals(binding.get("id"));
                assertTrue(retained);
                var selected = new ArrayList<Row>();
                for (var row : rows)
                    if (row.name().equals(name))
                        selected.add(row);
                for (var backend : List.of("ast", "bytecode")) try (var context = context(inlining)) {
                        context.initialize("thc");
                        context.enter();
                        try {
                            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                            var instrumented = new LinkedHashMap<>(CoreModules.reachable(module, "main:ShortByteStringSliceAudit." + name));
                            instrumented.put("instrument", true);
                            ExecutableProgram program = backend.equals("ast")
                                ? new Program(language, instrumented)
                                : new BytecodeProgram(language, instrumented);
                            var function = context.asValue(new EntryValue(program, "main:ShortByteStringSliceAudit." + name, 4));
                            var host = program.hostEntryTarget(4);
                            var original = program.entryTarget("main:ShortByteStringSliceAudit." + name);
                            var worker = program.entryTarget(lengthWorker);
                            var label = stage + "/" + backend + "/" + name + "/inlining=" + inlining;
                            // Warm the complete native corpus once; no settling calls, retries, automatic threshold
                            // changes or compiler budget overrides.
                            for (var row : selected) check(row, function, language, label);
                            if (stability) {
                                var targets = activeTargets(host);
                                assertTrue(targets.size() > 1, label + " adopted guest call path");
                                for (var target : targets)
                                    if (target != host)
                                        compile(target);
                                compile(original);
                                compile(worker);
                                assertTrue(function.invokeMember("compile").asBoolean());
                                var state = language.getHandoffState().get();
                                long resultAllocations = state.getResults().getAllocations();
                                var probes = snapshotProbes(host);
                                var observed = new ArrayList<>(targets);
                                for (var candidate : List.of(original, worker)) {
                                    boolean found = false;
                                    for (var target : targets) found |= target == candidate;
                                    if (!found)
                                        observed.add(candidate);
                                }
                                for (var row : selected.reversed()) {
                                    // Read-only snapshots add no guest invocation, repair, or compilation. Failed
                                    // optional observations cannot replace the unchanged assertions.
                                    var beforeTargets = snapshot(observed, probes);
                                    long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                                    check(row, function, language, label);
                                    long after = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                                    var afterTargets = snapshot(observed, probes);
                                    try {
                                        assertTrue(after > before, label + ": actual compiled guest entry");
                                        assertEquals(targets, activeTargets(host), label + " active target identity");
                                        valid(original, label + " original", row, host);
                                        valid(worker, label + " List worker", row, host);
                                        for (int i = 0; i < targets.size(); i++)
                                            valid(targets.get(i), label + " active[" + i + "]", row, host);
                                    } catch (AssertionError error) {
                                        System.err.println("ShortByteStringSlice FIRST FAILURE " + label + " row=" + row
                                            + " context@" + Integer.toHexString(System.identityHashCode(context))
                                            + " handoff=" + System.getProperty("thc.handoffSlabs", "false")
                                            + " countBefore=" + before + " countAfter=" + after + " before={"
                                            + describe(beforeTargets, host, original, worker) + "} after={"
                                            + describe(afterTargets, host, original, worker) + "}");
                                        throw error;
                                    }
                                }
                                assertEquals(resultAllocations, state.getResults().getAllocations(),
                                    label + " result slabs reused");
                            } else
                                for (var row : selected.reversed()) check(row, function, language, label);
                            for (var counter : List.of("unsupportedTraps", "blackholes"))
                                assertEquals(0L, ((Number) program.diagnostics().get(counter)).longValue(),
                                    label + "/" + counter);
                            System.out.println("ShortByteStringSlice PASS " + label + " rows=" + selected.size()
                                + " stability=" + stability);
                        } finally {
                            context.leave();
                        }
                    }
            }
        }
    }
    @Test
    void completeAppendAndConcatOverflowPathsRemainStrictFrontiers() throws Exception {
        var manifest = manifest();
        var unit = (String) manifest.get("installedBytestringUnitId");
        assertTrue(unit.matches("bytestring-0\\.12\\.2\\.0(?:-[A-Za-z0-9]+)?"));
        var stages = (Map<String, List<String>>) manifest.get("stages");
        for (var stageEntry : stages.entrySet())
            for (var name : List.of("appendFrontier", "concatFrontier")) {
                var stage = stageEntry.getKey();
                var paths = stageEntry.getValue();
                var audit = (Map<String, Object>) Json.parse(
                    Files.readString(new File(directory, stage + "-" + name + ".audit.json").toPath()));
                assertEquals(false, audit.get("accepted"));
                assertEquals(List.of(), audit.get("issues"));
                var missing = new ArrayList<Object>();
                for (var binding : (List<Map<String, Object>>) audit.get("missingGlobals"))
                    missing.add(binding.get("id"));
                assertEquals(List.of(unit + ":Data.ByteString.Internal.Type.overflowError"), missing);
                for (var backend : List.of("ast", "bytecode")) try (var context = context(true)) {
                        context.initialize("thc");
                        context.enter();
                        try {
                            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                            var linked = CoreModules.reachable(merged(paths), "main:ShortByteStringSliceAudit." + name);
                            assertThrows(UnsupportedCore.class, () -> {
                                if (backend.equals("ast"))
                                    new Program(language, linked);
                                else
                                    new BytecodeProgram(language, linked);
                            });
                            released(language);
                        } finally {
                            context.leave();
                        }
                    }
            }
    }
}
