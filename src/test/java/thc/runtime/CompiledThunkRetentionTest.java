// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import thc.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class CompiledThunkRetentionTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private record Row(long input, long expected) {}
    @Test void cachedFloatingNaNThunksRetainEveryCompiledCall() throws Exception {
        var rows = new ArrayList<String[]>();
        for (var line : Files.readAllLines(root.resolve("build/floating/oracle.tsv"))) rows.add(line.split("\t", -1));
        for (var entry : List.of("floatComparisons", "doubleComparisons")) {
            var selected = new ArrayList<Row>();
            for (var row : rows) if (row[0].equals(entry)) selected.add(new Row(Long.parseLong(row[1]), Long.parseLong(row[2])));
            boolean contains = false; for (var row : selected) if (row.input == -16777216L) { contains = true; break; }
            assertTrue(contains);
            var compiled = new ArrayList<>(selected);
            // Select the cached NaN branch on the very first compiled call.
            compiled.sort(Comparator.comparingInt(row -> row.input == -16777216L ? 0 : 1));
            check("pre", List.of("build/floating/core/FloatingAudit.cbd"), "main:FloatingAudit." + entry, selected, compiled);
        }
    }
    private List<RootCallTarget> active(RootCallTarget host, RootCallTarget original) {
        var result = new ArrayList<RootCallTarget>();
        for (var call : NodeUtil.findAllNodeInstances(host.getRootNode(), DirectCallNode.class))
            if (call.getCallTarget() == original) result.add((RootCallTarget) call.getCurrentCallTarget());
        return result;
    }
    private void valid(List<RootCallTarget> targets, Class<?> targetClass, String prefix, String label) throws Exception {
        for (var target : targets) assertEquals(true, targetClass.getMethod("isValidLastTier").invoke(target), prefix + "/" + label + "/" + target);
    }
    private void check(String stage, List<String> paths, String entry, List<Row> warm, List<Row> compiled) throws Exception {
        var modules = new ArrayList<Map<String, Object>>();
        for (var path : paths) modules.add(CoreCbdFixtures.read(root.resolve(path)));
        var module = new LinkedHashMap<>(CoreModules.reachable(CoreModules.merge(modules), entry)); module.put("instrument", true);
        for (var backend : List.of("ast", "bytecode")) try (var context = Main.executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                ExecutableProgram program = backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
                var function = context.asValue(new EntryValue(program, entry, 1));
                var host = program.hostEntryTarget(1); var original = program.entryTarget(entry);
                for (var row : warm) assertEquals(row.expected, function.execute(row.input).asLong());
                var observed = active(host, original); assertTrue(!observed.isEmpty());
                assertTrue(function.invokeMember("compile").asBoolean());
                var targets = new LinkedHashSet<RootCallTarget>(); targets.add(host); targets.add(original); targets.addAll(observed);
                var ordered = new ArrayList<>(targets);
                var targetClass = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
                var prefix = stage + "/" + backend + "/" + entry;
                valid(ordered, targetClass, prefix, "installed");
                for (int index = 0; index < compiled.size(); index++) {
                    var row = compiled.get(index);
                    valid(ordered, targetClass, prefix, "before-" + index + "/" + row.input);
                    long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                    assertEquals(row.expected, function.execute(row.input).asLong());
                    assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before);
                    assertEquals(observed, active(host, original));
                    valid(ordered, targetClass, prefix, "after-" + index + "/" + row.input);
                }
                assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                assertEquals(0, language.getHandoffState().get().getArguments().getDepth());
            } finally { context.leave(); }
        }
    }
}
