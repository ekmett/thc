// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import java.nio.file.*;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.RepresentationTestSupport.*;

class BoxedLexicalProofTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private Map<String, Object> proof(String levity) { return proof(levity, "object", false); }
    private Map<String, Object> proof(String levity, String kind) { return proof(levity, kind, false); }
    private Map<String, Object> proof(String levity, String kind, boolean evaluated) {
        return map("kind", kind, "primReps", list(levity.equals("Nothing") ? "BoxedRep Nothing" : "BoxedRep (Just " + levity + ")"), "evaluated", evaluated);
    }
    private List<Object> variable(String id, Map<String, Object> rep) {
        var value = new ArrayList<Object>(list("var", id));
        if (rep != null) value.add(map("rep", rep));
        return value;
    }
    private Map<String, Object> module(Map<String, Object> stored, Map<String, Object> occurrence) { return module(stored, occurrence, false); }
    private Map<String, Object> module(Map<String, Object> stored, Map<String, Object> occurrence, boolean caseBinder) {
        var binder = map("id", "x", "name", "x", "lifted", true);
        if (stored != null) binder.put("rep", stored);
        var body = !caseBinder ? variable("x", occurrence) : list("case", variable("x", stored), "b",
            list(list("default", null, list(), variable("b", occurrence))), map("rep", occurrence, "binder", map("id", "b", "rep", occurrence)));
        return map("schema", 1, "ghc", "9.14.1", "module", "BoxedLexicalProof", "constructors", list(),
            "bindings", list(map("id", "entry", "name", "entry", "arity", 1, "lifted", true, "expr", list("lam", list(binder), body))));
    }
    private ExecutableProgram program(Language language, Map<String, Object> module, String backend) {
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }
    private void visit(CheckedBiConsumer<Language, String> action) throws Exception {
        for (var backend : list("ast", "bytecode")) try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try { action.accept(TruffleLanguage.LanguageReference.create(Language.class).get(null), backend); }
            finally { context.leave(); }
        }
    }
    private record ProofPair(Map<String, Object> stored, Map<String, Object> occurrence) {}
    @Test void contradictoryBoxedOccurrencesAndCaseBindersFailBothLowerers() throws Exception {
        visit((language, backend) -> {
            for (var pair : list(new ProofPair(proof("Lifted", "data"), proof("Unlifted")), new ProofPair(proof("Unlifted", "data"), proof("Lifted"))))
                for (boolean caseBinder : new boolean[]{false, true}) for (boolean diagnostic : new boolean[]{false, true}) {
                    var error = assertThrows(RuntimeFault.class, () -> program(language,
                        with(module(pair.stored(), pair.occurrence(), caseBinder), "diagnosticUnsupported", diagnostic), backend));
                    assertTrue(Objects.toString(error.getMessage(), "").contains("Conflicting Core boxed levity proofs"), error.getMessage());
                }
        });
    }
    @Test void unknownLevityClassAndEvaluatednessRefineIndependently() throws Exception {
        var unknown = CoreRepresentations.INSTANCE.parse(proof("Nothing"));
        for (var levity : list("Lifted", "Unlifted")) for (var kind : list("object", "data", "closure")) {
            var exact = CoreRepresentations.INSTANCE.parse(proof(levity, kind));
            for (var merged : list(exact.refine(unknown), unknown.refine(exact), exact.refine(CoreRepresentation.Companion.getUNKNOWN()), CoreRepresentation.Companion.getUNKNOWN().refine(exact))) {
                assertEquals(exact.getPrimReps(), merged.getPrimReps()); assertEquals(exact.getKind(), merged.getKind());
                assertFalse(merged.getEvaluated(), "Known levity does not manufacture evaluatedness");
                assertThrows(RuntimeFault.class, () -> merged.refine(CoreRepresentations.INSTANCE.parse(proof(levity.equals("Lifted") ? "Unlifted" : "Lifted"))));
            }
            var evaluated = exact.refine(CoreRepresentations.INSTANCE.parse(proof("Nothing", "object", true)));
            assertEquals(exact.getPrimReps(), evaluated.getPrimReps()); assertTrue(evaluated.getEvaluated());
            for (var otherKind : list("object", "data", "closure")) {
                var other = CoreRepresentations.INSTANCE.parse(proof(levity, otherKind)); var merged = exact.refine(other);
                assertEquals(other.getKind() == CoreKind.OBJECT ? exact.getKind() : other.getKind(), merged.getKind());
                assertEquals(exact.getPrimReps(), merged.getPrimReps()); assertFalse(merged.getEvaluated());
            }
        }
        visit((language, backend) -> {
            var exact = proof("Lifted"); var legacy = map("kind", "unknown", "primReps", null, "evaluated", false);
            for (var pair : list(new ProofPair(null, exact), new ProofPair(exact, null), new ProofPair(legacy, exact), new ProofPair(exact, legacy),
                    new ProofPair(proof("Nothing"), exact), new ProofPair(exact, proof("Nothing")), new ProofPair(exact, exact))) {
                var program = program(language, module(pair.stored(), pair.occurrence()), backend); var token = new Object();
                assertSame(token, Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue("entry"), new Object[]{token}}));
            }
        });
    }
    @Test void genuineBoxedNewtypeCastsPreserveLevityAndLazyFieldsInCompiledCode() throws Exception {
        visit((language, backend) -> {
            for (var stage : list("core", "cbv-post-core")) {
                var input = object(Json.INSTANCE.parse(Files.readString(root.resolve("build/" + stage + "/CBVCoercionAudit.json"))));
                var bindings = new LinkedHashMap<String, Map<String, Object>>();
                for (var binding : objects(input.get("bindings"))) bindings.put((String) binding.get("name"), binding);
                for (var pair : list(new String[]{"wrapSpine", "Lifted"}, new String[]{"unwrapSpine", "Lifted"}, new String[]{"wrapProduct", "Unlifted"}, new String[]{"unwrapProduct", "Unlifted"})) {
                    var name = pair[0]; var levity = pair[1]; var lambda = expression(Objects.requireNonNull(bindings.get(name)).get("expr"));
                    var parameters = objects(lambda.get(1)); assertEquals(1, parameters.size());
                    var binder = CoreRepresentations.INSTANCE.binder(parameters.getFirst()); var body = expression(lambda.get(2));
                    assertEquals("var", body.getFirst(), "Genuine newtype casts must erase to the lexical value");
                    var occurrence = CoreRepresentations.INSTANCE.expression(body);
                    assertEquals(list("BoxedRep (Just " + levity + ")"), binder.getPrimReps()); assertEquals(binder.getPrimReps(), occurrence.getPrimReps());
                    // GHC can cast the function while its inner lambda retains the source representation.
                    assertEquals(name.startsWith("unwrap") ? CoreKind.OBJECT : CoreKind.DATA, binder.getKind());
                }
                for (var name : list("boxedCastEntry", "unliftedBoxedCastEntry")) {
                    var program = program(language, CoreModules.INSTANCE.reachable(input, name, false), backend);
                    var host = program.hostEntryTarget(1); var entry = program.entryValue(name);
                    long[] values = {Long.MIN_VALUE, -4097L, -1L, 0L, 1L, 4097L, Long.MAX_VALUE};
                    for (long x : values) assertEquals(x, Calls.target(host, new Object[]{entry, new Object[]{x}}));
                    var target = program.entryTarget(name); var targetClass = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
                    targetClass.getMethod("compile", boolean.class).invoke(target, true);
                    assertEquals(true, targetClass.getMethod("isValidLastTier").invoke(target));
                    for (int i = values.length - 1; i >= 0; i--) {
                        long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                        assertEquals(values[i], Calls.target(host, new Object[]{entry, new Object[]{values[i]}}));
                        // Opaque helpers contribute entries too after guest inlining.
                        assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before);
                        assertEquals(true, targetClass.getMethod("isValidLastTier").invoke(target));
                    }
                    assertEquals(true, targetClass.getMethod("isValidLastTier").invoke(target));
                    assertEquals(0L, ((Number) program.diagnostics().get("blackholes")).longValue());
                }
            }
        });
    }
}
