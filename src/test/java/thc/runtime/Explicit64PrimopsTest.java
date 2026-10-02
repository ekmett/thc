// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import thc.NumericPrimopCoreEvidence;
import thc.ScalarPrimopModel;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
public final class Explicit64PrimopsTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private Map<String, Object> manifest() throws Exception {
        return (Map<String, Object>) Json.INSTANCE.parse(Files.readString(root.resolve("build/explicit64-primops/manifest.json")));
    }
    private Map<String, Object> module() throws Exception {
        return thc.CoreCbdFixtures.read(root.resolve("build/explicit64-primops/core/Explicit64PrimopsAudit.cbd"));
    }
    private Context context() {
        return Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw").build();
    }
    private ExecutableProgram program(Language language, Map<String, Object> module, String backend) {
        return backend.equals("ast") ? new Program(language, module, false, false) : new BytecodeProgram(language, module);
    }
    private void valid(RootCallTarget target) throws Exception {
        assertEquals(true, Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget").getMethod("isValidLastTier").invoke(target));
    }
    private void compile(RootCallTarget target) throws Exception {
        Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget").getMethod("compile", boolean.class).invoke(target, true);
        valid(target);
    }
    @FunctionalInterface private interface Visit { void accept(Language language, String backend) throws Exception; }
    private void visit(Visit action) throws Exception {
        for (String backend : List.of("ast", "bytecode")) try (Context context = context()) {
            context.initialize("thc");
            context.enter();
            try { action.accept(TruffleLanguage.LanguageReference.create(Language.class).get(null), backend); }
            finally { context.leave(); }
        }
    }
    private void verifyHashes(Map<String, Object> data) throws Exception {
        for (String kind : List.of("inputHashes", "artifactHashes")) for (var entry : ((Map<String, String>) data.get(kind)).entrySet()) {
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(entry.getKey()))));
            assertEquals(entry.getValue(), actual, "Stale explicit64 fixture: " + entry.getKey());
        }
    }
    private long mathematical(Map<String, Object> entry, long left, long right) {
        return ScalarPrimopModel.explicit64((String) entry.get("operation"), Boolean.TRUE.equals(entry.get("unsigned")), left, right);
    }
    private void check(RootCallTarget host, Object value, int arity, String label, long[] row) {
        Object[] args = arity == 1 ? new Object[] {row[0]} : new Object[] {row[0], row[1]};
        assertEquals(row[2], Calls.target(host, new Object[] {value, args}), label + "(" + Arrays.toString(args) + ")");
    }
    private long count(ExecutableProgram program, String counter) {
        return ((Number) program.diagnostics().get(counter)).longValue();
    }

    @Test void exactNativeScalarEntriesAgreeWithIndependentModelAndInstalledCode() throws Exception {
        var manifest = manifest();
        verifyHashes(manifest);
        var entries = (List<Map<String, Object>>) manifest.get("entries");
        assertEquals(36L, entries.stream().filter(e -> e.get("primitive") != null).count());
        Map<String, List<long[]>> casesByName = new LinkedHashMap<>();
        for (String line : Files.readAllLines(root.resolve("build/explicit64-primops/oracle.tsv"))) {
            String[] row = line.split("\t");
            casesByName.computeIfAbsent(row[0], ignored -> new ArrayList<>()).add(new long[] {
                Long.parseLong(row[1]), Long.parseLong(row[2]), Long.parseLong(row[3])});
        }
        assertEquals(entries.stream().map(e -> e.get("name")).collect(Collectors.toSet()), casesByName.keySet());
        var exported = module();
        for (var entry : entries) {
            String name = (String) entry.get("name");
            for (long[] row : casesByName.get(name)) assertEquals(mathematical(entry, row[0], row[1]), row[2],
                "native " + name + "(" + row[0] + "," + row[1] + ")");
            String primitive = (String) entry.get("primitive");
            if (primitive != null) NumericPrimopCoreEvidence.assertCall(NumericPrimopCoreEvidence.calls(exported, "main:Explicit64PrimopsAudit." + name),
                primitive, (List<String>) entry.get("arguments"), (String) entry.get("result"), name);
        }
        visit((language, backend) -> {
            for (var entry : entries) {
                String name = (String) entry.get("name");
                int arity = ((Number) entry.get("arity")).intValue();
                var cases = casesByName.get(name);
                var module = CoreModules.INSTANCE.reachable(exported, "main:Explicit64PrimopsAudit." + name, false);
                var bindings = (List<Map<String, Object>>) module.get("bindings");
                var binding = bindings.stream().filter(b -> ("main:Explicit64PrimopsAudit." + name).equals(b.get("id"))).findFirst().orElseThrow();
                var lambda = (List<Object>) binding.get("expr");
                assertEquals(((List<String>) entry.get("arguments")).stream().map(List::of).toList(),
                    ((List<Map<String, Object>>) lambda.get(1)).stream().map(b -> CoreRepresentations.binder(b).getPrimReps()).toList());
                assertEquals(List.of(entry.get("result")), CoreRepresentations.lambdaResult(lambda).getPrimReps());
                var program = program(language, module, backend);
                Object value = program.entryValue("main:Explicit64PrimopsAudit." + name);
                RootCallTarget host = program.hostEntryTarget(arity);
                for (long[] row : cases) check(host, value, arity, backend + "/" + name, row);
                RootCallTarget target = program.entryTarget("main:Explicit64PrimopsAudit." + name);
                compile(target);
                long before = count(program, "compiledEntries");
                var compiledCases = cases.reversed();
                check(host, value, arity, backend + "/" + name, compiledCases.getFirst());
                long first = count(program, "compiledEntries");
                assertTrue(first > before, name + " first call enters installed code");
                valid(target);
                for (long[] row : compiledCases.subList(1, compiledCases.size())) check(host, value, arity, backend + "/" + name, row);
                assertTrue(count(program, "compiledEntries") > first, name + " batch enters installed code");
                valid(target);
                assertEquals(0L, count(program, "unsupportedTraps"));
                assertEquals(0L, count(program, "blackholes"));
            }
        });
    }

    private Map<String, Object> raw(List<?> body, int arity) {
        List<Map<String, Object>> parameters = new ArrayList<>();
        for (int i = 0; i < arity; i++) parameters.add(Map.of("id", "x" + i, "lifted", false));
        return Map.of("schema", 1, "ghc", "9.14.1", "module", "Explicit64Control", "constructors", List.of(),
            "bindings", List.of(Map.of("id", "entry", "name", "entry", "arity", arity, "lifted", true,
                "expr", List.of("lam", parameters, body))));
    }
    private Map<String, Object> diagnostic(Map<String, Object> module, boolean enabled) {
        Map<String, Object> result = new LinkedHashMap<>(module);
        result.put("diagnosticUnsupported", enabled);
        return result;
    }
    private List<?> literal(String value, boolean alternative) {
        return !alternative ? List.of("lit", "word64", value) : List.of("case", List.of("var", "x0"), "whole", List.of(
            List.of("lit", List.of("word64", value), List.of(), List.of("lit", "int", "1")),
            Arrays.asList("default", null, List.of(), List.of("lit", "int", "0"))));
    }

    @Test void malformedAritiesAndWord64LiteralsRejectAtLoadIncludingCaseAlternatives() throws Exception {
        visit((language, backend) -> {
            for (boolean diagnostic : new boolean[] {false, true}) {
                for (var entry : (List<Map<String, Object>>) manifest().get("entries")) {
                    String primitive = (String) entry.get("primitive");
                    if (primitive == null) continue;
                    int arity = ((Number) entry.get("arity")).intValue();
                    for (int supplied : new int[] {arity - 1, arity + 1}) {
                        List<List<String>> arguments = new ArrayList<>();
                        for (int i = 0; i < supplied; i++) arguments.add(List.of("var", "x" + i));
                        var body = List.of("app", List.of("prim", primitive), arguments, Collections.nCopies(supplied, false));
                        var error = assertThrows(RuntimeFault.class, () -> program(language, diagnostic(raw(body, supplied), diagnostic), backend));
                        assertTrue(error.getMessage() != null && error.getMessage().contains("Primitive arity mismatch: " + primitive), error.getMessage());
                    }
                }
                for (boolean alternative : new boolean[] {false, true}) {
                    for (String value : List.of("0", "1", "9223372036854775808", "18446744073709551615")) {
                        var program = program(language, diagnostic(raw(literal(value, alternative), 1), diagnostic), backend);
                        long bits = Long.parseUnsignedLong(value);
                        assertEquals(alternative ? 1L : bits, Calls.target(program.hostEntryTarget(1), new Object[] {program.entryValue("entry"), new Object[] {bits}}));
                        if (alternative) assertEquals(0L, Calls.target(program.hostEntryTarget(1), new Object[] {program.entryValue("entry"), new Object[] {bits ^ 1}}));
                    }
                    for (String value : List.of("-1", "18446744073709551616", "", "+1", "01", "-0", " 1", "1.0")) {
                        var error = assertThrows(RuntimeFault.class, () -> program(language, diagnostic(raw(literal(value, alternative), 1), diagnostic), backend));
                        assertTrue(error.getMessage() != null && error.getMessage().contains("Invalid word64 literal"), error.getMessage());
                    }
                }
            }
        });
    }

    @Test void integralBinderAliasesExecuteButIncompatibleCarriersReject() throws Exception {
        visit((language, backend) -> {
            for (boolean diagnostic : new boolean[] {false, true}) for (var representation : List.of(
                    List.of("long", "Word64Rep"), List.of("long", "IntRep"), List.of("float", "FloatRep"),
                    List.of("double", "DoubleRep"), List.of("object", "BoxedRep (Just Lifted)"))) {
                String kind = representation.get(0), rep = representation.get(1);
                var module = CoreModules.INSTANCE.reachable(module(), "main:Explicit64PrimopsAudit.plusInt64", false);
                var bindings = (List<Map<String, Object>>) module.get("bindings");
                var binding = bindings.stream().filter(b -> "main:Explicit64PrimopsAudit.plusInt64".equals(b.get("id"))).findFirst().orElseThrow();
                var lambda = (List<Object>) binding.get("expr");
                var binder = ((List<Map<String, Object>>) lambda.get(1)).getFirst();
                binder.put("rep", Map.of("kind", kind, "primReps", List.of(rep), "evaluated", true));
                String label = backend + "/" + rep + "/diagnostic=" + diagnostic;
                if (kind.equals("long")) {
                    var program = program(language, diagnostic(module, diagnostic), backend);
                    for (long[] pair : new long[][] {{0, 1}, {Long.MAX_VALUE, 1}, {Long.MIN_VALUE, -1}, {-1, 1}})
                        assertEquals(ScalarPrimopModel.explicit64("plus", false, pair[0], pair[1]),
                            Calls.target(program.hostEntryTarget(2), new Object[] {program.entryValue("main:Explicit64PrimopsAudit.plusInt64"), new Object[] {pair[0], pair[1]}}), label);
                } else assertThrows(RuntimeFault.class, () -> program(language, diagnostic(module, diagnostic), backend), label);
            }
        });
    }
}
