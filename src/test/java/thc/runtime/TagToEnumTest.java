// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.graalvm.polyglot.*;
import org.junit.jupiter.api.Test;
import thc.*;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarValueTestSupport.*;

class TagToEnumTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private Context context() { return context(true); }
    private Context context(boolean inlining) {
        return Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw").option("compiler.Inlining", Boolean.toString(inlining)).build();
    }
    private Map<String, Object> module(String stage) throws Exception {
        var modules = new ArrayList<Map<String, Object>>();
        for (var name : list("TagToEnumAudit", "TagToEnumExternal"))
            modules.add(object(Json.parse(Files.readString(root.resolve("build/tag-to-enum/" + stage + "-core/" + name + ".json")))));
        return CoreModules.merge(modules);
    }
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
    private void verifyEvidence() throws Exception {
        var evidence = object(Json.parse(Files.readString(root.resolve("build/tag-to-enum/provenance.json"))));
        for (var section : list("sources", "artifacts")) for (var record : objects(evidence.get(section))) {
            var path = (String) record.get("path");
            var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(path))));
            assertEquals(record.get("sha256"), hash, "Stale tagToEnum evidence: " + path);
        }
        for (var stage : list("pre", "post")) assertEquals(true,
            object(Json.parse(Files.readString(root.resolve("build/tag-to-enum/" + stage + "-audit.json")))).get("accepted"));
    }
    @Test void nativeEnumsWithInlining() throws Exception { nativeEnums(true); }
    @Test void nativeEnumsAcrossResidualCalls() throws Exception { nativeEnums(false); }
    private void check(Value function, List<String> row, String label) { assertEquals(Long.parseLong(row.get(2)), function.execute(Long.parseLong(row.get(1))).asLong(), label + "/" + row.get(1)); }
    private void nativeEnums(boolean inlining) throws Exception {
        verifyEvidence();
        var rows = new LinkedHashMap<String, List<List<String>>>();
        for (var line : Files.readAllLines(root.resolve("build/tag-to-enum/oracle.tsv"))) {
            var row = Arrays.asList(line.split("\t", -1)); rows.computeIfAbsent(row.getFirst(), ignored -> new ArrayList<>()).add(row);
        }
        assertEquals(38, rows.values().stream().mapToInt(List::size).sum());
        for (var stage : list("pre", "post")) for (var backend : list("ast", "bytecode")) for (var entry : rows.entrySet())
            try (var context = context(inlining)) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var name = entry.getKey(); var selected = entry.getValue();
                    var program = program(language, with(CoreModules.reachable(module(stage), name), "instrument", true), backend);
                    var function = context.asValue(new EntryValue(program, name, 1)); var host = program.hostEntryTarget(1); var original = program.entryTarget(name);
                    var label = stage + "/" + backend + "/" + name + "/inline=" + inlining;
                    for (var row : selected) check(function, row, label);
                    var targets = activeTargets(host); assertTrue(targets.size() > 1, label + " actual adopted guest target");
                    for (var target : targets) if (target != host) compile(target);
                    assertTrue(function.invokeMember("compile").asBoolean());
                    for (int i = selected.size() - 1; i >= 0; i--) {
                        var row = selected.get(i); long before = count(program); check(function, row, label);
                        assertTrue(count(program) > before, label + "/" + row.get(1) + " actual compiled guest entry");
                        assertEquals(targets, activeTargets(host), label + " active target identities");
                        valid(original, label + " original"); for (var target : targets) valid(target, label + " active"); released(language);
                    }
                    assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
                    assertEquals(0L, ((Number) program.diagnostics().get("blackholes")).longValue());
                } finally { context.leave(); }
            }
    }
    private List<List<Object>> applications(Object value) {
        var result = new ArrayList<List<Object>>();
        if (value instanceof List<?> list) {
            if (!list.isEmpty() && "app".equals(list.getFirst()) && list.size() > 1 && list.get(1) instanceof List<?> head
                    && head.size() >= 2 && head.subList(0, 2).equals(list("prim", "tagToEnum#"))) result.add(expression(value));
            for (var child : list) result.addAll(applications(child));
        } else if (value instanceof Map<?, ?> map) for (var child : map.values()) result.addAll(applications(child));
        return result;
    }
    private List<Object> application(Map<String, Object> module) {
        var applications = applications(module); assertEquals(1, applications.size()); return applications.getFirst();
    }
    private DataValue call(ExecutableProgram program, RootCallTarget host, String name, long tag) {
        return (DataValue) Calls.target(host, new Object[]{program.entryValue(name), new Object[]{tag}});
    }
    @Test void directEnumRootsUseTypedNullaryValuesAndEveryCompiledCallEntersExactlyOnce() throws Exception {
        verifyEvidence();
        for (var stage : list("pre", "post")) for (var backend : list("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var name : list("chooseBool", "chooseOrdering", "chooseColour", "chooseExternal")) {
                    var linked = CoreModules.reachable(module(stage), name);
                    var ids = expression(object(object(application(linked).get(6)).get("enumFamily")).get("constructors"));
                    var program = program(language, with(linked, "instrument", true), backend); var host = program.hostEntryTarget(1); var target = program.entryTarget(name);
                    var values = new ArrayList<DataValue>();
                    for (int tag = 0; tag < ids.size(); tag++) {
                        var value = call(program, host, name, tag); assertEquals(ids.get(tag), value.getLayout().getId()); assertEquals(0, value.getLayout().getArity()); values.add(value);
                    }
                    compile(target); compile(host);
                    for (int tag = ids.size() - 1; tag >= 0; tag--) {
                        long before = count(program); assertSame(values.get(tag), call(program, host, name, tag));
                        assertEquals(before + 1, count(program), stage + "/" + backend + "/" + name + "/" + tag); valid(target, name); valid(host, name);
                    }
                    for (long tag : new long[]{-1L, ids.size(), Long.MIN_VALUE, Long.MAX_VALUE, 1L << 32, (1L << 32) + 1}) {
                        var failure = assertThrows(RuntimeFault.class, () -> call(program, host, name, tag));
                        assertTrue(Objects.requireNonNull(failure.getMessage()).contains("tag out of range: " + tag)); released(language);
                    }
                }
            } finally { context.leave(); }
        }
    }
    @Test void genuineNewtypeResultCastRetainsTheOriginalEnumFamily() throws Exception {
        verifyEvidence();
        for (var stage : list("pre", "post")) for (var backend : list("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var linked = CoreModules.reachable(module(stage), "wrappedColourCase");
                var metadata = object(application(linked).get(6));
                assertEquals("object", object(metadata.get("rep")).get("kind"));
                assertEquals(list("BoxedRep (Just Lifted)"), object(metadata.get("rep")).get("primReps"));
                assertTrue(((String) object(metadata.get("enumFamily")).get("typeConstructor")).endsWith(":TagToEnumAudit.Colour"));
                var program = program(language, linked, backend);
                var function = context.asValue(new EntryValue(program, "wrappedColourCase", 1));
                assertEquals(17L, function.execute(0L).asLong());
                assertEquals(-31L, function.execute(1L).asLong());
                assertEquals(83L, function.execute(2L).asLong());
                released(language);
            } finally { context.leave(); }
        }
    }
    @Test void malformedOrUnsaturatedEnumProofsRejectAtLoad() throws Exception {
        for (var backend : list("ast", "bytecode")) for (var variant : list("missing", "family", "empty", "reverse", "duplicate", "missing-con", "wrong-tag", "float-tag", "fields", "newtype", "truncated-family", "extra-family-member", "word", "unknown", "aggregate", "function-proof", "result", "lifted", "zero", "two", "bare"))
            try (var context = context()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var linked = CoreModules.reachable(module("pre"), "chooseBool"); var app = application(linked); var metadata = object(app.get(6));
                    var family = object(metadata.get("enumFamily")); var ids = expression(family.get("constructors")); var cons = objects(linked.get("constructors"));
                    var matches = cons.stream().filter(c -> Objects.equals(c.get("id"), ids.getFirst())).toList(); assertEquals(1, matches.size()); var con = matches.getFirst();
                    switch (variant) {
                        case "missing" -> metadata.remove("enumFamily");
                        case "family" -> family.put("typeConstructor", "Other");
                        case "empty" -> ids.clear();
                        case "reverse" -> Collections.reverse(ids);
                        case "duplicate" -> ids.set(1, ids.getFirst());
                        case "missing-con" -> cons.remove(con);
                        case "wrong-tag" -> con.put("tag", 2L);
                        case "float-tag" -> con.put("tag", 1.0);
                        case "fields" -> con.put("fieldTypes", list(map("kind", "long", "primReps", list("IntRep"), "evaluated", true)));
                        case "newtype" -> con.put("kind", "newtype");
                        case "truncated-family" -> { family.put("constructors", list(ids.getFirst())); con.put("enumFamily", new LinkedHashMap<>(family)); }
                        case "extra-family-member" -> cons.add(with(con, "id", "Extra"));
                        case "word" -> object(expression(expression(app.get(2)).getFirst()).get(2)).put("rep", map("kind", "long", "primReps", list("WordRep"), "evaluated", true));
                        case "unknown" -> app.set(2, list(list("lit", "int", "0")));
                        case "aggregate" -> metadata.put("rep", map("kind", "unknown", "aggregate", "unboxed-tuple", "components", list(), "primReps", list(), "evaluated", true));
                        case "function-proof" -> expression(app.get(1)).set(2, map("rep", list()));
                        case "result" -> metadata.put("rep", map("kind", "object", "primReps", list("BoxedRep (Just Unlifted)"), "evaluated", false));
                        case "lifted" -> app.set(3, list(true));
                        case "zero" -> { app.set(2, list()); app.set(3, list()); }
                        case "two" -> { var twice = new ArrayList<>(expression(app.get(2))); twice.addAll(expression(app.get(2))); app.set(2, twice); app.set(3, list(false, false)); }
                        case "bare" -> { var bindings = objects(linked.get("bindings")); assertEquals(1, bindings.size()); expression(bindings.getFirst().get("expr")).set(2, app.get(1)); }
                        default -> throw new AssertionError(variant);
                    }
                    var failure = assertThrows(RuntimeException.class, () -> program(language, linked, backend), backend + "/" + variant);
                    assertTrue(failure instanceof RuntimeFault || failure instanceof UnsupportedCore, backend + "/" + variant + " " + failure.getClass());
                    if (variant.equals("truncated-family") || variant.equals("extra-family-member"))
                        assertTrue(Objects.toString(failure.getMessage(), "").contains("Contradictory family record"), backend + "/" + variant + " " + failure.getMessage());
                } finally { context.leave(); }
            }
    }
    @Test void lexicalIntProofRefinesLegacyOrUnknownOccurrencesWithoutGuessingTheFamily() throws Exception {
        for (var backend : list("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var proof : list(null, map("kind", "unknown", "primReps", null, "evaluated", false), map("kind", "unknown", "primReps", list("IntRep"), "evaluated", false))) {
                    var linked = CoreModules.reachable(module("pre"), "chooseBool"); var app = application(linked); var args = expression(app.get(2)); assertEquals(1, args.size());
                    var old = expression(args.getFirst()); var replacement = new ArrayList<>(old.subList(0, 2)); if (proof != null) replacement.add(map("rep", proof)); app.set(2, list(replacement));
                    var program = program(language, linked, backend);
                    var value = (DataValue) Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue("chooseBool"), new Object[]{1L}});
                    assertEquals("True", value.getLayout().getName());
                }
            } finally { context.leave(); }
        }
    }
    @Test void genuineParameterizedAndDataFamilyEnumsRemainRejected() throws Exception {
        for (var stage : list("pre", "post")) for (var backend : list("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var module = object(Json.parse(Files.readString(root.resolve("build/tag-to-enum/" + stage + "-core/TagToEnumFrontier.json"))));
                for (var entry : list("parameterized", "family")) {
                    var failure = assertThrows(RuntimeFault.class, () -> program(language, CoreModules.reachable(module, entry), backend));
                    assertTrue(Objects.requireNonNull(failure.getMessage()).contains("tagToEnum#"));
                }
            } finally { context.leave(); }
        }
    }
    @Test void typedTagOperandExecutesOnceBeforeSelectionAndFailure() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var values = new DataValue[]{new DataLayout(language, "A", "A", new String[0]).allocate(), new DataLayout(language, "B", "B", new String[0]).allocate()};
                int[] calls = {0}; long[] tag = {1L}; boolean[] fail = {false};
                var operand = new Expr() {
                    @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("Object operand path must not execute"); }
                    @Override public long executeLong(VirtualFrame frame) { calls[0]++; if (fail[0]) throw new RuntimeFault("tag operand failed"); return tag[0]; }
                };
                var node = new TagToEnum(new EnumFamily(values), operand); var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], FrameDescriptor.newBuilder().build());
                assertSame(values[1], node.executeDataValue(frame)); assertEquals(1, calls[0]);
                tag[0] = Long.MAX_VALUE; assertThrows(RuntimeFault.class, () -> node.executeDataValue(frame)); assertEquals(2, calls[0]);
                fail[0] = true; var failure = assertThrows(RuntimeFault.class, () -> node.executeDataValue(frame));
                assertEquals("tag operand failed", failure.getMessage()); assertEquals(3, calls[0]);
            } finally { context.leave(); }
        }
    }
}
