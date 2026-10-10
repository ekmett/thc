// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import org.junit.jupiter.api.Test;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import thc.Main;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class LargeLiteralCaseNativeTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final Path directory = root.resolve("build/large-literal-cases");
    private Map<String, Object> original() throws Exception {
        var hashes = (Map<String, String>) Json.parse(Files.readString(directory.resolve("literal-manifest.json")));
        for (var item : hashes.entrySet()) {
            var actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(item.getKey()))));
            assertEquals(item.getValue(), actual, "Stale original literal-case fixture: " + item.getKey());
        }
        var module = thc.CoreCbdFixtures.read(directory.resolve("core/LargeLiteralCaseAudit.cbd"));
        var linked = new LinkedHashMap<>(CoreModules.reachable(module, List.of("main:LargeLiteralCaseAudit.largeInt", "main:LargeLiteralCaseAudit.largeWordCheck", "main:LargeLiteralCaseAudit.largeLazy", "main:LargeLiteralCaseAudit.boxInt"), true));
        linked.put("instrument", true); return linked;
    }
    @FunctionalInterface private interface Action { void run(String backend, ExecutableProgram program) throws Exception; }
    private void each(Map<String, Object> module, Action action) throws Exception {
        try (var context = Main.executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var backend : List.of("ast", "bytecode")) {
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
                    action.run(backend, program);
                }
            } finally { context.leave(); }
        }
    }
    private static Object call(ExecutableProgram program, String entry, Object input) {
        return Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue("main:LargeLiteralCaseAudit." + entry), new Object[]{input}});
    }
    private static void compile(RootCallTarget target) throws Exception {
        var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
        type.getMethod("compile", boolean.class).invoke(target, true);
        type.getMethod("waitForCompilation").invoke(target);
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target));
        var runtime = Truffle.getRuntime(); runtime.getClass().getMethod("bypassedInstalledCode", type).invoke(runtime, target);
    }
    private record Entry(String kind, String name) {}
    @Test void originalSignedWordAndTupleCasesMatchNativeBeforeAndOnFirstCompiledEntry() throws Exception {
        each(original(), (backend, program) -> {
            var rows = new ArrayList<String[]>();
            for (var line : Files.readAllLines(directory.resolve("literal-native-output.txt"))) rows.add(line.split("\t", -1));
            assertEquals(52, rows.size());
            for (var row : rows) {
                var entry = row[0].equals("int") ? "largeInt" : "largeWordCheck";
                assertEquals(Long.parseLong(row[2]), call(program, entry, Long.parseLong(row[1])), backend + "/" + row[0] + "/" + row[1]);
            }
            for (var entry : List.of(new Entry("int", "largeInt"), new Entry("word", "largeWordCheck"))) {
                compile(program.entryTarget("main:LargeLiteralCaseAudit." + entry.name()));
                long before = (Long) program.diagnostics().get("compiledEntries");
                String[] first = null;
                for (int i = rows.size() - 1; i >= 0; i--) if (rows.get(i)[0].equals(entry.kind())) { first = rows.get(i); break; }
                if (first == null) throw new NoSuchElementException("List contains no element matching the predicate.");
                assertEquals(Long.parseLong(first[2]), call(program, entry.name(), Long.parseLong(first[1])), backend + "/" + entry.name() + " first compiled call");
                assertTrue((Long) program.diagnostics().get("compiledEntries") > before);
                for (var row : rows) if (row[0].equals(entry.kind()))
                    assertEquals(Long.parseLong(row[2]), call(program, entry.name(), Long.parseLong(row[1])), backend + "/" + entry.name() + "/" + row[1]);
            }
        });
    }
    @Test void originalCaseForcesSharedInputExactlyOnceAndMemoizesFailure() throws Exception {
        each(original(), (backend, program) -> {
            var boxed = Objects.requireNonNull(call(program, "boxInt", 42L));
            assertEquals(115L, call(program, "largeLazy", boxed));
            int[] effects = {0};
            var value = new Thunk(new RootNode(null) {
                @Override public Object execute(VirtualFrame frame) { effects[0]++; return boxed; }
            }.getCallTarget(), null);
            // Demand the new thunk through the original boxed wrapper, then enter
            // the compiled numeric case whose branch lowering this test exercises.
            compile(program.entryTarget("main:LargeLiteralCaseAudit.largeInt"));
            long before = (Long) program.diagnostics().get("compiledEntries");
            assertEquals(115L, call(program, "largeLazy", value), backend + " first compiled demand");
            assertTrue((Long) program.diagnostics().get("compiledEntries") > before, backend + " first compiled demand");
            assertEquals(115L, call(program, "largeLazy", value)); assertEquals(1, effects[0]);
            var failed = new Thunk(new RootNode(null) {
                @Override public Object execute(VirtualFrame frame) { effects[0]++; throw new RuntimeFault("literal scrutinee failed"); }
            }.getCallTarget(), null);
            for (int i = 0; i < 2; i++) assertEquals("literal scrutinee failed", assertThrows(RuntimeFault.class, () -> call(program, "largeLazy", failed)).getMessage());
            assertEquals(2, effects[0]);
        });
    }
    private static List<Object> number(long n) { return List.of("lit", "int", Long.toString(n)); }
    private static List<Object> arm(String kind, long n, long result) {
        String encoded = switch (kind) {
            case "word" -> Long.toUnsignedString(n);
            case "word32" -> Integer.toUnsignedString((int) n);
            default -> Long.toString(n);
        };
        return List.of("lit", List.of(kind, encoded), List.of(), number(result));
    }
    private static Object carrier(boolean narrow, long value) {
        if (narrow) return (int) value;
        return value;
    }
    private static Map<String, Object> binding(String id, List<List<Object>> arms, Map<String, Object> parameter, Map<String, Object> integral, Map<String, Object> closure) {
        var selected = new LinkedHashMap<>(parameter); selected.put("id", "selected");
        return Map.of("id", "main:LargeLiteralCaseAudit." + id, "name", id, "lifted", true, "expr", List.of("lam", List.of(parameter),
            List.of("case", List.of("var", "x"), "selected", arms, Map.of("binder", selected, "rep", integral)), Map.of("rep", closure, "resultRep", integral)));
    }
    @Test void integralCaseBoundariesAndMissingDefaultPreserveCompiledBehavior() throws Exception {
        record Domain(String literal, String rep, boolean narrow) {}
        Map<String, Object> result = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
        Map<String, Object> closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
        for (var domain : List.of(new Domain("int", "IntRep", false), new Domain("word", "WordRep", false),
                new Domain("int32", "Int32Rep", true), new Domain("word32", "Word32Rep", true))) {
            Map<String, Object> integral = Map.of("kind", "long",
                "primReps", List.of(domain.rep), "evaluated", true);
            Map<String, Object> parameter = Map.of("id", "x", "name", "x", "lifted", false, "coercion", false, "rep", integral);
            // Deliberately unordered distinct labels exercise indexed selection.
            var arms = new ArrayList<List<Object>>();
            for (long i = 20; i >= 0; i--) arms.add(arm(domain.literal, i * 3, i + 100));
            long minimum = domain.narrow ? Integer.MIN_VALUE : Long.MIN_VALUE;
            long maximum = domain.narrow ? Integer.MAX_VALUE : Long.MAX_VALUE;
            arms.add(arm(domain.literal, minimum, 200)); arms.add(arm(domain.literal, maximum, 201));
            var defaults = new ArrayList<>(arms);
            defaults.add(java.util.Arrays.asList("default", null, List.of(), number(777)));
            each(Map.of("instrument", true, "bindings", List.of(binding("partial", arms, parameter, result, closure),
                binding("defaults", defaults, parameter, result, closure))), (backend, program) -> {
                for (String entry : List.of("partial", "defaults")) compile(program.entryTarget("main:LargeLiteralCaseAudit." + entry));
                long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                assertEquals(100L, call(program, "partial", carrier(domain.narrow, 0)), backend + "/" + domain.literal);
                assertEquals(before + 1, program.diagnostics().get("compiledEntries"), "Untouched partial must enter installed code");
                before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                assertEquals(777L, call(program, "defaults", carrier(domain.narrow, -1)));
                assertEquals(before + 1, program.diagnostics().get("compiledEntries"), "Untouched indexed miss enters installed default");
                for (long n = 0; n <= 20; n++) assertEquals(n + 100, call(program, "partial", carrier(domain.narrow, n * 3)), backend + "/" + domain.literal + "/" + n);
                assertEquals(200L, call(program, "partial", carrier(domain.narrow, minimum)));
                assertEquals(201L, call(program, "partial", carrier(domain.narrow, maximum)));
                assertTrue(Objects.toString(assertThrows(RuntimeFault.class, () -> call(program, "partial", carrier(domain.narrow, -1))).getMessage(), "").contains("Non-exhaustive Core case"));
            });
        }
    }
}
