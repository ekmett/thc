// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import thc.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarValueTestSupport.*;

class ProxyVoidTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final Map<String, Object> voidProof = map("kind", "void", "primReps", list(), "evaluated", true);
    private final Map<String, Object> empty = map("kind", "unknown", "primReps", list(), "evaluated", true,
        "aggregate", "unboxed-tuple", "components", list());
    private Map<String, Object> module(String stage) throws Exception {
        return object(Json.parse(Files.readString(root.resolve("build/proxy-void/" + stage + "/core/ProxyVoidAudit.json"))));
    }
    private List<Map<String, Object>> bindings(Map<String, Object> module) { return objects(module.get("bindings")); }
    private Map<String, Object> binding(Map<String, Object> module, String name) {
        var selected = bindings(module).stream().filter(value -> name.equals(value.get("name"))).toList();
        assertEquals(1, selected.size()); return selected.getFirst();
    }
    private List<Object> lambda(Map<String, Object> module, String name) { return expression(binding(module, name).get("expr")); }
    private List<List<?>> nodes(Object value) {
        var result = new ArrayList<List<?>>();
        if (value instanceof List<?> values) { result.add(values); for (var item : values) result.addAll(nodes(item)); }
        else if (value instanceof Map<?, ?> map) for (var item : map.values()) result.addAll(nodes(item));
        return result;
    }
    private Context context(boolean inlining) {
        return Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
            .option("compiler.Inlining", Boolean.toString(inlining)).build();
    }
    private ExecutableProgram program(Language language, Map<String, Object> module, String backend) {
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }
    private void valid(RootCallTarget target, String label) throws Exception {
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label);
    }
    private void compile(RootCallTarget target) throws Exception {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target, "installed");
    }
    private long count(ExecutableProgram program) { return ((Number) program.diagnostics().get("compiledEntries")).longValue(); }
    private void released(Language language) {
        var state = language.getHandoffState().get();
        assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getResults().getDepth());
        assertEquals(0, state.getArguments().retainedReferences()); assertEquals(0, state.getResults().retainedReferences());
    }
    @BeforeEach void evidence() throws Exception {
        var manifest = object(Json.parse(Files.readString(root.resolve("build/proxy-void/manifest.json"))));
        assertEquals(25L, ((Number) manifest.get("nativeRows")).longValue());
        for (var kind : list("inputHashes", "artifactHashes")) for (var record : object(manifest.get(kind)).entrySet()) {
            var actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(record.getKey()))));
            assertEquals(record.getValue(), actual, "Stale proxy evidence: " + record.getKey());
        }
        for (var stage : list("pre", "post")) {
            var audit = object(Json.parse(Files.readString(root.resolve("build/proxy-void/" + stage + "/audit.json"))));
            assertEquals(true, audit.get("accepted"));
            for (var key : list("missingGlobals", "issues", "runtimeExternals")) assertEquals(list(), audit.get(key));
        }
    }
    @Test void nativeValuesInlined() throws Exception { nativeValues(true); }
    @Test void nativeValuesAcrossResidualCalls() throws Exception { nativeValues(false); }
    private void nativeValues(boolean inlining) throws Exception {
        var rows = new LinkedHashMap<String, List<List<String>>>();
        for (var line : Files.readAllLines(root.resolve("build/proxy-void/oracle.tsv"))) {
            var row = Arrays.asList(line.split("\t", -1)); rows.computeIfAbsent(row.getFirst(), ignored -> new ArrayList<>()).add(row);
        }
        for (var stage : list("pre", "post")) for (var backend : list("ast", "bytecode"))
            for (var group : rows.entrySet()) try (var context = context(inlining)) {
                var name = group.getKey(); var selected = group.getValue();
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var program = program(language, with(CoreModules.reachable(module(stage), name), "instrument", true), backend);
                    var function = context.asValue(new EntryValue(program, name, 1));
                    var host = program.hostEntryTarget(1); var original = program.entryTarget(name);
                    var label = stage + "/" + backend + "/" + name + "/inlining=" + inlining;
                    CheckedConsumer<List<String>> check = row -> assertEquals(Long.parseLong(row.get(2)), function.execute(Long.parseLong(row.get(1))).asLong(), label);
                    for (var row : selected) check.accept(row);
                    var targets = activeTargets(host);
                    assertEquals(name.equals("direct") ? 5 : 4, targets.size(), label + " adopted roots including host");
                    for (var target : targets) if (target != host) compile(target);
                    assertTrue(function.invokeMember("compile").asBoolean());
                    for (var row : selected.reversed()) {
                        long before = count(program); check.accept(row);
                        assertEquals(before + (name.equals("direct") ? 4 : 3), count(program), label + " first/subsequent compiled entry");
                        assertEquals(targets, activeTargets(host), label + " stable adopted targets");
                        valid(original, label); for (var target : targets) valid(target, label); released(language);
                    }
                    if (name.equals("effect")) {
                        var thrown = assertThrows(GuestException.class, () -> Calls.target(host, new Object[]{program.entryValue(name), new Object[]{-1L}}));
                        assertEquals("main:ProxyVoidAudit.Failure", ((DataValue) thrown.getPayload()).getLayout().getId()); released(language);
                    }
                    assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
                    assertEquals(0L, ((Number) program.diagnostics().get("blackholes")).longValue());
                } finally { context.leave(); }
            }
    }
    @Test void ghcKeepsProxyScalarVoidDistinctFromEmptyTuple() throws Exception {
        for (var stage : list("pre", "post")) {
            var module = module(stage);
            assertEquals(stage.equals("pre") ? "optimized-Core-before-Tidy" : "optimized-Core-after-Tidy-before-CorePrep", module.get("boundary"));
            assertTrue(((String) module.get("sourceCore")).contains("proxy#"));
            var token = lambda(module, "token"); var arguments = objects(token.get(1));
            assertEquals(1, arguments.size()); assertEquals(empty, arguments.getFirst().get("rep"));
            assertEquals(voidProof, object(token.get(3)).get("resultRep")); assertEquals("void", expression(token.get(2)).getFirst());
            var lowered = nodes(module.get("bindings")).stream().filter(node -> !node.isEmpty() && "void".equals(node.getFirst())).toList();
            assertEquals(4, lowered.size(), "token, through's type application, pair, required");
            for (var node : lowered) assertEquals(voidProof, object(node.getLast()).get("rep"));
            assertFalse(nodes(module.get("bindings")).stream().anyMatch(node -> node.size() >= 2 && node.subList(0, 2).equals(list("var", "ghc-internal:GHC.Internal.Prim.proxy#"))));
            var result = object(object(lambda(module, "pair").get(3)).get("resultRep"));
            var components = expression(result.get("components"));
            assertEquals(voidProof, components.get(0)); assertEquals(empty, components.get(1));
            assertEquals(list("IntRep"), result.get("primReps"));
            assertFalse(TupleShape.compatible(CoreRepresentations.parse(components.get(0)), CoreRepresentations.parse(components.get(1))));
            var required = binding(module, "required").get("id");
            assertTrue(nodes(lambda(module, "effect")).stream().anyMatch(node -> node.size() >= 2 && node.subList(0, 2).equals(list("var", required))), "Retain the effectful producer");
        }
    }
    @Test void sameWidthEmptyTupleCannotReplaceProxyField() throws Exception {
        for (var stage : list("pre", "post")) for (var backend : list("ast", "bytecode")) try (var context = context(true)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var module = module(stage); var proof = object(object(lambda(module, "pair").get(3)).get("resultRep"));
                expression(proof.get("components")).set(0, empty);
                assertThrows(RuntimeFault.class, () -> program(language, CoreModules.reachable(module, "tupleCase"), backend));
            } finally { context.leave(); }
        }
    }
}
