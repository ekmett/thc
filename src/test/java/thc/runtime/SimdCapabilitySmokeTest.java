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
import thc.CoreModules;
import thc.EntryValue;
import thc.Json;
import thc.Language;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Finite scalar entries exercise promoted local vectors without a vector call ABI. */
@SuppressWarnings("unchecked")
class SimdCapabilitySmokeTest {
    private static final String PREFIX = "main:GeneratedSimdSmoke.";
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/simd-capability-smoke");
    private record Pair(long a, long b) {}

    /** Requests use real exported vector primops; only their edge expectations are Java-specific. */
    private List<List<String>> javaExtremaCases(String name, List<List<String>> cases, Map<String, String> selectors) {
        var owned = new LinkedHashSet<Integer>();
        for (var row : cases) owned.add(Integer.parseInt(row.get(1)) / 4096);
        var result = new ArrayList<List<String>>();
        for (var selector : selectors.entrySet()) {
            var index = selector.getKey(); var primitive = selector.getValue();
            if (!owned.contains(Integer.parseInt(index)) || !primitive.matches("(min|max)(Float|Double)X(2|4|8|16)#")) continue;
            boolean wide = primitive.contains("Double");
            int count = Integer.parseInt(primitive.substring(primitive.indexOf('X') + 1, primitive.length() - 1));
            long sign = wide ? Long.MIN_VALUE : 0x80000000L;
            long one = wide ? 0x3ff0000000000000L : 0x3f800000L;
            long infinity = wide ? 0x7ff0000000000000L : 0x7f800000L;
            long nan = wide ? 0x7ff8000000001234L : 0x7fc01234L;
            var pairs = new LinkedHashSet<Pair>();
            for (var pair : List.of(new Pair(0, sign), new Pair(0, 0), new Pair(sign, sign), new Pair(nan, one),
                    new Pair(nan, infinity), new Pair(nan, nan + 1), new Pair(nan | sign, one | sign),
                    new Pair(infinity, one), new Pair(infinity, infinity | sign), new Pair(1, 3),
                    new Pair(sign | 1, 1), new Pair(infinity - 1, (infinity - 1) | sign))) {
                pairs.add(pair); pairs.add(new Pair(pair.b, pair.a));
            }
            for (int lane = 0; lane < count; lane++) for (var pair : pairs) {
                boolean minimum = primitive.startsWith("min");
                long expected;
                if (wide) {
                    double x = Double.longBitsToDouble(pair.a), y = Double.longBitsToDouble(pair.b);
                    double value = minimum ? Math.min(x, y) : Math.max(x, y);
                    expected = Double.isNaN(value) ? 0x7ff8000000000000L : Double.doubleToRawLongBits(value);
                } else {
                    float x = Float.intBitsToFloat((int) pair.a), y = Float.intBitsToFloat((int) pair.b);
                    float value = minimum ? Math.min(x, y) : Math.max(x, y);
                    expected = Float.isNaN(value) ? 0x7fc00000L : Float.floatToRawIntBits(value) & 0xffffffffL;
                }
                result.add(List.of(name, Integer.toString(Integer.parseInt(index) * 4096 + lane),
                    Long.toString(pair.a - lane * 104729L), Long.toString(pair.b + lane * 7919L), Long.toString(expected)));
            }
        }
        return result;
    }

    private List<RootCallTarget> targets(RootCallTarget entry) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<RootCallTarget, Boolean>());
        var result = new ArrayList<RootCallTarget>();
        visit(entry, seen, result);
        return result;
    }
    private void visit(RootCallTarget target, Set<RootCallTarget> seen, List<RootCallTarget> result) {
        if (!seen.add(target)) return;
        var body = target.getRootNode();
        var nodes = new ArrayList<Node>(); nodes.add(body);
        if (body instanceof BytecodeRoot root) for (var instruction : root.getBytecodeNode().getInstructions())
            for (var argument : instruction.getArguments())
                if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) {
                    var cached = argument.asCachedNode();
                    if (cached != null) nodes.add(cached);
                }
        for (var node : nodes) for (var call : NodeUtil.findAllNodeInstances(node, DirectCallNode.class))
            if (call.getCurrentCallTarget() instanceof RootCallTarget child && child.getRootNode() instanceof GuestRoot) {
                visit(child, seen, result);
            }
        result.add(target);
    }
    private boolean compiled(RootCallTarget target) throws Exception {
        return Boolean.TRUE.equals(target.getClass().getMethod("isValidLastTier").invoke(target));
    }
    private void check(Value function, List<String> row, String backend, String name) {
        assertEquals(Long.parseLong(row.get(4)), function.execute(Long.parseLong(row.get(1)),
            Long.parseLong(row.get(2)), Long.parseLong(row.get(3))).asLong(), backend + "/" + name + "/" + row.subList(1, row.size()));
    }

    @Test void finiteLocalVectorsCompileOnAstAndBytecode() throws Exception {
        var manifest = (Map<String, Object>) Json.parse(Files.readString(new File(directory, "manifest.json").toPath()));
        assertEquals("9.14.1", manifest.get("ghcVersion"));
        assertTrue(List.of("scalar", "scalar-and-vector").contains(manifest.get("nativeOracle")));
        assertEquals("java-math", manifest.get("floatingExtrema"));
        assertEquals("finite-without-mixed-zero-ties", manifest.get("nativeFloatingExtrema"));
        var selectors = (Map<String, String>) manifest.get("selectors");
        var allOperations = new LinkedHashSet<>(GeneratedVectors.operations);
        allOperations.removeIf(operation -> operation.startsWith("shuffle"));
        for (var family : List.of("Int8X16", "Int16X8", "Int32X4", "Word8X16", "Word16X8", "Word32X4")) {
            for (var operation : List.of("broadcast", "plus", "minus", "times")) allOperations.add(operation + family + "#");
            if (family.startsWith("Int")) allOperations.add("negate" + family + "#");
        }
        var operations = new LinkedHashSet<>(allOperations);
        operations.removeIf(operation -> operation.startsWith("pack") || operation.startsWith("unpack"));
        assertEquals(operations, new LinkedHashSet<>(selectors.values()));
        int extrema = 0;
        for (var selector : selectors.values()) if (selector.matches("(min|max)(Float|Double)X(2|4|8|16)#")) extrema++;
        assertEquals(12, extrema);
        var items = new ArrayList<>((List<Map<String, String>>) manifest.get("inputs"));
        items.addAll((List<Map<String, String>>) manifest.get("artifacts"));
        for (var item : items) {
            var file = new File(root, item.get("path"));
            var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath())));
            assertEquals(item.get("sha256"), digest, "Stale SIMD smoke input/artifact: " + item.get("path"));
        }
        var module = thc.CoreCbdFixtures.read(new File(directory, "pre-core/GeneratedSimdSmoke.cbd").toPath());
        assertEquals(allOperations, new LinkedHashSet<>((List<String>) manifest.get("operations")));
        var rows = new LinkedHashMap<String, List<List<String>>>();
        for (var line : Files.readAllLines(new File(directory, "cases.tsv").toPath())) if (!line.isEmpty()) {
            var fields = Arrays.asList(line.split("\t", -1)); assertEquals(5, fields.size());
            rows.computeIfAbsent(fields.getFirst(), ignored -> new ArrayList<>()).add(fields);
        }
        assertEquals(manifest.get("names"), new ArrayList<>(rows.keySet()));
        int rowCount = 0; for (var cases : rows.values()) rowCount += cases.size();
        assertEquals(((Number) manifest.get("rows")).intValue(), rowCount);
        var javaEdges = new LinkedHashMap<String, List<List<String>>>();
        for (var row : rows.entrySet()) javaEdges.put(row.getKey(), javaExtremaCases(row.getKey(), row.getValue(), selectors));
        int edgeCount = 0; for (var cases : javaEdges.values()) edgeCount += cases.size();
        assertEquals(1848, edgeCount, "Both extrema and operand orders across all 42 floating lanes");
        for (var backend : List.of("ast", "bytecode")) try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("compiler.Inlining", "false").option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
                .option("engine.SingleTierCompilationThreshold", "10000000").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var rowEntry : rows.entrySet()) {
                    var name = rowEntry.getKey();
                    var cases = new ArrayList<>(rowEntry.getValue()); cases.addAll(javaEdges.get(name));
                    var input = new LinkedHashMap<>(CoreModules.reachable(module, PREFIX + name)); input.put("instrument", true);
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, input) : new BytecodeProgram(language, input);
                    var host = program.hostEntryTarget(3);
                    var function = context.asValue(new EntryValue(program, PREFIX + name, 3));
                    for (var row : cases) check(function, row, backend, name);
                    var active = targets(host);
                    long beforeInstallation = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                    for (var target : active) if (target != host) {
                        try { target.getClass().getMethod("compile", boolean.class).invoke(target, true); }
                        catch (Exception failure) { throw new AssertionError(backend + "/" + name + " guest compilation", failure); }
                        assertTrue(compiled(target), backend + "/" + name + " guest installation");
                        var runtime = com.oracle.truffle.api.Truffle.getRuntime();
                        runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target);
                    }
                    assertTrue(function.invokeMember("compile").asBoolean(), backend + "/" + name + " host installation");
                    assertEquals(beforeInstallation, ((Number) program.diagnostics().get("compiledEntries")).longValue(), "Installation executes no compiled guest work");
                    for (var row : cases) {
                        long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                        check(function, row, backend, name);
                        assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before,
                            backend + "/" + name + " installed guest execution");
                        var state = language.getHandoffState().get();
                        assertEquals(0, state.getArguments().getDepth());
                        assertEquals(0, state.getArguments().retainedReferences());
                        assertEquals(0, state.getResults().getDepth());
                        assertEquals(0, state.getResults().retainedReferences());
                    }
                    for (var counter : List.of("unsupportedTraps", "blackholes"))
                        assertEquals(0L, ((Number) program.diagnostics().get(counter)).longValue(), backend + "/" + name + "/" + counter);
                }
            } finally { context.leave(); }
        }
    }
}
