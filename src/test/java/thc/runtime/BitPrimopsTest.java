// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thc.CoreModules;
import thc.EntryValue;
import thc.Json;
import thc.Language;
import thc.Main;
import thc.NumericPrimopCoreEvidence;
import thc.PrimopTestContext;
import thc.ScalarPrimopModel;

import static org.junit.jupiter.api.Assertions.*;

/** Native defined bits, an independent unbounded bit model, and installed execution. */
@SuppressWarnings("unchecked")
public final class BitPrimopsTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private Map<String, Object> manifest() throws Exception {
        return (Map<String, Object>) Json.INSTANCE.parse(Files.readString(root.resolve("build/bit-primops/manifest.json")));
    }
    private void verifyHashes(Map<String, Object> manifest) throws Exception {
        for (String kind : List.of("inputHashes", "artifactHashes")) for (var entry : ((Map<String, String>) manifest.get(kind)).entrySet()) {
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(entry.getKey()))));
            assertEquals(entry.getValue(), actual, "Stale bit primitive fixture: " + entry.getKey() + "; run make fixtures TESTS=thc.runtime.BitPrimopsTest");
        }
    }
    private long mathematical(Map<String, Object> entry, long input) {
        return ScalarPrimopModel.bit((String) entry.get("operation"), ((Number) entry.get("width")).intValue(), input);
    }
    private void valid(RootCallTarget target, String label) throws Exception {
        assertEquals(true, Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
            .getMethod("isValidLastTier").invoke(target), label);
    }
    private void check(Value function, String label, Long[] row, long failures) {
        assertEquals(failures, function.execute((Object[]) row).asLong(), label + "(" + row[0] + "): one failure bit per operation");
    }
    private long compiled(ExecutableProgram program) {
        return ((Number) program.diagnostics().get("compiledEntries")).longValue();
    }
    private void released(Language language, String label) {
        var state = language.getHandoffState().get();
        assertEquals(0, state.getArguments().getDepth(), label + " argument loans");
        assertEquals(0, state.getArguments().retainedReferences(), label + " argument references");
        assertEquals(0, state.getResults().getDepth(), label + " result loans");
        assertEquals(0, state.getResults().retainedReferences(), label + " result references");
    }

    @Test void realCoreMatchesNativeAndBitModelForEveryInstalledEntry() throws Exception {
        var manifest = manifest();
        verifyHashes(manifest);
        var entries = (List<Map<String, Object>>) manifest.get("entries");
        assertEquals(24, entries.size());
        Map<String, List<String[]>> rows = new LinkedHashMap<>();
        for (String line : Files.readAllLines(root.resolve("build/bit-primops/oracle.tsv"))) {
            String[] row = line.split("\t");
            rows.computeIfAbsent(row[0], ignored -> new ArrayList<>()).add(row);
        }
        assertEquals(entries.stream().map(e -> e.get("name")).collect(Collectors.toSet()), rows.keySet());
        int rowCount = rows.values().stream().mapToInt(List::size).sum();
        assertEquals(14326, rowCount);
        assertEquals(((Number) manifest.get("nativeRows")).intValue(), rowCount);
        var stages = (Map<String, List<String>>) manifest.get("stages");
        assertEquals(Set.of("pre", "post"), stages.keySet());
        String composite = (String) manifest.get("compositeEntry");
        assertEquals(IntStream.range(0, entries.size()).boxed().toList(),
            entries.stream().map(e -> ((Number) e.get("index")).intValue()).toList());
        List<Map<Long, Long>> expectations = new ArrayList<>();
        for (var entry : entries) {
            String name = (String) entry.get("name");
            assertEquals(rows.get(name).size(), rows.get(name).stream().map(r -> Long.parseLong(r[1])).collect(Collectors.toSet()).size(),
                "Unique native inputs for " + name);
            Map<Long, Long> expected = new LinkedHashMap<>();
            for (String[] row : rows.get(name)) {
                long input = Long.parseLong(row[1]), result = Long.parseLong(row[2]);
                assertEquals(mathematical(entry, input), result, "Native " + name + "(" + input + ")");
                expected.put(input, result);
            }
            expectations.add(expected);
        }
        // Retain every native input; the independent model fills only other operations' gaps.
        TreeSet<Long> inputs = new TreeSet<>();
        expectations.forEach(e -> inputs.addAll(e.keySet()));
        List<Long[]> cases = new ArrayList<>();
        for (long input : inputs) {
            Long[] row = new Long[entries.size() + 1];
            row[0] = input;
            for (int i = 0; i < entries.size(); i++) {
                Long nativeValue = expectations.get(i).get(input);
                row[i + 1] = nativeValue != null ? nativeValue : mathematical(entries.get(i), input);
            }
            cases.add(row);
        }
        assertEquals(1953, cases.size());
        for (var stage : stages.entrySet()) {
            List<Map<String, Object>> modules = new ArrayList<>();
            for (String path : stage.getValue()) modules.add(thc.CoreCbdFixtures.read(root.resolve(path)));
            var merged = CoreModules.INSTANCE.merge(modules);
            var compositeCalls = NumericPrimopCoreEvidence.calls(merged, "main:BitPrimopsAudit." + composite);
            for (var entry : entries) {
                String name = (String) entry.get("name"), primitive = (String) entry.get("primitive");
                List<String> arguments = List.of((String) entry.get("argumentRep"));
                String result = (String) entry.get("resultRep");
                NumericPrimopCoreEvidence.assertCall(NumericPrimopCoreEvidence.calls(merged, "main:BitPrimopsAudit." + name), primitive, arguments, result, stage.getKey() + "/" + name);
                NumericPrimopCoreEvidence.assertCall(compositeCalls, primitive, arguments, result, stage.getKey() + "/" + composite);
            }
            for (String backend : List.of("ast", "bytecode")) try (Context context = PrimopTestContext.primopTestContext()) {
                NumericPrimopCoreEvidence.assertLoadableWrappers(context, merged, entries.stream().map(e -> "main:BitPrimopsAudit." + e.get("name")).toList(), backend);
                context.enter();
                try {
                    String label = stage.getKey() + "/" + backend;
                    Language language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    Map<String, Object> module = new LinkedHashMap<>(CoreModules.INSTANCE.reachable(merged, "main:BitPrimopsAudit." + composite, false));
                    module.put("instrument", true);
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, module, false, false) : new BytecodeProgram(language, module);
                    RootCallTarget host = program.hostEntryTarget(entries.size() + 1);
                    Value function = context.asValue(new EntryValue(program, "main:BitPrimopsAudit." + composite, entries.size() + 1));
                    for (Long[] row : cases) check(function, label, row, 0);
                    List<Long[]> wrongExpectations = new ArrayList<>();
                    for (int i = 0; i < entries.size(); i++) {
                        Long[] wrong = cases.getFirst().clone();
                        wrong[i + 1] ^= 1L;
                        wrongExpectations.add(wrong);
                        check(function, label, wrong, 1L << i);
                    }
                    assertEquals(0L, compiled(program), label + " waits for the explicit compilation request");
                    assertTrue(function.invokeMember("compile").asBoolean(), label + " installation");
                    RootCallTarget original = program.entryTarget("main:BitPrimopsAudit." + composite);
                    List<RootCallTarget> active = NodeUtil.findAllNodeInstances(host.getRootNode(), DirectCallNode.class).stream()
                        .filter(n -> n.getCallTarget() == original).map(n -> (RootCallTarget) n.getCurrentCallTarget()).toList();
                    if (active.isEmpty()) active = List.of(original);
                    for (Long[] row : cases.reversed()) {
                        long before = compiled(program);
                        check(function, label, row, 0);
                        assertTrue(compiled(program) > before, label + "(" + row[0] + ") must enter installed guest code");
                        released(language, label);
                    }
                    // Every result must reach its own check in installed code.
                    for (int i = 0; i < entries.size(); i++) {
                        long before = compiled(program);
                        check(function, label, wrongExpectations.get(i), 1L << i);
                        assertTrue(compiled(program) > before, label + " check " + i + " remains compiled");
                        released(language, label);
                    }
                    valid(host, label + " host remains installed");
                    for (RootCallTarget target : active) valid(target, label + " active target remains installed");
                    for (String counter : List.of("unsupportedTraps", "blackholes"))
                        assertEquals(0L, ((Number) program.diagnostics().get(counter)).longValue(), label + "/" + counter);
                } finally { context.leave(); }
            }
        }
    }

    @Test void malformedArityIsRejectedAtLoadEvenWithDiagnosticExecutionEnabled(@TempDir Path temporary) throws Exception {
        var entries = (List<Map<String, Object>>) manifest().get("entries");
        for (String backend : List.of("ast", "bytecode")) for (boolean diagnostic : new boolean[] {false, true}) {
            try (Context context = Main.executionContext(false)) {
                for (var entry : entries) for (int supplied : new int[] {0, 2}) {
                    String primitive = (String) entry.get("primitive");
                    var body = List.of("app", List.of("prim", primitive, Map.of()), Collections.nCopies(supplied, List.of("lit", "word", "1", Map.of())), Collections.nCopies(supplied, false), false, false, Map.of());
                    var module = Map.of("schema", 1, "ghc", "9.14.1", "unit", "main", "module", "Malformed.BitPrimitive", "boundary", "test-model", "constructors", List.of(),
                        "bindings", List.of(Map.of("id", "main:Malformed.BitPrimitive.entry", "name", "entry", "lifted", true, "arity", 0, "expr", body)));
                    var source = thc.CoreCbdFixtures.write(Files.createTempFile(temporary, "arity-", ".cbd"), module);
                    var error = assertThrows(PolyglotException.class, () -> context.eval("thc", CoreModules.request(
                        List.of(source.toString()), "main:Malformed.BitPrimitive.entry", false, diagnostic, backend)));
                    assertTrue(error.getMessage() != null && error.getMessage().contains("Primitive arity mismatch: " + primitive), error.getMessage());
                }
            }
        }
    }
}
