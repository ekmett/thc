// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.nodes.RootNode;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.graalvm.polyglot.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreExecutionTestSupport.*;

class RuntimeTest {
    @TempDir Path temporary;
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final List<String> modules = list("THC.Prim.Test", "Fixtures").stream().map(name -> root.resolve("build/core/" + name + ".cbd").toString()).toList();
    record Example(String entry, long input, long expected) {}
    private List<Example> oracle() throws Exception {
        var result = new ArrayList<Example>();
        for (String line : Files.readAllLines(root.resolve("build/native/oracle.tsv"))) if (!line.isBlank()) {
            var p = line.split("\t", -1); result.add(new Example(p[0], Long.parseLong(p[1]), Long.parseLong(p[2])));
        }
        return result;
    }
    private Map<String, Object> diagnostics(Value value) { return object(Json.parse(value.getMember("diagnostics").asString())); }
    private long count(Value value, String key) { return ((Number) diagnostics(value).get(key)).longValue(); }
    private String thunkLabel(String owner, String name) throws Exception {
        var path = root.resolve("build/core/Fixtures.cbd");
        var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        try (var file = new CoreCompactFile(path, hash, true)) {
            var records = new CoreCompactRecords(file, path.toString());
            var binding = records.binding(Objects.requireNonNull(file.lookup("main:Fixtures." + owner)));
            var matches = new HashSet<String>();
            thunkLabels(binding, name, matches);
            assertEquals(1, matches.size(), owner + "/" + name + " must identify one actual local thunk");
            return matches.iterator().next();
        }
    }
    private void thunkLabels(Object value, String name, Set<String> matches) {
        if (value instanceof Map<?, ?> fields) {
            if (fields.containsKey("expr") && fields.get("id") instanceof String id && id.startsWith("\u0000compact-local:")
                    && fields.get("compactOrigin") instanceof CoreCompactRecords.Origin origin) {
                long ordinal = Long.parseLong(id.substring(id.lastIndexOf(':') + 1));
                if (name.equals(origin.debug().name(origin.bindingOffset(), ordinal + 1))) matches.add(id);
            }
            fields.values().forEach(child -> thunkLabels(child, name, matches));
        } else if (value instanceof List<?> values) values.forEach(child -> thunkLabels(child, name, matches));
    }
    @Test void exportedHaskellMatchesNativeGhcBeforeAndAfterGuestCompilation() throws Exception {
        try (var context = Main.executionContext(false)) {
            var grouped = new LinkedHashMap<String, List<Example>>();
            for (var row : oracle()) grouped.computeIfAbsent(row.entry, ignored -> new ArrayList<>()).add(row);
            for (var group : grouped.entrySet()) {
                String entry = group.getKey(); var rows = group.getValue(); var fn = Main.loadEntry(context, modules, "main:Fixtures." + entry);
                for (var r : rows) assertEquals(r.expected, fn.execute(r.input).asLong(), entry + "(" + r.input + ") interpreted");
                for (int i = 0; i < 40; i++) { var r = rows.get(i % rows.size()); assertEquals(r.expected, fn.execute(r.input).asLong()); }
                assertTrue(fn.invokeMember("compile").asBoolean(), "Guest compilation: " + entry); long before = count(fn, "compiledEntries");
                for (var r : rows) assertEquals(r.expected, fn.execute(r.input).asLong(), entry + "(" + r.input + ") compiled");
                assertTrue(count(fn, "compiledEntries") > before, "Must execute installed guest code: " + entry);
                System.out.println("VERIFIED_GUEST " + entry + " " + Json.stringify(diagnostics(fn)));
            }
        }
    }
    /** Consumes the shared pre-Tidy CBD/native oracle; publishes no products.
     * Real newtype casts must erase without changing the scalar carrier. */
    @Test void genuineScalarNewtypeIdentityPreservesNativeFullWidthInBothBackends() throws Exception {
        var bindings = objects(CoreCbdFixtures.read(root.resolve("build/core/Fixtures.cbd")).get("bindings"));
        for (String name : list("wrapRaw", "unwrapRaw", "scalarCastEntry")) {
            var binding = bindings.stream().filter(b -> ("main:Fixtures." + name).equals(b.get("id"))).findFirst().orElseThrow();
            var signature = Objects.requireNonNull(CoreRepresentations.knownFunctionSignature((List<?>) binding.get("expr"), bindings));
            assertEquals(list(list("IntRep")), signature.inputs().stream().map(CoreRepresentation::getPrimReps).toList(), name);
            assertEquals(list("IntRep"), signature.result().getPrimReps(), name);
        }
        var rows = oracle().stream().filter(row -> row.entry.equals("scalarCastEntry")).toList();
        var interpreted = rows.stream().filter(row -> row.input == 7L).findFirst().orElseThrow();
        var firstInstalled = rows.stream().filter(row -> row.input == Long.MIN_VALUE).findFirst().orElseThrow();
        assertTrue(rows.stream().anyMatch(row -> row.input == Long.MAX_VALUE));
        assertTrue(rows.stream().anyMatch(row -> row.input == 4_294_967_311L));
        for (var row : rows) assertEquals(row.input, row.expected, "Native GHC scalar identity");
        for (String backend : list("ast", "bytecode")) try (var context = Main.executionContext(false)) {
            var function = context.eval("thc", CoreModules.request(modules, "main:Fixtures.scalarCastEntry", true, false, backend));
            assertEquals(interpreted.expected, function.execute(interpreted.input).asLong(), backend);
            assertTrue(function.invokeMember("compile").asBoolean(), backend);
            long before = count(function, "compiledEntries");
            assertEquals(firstInstalled.expected, function.execute(firstInstalled.input).asLong(), backend + " first installed call");
            assertTrue(count(function, "compiledEntries") > before, backend);
            for (var row : rows) assertEquals(row.expected, function.execute(row.input).asLong(), backend + "/" + row.input);
            var installed = object(diagnostics(function).get("explicitCompilation"));
            assertEquals(true, installed.get("sameTargets"), backend);
            assertEquals(true, installed.get("validLastTier"), backend);
            assertEquals(0L, count(function, "blackholes"), backend);
        }
    }
    @Test void sharingPapAndOverapplicationHaveObservableRuntimeCoverage() throws Exception {
        try (var context = Main.executionContext(false)) {
            var shared = Main.loadEntry(context, modules, "main:Fixtures.shared"); assertEquals(120L, shared.execute(7L).asLong());
            assertTrue(count(shared, "thunkEvaluations") > 0);
            var entries = (Map<?, ?>) diagnostics(shared).get("thunkEvaluationsByLabel");
            assertEquals(1L, ((Number) entries.get(thunkLabel("shared", "x"))).longValue(), "Shared Core binding x entered exactly once");
            var under = Main.loadEntry(context, modules, "main:Fixtures.under"); assertEquals(14L, under.execute(7L).asLong());
            assertTrue(count(under, "papAllocations") > 0); long updates = count(under, "thunkEvaluations");
            assertEquals(15L, under.execute(8L).asLong());
            assertEquals(updates, count(under, "thunkEvaluations"), "GHC-known WHNF constructors and PAPs need no per-call update thunk");
            var over = Main.loadEntry(context, modules, "main:Fixtures.over"); assertEquals(13L, over.execute(0L).asLong()); assertEquals(42L, over.execute(7L).asLong());
        }
    }
    @Test void unusedBottomRemainsLazyAndDemandedBottomBlackholes() {
        try (var context = Main.executionContext(false)) {
            for (String entry : list("lazyArgument", "lazyField")) {
                var fn = Main.loadEntry(context, modules, "main:Fixtures." + entry); assertEquals(123L, fn.execute(123L).asLong()); assertEquals(0L, count(fn, "blackholes"));
            }
            var bottom = Main.loadEntry(context, modules, "main:Fixtures.blackhole");
            var error = assertThrows(PolyglotException.class, () -> bottom.execute(0L));
            assertTrue(Objects.toString(error.getMessage(), "").contains("Blackhole"), error.getMessage()); assertEquals(1L, count(bottom, "blackholes"));
            var repeated = assertThrows(PolyglotException.class, () -> bottom.execute(0L)); assertTrue(Objects.toString(repeated.getMessage(), "").contains("Blackhole"));
            assertEquals(1L, count(bottom, "blackholes"), "Failed thunk rethrows its memoized failure");
        }
    }
    @Test void cadenzaTailLoopsAndProductiveCafUseBoundedHostStack() {
        try (var context = Main.executionContext(false)) {
            var sum = Main.loadEntry(context, modules, "main:Fixtures.sumLoop"); assertEquals(5_000_050_000L, sum.execute(100_000L).asLong());
            assertTrue(count(sum, "selfTailReentries") >= 100_000L); assertEquals(0L, count(sum, "tailBounces"));
            var cyclic = Main.loadEntry(context, modules, "main:Fixtures.recursiveCaf"); assertEquals(100_000L, cyclic.execute(100_000L).asLong());
            var list = Main.loadEntry(context, modules, "main:Fixtures.caseList"); assertEquals(50_005_000L, list.execute(10_000L).asLong());
        }
    }
    @Test void boundedDispatchCacheSurvivesMoreThanThreeTargets() throws Exception {
        try (var context = Main.executionContext(false)) {
            var fn = Main.loadEntry(context, modules, "main:Fixtures.cacheSaturation");
            var rows = oracle().stream().filter(row -> row.entry.equals("cacheSaturation")).toList(); assertFalse(rows.isEmpty());
            for (int i = 0; i < 4; i++) for (var r : rows) assertEquals(r.expected, fn.execute(r.input).asLong());
            assertTrue(count(fn, "indirectCalls") > 0, "Fixture must reach megamorphic fallback");
            fn.invokeMember("compile"); for (var r : rows) assertEquals(r.expected, fn.execute(r.input).asLong());
            var mutual = Main.loadEntry(context, modules, "main:Fixtures.mutualTail"); assertEquals(100_000L, mutual.execute(100_000L).asLong());
            var mixed = Main.loadEntry(context, modules, "main:Fixtures.selfMutualTail"); assertEquals(100_000L, mixed.execute(100_000L).asLong());
        }
    }
    @Test void selfTailCallsRefreshChangingPrimitiveCapturesAndReplayOriginalCaf() {
        try (var context = Main.executionContext(false)) {
            var fn = Main.loadEntry(context, modules, "main:Fixtures.capturedChangingEnv"); assertEquals(10_017L, fn.execute(10_000L).asLong());
            assertTrue(fn.invokeMember("compile").asBoolean()); long before = count(fn, "selfTailReentries"), bounces = count(fn, "tailBounces");
            assertEquals(100_017L, fn.execute(100_000L).asLong()); assertTrue(count(fn, "selfTailReentries") - before >= 100_000L);
            assertEquals(bounces, count(fn, "tailBounces"), "Direct self entry needs no tail packet");
            long updates = count(fn, "thunkEvaluations");
            assertEquals(24L, fn.execute(7L).asLong()); assertEquals(17L, fn.execute(0L).asLong()); assertEquals(10_017L, fn.execute(10_000L).asLong());
            assertEquals(updates, count(fn, "thunkEvaluations"), "Safe constructor arguments need no update thunk on warmed continuation steps"); assertEquals(0L, count(fn, "blackholes"));
        }
    }
    private long labelCount(Value value, String label) {
        Object count = ((Map<?, ?>) diagnostics(value).get("thunkEvaluationsByLabel")).get(label);
        return count instanceof Number number ? number.longValue() : 0;
    }
    @Test void recursiveCapturedCellsAndEscapedThunksKeepTheirOwnLexicalValues() throws Exception {
        try (var context = Main.executionContext(false)) {
            var mutual = Main.loadEntry(context, modules, "main:Fixtures.localMutualClosures"); assertEquals(20_040L, mutual.execute(10_000L).asLong());
            assertTrue(mutual.invokeMember("compile").asBoolean());
            for (long input : new long[]{10_001L, -5L, 0L, 10_000L}) assertEquals(2L * input + 40L, mutual.execute(input).asLong());
            var escaped = Main.loadEntry(context, modules, "main:Fixtures.nestedCaptureThunk");
            for (int i = 0; i < 20; i++) escaped.execute((long) i).asLong();
            assertTrue(escaped.invokeMember("compile").asBoolean());
            String outerLabel = thunkLabel("escapingFactory", "outer"), middleLabel = thunkLabel("escapingFactory", "middle");
            long outer = labelCount(escaped, outerLabel), middle = labelCount(escaped, middleLabel);
            long[] inputs = {3_000_000_000L, -3_000_000_000L, 7, 0, 13, -5, 7};
            for (long input : inputs) assertEquals(input * input + 23L, escaped.execute(input).asLong());
            assertEquals((long) inputs.length, labelCount(escaped, outerLabel) - outer); assertEquals((long) inputs.length, labelCount(escaped, middleLabel) - middle);
            assertEquals(0L, count(escaped, "blackholes"));
        }
    }
    /** Raw roots isolate packet/PAP semantics; these are not Haskell-oracle rows. */
    private static final class ApplicationDriver extends RootNode {
        @Child private Dispatch dispatch;
        ApplicationDriver(int argumentCount, Metrics metrics) { super(null); dispatch = Dispatch.create(argumentCount, false, metrics); }
        @Override public Object execute(VirtualFrame frame) { return dispatch.execute(frame, (Closure) frame.getArguments()[0], (Object[]) frame.getArguments()[1]); }
        Object apply(Closure function, Object... arguments) { return Calls.target(getCallTarget(), new Object[]{function, arguments}); }
    }
    private RootCallTarget decimalTarget() {
        return new RootNode(null) {
            @Override public Object execute(VirtualFrame frame) {
                long result = 0; for (int i = 1; i < frame.getArguments().length; i++) result = result * 10L + (Long) frame.getArguments()[i]; return result;
            }
        }.getCallTarget();
    }
    private Closure overapplied(int consumed, int suppliedCount) {
        var resultTarget = decimalTarget();
        var target = new RootNode(null) {
            @Override public Object execute(VirtualFrame frame) {
                long prefix = 0; for (int i = 1; i < frame.getArguments().length; i++) prefix = prefix * 10L + (Long) frame.getArguments()[i];
                return new Closure(null, new Object[]{prefix}, suppliedCount - consumed, resultTarget);
            }
        }.getCallTarget();
        return new Closure(null, new Object[0], consumed, target);
    }
    @Test void papPrefixesSurviveRepeatedUnderapplicationAndMegamorphicOverapplication() {
        var metrics = new Metrics(true); var one = new ApplicationDriver(1, metrics); var two = new ApplicationDriver(2, metrics);
        var original = new Closure(null, new Object[0], 4, decimalTarget()); var first = (Closure) one.apply(original, 1L); var second = (Closure) one.apply(first, 2L);
        assertEquals(1_234L, two.apply(second, 3L, 4L)); assertEquals(1_289L, two.apply(second, 8L, 9L), "PAP prefixes remain reusable");
        assertTrue(metrics.getPapAllocations() >= 2);
        var initialPap = (Closure) one.apply(overapplied(2, 4), 1L);
        assertEquals(1_234L, new ApplicationDriver(3, metrics).apply(initialPap, 2L, 3L, 4L));
        var six = new ApplicationDriver(6, metrics); var functions = new ArrayList<Closure>();
        for (int i = 1; i <= 5; i++) functions.add(overapplied(i, 6));
        var revisit = new ArrayList<>(functions); revisit.addAll(functions.reversed());
        for (var function : revisit) assertEquals(123_456L, six.apply(function, 1L, 2L, 3L, 4L, 5L, 6L));
        assertTrue(metrics.getIndirectCalls() > 0, "Overapplication must reach its generic fallback");
    }
    @Test void constructorLayoutsPreservePrimitiveWidthObjectIdentityAndVoidSlots() throws Exception {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var mixed = new DataLayout(language, "Synthetic.Mixed", "Mixed", new String[]{"VoidRep", "IntRep", "LiftedRep"}, new Class<?>[3]);
                var marker = new Object(); var builder = FrameDescriptor.newBuilder();
                int numberSlot = builder.addSlot(FrameSlotKind.Illegal, "number", null), objectSlot = builder.addSlot(FrameSlotKind.Illegal, "object", null);
                var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], builder.build());
                for (long number : new long[]{3_000_000_000L, -3_000_000_000L, Long.MIN_VALUE, Long.MAX_VALUE}) {
                    var value = mixed.create(new Object[]{thc.runtime.Unit.INSTANCE, number, marker});
                    assertSame(thc.runtime.Unit.INSTANCE, mixed.read(value, 0), "Zero-width Core slots retain their logical index");
                    assertEquals(number, mixed.readLong(value, 1)); assertSame(marker, mixed.read(value, 2), "Lifted fields retain lazy payload identity");
                    mixed.restore(value, 1, frame, numberSlot); mixed.restore(value, 2, frame, objectSlot);
                    assertTrue(frame.isLong(numberSlot), "Case restoration must preserve primitive Long slots");
                    assertEquals(number, frame.getLong(numberSlot)); assertSame(marker, frame.getObject(objectSlot));
                }
                var reference = new DataLayout(language, "Synthetic.Reference", "Reference", new String[]{"LiftedRep"}, new Class<?>[1]);
                var objectValue = reference.create(new Object[]{marker}); assertSame(marker, reference.read(objectValue, 0));
                assertThrows(RuntimeFault.class, () -> reference.readLong(objectValue, 0));
                assertThrows(RuntimeFault.class, () -> mixed.create(new Object[]{thc.runtime.Unit.INSTANCE, 3, marker}));
            } finally { context.leave(); }
        }
    }
    @Test void constructorLayoutsRejectCrossConstructorReadsAndShareNullaryValues() {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var first = new DataLayout(language, "Synthetic.First", "Choice", new String[]{"IntRep"}, new Class<?>[1]);
                var second = new DataLayout(language, "Synthetic.Second", "Choice", new String[]{"IntRep"}, new Class<?>[1]);
                var value = first.create(new Object[]{3_000_000_000L}); assertNotSame(first, second);
                assertThrows(RuntimeFault.class, () -> second.read(value, 0)); assertThrows(RuntimeFault.class, () -> second.readLong(value, 0));
                var nil = new DataLayout(language, "Synthetic.Nil", "Nil", new String[0], new Class<?>[0]);
                var end = new DataLayout(language, "Synthetic.End", "End", new String[0], new Class<?>[0]);
                assertSame(nil.create(new Object[0]), nil.create(new Object[0]), "Nullary constructors are per-layout singletons");
                assertNotSame(nil.create(new Object[0]), end.create(new Object[0]));
            } finally { context.leave(); }
        }
    }
    private final Map<String, Object> integerRep = map("kind", "long", "evaluated", true, "primReps", list("IntRep"));
    private final Map<String, Object> dataRep = map("kind", "data", "evaluated", false, "primReps", list("BoxedRep (Just Lifted)"));
    private final Map<String, Object> choiceRep = with(dataRep, "evaluated", true);
    private final Map<String, Object> closureRep = map("kind", "closure", "evaluated", true, "primReps", list("BoxedRep (Just Lifted)"));
    private List<Object> variable(String id, Map<String, Object> rep) { return list("var", id, map("rep", rep)); }
    private List<Object> integer(long number) { return list("lit", "int", Long.toString(number), map("rep", integerRep)); }
    private List<Object> primitive(String name, List<Object> first, List<Object> second) {
        return list("app", list("prim", name, map()), list(first, second), list(false, false), false, false, map("rep", integerRep));
    }
    private Map<String, Object> constructor(String id) {
        return map("id", id, "name", id.substring(id.lastIndexOf('.') + 1), "arity", 1, "tag", id.endsWith("LeftChoice") ? 1 : 2,
            "kind", "boxed", "strictFields", list(false), "fieldLifted", list(false), "fieldReps", list(list("IntRep")), "fieldTypes", list(integerRep));
    }
    private Map<String, Object> binder(String id) { return map("id", id, "name", id, "type", "Int#", "lifted", false, "coercion", false, "rep", integerRep); }
    private List<Object> constructed(String id) {
        return list("app", list("con", id, 1, map("rep", closureRep)), list(variable("n", integerRep)), list(false), true, true, map("rep", choiceRep));
    }
    private void checkChoices(Value function, long[] inputs) {
        for (long input : inputs) assertEquals(input <= 0 ? input + 101 : input - 202, function.execute(input).asLong());
    }
    @Test void constructorCasesDistinguishEqualArityAlternativesBeforeAndAfterCompilation() throws Exception {
        String left = "Synthetic.LeftChoice", right = "Synthetic.RightChoice";
        var chooseBody = list("case", primitive("<=#", variable("n", integerRep), integer(0)), "test", list(
            list("default", null, list(), constructed(right), map("binders", list())),
            list("lit", list("int", "1"), list(), constructed(left), map("binders", list()))),
            map("rep", choiceRep, "binder", binder("test")));
        var choose = map("id", "choose", "name", "choose", "arity", 1, "lifted", true, "rep", closureRep,
            "expr", list("lam", list(binder("n")), chooseBody, map("rep", closureRep, "resultRep", choiceRep)));
        var entryBody = list("case", list("app", variable("choose", closureRep), list(variable("input", integerRep)), list(false), false, false, map("rep", choiceRep)), "chosen", list(
            list("data", left, list("leftField"), primitive("+#", variable("leftField", integerRep), integer(101)), map("binders", list(binder("leftField")))),
            list("data", right, list("rightField"), primitive("-#", variable("rightField", integerRep), integer(202)), map("binders", list(binder("rightField"))))),
            map("rep", integerRep, "binder", map("id", "chosen", "name", "chosen", "type", "Choice", "lifted", true, "coercion", false, "rep", choiceRep)));
        var entry = map("id", "entry", "name", "entry", "arity", 1, "lifted", true, "rep", closureRep,
            "expr", list("lam", list(binder("input")), entryBody, map("rep", closureRep, "resultRep", integerRep)));
        String request = request(map("schema", 1, "ghc", "9.14.1", "unit", "main", "boundary", "pre-core", "module", "Synthetic",
            "constructors", list(constructor(left), constructor(right)), "bindings", list(choose, entry)));
        try (var context = Main.executionContext(false)) {
            var function = context.eval("thc", request); long[] inputs = {-3_000_000_000L, 3_000_000_000L, 0, -1, 1};
            checkChoices(function, inputs); for (int i = 0; i < 8; i++) checkChoices(function, inputs);
            assertTrue(function.invokeMember("compile").asBoolean()); checkChoices(function, inputs);
        }
    }
    @Test void repeatedHostEntryKeepsGuestAndInteropCompilable() {
        try (var context = Main.executionContext(false)) {
            var fn = Main.loadEntry(context, modules, "main:Fixtures.under", false);
            for (int i = 0; i < 40; i++) fn.execute(100L + (i & 15)).asLong();
            assertTrue(fn.invokeMember("compile").asBoolean());
            for (int i = 0; i < 20_000; i++) { long input = 100L + (i & 15); assertEquals(input + 7L, fn.execute(input).asLong()); }
        }
    }
    private String request(Map<String, Object> module) throws Exception {
        var output = CoreCbdFixtures.write(temporary.resolve(UUID.randomUUID() + ".cbd"), module);
        return CoreModules.request(list(output.toString()), "entry", true, false, null);
    }
    private String aliasRequest(Map<String, Object> binder, List<Object> body, Map<String, Object> resultRep) throws Exception {
        return request(map("schema", 1, "ghc", "9.14.1", "unit", "main", "boundary", "pre-core", "module", "Synthetic",
            "constructors", list(), "bindings", list(map("id", "entry", "name", "entry", "arity", 0, "lifted", true, "rep", resultRep,
                "expr", list("let", true, list(binder), body, map("rep", resultRep))))));
    }
    @Test void recursiveAliasStaysLazyUntilDemanded() throws Exception {
        var binder = map("id", "x", "name", "x", "type", "Int", "lifted", true, "arity", 0, "rep", dataRep, "expr", variable("x", dataRep));
        try (var context = Main.executionContext(false)) {
            var unused = context.eval("thc", aliasRequest(binder, integer(42), integerRep)); assertEquals(42L, unused.execute().asLong());
            var used = context.eval("thc", aliasRequest(binder, variable("x", dataRep), dataRep)); var error = assertThrows(PolyglotException.class, () -> used.execute());
            assertTrue(Objects.toString(error.getMessage(), "").contains("Blackhole"), error.getMessage());
        }
    }
    @Test void hostKernelAbiRejectsNonIntegralArgumentsAndWrongArity() {
        try (var context = Main.executionContext(false)) {
            var fn = Main.loadEntry(context, modules, "main:Fixtures.sumLoop"); assertEquals(6L, fn.execute(3).asLong());
            assertThrows(PolyglotException.class, () -> fn.execute(1.5)); assertThrows(PolyglotException.class, () -> fn.execute());
        }
    }
    @Test void transportRejectsAmbiguityAndRoundTripsControlCharacters() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"x\":1,\"x\":2}"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[01]"));
        var value = map("text", "\"hello\"\n\\\t\u0000λ", "values", list(1L, true, null));
        assertEquals(value, Json.parse(Json.stringify(value)));
    }
}
