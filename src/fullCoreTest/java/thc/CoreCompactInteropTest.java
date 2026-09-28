// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real converter/native GHC inputs, not a model writer. Dedicated tasks require both. */
public class CoreCompactInteropTest {
    private final Path manifest = Path.of(System.getProperty("thc.compactInteropManifest"));
    private record Row(String name, long input, long expected) {}
    private Map<String, List<Row>> rows() throws Exception {
        var grouped = new LinkedHashMap<String, List<Row>>();
        var names = new LinkedHashSet<String>(); int count = 0;
        for (String line : Files.readAllLines(Path.of(System.getProperty("thc.compactInteropOracle")))) {
            String[] fields = line.split("\t", -1);
            if (fields.length != 3) throw new IllegalArgumentException("Invalid native compact interop row");
            Row row = new Row(fields[0], Long.parseLong(fields[1]), Long.parseLong(fields[2]));
            grouped.computeIfAbsent(row.name(), ignored -> new ArrayList<>()).add(row); names.add(row.name()); count++;
        }
        assertEquals(Set.of("unicode", "tabbed", "missing"), names); assertEquals(15, count);
        return grouped;
    }
    private CoreUnitProgram program(Context context) {
        context.enter();
        try {
            var programs = Language.currentState().getCoreUnitPrograms();
            assertEquals(1, programs.size()); return programs.getFirst();
        } finally { context.leave(); }
    }
    private long count(CoreUnitProgram program, String field) { return ((Number) program.diagnostics().get(field)).longValue(); }
    private Context compiledContext() {
        return Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw")
            .option("engine.SingleTierCompilationThreshold", "10000000").build();
    }
    @Test public void nativeResultsAndImmediateCompiledEntriesUseTheActualCompactRequest() throws Exception { checkCompiled("ast", manifest, false); }
    @Test public void bytecodeNativeResultsAndImmediateCompiledEntriesUseTheActualCompactRequest() throws Exception { checkCompiled("bytecode", manifest, false); }
    @Test public void debugContainerPreservesImmediateAstCompiledEntriesWithoutReadingDebug() throws Exception {
        checkCompiled("ast", Path.of(System.getProperty("thc.compactInteropDebugManifest")), true);
    }
    private void checkCompiled(String backend, Path selectedManifest, boolean sourceNotes) throws Exception {
        var oracle = rows();
        for (boolean verify : new boolean[] {false, true}) for (var group : oracle.entrySet()) try (Context context = compiledContext()) {
            String name = group.getKey(); var values = group.getValue();
            String id = "main:SourceNotes." + name;
            var entry = context.eval("thc", CoreModules.request(List.of("@" + selectedManifest), id, true, false, backend, sourceNotes, false, null, false, false, verify));
            var program = program(context);
            assertEquals(1L, count(program, "coreCompactModuleOpens"));
            assertEquals(verify ? 8L : 1L, count(program, "coreCompactDecodedBindings"));
            assertEquals(0L, count(program, "coreCompactDebugBytesRead"));
            if (verify) assertTrue(count(program, "coreCompactHashBytesScanned") > 0);
            else assertEquals(0L, count(program, "coreCompactHashBytesScanned"));
            var target = program.entryTarget(id);
            assertTrue(entry.invokeMember("compile").asBoolean());
            var installed = target.getClass().getMethod("isValidLastTier");
            assertEquals(true, installed.invoke(target), backend + "/" + verify + "/" + name + " before first call");
            long before = count(program, "compiledEntries");
            // No interpreted or settling call precedes this invocation.
            assertEquals(values.getFirst().expected(), entry.execute(values.getFirst().input()).asLong());
            assertTrue(count(program, "compiledEntries") > before,
                backend + "/" + verify + "/" + name + " first compiled entry; valid=" + installed.invoke(target) + ", diagnostics=" + program.diagnostics());
            assertSame(target, program.entryTarget(id));
            assertEquals(true, installed.invoke(target), backend + "/" + verify + "/" + name + " after first call");
            for (int i = 1; i < values.size(); i++) assertEquals(values.get(i).expected(), entry.execute(values.get(i).input()).asLong());
            assertEquals(0L, count(program, "coreCompactDebugBytesRead"));
        }
    }
    @Test public void originalJsonPairHasTheSameStrictPublicFirstCompiledControl() throws Exception { checkJsonCompiled("ast"); }
    @Test public void originalJsonBytecodePairHasTheSameStrictPublicFirstCompiledControl() throws Exception { checkJsonCompiled("bytecode"); }
    private void checkJsonCompiled(String backend) throws Exception {
        String reference = System.getProperty("thc.compactInteropReference");
        try (Context context = compiledContext()) {
            String id = "main:SourceNotes.unicode";
            var entry = context.eval("thc", CoreModules.request(List.of("@" + reference), id, true, false, backend, false, false, null, false));
            var program = program(context); var target = program.entryTarget(id);
            long before = count(program, "compiledEntries");
            assertTrue(entry.invokeMember("compile").asBoolean());
            assertEquals(1L, entry.execute(0).asLong());
            Object valid = target.getClass().getMethod("isValidLastTier").invoke(target);
            assertTrue(count(program, "compiledEntries") > before,
                backend + " original JSON first compiled entry; valid=" + valid + ", diagnostics=" + program.diagnostics());
        }
    }
    @Test public void genuineCompactAndJsonPublicExecutionMatchEveryNativeRow() throws Exception {
        var oracle = rows();
        for (String path : List.of(manifest.toString(), System.getProperty("thc.compactInteropReference")))
            for (String backend : List.of("ast", "bytecode")) for (boolean verify : new boolean[] {false, true})
                for (var group : oracle.entrySet()) try (Context context = Context.newBuilder("thc").allowExperimentalOptions(true)
                        .option("engine.Compilation", "false").build()) {
                    var entry = context.eval("thc", CoreModules.request(List.of("@" + path), "main:SourceNotes." + group.getKey(), true, false, backend, false, false, null, false, false, verify));
                    for (Row row : group.getValue()) assertEquals(row.expected(), entry.execute(row.input()).asLong(), backend + "/" + verify + "/" + path + "/" + row);
                    var diagnostics = (Map<?, ?>) Json.parse(entry.getMember("diagnostics").asString());
                    assertEquals(0L, ((Number) diagnostics.get("unsupportedTraps")).longValue());
                    assertEquals(0L, ((Number) diagnostics.get("coreCompactDebugBytesRead")).longValue());
                }
    }
}
