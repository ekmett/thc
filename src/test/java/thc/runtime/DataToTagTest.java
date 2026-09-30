// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.nodes.RootNode;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.graalvm.polyglot.*;
import org.junit.jupiter.api.Test;
import thc.*;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarValueTestSupport.*;

class DataToTagTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private Context context() { return context(true); }
    private Context context(boolean inlining) {
        return Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw").option("compiler.Inlining", Boolean.toString(inlining)).build();
    }
    private Map<String, Object> module(String stage) throws Exception { return CoreCbdFixtures.read(root.resolve("build/data-to-tag/" + stage + "/core/DataToTagAudit.cbd")); }
    private ExecutableProgram program(Language language, Map<String, Object> module, String backend) {
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }
    private void valid(RootCallTarget target, String label) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label); }
    private void compile(RootCallTarget target) throws Exception { target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target, "installed"); }
    private long count(ExecutableProgram program) { return ((Number) program.diagnostics().get("compiledEntries")).longValue(); }
    private void released(Language language) {
        var state = language.getHandoffState().get();
        assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getResults().getDepth());
        assertEquals(0, state.getArguments().retainedReferences()); assertEquals(0, state.getResults().retainedReferences());
    }
    private Map<String, Object> evidence() throws Exception {
        var manifest = object(Json.parse(Files.readString(root.resolve("build/data-to-tag/manifest.json"))));
        for (var key : list("inputHashes", "artifactHashes")) for (var entry : object(manifest.get(key)).entrySet()) {
            var actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(entry.getKey()))));
            assertEquals(entry.getValue(), actual, "Stale dataToTag evidence: " + entry.getKey());
        }
        assertEquals(293L, manifest.get("nativeRows")); assertEquals(1L, manifest.get("nativeExceptionRows")); return manifest;
    }
    @Test void nativeFamiliesWithInlining() throws Exception { nativeFamilies(true); }
    @Test void nativeFamiliesAcrossResidualCalls() throws Exception { nativeFamilies(false); }
    private void check(Value function, List<String> row, String label) { assertEquals(Long.parseLong(row.get(2)), function.execute(Long.parseLong(row.get(1))).asLong(), label + "/" + row.get(1)); }
    private void nativeFamilies(boolean inlining) throws Exception {
        evidence();
        var rows = new LinkedHashMap<String, List<List<String>>>();
        for (var line : Files.readAllLines(root.resolve("build/data-to-tag/oracle.tsv"))) {
            var row = Arrays.asList(line.split("\t", -1)); rows.computeIfAbsent(row.getFirst(), ignored -> new ArrayList<>()).add(row);
        }
        assertEquals(293, rows.values().stream().mapToInt(List::size).sum());
        for (var stage : list("pre", "post")) for (var backend : list("ast", "bytecode")) for (var entry : rows.entrySet())
            try (var context = context(inlining)) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var name = entry.getKey(); var selected = entry.getValue(); var source = module(stage);
                    var entryId = source.get("unit") + ":" + source.get("module") + "." + name;
                    var program = program(language, with(CoreModules.reachable(source, entryId), "instrument", true), backend);
                    var original = program.entryTarget(entryId); var host = program.hostEntryTarget(1); var function = context.asValue(new EntryValue(program, entryId, 1));
                    var label = stage + "/" + backend + "/" + name + "/inlining=" + inlining;
                    for (var row : selected) check(function, row, label);
                    var targets = activeTargets(host); assertTrue(targets.size() > 1, label + " observed guest target");
                    for (var target : targets) if (target != host) compile(target);
                    assertTrue(function.invokeMember("compile").asBoolean());
                    for (int i = selected.size() - 1; i >= 0; i--) {
                        var row = selected.get(i); long before = count(program); check(function, row, label);
                        assertTrue(count(program) > before, label + "/" + row.get(1) + " compiled guest entry");
                        assertEquals(targets, activeTargets(host), label + " target identities");
                        valid(original, label + " original"); for (var target : targets) valid(target, label + " active"); released(language);
                    }
                    assertEquals(0L, ((Number) program.diagnostics().get("blackholes")).longValue());
                    assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
                    if (name.equals("forceCase")) {
                        assertThrows(PolyglotException.class, () -> function.execute(-1L));
                        assertEquals(1L, ((Number) program.diagnostics().get("blackholes")).longValue()); released(language);
                    }
                } finally { context.leave(); }
            }
    }
    private final Map<String, Object> data = map("kind", "data", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", false);
    private final Map<String, Object> wide = map("kind", "long", "primReps", list("IntRep"), "evaluated", true);
    private final Map<String, Object> closure = with(data, "kind", "closure", "evaluated", true);
    private Map<String, Object> synthetic() {
        var family = map("typeConstructor", "test:T", "constructors", list("A", "B"), "smallFamilyLimit", 7, "smallFamily", true);
        var field = with(data, "kind", "object"); var cons = new ArrayList<Map<String, Object>>();
        var ids = list("A", "B");
        for (int index = 0; index < ids.size(); index++) {
            var id = ids.get(index);
            cons.add(map("id", id, "name", id, "arity", 1, "tag", index + 1, "kind", "boxed",
                "fieldReps", list(list("BoxedRep (Just Lifted)")), "fieldTypes", list(field), "fieldLifted", list(true),
                "strictFields", list(false), "dataToTagFamily", family));
        }
        var app = list("app", list("prim", "dataToTagSmall#", map("rep", closure)),
            list(list("var", "x", map("rep", data))), list(true), false, false, map("rep", wide, "dataToTagFamily", family));
        var binding = map("id", "tag", "name", "tag", "lifted", true, "rep", closure,
            "expr", list("lam", list(map("id", "x", "name", "x", "lifted", true, "rep", data)), app, map("rep", closure, "resultRep", wide)));
        var bottom = map("id", "bottom", "name", "bottom", "lifted", true, "rep", field, "expr", list("var", "bottom", map("rep", field)));
        var bindings = new ArrayList<Map<String, Object>>(list(binding, bottom));
        for (var id : ids) bindings.add(map("id", "value" + id, "name", "value" + id, "lifted", true, "rep", data,
            "expr", list("app", list("con", id, 1), list(list("var", "bottom", map("rep", field))), list(true), true, true, map("rep", data))));
        return object(Json.parse(Json.stringify(map("schema", 1, "ghc", "9.14.1", "bindings", bindings, "constructors", cons, "instrument", true))));
    }
    private List<Object> app(Map<String, Object> module) { return expression(expression(objects(module.get("bindings")).getFirst().get("expr")).get(2)); }
    @Test void demandedOuterValueLeavesFieldsLazyAndReturnsExactCompiledTags() throws Exception {
        for (var backend : list("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = program(language, synthetic(), backend); var target = program.entryTarget("tag");
                var values = new ArrayList<DataValue>();
                for (var id : list("A", "B")) values.add((DataValue) Calls.target(program.hostEntryTarget(0), new Object[]{program.entryValue("value" + id), new Object[0]}));
                for (int index = 0; index < values.size(); index++) assertEquals((long) index, Calls.target(target, new Object[]{0L, values.get(index)}));
                compile(target);
                for (int index = 0; index < values.size(); index++) {
                    long before = count(program); var value = values.get(index); assertEquals((long) index, Calls.target(target, new Object[]{0L, value}));
                    assertEquals(before + 1, count(program)); valid(target, backend);
                    var payload = (Thunk) value.getLayout().read(value, 0); assertEquals(0, payload.getState());
                }
                int[] demanded = {0};
                var thunk = new Thunk(new RootNode(null) { @Override public Object execute(VirtualFrame frame) { demanded[0]++; return values.get(1); } }.getCallTarget(), null);
                assertEquals(1L, Calls.target(target, new Object[]{0L, thunk})); assertEquals(1, demanded[0]);
                assertEquals(1L, Calls.target(target, new Object[]{0L, thunk})); assertEquals(1, demanded[0]);
                var failure = new Thunk(new RootNode(null) { @Override public Object execute(VirtualFrame frame) { throw new RuntimeFault("demanded bottom"); } }.getCallTarget(), null);
                assertThrows(RuntimeFault.class, () -> Calls.target(target, new Object[]{0L, failure})); released(language);
                assertEquals(0L, Calls.target(target, new Object[]{0L, values.getFirst()}));
                var other = new DataLayout(language, "Other", "Other", new String[0]).allocate();
                for (var value : list(17L, thc.runtime.Unit.INSTANCE, ManagedAddress.fromHex("6100"), other))
                    assertThrows(RuntimeException.class, () -> Calls.target(target, new Object[]{0L, value}));
                released(language);
            } finally { context.leave(); }
        }
    }
    @Test void exactFamilyVariantAndMetadataRequiredAtLoad() {
        for (var backend : list("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var variant : list("missing", "variant", "small", "limit", "float-limit", "empty", "reverse", "missing-con", "tag", "float-tag", "newtype", "fields", "truncated", "extra", "closure", "word", "generic", "lifted", "result", "function", "zero", "two", "bare")) {
                    var module = synthetic(); var app = app(module); var metadata = object(app.get(6));
                    var family = object(metadata.get("dataToTagFamily")); var cons = objects(module.get("constructors"));
                    switch (variant) {
                        case "missing" -> metadata.remove("dataToTagFamily");
                        case "variant" -> expression(app.get(1)).set(1, "dataToTagLarge#");
                        case "small" -> family.put("smallFamily", false);
                        case "limit" -> family.put("smallFamilyLimit", 3L);
                        case "float-limit" -> family.put("smallFamilyLimit", 7.0);
                        case "empty" -> family.put("constructors", list());
                        case "reverse" -> Collections.reverse(expression(family.get("constructors")));
                        case "missing-con" -> cons.remove(1);
                        case "tag" -> cons.getFirst().put("tag", 2L);
                        case "float-tag" -> cons.getFirst().put("tag", 1.0);
                        case "newtype" -> cons.getFirst().put("kind", "newtype");
                        case "fields" -> cons.getFirst().put("fieldReps", list());
                        case "truncated" -> { family.put("constructors", list("A")); cons.getFirst().put("dataToTagFamily", new LinkedHashMap<>(family)); }
                        case "extra" -> cons.add(with(cons.getFirst(), "id", "Extra"));
                        case "closure", "generic", "word" -> {
                            var proof = switch (variant) { case "closure" -> closure; case "generic" -> with(data, "primReps", list("BoxedRep Nothing")); default -> with(wide, "primReps", list("WordRep")); };
                            object(expression(expression(app.get(2)).getFirst()).get(2)).put("rep", proof);
                            if (variant.equals("generic")) objects(expression(objects(module.get("bindings")).getFirst().get("expr")).get(1)).getFirst().put("rep", proof);
                        }
                        case "lifted" -> app.set(3, list(false));
                        case "result" -> metadata.put("rep", with(wide, "primReps", list("WordRep")));
                        case "function" -> expression(app.get(1)).set(2, map("rep", list()));
                        case "zero" -> { app.set(2, list()); app.set(3, list()); }
                        case "two" -> { var twice = new ArrayList<>(expression(app.get(2))); twice.addAll(expression(app.get(2))); app.set(2, twice); app.set(3, list(true, true)); }
                        case "bare" -> expression(objects(module.get("bindings")).getFirst().get("expr")).set(2, app.get(1));
                        default -> throw new AssertionError(variant);
                    }
                    assertThrows(RuntimeException.class, () -> program(language, module, backend), backend + "/" + variant);
                }
                for (var proof : list(null, map("kind", "unknown", "primReps", null, "evaluated", false), with(data, "kind", "object", "primReps", list("BoxedRep Nothing")))) {
                    var module = synthetic(); var app = app(module);
                    app.set(2, list(proof == null ? list("var", "x") : list("var", "x", map("rep", proof))));
                    var program = program(language, module, backend);
                    var value = Calls.target(program.hostEntryTarget(0), new Object[]{program.entryValue("valueA"), new Object[0]});
                    assertEquals(0L, Calls.target(program.entryTarget("tag"), new Object[]{0L, value}));
                }
            } finally { context.leave(); }
        }
    }
    @Test void genuineInvalidFamilyAndBarePrimitiveFrontiersStayRejected() throws Exception {
        var manifest = evidence();
        for (var stage : list("pre", "post")) for (var backend : list("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var source = module(stage);
                for (var name : expression(manifest.get("frontiers"))) {
                    var entryId = source.get("unit") + ":" + source.get("module") + "." + name;
                    assertTrue(objects(source.get("bindings")).stream().anyMatch(binding -> entryId.equals(binding.get("id"))), entryId);
                    assertThrows(RuntimeException.class, () -> program(language, CoreModules.reachable(source, entryId), backend), stage + "/" + backend + "/" + name);
                }
            } finally { context.leave(); }
        }
    }
    @Test void typedDataOperandExecutesExactlyOnceBeforeTagSelection() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var layout = new DataLayout(language, "A", "A", new String[0]); var value = layout.allocate(); int[] calls = {0}; boolean[] fail = {false};
                var operand = new Expr() {
                    @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("Generic operand path must not execute"); }
                    @Override public DataValue executeDataValue(VirtualFrame frame) { calls[0]++; if (fail[0]) throw new RuntimeFault("operand failed"); return value; }
                };
                var node = new DataToTag(new DataTagFamily(new DataLayout[]{layout}), operand);
                var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], FrameDescriptor.newBuilder().build());
                assertEquals(0L, node.executeLong(frame)); assertEquals(1, calls[0]);
                fail[0] = true; assertThrows(RuntimeFault.class, () -> node.executeLong(frame)); assertEquals(2, calls[0]);
            } finally { context.leave(); }
        }
    }
}
