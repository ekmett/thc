// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import thc.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
public class SumJoinInputNativeTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/sum-join-input");
    private Map<String, Object> json(File file) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8)); }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), target.toString()); }
    private List<List<?>> groups(Object value) {
        var result = new ArrayList<List<?>>();
        if (value instanceof List<?> list) { if (!list.isEmpty() && "let".equals(list.getFirst())) result.add(list); for (var child : list) result.addAll(groups(child)); }
        else if (value instanceof Map<?, ?> map) for (var child : map.values()) result.addAll(groups(child));
        return result;
    }
    private void assertRecursiveWrapper(Map<String, Object> source, String name) {
        Map<String, Object> binding = null;
        for (var candidate : (List<Map<String, Object>>) source.get("bindings")) if (name.equals(candidate.get("id"))) { assertNull(binding); binding = candidate; }
        assertNotNull(binding); var joins = new ArrayList<List<?>>();
        for (var group : groups(binding.get("expr"))) {
            boolean join = false; for (var candidate : (List<Map<String, Object>>) group.get(2)) if (candidate.containsKey("joinValueArity")) join = true;
            if (join) joins.add(group);
        }
        var recursive = new ArrayList<Object>(); for (var join : joins) recursive.add(join.get(1)); assertEquals(List.of(false, true), recursive, "GHC wrapper then recursive join");
        var wrapperBindings = (List<Map<String, Object>>) joins.getFirst().get(2); assertEquals(1, wrapperBindings.size()); var wrapper = (List<?>) wrapperBindings.getFirst().get("expr");
        assertEquals(joins.get(1), wrapper.get(2)); var loops = (List<Map<String, Object>>) joins.get(1).get(2); assertEquals(1, loops.size());
        var loop = loops.getFirst(); var transfer = (List<?>) joins.get(1).get(3); assertEquals("app", transfer.getFirst()); assertEquals(loop.get("id"), ((List<?>) transfer.get(1)).get(1));
        var parameters = new ArrayList<Object>(); for (var parameter : (List<Map<String, Object>>) wrapper.get(1)) parameters.add(parameter.get("id"));
        var actuals = new ArrayList<Object>(); for (var argument : (List<List<?>>) transfer.get(2)) { assertEquals("var", argument.getFirst()); actuals.add(argument.get(1)); }
        assertEquals(parameters, actuals);
    }
    @TestFactory public List<DynamicTest> originalSumJoinInputsInline() throws Exception { return nativeTests(true); }
    @TestFactory public List<DynamicTest> originalSumJoinInputsResidual() throws Exception { return nativeTests(false); }
    private void check(String entry, List<String> row, ExecutableProgram program, Value callable) {
        long before = (Long) program.diagnostics().get("localJoinTransfers");
        assertEquals(Long.parseLong(row.get(2)), callable.execute(Long.parseLong(row.get(1))).asLong(), row.get(1));
        long transfers = (Long) program.diagnostics().get("localJoinTransfers") - before;
        long expected = switch (entry) {
            // GHC retains a nonrecursive NOINLINE join wrapper around
            // these recursive loops: entry transfers through both.
            case "recursiveSwap", "changingTag" -> (Long.parseLong(row.get(1)) & 31L) + 2;
            case "mutual" -> (Long.parseLong(row.get(1)) & 15L) + 1;
            default -> 1L;
        };
        assertEquals(expected, transfers, entry + " join transfer count");
    }
    private List<DynamicTest> nativeTests(boolean inlining) throws Exception {
        var manifest = json(new File(directory, "manifest.json"));
        for (String group : List.of("inputHashes", "artifactHashes")) for (var hash : ((Map<String, String>) manifest.get(group)).entrySet())
            assertEquals(hash.getValue(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, hash.getKey()).toPath()))), "Stale sum join input fixture " + hash.getKey());
        var rows = new LinkedHashMap<String, List<List<String>>>(); int count = 0;
        for (String line : Files.readAllLines(new File(directory, "oracle.tsv").toPath(), StandardCharsets.UTF_8)) {
            var row = Arrays.asList(line.split("\t", -1)); rows.computeIfAbsent(row.getFirst(), ignored -> new ArrayList<>()).add(row); count++;
        }
        assertEquals(7, rows.size()); assertEquals(259, count); var tests = new ArrayList<DynamicTest>();
        for (String stage : List.of("pre", "post")) {
            assertEquals(true, json(new File(directory, stage + "/audit.json")).get("accepted")); var source = CoreCbdFixtures.read(new File(directory, stage + "/core/SumJoinInputAudit.cbd").toPath());
            for (var group : rows.entrySet()) {
                String entry = group.getKey(), name = "main:SumJoinInputAudit." + entry; var cases = group.getValue();
                if (List.of("recursiveSwap", "changingTag").contains(entry)) assertRecursiveWrapper(source, name);
                var linked = new LinkedHashMap<>(CoreModules.reachable(source, name, true)); linked.put("instrument", true);
                for (String backend : List.of("ast", "bytecode")) tests.add(DynamicTest.dynamicTest(stage + "/" + backend + "/" + entry + "/inlining=" + inlining, () -> {
                    try (Context context = Context.newBuilder("thc").allowExperimentalOptions(true).option("compiler.Inlining", Boolean.toString(inlining))
                        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
                        context.initialize("thc"); context.enter();
                        try {
                            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                            ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                            var callable = context.asValue(new EntryValue(program, name, 1));
                            for (var row : cases) check(entry, row, program, callable); assertTrue(callable.invokeMember("compile").asBoolean());
                            for (var row : cases.reversed()) {
                                long before = (Long) program.diagnostics().get("compiledEntries"); check(entry, row, program, callable);
                                assertTrue((Long) program.diagnostics().get("compiledEntries") > before); valid(program.entryTarget(name)); valid(program.hostEntryTarget(1));
                            }
                            var handoff = language.getHandoffState().get(); assertEquals(0, handoff.getResults().getDepth()); assertEquals(0, handoff.getArguments().getDepth());
                            assertEquals(0, handoff.getResults().retainedReferences()); assertEquals(0, handoff.getArguments().retainedReferences());
                        } finally { context.leave(); }
                    }
                }));
            }
        }
        return tests;
    }
}
