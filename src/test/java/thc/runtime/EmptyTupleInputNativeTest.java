// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeUtil;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import thc.CoreModules;
import thc.CoreCbdFixtures;
import thc.EntryValue;
import thc.Json;
import thc.Language;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class EmptyTupleInputNativeTest {
    private static String id(String name) { return "main:EmptyTupleInputAudit." + name; }
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final Path folder = root.resolve("build/empty-tuple-input");
    private Map<String, Object> module(String stage) throws Exception {
        return CoreCbdFixtures.read(folder.resolve(stage + "-core/EmptyTupleInputAudit.cbd"));
    }
    @BeforeEach void currentEvidence() throws Exception {
        var evidence = (Map<String, Object>) Json.parse(Files.readString(folder.resolve("provenance.json")));
        for (var kind : List.of("sources", "artifacts")) for (var item : (List<Map<String, String>>) evidence.get(kind)) {
            var path = item.get("path");
            var actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(path))));
            assertEquals(item.get("sha256"), actual, "Stale empty-input evidence: " + path);
        }
        for (var stage : List.of("pre", "post")) {
            var audit = (Map<String, Object>) Json.parse(Files.readString(folder.resolve(stage + "-audit.json")));
            assertEquals(true, audit.get("accepted"), stage + " strict native audit");
        }
    }
    private static Context context(boolean inlining) {
        return Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.SingleTierCompilationThreshold", "10000").option("engine.CompilationFailureAction", "Throw")
            .option("compiler.CompilationTimeout", "30").option("compiler.MaximumGraalGraphSize", "100000")
            .option("compiler.Inlining", Boolean.toString(inlining)).build();
    }
    private static void valid(RootCallTarget target, String label) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label); }
    private static void released(Language language) {
        var state = language.getHandoffState().get();
        assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getArguments().retainedReferences()); assertNull(state.getPending());
        assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getResults().retainedReferences());
    }
    @Test void genuineEmptyInputsExecuteInlinedWithInstalledHostAndGuestEntries() throws Exception { checkNative(true, 1); }
    @Test void genuineEmptyInputsExecuteAcrossResidualCallsWithInstalledHostAndGuestEntries() throws Exception { checkNative(false, 1); }
    @Test void independentScalarInputsKeepCompiledEntryAndCompactPositions() throws Exception { checkNative(true, 2); checkNative(false, 2); }
    private static Set<RootCallTarget> activeTargets(RootCallTarget host, RootCallTarget original) {
        var targets = new LinkedHashSet<RootCallTarget>();
        for (var call : NodeUtil.findAllNodeInstances(host.getRootNode(), DirectCallNode.class)) if (call.getCallTarget() == original) targets.add((RootCallTarget) call.getCurrentCallTarget());
        if (targets.isEmpty()) targets.add(original);
        return targets;
    }
    private static void check(String[] row, int arity, Value function, Language language, String label) {
        var inputs = new Object[row.length - 2];
        for (int i = 1; i < row.length - 1; i++) inputs[i - 1] = Long.parseLong(row[i]);
        assertEquals(arity, inputs.length);
        assertEquals(Long.parseLong(row[row.length - 1]), function.execute(inputs).asLong(), label + "/" + Arrays.asList(inputs));
        released(language);
    }
    private void checkNative(boolean inlining, int arity) throws Exception {
        var rows = new LinkedHashMap<String, List<String[]>>();
        for (var line : Files.readAllLines(folder.resolve(arity == 1 ? "oracle.tsv" : "oracle-pairs.tsv"))) {
            var row = line.split("\t", -1); rows.computeIfAbsent(row[0], ignored -> new ArrayList<>()).add(row);
        }
        int total = 0; for (var cases : rows.values()) total += cases.size();
        assertEquals(arity == 1 ? 118 : 7, total); assertEquals(arity == 1 ? 17 : 1, rows.size());
        for (var stage : List.of("pre", "post")) for (var rowGroup : rows.entrySet()) for (var backend : List.of("ast", "bytecode")) try (var context = context(inlining)) {
            var name = rowGroup.getKey(); var cases = rowGroup.getValue();
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var linked = new LinkedHashMap<>(CoreModules.reachable(module(stage), id(name))); linked.put("instrument", true);
                ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                var host = program.hostEntryTarget(arity); var function = context.asValue(new EntryValue(program, id(name), arity));
                var label = stage + "/" + backend + "/" + name + "/inlining=" + inlining;
                for (var row : cases) check(row, arity, function, language, label);
                assertTrue(function.invokeMember("compile").asBoolean(), label + " installation");
                var original = program.entryTarget(id(name)); var active = activeTargets(host, original);
                for (var row : cases.reversed()) {
                    long before = (Long) program.diagnostics().get("compiledEntries");
                    check(row, arity, function, language, label);
                    assertTrue((Long) program.diagnostics().get("compiledEntries") > before, label + "/" + row[1] + " compiled guest entry");
                    assertEquals(active, activeTargets(host, original), label + " current active identities");
                    valid(original, label + " original guest"); valid(host, label + " host");
                    for (var target : active) valid(target, label + " active guest");
                }
                assertEquals(0L, program.diagnostics().get("unsupportedTraps")); assertEquals(0L, program.diagnostics().get("blackholes"));
            } finally { context.leave(); }
        }
    }
    private static Object call(ExecutableProgram program, String name, long x) { return Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue(id(name)), new Object[]{x}}); }
    @Test void nativeEmptyProducerFailurePrecedesDeadFormalOrPapAndLeavesNoLoans() throws Exception {
        for (var stage : List.of("pre", "post")) for (var name : List.of("effectCase", "effectPapCase")) for (var backend : List.of("ast", "bytecode")) try (var context = context(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var linked = CoreModules.reachable(module(stage), id(name));
                ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                var good = call(program, name, 7L);
                assertThrows(GuestException.class, () -> call(program, name, -1L)); released(language);
                assertEquals(good, call(program, name, 7L)); released(language);
            } finally { context.leave(); }
        }
    }
}
