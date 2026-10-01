// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.file.*;
import java.util.*;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thc.runtime.BytecodeProgram;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreBackendTestSupport.*;

/** Semantic parity at the public backend boundary, including cold compiled paths. */
class BytecodeBackendTest {
    @TempDir Path temporary;
    private final Path project = Path.of(System.getProperty("thc.projectRoot"));
    private final List<String> modules = list(project.resolve("build/core/THC.Prim.Test.cbd").toString(), project.resolve("build/core/Fixtures.cbd").toString());
    private record Example(String entry, long input, long expected) {}
    private List<Example> oracle() throws Exception {
        var rows = new ArrayList<Example>(); for (var line : Files.readAllLines(project.resolve("build/native/oracle.tsv"))) if (!line.isBlank()) {
            var fields = line.split("\t"); rows.add(new Example(fields[0], Long.parseLong(fields[1]), Long.parseLong(fields[2])));
        }
        return rows;
    }
    private Map<String, Object> diagnostics(Value fn) { return object(Json.parse(fn.getMember("diagnostics").asString())); }
    private long count(Value fn, String key) { return ((Number) diagnostics(fn).get(key)).longValue(); }
    private Value load(Context context, String entry) {
        var fn = Main.loadEntry(context, modules, "main:Fixtures." + entry, true, "bytecode"); assertEquals("bytecode", diagnostics(fn).get("backend"), "Must execute the requested backend"); return fn;
    }
    private void compileAndCheck(Value fn, long input, long expected) {
        for (int i = 0; i < 40; i++) assertEquals(expected, fn.execute(input).asLong()); assertTrue(fn.invokeMember("compile").asBoolean());
        long before = count(fn, "compiledEntries"); assertEquals(expected, fn.execute(input).asLong()); assertTrue(count(fn, "compiledEntries") > before, "Must enter installed guest code");
    }
    private List<Object> variable(String id) { return list("var", id, map()); }
    private List<Object> integer(long value) { return list("lit", "int", Long.toString(value), map()); }
    private List<Object> apply(List<Object> function, List<List<Object>> arguments, List<Boolean> lifted) { return list("app", function, arguments, lifted, false, false, map()); }
    private List<Object> primitiveHead(String name) { return list("prim", name, map()); }
    private List<Object> constructorHead(String name, int arity) { return list("con", name, arity, map()); }
    @SafeVarargs private final List<Object> primitive(String name, List<Object>... args) { return apply(primitiveHead(name), list(args), Collections.nCopies(args.length, false)); }
    private List<Object> lambda(String id, List<Object> body) { return list("lam", list(map("id", id, "name", id, "type", "Int#", "lifted", false, "coercion", false)), body, map()); }
    private List<Object> alternative(String tag, Object discriminator, List<String> ids, List<Object> body) { return list(tag, discriminator, ids, body, map("binders", ids.stream().map(id -> map("id", id)).toList())); }
    private List<Object> caseOf(List<Object> value, String binder, List<List<Object>> alternatives) { return list("case", value, binder, alternatives, map()); }
    private List<Object> let(Map<String, Object> binding, List<Object> body) { return list("let", false, list(binding), body, map()); }
    private Map<String, Object> binding(String id, List<Object> expr) { return binding(id, expr, 0); }
    private Map<String, Object> binding(String id, List<Object> expr, int arity) { return map("id", id, "name", id, "type", "Synthetic", "lifted", true, "arity", arity, "expr", expr); }
    private Map<String, Object> constructor(String id, List<Boolean> strict, List<Boolean> lifted) {
        return map("id", id, "name", id, "arity", strict.size(), "tag", 1, "kind", "boxed", "strictFields", strict, "fieldLifted", lifted,
            "fieldReps", lifted.stream().map(value -> list(Boolean.FALSE.equals(value) ? "IntRep" : "BoxedRep (Just Lifted)")).toList(),
            "fieldTypes", java.util.stream.IntStream.range(0, lifted.size()).mapToObj(index -> {
                var value = lifted.get(index);
                return map("kind", value ? "closure" : "long",
                    "evaluated", strict.get(index) || !value, "primReps", list(value ? "BoxedRep (Just Lifted)" : "IntRep"));
            }).toList());
    }
    private Map<String, Object> module(List<Map<String, Object>> bindings) { return module(bindings, list()); }
    private Map<String, Object> module(List<Map<String, Object>> bindings, List<Map<String, Object>> constructors) { return map("schema", 1, "ghc", "9.14.1", "unit", "main", "boundary", "pre-core", "module", "Synthetic.BytecodeParity", "bindings", bindings, "constructors", constructors); }
    private String request(Map<String, Object> data) throws Exception { return request(data, false); }
    private Path artifact(Map<String, Object> data) throws Exception {
        var path = temporary.resolve(UUID.randomUUID() + ".cbd"); CoreCbdFixtures.write(path, data);
        return path;
    }
    private String request(Map<String, Object> data, boolean diagnostic) throws Exception {
        return CoreModules.request(list(artifact(data).toString()), "entry", true, diagnostic, "bytecode");
    }
    private Value diagnosticEntry(Context context, Map<String, Object> data) throws Exception {
        var decoded = with(CoreCbdFixtures.read(artifact(data)), "instrument", true, "diagnosticUnsupported", true);
        context.initialize("thc"); context.enter();
        try {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
            return context.asValue(new EntryValue(new BytecodeProgram(language, decoded, false), "entry", 1));
        } finally { context.leave(); }
    }
    private List<Object> ignore(List<Object> value) { return caseOf(value, "ignored", list(alternative("default", null, list(), integer(41)))); }
    private List<Object> coldBranch(List<Object> value) { return caseOf(variable("input"), "choice", list(alternative("lit", list("int", "0"), list(), integer(41)), alternative("default", null, list(), value))); }
    private PolyglotException assertFailure(Value fn, String message, Object... args) {
        var failure = assertThrows(PolyglotException.class, () -> fn.execute(args)); assertTrue(failure.getMessage().contains(message), failure.getMessage()); return failure;
    }
    @Test void allExportedFixturesMatchNativeGhcBeforeAndAfterCompilation() throws Exception {
        try (var context = Main.executionContext(false)) {
            var grouped = new LinkedHashMap<String, List<Example>>(); for (var row : oracle()) grouped.computeIfAbsent(row.entry(), _ -> new ArrayList<>()).add(row);
            assertFalse(grouped.isEmpty(), "Native oracle must not be empty");
            for (var group : grouped.entrySet()) {
                var entry = group.getKey(); var rows = group.getValue(); var fn = load(context, entry);
                for (var row : rows) assertEquals(row.expected(), fn.execute(row.input()).asLong(), entry + "(" + row.input() + ") interpreted");
                for (int i = 0; i < 40; i++) { var row = rows.get(i % rows.size()); assertEquals(row.expected(), fn.execute(row.input()).asLong()); }
                assertTrue(fn.invokeMember("compile").asBoolean(), "Compile bytecode: " + entry); long before = count(fn, "compiledEntries");
                for (var row : rows) assertEquals(row.expected(), fn.execute(row.input()).asLong(), entry + "(" + row.input() + ") compiled");
                assertTrue(count(fn, "compiledEntries") > before, "Installed bytecode guest: " + entry); assertEquals(0L, count(fn, "unsupportedTraps"));
            }
        }
    }
    private record Named(String entry, long expected) {}
    @Test void compiledBaseCasesCanGrowIntoLongSelfMutualAndCapturedLoops() {
        try (var context = Main.executionContext(false)) {
            for (var example : list(new Named("sumLoop", 5_000_050_000L), new Named("mutualTail", 100_000L), new Named("selfMutualTail", 100_000L), new Named("recursiveCaf", 100_000L), new Named("capturedChangingEnv", 100_017L))) {
                var entry = example.entry(); var fn = load(context, entry); long base = entry.equals("capturedChangingEnv") ? 17L : 0L; compileAndCheck(fn, 0L, base);
                assertEquals(example.expected(), fn.execute(100_000L).asLong(), entry); assertTrue(fn.invokeMember("compile").asBoolean()); assertEquals(example.expected(), fn.execute(100_000L).asLong(), entry + " recompiled");
                assertEquals(base, fn.execute(0L).asLong(), entry + " fresh host call"); if (entry.equals("capturedChangingEnv")) assertEquals(24L, fn.execute(7L).asLong()); assertEquals(0L, count(fn, "blackholes"));
            }
        }
    }
    private String sharedThunkLabel() throws Exception {
        var decoded = CoreCbdFixtures.read(project.resolve("build/core/Fixtures.cbd"));
        var matches = objects(decoded.get("bindings")).stream().filter(binding -> "main:Fixtures.shared".equals(binding.get("id"))).toList();
        assertEquals(1, matches.size());
        var lambda = (List<?>) matches.getFirst().get("expr"); assertEquals("lam", lambda.getFirst());
        var body = (List<?>) lambda.get(2); assertEquals("let", body.getFirst());
        var bindings = objects(body.get(2)); assertEquals(1, bindings.size());
        return (String) bindings.getFirst().get("name");
    }
    @Test void sharingPartialAndOverapplicationPreserveTheirSemantics() throws Exception {
        try (var context = Main.executionContext(false)) {
            var shared = load(context, "shared"); assertEquals(120L, shared.execute(7L).asLong()); var labels = (Map<?, ?>) diagnostics(shared).get("thunkEvaluationsByLabel"); assertEquals(1L, labels.get(sharedThunkLabel()), labels.toString());
            // Caller-side forcing may forward x; its single recorded entry establishes sharing.
            var partial = load(context, "under"); assertEquals(14L, partial.execute(7L).asLong()); assertTrue(count(partial, "papAllocations") > 0); compileAndCheck(partial, 8L, 15L);
            var over = load(context, "over"); compileAndCheck(over, 0L, 13L); assertEquals(42L, over.execute(7L).asLong(), "A different returned function still accepts the surplus argument");
            var captured = load(context, "nestedCaptureThunk"); compileAndCheck(captured, 7L, 72L);
            for (long input : new long[]{3_000_000_000L, -3_000_000_000L, 0L, 7L}) assertEquals(input * input + 23L, captured.execute(input).asLong(), "Captures preserve 64-bit values and lexical ownership");
        }
    }
    private List<Map<String, Object>> parameters(List<String> ids) { return ids.stream().map(id -> map("id", id, "name", id, "type", "Int#", "lifted", false, "coercion", false)).toList(); }
    private List<Object> sum(List<String> ids) { var result = variable(ids.getFirst()); for (int i = 1; i < ids.size(); i++) result = primitive("+#", result, variable(ids.get(i))); return result; }
    private record Weighted(Map<String, Object> data, long coefficient) {}
    @Test void wideArgumentsCapturesAndConstructorFieldsPreserveEveryOperand() throws Exception {
        var ids = new ArrayList<String>(); var operands = new ArrayList<List<Object>>(); for (int i = 0; i < 10; i++) { ids.add("value" + i); operands.add(primitive("+#", variable("input"), integer(i))); }
        var wideFunction = binding("wide", list("lam", parameters(ids), sum(ids), map()), 10); var call = apply(variable("wide"), operands, Collections.nCopies(10, false));
        var arguments = module(list(wideFunction, binding("entry", lambda("input", call), 1)));
        var capturedBindings = new ArrayList<Map<String, Object>>(); for (int i = 0; i < ids.size(); i++) capturedBindings.add(with(binding(ids.get(i), operands.get(i)), "lifted", false));
        var closure = binding("closed", lambda("extra", primitive("+#", sum(ids), variable("extra"))), 1);
        var capturedBody = let(closure, apply(variable("closed"), list(variable("input")), list(false)));
        for (int i = capturedBindings.size() - 1; i >= 0; i--) capturedBody = let(capturedBindings.get(i), capturedBody);
        var captures = module(list(binding("entry", lambda("input", capturedBody), 1))); var wide = constructor("Wide", Collections.nCopies(10, false), Collections.nCopies(10, false));
        var constructed = apply(constructorHead("Wide", 10), operands, Collections.nCopies(10, false)); var readFields = caseOf(constructed, "wideValue", list(alternative("data", "Wide", ids, sum(ids))));
        var fields = module(list(binding("entry", lambda("input", readFields), 1)), list(wide));
        try (var context = Main.executionContext(false)) {
            for (var sample : list(new Weighted(arguments, 10L), new Weighted(captures, 11L), new Weighted(fields, 10L))) {
                var fn = context.eval("thc", request(sample.data())); compileAndCheck(fn, 7L, sample.coefficient() * 7L + 45L);
                for (long input : new long[]{3_000_000_000L, -3_000_000_000L, Long.MIN_VALUE, Long.MAX_VALUE, 0L, 7L}) assertEquals(sample.coefficient() * input + 45L, fn.execute(input).asLong(), "All ten operands survive primitive storage, capture and call boundaries");
            }
        }
    }
    @Test void aSelfTailCallThroughAPapRestoresBothItsPrefixAndNewArguments() throws Exception {
        var partial = apply(variable("worker"), list(primitive("-#", variable("remaining"), integer(1))), list(false));
        var continueWithPap = caseOf(partial, "next", list(alternative("default", null, list(), apply(variable("next"), list(primitive("+#", variable("total"), variable("remaining"))), list(false)))));
        var body = caseOf(primitive("<=#", variable("remaining"), integer(0)), "finished", list(alternative("lit", list("int", "1"), list(), variable("total")), alternative("default", null, list(), continueWithPap)));
        var worker = binding("worker", list("lam", parameters(list("remaining", "total")), body, map()), 2);
        var entry = binding("entry", lambda("input", apply(variable("worker"), list(variable("input"), integer(0)), list(false, false))), 1);
        try (var context = Main.executionContext(false)) {
            var fn = context.eval("thc", request(module(list(worker, entry)))); compileAndCheck(fn, 0L, 0L); assertEquals(5_000_050_000L, fn.execute(100_000L).asLong());
            assertTrue(count(fn, "papAllocations") > 0, "Exercise a supplied prefix at the self-call site"); assertTrue(fn.invokeMember("compile").asBoolean()); long before = count(fn, "compiledEntries");
            for (long input : new long[]{100_000L, 7L, 0L, 10_000L}) assertEquals(input * (input + 1) / 2, fn.execute(input).asLong()); assertTrue(count(fn, "compiledEntries") > before);
        }
    }
    @Test void unusedBottomStaysLazyAndFailedThunksAreNotReentered() {
        try (var context = Main.executionContext(false)) {
            for (var entry : list("lazyArgument", "lazyField")) { var fn = load(context, entry); compileAndCheck(fn, 123L, 123L); assertEquals(0L, count(fn, "blackholes")); }
            var bottom = load(context, "blackhole"); assertFailure(bottom, "Blackhole", 0L); long evaluated = count(bottom, "thunkEvaluations"); assertEquals(1L, count(bottom, "blackholes"));
            assertFailure(bottom, "Blackhole", 0L); assertEquals(evaluated, count(bottom, "thunkEvaluations"), "Failed update is memoized"); assertEquals(1L, count(bottom, "blackholes"));
        }
    }
    @Test void strictConstructorWorkersForceOnlyAtFullSaturationIncludingColdCompiledPaths() throws Exception {
        var strict = constructor("Strict", list(true), list(true)); var lazy = constructor("Lazy", list(false), list(true));
        try (var context = Main.executionContext(false)) {
            for (boolean firstClass : list(false, true)) for (var id : list("Strict", "Lazy")) {
                List<Object> worker = constructorHead(id, 1); var constructed = apply(firstClass ? variable("worker") : worker, list(variable("bottom")), list(true));
                var data = module(list(binding("bottom", variable("bottom")), binding("worker", worker, 1), binding("entry", lambda("input", coldBranch(ignore(constructed))), 1)), list(strict, lazy));
                var fn = context.eval("thc", request(data)); compileAndCheck(fn, 0L, 41L);
                if (id.equals("Lazy")) { assertEquals(41L, fn.execute(1L).asLong()); assertEquals(0L, count(fn, "blackholes")); }
                else { assertFailure(fn, "Blackhole", 1L); long evaluated = count(fn, "thunkEvaluations"); assertFailure(fn, "Blackhole", 1L); assertEquals(evaluated, count(fn, "thunkEvaluations")); assertEquals(1L, count(fn, "blackholes")); }
            }
            var pair = constructor("Pair", list(true, false), list(true, true)); var partial = apply(constructorHead("Pair", 2), list(variable("bottom")), list(true));
            var saturated = ignore(apply(variable("pap"), list(variable("bottom")), list(true))); var body = caseOf(partial, "pap", list(alternative("default", null, list(), coldBranch(saturated))));
            var fn = context.eval("thc", request(module(list(binding("bottom", variable("bottom")), binding("entry", lambda("input", body), 1)), list(pair))));
            compileAndCheck(fn, 0L, 41L); assertTrue(count(fn, "papAllocations") > 0); assertEquals(0L, count(fn, "blackholes"), "A strict argument is lazy while the constructor is partial"); assertFailure(fn, "Blackhole", 1L);
        }
    }
    private record Gap(List<Object> expression, String message) {}
    @Test void unsupportedCodeRejectsAtLoadOrTrapsOnlyWhenTheDiagnosticBranchIsEntered() throws Exception {
        var gaps = list(new Gap(variable("Missing.libraryBody"), "Unresolved external binding Missing.libraryBody"), new Gap(primitive("futurePrim#", variable("input")), "Unsupported primitive futurePrim#"));
        try (var context = Main.executionContext(false)) {
            for (var gap : gaps) {
                var data = module(list(binding("entry", lambda("input", coldBranch(gap.expression())), 1))); var rejected = assertThrows(PolyglotException.class, () -> context.eval("thc", request(data)));
                boolean unlinked = gap.expression().getFirst().equals("var");
                assertTrue(rejected.getMessage().contains(unlinked ? "Unlinked Core globals: Missing.libraryBody referenced by entry" : gap.message()), rejected.getMessage());
                // Public CBD loading strictly admits bindings. The decoded-model seam
                // retains the backend's separate diagnostic cold-path controls.
                var fn = diagnosticEntry(context, data); compileAndCheck(fn, 0L, 41L);
                assertEquals("diagnostic-traps", diagnostics(fn).get("unsupportedPolicy")); assertEquals(0L, count(fn, "unsupportedTraps")); assertFalse(((List<?>) diagnostics(fn).get("deferredUnsupported")).isEmpty());
                assertFailure(fn, "Diagnostic unsupported path reached: " + gap.message(), 1L); assertEquals(1L, count(fn, "unsupportedTraps"));
            }
            var malformed = apply(primitiveHead("+#"), list(variable("input")), list()); var bad = module(list(binding("entry", lambda("input", coldBranch(malformed)), 1)));
            var error = assertThrows(PolyglotException.class, () -> context.eval("thc", request(bad, true))); assertTrue(error.getMessage().contains("representation flag count mismatch"), error.getMessage());
            var uncertain = constructor("Uncertain", list(true), list(true)); var data = module(list(binding("entry", ignore(apply(constructorHead("Uncertain", 1), list(integer(0)), list((Boolean) null))))), list(uncertain));
            var levity = assertThrows(PolyglotException.class, () -> context.eval("thc", request(data))); assertTrue(levity.getMessage().contains("Unknown argument levity without a boxed pointer representation"), levity.getMessage());
        }
    }
    @Test void managedManagedAddressesPreserveUnsignedBytesNulsAndBoundsAfterCompilation() throws Exception {
        var shifted = primitive("plusAddr#", list("lit", "string-bytes", "ff4100", map()), variable("input")); var body = primitive("indexCharOffAddr#", shifted, integer(0));
        try (var context = Main.executionContext(false)) {
            var fn = context.eval("thc", request(module(list(binding("entry", lambda("input", body), 1))))); long[] expected = {255L, 65L, 0L, 0L};
            for (int offset = 0; offset < expected.length; offset++) assertEquals(expected[offset], fn.execute(offset).asLong()); compileAndCheck(fn, 0L, 255L);
            for (int offset = 0; offset < expected.length; offset++) assertEquals(expected[offset], fn.execute(offset).asLong()); assertFailure(fn, "outside its backing storage", 4L);
            var fakeRead = primitive("indexCharOffAddr#", variable("input"), integer(0)); var fake = context.eval("thc", request(module(list(binding("entry", lambda("input", fakeRead), 1))))); assertFailure(fake, "Expected a managed literal Addr#", 0L);
        }
    }
    @Test void coldRaiseKeepsItsBottomPayloadLazyAndMemoizesTheGuestFailure() throws Exception {
        var raised = apply(primitiveHead("raise#"), list(variable("payload")), list(true)); var data = module(list(binding("payload", variable("payload")), binding("failure", raised), binding("entry", lambda("input", coldBranch(variable("failure"))), 1)));
        try (var context = Main.executionContext(false)) {
            var fn = context.eval("thc", request(data)); compileAndCheck(fn, 0L, 41L); assertEquals(0L, count(fn, "thunkEvaluations"));
            var first = assertFailure(fn, "Haskell exception (payload retained lazily)", 1L); assertTrue(first.isGuestException()); assertFalse(first.isHostException()); assertEquals(0L, count(fn, "blackholes"), "raise# must not enter its bottom payload");
            long evaluated = count(fn, "thunkEvaluations"); assertEquals(1L, evaluated, "Only the failing CAF is entered"); assertTrue(assertFailure(fn, "Haskell exception (payload retained lazily)", 1L).isGuestException());
            assertEquals(evaluated, count(fn, "thunkEvaluations")); assertEquals(0L, count(fn, "blackholes"));
        }
    }
    @Test void backendSelectionAndMutableProgramStateAreIsolatedWithinASharedEngine() {
        try (var engine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").build();
                var first = Context.newBuilder("thc").engine(engine).build(); var second = Context.newBuilder("thc").engine(engine).build()) {
            var ast = Main.loadEntry(first, modules, "main:Fixtures.shared", true, "ast"); var bytecode = load(first, "shared"); var otherContext = load(second, "shared");
            assertEquals("ast", diagnostics(ast).get("backend")); assertFalse(ast.hasMember("bytecode")); assertTrue(bytecode.hasMember("bytecode")); var instructions = bytecode.getMember("bytecode").asString();
            assertFalse(instructions.isBlank()); assertTrue(instructions.contains("c.EnterRoot"), "Dump must contain generated Core instructions"); assertTrue(instructions.contains("load.argument"), "Dump must include the bytecode argument loads");
            assertEquals(120L, ast.execute(7L).asLong()); assertTrue(count(ast, "thunkEvaluations") > 0); assertEquals(0L, count(bytecode, "thunkEvaluations")); assertEquals(0L, count(otherContext, "thunkEvaluations"));
            assertEquals(222L, bytecode.execute(10L).asLong()); assertEquals(0L, count(otherContext, "thunkEvaluations")); assertEquals(30L, otherContext.execute(2L).asLong()); assertEquals(120L, ast.execute(7L).asLong());
            var bad = assertThrows(PolyglotException.class, () -> first.eval("thc", CoreModules.request(modules, "under", true, false, "unknown-backend", true, false, null, null, false)));
            assertTrue(bad.getMessage().contains("Unknown THC backend: unknown-backend"), bad.getMessage()); assertEquals(14L, load(first, "under").execute(7L).asLong());
        }
    }
}
